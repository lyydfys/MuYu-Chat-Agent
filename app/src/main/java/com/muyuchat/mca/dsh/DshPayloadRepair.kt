package com.muyuchat.mca.dsh

import com.muyuchat.core.dsh.maintenance.DshMaintenanceIo
import com.muyuchat.core.dsh.maintenance.DshManagedFile
import com.muyuchat.core.dsh.maintenance.DshManagedFileRoot
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream
import org.json.JSONObject

/**
 * Managed repair inventory derived from the payload manifest that ships inside this APK.
 *
 * The build already trusts one exact source of truth for "which bytes must the official payload
 * contain": `dsh/MANIFEST.json` (`archive.sha256`, `criticalFiles[]`). The repair inventory is a
 * strict, bounded projection of that manifest — never a hand-written list and never a directory
 * scan, so a file the build did not declare can never be replaced by a self-check repair.
 *
 * Rules:
 *  - only payload-relative paths are accepted (safe relative path, no `..`, no drive/colon), so a
 *    crafted manifest entry cannot escape the payload root;
 *  - the expected hash must be a full 64-character SHA-256, otherwise the entry is dropped (a
 *    partial or empty hash would make "mismatch" meaningless);
 *  - the list is deduplicated and truncated to [MAX_MANAGED_FILES], so one self-check transaction
 *    stays bounded even if a future manifest grows without limit;
 *  - sessions, attachments, credentials and the tool workspace stay protected by the policy; this
 *    projection only ever yields payload-internal build artifacts.
 */
internal object DshPayloadCriticalFiles {

    /** Enough for the kernel, profile template and runtime libraries; far below any payload size. */
    const val MAX_MANAGED_FILES = 24

    /** Parses the packaged manifest text; an unreadable/malformed manifest yields no inventory. */
    fun parse(manifestJson: String?): List<DshManagedFile> {
        val text = manifestJson?.takeIf { it.isNotBlank() } ?: return emptyList()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        return parse(json)
    }

    fun parse(manifest: JSONObject): List<DshManagedFile> {
        val files = manifest.optJSONArray("criticalFiles") ?: return emptyList()
        val accepted = ArrayList<DshManagedFile>(files.length())
        val seen = HashSet<String>()
        for (index in 0 until files.length()) {
            if (accepted.size >= MAX_MANAGED_FILES) break
            val entry = files.optJSONObject(index) ?: continue
            val path = entry.optString("path").trim()
            val sha = entry.optString("sha256").trim().lowercase()
            if (!isRepairablePayloadPath(path)) continue
            if (!DshMaintenanceIo.isSha256Hex(sha)) continue
            if (!seen.add(path)) continue
            accepted += DshManagedFile(
                relativePath = path,
                expectedSha256 = sha,
                root = DshManagedFileRoot.PAYLOAD
            )
        }
        return accepted
    }

    /** Payload-internal build artifacts only; user data and the executable Node jniLib are out. */
    fun isRepairablePayloadPath(path: String): Boolean {
        if (path.isEmpty() || path.length > MAX_PATH_CHARS) return false
        if (!DshMaintenanceIo.isSafeRelativePath(path)) return false
        if (path.endsWith("/")) return false
        val first = path.substringBefore('/').lowercase()
        if (first in FORBIDDEN_ROOTS) return false
        return first in ALLOWED_ROOTS
    }

    private const val MAX_PATH_CHARS = 240

    /** Roots this inventory may touch: the official kernel tree and its Node runtime libraries. */
    private val ALLOWED_ROOTS = setOf("dshroot", "runtime", "dshhome", "bin")

    /** Never repairable even if a manifest wrongly lists them. */
    private val FORBIDDEN_ROOTS = setOf(
        "sessions", "sessions-deleted", "attachments", "workspace", "config", "home", "tmp"
    )
}

/**
 * Re-materialises the declared payload files from this APK's own payload archive.
 *
 * A repair needs the exact expected bytes. The payload archive asset is the build's signed input
 * (`archive.sha256` was verified when the tree was installed), so extracting the declared
 * `criticalFiles` entries from it is the only honest source: it cannot be influenced by the current
 * (possibly corrupted) payload tree, and every extracted file is hash-checked against the manifest
 * declaration before it can become a repair source.
 *
 * The result is a fresh work directory laid out exactly like the payload root, so the maintenance
 * policy can resolve `relativePath` against it. Nothing is extracted when any declared entry is
 * missing, oversized or does not match its declared hash: the caller then gets `null` and a typed
 * refusal instead of a half-prepared source.
 */
internal class DshPayloadRepairSource(
    private val openArchive: () -> InputStream?,
    private val workRoot: File
) {
    fun extract(declarations: List<DshManagedFile>): File? {
        val wanted = declarations.filter { it.root == DshManagedFileRoot.PAYLOAD }
        if (wanted.isEmpty()) return null
        val declared = wanted.associateBy { it.relativePath }
        val staging = File(workRoot, "payload-repair-" + System.nanoTime().toString(16))
        if (!staging.isDirectory && !staging.mkdirs() && !staging.isDirectory) return null
        val extracted = HashSet<String>()
        var totalBytes = 0L
        val archive = runCatching { openArchive() }.getOrNull()
            ?: return discard(staging)
        val ok = runCatching {
            ZipInputStream(archive.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val declaration = declared[entry.name.trimStart('/')] ?: run {
                        zip.closeEntry()
                        continue
                    }
                    if (extracted.size >= MAX_FILES || totalBytes >= MAX_TOTAL_BYTES) {
                        zip.closeEntry()
                        return@use false
                    }
                    val target = DshMaintenanceIo.resolveWithin(staging, declaration.relativePath)
                        ?: return@use false
                    target.parentFile?.let { parent ->
                        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) return@use false
                    }
                    val written = copyBounded(zip, target)
                    if (written <= 0L || written > MAX_FILE_BYTES) return@use false
                    totalBytes += written
                    if (!DshMaintenanceIo.sha256(target).equals(
                            declaration.expectedSha256,
                            ignoreCase = true
                        )
                    ) {
                        return@use false
                    }
                    extracted += declaration.relativePath
                    zip.closeEntry()
                    if (extracted.size == declared.size) break
                }
                true
            }
        }.getOrDefault(false)
        if (!ok || extracted.size != declared.size) return discard(staging)
        return staging
    }

    private fun copyBounded(input: InputStream, target: File): Long {
        var written = 0L
        target.outputStream().use { output: OutputStream ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                written += read
                if (written > MAX_FILE_BYTES) return 0L
                output.write(buffer, 0, read)
            }
            output.flush()
        }
        return written
    }

    private fun discard(staging: File): File? {
        runCatching { staging.deleteRecursively() }
        return null
    }

    private companion object {
        const val MAX_FILES = DshPayloadCriticalFiles.MAX_MANAGED_FILES
        const val MAX_FILE_BYTES = 16L * 1024L * 1024L
        const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L
        const val BUFFER_BYTES = 64 * 1024
    }
}
