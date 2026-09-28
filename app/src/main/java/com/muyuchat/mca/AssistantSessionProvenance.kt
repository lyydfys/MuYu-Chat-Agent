package com.muyuchat.mca

import java.security.MessageDigest
import org.json.JSONObject

internal data class AssistantSessionSaveDecision(
    val session: ChatSessionEntity,
    val preservePersistedMessages: Boolean
)

internal object AssistantSessionProvenance {
    fun isTrustedOwner(session: ChatSessionEntity, assistantId: String): Boolean {
        if (session.mixedAssistantHistory || session.assistantId != assistantId) return false
        val captured = session.assistantSnapshotJson ?: return false
        return AssistantConversationSnapshot.fromJsonOrNull(captured)?.assistantId == assistantId
    }

    fun resolve(
        persisted: ChatSessionEntity?,
        requested: ChatSessionEntity,
        messages: List<ChatMessageEntity>
    ): AssistantSessionSaveDecision {
        val capturedId = snapshotId(requested.assistantSnapshotJson)
        val requestedOwner = capturedId ?: requested.assistantId
        val persistedOwner = persisted?.let { snapshotId(it.assistantSnapshotJson) ?: it.assistantId }
        val ownerChanged = persisted != null && persistedOwner != requestedOwner
        if (ownerChanged) {
            // A session id is never reused to move a transcript to another persona.
            return AssistantSessionSaveDecision(
                requested.copy(
                    assistantId = persisted.assistantId,
                    assistantSnapshotJson = persisted.assistantSnapshotJson,
                    contextSummariesJson = persisted.contextSummariesJson,
                    modelMode = persisted.modelMode,
                    modelId = persisted.modelId,
                    mixedAssistantHistory = true
                ),
                preservePersistedMessages = true
            )
        }
        if (persisted?.mixedAssistantHistory == true) {
            return AssistantSessionSaveDecision(
                requested.copy(
                    assistantId = persisted.assistantId,
                    assistantSnapshotJson = persisted.assistantSnapshotJson,
                    contextSummariesJson = persisted.contextSummariesJson,
                    modelMode = persisted.modelMode,
                    modelId = persisted.modelId,
                    mixedAssistantHistory = true
                ),
                preservePersistedMessages = true
            )
        }
        val snapshot = AssistantConversationSnapshot.fromJsonOrNull(requested.assistantSnapshotJson)
        val mixed = requested.mixedAssistantHistory || persisted?.mixedAssistantHistory == true ||
            (requested.assistantSnapshotJson == null && messages.isNotEmpty()) ||
            (requested.assistantSnapshotJson != null && snapshot == null && messages.isNotEmpty()) ||
            (snapshot != null && (
                hasConflictingPromptTrace(snapshot, messages) ||
                    messages.any { message ->
                        (message.role == "ASSISTANT" || message.role == "USER") &&
                            (snapshot.capturedAt == 0L || message.createdAt < snapshot.capturedAt) &&
                            tracePromptHash(message.contextAssemblyTraceJson) == null &&
                            snapshot.priorSystemPromptHashes.isEmpty()
                    }
                ))
        return AssistantSessionSaveDecision(
            requested.copy(
                assistantId = requestedOwner,
                contextSummariesJson = if (mixed && persisted != null) {
                    persisted.contextSummariesJson
                } else {
                    requested.contextSummariesJson
                },
                mixedAssistantHistory = mixed
            ),
            preservePersistedMessages = mixed && persisted != null
        )
    }

    private fun snapshotId(raw: String?): String? =
        AssistantConversationSnapshot.fromJsonOrNull(raw)?.assistantId

    private fun hasConflictingPromptTrace(
        snapshot: AssistantConversationSnapshot,
        messages: List<ChatMessageEntity>
    ): Boolean {
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(snapshot.systemPrompt.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val accepted = snapshot.priorSystemPromptHashes.toSet() + expected
        return messages.any { message ->
            tracePromptHash(message.contextAssemblyTraceJson)?.let { it !in accepted } == true
        }
    }

    private fun tracePromptHash(raw: String?): String? = runCatching {
        raw?.let { JSONObject(it).optString("rolePromptHash") }
            ?.takeIf { value -> value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' } }
    }.getOrNull()
}
