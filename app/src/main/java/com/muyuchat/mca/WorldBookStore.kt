package com.muyuchat.mca

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    val secondaryKeys: List<String> = emptyList(),
    val content: String,
    val enabled: Boolean = true,
    val constant: Boolean = false,
    val priority: Int = 0,
    val selective: Boolean = false,
    val useRegex: Boolean = false,
    val caseSensitive: Boolean = false
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
    val updatedAt: Long = System.currentTimeMillis(),
    val originalJson: String? = null
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
    val error: String? = null,
    val warnings: List<String> = emptyList()
) {
    val isSuccess: Boolean
        get() = book != null && error == null
}

data class WorldBookSelection(
    val context: String = "",
    val selectedEntryIds: List<String> = emptyList(),
    val skippedEntryIds: List<String> = emptyList(),
    val estimatedTokens: Int = 0,
    val selectedSources: List<WorldBookSource> = emptyList(),
    val skippedSources: List<WorldBookSource> = emptyList()
)

data class WorldBookSource(
    val bookId: String,
    val entryId: String,
    val scope: WorldBookScope,
    val excerpt: String,
    val estimatedTokens: Int,
    val reason: String
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
            "世界书文件超过 1 MiB。"
        }
        require(CharacterCardCodec.validateJsonBounds(rawJson) == null) {
            "世界书 JSON 结构无效或嵌套超过 ${CharacterCardCodec.MAX_JSON_NESTING} 层。"
        }
        parseDetailed(
            root = parseImportRoot(rawJson),
            scope = scope,
            assistantId = assistantId,
            chatSessionId = chatSessionId,
            fallbackName = fallbackName
        ).let { result -> result.copy(book = result.book?.copy(originalJson = rawJson)) }
    }.fold(
        onSuccess = { it },
        onFailure = { error ->
            val message = when (error) {
                is org.json.JSONException -> "世界书文件不是有效的 JSON，请重新从酒馆导出 World Info JSON。"
                else -> error.message ?: "世界书导入失败，请检查文件格式后重试。"
            }
            WorldBookImportResult(error = message)
        }
    )

    fun parse(
        root: JSONObject,
        scope: WorldBookScope,
        assistantId: String? = null,
        chatSessionId: String? = null,
        fallbackName: String = "Imported World Book"
    ): WorldBookRecord = parseDetailed(root, scope, assistantId, chatSessionId, fallbackName)
        .book ?: error("世界书没有可用条目。")

    fun parseDetailed(
        root: JSONObject,
        scope: WorldBookScope,
        assistantId: String? = null,
        chatSessionId: String? = null,
        fallbackName: String = "Imported World Book"
    ): WorldBookImportResult {
        val source = root.optJSONObject("character_book")
            ?: root.optJSONObject("world_info")
            ?: root.optJSONObject("worldInfo")
            ?: root
        val rawEntries = source.opt("entries")
        val parsedEntries = when (rawEntries) {
            is JSONObject -> rawEntries.keys().asSequence().mapNotNull { key ->
                rawEntries.optJSONObject(key)?.let { entry -> parseEntry(entry, key) }
            }.toList()
            is JSONArray -> List(rawEntries.length()) { index ->
                rawEntries.optJSONObject(index)?.let { entry -> parseEntry(entry, index.toString()) }
            }.filterNotNull()
            else -> emptyList()
        }
        val entries = parsedEntries.mapNotNull(ParsedWorldBookEntry::entry)
        val skippedRegexEntries = parsedEntries.count(ParsedWorldBookEntry::regexSkipped)
        if (entries.isEmpty() && skippedRegexEntries > 0) {
            error("世界书只包含正则触发条目；为避免无界正则导致卡顿，MCA 不导入这类条目。请改用普通关键词。")
        }
        require(entries.isNotEmpty()) {
            "世界书没有可用条目。请检查 entries 中是否有非空 content，并为普通条目设置关键词。"
        }
        require(entries.size <= MAX_ENTRIES) { "世界书条目超过 $MAX_ENTRIES 条，请拆分后导入。" }
        val name = source.optString("name")
            .ifBlank { source.optString("title") }
            .ifBlank { root.optString("name") }
            .ifBlank { root.optString("title") }
            .ifBlank { fallbackName }
            .trim()
            .take(96)
        val warnings = buildList {
            if (skippedRegexEntries > 0) {
                add("有 $skippedRegexEntries 条正则触发条目未导入；MCA 使用普通关键词，避免不受信任正则造成卡顿。")
            }
        }
        return WorldBookImportResult(
            book = WorldBookRecord(
                name = name,
                scope = scope,
                assistantId = assistantId?.takeIf { it.isNotBlank() },
                chatSessionId = chatSessionId?.takeIf { it.isNotBlank() },
                entries = entries,
                originalJson = root.toString()
            ),
            warnings = warnings
        )
    }

    private data class ParsedWorldBookEntry(
        val entry: WorldBookEntry?,
        val regexSkipped: Boolean = false
    )

    /** Accepts Tavern JSON exports plus entry-oriented JSON arrays and JSONL files. */
    private fun parseImportRoot(rawJson: String): JSONObject {
        val sourceText = rawJson.removePrefix("\uFEFF").trim()
        require(sourceText.isNotBlank()) { "世界书文件为空。" }

        val objectRoot = runCatching { JSONObject(sourceText) }.getOrNull()
        if (objectRoot != null) {
            if (hasWorldBookContainer(objectRoot)) return objectRoot
            if (looksLikeEntry(objectRoot)) {
                return JSONObject()
                    .put("entries", JSONArray().put(objectRoot))
            }
        }

        val arrayRoot = runCatching { JSONArray(sourceText) }.getOrNull()
        if (arrayRoot != null) {
            return JSONObject().put("entries", arrayRoot)
        }

        val lines = sourceText.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.isNotEmpty()) { "世界书文件为空。" }
        val entries = JSONArray()
        var name = ""
        lines.forEachIndexed { index, line ->
            val row = runCatching { JSONObject(line) }.getOrElse { error ->
                throw IllegalArgumentException("JSONL 第 ${index + 1} 行不是有效的 JSON 对象。", error)
            }
            if (name.isBlank()) {
                name = row.optString("name").ifBlank { row.optString("title") }
            }
            val nested = row.optJSONObject("character_book")
                ?: row.optJSONObject("world_info")
                ?: row.optJSONObject("worldInfo")
            val nestedEntries = nested?.opt("entries")
            when (nestedEntries) {
                is JSONArray -> for (entryIndex in 0 until nestedEntries.length()) {
                    nestedEntries.optJSONObject(entryIndex)?.let { entries.put(it) }
                }
                is JSONObject -> nestedEntries.keys().asSequence().forEach { key ->
                    nestedEntries.optJSONObject(key)?.let { entries.put(it) }
                }
            }
            val rootEntries = row.opt("entries")
            when (rootEntries) {
                is JSONArray -> for (entryIndex in 0 until rootEntries.length()) {
                    rootEntries.optJSONObject(entryIndex)?.let { entries.put(it) }
                }
                is JSONObject -> rootEntries.keys().asSequence().forEach { key ->
                    rootEntries.optJSONObject(key)?.let { entries.put(it) }
                }
            }
            if (looksLikeEntry(row)) entries.put(row)
        }
        require(entries.length() > 0) {
            "没有找到世界书条目。请使用 World Info JSON，或每行一条 JSONL 条目（至少包含 content 和 key/constant）。"
        }
        return JSONObject()
            .put("name", name.ifBlank { "Imported World Book" })
            .put("entries", entries)
    }

    private fun hasWorldBookContainer(root: JSONObject): Boolean =
        root.has("entries") || root.has("character_book") || root.has("world_info") || root.has("worldInfo")

    private fun looksLikeEntry(value: JSONObject): Boolean =
        (value.has("content") || value.has("entry")) &&
            (value.has("key") || value.has("keys") || value.optBoolean("constant", false))

    private fun parseEntry(source: JSONObject, fallbackId: String): ParsedWorldBookEntry? {
        val content = source.optString("content")
            .ifBlank { source.optString("entry") }
            .trim()
        if (content.isBlank()) return null
        require(content.length <= MAX_ENTRY_CHARS) { "单条世界书内容超过 64 KiB，请拆分条目后重试。" }
        val keys = readStringValues(source, listOf("key", "keys"))
            .flatMap { it.split(',', '\n') }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(32)
        val secondaryKeys = readStringValues(
            source,
            listOf("secondary_keys", "secondaryKeys", "keysecondary", "keySecondary")
        )
            .flatMap { it.split(',', '\n') }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(32)
        val enabled = if (source.has("enabled")) {
            source.optBoolean("enabled", true)
        } else {
            !source.optBoolean("disable", source.optBoolean("disabled", false))
        }
        val constant = source.optBoolean("constant", false)
        val selective = source.optBoolean("selective", false)
        val useRegex = source.optBoolean("use_regex", source.optBoolean("useRegex", false))
        val caseSensitive = source.optBoolean("case_sensitive", source.optBoolean("caseSensitive", false))
        if (useRegex && !constant && enabled) {
            return ParsedWorldBookEntry(entry = null, regexSkipped = true)
        }
        if (!constant && keys.isEmpty()) return null
        return ParsedWorldBookEntry(
            entry = WorldBookEntry(
                id = source.opt("uid")?.toString()?.takeIf { it.isNotBlank() }
                    ?: source.opt("id")?.toString()?.takeIf { it.isNotBlank() }
                    ?: fallbackId,
                keys = keys,
                secondaryKeys = secondaryKeys,
                content = content,
                enabled = enabled,
                constant = constant,
                priority = source.optInt(
                    "order",
                    source.optInt("insertion_order", source.optInt("insertionOrder", source.optInt("priority", 0)))
                ),
                selective = selective,
                useRegex = false,
                caseSensitive = caseSensitive
            )
        )
    }

    private fun readStringValues(source: JSONObject, names: List<String>): List<String> = buildList {
        names.forEach { name ->
            when (val value = source.opt(name)) {
                is JSONArray -> value.forEachString { add(it) }
                is String -> {
                    val text = value.trim()
                    if (text.isBlank()) return@forEach
                    if (text.startsWith("[") && text.endsWith("]")) {
                        runCatching { JSONArray(text) }
                            .getOrNull()
                            ?.forEachString { add(it) }
                            ?: add(text)
                    } else {
                        add(text)
                    }
                }
                null, JSONObject.NULL -> Unit
                else -> add(value.toString())
            }
        }
    }

    private inline fun JSONArray.forEachString(block: (String) -> Unit) {
        for (index in 0 until length()) {
            optString(index).trim().takeIf { it.isNotBlank() }?.let(block)
        }
    }
}

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

