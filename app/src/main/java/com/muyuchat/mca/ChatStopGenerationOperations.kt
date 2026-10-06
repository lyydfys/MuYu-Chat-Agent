package com.muyuchat.mca

import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.chat.BrowserTaskPhase
import com.muyuchat.feature.chat.BrowserTaskStateMachine
import com.muyuchat.mca.presentation.withGenerationProgressCleared
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// A stop tap must never wait behind a wedged Binder/JNI call.  The
// isolated worker owns the longer cancellation grace period and can
// reclaim itself independently; the UI only waits long enough to
// enqueue the request before releasing its generation state.
internal const val STOP_GENERATION_REQUEST_TIMEOUT_MS = 750L
internal const val STOP_GENERATION_JOIN_TIMEOUT_MS = 2_000L

/**
 * The owner seam for the user-facing stop path: reject during a conversation mutation, admit one
 * stop at a time, cancel the current UI run, cancel its browser task, bound the native stop
 * request and the join, settle the UI state, and persist the settled conversation.
 *
 * Moved verbatim out of `MainViewModel.stopGeneration` (R1 item 2, stop path). The ViewModel
 * keeps `stopGenerationInProgress` as the re-entrancy latch and supplies every collaborator
 * through this port; the orchestration below has no direct field access.
 */
internal interface ChatStopGenerationPort {

    fun currentState(): MainUiState

    fun updateUiState(transform: (MainUiState) -> MainUiState)

    /** The scope a stop runs in (the ViewModel scope). */
    fun stopScope(): CoroutineScope

    /** The conversation-mutation barrier check; a stop during a mutation is refused. */
    fun rejectWhileConversationMutationInProgress(): Boolean

    /** Claims the one-at-a-time stop latch; false when another stop is already in flight. */
    fun tryEnterStop(): Boolean

    fun exitStop()

    /** The metrics of the run being stopped, or null when none is current. */
    fun currentReplyMetrics(): ReplyGenerationMetrics?

    fun isRunCurrent(runId: Long?): Boolean

    fun currentRunId(): Long

    /** Cancels the owning UI run and cancels its Job; returns the ownership cancellation verbatim. */
    fun cancelCurrentGeneration(): UiGenerationCancellation

    /** The request id of the active native stop target, when the runtime exposes one. */
    fun activeUiStopRequestId(): String?

    /** The runtime stop is suspend: the service stop call suspends its caller. */
    suspend fun requestRuntimeStop()

    fun runtimeLifecycle(): AgentEngineLifecycle

    /** The rollback snapshot of the conversation as of this exact revision. */
    fun rollbackState(): ConversationMutationRollbackState

    fun flushPendingAssistantOutput()

    fun persistConversationMutation(
        sessions: List<ChatSessionRecord>,
        rollback: ConversationMutationRollbackState
    )
}

internal fun stopActiveGeneration(port: ChatStopGenerationPort) {
    if (port.rejectWhileConversationMutationInProgress()) return
    // Regeneration reserves the UI turn before its Room/KV mutation finishes.
    // Stop must invalidate that reservation even while the mutation barrier
    // is active; its eventual onCommitted callback cannot reactivate it.
    if (!port.tryEnterStop()) return
    port.stopScope().launch {
        try {
            if (!port.currentState().isGenerating) return@launch
            val metrics = port.currentReplyMetrics()?.takeIf { port.isRunCurrent(it.runId) }
                ?.finish()
            val cancellation = port.cancelCurrentGeneration()
            val stoppedJob = cancellation.owner as? Job
            val stopLocalRuntime = cancellation.stopLocalRuntime ||
                (cancellation.pendingCancelled &&
                    port.activeUiStopRequestId()?.startsWith("ui-") == true)
            val cancelledEpoch = port.currentRunId()
            port.updateUiState { state ->
                val task = state.browserTask
                if (task?.phase != BrowserTaskPhase.SEARCHING) state
                else state.copy(browserTask = BrowserTaskStateMachine.cancel(task, "Generation stopped"))
            }
            // The worker stop request is one-way and must not wait behind a
            // native decode mutex. A wedged Binder decode is bounded here so
            // the UI can recover even before the isolated worker watchdog
            // finishes reclaiming its process.
            val stopRequested = !stopLocalRuntime ||
                withTimeoutOrNull(STOP_GENERATION_REQUEST_TIMEOUT_MS) {
                    port.requestRuntimeStop()
                    true
                } == true
            val joined = withTimeoutOrNull(STOP_GENERATION_JOIN_TIMEOUT_MS) {
                stoppedJob?.join()
                true
            } == true
            if (!port.isRunCurrent(cancelledEpoch)) return@launch
            port.flushPendingAssistantOutput()
            val rollback = port.rollbackState()
            var sessionsToPersist: List<ChatSessionRecord> = emptyList()
            port.updateUiState {
                val updated = it.withReplyGenerationMetrics(metrics)
                sessionsToPersist = updated.chatSessions
                updated.copy(
                    isGenerating = false,
                    engineLifecycle = port.runtimeLifecycle(),
                    statusMessage = if (stopRequested && joined) {
                        "已停止生成"
                    } else {
                        "已请求停止，正在恢复本地推理进程…"
                    }
                ).withGenerationProgressCleared()
            }
            port.persistConversationMutation(sessionsToPersist, rollback)
        } finally {
            port.exitStop()
        }
    }
}

internal fun MainUiState.conversationMutationRollbackState(): ConversationMutationRollbackState =
    ConversationMutationRollbackState(
        messages = messages,
        activeChatSessionId = activeChatSessionId,
        chatSessions = chatSessions,
        selectedKnowledgeBaseIds = selectedKnowledgeBaseIds,
        generationPhase = generationPhase,
        generationTokenProgress = generationTokenProgress,
        promptContextUsage = promptContextUsage
    )
