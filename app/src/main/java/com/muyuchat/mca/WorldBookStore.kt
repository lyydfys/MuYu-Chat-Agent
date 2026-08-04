package com.muyuchat.mca

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.UUID

/**
 * A deliberately small, deterministic subset of the Tavern World Info format.
 * Imported books are data only: macros, regexes and executable extensions are
 * retained nowhere and are never evaluated by MCA.
 */
enum class WorldBookScope(val wireName: String) {
    GLOBAL("global"),
    ASSISTANT("assistant"),
    CHAT("chat");

    companion object {
        fun fromWireName(value: String?): WorldBookScope =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) }
                ?: GLOBAL
    }
}

data class WorldBookEntry(
    val id: String = UUID.randomUUID().toString(),
    val keys: List<String> = emptyList(),
    val content: String,
    val enabled: Boolean = true,
    val constant: Boolean = false,
    val priority: Int = 0
) {
    init {
        require(content.isNotBlank()) { "World book entry content is required." }
    }
}

data class WorldBookRecord(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val scope: WorldBookScope = WorldBookScope.GLOBAL,
    val assistantId: String? = null,
    val chatSessionId: String? = null,
    val entries: List<WorldBookEntry>,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    init {
        require(name.isNotBlank()) { "World book name is required." }
        require(entries.isNotEmpty()) { "World book must contain at least one entry." }
        require(scope != WorldBookScope.ASSISTANT || !assistantId.isNullOrBlank()) {
            "Assistant world books require an assistant id."
        }
        require(scope != WorldBookScope.CHAT || !chatSessionId.isNullOrBlank()) {
            "Chat world books require a chat session id."
        }
    }
}

data class WorldBookImportResult(
    val book: WorldBookRecord? = null,
    val error: String? = null
) {
    val isSuccess: Boolean
        get() = book != null && error == null
}

data class WorldBookSelection(
    val context: String = "",
    val selectedEntryIds: List<String> = emptyList(),
    val skippedEntryIds: List<String> = emptyList(),
    val estimatedTokens: Int = 0
)

object WorldBookCodec {
    private const val MAX_ENTRIES = 512
    private const val MAX_BOOK_CHARS = 1_048_576
    private const val MAX_ENTRY_CHARS = 65_536

    fun parse(
        rawJson: String,
        scope: WorldBookScope,
        assistantId: String? = null,
        chatSessionId: String? = null,
        fallbackName: String = "Imported World Book"
    ): WorldBookImportResult = runCatching {
        require(rawJson.toByteArray(Charsets.UTF_8).size <= MAX_BOOK_CHARS) {
            "World book is larger than 1 MiB."
        }
        val root = JSONObject(rawJson)
        parse(
            root = root,
            scope = scope,
            assistantId = assistantId,
            chatSessionId = chatSessionId,
            fallbackName = fallbackName
        )
    }.fold(
        onSuccess = { WorldBookImportResult(book = it) },
        onFailure = { WorldBookImportResult(error = it.message ?: "Invalid world book JSON.") }
    )

    fun parse(
        root: JSONObject,
        scope: WorldBookScope,
        assistantId: String? = null,
        chatSessionId: String? = null,
        fallbackName: String = "Imported World Book"
    ): WorldBookRecord {
        val source = root.optJSONObject("character_book") ?: root
        val rawEntries = source.opt("entries")
        val entries = when (rawEntries) {
            is JSONObject -> rawEntries.keys().asSequence().mapNotNull { key ->
                rawEntries.optJSONObject(key)?.let { entry -> parseEntry(entry, key) }
            }.toList()
            is JSONArray -> List(rawEntries.length()) { index ->
                rawEntries.optJSONObject(index)?.let { entry -> parseEntry(entry, index.toString()) }
            }.filterNotNull()
            else -> emptyList()
        }
        require(entries.isNotEmpty()) { "World book contains no usable entries." }
        require(entries.size <= MAX_ENTRIES) { "World book contains more than $MAX_ENTRIES entries." }
        val name = source.optString("name")
            .ifBlank { source.optString("title") }
            .ifBlank { fallbackName }
            .trim()
            .take(96)
        return WorldBookRecord(
            name = name,
            scope = scope,
            assistantId = assistantId?.takeIf { it.isNotBlank() },
            chatSessionId = chatSessionId?.takeIf { it.isNotBlank() },
            entries = entries
        )
    }

    private fun parseEntry(source: JSONObject, fallbackId: String): WorldBookEntry? {
        val content = source.optString("content")
            .ifBlank { source.optString("entry") }
            .trim()
        if (content.isBlank()) return null
        require(content.length <= MAX_ENTRY_CHARS) { "A world book entry is too large." }
        val keys = buildList {
            source.optJSONArray("key")?.forEachString { add(it) }
            source.optJSONArray("keys")?.forEachString { add(it) }
            source.optString("key").takeIf { it.isNotBlank() }?.let { add(it) }
            source.optString("keys").takeIf { it.isNotBlank() }?.let { add(it) }
        }
            .flatMap { it.split(',', '\n') }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(32)
        val enabled = !source.has("enabled") || source.optBoolean("enabled", true)
        val constant = source.optBoolean("constant", false)
        if (!constant && keys.isEmpty()) return null
        return WorldBookEntry(
            id = source.opt("uid")?.toString()?.takeIf { it.isNotBlank() } ?: fallbackId,
            keys = keys,
            content = content,
            enabled = enabled,
            constant = constant,
            priority = source.optInt("order", source.optInt("priority", 0))
        )
    }

