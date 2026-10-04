package com.muyuchat.mca.presentation

import com.muyuchat.core.engine.ChatGenerationMetrics
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.TokenProgress
import com.muyuchat.core.telemetry.RuntimeMetrics
import com.muyuchat.mca.ChatRunOwnership
import com.muyuchat.mca.MainUiState
import com.muyuchat.mca.withReplyGenerationMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

// The named publications a running chat generation writes into MainUiState.
//
// Why this exists: startGeneration carried twenty-odd `_uiState.update { … }` sites whose
// transform was an arbitrary `(MainUiState) -> MainUiState`, gated only by the run id. The guard
// is right; the unbounded transform is what makes the execution owner impossible to move out of
// the ViewModel, because every write can touch any field. These publications give the gated
// writes names and fixed signatures, so the eventual owner depends on this surface instead of on
// the transform type. The first group (this file) covers the sites that publish status, stream
// progress and completion bookkeeping; they neither write `isGenerating` nor carry side effects.
//
// Every method is a verbatim move of one call site: same fields, same values, same guard shape.
// The gate below is the same two-check form as `MainViewModel.updateGenerationUi` -- checked
// before the update and again inside it -- so a superseded run cannot publish, exactly as the
// inline sites could not.
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
}
