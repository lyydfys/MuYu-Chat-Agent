package com.muyuchat.mca

import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatWebSearchTrace
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON column <-> value-object mapping for one chat message.
 *
 * Moved out of `ChatSessionStore.kt` verbatim: the image-attachment, source-reference, web-search-trace
 * and string-list encoders/decoders used by `ChatMessage.toEntity` and
 * `ChatMessageEntity.toChatMessage`. Every JSON field name, default, null/blank rule and ordering is
 * unchanged.
 *
 * Helpers that the store and the mapping file still call are `internal` under responsibility-prefixed
 * names (`chatMessage*`), so they cannot collide with same-package declarations the way a bare
 * `JSONArray?.toSourceReferences()` did. `chatMessageStringList` stays private to this file.
 */
internal fun JSONArray?.chatMessageImageAttachments(): List<ChatImageAttachment> {
    if (this == null) return emptyList()
    return List(length()) { index ->
        val json = optJSONObject(index) ?: JSONObject()
        ChatImageAttachment(
            name = json.optString("name"),
            uriString = json.optString("uriString"),
            mimeType = json.optString("mimeType", "image/jpeg"),
            dataBase64 = json.optString("dataBase64"),
            width = json.optInt("width", 0),
            height = json.optInt("height", 0),
            sizeBytes = json.optLong("sizeBytes", 0L)
        )
    }.filter { it.uriString.isNotBlank() || it.dataBase64.isNotBlank() }
}

internal fun List<ChatImageAttachment>.chatMessageAttachmentJson(includeInlineData: Boolean): String {
    val array = JSONArray()
    forEach { attachment ->
        array.put(
            JSONObject()
                .put("name", attachment.name)
                .put("uriString", attachment.uriString)
                .put("mimeType", attachment.mimeType)
                .put("dataBase64", if (includeInlineData) attachment.dataBase64 else "")
                .put("width", attachment.width)
                .put("height", attachment.height)
                .put("sizeBytes", attachment.sizeBytes)
        )
    }
    return array.toString()
}

internal fun JSONArray?.chatMessageSourceReferences(): List<ChatSourceReference> {
    if (this == null) return emptyList()
    return List(length()) { index ->
        val json = optJSONObject(index) ?: JSONObject()
        ChatSourceReference(
            title = json.optString("title"),
            url = json.optString("url"),
            snippet = json.optString("snippet"),
            provider = json.optString("provider"),
            hostLabel = json.optString("hostLabel"),
            trustLabel = json.optString("trustLabel"),
            trustReason = json.optString("trustReason")
        )
    }.filter { it.url.isNotBlank() }
}

internal fun List<ChatSourceReference>.chatMessageSourceReferenceJson(): String {
    val array = JSONArray()
    forEach { source ->
        array.put(
            JSONObject()
                .put("title", source.title)
                .put("url", source.url)
                .put("snippet", source.snippet)
                .put("provider", source.provider)
                .put("hostLabel", source.hostLabel)
                .put("trustLabel", source.trustLabel)
                .put("trustReason", source.trustReason)
        )
    }
    return array.toString()
}

internal fun JSONObject?.chatMessageWebSearchTrace(): ChatWebSearchTrace? {
    if (this == null || length() == 0) return null
    val trace = ChatWebSearchTrace(
        query = optString("query"),
        providerLabel = optString("providerLabel"),
        triggerModeLabel = optString("triggerModeLabel"),
        running = optBoolean("running", false),
        stageLabel = optString("stageLabel"),
        searchedQueries = optJSONArray("searchedQueries").chatMessageStringList(),
        directUrls = optJSONArray("directUrls").chatMessageStringList(),
        sourceCount = optInt("sourceCount", 0),
        elapsedMs = optLong("elapsedMs", 0L),
        success = optBoolean("success", false),
        message = optString("message"),
        healthScore = optInt("healthScore", 0),
        healthLabel = optString("healthLabel"),
        qualityScore = optInt("qualityScore", 0),
        qualityLabel = optString("qualityLabel"),
        researchConfidenceScore = optInt("researchConfidenceScore", 0),
        researchConfidenceLabel = optString("researchConfidenceLabel"),
        evidenceGroups = optJSONArray("evidenceGroups").chatMessageStringList(),
        conflictWarnings = optJSONArray("conflictWarnings").chatMessageStringList(),
        synthesisGuidance = optJSONArray("synthesisGuidance").chatMessageStringList(),
        triggerReasons = optJSONArray("triggerReasons").chatMessageStringList(),
        warnings = optJSONArray("warnings").chatMessageStringList(),
        cacheStatus = optString("cacheStatus"),
        closedLoopChecks = optJSONArray("closedLoopChecks").chatMessageStringList()
    )
    return trace.takeIf { it.hasContent }
}

internal fun ChatWebSearchTrace?.chatMessageWebSearchTraceJson(): String {
    if (this == null || !hasContent) return "{}"
    return JSONObject()
        .put("query", query)
        .put("providerLabel", providerLabel)
        .put("triggerModeLabel", triggerModeLabel)
        .put("running", running)
        .put("stageLabel", stageLabel)
        .put("searchedQueries", searchedQueries.toJsonArray())
        .put("directUrls", directUrls.toJsonArray())
        .put("sourceCount", sourceCount)
        .put("elapsedMs", elapsedMs)
        .put("success", success)
        .put("message", message)
        .put("healthScore", healthScore)
        .put("healthLabel", healthLabel)
        .put("qualityScore", qualityScore)
        .put("qualityLabel", qualityLabel)
        .put("researchConfidenceScore", researchConfidenceScore)
        .put("researchConfidenceLabel", researchConfidenceLabel)
        .put("evidenceGroups", evidenceGroups.toJsonArray())
        .put("conflictWarnings", conflictWarnings.toJsonArray())
        .put("synthesisGuidance", synthesisGuidance.toJsonArray())
        .put("triggerReasons", triggerReasons.toJsonArray())
        .put("warnings", warnings.toJsonArray())
        .put("cacheStatus", cacheStatus)
        .put("closedLoopChecks", closedLoopChecks.toJsonArray())
        .toString()
}

private fun JSONArray?.chatMessageStringList(): List<String> {
    if (this == null) return emptyList()
    return List(length()) { index -> optString(index) }
        .map { it.trim() }
        .filter { it.isNotBlank() }
}
