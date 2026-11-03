package com.muyuchat.mca

import java.io.File
import java.security.MessageDigest

/**
 * Moves an oversized text/JSON column value out of SQLite and into an app-private file.
 *
 * Why this exists: Android reads query rows through a `CursorWindow` of about 2 MB. A single row
 * carrying a multi-megabyte character-card snapshot, a runaway model reply recorded as a memory
 * turn, or a huge preset system prompt exceeds that, and then *every* read of that table fails with
 * `Row too big to fit into CursorWindow` — which the user sees as "发送失败" on a perfectly normal
 * send, because the send path re-reads memories and memory turns.
 *
 * The fix never truncates content: the value is written to a content-addressed file and the column
 * keeps a small reference object. Reads resolve the reference back to the original text, so every
 * caller observes exactly what it stored and no Room schema migration is needed.
 *
 * Coverage is a closed set: a column may only be offloaded if its entity mapper resolves it back
 * ([LargeRowRepair.COLUMNS] lists the pairs, and `LargeRowOffloadTest` pins the contract). A column
 * that is written here but not read back would surface as literal reference JSON in the UI, so the
 * two lists must stay in sync.
 *
 * The store is a process-wide configuration because values are materialised inside Room entity
 * mappers, which have no Context. It is configured once when the database handle is created, and
 * every method is a pass-through when unconfigured — an unconfigured store cannot corrupt a write.
 */
internal object LargeRowOffload {

    /** Inline ceiling per column, in characters. */
    const val MAX_INLINE_CHARS = 192 * 1024

    private const val REFERENCE_FIELD = "\$mcaTextRef"
    private const val DIRECTORY_NAME = "row_text"

    @Volatile
    private var rootDirectory: File? = null

    /** Points the offload store at app-private storage. Idempotent. */
    fun configure(directory: File?) {
        rootDirectory = directory
        if (directory != null) runCatching { directory.mkdirs() }
    }

    fun isConfigured(): Boolean = rootDirectory != null

    /** True when [stored] is a reference produced by [offload] rather than the value itself. */
    fun isReference(stored: String?): Boolean =
        stored != null && stored.startsWith("{\"$REFERENCE_FIELD\"") && stored.endsWith("}")

    /**
     * Returns [value] unchanged while it fits, or a reference object after writing it to disk.
     * A write failure returns the original value: an oversized row is still better than lost data.
     */
    fun offload(key: String, value: String?): String? {
        if (value == null) return null
        if (value.length <= MAX_INLINE_CHARS) return value
        if (isReference(value)) return value
        val root = rootDirectory ?: return value
        val digest = sha256Hex(value)
        val fileName = sanitizeKey(key) + "-" + digest.take(32) + ".json"
        return runCatching {
            root.mkdirs()
            val target = File(root, fileName)
            val expectedBytes = value.toByteArray(Charsets.UTF_8).size.toLong()
            if (!target.isFile || target.length() != expectedBytes) {
                val temporary = File(root, fileName + ".tmp")
                temporary.writeText(value, Charsets.UTF_8)
                if (!temporary.renameTo(target)) {
                    target.writeText(value, Charsets.UTF_8)
                    temporary.delete()
                }
            }
            "{\"$REFERENCE_FIELD\":\"$DIRECTORY_NAME/$fileName\"}"
        }.getOrDefault(value)
    }

    /**
     * Returns the value behind a stored column. Plain values pass through; a reference whose file is
     * gone returns null so the caller can report missing content honestly instead of crashing the
     * whole table read.
     */
    fun resolve(stored: String?): String? {
        if (stored == null) return null
        if (!isReference(stored)) return stored
        val root = rootDirectory ?: return null
        val relative = runCatching {
            org.json.JSONObject(stored).optString(REFERENCE_FIELD)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        // Never follow a path out of the offload directory, even if a stored document was tampered
        // with: the reference is app-written data and must stay inside its own folder.
        if (relative.contains("..")) return null
        val name = relative.substringAfterLast('/')
        if (name.isBlank()) return null
        val file = File(root, name)
        return runCatching { if (file.isFile) file.readText(Charsets.UTF_8) else null }.getOrNull()
    }

    /** Resolves a non-null column without turning a missing file into an exception. */
    fun resolveOrEmpty(stored: String): String = resolve(stored).orEmpty()

    /** Content-addressed ids keep the file name short and stable across re-saves. */
    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun sanitizeKey(value: String): String {
        val cleaned = value.map { char ->
            if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_'
        }.joinToString("")
        return cleaned.take(40).ifBlank { "row" }
    }
}
