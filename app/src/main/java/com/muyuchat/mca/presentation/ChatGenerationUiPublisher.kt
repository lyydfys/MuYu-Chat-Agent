package com.muyuchat.mca.presentation

import com.muyuchat.core.engine.ChatGenerationMetrics
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.TokenProgress
import com.muyuchat.core.telemetry.RuntimeMetrics
import com.muyuchat.feature.chat.BrowserTask
import com.muyuchat.feature.chat.BrowserTaskPhase
import com.muyuchat.feature.chat.BrowserTaskStateMachine
import com.muyuchat.mca.AssistantConversationSnapshot
import com.muyuchat.mca.ChatRunOwnership
import com.muyuchat.mca.ContextSummaryRecord
import com.muyuchat.mca.MainUiState
import com.muyuchat.mca.WebSearchDiagnosticRecord
import com.muyuchat.mca.WebSearchTurnMode
import com.muyuchat.mca.activateContextSummaryCandidate
import com.muyuchat.mca.withReplyGenerationMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

// The named publications a running chat generation writes into MainUiState.
//
// Why this exists: startGeneration carried twenty-odd `_uiState.update { … }` sites whose
// transform was an arbitrary `(MainUiState) -> MainUiState`, gated only by the run id. The guard
// is right; the unbounded transform is what makes the execution owner impossible to move out of
// the ViewModel, because every write can touch any field. These publications give the writes
// names and fixed signatures, so the eventual owner depends on this surface instead of on the
// transform type.
//
// Two deliberate write classes live here:
//
// 1. Run-gated publications (status, stream progress, completion bookkeeping, refusals). Each is
//    a verbatim move of one call site: same fields, same values, same guard shape. The gate is
//    the same two-check form as `MainViewModel.updateGenerationUi` -- checked before the update
//    and again inside it -- so a superseded run cannot publish, exactly as the inline sites could
//    not.
// 2. Pre-stream turn normalization (`publishNormalizedTurnParams`, `publishManualCompressionConsumed`,
//    `publishWebSearchTurnReset`, `publishWebSearchOutcome`). These inline sites were NOT
//    run-gated, and they are NOT gated here: their values were already frozen into the run's
//    own `initialState`/`baseParams` before the write, so adding a gate would change behaviour
//    rather than fix a race. They are named for the execution-owner seam and their un-gated
//    semantics are pinned by ChatGenerationUiPublisherTest.
// 3. Turn-boundary task and summary transitions (`publishBrowserSearchTaskStart`,
//    `publishBrowserSearchResults`, `publishContextSummaryActivation`). Each keeps the exact
//    guard its inline site carried: the run gate where the site had one, and the task-id /
//    phase / session-identity checks verbatim where it did not, so a task or session the UI no
//    longer owns is left untouched. The two methods that report a change (`...Start` published,
//    `...Activation` activated) return it so the caller keeps its local bookkeeping faithful.
//
// Guard semantics: both checks read `ChatRunOwnership.matches(generationRunId)`, the strict
// "an id was given and it matches" form. The publisher is constructed per run, bound to the same
// `_uiState` instance the ViewModel writes, so there is still exactly one state object.