    private inline fun JSONArray.forEachString(block: (String) -> Unit) {
        for (index in 0 until length()) {
            optString(index).trim().takeIf { it.isNotBlank() }?.let(block)
        }
    }
}

class WorldBookStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "world_books_v1.json")
    private val lock = Any()

    fun load(): List<WorldBookRecord> = synchronized(lock) {
        runCatching { readRecords(skipInvalidRecords = true) }.getOrDefault(emptyList())
    }

    fun save(records: List<WorldBookRecord>) = synchronized(lock) {
        val array = JSONArray()
        records.distinctBy { it.id }.forEach { array.put(it.toJson()) }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(array.toString(), Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            file.writeText(array.toString(), Charsets.UTF_8)
            temporary.delete()
        }
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

    private fun readRecords(skipInvalidRecords: Boolean = false): List<WorldBookRecord> {
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

    private fun WorldBookRecord.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("scope", scope.wireName)
        .put("assistantId", assistantId)
        .put("chatSessionId", chatSessionId)
        .put("enabled", enabled)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("entries", JSONArray().apply {
            entries.forEach { entry ->
                put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("keys", JSONArray(entry.keys))
                        .put("content", entry.content)
                        .put("enabled", entry.enabled)
                        .put("constant", entry.constant)
                        .put("priority", entry.priority)
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
        entries = optJSONArray("entries")?.let { array ->
            List(array.length()) { index ->
                val entry = array.getJSONObject(index)
                WorldBookEntry(
                    id = entry.optString("id").ifBlank { UUID.randomUUID().toString() },
                    keys = entry.optJSONArray("keys")?.let { keys ->
                        List(keys.length()) { keyIndex -> keys.optString(keyIndex).trim() }
                            .filter { it.isNotBlank() }
                    }.orEmpty(),
                    content = entry.optString("content"),
                    enabled = !entry.has("enabled") || entry.optBoolean("enabled", true),
                    constant = entry.optBoolean("constant", false),
                    priority = entry.optInt("priority", 0)
                )
            }
        }.orEmpty()
    )
}

object WorldBookResolver {
    private const val MAX_SCAN_CHARS = 16_384

    fun select(
        books: List<WorldBookRecord>,
        messages: List<com.muyuchat.core.engine.ChatMessage>,
        assistantId: String,
        chatSessionId: String?,
        tokenBudget: Int
    ): WorldBookSelection {
        if (tokenBudget <= 0) return WorldBookSelection()
        val scanText = normalize(
            messages
                .filter { it.role != com.muyuchat.core.engine.Role.SYSTEM }
                .takeLast(8)
                .joinToString("\n") { it.content }
                .takeLast(MAX_SCAN_CHARS)
        )
        val candidates = books.asSequence()
            .filter { it.enabled && matchesScope(it, assistantId, chatSessionId) }
            .flatMap { book ->
                book.entries.asSequence()
                    .filter { it.enabled }
                    .filter { entry -> entry.constant || entry.keys.any { key -> normalize(key) in scanText } }
                    .map { entry -> WorldBookCandidate(book, entry) }
            }
            .sortedWith(
                compareByDescending<WorldBookCandidate> { it.entry.constant }
                    .thenByDescending { it.entry.priority }
                    .thenBy { it.book.name }
                    .thenBy { it.entry.id }
            )
            .toList()
        var usedTokens = 0
        val selected = mutableListOf<WorldBookCandidate>()
        val skipped = mutableListOf<String>()
        candidates.forEach { candidate ->
            val entryTokens = estimateTokens(candidate.entry.content)
            if (entryTokens <= tokenBudget - usedTokens) {
                selected += candidate
                usedTokens += entryTokens
            } else {
                skipped += candidate.entry.id
            }
        }
        val context = selected.takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n") { it.entry.content }
            ?.let { "[World book]\n$it" }
            .orEmpty()
        return WorldBookSelection(
            context = context,
            selectedEntryIds = selected.map { it.entry.id },
            skippedEntryIds = skipped,
            estimatedTokens = usedTokens
        )
    }

    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var cjk = 0
        var other = 0
        text.forEach { character ->
            if (character.isWhitespace()) return@forEach
            if (character.code in 0x2E80..0x9FFF || character.code in 0xAC00..0xD7AF ||
                character.code in 0x3040..0x30FF
            ) {
                cjk++
            } else {
                other++
            }
        }
        return (cjk + (other + 2) / 3).coerceAtLeast(1)
    }

    private fun matchesScope(
        book: WorldBookRecord,
        assistantId: String,
        chatSessionId: String?
    ): Boolean = when (book.scope) {
        WorldBookScope.GLOBAL -> true
        WorldBookScope.ASSISTANT -> book.assistantId == assistantId
        WorldBookScope.CHAT -> book.chatSessionId == chatSessionId
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase()

    private data class WorldBookCandidate(
        val book: WorldBookRecord,
        val entry: WorldBookEntry
    )
}
