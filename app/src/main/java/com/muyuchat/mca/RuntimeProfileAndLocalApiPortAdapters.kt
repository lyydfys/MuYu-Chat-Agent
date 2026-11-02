package com.muyuchat.mca

import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.EngineLifecycleLease
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LoadAuthorization
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.PendingProfileDisposition
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.tuning.CanaryEvaluationParams
import com.muyuchat.core.tuning.CandidateScore
import com.muyuchat.core.tuning.ExecutionProfileKind
import com.muyuchat.core.tuning.ProfileVerificationLevel
import com.muyuchat.core.tuning.TuningExecutionProfile
import org.json.JSONArray
import org.json.JSONObject
import com.muyuchat.api.local.LocalApiControlResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.advisor.AgentRecommendation
import com.muyuchat.core.tuning.UserPreference
import com.muyuchat.core.benchmark.BenchmarkRunner

internal class RuntimeProfileTransactionPortAdapter(
    private val store: UiStateWriter<RuntimeProfileFields>,
    override val runtimeProfileStore: ModelRuntimeProfileStore,
    override val engine: McaInferenceService,
    private val runtimeAdmission: RuntimeAdmissionState,
    private val tuningOwner: TuningOwner,
    private val showStatusAction: (String) -> Unit,
) : RuntimeProfileTransactionPort {
    override fun uiState(): MainUiState = store.current

    override fun updateUiState(transform: (RuntimeProfileFields) -> RuntimeProfileFields) {
        store.update(transform)
    }

    override val activeModelForRuntimeProfile: ModelManifest?
        get() = runtimeAdmission.activeModelForRuntimeProfile

    override var pendingProfileTransactionId: String?
        get() = runtimeAdmission.pendingProfileTransactionId
        set(value) {
            runtimeAdmission.pendingProfileTransactionId = value
        }

    override fun candidateMeasurement(
        profileId: String,
        jobId: String?,
        phase: String,
        result: CandidateCanaryResult
    ): CandidateMeasurementEntity =
        tuningOwner.candidateMeasurement(profileId, jobId, phase, result)

    override fun tuningCandidateScore(result: CandidateCanaryResult): CandidateScore =
        canaryScore(port = tuningOwner.canaryPort, result)

    override fun mergeExecutionProfile(
        generation: GenerationParams,
        profile: ModelExecutionProfile
    ): GenerationParams = mergedExecutionProfile(generation, profile)

    override suspend fun restoreExactRuntimeProfile(
        model: ModelManifest,
        profile: ModelExecutionProfile
    ): Boolean = tuningOwner.restoreExactRuntimeProfile(model, profile)

    override fun showStatus(message: String) {
        showStatusAction(message)
    }

    override fun asTuningExecutionProfile(
        profile: ModelExecutionProfile,
        kind: ExecutionProfileKind,
        verificationLevel: ProfileVerificationLevel,
        reason: String
    ): TuningExecutionProfile =
        tuningOwner.asTuningExecutionProfile(profile, kind = kind, verificationLevel = verificationLevel, reason = reason)

    override suspend fun runCandidateCanary(
        candidate: TuningExecutionProfile,
        canaryParams: CanaryEvaluationParams,
        authorization: LoadAuthorization,
        disposition: PendingProfileDisposition,
        lifecycleLease: EngineLifecycleLease?
    ): CandidateCanaryResult = tuningOwner.runCandidateCanary(
        candidate = candidate,
        canaryParams = canaryParams,
        authorization = authorization,
        disposition = disposition,
        lifecycleLease = lifecycleLease
    )
}

internal class LocalApiAuxProviderPortAdapter(
    private val store: UiStateReadPort,
    private val engine: McaInferenceService,
    private val localApiPayloadPortProvider: () -> LocalApiPayloadPort,
    private val deviceProfileReader: DeviceProfileReader,
    private val buildRecommendationAction: (UserPreference) -> AgentRecommendation,
    private val benchmarkRunner: BenchmarkRunner,
    private val generateLocalApiImageAction: suspend (String, String) -> String,
    private val generateLocalApiVideoAction: suspend (String, String) -> String,
    private val stopLocalApiVideoAction: suspend (String) -> Boolean,
) : LocalApiAuxProviderPort {
    override fun analysisParamsJson(): String =
        engineApiGenerationParams(engine, store.current).toJson()

    override fun analysisGenerationParams(): GenerationParams =
        engineApiGenerationParams(engine, store.current)

    override fun modelsJsonText(): String =
        localApiModelsJson(localApiPayloadPortProvider())

    override fun textualInversionsJsonText(): String = JSONArray().apply {
        store.current.localImageTextualInversions.forEach { artifact ->
            put(artifact.toJson(includePath = false))
        }
    }.toString()

    override fun modelRuntimeStatesJsonText(): String = runBlocking(Dispatchers.IO) {
        localApiModelRuntimeStatesJson(localApiPayloadPortProvider())
    }

    override fun deviceProfileJsonText(): String =
        deviceProfileReader.read().toJson().toString()

    override fun recommendationJsonText(requestJson: String): String {
        val preference = UserPreference.fromJson(requestJson)
        return buildRecommendationAction(preference).toJson().toString()
    }

    override suspend fun benchmarkJsonText(): String =
        benchmarkRunner.runCurrentParamsBenchmark(store.current.params)
            .toJson()
            .toString()

    override suspend fun generateImage(requestId: String, body: String): String =
        generateLocalApiImageAction(requestId, body)

    override suspend fun generateVideo(requestId: String, body: String): String =
        generateLocalApiVideoAction(requestId, body)

    override suspend fun stopVideo(requestId: String): Boolean =
        stopLocalApiVideoAction(requestId)
}