/** Pure scope-owner filtering used by persistence and lifecycle cleanup tests. */
internal fun List<WorldBookRecord>.withoutScopedOwner(
    scope: WorldBookScope,
    ownerId: String? = null
): List<WorldBookRecord> {
    if (scope == WorldBookScope.GLOBAL) return this
    return filterNot { book ->
        if (book.scope != scope) return@filterNot false
        when (scope) {
            WorldBookScope.ASSISTANT -> ownerId == null || book.assistantId == ownerId
            WorldBookScope.CHAT -> ownerId == null || book.chatSessionId == ownerId
            WorldBookScope.GLOBAL -> false
        }
    }
}

/** Pure batch filtering used after the corresponding owner transaction commits. */
internal fun List<WorldBookRecord>.withoutScopedOwners(
    scope: WorldBookScope,
    ownerIds: Set<String>
): List<WorldBookRecord> {
    if (scope == WorldBookScope.GLOBAL || ownerIds.isEmpty()) return this
    return filterNot { book ->
        when (scope) {
            WorldBookScope.ASSISTANT ->
                book.scope == scope && book.assistantId in ownerIds
            WorldBookScope.CHAT ->
                book.scope == scope && book.chatSessionId in ownerIds
            WorldBookScope.GLOBAL -> false
        }
    }
}

