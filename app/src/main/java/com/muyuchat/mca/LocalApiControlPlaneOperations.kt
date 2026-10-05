package com.muyuchat.mca

import com.muyuchat.api.local.LocalApiBusyState
import com.muyuchat.api.local.LocalApiControlResult
import com.muyuchat.api.local.LocalApiPreflightRequest
import com.muyuchat.api.local.LocalApiPreflightResult
import com.muyuchat.api.local.LocalApiTuningJobCreateRequest
import com.muyuchat.core.engine.CompletionPreflight
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.tuning.PerformanceMode
import com.muyuchat.core.tuning.UserPreference
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.agent.AgentTuningJobState
import com.muyuchat.mca.MainViewModel.AgentDebugMode
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * P8/S40 part 1: the read-only half of the Local API control plane. Busy-state and preflight
 * decisions moved verbatim out of MainViewModel's `LocalApiRuntime.controlPlane` object; the
 * VM object delegates here. Callers keep the same evaluation order and payloads.
 */
internal fun localApiBusyStateFor(state: MainUiState, tuningJson: () -> String): LocalApiBusyState {
    val busyCode = when {
        state.isGenerating || state.engineLifecycle == AgentEngineLifecycle.GENERATING ->
            "generation_in_progress"
        state.engineLifecycle == AgentEngineLifecycle.LOADING -> "model_loading"
        state.engineLifecycle == AgentEngineLifecycle.RELOADING -> "model_reloading"
        state.engineLifecycle == AgentEngineLifecycle.ROLLING_BACK -> "profile_rollback"
        state.tuningJobState in setOf(
            AgentTuningJobState.QUEUED,
            AgentTuningJobState.RUNNING,
            AgentTuningJobState.VALIDATING,
            AgentTuningJobState.CANCELING,
            AgentTuningJobState.RECOVERING
        ) -> "tuning_in_progress"
        state.busy && !state.isGenerating -> "runtime_busy"
        else -> return LocalApiBusyState.IDLE
    }
    return LocalApiBusyState(
        busy = true,
        code = busyCode,
        message = state.statusMessage ?: "本地模型运行时正在执行独占操作，请稍后重试。",
        retryAfterMs = 1_000L,
        detailsJson = tuningJson()
    )
}

internal fun localApiPreflightFor(
    request: LocalApiPreflightRequest,
    state: MainUiState,
    engine: McaInferenceService,
    publicModelId: (displayName: String, id: String) -> String,
    runtimeProfileJson: () -> String
): LocalApiPreflightResult {
    val requestedModel = request.requestedModel?.trim().orEmpty()
    if (requestedModel.isNotBlank()) {
        val matches = requestedModel == state.loadedModelId ||
            requestedModel == state.loadedModelName ||
            state.models.firstOrNull { it.id == state.loadedModelId }?.let { model ->
                requestedModel == model.fileName ||
                    requestedModel == model.displayName ||
                    requestedModel == publicModelId(model.displayName, model.id)
            } == true
        if (!matches) {
            return LocalApiPreflightResult.Rejected(
                httpStatus = 409,
                code = "model_mismatch",
                message = "请求的模型不是当前正式 MainActivity 已加载模型。",
                detailsJson = JSONObject()
                    .put("requestedModel", requestedModel)
                    .put("loadedModelId", state.loadedModelId)
                    .put("loadedModelName", state.loadedModelName)
                    .toString()
            )
        }
    }
    return when (val preflight = runBlocking(Dispatchers.IO) {
        engine.preflightChat(request.chatRequest)
    }) {
        is CompletionPreflight.Ready -> LocalApiPreflightResult.Ready
        is CompletionPreflight.Rejected -> LocalApiPreflightResult.Rejected(
            httpStatus = 409,
            code = preflight.code,
            message = preflight.message,
            detailsJson = JSONObject()
                .put("changedFields", JSONArray(preflight.changedFields.toList().sorted()))
                .put("quarantinedOverrides", JSONArray().also { array ->
                    preflight.quarantinedOverrides.forEach { value -> array.put(value.toString()) }
                })
                .put("profile", JSONObject(runtimeProfileJson()))
                .toString()
        )
    }
}

