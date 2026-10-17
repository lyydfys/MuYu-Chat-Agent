package com.muyuchat.mca

import android.app.Application
import android.util.Log
import com.muyuchat.core.advisor.AgentAdvisor
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.mca.presentation.afterGenerationTerminated
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.feature.agent.AgentTuningJobState
import com.muyuchat.feature.agent.AgentProfileRecordState
import com.muyuchat.feature.agent.AgentProfileVerification
import com.muyuchat.feature.agent.AgentPendingProfile
import com.muyuchat.feature.agent.AgentRollbackProfile
import com.muyuchat.feature.agent.AgentCandidateProgress
import com.muyuchat.core.tuning.BootstrapLoadCanaryPolicy
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.liteRtVisionInputAvailable
import com.muyuchat.core.engine.LITERT_LM_VISION_TRANSPORT_UNAVAILABLE_MESSAGE
import com.muyuchat.core.engine.LITERT_LM_VISION_COMPONENTS_UNAVAILABLE_MESSAGE
import com.muyuchat.core.engine.LITERT_LM_KNOWN_TEXT_ONLY_VISION_UNAVAILABLE_MESSAGE
import kotlinx.coroutines.CancellationException
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.tuning.AdaptiveTuningRecommendation

/**
 * T2B22-T3（b 工位）拆片：runLoadModelBody 内 L258-314 的 profile 解析段（capabilities/
 * adaptive/persisted profile 复用与迁移/重试 profileId）逐字节平移为段函数；壳内以登记
 * glue 调用并按结果重建局部绑定（执行顺序不变）。
 */

/**
 * T2B22-T3（b 工位）拆片：profile 解析段自 LoadModelOperations.kt 逐字节平移；壳内以登记 glue
 * 调用并按结果重建局部绑定；执行顺序与实参求值序不变。
 */
internal suspend fun resolveLoadModelProfileFor(
    tuningOwner: TuningOwner,
    model: ModelManifest,
    identity: ModelRuntimeIdentity,
    qairtAdmissionPassed: Boolean,
    device: DeviceProfile,
    admission: RuntimeAdmissionState,
    engine: McaInferenceService,
    runtimeProfileStore: ModelRuntimeProfileStore,
    params: GenerationParams,
    persistedProfile: ModelExecutionProfile?
): LoadModelProfileResolution {
    val capabilities = tuningOwner.modelTuningCapabilities(model, identity, qairtAdmissionPassed)
    val adaptive = tuningOwner.buildAdaptiveTuningRecommendation(model, identity, device, capabilities)
    // A profile persisted before variant-aware LiteRT transport
    // selection may contain the generic CPU default. Do not let that
    // stale profile silently force a Qualcomm/GPU artifact onto CPU;
    // retain it only when it agrees with the model-declared advisory
    // backend (or when the user explicitly overrode backend).
    val reusablePersistedProfile = persistedProfile?.takeIf { persisted ->
        val preferred = capabilities.preferredBackend
        preferred == null || "backend" in admission.runtimeUserOverrideFields ||
            persisted.resolvedLoadBoundValues.value("backend")?.toString()
                ?.trim()?.lowercase()?.replace('-', '_') == preferred
    }
    var bootstrapProfile = reusablePersistedProfile ?: adaptive.executionProfile.engineProfile
    val persistedRevisions = runtimeProfileStore.profiles(identity.identityHash).map { it.revision }
    if (admission.runtimeUserOverrideFields.isNotEmpty()) {
        val requested = engine.resolveExecutionProfile(
            identity = identity,
            requestedParamsJson = params.toJson(),
            profileId = "manual-load-${UUID.randomUUID().toString().replace("-", "").take(20)}",
            revision = nextExecutionProfileRevision(bootstrapProfile.revision, persistedRevisions)
        ).profile
        bootstrapProfile = mergeUserRequestedIntoProfile(
            base = bootstrapProfile,
            requested = requested,
            authoritativeFields = admission.runtimeUserOverrideFields
        )
    }
    // Older persisted llama profiles left prompt batching at one thread
    // while decode used the tuned worker count. Upgrade that implicit
    // default once so GGUF prefill does not run effectively single-
    // threaded. Explicit n_threads_batch selections are preserved.
    bootstrapProfile = bootstrapProfile.migrateLegacyLlamaBatchThreads(
        profileId = "${bootstrapProfile.profileId}-prefill-${UUID.randomUUID().toString().take(8)}",
        revision = nextExecutionProfileRevision(bootstrapProfile.revision, persistedRevisions)
    )
    // Snapshots written before desired hot/behavior values were persisted
    // cannot reproduce their original desired digest after normalization.
    // Keep the effective settings, but write a complete successor after
    // this formal native load rather than reusing the incomplete record.
    val persistedBootstrapSnapshot = persistedProfile
        ?.takeIf { it.profileId == bootstrapProfile.profileId }
        ?.let { runtimeProfileStore.profile(it.profileId) }
    if (persistedBootstrapSnapshot?.requiresDesiredExecutionSnapshotMigration() == true) {
        bootstrapProfile = bootstrapProfile.copy(
            profileId = "${bootstrapProfile.profileId}-snapshot-${UUID.randomUUID().toString().take(8)}",
            revision = nextExecutionProfileRevision(bootstrapProfile.revision, persistedRevisions)
        )
    }
    if (persistedProfile == null && runtimeProfileStore.profile(bootstrapProfile.profileId)
            ?.recordState == PersistedProfileRecordState.REJECTED.name
    ) {
        bootstrapProfile = bootstrapProfile.copy(
            profileId = "${bootstrapProfile.profileId}-retry-${UUID.randomUUID().toString().take(8)}",
            revision = nextExecutionProfileRevision(bootstrapProfile.revision, persistedRevisions)
        )
    }
    return LoadModelProfileResolution(
        bootstrapProfile = bootstrapProfile,
        adaptive = adaptive
    )
}

/** T2B22-T3：profile 解析段结果承载（原 runLoadModelBody 局部绑定）。 */
internal data class LoadModelProfileResolution(
    val bootstrapProfile: ModelExecutionProfile,
    val adaptive: AdaptiveTuningRecommendation
)
