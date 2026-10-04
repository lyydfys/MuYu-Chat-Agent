package com.muyuchat.mca

import com.muyuchat.api.local.LocalApiRuntime
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.mca.presentation.afterGenerationCompleted
import com.muyuchat.mca.presentation.afterGenerationStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update

/**
 * The Local API side of the chat execution owner: installs the two providers that route HTTP /
 * Local API chat traffic into the shared [McaInferenceService] and mirror the generation lifecycle
 * into [MainUiState]. Moved verbatim from MainViewModel (P3 step 2); the only call site is
 * MainViewModel's Local API assembly ([installChatLocalApiProviders]).
 */
internal interface ChatLocalApiProviderPort {
    val engine: McaInferenceService
    val chatRuns: ChatRunOwnership
    val uiState: MutableStateFlow<MainUiState>

    fun mergeExecutionProfile(generation: GenerationParams, profile: ModelExecutionProfile): GenerationParams

    fun cancelActiveLocalApiImageGeneration(reason: String): Boolean
}

internal fun installChatLocalApiProviders(port: ChatLocalApiProviderPort) {
    val engine = port.engine
    val chatRuns = port.chatRuns
    val uiState = port.uiState
LocalApiRuntime.engine = engine
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
    engine.streamChat(effectiveRequest, executionContext)
        .onStart {
            if (ownsUiLifecycle()) {
                uiState.update { state ->
                    if (ownsUiLifecycle()) state.afterGenerationStarted() else state
                }
            }
        }
        .onCompletion {
            if (ownsUiLifecycle()) {
                uiState.update { state ->
                    if (ownsUiLifecycle()) {
                        state.afterGenerationCompleted(engine.stats.value)
                    } else {
                        state
                    }
                }
            }
        }
}
LocalApiRuntime.stopGenerationProvider = {
    port.cancelActiveLocalApiImageGeneration("Local API generation was stopped by the client.")
    engine.stopGeneration()
}
}
