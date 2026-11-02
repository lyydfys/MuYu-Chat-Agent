package com.muyuchat.mca.video.remote

import android.content.Context
import android.util.Log
import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoRemoteCapabilities
import com.muyuchat.core.video.contract.VideoRemoteEndpointBinding
import com.muyuchat.core.video.contract.VideoRemoteProbePort
import com.muyuchat.core.video.contract.VideoRemoteProbeResult
import com.muyuchat.core.video.contract.VideoRequestId
import com.muyuchat.core.video.contract.VideoRunStage
import com.muyuchat.mca.video.execution.VideoExecutionOutcome
import com.muyuchat.mca.video.execution.VideoExecutionRequest
import com.muyuchat.mca.video.execution.VideoGenerationOwner
import com.muyuchat.mca.video.media.VideoMediaPublisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * V3-01 remote ComfyUI execution adapter.
 *
 * It executes ONLY a run whose request carries an explicit [VideoRemoteEndpointBinding] and whose
 * immutable document carries the saved API-format graph. It never falls back to local execution and
 * never re-submits when the server response is lost. VideoGenerationOwner remains the only writer
 * of business run state; this adapter reports only stages that the remote WebSocket actually
 * announced and returns the downloaded file path for the owner/publisher to verify and publish.
 *
 * Credential boundary: the key is parsed into an in-memory [RemoteComfyEndpoint], rendered only as
 * `Authorization: Bearer`, and never placed in a URL, log line, exception, Room row or snapshot.
 */
