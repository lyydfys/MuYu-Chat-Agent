package com.muyuchat.mca

import com.muyuchat.core.engine.ActiveLoadedSignature
import com.muyuchat.core.engine.EffectiveExecutionSignature
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LoadParams
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ParameterSignatureSnapshot
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.RuntimeOverrideSignature
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import java.io.File

internal fun ModelManifest.loadParamsForExecutionProfile(
    profile: ModelExecutionProfile
): LoadParams {
    require(profile.modelId == id) { "Execution profile belongs to another model." }
    val values = profile.runtimeValuesJson()
    val projector = if (runtime == ChatModelRuntime.LLAMA_CPP) {
        visionProjectorPath?.takeIf(String::isNotBlank)?.takeIf { File(it).isFile }
    } else {
        null
    }
    val defaults = GenerationParams()
    val base = LoadParams(
        nCtx = (profile.resolvedLoadBoundValues.value("n_ctx") as? Number)?.toInt()
            ?: defaults.nCtx,
        nThreads = (profile.hotExecutionValues.value("n_threads") as? Number)?.toInt()
            ?: defaults.nThreads,
        visionProjectorPath = projector
    )
    return LoadParams.fromJson(values.toString(), base).copy(visionProjectorPath = projector)
}

internal fun ModelExecutionProfile.bootstrapCanaryGenerationParams(): GenerationParams {
    val effective = GenerationParams.fromJson(runtimeValuesJson(), GenerationParams())
    return effective.copy(
        nPredict = minOf(effective.nPredict.coerceAtLeast(32), 96),
        temperature = 0.0f,
        topK = 1,
        topP = 1.0f,
        minP = 0.0f,
        repeatPenalty = 1.0f,
        presencePenalty = 0.0f,
        frequencyPenalty = 0.0f,
        reasoningMode = ReasoningMode.OFF,
        hideReasoning = true,
        systemPrompt = ""
    )
}

internal fun ModelExecutionProfile.matchesExactParameterSignatures(
    snapshot: ParameterSignatureSnapshot
): Boolean {
    val active = ActiveLoadedSignature.of(runtimeIdentity, resolvedLoadBoundValues)
    val noOverride = RuntimeOverrideSignature.none(runtimeIdentity)
    val effective = EffectiveExecutionSignature.of(
        identity = runtimeIdentity,
        active = active,
        committed = committedExecutionSignature,
        override = noOverride
    )
    return snapshot.desired.digest == desiredSignature.digest &&
        snapshot.resolved.digest == resolvedLoadSignature.digest &&
        snapshot.active?.digest == active.digest &&
        snapshot.committed.digest == committedExecutionSignature.digest &&
        snapshot.override.digest == noOverride.digest &&
        snapshot.override.isNone &&
        snapshot.effective?.digest == effective.digest
}

private fun ModelExecutionProfile.runtimeValuesJson() = resolvedLoadBoundValues
    .plus(hotExecutionValues)
    .plus(modelBehaviorValues)
    .toJsonObject()
