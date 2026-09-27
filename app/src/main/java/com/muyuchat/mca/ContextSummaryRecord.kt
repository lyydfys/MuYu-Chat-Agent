package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ContextCompressionResult
import com.muyuchat.core.engine.ContextSummaryEvidence
import com.muyuchat.core.engine.ContextSummaryKind
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.StructuredContextSummary
import com.muyuchat.core.engine.estimateLocalPromptTokens
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

enum class ContextSummaryRecordStatus { CANDIDATE, ACTIVE, REVOKED }

data class ContextSummaryRecord(
    val id: String,
    val version: Int,
    val source: String,
    val text: String,
    val sourceMessageIds: List<String>,
    val sourceFingerprint: String,
    val scopeFingerprint: String,
    val createdAt: Long,
    val active: Boolean = false,
    val parentRevision: Int? = null,
    val sourceRevisionMessageIds: List<String> = emptyList(),
    val sourceRevisionHash: String = "",
    val structuredSummary: StructuredContextSummary = StructuredContextSummary(),
    val status: ContextSummaryRecordStatus = if (active) ContextSummaryRecordStatus.ACTIVE
        else ContextSummaryRecordStatus.CANDIDATE,
    val summaryModelIdentity: String? = null,
    val summaryRuntimeIdentity: String? = null,
    val summaryDiagnostic: String? = null,
    val tokenEstimate: Int = estimateLocalPromptTokens(text)
) {
    internal fun sourcesMatch(messages: List<ChatMessage>, scope: String): Boolean {
        if (scopeFingerprint != contextSummaryDigest(scope) ||
            sourceMessageIds.isEmpty() || sourceRevisionMessageIds.isEmpty() ||
            sourceRevisionHash.isBlank() || sourceMessageIds.distinct().size != sourceMessageIds.size ||
            sourceRevisionMessageIds.distinct().size != sourceRevisionMessageIds.size ||
            sourceMessageIds.any { it !in sourceRevisionMessageIds }
        ) return false
        val byId = messages.associateBy(ChatMessage::id)
        if (byId.size != messages.size) return false
        if (messages.take(sourceRevisionMessageIds.size).map(ChatMessage::id) != sourceRevisionMessageIds) {
            return false
        }
        val revision = sourceRevisionMessageIds.map { byId[it] ?: return false }
        val folded = sourceMessageIds.map { byId[it] ?: return false }
        if (folded.any { it.role == Role.SYSTEM || it.pinned || it.imageAttachments.isNotEmpty() }) return false
        return contextSummaryFingerprint(folded) == sourceFingerprint &&
            contextSummaryFingerprint(revision) == sourceRevisionHash
    }

    fun appliesTo(messages: List<ChatMessage>, scope: String): Boolean =
        active && status == ContextSummaryRecordStatus.ACTIVE && sourcesMatch(messages, scope)

    fun project(messages: List<ChatMessage>, scope: String): List<ChatMessage> =
        if (appliesTo(messages, scope)) projectVerified(messages) else messages

    /** A candidate can serve its owned request before history activates it. */
    fun projectCandidate(messages: List<ChatMessage>, scope: String): List<ChatMessage> =
        if (status == ContextSummaryRecordStatus.CANDIDATE && !active && sourcesMatch(messages, scope)) {
            projectVerified(messages)
        } else messages

    private fun projectVerified(messages: List<ChatMessage>): List<ChatMessage> {
        val ids = sourceMessageIds.toSet()
        val first = messages.indexOfFirst { it.id in ids }
        return buildList {
            messages.forEachIndexed { index, message ->
                if (index == first) add(ChatMessage(id = "summary-$id", role = Role.SYSTEM, content = text))
                if (message.id !in ids) add(message)
            }
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", 3)
        .put("id", id).put("version", version).put("source", source).put("text", text)
        .put("sourceMessageIds", JSONArray(sourceMessageIds))
        .put("sourceFingerprint", sourceFingerprint).put("scopeFingerprint", scopeFingerprint)
        .put("createdAt", createdAt).put("active", active)
        .put("parentRevision", parentRevision ?: JSONObject.NULL)
        .put("sourceRevisionMessageIds", JSONArray(sourceRevisionMessageIds))
        .put("sourceRevisionHash", sourceRevisionHash)
        .put("structuredSummary", structuredSummary.toJson())
        .put("status", status.name)
        .put("summaryModelIdentity", summaryModelIdentity ?: JSONObject.NULL)
        .put("summaryRuntimeIdentity", summaryRuntimeIdentity ?: JSONObject.NULL)
        .put("summaryDiagnostic", summaryDiagnostic ?: JSONObject.NULL)
        .put("tokenEstimate", tokenEstimate)
}

internal fun contextSummaryCandidate(
    originals: List<ChatMessage>,
    result: ContextCompressionResult,
    previous: List<ContextSummaryRecord>,
    scope: String,
    nowMillis: Long = System.currentTimeMillis()
): ContextSummaryRecord? {
    if (!result.didCompress || originals.isEmpty()) return null
    val text = result.summaryMessage?.content?.takeIf(String::isNotBlank) ?: return null
    val sourceIds = result.summarySourceMessageIds
    if (sourceIds.isEmpty() || sourceIds.distinct().size != sourceIds.size) return null
    val byId = originals.associateBy(ChatMessage::id)
    if (byId.size != originals.size) return null
    val folded = sourceIds.map { byId[it] ?: return null }
    if (folded.any { it.role == Role.SYSTEM || it.pinned || it.imageAttachments.isNotEmpty() ||
            it.id in result.protectedMessageIds
        }) return null
    if (result.structuredSummary.evidence.any { !it.matchesSource(byId, sourceIds.toSet()) }) return null
    val parent = previous.lastOrNull { it.active && it.status == ContextSummaryRecordStatus.ACTIVE }
    return ContextSummaryRecord(
        id = UUID.randomUUID().toString(),
        version = (previous.maxOfOrNull(ContextSummaryRecord::version) ?: 0) + 1,
        source = result.summarySource.name,
        text = text,
        sourceMessageIds = sourceIds,
        sourceFingerprint = contextSummaryFingerprint(folded),
        scopeFingerprint = contextSummaryDigest(scope),
        createdAt = nowMillis,
        active = false,
        parentRevision = parent?.version,
        sourceRevisionMessageIds = originals.map(ChatMessage::id),
        sourceRevisionHash = contextSummaryFingerprint(originals),
        structuredSummary = result.structuredSummary,
        status = ContextSummaryRecordStatus.CANDIDATE,
        summaryModelIdentity = result.summaryModelIdentity,
        summaryRuntimeIdentity = result.summaryRuntimeIdentity,
        summaryDiagnostic = result.summaryDiagnostic,
        tokenEstimate = result.summaryMessage?.tokenCount ?: estimateLocalPromptTokens(text)
    )
}

/** Apply inside the owner-checked session update; a stale candidate leaves history untouched. */
internal fun activateContextSummaryCandidate(
    records: List<ContextSummaryRecord>,
    candidate: ContextSummaryRecord,
    messages: List<ChatMessage>,
    scope: String
): List<ContextSummaryRecord> {
    val activeRevision = records.lastOrNull {
        it.active && it.status == ContextSummaryRecordStatus.ACTIVE
    }?.version
    if (candidate.status != ContextSummaryRecordStatus.CANDIDATE || candidate.active ||
        candidate.parentRevision != activeRevision ||
        candidate.version != (records.maxOfOrNull(ContextSummaryRecord::version) ?: 0) + 1 ||
        records.any { it.id == candidate.id } ||
        candidate.projectCandidate(messages, scope) === messages
    ) return records
    return revokeContextSummaries(records) +
        candidate.copy(active = true, status = ContextSummaryRecordStatus.ACTIVE)
}

internal fun revokeContextSummaries(records: List<ContextSummaryRecord>): List<ContextSummaryRecord> =
    records.map { record ->
        if (record.active) record.copy(active = false, status = ContextSummaryRecordStatus.REVOKED)
        else record
    }

/** Undo one activation while keeping every revision and the original messages. */
internal fun restorePreviousContextSummaryRevision(
    records: List<ContextSummaryRecord>,
    messages: List<ChatMessage>,
    scope: String
): List<ContextSummaryRecord> {
    val current = records.lastOrNull {
        it.active && it.status == ContextSummaryRecordStatus.ACTIVE
    } ?: return records
    val revoked = revokeContextSummaries(records)
    val byVersion = records.groupBy(ContextSummaryRecord::version)
    val seen = mutableSetOf(current.version)
    var parentVersion = current.parentRevision
    while (parentVersion != null && seen.add(parentVersion)) {
        val parent = byVersion[parentVersion]?.singleOrNull() ?: break
        if (parent.status != ContextSummaryRecordStatus.CANDIDATE &&
            parent.sourcesMatch(messages, scope)
        ) {
            return revoked.map { record ->
                if (record.id == parent.id) record.copy(active = true, status = ContextSummaryRecordStatus.ACTIVE)
                else record
            }
        }
        parentVersion = parent.parentRevision
    }
    return revoked
}

internal data class ContextSummarySourceHit(
    val evidenceId: String,
    val revision: Int,
    val kind: ContextSummaryKind,
    val text: String,
    val sourceMessageId: String,
    val sourceCreatedAt: Long,
    val sourceOffset: Int,
    val supersedesEvidenceId: String?
)

/** An exact-source lexical index, rebuilt from the persisted active revision. */
internal class ContextSummarySourceIndex private constructor(
    val hits: List<ContextSummarySourceHit>
) {
    private val bySource = hits.groupBy(ContextSummarySourceHit::sourceMessageId)
    private val byKind = hits.groupBy(ContextSummarySourceHit::kind)
    private val termsByHit = hits.map { contextSummarySearchTerms(it.text) }
    private val byTerm = buildMap<String, MutableSet<Int>> {
        termsByHit.forEachIndexed { index, terms ->
            terms.forEach { term -> getOrPut(term) { mutableSetOf() }.add(index) }
        }
    }

    fun fromSourceMessage(messageId: String): List<ContextSummarySourceHit> =
        bySource[messageId].orEmpty()

    fun ofKind(kind: ContextSummaryKind): List<ContextSummarySourceHit> =
        byKind[kind].orEmpty()

    fun search(query: String, limit: Int = 8): List<ContextSummarySourceHit> {
        if (limit <= 0) return emptyList()
        val terms = contextSummarySearchTerms(query)
        if (terms.isEmpty()) return emptyList()
        val scores = mutableMapOf<Int, Int>()
        terms.forEach { term -> byTerm[term]?.forEach { index ->
            scores[index] = scores.getOrDefault(index, 0) + 1
        } }
        return scores.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }
            .thenByDescending { hits[it.key].sourceCreatedAt }
            .thenBy { hits[it.key].sourceMessageId }
            .thenBy { hits[it.key].sourceOffset })
            .take(limit.coerceAtMost(50)).map { hits[it.key] }
    }

    companion object {
        fun fromActiveRevision(
            records: List<ContextSummaryRecord>,
            messages: List<ChatMessage>,
            scope: String
        ): ContextSummarySourceIndex {
            val active = records.filter { it.active && it.status == ContextSummaryRecordStatus.ACTIVE }
            val record = active.singleOrNull()?.takeIf { it.sourcesMatch(messages, scope) }
                ?: return ContextSummarySourceIndex(emptyList())
            val byId = messages.associateBy(ChatMessage::id)
            val sourceIds = record.sourceMessageIds.toSet()
            return ContextSummarySourceIndex(record.structuredSummary.evidence.mapNotNull { evidence ->
                if (!evidence.matchesSource(byId, sourceIds)) return@mapNotNull null
                val sourceId = evidence.sourceMessageIds.single()
                val evidenceId = contextSummaryDigest(JSONArray()
                    .put(sourceId).put(evidence.sourceOffset).put(evidence.text).toString())
                ContextSummarySourceHit(
                    evidenceId = evidenceId,
                    revision = record.version,
                    kind = evidence.kind,
                    text = evidence.text,
                    sourceMessageId = sourceId,
                    sourceCreatedAt = evidence.sourceCreatedAt,
                    sourceOffset = evidence.sourceOffset,
                    supersedesEvidenceId = evidence.supersedesEvidenceId
                )
            })
        }
    }
}

