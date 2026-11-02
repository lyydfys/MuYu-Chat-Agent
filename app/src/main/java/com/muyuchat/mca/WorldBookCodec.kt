package com.muyuchat.mca

import com.google.re2j.Pattern
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_WORLD_BOOK_REGEX_CHARS = 512

object WorldBookCodec {
    // Real Tavern lorebooks ship well past 1 MiB (embedded reference books, translated packs);
    // 8 MiB keeps the bounded per-entry and entry-count guards as the real safety net.
    private const val MAX_IMPORT_BYTES = 8_388_608
    private const val MAX_ENTRIES = 1024
    private const val MAX_ENTRY_CHARS = 65_536

    fun parse(
        rawJson: String,
        scope: WorldBookScope,
        assistantId: String? = null,
        chatSessionId: String? = null,
        fallbackName: String = "Imported World Book"
    ): WorldBookImportResult = runCatching {
        require(rawJson.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES) {
            "世界书文件超过 8 MiB。"
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
        val unsupportedRegexEntries = parsedEntries.count(ParsedWorldBookEntry::regexUnsupported)
        require(entries.isNotEmpty()) {
            "世界书没有可用条目。请检查 entries 中是否有非空 content。"
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
            if (unsupportedRegexEntries > 0) {
                add("有 $unsupportedRegexEntries 条正则触发条目含不受支持的表达式；条目已保留，但这些表达式不会触发。")
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
        val regexUnsupported: Boolean = false
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
        val useRegex = source.optBoolean("use_regex", source.optBoolean("useRegex", false))
        val keys = readStringValues(source, listOf("key", "keys"))
            .flatMap { if (useRegex) listOf(it) else it.split(',', '\n') }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(32)
        val secondaryKeys = readStringValues(
            source,
            listOf("secondary_keys", "secondaryKeys", "keysecondary", "keySecondary")
        )
            .flatMap { if (useRegex) listOf(it) else it.split(',', '\n') }
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
        val caseSensitive = source.optBoolean("case_sensitive", source.optBoolean("caseSensitive", false))
        // Tavern retains keyless non-constant entries for editing; selection cannot match them.
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
                useRegex = useRegex,
                caseSensitive = caseSensitive
            ),
            regexUnsupported = useRegex && !constant &&
                (keys + if (selective) secondaryKeys else emptyList()).any {
                    compileWorldBookRegex(it, caseSensitive) == null
                }
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

internal fun compileWorldBookRegex(trigger: String, caseSensitive: Boolean): Pattern? {
    if (trigger.isBlank() || trigger.length > MAX_WORLD_BOOK_REGEX_CHARS) return null
    val lastSlash = if (trigger.startsWith('/')) trigger.lastIndexOf('/') else -1
    val literal = lastSlash > 0 && trigger.substring(lastSlash + 1).all { it in "gimsuy" }
    val expression = if (literal) trigger.substring(1, lastSlash) else trigger
    val flags = if (literal) trigger.substring(lastSlash + 1) else ""
    val modifiers = buildString {
        if (!caseSensitive || 'i' in flags) append('i')
        if ('m' in flags) append('m')
        if ('s' in flags) append('s')
    }
    return runCatching {
        Pattern.compile(if (modifiers.isEmpty()) expression else "(?$modifiers)$expression")
    }.getOrNull()
}
