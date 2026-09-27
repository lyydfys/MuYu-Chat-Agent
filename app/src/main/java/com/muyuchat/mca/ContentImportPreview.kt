package com.muyuchat.mca

import android.content.Context
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class ContentImportOwner(
    val sessionId: String?,
    val assistantId: String,
    val selectionRevision: Long
) {
    fun toJsonString(): String = JSONObject().put("sessionId", sessionId)
        .put("assistantId", assistantId).put("selectionRevision", selectionRevision).toString()

    companion object {
        fun fromJsonOrNull(raw: String?): ContentImportOwner? = raw?.let {
            runCatching {
                val value = JSONObject(it)
                ContentImportOwner(value.optString("sessionId").takeIf { id -> id.isNotBlank() && id != "null" },
                    value.getString("assistantId"), value.getLong("selectionRevision"))
            }.getOrNull()
        }
    }
}

data class ContentImportPreview(
    val id: String = UUID.randomUUID().toString(),
    val kind: String,
    val format: String,
    val title: String,
    val sourceHash: String,
    val fields: List<Pair<String, String>>,
    val warnings: List<String>,
    val owner: ContentImportOwner,
    val rawSource: String,
    val scope: WorldBookScope? = null,
    val sourceKind: CharacterCardSource? = null,
    val knowledgeBaseId: String? = null,
    val source: String? = null
)

internal fun characterCardPreview(
    success: CharacterCardParseResult.Success,
    owner: ContentImportOwner
): ContentImportPreview {
    val card = success.card
    val assistant = card.toAssistantRecord()
    val embedded = parseEmbeddedCharacterBook(card, "preview")
    val root = card.toJson()
    val data = root.optJSONObject("data") ?: root
    val known = setOf("name", "description", "personality", "scenario", "first_mes", "mes_example",
        "system_prompt", "post_history_instructions", "creator_notes", "creator", "character_version",
        "alternate_greetings", "group_only_greetings", "tags", "character_book", "extensions")
    val unknownCount = data.keys().asSequence().count { it !in known }
    return ContentImportPreview(
        kind = "character_card", format = "${card.format.name} / ${success.source.name}",
        title = card.name.ifBlank { "Imported Assistant" },
        sourceHash = contentImportSha256(card.rawJson),
        fields = listOf("name" to assistant.name, "system_prompt" to assistant.systemPrompt,
            "character_book" to (embedded.book?.entries?.size?.toString() ?: "0")),
        warnings = buildList {
            embedded.error?.let(::add)
            addAll(embedded.warnings)
            if (unknownCount > 0 || card.extensionsJson != null) {
                add("Unknown fields and extensions are preserved as data; scripts and macros are not executed.")
            }
            if (listOf(card.systemPrompt, card.description, card.personality, card.scenario,
                    card.firstMessage, card.exampleDialogue, card.postHistoryInstructions).sumOf { it.length } >
                AssistantRecord.MAX_SYSTEM_PROMPT_CHARS
            ) add("The editable system prompt is limited to ${AssistantRecord.MAX_SYSTEM_PROMPT_CHARS} characters; the original card is retained.")
        },
        owner = owner, rawSource = card.rawJson, sourceKind = success.source
    )
}

internal fun worldBookPreview(
    rawSource: String,
    result: WorldBookImportResult,
    owner: ContentImportOwner,
    scope: WorldBookScope
): ContentImportPreview {
    val book = requireNotNull(result.book)
    return ContentImportPreview(
        kind = "world_book", format = "World Info JSON / ${scope.wireName}", title = book.name,
        sourceHash = contentImportSha256(rawSource),
        fields = listOf("entries" to book.entries.size.toString(),
            "constant" to book.entries.count { it.constant }.toString(),
            "keyword" to book.entries.count { !it.constant }.toString(),
            "excerpt" to book.entries.take(3).joinToString("\n\n") { it.content.take(400) }),
        warnings = result.warnings + "Advanced trigger fields and executable extensions are not applied.",
        owner = owner, rawSource = rawSource, scope = scope
    )
}

