package com.muyuchat.core.dsh.maintenance

import java.io.File
import org.json.JSONObject

/**
 * Candidate source for the whole-closure upgrade (D24).
 *
 * A candidate closure is staged out of band (installer / engineering console) below
 * `<dataRoot>/closure/incoming/<candidateId>/` with one `candidate.json` describing the identity
 * components and their expected hashes. This object only verifies what it can prove:
 *
 * - exactly one candidate is staged (ambiguity is refused instead of guessed);
 * - the directory stays inside the managed closure root and outside the real home/workspace;
 * - every [DSH_CLOSURE_COMPONENTS] artifact is a regular, non-symlinked file below the candidate;
 * - declared hashes (when present) match the bytes that are actually staged;
 * - the target ABI is supported by this build and the declared data schema is coherent.
 *
 * It never extracts an archive, never writes the candidate tree and never falls back to the
 * currently installed payload: "no candidate" is an honest typed result.
 */
sealed interface DshClosureCandidateLookup {
    data object None : DshClosureCandidateLookup
    data class Available(val candidate: DshUpgradeCandidate) : DshClosureCandidateLookup
    data class Rejected(val failure: DshMaintenanceFailure) : DshClosureCandidateLookup
}

class DshClosureCandidateSource(
    private val closureRoot: File,
    private val supportedAbis: Set<String>,
    /** Roots a candidate may never live in (the real home and workspace). */
    private val protectedRoots: List<File> = emptyList(),
    private val maxArtifactBytes: Long = 2L * 1024L * 1024L * 1024L,
    /**
     * The closure the selector currently serves. A committed candidate keeps living below
     * `incoming`, so without this exclusion a second upgrade could never be staged: every staged
     * tree would count as a second candidate and the lookup would refuse.
     */
    private val activeRoot: () -> File? = { null },
    /**
     * The closure the selector would fall back to. After a commit the previous closure keeps
     * living below `incoming`; offering it as a "new candidate" would present a downgrade as an
     * upgrade, so it is excluded exactly like the active root.
     */
    private val previousRoot: () -> File? = { null }
) {
    private val incomingDir: File get() = File(closureRoot, "incoming")

    /** True when at least one candidate manifest exists; used only for console advertising. */
    fun isPresent(): Boolean {
        val candidates = candidateDirectories() ?: return false
        return candidates.size == 1
    }

    fun lookup(): DshClosureCandidateLookup {
        val candidates = candidateDirectories()
            ?: return DshClosureCandidateLookup.Rejected(
                DshMaintenanceFailure(DshMaintenanceError.IO_FAILURE, "the closure staging directory is unreadable")
            )
        if (candidates.isEmpty()) return DshClosureCandidateLookup.None
        if (candidates.size > 1) {
            return DshClosureCandidateLookup.Rejected(
                DshMaintenanceFailure(
                    DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                    "more than one candidate closure is staged; promote exactly one",
                    detail = "staged=${candidates.size}"
                )
            )
        }
        return inspect(candidates.single())
    }

    private fun candidateDirectories(): List<File>? {
        if (!incomingDir.isDirectory) return emptyList()
        val children = incomingDir.listFiles() ?: return null
        // The selector records the canonical spelling, while `incoming` may be reached through an
        // alias (`/data/user/0/...` vs `/data/data/...`); compare canonical paths so the active
        // closure is recognised and not counted as a second staged candidate.
        val active = runCatching { activeRoot()?.canonicalPath }.getOrNull()
        val previous = runCatching { previousRoot()?.canonicalPath }.getOrNull()
        return children
            .filter { it.isDirectory && File(it, MANIFEST_NAME).isFile }
            .filter { active == null || runCatching { it.canonicalPath }.getOrNull() != active }
            .filter { previous == null || runCatching { it.canonicalPath }.getOrNull() != previous }
            .sortedBy { it.name }
    }

    private fun inspect(directory: File): DshClosureCandidateLookup {
        if (protectedRoots.any { DshMaintenanceIo.relativeWithin(it, directory) != null }) {
            return reject(DshMaintenanceError.PATH_ESCAPE, "a candidate may not be staged inside live user data")
        }
        if (DshMaintenanceIo.relativeWithin(closureRoot, directory) == null) {
            return reject(DshMaintenanceError.PATH_ESCAPE, "candidate staging escapes the closure root")
        }
        val manifestFile = File(directory, MANIFEST_NAME)
        if (manifestFile.length() <= 0L || manifestFile.length() > MAX_MANIFEST_BYTES) {
            return reject(DshMaintenanceError.UPGRADE_INCOMPATIBLE, "candidate manifest is outside the size budget")
        }
        val manifest = runCatching { JSONObject(DshMaintenanceIo.readText(manifestFile)) }.getOrNull()
            ?: return reject(DshMaintenanceError.UPGRADE_INCOMPATIBLE, "candidate manifest is not valid JSON")
        val candidateId = manifest.optString("candidateId", "")
        if (!isOpaqueToken(candidateId)) {
            return reject(DshMaintenanceError.UPGRADE_INCOMPATIBLE, "candidate id is missing or not a stable token")
        }
        if (candidateId != directory.name) {
            return reject(DshMaintenanceError.UPGRADE_INCOMPATIBLE, "candidate id does not match its staging directory")
        }
        val targetAbi = manifest.optString("targetAbi", "")
        if (targetAbi !in supportedAbis) {
            return reject(
                DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                "candidate ABI is not supported by this build",
                targetAbi.take(32)
            )
        }
        val dataSchemaVersion = manifest.optInt("dataSchemaVersion", 0)
        if (dataSchemaVersion < 1) {
            return reject(DshMaintenanceError.UPGRADE_INCOMPATIBLE, "candidate does not declare a data schema")
        }
        val minReadable = manifest.optInt("minReadableDataSchema", 0)
        if (minReadable < 0 || minReadable > dataSchemaVersion) {
            return reject(DshMaintenanceError.UPGRADE_INCOMPATIBLE, "candidate data schema bounds are incoherent")
        }
        val artifactsJson = manifest.optJSONObject("artifacts")
            ?: return reject(DshMaintenanceError.UPGRADE_INCOMPATIBLE, "candidate declares no identity artifacts")
        val hashesJson = manifest.optJSONObject("hashes")
        val artifacts = LinkedHashMap<String, File>()
        val missing = ArrayList<String>()
        for (component in DSH_CLOSURE_COMPONENTS) {
            val relative = artifactsJson.optString(component, "")
            if (relative.isEmpty()) {
                missing.add(component)
                continue
            }
            if (!DshMaintenanceIo.isSafeRelativePath(relative)) {
                return reject(
                    DshMaintenanceError.PATH_ESCAPE,
                    "candidate artifact path is not a safe relative path",
                    component
                )
            }
            val file = File(directory, relative)
            if (DshMaintenanceIo.relativeWithin(directory, file) == null) {
                return reject(DshMaintenanceError.PATH_ESCAPE, "candidate artifact escapes its staging tree", component)
            }
            if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
                return reject(DshMaintenanceError.ARCHIVE_MALICIOUS, "candidate artifact is a symlink", component)
            }
            if (!file.isFile || file.length() <= 0L || file.length() > maxArtifactBytes) {
                return reject(DshMaintenanceError.STAGING_MISSING, "candidate artifact is missing or empty", component)
            }
            if (hashesJson != null && hashesJson.has(component)) {
                val declared = hashesJson.optString(component, "")
                if (!DshMaintenanceIo.isSha256Hex(declared)) {
                    return reject(DshMaintenanceError.UPGRADE_INCOMPATIBLE, "candidate hash is not sha256 hex", component)
                }
                if (!DshMaintenanceIo.sha256(file).equals(declared, ignoreCase = false)) {
                    return reject(
                        DshMaintenanceError.ARCHIVE_INVALID,
                        "candidate artifact does not match its declared hash",
                        component
                    )
                }
            }
            artifacts[component] = file
        }
        if (missing.isNotEmpty()) {
            return reject(
                DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                "candidate closure is incomplete; mixed versions are refused",
                missing.take(6).joinToString(",")
            )
        }
        val payload = when (val verified = verifyPayload(directory, manifest)) {
            is PayloadInspection.Refused -> return reject(verified.code, verified.message, verified.detail)
            is PayloadInspection.Ok -> verified
        }
        return DshClosureCandidateLookup.Available(
            DshUpgradeCandidate(
                candidateId = candidateId,
                stagedRoot = directory,
                artifacts = artifacts,
                targetAbi = targetAbi,
                dataSchemaVersion = dataSchemaVersion,
                minReadableDataSchema = minReadable,
                migratesData = manifest.optBoolean("migratesData", false),
                nativeLibraryReplaced = manifest.optBoolean("nativeLibraryReplaced", false),
                prePackagedSlotVerified = manifest.optBoolean("prePackagedSlotVerified", false),
                abiCompatibleWithPrevious = manifest.optBoolean("abiCompatibleWithPrevious", false),
                payloadRoot = payload.root,
                payloadKernelEntry = payload.kernelEntry,
                payloadCriticalFiles = payload.criticalFiles,
                payloadArchiveSha256 = payload.archiveSha256,
                payloadFileCount = payload.fileCount
            )
        )
    }

    /**
     * Verifies the candidate's *executable* payload, not just its identity descriptors.
     *
     * The staged tree must carry a real payload directory with a kernel entry and at least one
     * declared critical file whose bytes still match. An identity-only candidate is refused: it
     * cannot be launched, so a "successful trial" of it would prove nothing. Hashes are recomputed
     * here, so a candidate whose bytes changed after staging is refused at the point of use.
     */
    private fun verifyPayload(directory: File, manifest: JSONObject): PayloadInspection {
        val payloadJson = manifest.optJSONObject("payload")
            ?: return PayloadInspection.Refused(
                DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                "the candidate carries no executable payload; identity-only candidates cannot be trialled",
                null
            )
        val relativeRoot = payloadJson.optString("root", "")
        if (relativeRoot.isEmpty() || !DshMaintenanceIo.isSafeRelativePath(relativeRoot)) {
            return PayloadInspection.Refused(
                DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                "the candidate payload root is not a safe relative path",
                null
            )
        }
        val root = File(directory, relativeRoot)
        if (DshMaintenanceIo.relativeWithin(directory, root) == null || root == directory) {
            return PayloadInspection.Refused(
                DshMaintenanceError.PATH_ESCAPE,
                "the candidate payload escapes its staging tree",
                null
            )
        }
        if (!root.isDirectory) {
            return PayloadInspection.Refused(
                DshMaintenanceError.STAGING_MISSING,
                "the candidate payload tree is missing",
                null
            )
        }
        val kernelEntry = payloadJson.optString("kernelEntry", "")
        if (!DshMaintenanceIo.isSafeRelativePath(kernelEntry)) {
            return PayloadInspection.Refused(
                DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                "the candidate payload declares no usable kernel entry",
                null
            )
        }
        val kernel = File(root, kernelEntry)
        if (DshMaintenanceIo.relativeWithin(root, kernel) == null ||
            !kernel.isFile || kernel.length() <= 0L
        ) {
            return PayloadInspection.Refused(
                DshMaintenanceError.STAGING_MISSING,
                "the candidate payload kernel entry is missing or empty",
                null
            )
        }
        val archiveSha = payloadJson.optString("archiveSha256", "")
        if (!DshMaintenanceIo.isSha256Hex(archiveSha)) {
            return PayloadInspection.Refused(
                DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                "the candidate payload declares no archive sha256",
                null
            )
        }
        val criticalJson = payloadJson.optJSONArray("criticalFiles")
            ?: return PayloadInspection.Refused(
                DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                "the candidate payload declares no critical files; there is no integrity proof",
                null
            )
        if (criticalJson.length() == 0) {
            return PayloadInspection.Refused(
                DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                "the candidate payload declares no critical files; there is no integrity proof",
                null
            )
        }
        val critical = LinkedHashMap<String, String>()
        for (index in 0 until criticalJson.length()) {
            if (index >= MAX_CRITICAL_FILES) {
                return PayloadInspection.Refused(
                    DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                    "the candidate declares more critical files than a bounded verification allows",
                    "max=$MAX_CRITICAL_FILES"
                )
            }
            val entry = criticalJson.optJSONObject(index) ?: continue
            val path = entry.optString("path", "")
            val sha = entry.optString("sha256", "").lowercase()
            if (!DshMaintenanceIo.isSafeRelativePath(path) || !DshMaintenanceIo.isSha256Hex(sha)) {
                return PayloadInspection.Refused(
                    DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                    "a candidate critical file entry is malformed",
                    null
                )
            }
            val file = File(root, path)
            if (DshMaintenanceIo.relativeWithin(root, file) == null || !file.isFile) {
                return PayloadInspection.Refused(
                    DshMaintenanceError.STAGING_MISSING,
                    "a candidate critical file is missing",
                    path.take(120)
                )
            }
            if (file.length() > maxArtifactBytes) {
                return PayloadInspection.Refused(
                    DshMaintenanceError.UPGRADE_INCOMPATIBLE,
                    "a candidate critical file exceeds the verification budget",
                    path.take(120)
                )
            }
            if (!DshMaintenanceIo.sha256(file).equals(sha, ignoreCase = false)) {
                return PayloadInspection.Refused(
                    DshMaintenanceError.ARCHIVE_INVALID,
                    "a candidate critical file does not match its declared hash",
                    path.take(120)
                )
            }
            critical[path] = sha
        }
        return PayloadInspection.Ok(
            root = root,
            kernelEntry = kernelEntry,
            criticalFiles = critical,
            archiveSha256 = archiveSha,
            fileCount = payloadJson.optLong("fileCount", 0L)
        )
    }

    private sealed interface PayloadInspection {
        data class Ok(
            val root: File,
            val kernelEntry: String,
            val criticalFiles: Map<String, String>,
            val archiveSha256: String,
            val fileCount: Long
        ) : PayloadInspection

        data class Refused(
            val code: DshMaintenanceError,
            val message: String,
            val detail: String?
        ) : PayloadInspection
    }

    private fun reject(
        code: DshMaintenanceError,
        message: String,
        detail: String? = null
    ): DshClosureCandidateLookup.Rejected = DshClosureCandidateLookup.Rejected(
        DshMaintenanceFailure(code, message, retryable = false, detail = detail?.take(200))
    )

    private fun isOpaqueToken(value: String): Boolean =
        value.length in 1..64 && value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }

    private companion object {
        const val MANIFEST_NAME = "candidate.json"
        const val MAX_MANIFEST_BYTES = 64L * 1024L
        const val MAX_CRITICAL_FILES = 64
    }
}