internal fun localApiTuningJobFor(
    requestedJobId: String?,
    currentIdentityKey: () -> String?,
    currentActiveTuningJobId: () -> String?,
    lookupJob: suspend (String) -> TuningJobEntity?,
    lookupActiveJob: suspend (String) -> TuningJobEntity?,
    tuningJson: () -> String,
    publicJobJson: (TuningJobEntity) -> JSONObject
): LocalApiControlResult {
    val requested = requestedJobId?.takeIf { it.isNotBlank() && !it.equals("current", ignoreCase = true) }
    val job = if (requested == null) {
        val identityKey = currentIdentityKey()
        runBlocking(Dispatchers.IO) {
            currentActiveTuningJobId()?.let { lookupJob(it) }
                ?: identityKey?.let { lookupActiveJob(it) }
        }
    } else {
        runBlocking(Dispatchers.IO) { lookupJob(requested) }
    }
    if (requested == null) {
        val payload = JSONObject(tuningJson())
        if (job != null) {
            payload
                .put("jobId", job.jobId)
                .put("state", job.state.lowercase(Locale.ROOT))
                .put("phase", job.phase)
                .put("job", publicJobJson(job))
        }
        return LocalApiControlResult.Success(payload.toString())
    }
    job
        ?: return LocalApiControlResult.Rejected(
            httpStatus = 404,
            code = "tuning_job_not_found",
            message = "未找到调优任务：$requested"
        )
    return LocalApiControlResult.Success(
        JSONObject(tuningJson())
            .put("job", publicJobJson(job))
            .toString()
    )
}

/**
 * P8/S40-2B-1: the MainViewModel surface used by [localApiCreateTuningJobFor]. Members mirror
 * the private helpers they delegate to so the moved body keeps its original call shapes; the
 * VM stays the only writer of `activeTuningJobId` and `MainUiState`.
 */
internal interface LocalApiTuningControlPort {
    fun activeRuntimeIdentity(): ModelRuntimeIdentity?
    fun activeModelForRuntimeProfile(): ModelManifest?
    fun localApiModelMatches(requestedModel: String, state: MainUiState, model: ModelManifest): Boolean
    fun localApiDebugMode(value: String): AgentDebugMode?
    fun localApiPerformancePreference(value: String?): UserPreference?
    fun localApiTuningRejected(
        httpStatus: Int,
        code: String,
        message: String,
        details: JSONObject = JSONObject()
    ): LocalApiControlResult.Rejected
    fun localApiTuningConflict(action: String, activeJobId: String?, message: String): LocalApiControlResult.Rejected
    fun localApiExclusiveLifecycleConflict(action: String, allowedStagedJobId: String? = null): LocalApiControlResult.Rejected?
    fun assignActiveTuningJobId(jobId: String?)
    fun updateUiState(transform: (MainUiState) -> MainUiState)
    fun tuningJobPublicJson(job: TuningJobEntity): JSONObject
    fun startAgentTuning(
        debugMode: AgentDebugMode,
        autoApply: Boolean,
        precreatedJob: TuningJobEntity?,
        preferenceOverride: UserPreference?
    ): Boolean
    suspend fun activeTuningJob(identityKey: String): TuningJobEntity?
    suspend fun createTuningJob(identityKey: String, autoApplyLoadChanges: Boolean, phase: String): TuningJobEntity
    suspend fun transitionTuningJob(
        jobId: String,
        state: PersistedTuningJobState,
        phase: String,
        failureCode: String?,
        failureSummary: String?
    ): TuningJobEntity?

    suspend fun tuningJob(jobId: String): TuningJobEntity?

