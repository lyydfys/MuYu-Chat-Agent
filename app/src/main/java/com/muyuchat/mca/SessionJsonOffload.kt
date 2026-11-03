package com.muyuchat.mca

import java.io.File
import java.security.MessageDigest

/**
 * Moves an oversized JSON column value out of SQLite and into an app-private file.
 *
 * Why this exists: Android reads query rows through a `CursorWindow` of about 2 MB. A single
 * `chat_sessions` row carrying a large character-card snapshot (SillyTavern cards can embed a whole
 * lorebook) can exceed that, and then *every* `SELECT * FROM chat_sessions` fails with
 * `Row too big to fit into CursorWindow` — including the send path, which is why the user saw
 * "发送失败" no matter which preset was bound.
 *
 * The fix does not truncate content: the value is written to a content-addressed file and the
 * column keeps a small reference object. Reads resolve the reference back to the original JSON, so
 * behaviour is unchanged for every caller and no Room schema migration is needed.
 *
 * The store is a process-wide configuration because the value is materialised inside the Room
 * entity mappers, which have no Context. It is configured once, when the database handle is
 * created, and every method is a pass-through when it has not been configured — an unconfigured
 * store can never corrupt a write.
 */
internal object SessionJsonOffload {

    /**
     * Inline ceiling per JSON column, in characters. Three such columns plus the rest of the row
     * stay well below the 2 MB window even when every character is 4-byte UTF-8.
     */
    const val MAX_INLINE_JSON_CHARS = 192 * 1024

    private const val REFERENCE_FIELD = "\$mcaJsonRef"

    @Volatile
    private var rootDirectory: File? = null

    /** Points the offload store at app-private storage. Idempotent. */
    fun configure(directory: File?) {
        rootDirectory = directory
        if (directory != null) runCatching { directory.mkdirs() }
    }

    fun isConfigured(): Boolean = rootDirectory != null

    /** True when [stored] is a reference produced by [offload] rather than the JSON itself. */
    fun isReference(stored: String?): Boolean =
        stored != null && stored.startsWith("{\"$REFERENCE_FIELD\"") && stored.endsWith("}")

    /**
     * Returns [json] unchanged while it fits, or a reference object after writing it to disk.
     * A write failure returns the original JSON: an oversized row is still better than lost data.
     */
    fun offload(key: String, json: String?): String? {
        if (json == null) return null
        if (json.length <= MAX_INLINE_JSON_CHARS) return json
        if (isReference(json)) return json
        val root = rootDirectory ?: return json
        val digest = sha256Hex(json)
        val fileName = sanitizeKey(key) + "-" + digest.take(32) + ".json"
        return runCatching {
            root.mkdirs()
            val target = File(root, fileName)
            if (!target.isFile || target.length() != json.toByteArray(Charsets.UTF_8).size.toLong()) {
                val temporary = File(root, fileName + ".tmp")
                temporary.writeText(json, Charsets.UTF_8)
                if (!temporary.renameTo(target)) {
                    target.writeText(json, Charsets.UTF_8)
                    temporary.delete()
                }
            }
            "{\"$REFERENCE_FIELD\":\"${root.name}/$fileName\"}"
        }.getOrDefault(json)
    }

    /**
     * Returns the JSON behind a stored value. A plain JSON value passes through; a reference whose
     * file is gone returns null so the caller can report a missing snapshot honestly instead of
     * crashing the whole conversation list.
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

    /** Content-addressed ids keep the file name short and stable across re-saves. */
    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun sanitizeKey(value: String): String {
        val cleaned = value.map { char ->
            if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_'
        }.joinToString("")
        return cleaned.take(40).ifBlank { "session" }
    }
}