class RemoteComfyExecutionAdapter(
    @Suppress("unused") context: Context,
    private val publisher: VideoMediaPublisher,
    private val onRemotePromptId: (runId: String, attemptId: String, promptId: String) -> Unit,
    client: OkHttpClient = defaultClient(),
    private val logger: (String) -> Unit = { message -> Log.i("RemoteComfy", message) },
) : VideoGenerationOwner.VideoExecutionAdapter, VideoRemoteProbePort {

    private val http = RemoteComfyHttp(client)
    private val stoppedRequests = ConcurrentHashMap.newKeySet<String>()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val cancellation = RemoteComfyCancellation(http)
    private val waiter = RemoteComfyWaiter(
        http = http,
        publisher = publisher,
        cancellation = cancellation,
        isStopped = { requestId -> stoppedRequests.contains(requestId) },
        log = { endpointId, promptId, status -> logStatus(endpointId, promptId, status) },
    )

    override suspend fun execute(
        request: VideoExecutionRequest,
        reportStage: suspend (VideoRunStage, Long?, Long?) -> Unit,
    ): VideoExecutionOutcome {
        val requestId = request.requestId.value
        if (stoppedRequests.remove(requestId)) return VideoExecutionOutcome.Stopped

        val binding = request.remoteBinding
            ?: return failed(VideoErrorCode.INPUT_INVALID, "缺少显式远端 endpoint binding")
        val raw = request.document.rawSourceRef
        if (raw == null || raw.isBlank()) {
            return failed(
                VideoErrorCode.WORKFLOW_UNSUPPORTED_NODE,
                "该修订没有保存 ComfyUI API-format 原始图，不能提交到远端",
            )
        }
        val endpoint = try {
            RemoteComfyEndpoint.parse(binding)
        } catch (error: RemoteComfyConfigException) {
            return failed(VideoErrorCode.INPUT_INVALID, error.message ?: "远端 endpoint 无效")
        }
        val workflow = try {
            withContext(Dispatchers.IO) { RemoteComfyWorkflow.parse(raw) }
        } catch (error: RemoteComfyWorkflowException) {
            return failed(error.code, error.message ?: "远端工作流无效")
        }

        val clientId = UUID.randomUUID().toString()
        val inbound = Channel<RemoteComfyWsInbound>(
            capacity = 1024,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val startedAt = nowMs()
        var submitted = false
        var promptId: String? = null
        try {
            val opened = CompletableDeferred<Unit>()
            val newSocket = try {
                openSocket(endpoint, clientId, inbound, opened)
            } catch (_: Exception) {
                opened.complete(Unit)
                null
            }
            if (newSocket != null) sockets[requestId] = newSocket
            // Start the event channel first; a failed handshake must not block submission.
            withTimeoutOrNull(WS_OPEN_WAIT_MS) { opened.await() }
            if (stoppedRequests.contains(requestId)) return VideoExecutionOutcome.Stopped

            val body = try {
                withContext(Dispatchers.IO) { RemoteComfyWorkflow.promptBody(workflow, clientId) }
            } catch (error: RemoteComfyWorkflowException) {
                return failed(error.code, error.message ?: "远端提交体无效")
            }
            if (stoppedRequests.contains(requestId)) return VideoExecutionOutcome.Stopped

            submitted = true
            val response = http.postJson(
                requestId = requestId,
                request = endpoint.promptRequest(body),
                timeoutMs = SUBMIT_TIMEOUT_MS,
            )
            val knownPromptId = RemoteComfyWorkflow.readPromptId(response.body)
            promptId = knownPromptId
            // Persist the non-secret remote identity immediately, before waiting for any result.
            val persisted = withContext(Dispatchers.IO) {
                runCatching {
                    onRemotePromptId(request.runId.value, request.attemptId.value, knownPromptId)
                }.isSuccess
            }
            if (!persisted) logStatus(endpoint.endpointId, knownPromptId, "prompt_persist_failed")
            logStatus(endpoint.endpointId, knownPromptId, "submitted")
            if (stoppedRequests.contains(requestId)) {
                return cancellation.stopAfterSubmit(requestId, endpoint, knownPromptId)
            }
            return waiter.await(
                request = request,
                requestId = requestId,
                endpoint = endpoint,
                workflow = workflow,
                promptId = knownPromptId,
                inbound = inbound,
                reportStage = reportStage,
                startedAt = startedAt,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RemoteComfyRedirectException) {
            return failed(
                VideoErrorCode.REMOTE_AUTH_FAILED,
                "远端返回重定向（HTTP ${error.statusCode}），已拒绝",
            )
        } catch (error: RemoteComfyAuthException) {
            return failed(
                VideoErrorCode.REMOTE_AUTH_FAILED,
                "远端认证失败（HTTP ${error.statusCode}）",
            )
        } catch (error: RemoteComfyStatusException) {
            return submissionStatusFailure(error.statusCode)
        } catch (error: RemoteComfyLimitException) {
            return if (submitted && promptId == null) {
                failed(VideoErrorCode.REMOTE_UNKNOWN, UNKNOWN_SUBMIT_DETAIL)
            } else {
                failed(VideoErrorCode.INPUT_TOO_LARGE, error.message ?: "远端请求超过上限")
            }
        } catch (error: RemoteComfyProtocolException) {
            return if (submitted && promptId == null) {
                failed(VideoErrorCode.REMOTE_UNKNOWN, UNKNOWN_SUBMIT_DETAIL)
            } else {
                failed(VideoErrorCode.MEDIA_VERIFY_FAILED, error.message ?: "远端响应无效")
            }
        } catch (error: SocketTimeoutException) {
            if (stoppedRequests.contains(requestId)) {
                return cancellation.stopAfterSubmit(requestId, endpoint, promptId)
            }
            return if (submitted && promptId == null) {
                failed(VideoErrorCode.REMOTE_UNKNOWN, UNKNOWN_SUBMIT_DETAIL)
            } else {
                failed(VideoErrorCode.REMOTE_TIMEOUT, "远端请求超时")
            }
        } catch (error: IOException) {
            if (stoppedRequests.contains(requestId)) {
                return cancellation.stopAfterSubmit(requestId, endpoint, promptId)
            }
            return if (submitted && promptId == null) {
                failed(VideoErrorCode.REMOTE_UNKNOWN, UNKNOWN_SUBMIT_DETAIL)
            } else {
                failed(VideoErrorCode.REMOTE_UNKNOWN, "远端连接失败，状态未知")
            }
        } catch (error: Exception) {
            if (stoppedRequests.contains(requestId)) {
                return cancellation.stopAfterSubmit(requestId, endpoint, promptId)
            }
            return if (promptId != null) {
                failed(VideoErrorCode.REMOTE_UNKNOWN, "远端任务状态未知，需对账")
            } else {
                failed(
                    VideoErrorCode.NATIVE_EXECUTION_FAILED,
                    "远端执行失败（${error.javaClass.simpleName}）",
                )
            }
        } finally {
            inbound.close()
            sockets.remove(requestId)?.let { open -> runCatching { open.close(1000, "done") } }
            stoppedRequests.remove(requestId)
            http.cancel(requestId)
        }
    }

    override fun cancel(requestId: VideoRequestId) {
        val id = requestId.value
        // The attempt coroutine performs the suspend queue-delete / targeted-interrupt requests;
        // this method only makes the local stop observable and aborts the in-flight HTTP/WS call.
        stoppedRequests.add(id)
        http.cancel(id)
        sockets.remove(id)?.let { open -> runCatching { open.close(1000, "client_stop") } }
    }

    override suspend fun probe(binding: VideoRemoteEndpointBinding): VideoRemoteProbeResult {
        val endpoint = try {
            RemoteComfyEndpoint.parse(binding)
        } catch (error: RemoteComfyConfigException) {
            return VideoRemoteProbeResult.Failed(
                VideoErrorCode.INPUT_INVALID,
                error.message ?: "远端 endpoint 无效",
            )
        }
        val requestId = "probe-${UUID.randomUUID()}"
        return try {
            val status = http.getStatus(requestId, endpoint.queueRequest(), PROBE_TIMEOUT_MS)
            logStatus(endpoint.endpointId, null, "probe_$status")
            when (status) {
                200 -> VideoRemoteProbeResult.Ready(
                    VideoRemoteCapabilities(
                        serverVersion = null,
                        supportsTargetedCancel = binding.allowTargetedCancel,
                        supportsImageUpload = false,
                    ),
                )

                401, 403 -> VideoRemoteProbeResult.AuthFailed
                else -> VideoRemoteProbeResult.Failed(
                    VideoErrorCode.REMOTE_AUTH_FAILED,
                    "远端探测返回 HTTP $status",
                )
            }
        } catch (error: RemoteComfyRedirectException) {
            VideoRemoteProbeResult.Failed(
                VideoErrorCode.REMOTE_AUTH_FAILED,
                "远端探测返回重定向（HTTP ${error.statusCode}），已拒绝",
            )
        } catch (error: RemoteComfyLimitException) {
            VideoRemoteProbeResult.Failed(
                VideoErrorCode.REMOTE_AUTH_FAILED,
                "远端探测响应超过 4 MiB 上限",
            )
        } catch (error: RemoteComfyAuthException) {
            VideoRemoteProbeResult.AuthFailed
        } catch (error: SocketTimeoutException) {
            VideoRemoteProbeResult.Failed(VideoErrorCode.REMOTE_TIMEOUT, "远端探测超时")
        } catch (error: IOException) {
            VideoRemoteProbeResult.Failed(VideoErrorCode.REMOTE_TIMEOUT, "远端探测无法连接")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            VideoRemoteProbeResult.Failed(VideoErrorCode.REMOTE_TIMEOUT, "远端探测失败")
        }
    }

    private fun submissionStatusFailure(statusCode: Int): VideoExecutionOutcome.Failed = when {
        statusCode == 401 || statusCode == 403 -> failed(
            VideoErrorCode.REMOTE_AUTH_FAILED,
            "远端认证失败（HTTP $statusCode）",
        )

        statusCode in 400..499 -> failed(
            VideoErrorCode.INPUT_INVALID,
            "远端拒绝了提交（HTTP $statusCode）",
        )

        else -> failed(
            VideoErrorCode.NATIVE_EXECUTION_FAILED,
            "远端提交失败（HTTP $statusCode）",
        )
    }

    private fun openSocket(
        endpoint: RemoteComfyEndpoint,
        clientId: String,
        inbound: Channel<RemoteComfyWsInbound>,
        opened: CompletableDeferred<Unit>,
    ): WebSocket {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(Unit)
                inbound.trySend(RemoteComfyWsInbound.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > MAX_WS_MESSAGE_CHARS) return
                val event = RemoteComfyWsParser.parse(text) ?: return
                inbound.trySend(RemoteComfyWsInbound.Message(event))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                opened.complete(Unit)
                inbound.trySend(RemoteComfyWsInbound.Closed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.complete(Unit)
                inbound.trySend(RemoteComfyWsInbound.Closed)
            }
        }
        return http.newWebSocket(endpoint.webSocketUrl(clientId), endpoint.apiKey, listener)
    }

    private fun failed(code: VideoErrorCode, detail: String?): VideoExecutionOutcome.Failed =
        VideoExecutionOutcome.Failed(code, detail)

    private fun logStatus(endpointId: String, promptId: String?, status: String) {
        val safeEndpoint = sanitize(endpointId, 64).ifEmpty { "-" }
        val safePrompt = promptId?.let { sanitize(it, 128) }?.takeIf { it.isNotEmpty() } ?: "-"
        val safeStatus = sanitize(status, 64)
        runCatching { logger("endpoint=$safeEndpoint prompt=$safePrompt status=$safeStatus") }
    }

    private fun sanitize(value: String, maxChars: Int): String {
        val builder = StringBuilder(minOf(value.length, maxChars))
        for (ch in value) {
            if (builder.length >= maxChars) break
            if (!ch.isISOControl()) builder.append(ch)
        }
        return builder.toString()
    }

    companion object {
        private const val WS_OPEN_WAIT_MS = 2_000L
        private const val PROBE_TIMEOUT_MS = 5_000L
        private const val SUBMIT_TIMEOUT_MS = 60_000L
        private const val MAX_WS_MESSAGE_CHARS = 1024 * 1024
        private const val UNKNOWN_SUBMIT_DETAIL = "提交结果未知，需对账"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        private fun nowMs(): Long = System.nanoTime() / 1_000_000L
    }
}
