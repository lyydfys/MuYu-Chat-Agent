package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.PersistStage
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.TokenProgress
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.agent.AgentPendingProfile
import com.muyuchat.feature.agent.AgentProfileRecordState
import com.muyuchat.feature.agent.AgentProfileVerification
import com.muyuchat.feature.agent.AgentRollbackProfile
import com.muyuchat.feature.agent.AgentTuningJobState
import com.muyuchat.mca.composition.runtimeProfileWriter
import com.muyuchat.mca.presentation.afterGenerationTerminated
import com.muyuchat.mca.presentation.afterNativeRuntimeReleased
import org.junit.Assert.assertEquals
import org.junit.Test

/** Keeps the established terminal semantics while the production writer can return only fields. */
class RuntimeFieldTransitionsTest {
    private val running = MainUiState(
        input = "keep draft",
        hubPage = 7,
        apiEnabled = true,
        loadedModelId = "active-model",
        loadedModelName = "Active model",
        busy = true,
        isGenerating = true,
        generationPhase = GenerationPhase.DECODE,
        generationTokenProgress = TokenProgress(3, 12),
        generationPersistProgress = PersistProgress(PersistStage.WRITING, 3L, 12L),
        generationStats = RuntimeStats(loaded = true),
        autoTuningInProgress = true,
        rollbackParams = GenerationParams(),
        profileId = "active-profile",
        revision = 9L,
        profileRecordState = AgentProfileRecordState.COMMITTED,
        verification = AgentProfileVerification.COMPATIBLE,
        engineLifecycle = AgentEngineLifecycle.GENERATING,
        tuningJobState = AgentTuningJobState.RUNNING,
        reloadRequired = true,
        pendingProfile = AgentPendingProfile("pending", 10L, "pending", true),
        rollbackProfile = AgentRollbackProfile("rollback", 8L, "rollback", true),
        tuningEtaSeconds = 40L,
        tuningPhase = "running",
        statusMessage = "existing status",
    )

    @Test
    fun `termination clears progress and follows live runtime lifecycle without touching other domains`() {
        listOf(
            RuntimeStats(loaded = true),
            RuntimeStats(loaded = false),
            RuntimeStats(loaded = false, lastError = "failed"),
        ).forEach { stats ->
            val store = UiStateStore(running)
            store.runtimeProfileWriter().update { it.afterRuntimeGenerationTerminated(stats) }
            assertEquals(running.afterGenerationTerminated(stats), store.current)
        }
    }

    @Test
    fun `native release retires every active projection together while preserving foreign state`() {
        val stats = RuntimeStats(loaded = false)
        val store = UiStateStore(running)
        store.runtimeProfileWriter().update {
            it.afterRuntimeNativeReleased(AgentEngineLifecycle.UNLOADED, "released", stats, busy = true)
        }
        assertEquals(
            running.afterNativeRuntimeReleased(AgentEngineLifecycle.UNLOADED, "released", stats, busy = true),
            store.current,
        )
    }

    @Test
    fun `an explicit terminal status overrides the previous status in the same commit`() {
        val stats = RuntimeStats(loaded = true)
        val store = UiStateStore(running)
        store.runtimeProfileWriter().update { it.afterRuntimeGenerationTerminated(stats, "failed load") }
        assertEquals(running.afterGenerationTerminated(stats, "failed load"), store.current)
    }
}
