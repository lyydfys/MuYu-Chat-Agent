package com.muyuchat.mca

import android.content.Context
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import com.muyuchat.core.engine.PrefixCacheKey
import java.util.UUID

/**
 * T2B22-T3（b 工位）拆片：AssistantConversationSnapshot / toConversationSnapshot /
 * withBackfilledAssistantSnapshots 自 AssistantStore.kt 逐字节平移（含其上方 KDoc）；零改写。
 */

/**
 * T2B22-T3（b 工位）拆片：会话快照组自 AssistantStore.kt 逐字节平移；零改写。
 */
/**
 * The persona contract captured by a conversation when the user chooses an
 * assistant.  Generation controls remain live, but the system prompt and
 * assistant-scoped capabilities stay stable for the life of that conversation.
 * This is important both for conversational continuity and for a reusable
 * llama.cpp prefix cache.
 */
data class AssistantConversationSnapshot(
    val assistantId: String,
    val name: String,
    val systemPrompt: String,
    val memoryEnabled: Boolean,
    val webSearchEnabled: Boolean,
    val fileContextEnabled: Boolean,
    val capturedAt: Long,
    /** Earlier prompt versions explicitly applied to this same role and conversation. */
    val priorSystemPromptHashes: List<String> = emptyList(),
    /** Captured independently from later profile edits; absent legacy metadata remains unknown. */
    val systemPromptProvenance: AssistantPromptProvenance = AssistantPromptProvenance.LEGACY_UNKNOWN
) {
    init {
        require(assistantId.isNotBlank()) { "Assistant snapshot requires an assistant id." }
        require(systemPrompt.isNotBlank()) { "Assistant snapshot requires a system prompt." }
        require(capturedAt >= 0L) { "Assistant snapshot capture time must not be negative." }
        require(priorSystemPromptHashes.size <= 32 && priorSystemPromptHashes.all(PrefixCacheKey::isSha256Hex)) {
            "Invalid earlier prompt fingerprints."
        }
    }

    fun applyTo(params: GenerationParams): GenerationParams =
        params.copy(systemPrompt = systemPrompt)

    fun toJsonString(): String = JSONObject()
        .put("schema", SCHEMA)
        .put("version", VERSION)
        .put("assistantId", assistantId)
        .put("name", name)
        .put("systemPrompt", systemPrompt)
        .put("memoryEnabled", memoryEnabled)
        .put("webSearchEnabled", webSearchEnabled)
        .put("fileContextEnabled", fileContextEnabled)
        .put("capturedAt", capturedAt)
        .put("priorSystemPromptHashes", JSONArray(priorSystemPromptHashes))
        .put("systemPromptProvenance", systemPromptProvenance.name)
        .toString()

    companion object {
        const val SCHEMA = "mca.assistant.conversation_snapshot"
        const val VERSION = 1

        fun fromAssistant(
            assistant: AssistantRecord,
            capturedAt: Long = System.currentTimeMillis()
        ): AssistantConversationSnapshot {
            val prompt = assistant.systemPrompt
                .trim()
                .take(AssistantRecord.MAX_SYSTEM_PROMPT_CHARS)
                .ifBlank { GenerationParams().systemPrompt }
            return AssistantConversationSnapshot(
                assistantId = assistant.id.trim().take(MAX_ASSISTANT_ID_CHARS)
                    .ifBlank { AssistantRecord.DEFAULT_ID },
                name = assistant.name.trim().take(MAX_ASSISTANT_NAME_CHARS).ifBlank { "Assistant" },
                systemPrompt = prompt,
                memoryEnabled = assistant.memoryEnabled,
                webSearchEnabled = assistant.webSearchEnabled,
                fileContextEnabled = assistant.fileContextEnabled,
                capturedAt = capturedAt.coerceAtLeast(0L),
                systemPromptProvenance = assistant.systemPromptProvenance
            )
        }

        fun fromJsonOrNull(raw: String?): AssistantConversationSnapshot? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                val schema = json.optString("schema").trim()
                require(schema.isBlank() || schema == SCHEMA) {
                    "Unsupported assistant snapshot schema."
                }
                val version = if (json.has("version")) json.optInt("version", -1) else VERSION
                require(version == VERSION) {
                    "Unsupported assistant snapshot version."
                }
                val assistantId = json.optString("assistantId")
                    .trim()
                    .take(MAX_ASSISTANT_ID_CHARS)
                val systemPrompt = json.optString("systemPrompt")
                    .trim()
                    .take(AssistantRecord.MAX_SYSTEM_PROMPT_CHARS)
                AssistantConversationSnapshot(
                    assistantId = assistantId,
                    name = json.optString("name")
                        .trim()
                        .take(MAX_ASSISTANT_NAME_CHARS)
                        .ifBlank { "Assistant" },
                    systemPrompt = systemPrompt,
                    memoryEnabled = json.optBoolean("memoryEnabled", false),
                    webSearchEnabled = json.optBoolean("webSearchEnabled", false),
                    fileContextEnabled = !json.has("fileContextEnabled") ||
                        json.optBoolean("fileContextEnabled", true),
                    capturedAt = json.optLong("capturedAt", 0L).coerceAtLeast(0L),
                    priorSystemPromptHashes = json.optJSONArray("priorSystemPromptHashes")?.let { values ->
                        (0 until values.length().coerceAtMost(32)).mapNotNull { index ->
                            values.optString(index).takeIf(PrefixCacheKey::isSha256Hex)
                        }.distinct()
                    }.orEmpty(),
                    systemPromptProvenance = AssistantPromptProvenance.fromWireValue(
                        json.optString("systemPromptProvenance")
                    )
                )
            }.getOrNull()
        }

        private const val MAX_ASSISTANT_ID_CHARS = 128
        private const val MAX_ASSISTANT_NAME_CHARS = 96
    }
}