    suspend fun pendingTransaction(identityKey: String): PendingRuntimeProfileTransaction?

    fun setTuningCancelRequested(value: Boolean)

    fun setTuningPauseRequested(value: Boolean)

    fun launchTuningApply(pending: PendingRuntimeProfileTransaction)

    suspend fun currentRuntimeState(identityKey: String): CurrentRuntimeProfileState?

    fun launchTuningRollback(identity: ModelRuntimeIdentity, model: ModelManifest)
}

/**
 * P8/S40-2B-1: the Local API control-plane main tuning-job create overload, moved verbatim out
 * of MainViewModel's `LocalApiRuntime.controlPlane` object. The VM keeps every state and
 * persistence write behind [LocalApiTuningControlPort]; evaluation order, rejection matrix and
 * payloads are unchanged.
 */
internal fun localApiCreateTuningJobFor(
    request: LocalApiTuningJobCreateRequest,
    idempotencyKey: String,
    state: MainUiState,
    engine: McaInferenceService,
    port: LocalApiTuningControlPort
): LocalApiControlResult = with(port) {
    val identity = activeRuntimeIdentity()
        ?: return localApiTuningRejected(
            httpStatus = 409,
            code = "model_not_loaded",
            message = "请先通过正式 MainActivity 加载本地模型。"
        )
    val model = activeModelForRuntimeProfile()
        ?: return localApiTuningRejected(
            httpStatus = 409,
            code = "model_not_loaded",
            message = "当前模型没有可调优的本地运行身份。"
        )
    if (engine.activeExecutionProfile() == null) {
        return localApiTuningRejected(
            httpStatus = 409,
            code = "profile_not_loaded",
            message = "当前模型尚未建立 active execution profile。"
        )
    }
    if (!localApiModelMatches(request.modelId, state, model)) {
        return localApiTuningRejected(
            httpStatus = 409,
            code = "model_mismatch",
            message = "调优请求的模型不是当前正式 MainActivity 已加载模型。",
            details = JSONObject()
                .put("requestedModel", request.modelId)
                .put("loadedModelId", state.loadedModelId)
                .put("loadedModelName", state.loadedModelName)
        )
    }
    val debugMode = localApiDebugMode(request.mode)
        ?: return localApiTuningRejected(
            httpStatus = 400,
            code = "invalid_tuning_mode",
            message = "mode 仅支持 quick、standard、deep 或 power_save。"
        )
    val requestedPreference = localApiPerformancePreference(request.performancePreference)
    if (!request.performancePreference.isNullOrBlank() && requestedPreference == null) {
        return localApiTuningRejected(
            httpStatus = 400,
            code = "invalid_performance_preference",
            message = "performancePreference 仅支持 balanced、speed、quality、long_context 或 power_save。"
        )
    }
    val preference = requestedPreference ?: if (debugMode == AgentDebugMode.PowerSave) {
        state.preference.copy(mode = PerformanceMode.PowerSave)
    } else {
        state.preference
    }
    val existing = runCatching {
        runBlocking(Dispatchers.IO) { activeTuningJob(identity.identityHash) }
    }.getOrElse { error ->
        return localApiTuningRejected(
            httpStatus = 409,
            code = "tuning_state_unavailable",
            message = error.message ?: "无法读取当前调优任务状态。"
        )
    }
    if (existing != null) {
        return localApiTuningConflict(
            action = "create",
            activeJobId = existing.jobId,
            message = "当前模型已有未结束的调优任务。"
        )
    }
    localApiExclusiveLifecycleConflict("create")?.let { return it }
    val job = runCatching {
        runBlocking(Dispatchers.IO) {
            createTuningJob(
                identityKey = identity.identityHash,
                autoApplyLoadChanges = request.autoApply,
                phase = "QUEUED"
            )
        }
    }.getOrElse { error ->
        return localApiTuningRejected(
            httpStatus = 409,
            code = "tuning_create_conflict",
            message = error.message ?: "调优任务创建失败。"
        )
    }
    assignActiveTuningJobId(job.jobId)
    updateUiState {
        it.copy(
            tuningJobState = AgentTuningJobState.QUEUED,
            tuningPhase = "等待取得调优生命周期",
            statusMessage = "Local API 已创建调优任务，正在进入安全候选搜索。"
        )
    }
    val started = startAgentTuning(
        debugMode = debugMode,
        autoApply = request.autoApply,
        precreatedJob = job,
        preferenceOverride = preference
    )
    if (!started) {
        val failed = runCatching {
            runBlocking(Dispatchers.IO) {
                transitionTuningJob(
                    jobId = job.jobId,
                    state = PersistedTuningJobState.FAILED,
                    phase = "START_REJECTED",
                    failureCode = "LIFECYCLE_CONFLICT",
                    failureSummary = "调优任务未能取得运行时生命周期所有权。"
                )
            }
        }.getOrNull()
        assignActiveTuningJobId(null)
        updateUiState {
            it.copy(
                tuningJobState = AgentTuningJobState.FAILED,
                tuningPhase = "启动冲突",
                statusMessage = "调优任务未能启动，运行时状态已变化。"
            )
        }
        return localApiTuningConflict(
            action = "create",
            activeJobId = failed?.jobId,
            message = "调优任务未能启动，运行时状态已变化。"
        )
    }
    return com.muyuchat.api.local.LocalApiControlResult.Success(
        json = JSONObject()
            .put("accepted", true)
            .put("job", tuningJobPublicJson(job))
            .put("idempotencyKeyAccepted", idempotencyKey.isNotBlank())
            .toString(),
        httpStatus = 202
    )
}