internal class LocalApiTuningControlPortAdapter(
    private val runtimeAdmission: RuntimeAdmissionState,
    private val store: UiStateWriter<RuntimeProfileFields>,
    private val runtimeProfileStore: ModelRuntimeProfileStore,
    private val tuningOwner: TuningOwner,
    private val localApiModelMatchesRef: (String, MainUiState, ModelManifest) -> Boolean,
    private val localApiPerformancePreferenceRef: (String?) -> UserPreference?,
    private val localApiTuningRejectedRef: (Int, String, String, JSONObject) -> LocalApiControlResult.Rejected,
    private val localApiTuningConflictRef: (String, String?, String) -> LocalApiControlResult.Rejected,
    private val localApiExclusiveLifecycleConflictRef: (String, String?) -> LocalApiControlResult.Rejected?,
    private val assignActiveTuningJobIdRef: (String?) -> Unit,
    private val tuningJobPublicJsonRef: (TuningJobEntity) -> JSONObject,
    private val setTuningCancelRequestedRef: (Boolean) -> Unit,
    private val setTuningPauseRequestedRef: (Boolean) -> Unit,
    private val launchTuningApplyRef: (PendingRuntimeProfileTransaction) -> Unit,
    private val launchTuningRollbackRef: (ModelRuntimeIdentity, ModelManifest) -> Unit,
) : LocalApiTuningControlPort {
    override fun activeRuntimeIdentity(): ModelRuntimeIdentity? =
        runtimeAdmission.activeRuntimeIdentity

    override fun activeModelForRuntimeProfile(): ModelManifest? =
        runtimeAdmission.activeModelForRuntimeProfile

    override fun localApiModelMatches(
        requestedModel: String,
        state: MainUiState,
        model: ModelManifest
    ): Boolean = localApiModelMatchesRef(requestedModel, state, model)

    override fun localApiDebugMode(value: String): AgentDebugMode? =
        agentDebugModeFromLocalApiValue(value)

    override fun localApiPerformancePreference(value: String?): UserPreference? =
        localApiPerformancePreferenceRef(value)

    override fun localApiTuningRejected(
        httpStatus: Int,
        code: String,
        message: String,
        details: JSONObject
    ): LocalApiControlResult.Rejected =
        localApiTuningRejectedRef(httpStatus, code, message, details)

    override fun localApiTuningConflict(
        action: String,
        activeJobId: String?,
        message: String
    ): LocalApiControlResult.Rejected =
        localApiTuningConflictRef(action, activeJobId, message)

    override fun localApiExclusiveLifecycleConflict(
        action: String,
        allowedStagedJobId: String?
    ): LocalApiControlResult.Rejected? =
        localApiExclusiveLifecycleConflictRef(action, allowedStagedJobId)

    override fun assignActiveTuningJobId(jobId: String?) {
        assignActiveTuningJobIdRef(jobId)
    }

    override fun updateUiState(transform: (RuntimeProfileFields) -> RuntimeProfileFields) {
        store.update(transform)
    }

    override fun tuningJobPublicJson(job: TuningJobEntity): JSONObject =
        tuningJobPublicJsonRef(job)

    override fun startAgentTuning(
        debugMode: AgentDebugMode,
        autoApply: Boolean,
        precreatedJob: TuningJobEntity?,
        preferenceOverride: UserPreference?
    ): Boolean = tuningOwner.startAgentTuning(
        debugMode = debugMode,
        autoApply = autoApply,
        precreatedJob = precreatedJob,
        preferenceOverride = preferenceOverride
    )

    override suspend fun activeTuningJob(identityKey: String): TuningJobEntity? =
        runtimeProfileStore.activeTuningJob(identityKey)

    override suspend fun createTuningJob(
        identityKey: String,
        autoApplyLoadChanges: Boolean,
        phase: String
    ): TuningJobEntity = runtimeProfileStore.createTuningJob(
        identityKey = identityKey,
        autoApplyLoadChanges = autoApplyLoadChanges,
        phase = phase
    )

    override suspend fun transitionTuningJob(
        jobId: String,
        state: PersistedTuningJobState,
        phase: String,
        failureCode: String?,
        failureSummary: String?
    ): TuningJobEntity? = runtimeProfileStore.transitionTuningJob(
        jobId = jobId,
        state = state,
        phase = phase,
        failureCode = failureCode,
        failureSummary = failureSummary
    )

    override suspend fun tuningJob(jobId: String): TuningJobEntity? =
        runtimeProfileStore.tuningJob(jobId)

    override suspend fun pendingTransaction(identityKey: String): PendingRuntimeProfileTransaction? =
        runtimeProfileStore.pendingTransaction(identityKey)

    override fun setTuningCancelRequested(value: Boolean) {
        setTuningCancelRequestedRef(value)
    }

    override fun setTuningPauseRequested(value: Boolean) {
        setTuningPauseRequestedRef(value)
    }

    override fun launchTuningApply(pending: PendingRuntimeProfileTransaction) {
        launchTuningApplyRef(pending)
    }

    override suspend fun currentRuntimeState(identityKey: String): CurrentRuntimeProfileState? =
        runtimeProfileStore.currentRuntimeState(identityKey)

    override fun launchTuningRollback(identity: ModelRuntimeIdentity, model: ModelManifest) {
        launchTuningRollbackRef(identity, model)
    }
}