internal fun AssistantRecord.toConversationSnapshot(
    capturedAt: Long = System.currentTimeMillis()
): AssistantConversationSnapshot = AssistantConversationSnapshot.fromAssistant(this, capturedAt)

/**
 * Backfills a legacy conversation only when its recorded assistant still exists.
 * Existing authored snapshots stay immutable. The one exception is an untouched, exact MCA
 * boilerplate snapshot of the same external card whose persisted persona has just been recovered.
 * Unknown owners stay mixed and viewable.
 */
internal fun List<ChatSessionRecord>.withBackfilledAssistantSnapshots(
    assistants: List<AssistantRecord>
): List<ChatSessionRecord> {
    return map { session ->
        val assistant = session.assistantId
            ?.let { assistantId -> assistants.firstOrNull { it.id == assistantId } }
        val existing = session.assistantSnapshot
        if (existing != null) {
            if (!session.mixedAssistantHistory && assistant != null &&
                existing.assistantId == assistant.id && existing.priorSystemPromptHashes.isEmpty() &&
                existing.systemPromptProvenance != AssistantPromptProvenance.USER_AUTHORED &&
                isMcaDefaultAssistantPrompt(existing.systemPrompt)) {
                val recovered = restoreLegacyImportedCharacterCardPrompt(
                    assistant.copy(systemPrompt = existing.systemPrompt), GenerationParams()
                )
                if (recovered.systemPrompt != existing.systemPrompt &&
                    recovered.systemPrompt == assistant.systemPrompt) {
                    session.copy(assistantSnapshot = existing.copy(systemPrompt = recovered.systemPrompt))
                } else session
            } else session
        } else if (assistant == null) {
            session.copy(mixedAssistantHistory = true)
        } else {
            session.copy(
                assistantSnapshot = assistant.toConversationSnapshot(
                    capturedAt = session.updatedAt.coerceAtLeast(0L)
                )
            )
        }
    }
}