internal class ChatGenerationUiPublisher(
    private val chatRuns: ChatRunOwnership,
    private val generationRunId: Long,
    private val uiState: MutableStateFlow<MainUiState>
) {

    private fun updateGenerationUi(transform: (MainUiState) -> MainUiState) {
        if (!chatRuns.matches(generationRunId)) return
        uiState.update { state ->
            if (chatRuns.matches(generationRunId)) transform(state) else state
        }
    }

    /** The compressed-context notice after the summary pass rewrote this turn's messages. */
    fun publishContextCompressedStatus(compressedMessageCount: Int) {
        updateGenerationUi {
            it.copy(statusMessage = "已压缩 $compressedMessageCount 条较早对话，保留角色设定和最近消息")
        }
    }

    /** The turn-context status line computed by ChatContextOperations.turnContextStatus. */
    fun publishContextStatusMessage(contextStatus: String) {
        updateGenerationUi { it.copy(statusMessage = contextStatus) }
    }

    /** The search hook's "searching now" line, published before the provider call. */
    fun publishWebSearchStartStatus(displayQuery: String) {
        updateGenerationUi {
            it.copy(
                webSearchStatusMessage = "正在联网检索：${displayQuery.take(42)}",
                statusMessage = "正在联网检索..."
            )
        }
    }

    /** The admitted request's context-window usage projection. */
    fun publishPromptContextUsage(usage: PromptContextUsage) {
        updateGenerationUi { it.copy(promptContextUsage = usage) }
    }

    /** stream Phase events: the running stats and the exact runtime stage. */
    fun publishStreamPhase(stats: RuntimeStats, phase: GenerationPhase?, tokenProgress: TokenProgress?) {
        updateGenerationUi {
            it.copy(
                stats = stats,
                generationPhase = phase,
                generationTokenProgress = tokenProgress,
                generationStats = stats
            )
        }
    }

    /** stream Chunk events: the running stats plus the DECODE stage entry. */
    fun publishStreamChunkStats(stats: RuntimeStats) {
        updateGenerationUi {
            it.copy(
                stats = stats
            ).withGenerationProgress(
                phase = GenerationPhase.DECODE,
                tokenProgress = null,
                persistProgress = null,
                stats = stats
            )
        }
    }

    /** stream Persist events: byte-level KV serialization progress. */
    fun publishPersistProgress(progress: PersistProgress) {
        updateGenerationUi { it.copy(generationPersistProgress = progress) }
    }

    /** completion bookkeeping: the engine's recent logs snapshot. */
    fun publishRuntimeLogs(logs: List<RuntimeMetrics>) {
        updateGenerationUi { it.copy(logs = logs) }
    }

    /**
     * A pre-stream refusal: no generation started, so the generation lock is released and the
     * exact reason is shown. This is the shared shape of the six refusal sites in
     * startGeneration (context overrun, missing cloud model, cloud vision not enabled, cloud
     * image read failure, local vision runner missing, local image preprocess failure). The six
     * copies differed only in [statusMessage]; they are folded into this one named write.
     */
    fun refuseGenerationBeforeStream(statusMessage: String?) {
        updateGenerationUi {
            it.copy(
                isGenerating = false,
                generationPhase = null,
                generationTokenProgress = null,
                generationPersistProgress = null,
                statusMessage = statusMessage
            )
        }
    }

    /**
     * The stream ended without a terminal event: release the lock only while the UI still reports a
     * running generation, and attach the reply metrics under the same gate. The metrics are finished
     * lazily -- [finishMetrics] runs only when the state is actually settled -- so a superseded or
     * already-settled generation does not consume the metrics snapshot.
     */
    fun publishFallbackTermination(
        stats: RuntimeStats,
        statusMessage: String,
        finishMetrics: () -> ChatGenerationMetrics?
    ) {
        updateGenerationUi { state ->
            if (state.isGenerating) {
                state.afterGenerationTerminated(stats, statusMessage)
                    .withReplyGenerationMetrics(finishMetrics())
            } else {
                state
            }
        }
    }

    /**
     * The turn's normalized parameters after they were persisted (local nPredict ceiling / cloud
     * reasoning overrides). Not run-gated: see write class 2 in the file comment.
     */
    fun publishNormalizedTurnParams(params: GenerationParams) {
        uiState.update { it.copy(params = params) }
    }

    /** The manual-compression flag flips off once the queued request is consumed. Not run-gated. */
    fun publishManualCompressionConsumed() {
        uiState.update { it.copy(contextCompressionPending = false) }
    }

    /** One-shot / research-override switches reset to FOLLOW for the next turn. Not run-gated. */
    fun publishWebSearchTurnReset() {
        uiState.update {
            it.copy(
                webSearchTurnMode = WebSearchTurnMode.FOLLOW,
                webSearchResearchModeOverride = null,
                webSearchOneShotEnabled = false
            )
        }
    }

    /**
     * The web-search outcome lines: the search status, the diagnostic appended for this turn and
     * the chat status line. Null inputs keep the current values, exactly like the inline site's
     * `?:` chains. Not run-gated.
     */
    fun publishWebSearchOutcome(
        webSearchStatusMessage: String?,
        diagnostics: List<WebSearchDiagnosticRecord>?,
        statusMessage: String?
    ) {
        uiState.update {
            it.copy(
                webSearchStatusMessage = webSearchStatusMessage ?: it.webSearchStatusMessage,
                webSearchDiagnostics = diagnostics ?: it.webSearchDiagnostics,
                statusMessage = statusMessage ?: it.statusMessage
            )
        }
    }

    /**
     * The background browser-search task becomes visible for this turn. Run-gated (the inline
     * site checked `generationStillOwnsUi()` inside its update) and guarded by "no visible search
     * window is replaced" verbatim. Returns whether the task was actually published so the caller
     * can retain its task id for the cancellation path.
     */
    fun publishBrowserSearchTaskStart(task: BrowserTask): Boolean {
        var published = false
        updateGenerationUi { current ->
            if (current.browserTask?.windowVisible != true) {
                published = true
                current.copy(browserTask = task)
            } else {
                current
            }
        }
        return published
    }

    /**
     * The search task receives its results. Identity-guarded by task id + SEARCHING phase,
     * verbatim from the inline site, which carried no run gate: a task the UI no longer owns is
     * left untouched rather than overwritten with another turn's results.
     */
    fun publishBrowserSearchResults(taskId: String, urls: List<String>, message: String?) {
        uiState.update { current ->
            val task = current.browserTask
            if (task?.taskId != taskId || task.phase != BrowserTaskPhase.SEARCHING) {
                current
            } else {
                current.copy(browserTask = BrowserTaskStateMachine.publishSearchResults(task, urls, message))
            }
        }
    }

    /**
     * Activates the completed turn's summary candidate inside the frozen session. Run-gated and
     * session-identity-guarded verbatim from the inline site; returns whether the session's
     * summary records actually changed so the caller can bump local-context invalidation.
     */
    fun publishContextSummaryActivation(
        candidate: ContextSummaryRecord,
        scope: String,
        expectedSessionId: String?,
        frozenSessionId: String,
        frozenAssistantId: String,
        frozenAssistantSnapshot: AssistantConversationSnapshot
    ): Boolean {
        var activated = false
        updateGenerationUi { current ->
            if (current.activeChatSessionId != expectedSessionId) {
                current
            } else {
                current.copy(chatSessions = current.chatSessions.map { session ->
                    if (session.id != frozenSessionId || session.mixedAssistantHistory ||
                        session.assistantId != frozenAssistantId ||
                        session.assistantSnapshot != frozenAssistantSnapshot
                    ) session else {
                        val records = activateContextSummaryCandidate(
                            session.contextSummaries, candidate, current.messages, scope
                        )
                        activated = records !== session.contextSummaries
                        session.copy(contextSummaries = records)
                    }
                })
            }
        }
        return activated
    }
}
