package com.muyuchat.mca.presentation

import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.TokenProgress
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.mca.GenerationExecutionFields
import com.muyuchat.mca.GenerationLifecycleFields
import com.muyuchat.mca.GenerationStopFields

/** Fixed lifecycle writes; these reducers cannot change catalogs, configuration or attachments. */
internal fun GenerationLifecycleFields.afterGenerationStarted(): GenerationLifecycleFields = copy(
    isGenerating = true,
    generationPhase = null,
    generationTokenProgress = null,
    generationPersistProgress = null,
    generationStats = null,
    engineLifecycle = AgentEngineLifecycle.GENERATING
)

internal fun GenerationLifecycleFields.afterGenerationCompleted(stats: RuntimeStats): GenerationLifecycleFields = copy(
    isGenerating = false,
    generationPhase = null,
    generationTokenProgress = null,
    generationPersistProgress = null,
    generationStats = null,
    stats = stats,
    engineLifecycle = stats.lifecycleAfterGeneration()
)

internal fun GenerationExecutionFields.afterGenerationTerminated(
    stats: RuntimeStats,
    statusMessage: String? = null
): GenerationExecutionFields = copy(
    isGenerating = false,
    generationPhase = null,
    generationTokenProgress = null,
    generationPersistProgress = null,
    generationStats = null,
    stats = stats,
    engineLifecycle = stats.lifecycleAfterGeneration(),
    statusMessage = statusMessage ?: this.statusMessage
)

internal fun GenerationExecutionFields.withGenerationProgress(
    phase: GenerationPhase?,
    tokenProgress: TokenProgress?,
    persistProgress: PersistProgress?,
    stats: RuntimeStats?
): GenerationExecutionFields = copy(
    generationPhase = phase,
    generationTokenProgress = tokenProgress,
    generationPersistProgress = persistProgress,
    generationStats = stats
)

internal fun GenerationStopFields.withGenerationProgressCleared(): GenerationStopFields = copy(
    generationPhase = null,
    generationTokenProgress = null,
    generationPersistProgress = null,
    generationStats = null
)
