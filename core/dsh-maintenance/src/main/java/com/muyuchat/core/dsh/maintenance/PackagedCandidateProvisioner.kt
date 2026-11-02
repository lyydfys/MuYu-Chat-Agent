package com.muyuchat.core.dsh.maintenance

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Identity of the payload this APK ships, as verified by the runtime's payload installer.
 *
 * The app composition supplies it from the APK's signed manifest; the provisioner never reads the
 * APK itself, so it cannot invent an identity it did not receive.
 */
data class DshPackagedPayloadIdentity(
    val payloadVersion: String,
    val archiveSha256: String,
    val fileCount: Long
)

/** Outcome of looking for an upgrade candidate inside the currently installed payload. */
sealed interface DshProvisionResult {
    /** Nothing to stage: no closure yet, identities match, another candidate exists, or a guard failed. */
    data object NoChange : DshProvisionResult

    /** A verified candidate directory now exists below `<closure>/incoming`. */
    data class Staged(val candidateId: String) : DshProvisionResult

    /** A candidate was attempted but could not be staged; the console keeps its previous state. */
    data class Refused(val detail: String) : DshProvisionResult
}

/**
 * Stages the payload bundled with the installed APK as a closure upgrade candidate when (and only
 * when) the active closure is serving a *different* payload.
 *
 * This is production code, not a test fixture: the descriptor is cloned from the active closure's
 * own `candidate.json` and only the fields that describe the payload are rewritten. Everything
 * else (ABI, data schema, artifact paths, migration flags) is inherited verbatim, so a payload-only
 * update cannot silently change the closure's compatibility claims.
 *
 * The payload tree is materialised with hard links (same filesystem) and falls back to a byte copy.
 * Every failure path is fail-closed: no candidate directory is left behind unless the whole tree
 * and descriptor were written and atomically renamed into `incoming`.
 */
