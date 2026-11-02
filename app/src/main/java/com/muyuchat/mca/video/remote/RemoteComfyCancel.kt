package com.muyuchat.mca.video.remote

import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.mca.video.execution.VideoExecutionOutcome
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The three cancellation levels are deliberately separate; none is silently promoted. */
internal data class RemoteComfyCancelPlan(
    val deleteFromQueue: Boolean,
    val targetedInterrupt: Boolean,
    val localStopDetail: String,
) {
    companion object {
        fun plan(promptId: String?, allowTargetedCancel: Boolean): RemoteComfyCancelPlan {
            val hasPrompt = promptId != null
            return RemoteComfyCancelPlan(
                deleteFromQueue = hasPrompt,
                // Empty-body /interrupt is a global interrupt on old servers and is never sent.
                targetedInterrupt = hasPrompt && allowTargetedCancel,
                localStopDetail = when {
                    !hasPrompt -> "本机已停止等待；提交结果未知，远端可能仍在运行"
                    allowTargetedCancel -> "本机已停止等待；已请求 targeted cancel，但远端停止未确认"
                    else -> "本机已停止等待；服务器未启用 targeted cancel，远端可能仍在运行"
                },
            )
        }
    }
}

/**
 * Best-effort remote cancellation with an explicit capability boundary.
 *
 * Queued deletion is always attempted when a prompt id is known. `/interrupt` is sent only when the
 * binding explicitly enables targeted cancel, and always carries `prompt_id`; an empty-body global
 * interrupt is never constructed. A local stop is never reported as a confirmed remote stop.
 */
internal class RemoteComfyCancellation(private val http: RemoteComfyHttp) {

    suspend fun stopAfterSubmit(
        requestId: String,
        endpoint: RemoteComfyEndpoint,
        promptId: String?,
    ): VideoExecutionOutcome {
        val plan = RemoteComfyCancelPlan.plan(promptId, endpoint.allowTargetedCancel)
        val interruptStatus = performCancelRequests(requestId, endpoint, promptId, plan)
        return when {
            promptId == null -> failed(VideoErrorCode.REMOTE_CANCEL_UNSUPPORTED, plan.localStopDetail)
            interruptStatus == 200 -> {
                // The server accepted a targeted interrupt request; remote completion is still
                // not confirmed, so the caller must not describe this as a remote cancellation.
                VideoExecutionOutcome.Stopped
            }

            else -> failed(VideoErrorCode.REMOTE_CANCEL_UNSUPPORTED, plan.localStopDetail)
        }
    }

    suspend fun timeoutOutcome(
        requestId: String,
        endpoint: RemoteComfyEndpoint,
        promptId: String?,
    ): VideoExecutionOutcome {
        if (promptId != null) {
            // Best effort only; the remote task may still be running and is never reported stopped.
            performCancelRequests(
                requestId = requestId,
                endpoint = endpoint,
                promptId = promptId,
                plan = RemoteComfyCancelPlan.plan(promptId, endpoint.allowTargetedCancel),
            )
        }
        return failed(VideoErrorCode.REMOTE_TIMEOUT, "本机停止等待，远端状态未知")
    }

    private suspend fun performCancelRequests(
        requestId: String,
        endpoint: RemoteComfyEndpoint,
        promptId: String?,
        plan: RemoteComfyCancelPlan,
    ): Int? {
        val knownPromptId = promptId ?: return null
        return withContext(NonCancellable) {
            if (plan.deleteFromQueue) {
                val body = JSONObject()
                    .put("delete", JSONArray().put(knownPromptId))
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                try {
                    http.postStatus(
                        requestId = "$requestId-cancel-queue",
                        request = endpoint.postJsonRequest(body, "queue"),
                        timeoutMs = CANCEL_TIMEOUT_MS,
                    )
                } catch (_: Exception) {
                    // Best effort: the run remains locally stopped and the remote state is unknown.
                }
            }
            if (!plan.targetedInterrupt) return@withContext null
            // A targeted body is mandatory; no empty-body global interrupt is ever sent.
            val body = JSONObject()
                .put("prompt_id", knownPromptId)
                .toString()
                .toByteArray(Charsets.UTF_8)
            try {
                http.postStatus(
                    requestId = "$requestId-cancel-interrupt",
                    request = endpoint.postJsonRequest(body, "interrupt"),
                    timeoutMs = CANCEL_TIMEOUT_MS,
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun failed(code: VideoErrorCode, detail: String?): VideoExecutionOutcome.Failed =
        VideoExecutionOutcome.Failed(code, detail)

    private companion object {
        const val CANCEL_TIMEOUT_MS = 5_000L
    }
}
