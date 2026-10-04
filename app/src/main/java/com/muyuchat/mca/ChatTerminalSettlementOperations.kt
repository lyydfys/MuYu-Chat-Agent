package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatWebSearchTrace
import com.muyuchat.core.engine.Role
import java.util.UUID
import kotlinx.coroutines.flow.update

/**
 * The owner seam for terminal settlement: what happens to the message and the diagnostics when
 * a generation finishes -- guard the answer text, make sure a visible reply exists, record the
 * citation audit, drop a stale placeholder, and attach the search evidence.
 *
 * Every one of these runs *after* the stream ended, which is exactly where a stale callback is
 * most dangerous: the run they belong to may already have been superseded. They all ask
 * [isRunCurrent] before touching state, and they reach the stream buffer only through
 * [discardPendingOutput] -- no lock, no job and no flow crosses this seam.
 */
internal interface ChatTerminalSettlementPort {

    fun uiState(): MainUiState

    fun updateUiState(transform: (MainUiState) -> MainUiState)

    val webSearchDiagnosticStore: WebSearchDiagnosticStore

    /** The chat model id the turn is attributed to. */
    fun currentChatModelId(state: MainUiState): String?

    /** Inserts or replaces the session and returns the resulting list. */
    fun upsertSession(
        sessions: List<ChatSessionRecord>,
        sessionId: String,
        messages: List<ChatMessage>,
        assistantId: String?,
        modelMode: String?,
        modelId: String?
    ): List<ChatSessionRecord>

    /** Whether [runId] still owns the UI run; a null id means unbound and is always current. */
    fun isRunCurrent(runId: Long?): Boolean

