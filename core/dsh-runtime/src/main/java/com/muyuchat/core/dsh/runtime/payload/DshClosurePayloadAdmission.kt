package com.muyuchat.core.dsh.runtime.payload

import java.io.File
import java.security.MessageDigest

/**
 * Bounded admission of a committed closure `payload` tree for the production generation.
 *
 * The maintenance owner verifies a candidate archive, tries it in isolation and commits its
 * identity. This object is the runtime's own gate before a closure tree is executed: the tree must
 * live under the installation's closure store, must carry a candidate descriptor whose
 * `payload.root` names it `payload`, and every critical file that descriptor lists must hash to
 * the recorded value, with the kernel entry present. Anything else is refused by name; no process
 * is started and no path outside the store is ever read.
 */
object DshClosurePayloadAdmission {

    sealed interface Result {
        /** The tree was admitted; [payloadRoot] is the canonical directory to execute. */
        data class Admitted(val payloadRoot: File) : Result

        /** A concrete, log-safe reason. Never contains file contents. */
        data class Refused(val detail: String) : Result
    }

    private const val CANDIDATE_FILE_NAME = "candidate.json"
    private const val MAX_CRITICAL_FILES = 64

    fun admit(
        dataRoot: File,
        payloadRoot: File,
        kernelEntryRelativePath: String
    ): Result {
        val closureStore = canonicalOrNull(File(dataRoot, "closure"))
            ?: return Result.Refused("the closure store is not readable")
        val payload = canonicalOrNull(payloadRoot)
            ?: return Result.Refused("the closure payload root is not a readable directory")
        if (!payload.isDirectory) return Result.Refused("the closure payload root is not a directory")
        if (!payload.startsWith(closureStore)) {
            return Result.Refused("the closure payload root is outside the closure store")
        }
        if (payload.name != "payload") return Result.Refused("the closure root does not name a payload tree")

        val candidateFile = File(payload.parentFile, CANDIDATE_FILE_NAME)
        val descriptor = runCatching {
            DshJson.parse(candidateFile.readText(Charsets.UTF_8)) as? Map<*, *>
        }.getOrNull() ?: return Result.Refused("the closure descriptor is missing or unreadable")
        val payloadBlock = descriptor["payload"] as? Map<*, *>
            ?: return Result.Refused("the closure descriptor has no payload block")
        if ((payloadBlock["root"] as? String) != "payload") {
            return Result.Refused("the closure descriptor does not describe a payload tree")
        }

        @Suppress("UNCHECKED_CAST")
        val critical = payloadBlock["criticalFiles"] as? List<*>
            ?: return Result.Refused("the closure descriptor lists no critical files")
        if (critical.isEmpty() || critical.size > MAX_CRITICAL_FILES) {
            return Result.Refused("the closure descriptor critical-file count is out of bounds")
        }
        for (entry in critical) {
            val row = entry as? Map<*, *> ?: return Result.Refused("a critical-file row is malformed")
            val relative = (row["path"] as? String)?.trim().orEmpty()
            val expected = (row["sha256"] as? String)?.lowercase().orEmpty()
            if (!isSafeRelativePath(relative)) {
                return Result.Refused("a critical-file path is not a safe relative path")
            }
            if (expected.length != 64 || expected.any { it !in '0'..'9' && it !in 'a'..'f' }) {
                return Result.Refused("a critical-file hash is not sha256")
            }
            val file = resolveWithin(payload, relative)
                ?: return Result.Refused("a critical-file path escapes the payload tree")
            if (!file.isFile) return Result.Refused("a critical file is missing from the payload tree")
            val actual = sha256(file) ?: return Result.Refused("a critical file could not be read")
            if (actual != expected) return Result.Refused("a critical file does not match its recorded hash")
        }

        val kernelEntry = resolveWithin(payload, kernelEntryRelativePath)
            ?: return Result.Refused("the kernel entry path is not a safe relative path")
        if (!kernelEntry.isFile) return Result.Refused("the closure payload has no kernel entry")
        return Result.Admitted(payload)
    }

    private fun canonicalOrNull(file: File): File? =
        runCatching { file.canonicalFile }.getOrNull()?.takeIf { it.exists() }

    private fun resolveWithin(root: File, relative: String): File? {
        if (!isSafeRelativePath(relative)) return null
        val resolved = runCatching { File(root, relative).canonicalFile }.getOrNull() ?: return null
        return resolved.takeIf { it.startsWith(root) }
    }

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.length > 512) return false
        if (path.startsWith("/") || path.startsWith("\\")) return false
        if (path.contains('\u0000') || path.contains('\\')) return false
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
        return true
    }

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }.getOrNull()
}
