package com.muyuchat.mca

import com.muyuchat.api.local.LocalApiBusyState
import com.muyuchat.api.local.LocalApiControlResult
import com.muyuchat.api.local.LocalApiPreflightRequest
import com.muyuchat.api.local.LocalApiPreflightResult
import com.muyuchat.core.engine.CompletionPreflight
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.agent.AgentTuningJobState
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