/**
 * P8/S40-2B-2: the Local API control-plane apply overload, moved verbatim out of
 * MainViewModel's `LocalApiRuntime.controlPlane` object. Validation chain, gated state writes
 * and the 202 accept payload are unchanged; the apply job launch and its lifecycle ownership
 * stay on the VM behind [LocalApiTuningControlPort.launchTuningApply].
 */
internal fun localApiApplyTuningJobFor(
    jobId: String,
    idempotencyKey: String,
    port: LocalApiTuningControlPort
): LocalApiControlResult = with(port) {
    val identity = activeRuntimeIdentity()
        ?: return localApiTuningRejected(409, "model_not_loaded", "当前没有已加载的本地模型。")
    if (activeModelForRuntimeProfile() == null) {
        return localApiTuningRejected(409, "model_not_loaded", "当前模型没有可应用的运行配置。")
    }
    val job = runCatching {
        runBlocking(Dispatchers.IO) { tuningJob(jobId) }
    }.getOrNull() ?: return localApiTuningRejected(
        httpStatus = 404,
        code = "tuning_job_not_found",
        message = "未找到调优任务：$jobId"
    )
    if (job.identityKey != identity.identityHash) {
        return localApiTuningRejected(
            409,
            "tuning_job_model_mismatch",
            "该调优任务不属于当前已加载模型。"
        )
    }
    localApiExclusiveLifecycleConflict("apply", allowedStagedJobId = jobId)?.let { return it }
    val pending = runCatching {
        runBlocking(Dispatchers.IO) { pendingTransaction(identity.identityHash) }
    }.getOrElse { error ->
        return localApiTuningRejected(
            409,
            "pending_profile_unavailable",
            error.message ?: "无法读取 staged candidate。"
        )
    }
    if (pending == null || pending.journal.jobId != jobId) {
        return localApiTuningRejected(
            409,
            "staged_candidate_not_found",
            "该任务没有可应用的 staged candidate。"
        )
    }
    if (pending.journal.state != TuningJournalState.STAGED.name) {
        return localApiTuningRejected(
            409,
            "staged_candidate_not_ready",
            "候选事务当前处于 ${pending.journal.state.lowercase()}，不能重复应用。"
        )
    }
    if (job.state !in setOf(PersistedTuningJobState.PAUSED.name, PersistedTuningJobState.RUNNING.name)) {
        return localApiTuningRejected(
            409,
            "tuning_apply_state_conflict",
            "调优任务当前处于 ${job.state.lowercase()}，不能应用 staged candidate。"
        )
    }
    assignActiveTuningJobId(jobId)
    setTuningCancelRequested(false)
    setTuningPauseRequested(false)
    updateUiState {
        it.copy(
            busy = true,
            engineLifecycle = AgentEngineLifecycle.RELOADING,
            tuningJobState = AgentTuningJobState.VALIDATING,
            tuningPhase = "等待应用 staged candidate",
            statusMessage = "Local API 已接受 apply，正在取得运行时生命周期。"
        )
    }
    launchTuningApply(pending)
    return com.muyuchat.api.local.LocalApiControlResult.Success(
        json = JSONObject()
            .put("accepted", true)
            .put("action", "apply")
            .put("job", tuningJobPublicJson(job))
            .put("idempotencyKeyAccepted", idempotencyKey.isNotBlank())
            .toString(),
        httpStatus = 202
    )
}


