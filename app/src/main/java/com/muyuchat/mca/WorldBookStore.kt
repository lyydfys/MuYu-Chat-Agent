package com.muyuchat.mca

import android.content.Context
import com.google.re2j.Pattern
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.Normalizer
import java.util.UUID

class WorldBookStore private constructor(
    private val file: File
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "world_books_v1.json")
    )

    /** JVM tests and migration tooling use an explicit app-private target. */
    internal constructor(file: File, @Suppress("UNUSED_PARAMETER") testing: Boolean) : this(file)

    private val pendingFile = File(
        requireNotNull(file.parentFile) { "World book file must have a parent directory." },
        ".${file.name}.pending"
    )
    private val lock = Any()

    fun load(): List<WorldBookRecord> = synchronized(lock) {
        runCatching { readRecords(skipInvalidRecords = true) }.getOrDefault(emptyList())
    }

    fun save(records: List<WorldBookRecord>) = synchronized(lock) {
        val array = JSONArray()
        records.distinctBy { it.id }.forEach { array.put(it.toJson()) }
        recoverInterruptedWriteIfNeeded()
        writeAndPublish(array.toString().toByteArray(Charsets.UTF_8))
    }

    fun upsert(record: WorldBookRecord): List<WorldBookRecord> {
        return synchronized(lock) {
            val current = readRecords()
            val updated = current.filterNot { it.id == record.id } +
                record.copy(updatedAt = System.currentTimeMillis())
            save(updated)
            updated
        }
    }

    fun remove(id: String): List<WorldBookRecord> {
        return synchronized(lock) {
            val updated = readRecords().filterNot { it.id == id }
            save(updated)
            updated
        }
    }

    /** Removes books owned by one assistant/chat, or all books in that scope when owner is null. */
    fun removeScoped(scope: WorldBookScope, ownerId: String? = null): List<WorldBookRecord> {
        require(scope != WorldBookScope.GLOBAL) { "Global world books require an explicit book id." }
        val normalizedOwner = ownerId?.trim()?.takeIf { it.isNotEmpty() }
        return synchronized(lock) {
            val updated = readRecords().withoutScopedOwner(scope, normalizedOwner)
            save(updated)
            updated
        }
    }

    /** Removes books for committed-deleted owners in one file replacement. */
    fun removeScopedOwners(
        scope: WorldBookScope,
        ownerIds: Set<String>
    ): List<WorldBookRecord> {
        require(scope != WorldBookScope.GLOBAL) { "Global world books require an explicit book id." }
        val normalizedOwners = ownerIds.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (normalizedOwners.isEmpty()) return load()
        return synchronized(lock) {
            val updated = readRecords().withoutScopedOwners(scope, normalizedOwners)
            save(updated)
            updated
        }
    }

    private fun readRecords(skipInvalidRecords: Boolean = false): List<WorldBookRecord> {
        recoverInterruptedWriteIfNeeded()
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText(Charsets.UTF_8))
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                if (skipInvalidRecords) {
                    runCatching { array.getJSONObject(index).toWorldBookRecord() }
                        .getOrNull()
                        ?.let(::add)
                } else {
                    add(array.getJSONObject(index).toWorldBookRecord())
                }
            }
        }
    }

    /**
     * Keeps a complete pending snapshot until the final same-directory rename.
     * If a process dies between those operations, the next reader promotes the
     * intact pending file only when the primary file is missing or unreadable.
     */
    private fun recoverInterruptedWriteIfNeeded() {
        if (!pendingFile.isFile) return
        val primaryIsReadable = file.isFile && runCatching {
            JSONArray(file.readText(Charsets.UTF_8))
        }.isSuccess
        if (primaryIsReadable) {
            pendingFile.delete()
            return
        }
        val pendingIsReadable = runCatching {
            JSONArray(pendingFile.readText(Charsets.UTF_8))
        }.isSuccess
        if (!pendingIsReadable) return
        publish(pendingFile)
    }

    private fun writeAndPublish(payload: ByteArray) {
        val parent = requireNotNull(file.parentFile) { "World book file must have a parent directory." }
        check(parent.exists() || parent.mkdirs()) {
            "Unable to create world book directory: ${parent.absolutePath}"
        }
        if (pendingFile.exists() && !pendingFile.delete()) {
            error("Unable to replace interrupted world book write: ${pendingFile.absolutePath}")
        }
        FileOutputStream(pendingFile).use { output ->
            output.write(payload)
            output.fd.sync()
        }
        publish(pendingFile)
    }

    private fun publish(source: File) {
        try {
            Files.move(
                source.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    internal fun pendingFileForTesting(): File = pendingFile

    private fun WorldBookRecord.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("scope", scope.wireName)
        .put("assistantId", assistantId)
        .put("chatSessionId", chatSessionId)
        .put("enabled", enabled)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("originalJson", originalJson)
        .put("entries", JSONArray().apply {
            entries.forEach { entry ->
                put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("keys", JSONArray(entry.keys))
                        .put("secondaryKeys", JSONArray(entry.secondaryKeys))
                        .put("content", entry.content)
                        .put("enabled", entry.enabled)
                        .put("constant", entry.constant)
                        .put("priority", entry.priority)
                        .put("selective", entry.selective)
                        .put("useRegex", entry.useRegex)
                        .put("caseSensitive", entry.caseSensitive)
                )
            }
        })

    private fun JSONObject.toWorldBookRecord(): WorldBookRecord = WorldBookRecord(
        id = optString("id").ifBlank { UUID.randomUUID().toString() },
        name = optString("name").ifBlank { "Imported World Book" },
        scope = WorldBookScope.fromWireName(optString("scope")),
        assistantId = optString("assistantId").takeIf { it.isNotBlank() },
        chatSessionId = optString("chatSessionId").takeIf { it.isNotBlank() },
        enabled = !has("enabled") || optBoolean("enabled", true),
        createdAt = optLong("createdAt", System.currentTimeMillis()),
        updatedAt = optLong("updatedAt", System.currentTimeMillis()),
        originalJson = optString("originalJson").takeIf { it.isNotBlank() && it != "null" },
        entries = optJSONArray("entries")?.let { array ->
            List(array.length()) { index ->
                val entry = array.getJSONObject(index)
                WorldBookEntry(
                    id = entry.optString("id").ifBlank { UUID.randomUUID().toString() },
                    keys = entry.optJSONArray("keys")?.let { keys ->
                        List(keys.length()) { keyIndex -> keys.optString(keyIndex).trim() }
                            .filter { it.isNotBlank() }
                    }.orEmpty(),
                    secondaryKeys = entry.optJSONArray("secondaryKeys")?.let { keys ->
                        List(keys.length()) { keyIndex -> keys.optString(keyIndex).trim() }
                            .filter { it.isNotBlank() }
                    }.orEmpty(),
                    content = entry.optString("content"),
                    enabled = !entry.has("enabled") || entry.optBoolean("enabled", true),
                    constant = entry.optBoolean("constant", false),
                    priority = entry.optInt("priority", 0),
                    selective = entry.optBoolean("selective", false),
                    useRegex = entry.optBoolean("useRegex", false),
                    caseSensitive = entry.optBoolean("caseSensitive", false)
                )
            }
        }.orEmpty()
    )
}
