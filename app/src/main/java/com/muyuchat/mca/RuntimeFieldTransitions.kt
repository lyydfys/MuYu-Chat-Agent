package com.muyuchat.mca

import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.feature.agent.AgentCandidateProgress
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.agent.AgentProfileRecordState
import com.muyuchat.feature.agent.AgentProfileVerification
import com.muyuchat.feature.agent.AgentTuningJobState
import com.muyuchat.mca.presentation.lifecycleAfterGeneration

/** The generation terminal transition can change only the declared runtime fields. */
internal fun RuntimeProfileFields.afterRuntimeGenerationTerminated(
    stats: RuntimeStats,
    statusMessage: String? = null,
): RuntimeProfileFields = copy(
    isGenerating = false,
    generationPhase = null,
    generationTokenProgress = null,
    generationPersistProgress = null,
    generationStats = null,
    stats = stats,
    engineLifecycle = stats.lifecycleAfterGeneration(),
    statusMessage = statusMessage ?: this.statusMessage,
)

/** Native release retires every active runtime projection in one field-limited commit. */
internal fun RuntimeProfileFields.afterRuntimeNativeReleased(
    lifecycle: AgentEngineLifecycle,
    statusMessage: String,
    stats: RuntimeStats = RuntimeStats(),
    busy: Boolean = false,
): RuntimeProfileFields = copy(
    loadedModelId = null,
    loadedModelName = null,
    busy = busy,
    isGenerating = false,
    generationPhase = null,
    generationTokenProgress = null,
    generationPersistProgress = null,
    generationStats = null,
    stats = stats,
    autoTuningInProgress = false,
    rollbackParams = null,
    profileId = null,
    revision = null,
    profileRecordState = AgentProfileRecordState.NONE,
    verification = AgentProfileVerification.UNKNOWN,
    engineLifecycle = lifecycle,
    tuningJobState = AgentTuningJobState.IDLE,
    reloadRequired = false,
    pendingProfile = null,
    rollbackProfile = null,
    tuningEtaSeconds = null,
    tuningPhase = null,
    tuningCandidateProgress = AgentCandidateProgress(),
    statusMessage = statusMessage,
)
