package com.muyuchat.mca

import android.app.Application
import android.content.Context
import com.muyuchat.api.local.LocalApiControlPlane
import com.muyuchat.api.local.localApiPublicModelId
import com.muyuchat.api.local.LocalApiControlResult
import com.muyuchat.api.local.LocalApiIdempotencyContext
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.tuning.PerformanceMode
import com.muyuchat.core.tuning.UserPreference
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.agent.AgentTuningJobState
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * R4-S20：Local API 控制平面与调优操作整族下沉。
 *
 * 集中管理 Local API 控制平面装配、调优任务流转、独占生命周期冲突校验与幂等执行。
 */
internal class LocalApiControlPlaneCoordinator(
    private val runtimeAdmission: RuntimeAdmissionState,
    private val store: UiStateWriter<RuntimeProfileFields>,
    private val engine: McaInferenceService,
    private val tuningOwner: TuningOwner,
    private val runtimeProfileStore: ModelRuntimeProfileStore,
    private val agentTuningCoordinator: AgentTuningCoordinator,
    private val getActiveTuningJobId: () -> String?,
    private val setActiveTuningJobId: (String?) -> Unit,
    private val getAdaptiveTuningJob: () -> Job?,
    private val setAdaptiveTuningJob: (Job?) -> Unit,
    private val getActiveRuntimeIdentity: () -> ModelRuntimeIdentity?,
    private val applyPendingRuntimeTransaction: suspend (PendingRuntimeProfileTransaction) -> Unit,
    private val rollbackCommittedRuntimeProfile: suspend (ModelRuntimeIdentity, ModelManifest) -> Unit,
    private val idempotencyJournalLock: Any,
    private val application: Application,
    private val scope: CoroutineScope
) {
    val localApiPayloadPort: LocalApiPayloadPort =
        LocalApiPayloadPortAdapter(
            store = store,
            engineRef = engine,
            runtimeProfileStoreRef = runtimeProfileStore,
            tuningRef = tuningOwner,
            admissionRef = runtimeAdmission
        )

    fun localApiModelMatches(
        requestedModel: String,
        state: MainUiState,
        model: ModelManifest
    ): Boolean {
        val requested = requestedModel.trim()
        return requested.isNotEmpty() && (
            requested == state.loadedModelId ||
                requested == state.loadedModelName ||
                requested == model.id ||
                requested == model.fileName ||
                requested == model.displayName ||
                requested == localApiPublicModelId(model.displayName, model.id)
            )
    }

    fun runDurableLocalApiControl(
        idempotency: LocalApiIdempotencyContext,
        operation: () -> LocalApiControlResult
    ): LocalApiControlResult = runDurableLocalApiControlFor(
        preferences = application.getSharedPreferences(
            "mca_local_api_idempotency_journal",
            Context.MODE_PRIVATE
        ),
        lock = idempotencyJournalLock,
        idempotency = idempotency,
        localApiTuningRejected = ::localApiTuningRejected,
        operation = operation
    )

    fun localApiPerformancePreference(value: String?): UserPreference? {
        if (value.isNullOrBlank()) return null
        val mode = when (value.trim().lowercase(Locale.ROOT).replace("-", "_").replace(" ", "_")) {
            "balanced" -> PerformanceMode.Balanced
            "speed" -> PerformanceMode.Speed
            "quality" -> PerformanceMode.Quality
            "long_context", "longcontext" -> PerformanceMode.LongContext
            "power", "power_save", "powersave" -> PerformanceMode.PowerSave
            else -> return null
        }
        return store.current.preference.copy(mode = mode)
    }

    fun localApiExclusiveLifecycleConflict(
        action: String,
        allowedStagedJobId: String? = null
    ): LocalApiControlResult.Rejected? {
        val state = store.current
        val activeTuningJobId = getActiveTuningJobId()
        val adaptiveTuningJob = getAdaptiveTuningJob()
        val selectedStagedJobIsAllowed = allowedStagedJobId != null &&
            (activeTuningJobId == null || activeTuningJobId == allowedStagedJobId) &&
            state.tuningJobState in setOf(AgentTuningJobState.PAUSED, AgentTuningJobState.RUNNING) &&
            adaptiveTuningJob?.isActive != true
        val tuningConflict = !selectedStagedJobIsAllowed && (
            adaptiveTuningJob?.isActive == true ||
                state.tuningJobState in setOf(
                    AgentTuningJobState.QUEUED,
                    AgentTuningJobState.RUNNING,
                    AgentTuningJobState.PAUSED,
                    AgentTuningJobState.CANCELING,
                    AgentTuningJobState.VALIDATING,
                    AgentTuningJobState.RECOVERING
                )
            )
        val engineConflict = state.engineLifecycle != AgentEngineLifecycle.READY ||
            state.busy ||
            state.isGenerating
        if (!tuningConflict && !engineConflict) return null
        return localApiTuningConflict(
            action = action,
            activeJobId = activeTuningJobId,
            message = "运行时正在执行独占生命周期操作，不能并发执行 tuning $action。"
        )
    }

    fun localApiTuningConflict(
        action: String,
        activeJobId: String?,
        message: String
    ): LocalApiControlResult.Rejected = localApiTuningRejected(
        httpStatus = 409,
        code = "tuning_${action}_conflict",
        message = message,
        details = JSONObject()
            .put("activeJobId", activeJobId)
            .put("tuningJobState", store.current.tuningJobState.name.lowercase())
            .put("engineLifecycle", store.current.engineLifecycle.name.lowercase())
            .put("activeProfileId", engine.activeExecutionProfile()?.profileId)
    )

    fun localApiTuningRejected(
        httpStatus: Int,
        code: String,
        message: String,
        details: JSONObject = JSONObject()
    ): LocalApiControlResult.Rejected =
        LocalApiControlResult.Rejected(
            httpStatus = httpStatus,
            code = code,
            message = message,
            detailsJson = details.toString(),
            retryAfterMs = if (httpStatus == 409) 1_000L else 0L
        )

    fun tuningJobPublicJson(job: TuningJobEntity): JSONObject = JSONObject()
        .put("jobId", job.jobId)
        .put("state", job.state.lowercase(Locale.ROOT))
        .put("phase", job.phase)
        .put("candidateProfileId", job.candidateProfileId)
        .put("autoApply", job.autoApplyLoadChanges)
        .put("cancellationRequested", job.cancellationRequested)
        .put("failureCode", job.failureCode)
        .put("failureSummary", job.failureSummary)
        .put("createdAt", job.createdAt)
        .put("updatedAt", job.updatedAt)
        .put("lastHeartbeatAt", job.lastHeartbeatAt)

    suspend fun cancelPersistedTuningJob(jobId: String): TuningJobEntity {
        var canceled = runtimeProfileStore.requestTuningJobCancellation(jobId)
        engine.stopGeneration()
        if (getAdaptiveTuningJob()?.isActive != true) {
            val pending = runCatching { runtimeProfileStore.pendingTransaction(canceled.identityKey) }.getOrNull()
            if (pending?.journal?.jobId == jobId) {
                val recovery = runtimeProfileStore.rejectCandidate(
                    transactionId = pending.journal.transactionId,
                    failureStage = "USER_CANCEL",
                    failureCode = "USER_CANCELED",
                    failureSummary = "用户取消了等待应用的 staged candidate。"
                )
                recovery.rollbackProfileId?.let { rollbackProfileId ->
                    runtimeProfileStore.completeRecovery(
                        transactionId = pending.journal.transactionId,
                        restoredProfileId = rollbackProfileId
                    )
                }
                canceled = runtimeProfileStore.tuningJob(jobId) ?: canceled
                store.update { it.copy(pendingProfile = null) }
            } else if (canceled.state !in setOf(
                    PersistedTuningJobState.SUCCEEDED.name,
                    PersistedTuningJobState.FAILED.name
                )
            ) {
                canceled = runtimeProfileStore.transitionTuningJob(
                    jobId = jobId,
                    state = PersistedTuningJobState.FAILED,
                    phase = "USER_CANCELED",
                    failureCode = "USER_CANCELED",
                    failureSummary = "用户取消了调优任务。"
                )
            }
            if (getActiveTuningJobId() == jobId) setActiveTuningJobId(null)
        }
        return canceled
    }

    fun runTuningControl(
        jobId: String,
        action: String,
        operation: suspend () -> TuningJobEntity
    ): LocalApiControlResult = runCatching {
        val job = runBlocking(Dispatchers.IO) { operation() }
        store.update { state ->
            state.copy(
                tuningJobState = agentJobState(job),
                tuningPhase = job.phase,
                statusMessage = "Local API 已请求调优任务 ${action}。"
            )
        }
        LocalApiControlResult.Success(localApiTuningStateJson(localApiPayloadPort))
    }.getOrElse { error ->
        LocalApiControlResult.Rejected(
            httpStatus = 409,
            code = "tuning_${action}_rejected",
            message = error.message ?: "调优任务操作失败"
        )
    }

    val localApiTuningControlPort: LocalApiTuningControlPort =
        LocalApiTuningControlPortAdapter(
            runtimeAdmission = runtimeAdmission,
            store = store,
            runtimeProfileStore = runtimeProfileStore,
            tuningOwner = tuningOwner,
            localApiModelMatchesRef = ::localApiModelMatches,
            localApiPerformancePreferenceRef = ::localApiPerformancePreference,
            localApiTuningRejectedRef = ::localApiTuningRejected,
            localApiTuningConflictRef = ::localApiTuningConflict,
            localApiExclusiveLifecycleConflictRef = ::localApiExclusiveLifecycleConflict,
            assignActiveTuningJobIdRef = setActiveTuningJobId,
            tuningJobPublicJsonRef = ::tuningJobPublicJson,
            setTuningCancelRequestedRef = { agentTuningCoordinator.adaptiveTuningCancelRequested.set(it) },
            setTuningPauseRequestedRef = { agentTuningCoordinator.adaptiveTuningPauseRequested.set(it) },
            launchTuningApplyRef = { pending ->
                setAdaptiveTuningJob(
                    scope.launch(Dispatchers.IO) {
                        try {
                            applyPendingRuntimeTransaction(pending)
                        } finally {
                            setActiveTuningJobId(null)
                            setAdaptiveTuningJob(null)
                            agentTuningCoordinator.adaptiveTuningCancelRequested.set(false)
                            agentTuningCoordinator.adaptiveTuningPauseRequested.set(false)
                        }
                    }
                )
            },
            launchTuningRollbackRef = { identity, model ->
                setAdaptiveTuningJob(
                    scope.launch(Dispatchers.IO) {
                        try {
                            rollbackCommittedRuntimeProfile(identity, model)
                        } finally {
                            setAdaptiveTuningJob(null)
                        }
                    }
                )
            }
        )

    fun createControlPlane(): LocalApiControlPlane = LocalApiControlPlaneAdapter(
        busyStateProvider = {
            localApiBusyStateFor(store.current, tuningJson = { localApiTuningStateJson(localApiPayloadPort) })
        },
        preflightProvider = { request ->
            localApiPreflightFor(
                request = request,
                state = store.current,
                engine = engine,
                publicModelId = { displayName, id -> localApiPublicModelId(displayName, id) },
                runtimeProfileJson = { runtimeProfileJsonFor(store.current, engine, getActiveRuntimeIdentity()) }
            )
        },
        profileJsonProvider = {
            runtimeProfileJsonFor(store.current, engine, getActiveRuntimeIdentity())
        },
        tuningJsonProvider = {
            localApiTuningStateJson(localApiPayloadPort)
        },
        tuningJobProvider = { jobId ->
            localApiTuningJobFor(
                requestedJobId = jobId,
                currentIdentityKey = { getActiveRuntimeIdentity()?.identityHash },
                currentActiveTuningJobId = getActiveTuningJobId,
                lookupJob = { id -> runtimeProfileStore.tuningJob(id) },
                lookupActiveJob = { key -> runtimeProfileStore.activeTuningJob(key) },
                tuningJson = { localApiTuningStateJson(localApiPayloadPort) },
                publicJobJson = { job -> tuningJobPublicJson(job) }
            )
        },
        createTuningJobDelegate = { request, idempotencyKey ->
            localApiCreateTuningJobFor(
                request = request,
                idempotencyKey = idempotencyKey,
                state = store.current,
                engine = engine,
                port = localApiTuningControlPort
            )
        },
        pauseTuningJobDelegate = { jobId, _ ->
            runTuningControl(jobId, "pause") {
                agentTuningCoordinator.adaptiveTuningPauseRequested.set(true)
                runtimeProfileStore.pauseTuningJob(jobId)
            }
        },
        resumeTuningJobDelegate = { jobId, _ ->
            runTuningControl(jobId, "resume") {
                agentTuningCoordinator.adaptiveTuningPauseRequested.set(false)
                runtimeProfileStore.resumeTuningJob(jobId)
            }
        },
        cancelTuningJobDelegate = { jobId, _ ->
            runTuningControl(jobId, "cancel") {
                agentTuningCoordinator.adaptiveTuningCancelRequested.set(true)
                cancelPersistedTuningJob(jobId)
            }
        },
        applyTuningJobDelegate = { jobId, idempotencyKey ->
            localApiApplyTuningJobFor(
                jobId = jobId,
                idempotencyKey = idempotencyKey,
                port = localApiTuningControlPort
            )
        },
        rollbackTuningDelegate = { idempotencyKey ->
            localApiRollbackTuningFor(
                idempotencyKey = idempotencyKey,
                port = localApiTuningControlPort
            )
        },
        runDurableLocalApiControl = { idempotency, operation ->
            runDurableLocalApiControl(idempotency, operation)
        }
    )
}