/**
 * P8/S40-2B-3: the Local API control-plane rollback overload, moved verbatim out of
 * MainViewModel's `LocalApiRuntime.controlPlane` object. Validation chain, gated state writes
 * and the 202 accept payload are unchanged; the rollback job launch and its lifecycle
 * ownership stay on the VM behind [LocalApiTuningControlPort.launchTuningRollback].
 */
internal fun localApiRollbackTuningFor(
    idempotencyKey: String,
    port: LocalApiTuningControlPort
): LocalApiControlResult = with(port) {
    val identity = activeRuntimeIdentity()
        ?: return localApiTuningRejected(409, "model_not_loaded", "当前没有已加载的本地模型。")
    val model = activeModelForRuntimeProfile()
        ?: return localApiTuningRejected(409, "model_not_loaded", "当前模型没有可回滚的运行配置。")
    val runtimeState = runCatching {
        runBlocking(Dispatchers.IO) { currentRuntimeState(identity.identityHash) }
    }.getOrElse { error ->
        return localApiTuningRejected(409, "rollback_state_unavailable", error.message ?: "无法读取回滚状态。")
    } ?: return localApiTuningRejected(409, "rollback_not_available", "当前运行配置尚未持久化。")
    runtimeState.activeJob?.let { activeJob ->
        return localApiTuningConflict(
            action = "rollback",
            activeJobId = activeJob.jobId,
            message = "存在未结束的调优任务，不能并发回滚。"
        )
    }
    localApiExclusiveLifecycleConflict("rollback")?.let { return it }
    val active = runtimeState.activeProfile
        ?: return localApiTuningRejected(409, "rollback_not_available", "当前没有 committed profile。")
    val rollbackTarget = active.parentCommittedProfileId
        ?: runtimeState.pointers?.lastKnownGoodProfileId?.takeIf { it != active.profileId }
    if (rollbackTarget == null) {
        return localApiTuningRejected(409, "rollback_not_available", "当前 profile 没有更早的稳定版本可回滚。")
    }
    updateUiState {
        it.copy(
            busy = true,
            engineLifecycle = AgentEngineLifecycle.ROLLING_BACK,
            tuningJobState = AgentTuningJobState.RECOVERING,
            tuningPhase = "等待回滚生命周期",
            statusMessage = "Local API 已接受 rollback，正在取得运行时生命周期。"
        )
    }
    launchTuningRollback(identity, model)
    return com.muyuchat.api.local.LocalApiControlResult.Success(
        json = JSONObject()
            .put("accepted", true)
            .put("action", "rollback")
            .put("targetProfileId", rollbackTarget)
            .put("idempotencyKeyAccepted", idempotencyKey.isNotBlank())
            .toString(),
        httpStatus = 202
    )
}
