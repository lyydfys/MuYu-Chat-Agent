package com.muyuchat.api.local

import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.GenerationParams

object LocalApiRuntime {
    @Volatile
    var engine: McaInferenceService? = null

    @Volatile
    var loadedModelJsonProvider: () -> String = { "{}" }

    @Volatile
    var paramsJsonProvider: () -> String = { "{}" }

    @Volatile
    var generationParamsProvider: () -> GenerationParams = { GenerationParams() }

    @Volatile
    var modelsJsonProvider: () -> String = { "[]" }

    @Volatile
    var deviceProfileJsonProvider: () -> String = { "{}" }

    @Volatile
    var agentRecommendationJsonProvider: (String) -> String = { "{}" }

    @Volatile
    var benchmarkJsonProvider: suspend (String) -> String = { "{}" }
}