private fun ContextSummaryEvidence.matchesSource(
    byId: Map<String, ChatMessage>,
    foldedSourceIds: Set<String>
): Boolean {
    val source = sourceMessageIds.singleOrNull()?.let(byId::get) ?: return false
    return source.id in foldedSourceIds && sourceOffset >= 0 &&
        sourceLength == text.length && sourceOffset <= source.content.length - sourceLength &&
        source.content.regionMatches(sourceOffset, text, 0, sourceLength) &&
        sourceCreatedAt == source.createdAt && userPinned == source.pinned
}

private val CONTEXT_SUMMARY_SEARCH_WORDS = Regex("[\\p{IsHan}]+|[\\p{L}\\p{N}]+")

private fun contextSummarySearchTerms(text: String): Set<String> = buildSet {
    CONTEXT_SUMMARY_SEARCH_WORDS.findAll(text.lowercase(Locale.ROOT)).forEach { match ->
        val word = match.value
        if (Character.UnicodeScript.of(Character.codePointAt(word, 0)) == Character.UnicodeScript.HAN) {
            if (word.length == 1) add(word)
            else word.windowed(2).forEach { add(it) }
        } else if (word.length > 1 || word.all(Char::isDigit)) add(word)
    }
}

internal fun contextSummaryFingerprint(messages: List<ChatMessage>): String = contextSummaryDigest(
    JSONArray().apply {
        messages.forEach { message ->
            put(JSONArray().put(message.id).put(message.role.name).put(message.content)
                .put(message.createdAt).put(message.pinned)
                .put(JSONArray(message.imageAttachments.map { attachment ->
                    contextSummaryDigest(listOf(
                        attachment.uriString, attachment.dataBase64, attachment.mimeType,
                        attachment.width.toString(), attachment.height.toString(), attachment.sizeBytes.toString()
                    ).joinToString("\n"))
                })))
        }
    }.toString()
)