    /** Drops whatever the stream accumulator has buffered for [runId]. */
    fun discardPendingOutput(runId: Long? = null)
}

    internal fun settleWebSearchAnswerGuards(
    port: ChatTerminalSettlementPort,
        generationRunId: Long? = null
    ): WebSearchCitationAudit? {
        if (generationRunId != null && !port.isRunCurrent(generationRunId)) return null
        var citationAudit: WebSearchCitationAudit? = null
        port.updateUiState { state ->
            if (generationRunId != null && !port.isRunCurrent(generationRunId)) {
                return@updateUiState state
            }
            val updated = state.messages.toMutableList()
            val index = updated.indexOfLast { it.role == Role.ASSISTANT }
            if (index < 0) return@updateUiState state
            val guardResult = updated[index].withWebSearchAnswerGuards()
            citationAudit = guardResult.citationAudit
            if (guardResult.message == updated[index]) return@updateUiState state
            updated[index] = guardResult.message
            val sessionId = state.activeChatSessionId
            state.copy(
                messages = updated,
                chatSessions = if (sessionId == null) {
                    state.chatSessions
                } else {
                    port.upsertSession(
                        sessions = state.chatSessions,
                        sessionId = sessionId,
                        messages = updated,
                        assistantId = state.selectedAssistantId,
                        modelMode = state.selectedChatBackend.bindingValue(),
                        modelId = port.currentChatModelId(state)
                    )
                }
            )
        }
        return citationAudit
    }

    internal fun settleVisibleAssistantReply(
    port: ChatTerminalSettlementPort,generationRunId: Long? = null) {
        if (generationRunId != null && !port.isRunCurrent(generationRunId)) return
        port.updateUiState { state ->
            if (generationRunId != null && !port.isRunCurrent(generationRunId)) {
                return@updateUiState state
            }
            val updated = state.messages.withVisibleAssistantCompletionFallback()
            if (updated === state.messages) return@updateUiState state
            val sessionId = state.activeChatSessionId
            state.copy(
                messages = updated,
                chatSessions = if (sessionId == null) {
                    state.chatSessions
                } else {
                    port.upsertSession(
                        sessions = state.chatSessions,
                        sessionId = sessionId,
                        messages = updated,
                        assistantId = state.selectedAssistantId,
                        modelMode = state.selectedChatBackend.bindingValue(),
                        modelId = port.currentChatModelId(state)
                    )
                }
            )
        }
    }

    internal fun settleWebSearchCitationAudit(
    port: ChatTerminalSettlementPort,
        recordId: String?,
        audit: WebSearchCitationAudit,
        generationRunId: Long? = null
    ) {
        if (recordId.isNullOrBlank()) return
        if (generationRunId != null && !port.isRunCurrent(generationRunId)) return
        val current = port.uiState().webSearchDiagnostics.firstOrNull { it.id == recordId } ?: return
        val updatedRecord = current.copy(
            message = current.message + if (audit.repaired) " · 引用已修正" else " · 引用已审计",
            warnings = (current.warnings + audit.warnings).distinct(),
            closedLoopChecks = (current.closedLoopChecks + audit.closedLoopChecks).distinct()
        )
        val records = port.webSearchDiagnosticStore.replace(updatedRecord)
        port.updateUiState {
            if (generationRunId != null && !port.isRunCurrent(generationRunId)) {
                it
            } else {
                it.copy(
                    webSearchDiagnostics = records,
                    webSearchStatusMessage = audit.statusMessage,
                    statusMessage = audit.statusMessage
                )
            }
        }
    }

    internal fun settlePendingAssistantPlaceholder(
    port: ChatTerminalSettlementPort,generationRunId: Long? = null) {
        port.discardPendingOutput(generationRunId)
        port.updateUiState { state ->
            if (generationRunId != null && !port.isRunCurrent(generationRunId)) {
                return@updateUiState state
            }
            val index = state.messages.indexOfLast { it.role == Role.ASSISTANT }
            if (index < 0) return@updateUiState state
            val pending = state.messages[index]
            if (pending.content.isNotBlank() || pending.reasoningContent.isNotBlank()) return@updateUiState state
            val updated = state.messages.filterIndexed { messageIndex, _ -> messageIndex != index }
            val sessionId = state.activeChatSessionId
            state.copy(
                messages = updated,
                chatSessions = if (sessionId == null) {
                    state.chatSessions
                } else {
                    port.upsertSession(
                        sessions = state.chatSessions,
                        sessionId = sessionId,
                        messages = updated,
                        assistantId = state.selectedAssistantId,
                        modelMode = state.selectedChatBackend.bindingValue(),
                        modelId = port.currentChatModelId(state)
                    )
                }
            )
        }
    }

    internal fun settleWebSearchEvidenceAttachment(
    port: ChatTerminalSettlementPort,
        sources: List<ChatSourceReference>,
        trace: ChatWebSearchTrace?,
        generationRunId: Long? = null
    ) {
        if (sources.isEmpty() && trace?.hasContent != true) return
        if (generationRunId != null && !port.isRunCurrent(generationRunId)) return
        port.updateUiState { state ->
            if (generationRunId != null && !port.isRunCurrent(generationRunId)) {
                return@updateUiState state
            }
            val updated = state.messages.toMutableList()
            val index = updated.indexOfLast { it.role == Role.ASSISTANT }
            if (index >= 0) {
                val current = updated[index]
                updated[index] = current.copy(
                    sourceReferences = sources.ifEmpty { current.sourceReferences },
                    webSearchTrace = trace ?: current.webSearchTrace
                )
            } else {
                updated += ChatMessage(
                    role = Role.ASSISTANT,
                    content = "",
                    sourceReferences = sources,
                    webSearchTrace = trace
                )
            }
            val sessionId = state.activeChatSessionId ?: UUID.randomUUID().toString()
            state.copy(
                messages = updated,
                activeChatSessionId = sessionId,
                chatSessions = port.upsertSession(
                        sessions = state.chatSessions,
                    sessionId = sessionId,
                    messages = updated,
                    assistantId = state.selectedAssistantId,
                    modelMode = state.selectedChatBackend.bindingValue(),
                    modelId = port.currentChatModelId(state)
                )
            )
        }
    }
