package com.muyuchat.mca

import com.muyuchat.api.local.LocalApiRuntime
import com.muyuchat.core.engine.ChatExecutionBody
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.mca.facade.ChatExecutionFacade
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.mca.presentation.afterGenerationCompleted
import com.muyuchat.mca.presentation.afterGenerationStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/**
 * The Local API side of the chat execution owner: installs the runtime providers that route HTTP /
 * Local API chat traffic into the shared [McaInferenceService] and mirror the generation lifecycle
 * into [MainUiState]; also serves the chat stop entry points and projects the loaded model. Moved verbatim from MainViewModel (P3 step 2); the only call site is
 * MainViewModel's Local API assembly ([installChatLocalApiProviders]).
 */
internal interface ChatLocalApiProviderPort {
    val engine: McaInferenceService
    val chatRuns: ChatRunOwnership

    /**
     * The single chat execution facade (E06). Null keeps the historical direct-engine routing for
     * focused tests that assemble the port without the runtime supervisor.
     */
    val chatExecutionFacade: ChatExecutionFacade
    // R3/S26: 契约收敛——端口只暴露只读 StateFlow 与具名写入方法。
    val uiState: StateFlow<MainUiState>
    fun updateUiState(transform: (GenerationLifecycleFields) -> GenerationLifecycleFields)

    fun mergeExecutionProfile(generation: GenerationParams, profile: ModelExecutionProfile): GenerationParams

    fun cancelActiveLocalApiImageGeneration(reason: String): Boolean

    fun loadedModelJson(): String
}

internal fun installChatLocalApiProviders(port: ChatLocalApiProviderPort) {
    val engine = port.engine
    val chatRuns = port.chatRuns
    val uiState = port.uiState
    LocalApiRuntime.nativeStatsJsonProvider = { engine.nativeStatsJson() }
    LocalApiRuntime.streamChatWithContextProvider = { request, executionContext ->
        val uiRequestId = executionContext.requestId.takeIf { it.startsWith("ui-") }
        // UI requests encode the epoch that created them. Capturing the
        // current value here would let an old coroutine that reaches this
        // provider late claim a newer generation's lifecycle callbacks.
        val uiLifecycleRunId = uiRequestId
            ?.removePrefix("ui-")
            ?.substringBefore('-')
            ?.toLongOrNull()
            ?: uiRequestId?.let { chatRuns.currentRunId() }

        fun ownsUiLifecycle(): Boolean = uiLifecycleRunId == null ||
            chatRuns.matches(uiLifecycleRunId)

        val effectiveRequest = if (
            executionContext.loadAuthorization == null &&
            executionContext.requestId.startsWith("ui-")
        ) {
            engine.activeExecutionProfile()
                ?.let { profile -> request.copy(params = port.mergeExecutionProfile(request.params, profile)) }
                ?: request
        } else {
            request
        }
        val routedStream = port.chatExecutionFacade.streamChat(effectiveRequest, executionContext)
        routedStream
            .onStart {
                if (ownsUiLifecycle()) {
                    port.updateUiState { state ->
                        if (ownsUiLifecycle()) state.afterGenerationStarted() else state
                    }
                }
            }
            .onCompletion {
                if (ownsUiLifecycle()) {
                    val completedStats = engine.stats.value
                    port.updateUiState { state ->
                        if (ownsUiLifecycle()) {
                            state.afterGenerationCompleted(completedStats)
                        } else {
                            state
                        }
                    }
                }
            }
    }
    LocalApiRuntime.stopGenerationProvider = {
        port.cancelActiveLocalApiImageGeneration("Local API generation was stopped by the client.")
        port.chatExecutionFacade.stopGeneration()
    }
    LocalApiRuntime.stopGenerationIfRequestActiveProvider = { requestId ->
        port.chatExecutionFacade.stopGenerationIfActive(requestId)
    }
    LocalApiRuntime.loadedModelJsonProvider = { port.loadedModelJson() }
}

/**
 * Production execution body (E06): the engine call itself, unchanged. The facade/owner add
 * admission identity and terminal settlement on top; the engine stays the single implementation
 * of the actual generation for every channel.
 */
internal fun chatExecutionBodyFor(engine: McaInferenceService): ChatExecutionBody =
    object : ChatExecutionBody {
        override fun stream(
            request: ChatRequest,
            context: LocalChatExecutionContext
        ): Flow<GenerateEvent> = engine.streamChat(request, context)

        override suspend fun stopGeneration() {
            engine.stopGeneration()
        }

        override suspend fun stopGenerationIfActive(requestId: String): Boolean {
            val token = engine.activeGenerationStopToken()
                ?.takeIf { it.requestId == requestId }
            return engine.stopGenerationIfActive(token)
        }
    }