class DshPackagedCandidateProvisioner(
    private val closureRoot: File,
    private val activeRoot: () -> File?,
    private val packagedIdentity: () -> DshPackagedPayloadIdentity?,
    private val installedPayloadDir: () -> File?,
    /**
     * The selector's previous closure. It stays inside `incoming` after a commit but the candidate
     * lookup already refuses to offer it, so it must not count as "another staged candidate".
     */
    private val previousRoot: () -> File? = { null },
    private val clock: () -> Long = System::currentTimeMillis
) {

    fun ensureProvisioned(): DshProvisionResult {
        val active = activeRoot() ?: return DshProvisionResult.NoChange
        val activeDescriptor = File(active, MANIFEST_NAME)
        if (!activeDescriptor.isFile) return DshProvisionResult.NoChange

        val packaged = packagedIdentity() ?: return DshProvisionResult.NoChange
        if (packaged.archiveSha256.length != 64 || packaged.fileCount <= 0L) {
            return DshProvisionResult.NoChange
        }
        val installed = installedPayloadDir()?.takeIf { it.isDirectory }
            ?: return DshProvisionResult.NoChange
        // The installed tree only represents the packaged payload when its marker says so. With an
        // active closure the launcher may skip the packaged install entirely, leaving yesterday's
        // payload on disk; staging it under the new archive identity would describe the wrong bytes.
        val marker = runCatching {
            JSONObject(DshMaintenanceIo.readText(File(installed.parentFile, MARKER_NAME)))
        }.getOrNull() ?: return DshProvisionResult.NoChange
        if (!marker.optString("archiveSha256").equals(packaged.archiveSha256, ignoreCase = true)) {
            return DshProvisionResult.NoChange
        }
        if (marker.optLong("fileCount", -1L) != packaged.fileCount) return DshProvisionResult.NoChange

        val template = runCatching { JSONObject(DshMaintenanceIo.readText(activeDescriptor)) }
            .getOrNull() ?: return DshProvisionResult.NoChange
        val templatePayload = template.optJSONObject("payload")
            ?: return DshProvisionResult.NoChange
        if (templatePayload.optString("root") != "payload") return DshProvisionResult.NoChange
        val activeArchive = templatePayload.optString("archiveSha256")
        // The active closure already serves exactly this payload: nothing to upgrade.
        if (activeArchive.equals(packaged.archiveSha256, ignoreCase = true)) {
            return DshProvisionResult.NoChange
        }

        val incoming = File(closureRoot, "incoming")
        val excluded = listOfNotNull(
            active,
            previousRoot()?.takeIf { it.isAbsolute && it.isDirectory }
        ).mapNotNull { runCatching { it.canonicalPath }.getOrNull() }.toSet()
        val staged = incoming.listFiles()?.filter { child ->
            val canonical = runCatching { child.canonicalPath }.getOrNull()
            child.isDirectory && File(child, MANIFEST_NAME).isFile &&
                (canonical == null || canonical !in excluded)
        }.orEmpty()
        // `DshClosureCandidateSource.lookup()` refuses more than one staged candidate; never add a
        // second one behind its back.
        if (staged.isNotEmpty()) return DshProvisionResult.NoChange

        val candidateId = candidateId(packaged) ?: return DshProvisionResult.NoChange
        val target = File(incoming, candidateId)
        if (target.exists()) return DshProvisionResult.NoChange
        if (!incoming.isDirectory && !incoming.mkdirs()) {
            return DshProvisionResult.Refused("the closure staging directory could not be created")
        }

        val staging = File(closureRoot, ".staging-" + UUID.randomUUID())
        return try {
            if (!staging.mkdirs()) {
                return DshProvisionResult.Refused("the candidate staging directory could not be created")
            }
            // Inherit every identity artifact (source/node/nativeAbi/addons/...) from the active
            // closure by hard link, so the staged candidate is a complete closure and not just a
            // payload. The descriptor is rewritten below; drop its inherited link first so the
            // write cannot touch the active closure's file through the link.
            linkTree(active, staging)
            File(staging, MANIFEST_NAME).delete()
            val payload = File(staging, "payload")
            if (payload.exists() && !payload.deleteRecursively()) {
                staging.deleteRecursively()
                return DshProvisionResult.Refused("the inherited payload tree could not be detached")
            }
            if (!payload.mkdirs()) {
                staging.deleteRecursively()
                return DshProvisionResult.Refused("the candidate payload directory could not be created")
            }
            linkTree(installed, payload)
            val descriptor = rewriteDescriptor(template, candidateId, packaged, payload) ?: run {
                staging.deleteRecursively()
                return DshProvisionResult.Refused(
                    "the packaged payload could not be described from the active closure"
                )
            }
            DshMaintenanceIo.writeDurably(
                File(staging, MANIFEST_NAME),
                descriptor.toString().toByteArray(Charsets.UTF_8)
            )
            Files.move(
                staging.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )
            DshProvisionResult.Staged(candidateId)
        } catch (t: Throwable) {
            runCatching { staging.deleteRecursively() }
            runCatching { target.deleteRecursively() }
            DshProvisionResult.Refused("candidate staging failed: " + (t.message ?: t.javaClass.simpleName).take(160))
        }
    }

    /**
     * Rewrites only the payload description. Returns null when the installed tree cannot satisfy the
     * active descriptor (missing kernel entry or critical file), which keeps the console unchanged.
     */
    private fun rewriteDescriptor(
        template: JSONObject,
        candidateId: String,
        packaged: DshPackagedPayloadIdentity,
        payload: File
    ): JSONObject? {
        val descriptor = JSONObject(template.toString())
        descriptor.put("candidateId", candidateId)
        descriptor.put("stagedAt", clock())
        val payloadBlock = descriptor.optJSONObject("payload") ?: return null
        payloadBlock.put("archiveSha256", packaged.archiveSha256.lowercase())
        payloadBlock.put("fileCount", packaged.fileCount)
        val kernelEntry = payloadBlock.optString("kernelEntry")
        if (kernelEntry.isBlank() || !File(payload, kernelEntry).isFile) return null
        val critical = payloadBlock.optJSONArray("criticalFiles") ?: JSONArray()
        for (index in 0 until critical.length()) {
            val row = critical.optJSONObject(index) ?: return null
            val path = row.optString("path")
            if (path.isBlank() || !DshMaintenanceIo.isSafeRelativePath(path)) return null
            val file = File(payload, path)
            if (!file.isFile || file.length() <= 0L) return null
            row.put("sha256", DshMaintenanceIo.sha256(file))
        }
        payloadBlock.put("criticalFiles", critical)
        return descriptor
    }

    /** Hard-links every regular file (no extra storage); falls back to a copy when linking fails. */
    private fun linkTree(source: File, destination: File) {
        val children = source.listFiles()
            ?: throw java.io.IOException("the installed payload tree is unreadable")
        for (child in children) {
            val target = File(destination, child.name)
            if (child.isDirectory) {
                if (!target.mkdirs()) throw java.io.IOException("a payload directory could not be created")
                linkTree(child, target)
            } else if (child.isFile) {
                val linked = runCatching { Files.createLink(target.toPath(), child.toPath()) }.isSuccess
                if (!linked) {
                    Files.copy(child.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun candidateId(packaged: DshPackagedPayloadIdentity): String? {
        val version = packaged.payloadVersion
            .map { if (it.isLetterOrDigit() || it == '.' || it == '_') it else '-' }
            .joinToString("")
            .trim('-', '.', '_')
            .take(40)
        val suffix = packaged.archiveSha256.take(8).lowercase()
        if (version.isEmpty() || suffix.length != 8) return null
        val id = "pkg-$version-$suffix"
        if (id.length > MAX_CANDIDATE_ID_CHARS) return null
        if (!id.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }) return null
        return id
    }

    private companion object {
        const val MANIFEST_NAME = "candidate.json"
        const val MARKER_NAME = "payload.marker.json"
        const val MAX_CANDIDATE_ID_CHARS = 64
    }
}