internal fun contextSummaryDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun contextSummariesToJson(records: List<ContextSummaryRecord>): String =
    JSONArray().apply { records.forEach { put(it.toJson()) } }.toString()

internal fun contextSummariesFromJson(raw: String?): List<ContextSummaryRecord> = runCatching {
    if (raw.isNullOrBlank()) return@runCatching emptyList()
    val array = JSONArray(raw)
    List(array.length()) { index ->
        runCatching {
            val record = array.getJSONObject(index)
            val ids = record.getJSONArray("sourceMessageIds")
            val revisionIds = record.optJSONArray("sourceRevisionMessageIds")
            val verifiedSchema = record.optInt("schemaVersion", 1) >= 2
            val active = verifiedSchema && record.optBoolean("active", false)
            ContextSummaryRecord(
                id = record.getString("id"), version = record.getInt("version"),
                source = record.getString("source"), text = record.getString("text"),
                sourceMessageIds = List(ids.length()) { ids.getString(it) },
                sourceFingerprint = record.getString("sourceFingerprint"),
                scopeFingerprint = record.getString("scopeFingerprint"),
                createdAt = record.getLong("createdAt"), active = active,
                parentRevision = record.optInt("parentRevision", -1).takeIf { it >= 0 },
                sourceRevisionMessageIds = revisionIds?.let { values ->
                    List(values.length()) { values.getString(it) }
                }.orEmpty(),
                sourceRevisionHash = record.optString("sourceRevisionHash"),
                structuredSummary = record.optJSONObject("structuredSummary").toStructuredSummary(),
                status = if (!verifiedSchema) ContextSummaryRecordStatus.REVOKED else runCatching {
                    ContextSummaryRecordStatus.valueOf(record.getString("status"))
                }.getOrDefault(ContextSummaryRecordStatus.REVOKED),
                summaryModelIdentity = record.takeUnless { it.isNull("summaryModelIdentity") }
                    ?.optString("summaryModelIdentity")?.takeIf(String::isNotBlank),
                summaryRuntimeIdentity = record.takeUnless { it.isNull("summaryRuntimeIdentity") }
                    ?.optString("summaryRuntimeIdentity")?.takeIf(String::isNotBlank),
                summaryDiagnostic = record.takeUnless { it.isNull("summaryDiagnostic") }
                    ?.optString("summaryDiagnostic")?.takeIf(String::isNotBlank),
                tokenEstimate = record.optInt("tokenEstimate", estimateLocalPromptTokens(record.getString("text")))
            )
        }.getOrNull()
    }.filterNotNull()
}.getOrDefault(emptyList())

