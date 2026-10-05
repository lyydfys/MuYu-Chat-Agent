package com.muyuchat.mca

import com.muyuchat.api.local.LocalApiRuntime
import com.muyuchat.core.engine.GenerationParams

/**
 * The auxiliary Local API provider family: installs the runtime providers that serve generation
 * params, model listings, textual-inversion and runtime-state snapshots, device profile, agent
 * recommendations, benchmark results, and the Local API image-generation entry point.
 *
 * The only call site is MainViewModel's Local API assembly ([installLocalApiAuxProviders]);
 * provider lambda bodies live in the VM-side port object, so this file owns only the runtime
 * wiring. Follows the [installChatLocalApiProviders] pattern from the P3 chat-provider fold (P8/S38).
 */
internal interface LocalApiAuxProviderPort {
    fun analysisParamsJson(): String

    fun analysisGenerationParams(): GenerationParams

    fun modelsJsonText(): String

    fun textualInversionsJsonText(): String

    fun modelRuntimeStatesJsonText(): String

    fun deviceProfileJsonText(): String

    fun recommendationJsonText(requestJson: String): String

    suspend fun benchmarkJsonText(): String

    suspend fun generateImage(requestId: String, body: String): String
}

internal fun installLocalApiAuxProviders(port: LocalApiAuxProviderPort) {
    LocalApiRuntime.paramsJsonProvider = { port.analysisParamsJson() }
    LocalApiRuntime.generationParamsProvider = { port.analysisGenerationParams() }
    LocalApiRuntime.modelsJsonProvider = { port.modelsJsonText() }
    LocalApiRuntime.imageTextualInversionsJsonProvider = { port.textualInversionsJsonText() }
    LocalApiRuntime.modelRuntimeStatesJsonProvider = { port.modelRuntimeStatesJsonText() }
    LocalApiRuntime.deviceProfileJsonProvider = { port.deviceProfileJsonText() }
    LocalApiRuntime.agentRecommendationJsonProvider = { requestJson ->
        port.recommendationJsonText(requestJson)
    }
    LocalApiRuntime.benchmarkJsonProvider = { port.benchmarkJsonText() }
    LocalApiRuntime.imageGenerationProvider = { requestId, body ->
        port.generateImage(requestId, body)
    }
}