internal fun knowledgeDocumentPreview(
    rawSource: String,
    title: String,
    knowledgeBaseId: String,
    source: String,
    owner: ContentImportOwner
): ContentImportPreview {
    val prepared = KnowledgeDocumentImportCodec.prepare(
        knowledgeBaseId = knowledgeBaseId,
        title = title,
        text = rawSource,
        source = source
    )
    return ContentImportPreview(
        kind = "knowledge_document",
        format = "UTF-8 文档 / 知识库",
        title = prepared.document.title,
        sourceHash = contentImportSha256(rawSource),
        fields = listOf(
            "knowledge_base" to knowledgeBaseId,
            "source" to source,
            "characters" to prepared.document.contentLength.toString(),
            "chunks" to prepared.document.chunkCount.toString(),
            "excerpt" to prepared.originalText.trim().take(600)
        ),
        warnings = listOf("文档会按关键词检索；尚未建立 embedding 时仍保留词法检索。"),
        owner = owner,
        rawSource = rawSource,
        knowledgeBaseId = knowledgeBaseId,
        source = source
    )
}

internal fun contentImportSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal class ContentImportPreviewStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("content_import_preview_v1", Context.MODE_PRIVATE)

    fun save(preview: ContentImportPreview) {
        val payload = JSONObject().put("id", preview.id).put("kind", preview.kind).put("format", preview.format)
            .put("title", preview.title).put("sourceHash", preview.sourceHash).put("rawSource", preview.rawSource)
            .put("scope", preview.scope?.wireName).put("sourceKind", preview.sourceKind?.name)
            .put("knowledgeBaseId", preview.knowledgeBaseId).put("source", preview.source)
            .put("owner", JSONObject().put("sessionId", preview.owner.sessionId)
                .put("assistantId", preview.owner.assistantId).put("selectionRevision", preview.owner.selectionRevision))
            .put("fields", JSONArray().apply { preview.fields.forEach { (name, value) ->
                put(JSONObject().put("name", name).put("value", value))
            } }).put("warnings", JSONArray(preview.warnings))
        check(preferences.edit().putString("pending", payload.toString()).commit()) {
            "Import preview was not persisted."
        }
    }

    fun load(): ContentImportPreview? {
        val raw = preferences.getString("pending", null) ?: return null
        return runCatching {
            require(raw.length <= 2_200_000) { "Import preview exceeds its size limit." }
            val payload = JSONObject(raw)
            val owner = payload.getJSONObject("owner")
            val source = payload.getString("rawSource")
            require(contentImportSha256(source) == payload.getString("sourceHash"))
            val fields = payload.getJSONArray("fields")
            val warnings = payload.getJSONArray("warnings")
            ContentImportPreview(
                id = payload.getString("id"), kind = payload.getString("kind"),
                format = payload.getString("format"), title = payload.getString("title"),
                sourceHash = payload.getString("sourceHash"), rawSource = source,
                fields = List(fields.length()) { index -> fields.getJSONObject(index).let { it.getString("name") to it.getString("value") } },
                warnings = List(warnings.length()) { warnings.getString(it) },
                owner = ContentImportOwner(owner.optString("sessionId").takeIf { it.isNotBlank() && it != "null" },
                    owner.getString("assistantId"), owner.getLong("selectionRevision")),
                scope = payload.optString("scope").takeIf { it.isNotBlank() && it != "null" }?.let(WorldBookScope::fromWireName),
                sourceKind = payload.optString("sourceKind").takeIf { it.isNotBlank() && it != "null" }?.let(CharacterCardSource::valueOf),
                knowledgeBaseId = payload.optString("knowledgeBaseId").takeIf { it.isNotBlank() && it != "null" },
                source = payload.optString("source").takeIf { it.isNotBlank() && it != "null" }
            )
        }.getOrNull()
    }

    fun clear(id: String) {
        if (load()?.id == id) check(preferences.edit().remove("pending").commit()) { "Import preview could not be cleared." }
    }
}