object WorldBookResolver {
    private const val MAX_SCAN_CHARS = 24_576

    fun select(
        books: List<WorldBookRecord>,
        messages: List<com.muyuchat.core.engine.ChatMessage>,
        assistantId: String,
        chatSessionId: String?,
        tokenBudget: Int
    ): WorldBookSelection {
        if (tokenBudget <= 0) return WorldBookSelection()
        val scanText = boundedScanText(
            messages
                .filter { it.role != com.muyuchat.core.engine.Role.SYSTEM }
                .takeLast(16)
                .joinToString("\n") { it.content }
        )
        val candidates = books.asSequence()
            .filter { it.enabled && matchesScope(it, assistantId, chatSessionId) }
            .flatMap { book ->
                book.entries.asSequence()
                    .filter { it.enabled }
                    .filter { entry -> entry.constant || entryMatches(entry, scanText) }
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
        val skipped = mutableListOf<WorldBookCandidate>()
        candidates.forEach { candidate ->
            val entryTokens = estimateTokens(candidate.entry.content)
            if (entryTokens <= tokenBudget - usedTokens) {
                selected += candidate
                usedTokens += entryTokens
            } else {
                skipped += candidate
            }
        }
        val context = selected.takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n") { it.entry.content }
            ?.let { "[World book]\n$it" }
            .orEmpty()
        return WorldBookSelection(
            context = context,
            selectedEntryIds = selected.map { it.entry.id },
            skippedEntryIds = skipped.map { it.entry.id },
            estimatedTokens = usedTokens,
            selectedSources = selected.map { candidate ->
                WorldBookSource(
                    bookId = candidate.book.id,
                    entryId = candidate.entry.id,
                    scope = candidate.book.scope,
                    excerpt = candidate.entry.content,
                    estimatedTokens = estimateTokens(candidate.entry.content),
                    reason = if (candidate.entry.constant) "constant" else "keyword"
                )
            },
            skippedSources = skipped.map { candidate ->
                WorldBookSource(
                    bookId = candidate.book.id,
                    entryId = candidate.entry.id,
                    scope = candidate.book.scope,
                    excerpt = candidate.entry.content,
                    estimatedTokens = estimateTokens(candidate.entry.content),
                    reason = "budget"
                )
            }
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

    private fun boundedScanText(value: String): String {
        if (value.length <= MAX_SCAN_CHARS) return value
        val half = MAX_SCAN_CHARS / 2
        return value.take(half) + "\n…\n" + value.takeLast(half)
    }

    private fun entryMatches(entry: WorldBookEntry, scanText: String): Boolean {
        val primary = entry.keys.any { matchesTrigger(it, entry, scanText) }
        if (!primary) return false
        if (!entry.selective || entry.secondaryKeys.isEmpty()) return true
        return entry.secondaryKeys.any { matchesTrigger(it, entry, scanText) }
    }

    private fun matchesTrigger(trigger: String, entry: WorldBookEntry, scanText: String): Boolean {
        if (trigger.isBlank()) return false
        // Java regular expressions have no execution timeout and can block the
        // generation preflight on a crafted imported world-book trigger.
        // Tavern regex-only entries are skipped during import; legacy records
        // with this flag are inert as well.
        if (entry.useRegex) return false
        val normalizedTrigger = if (entry.caseSensitive) {
            Normalizer.normalize(trigger, Normalizer.Form.NFKC)
        } else {
            normalize(trigger)
        }
        return if (entry.caseSensitive) {
            scanText.contains(normalizedTrigger)
        } else {
            normalize(scanText).contains(normalizedTrigger)
        }
    }

    private data class WorldBookCandidate(
        val book: WorldBookRecord,
        val entry: WorldBookEntry
    )
}