private fun StructuredContextSummary.toJson(): JSONObject = JSONObject()
    .put("evidence", JSONArray().apply { evidence.forEach { item ->
        put(JSONObject().put("kind", item.kind.name).put("text", item.text)
            .put("sourceMessageIds", JSONArray(item.sourceMessageIds))
            .put("sourceCreatedAt", item.sourceCreatedAt).put("sourceOffset", item.sourceOffset)
            .put("sourceLength", item.sourceLength).put("userPinned", item.userPinned)
            .put("supersedesEvidenceId", item.supersedesEvidenceId ?: JSONObject.NULL))
    } })
    .put("sourceMessageCount", sourceMessageCount)
    .put("candidateExcerptCount", candidateExcerptCount)
    .put("omittedExcerptCount", omittedExcerptCount)
    .put("omittedCharacterCount", omittedCharacterCount)
    .put("coverageLimited", coverageLimited)

private fun JSONObject?.toStructuredSummary(): StructuredContextSummary {
    if (this == null) return StructuredContextSummary(coverageLimited = true)
    val items = optJSONArray("evidence")
    return StructuredContextSummary(
        evidence = List(items?.length() ?: 0) { index ->
            runCatching {
                val item = items!!.getJSONObject(index)
                val ids = item.getJSONArray("sourceMessageIds")
                ContextSummaryEvidence(
                    kind = ContextSummaryKind.valueOf(item.getString("kind")),
                    text = item.getString("text"),
                    sourceMessageIds = List(ids.length()) { ids.getString(it) },
                    sourceCreatedAt = item.getLong("sourceCreatedAt"),
                    sourceOffset = item.getInt("sourceOffset"),
                    sourceLength = item.getInt("sourceLength"),
                    userPinned = item.optBoolean("userPinned", false),
                    supersedesEvidenceId = item.takeUnless { it.isNull("supersedesEvidenceId") }
                        ?.optString("supersedesEvidenceId")
                        ?.takeIf(String::isNotBlank)
                )
            }.getOrNull()
        }.filterNotNull(),
        sourceMessageCount = optInt("sourceMessageCount", 0),
        candidateExcerptCount = optInt("candidateExcerptCount", 0),
        omittedExcerptCount = optInt("omittedExcerptCount", 0),
        omittedCharacterCount = optInt("omittedCharacterCount", 0),
        coverageLimited = optBoolean("coverageLimited", true)
    )
}
