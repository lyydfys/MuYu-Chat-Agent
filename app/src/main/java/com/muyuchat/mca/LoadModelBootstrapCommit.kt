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

/**
 * T2B22-T3（b 工位）拆片：stageBootstrapProfileFor / recordBootstrapProbeMeasurementFor /
 * persistBootstrapProfileFor 三个既有顶层 internal 助手自 LoadModelOperations.kt 逐字节
 * 平移（含其上方 KDoc）；零改写。
 */

/**
 * T2B22-T3（b 工位）拆片：bootstrap 提交/测量/profile 落库三助手自 LoadModelOperations.kt 逐字节平移。
 */
/** R3-S24：bootstrap 事务两助手顶层化（体逐字；runtimeProfileStore 参数化）。 */
internal suspend fun stageBootstrapProfileFor(
    runtimeProfileStore: ModelRuntimeProfileStore,
    transactionId: String,
    profile: ModelExecutionProfile,
    rollbackTargetProfileId: String?
) {
        runtimeProfileStore.stageCandidate(
            snapshot = profile.toPersistedExecutionProfileSnapshot(
                parentCommittedProfileId = rollbackTargetProfileId,
                verificationLevel = PersistedProfileVerificationLevel.SAFE,
                sourceSummaryJson = JSONObject()
                    .put("kind", "bootstrap_load")
                    .put("probeKind", TuningProbeWorkerProtocol.ProbeKind.BOOTSTRAP_LOAD.name)
                    .put("profileId", profile.profileId)
                    .put("resolvedLoadSignature", profile.resolvedLoadSignature.digest)
                    .put("committedExecutionSignature", profile.committedExecutionSignature.digest)
                    .toString()
            ),
            transactionId = transactionId,
            rollbackTargetProfileId = rollbackTargetProfileId,
            job = null
        )
        runtimeProfileStore.updateJournalStage(
            transactionId = transactionId,
            state = TuningJournalState.APPLYING,
            stage = "BOOTSTRAP_WORKER_PENDING"
        )
}

internal suspend fun recordBootstrapProbeMeasurementFor(
    runtimeProfileStore: ModelRuntimeProfileStore,
    result: TuningProbeWorkerProtocol.Result
) {
        require(result.probeKind == TuningProbeWorkerProtocol.ProbeKind.BOOTSTRAP_LOAD)
        runtimeProfileStore.recordMeasurement(
            CandidateMeasurementEntity(
                measurementId = "bootstrap-${result.requestId}",
                profileId = result.profileId,
                jobId = null,
                correctnessPassed = result.passed,
                safetyPassed = !result.lowMemoryTriggered,
                effectiveSignatureMatched = result.signatureMatched,
                accepted = result.passed && result.signatureMatched,
                metricsJson = JSONObject(result.evidenceJson)
                    .put("runtimeStats", JSONObject(result.runtimeStatsJson))
                    .put("elapsedMs", result.elapsedMs)
                    .toString(),
                failureCode = if (result.passed) null else "BOOTSTRAP_WORKER_REJECTED",
                createdAt = System.currentTimeMillis()
            )
        )
}

/** R3-S24：正式加载 profile 落库助手顶层化（体逐字；store/engine 参数化）。 */
internal suspend fun persistBootstrapProfileFor(
    runtimeProfileStore: ModelRuntimeProfileStore,
    engine: McaInferenceService,
    profile: ModelExecutionProfile,
    sourceSummary: String
) {
        val existing = runtimeProfileStore.profile(profile.profileId)
        if (existing?.recordState == PersistedProfileRecordState.COMMITTED.name) return
        val transactionId = "bootstrap-formal-${UUID.randomUUID()}"
        val current = runtimeProfileStore.currentRuntimeState(profile.runtimeIdentity.identityHash)
        val rollbackTargetProfileId = current?.activeProfile
            ?.takeIf {
                it.recordState == PersistedProfileRecordState.COMMITTED.name &&
                    it.profileId != profile.profileId
            }
            ?.profileId
        val snapshot = profile.toPersistedExecutionProfileSnapshot(
            parentCommittedProfileId = rollbackTargetProfileId,
            verificationLevel = PersistedProfileVerificationLevel.SAFE,
            sourceSummaryJson = sourceSummary
        )
        runtimeProfileStore.stageCandidate(
            snapshot = snapshot,
            transactionId = transactionId,
            rollbackTargetProfileId = rollbackTargetProfileId
        )
        runtimeProfileStore.updateJournalStage(
            transactionId,
            TuningJournalState.VALIDATING,
            "FORMAL_LOAD_VALIDATED"
        )
        val signatures = engine.parameterSignatureSnapshot()
            ?: error("安全基线缺少参数签名快照")
        require(profile.matchesExactParameterSignatures(signatures)) {
            "正式加载参数签名与待提交 profile 不一致"
        }
        runtimeProfileStore.commitCandidate(
            transactionId = transactionId,
            verificationLevel = PersistedProfileVerificationLevel.SAFE,
            activeLoadedSignature = requireNotNull(signatures.active).digest,
            effectiveExecutionSignature = requireNotNull(signatures.effective).digest
        )
    }
