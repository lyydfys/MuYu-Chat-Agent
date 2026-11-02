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
 * P03: the stop captures the run's exact local attempt identity atomically with the cancellation,
 * before any engine call. The engine stop is then dispatched for that frozen request only -- never
 * for "whatever token is current" -- so a late chat stop can never stop an Agent, Local API or
 * image request that has since taken the engine, and a chat run with no dispatched attempt never
 * touches the engine at all. The user feedback and the settlement/persist tail are unchanged.
 *
 * Moved verbatim out of `MainViewModel.stopGeneration` (R1 item 2, stop path). The ViewModel
 * keeps `stopGenerationInProgress` as the re-entrancy latch and supplies every collaborator
 * through this port; the orchestration below has no direct field access.
 */
internal interface ChatStopGenerationPort {

    fun currentState(): MainUiState

    fun updateUiState(transform: (GenerationStopFields) -> GenerationStopFields): GenerationStopFields

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

    /** Cancels the owning UI run and cancels its Job; returns the ownership cancellation verbatim. */
    fun cancelCurrentGeneration(): UiGenerationCancellation

    /**
     * The exact local engine attempt frozen with [cancellation], or null when no engine stop is
     * needed.
     *
     * The decision is made only from the [UiGenerationCancellation] captured under the ownership
     * lock: never from the engine's current token and never from the phase of some other run. Cloud
     * turns and reservations that never dispatched a local request return null.
     */
    fun frozenLocalAttempt(cancellation: UiGenerationCancellation): ChatAttemptIdentity?

    /**
     * Stops only [attempt], the identity frozen before the run was cancelled.
     *
     * Implementations must not look up a current token/target at call time. A stop for an attempt
     * that already ended is a no-op.
     */
    suspend fun requestRuntimeStop(attempt: ChatAttemptIdentity)

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
    if (!port.currentState().isGenerating) {
        port.exitStop()
        return
    }
    val metrics = port.currentReplyMetrics()?.takeIf { port.isRunCurrent(it.runId) }?.finish()
    // Freeze the exact attempt atomically with the ownership cancellation, on the caller's
    // thread, before the stop coroutine can race a replacement run. `cancellation.localAttempt`
    // is the only engine target this stop may ever use (P03).
    val cancellation = port.cancelCurrentGeneration()
    val stoppedJob = cancellation.owner as? Job
    // The epoch captured by the cancellation itself, not a later "current" read: a replacement
    // that starts after this point must invalidate the stop instead of being mistaken for it.
    val cancelledEpoch = cancellation.invalidatedRunId
    port.stopScope().launch {
        try {
            // A replacement run may have started between the synchronous freeze and this
            // dispatcher hop. The exact attempt stop below is still safe (it can only name the
            // frozen request), but the UI and browser task belong to the replacement now.
            val ownsUiAtEntry = port.isRunCurrent(cancelledEpoch)
            if (ownsUiAtEntry) {
                port.updateUiState { state ->
                    val task = state.browserTask
                    if (task?.phase != BrowserTaskPhase.SEARCHING) state
                    else state.copy(browserTask = BrowserTaskStateMachine.cancel(task, "Generation stopped"))
                }
            }
            // The worker stop request is one-way and must not wait behind a
            // native decode mutex. A wedged Binder decode is bounded here so
            // the UI can recover even before the isolated worker watchdog
            // finishes reclaiming its process.
            val frozenAttempt = port.frozenLocalAttempt(cancellation)
            val stopRequested = frozenAttempt?.let { attempt ->
                withTimeoutOrNull(STOP_GENERATION_REQUEST_TIMEOUT_MS) {
                    port.requestRuntimeStop(attempt)
                    true
                } == true
            } ?: true
            if (!ownsUiAtEntry || !port.isRunCurrent(cancelledEpoch)) return@launch
            val joined = withTimeoutOrNull(STOP_GENERATION_JOIN_TIMEOUT_MS) {
                stoppedJob?.join()
                true
            } == true
            if (!port.isRunCurrent(cancelledEpoch)) return@launch
            port.flushPendingAssistantOutput()
            val rollback = port.rollbackState()
            val stoppedLifecycle = port.runtimeLifecycle()
            val settled = port.updateUiState {
                val updated = it.withReplyGenerationMetrics(metrics)
                updated.copy(
                    isGenerating = false,
                    engineLifecycle = stoppedLifecycle,
                    statusMessage = if (stopRequested && joined) {
                        "已停止生成"
                    } else {
                        "已请求停止，正在恢复本地推理进程…"
                    }
                ).withGenerationProgressCleared()
            }
            port.persistConversationMutation(settled.chatSessions, rollback)
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
