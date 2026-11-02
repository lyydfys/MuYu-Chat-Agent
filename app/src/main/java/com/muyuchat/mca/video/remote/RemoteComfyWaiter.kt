package com.muyuchat.mca.video.remote

import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoRunStage
import com.muyuchat.mca.video.execution.VideoExecutionOutcome
import com.muyuchat.mca.video.execution.VideoExecutionRequest
import com.muyuchat.mca.video.media.VideoMediaPublisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal sealed class RemoteComfyWsInbound {
    data object Connected : RemoteComfyWsInbound()
    data object Closed : RemoteComfyWsInbound()
    data class Message(val event: RemoteComfyWsEvent) : RemoteComfyWsInbound()
}

/**
 * Waits for one submitted remote prompt using WebSocket stages plus history/queue reconciliation.
 *
 * Stage reports come only from mapped `executing`/`progress` events. History is the terminal
 * authority; timeouts of the socket or of individual polls never fabricate a result. A run absent
 * from both history and queue for a grace period becomes REMOTE_UNKNOWN, not success.
 */
internal class RemoteComfyWaiter(
    private val http: RemoteComfyHttp,
    private val publisher: VideoMediaPublisher,
    private val cancellation: RemoteComfyCancellation,
    private val isStopped: (String) -> Boolean,
    private val log: (String, String?, String) -> Unit = { _, _, _ -> },
) {

    suspend fun await(
        request: VideoExecutionRequest,
        requestId: String,
        endpoint: RemoteComfyEndpoint,
        workflow: RemoteComfyWorkflow,
        promptId: String,
        inbound: Channel<RemoteComfyWsInbound>,
        reportStage: suspend (VideoRunStage, Long?, Long?) -> Unit,
        startedAt: Long,
    ): VideoExecutionOutcome {
        var lastProgressAt = startedAt
        var absentSince: Long? = null
        var lastQueueState: RemoteComfyQueueState? = null
        var terminalHint = false
        var wsClosedLogged = false
        var nextPollAt = startedAt + FIRST_POLL_DELAY_MS
        val projection = RemoteComfyWsProjection(workflow.nodeClasses)

        while (true) {
            if (isStopped(requestId)) {
                return cancellation.stopAfterSubmit(requestId, endpoint, promptId)
            }
            val now = nowMs()
            if (now - startedAt >= OVERALL_TIMEOUT_MS) {
                return cancellation.timeoutOutcome(requestId, endpoint, promptId)
            }
            if (!terminalHint && now - lastProgressAt >= NO_PROGRESS_TIMEOUT_MS) {
                return cancellation.timeoutOutcome(requestId, endpoint, promptId)
            }

            val waitMs = (nextPollAt - now).coerceIn(0L, LOOP_SLICE_MS)
            val inboundEvent = if (waitMs > 0L) {
                withTimeoutOrNull(waitMs) { inbound.receive() }
            } else {
                null
            }
            val afterEvent = nowMs()
            when (inboundEvent) {
                RemoteComfyWsInbound.Connected -> log(endpoint.endpointId, promptId, "ws_open")
                RemoteComfyWsInbound.Closed -> if (!wsClosedLogged) {
                    wsClosedLogged = true
                    log(endpoint.endpointId, promptId, "ws_closed")
                }

                is RemoteComfyWsInbound.Message -> {
                    val event = inboundEvent.event
                    if (event.matchesPrompt(promptId)) {
                        lastProgressAt = afterEvent
                        when (val projected = projection.apply(event)) {
                            is RemoteComfyWsProjectionResult.Stage -> reportStage(
                                projected.signal.stage,
                                projected.signal.step,
                                projected.signal.stepTotal,
                            )

                            RemoteComfyWsProjectionResult.Completed -> {
                                terminalHint = true
                                nextPollAt = afterEvent
                            }

                            is RemoteComfyWsProjectionResult.Failed -> return failed(
                                VideoErrorCode.NATIVE_EXECUTION_FAILED,
                                projected.summary,
                            )

                            RemoteComfyWsProjectionResult.Ignored -> Unit
                        }
                    }
                }

                null -> Unit
            }

            if (afterEvent >= nextPollAt) {
                nextPollAt = afterEvent + POLL_INTERVAL_MS
                when (
                    val poll = withContext(Dispatchers.IO) {
                        pollRemote(
                            request = request,
                            requestId = requestId,
                            endpoint = endpoint,
                            promptId = promptId,
                            reportStage = reportStage,
                            startedAt = startedAt,
                        )
                    }
                ) {
                    is PollResult.Terminal -> return poll.outcome
                    is PollResult.Active -> {
                        if (poll.state != lastQueueState) {
                            lastProgressAt = afterEvent
                            lastQueueState = poll.state
                            log(
                                endpoint.endpointId,
                                promptId,
                                if (poll.state == RemoteComfyQueueState.RUNNING) "running" else "pending",
                            )
                        }
                        absentSince = null
                    }

                    PollResult.Absent -> {
                        val firstAbsent = absentSince ?: afterEvent
                        if (absentSince == null) log(endpoint.endpointId, promptId, "absent")
                        absentSince = firstAbsent
                        lastQueueState = null
                        if (afterEvent - firstAbsent >= QUEUE_ABSENT_GRACE_MS) {
                            return failed(
                                VideoErrorCode.REMOTE_UNKNOWN,
                                "远端队列与历史均无记录，提交结果未知，需对账",
                            )
                        }
                    }

                    PollResult.Retry -> Unit
                }
                if (terminalHint) {
                    nextPollAt = minOf(nextPollAt, nowMs() + TERMINAL_HINT_POLL_MS)
                }
            }
        }
    }

    private suspend fun pollRemote(
        request: VideoExecutionRequest,
        requestId: String,
        endpoint: RemoteComfyEndpoint,
        promptId: String,
        reportStage: suspend (VideoRunStage, Long?, Long?) -> Unit,
        startedAt: Long,
    ): PollResult {
        val history: RemoteComfyJsonResponse? = try {
            http.getJson(requestId, endpoint.historyRequest(promptId), POLL_TIMEOUT_MS)
        } catch (error: RemoteComfyAuthException) {
            return PollResult.Terminal(
                failed(VideoErrorCode.REMOTE_AUTH_FAILED, "远端认证失败（HTTP ${error.statusCode}）"),
            )
        } catch (error: RemoteComfyRedirectException) {
            return PollResult.Terminal(
                failed(
                    VideoErrorCode.REMOTE_AUTH_FAILED,
                    "远端返回重定向（HTTP ${error.statusCode}），已拒绝",
                ),
            )
        } catch (error: RemoteComfyLimitException) {
            return PollResult.Retry
        } catch (error: RemoteComfyProtocolException) {
            return PollResult.Retry
        } catch (error: RemoteComfyStatusException) {
            // A proxy may answer 404 for an unknown history id; that is "no record", not a
            // terminal result, so queue reconciliation still runs.
            if (error.statusCode != 404) return PollResult.Retry
            null
        } catch (error: SocketTimeoutException) {
            return PollResult.Retry
        } catch (error: IOException) {
            return PollResult.Retry
        }

        if (isStopped(requestId)) {
            return PollResult.Terminal(cancellation.stopAfterSubmit(requestId, endpoint, promptId))
        }
        val record = history?.body?.let { body -> RemoteComfyHistory.record(body, promptId) }
        if (record != null) {
            return when (RemoteComfyHistory.status(record)) {
                RemoteComfyHistoryStatus.ERROR -> PollResult.Terminal(
                    failed(
                        VideoErrorCode.NATIVE_EXECUTION_FAILED,
                        RemoteComfyHistory.errorSummary(record),
                    ),
                )

                RemoteComfyHistoryStatus.SUCCESS,
                RemoteComfyHistoryStatus.UNKNOWN -> {
                    val output = RemoteComfyOutputSelector.select(record)
                    if (output == null) {
                        PollResult.Terminal(
                            failed(
                                VideoErrorCode.MEDIA_VERIFY_FAILED,
                                "远端任务完成但没有可用的 MP4 输出",
                            ),
                        )
                    } else {
                        PollResult.Terminal(
                            downloadOutput(
                                request = request,
                                requestId = requestId,
                                endpoint = endpoint,
                                promptId = promptId,
                                output = output,
                                reportStage = reportStage,
                                startedAt = startedAt,
                            ),
                        )
                    }
                }
            }
        }

        if (isStopped(requestId)) {
            return PollResult.Terminal(cancellation.stopAfterSubmit(requestId, endpoint, promptId))
        }

        val queue = try {
            http.getJson(requestId, endpoint.queueRequest(), POLL_TIMEOUT_MS)
        } catch (error: RemoteComfyAuthException) {
            return PollResult.Terminal(
                failed(VideoErrorCode.REMOTE_AUTH_FAILED, "远端认证失败（HTTP ${error.statusCode}）"),
            )
        } catch (error: RemoteComfyRedirectException) {
            return PollResult.Terminal(
                failed(
                    VideoErrorCode.REMOTE_AUTH_FAILED,
                    "远端返回重定向（HTTP ${error.statusCode}），已拒绝",
                ),
            )
        } catch (error: RemoteComfyLimitException) {
            return PollResult.Retry
        } catch (error: RemoteComfyProtocolException) {
            return PollResult.Retry
        } catch (error: RemoteComfyStatusException) {
            return PollResult.Retry
        } catch (error: SocketTimeoutException) {
            return PollResult.Retry
        } catch (error: IOException) {
            return PollResult.Retry
        }
        if (isStopped(requestId)) {
            return PollResult.Terminal(cancellation.stopAfterSubmit(requestId, endpoint, promptId))
        }
        return when (val state = RemoteComfyQueue.state(queue.body, promptId)) {
            RemoteComfyQueueState.RUNNING,
            RemoteComfyQueueState.PENDING -> PollResult.Active(state)

            RemoteComfyQueueState.ABSENT -> PollResult.Absent
            RemoteComfyQueueState.UNKNOWN -> PollResult.Retry
        }
    }

    private suspend fun downloadOutput(
        request: VideoExecutionRequest,
        requestId: String,
        endpoint: RemoteComfyEndpoint,
        promptId: String,
        output: RemoteComfyOutputRef,
        reportStage: suspend (VideoRunStage, Long?, Long?) -> Unit,
        startedAt: Long,
    ): VideoExecutionOutcome {
        if (isStopped(requestId)) {
            return cancellation.stopAfterSubmit(requestId, endpoint, promptId)
        }
        // A valid MP4 in history is real encode evidence; report ENCODE before handing bytes back.
        reportStage(VideoRunStage.ENCODE, null, null)
        try {
            withContext(Dispatchers.IO) { publisher.ensureRunDirectory(request.runId.value) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failed(VideoErrorCode.PUBLISH_FAILED, "无法创建运行暂存目录")
        }
        val finalFile = publisher.stagedOutput(request.runId.value)
        val part = File(finalFile.absolutePath + ".part")
        withContext(Dispatchers.IO) {
            runCatching { if (part.exists()) part.delete() }
        }
        try {
            val remaining = startedAt + OVERALL_TIMEOUT_MS - nowMs()
            if (remaining <= 0L) return cancellation.timeoutOutcome(requestId, endpoint, promptId)
            val downloaded = withTimeoutOrNull(remaining) {
                http.download(requestId, endpoint.viewRequest(output), part)
            } ?: return cancellation.timeoutOutcome(requestId, endpoint, promptId)
            if (downloaded <= 0L) {
                return failed(VideoErrorCode.MEDIA_VERIFY_FAILED, "远端输出为空")
            }
            val prefix = withContext(Dispatchers.IO) { RemoteComfyMp4.readPrefix(part) }
            if (!RemoteComfyMp4.hasFtyp(prefix)) {
                return failed(VideoErrorCode.MEDIA_VERIFY_FAILED, "下载文件不是有效的 MP4（缺少 ftyp）")
            }
            val promoted = withContext(Dispatchers.IO) { promote(part, finalFile) }
            if (!promoted) {
                return failed(VideoErrorCode.MEDIA_VERIFY_FAILED, "无法写入运行暂存目录")
            }
            if (isStopped(requestId)) {
                return cancellation.stopAfterSubmit(requestId, endpoint, promptId)
            }
            return VideoExecutionOutcome.Succeeded(finalFile.name)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RemoteComfyAuthException) {
            return failed(
                VideoErrorCode.REMOTE_AUTH_FAILED,
                "远端认证失败（HTTP ${error.statusCode}）",
            )
        } catch (error: RemoteComfyRedirectException) {
            return failed(
                VideoErrorCode.MEDIA_VERIFY_FAILED,
                "远端输出下载返回重定向（HTTP ${error.statusCode}），已拒绝",
            )
        } catch (error: RemoteComfyLimitException) {
            return failed(VideoErrorCode.MEDIA_VERIFY_FAILED, "远端输出超过 512 MiB 上限")
        } catch (error: RemoteComfyStatusException) {
            return failed(
                VideoErrorCode.MEDIA_VERIFY_FAILED,
                "远端输出下载失败（HTTP ${error.statusCode}）",
            )
        } catch (error: RemoteComfyProtocolException) {
            return failed(VideoErrorCode.MEDIA_VERIFY_FAILED, "远端输出为空")
        } catch (error: SocketTimeoutException) {
            return failed(VideoErrorCode.REMOTE_TIMEOUT, "远端输出下载超时")
        } catch (error: IOException) {
            if (isStopped(requestId)) {
                return cancellation.stopAfterSubmit(requestId, endpoint, promptId)
            }
            return if (isDiskFull(error)) {
                failed(VideoErrorCode.DISK_FULL, "本地存储空间不足")
            } else {
                failed(VideoErrorCode.MEDIA_VERIFY_FAILED, "远端输出下载失败")
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { if (part.exists()) part.delete() }
            }
        }
    }

    private fun promote(source: File, target: File): Boolean = try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        true
    } catch (_: Exception) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isDiskFull(error: IOException): Boolean {
        val message = error.message.orEmpty()
        return message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true)
    }

    private fun failed(code: VideoErrorCode, detail: String?): VideoExecutionOutcome.Failed =
        VideoExecutionOutcome.Failed(code, detail)

    private sealed class PollResult {
        data class Terminal(val outcome: VideoExecutionOutcome) : PollResult()
        data class Active(val state: RemoteComfyQueueState) : PollResult()
        data object Absent : PollResult()
        data object Retry : PollResult()
    }

    private companion object {
        const val FIRST_POLL_DELAY_MS = 500L
        const val LOOP_SLICE_MS = 500L
        const val POLL_INTERVAL_MS = 3_000L
        const val TERMINAL_HINT_POLL_MS = 1_000L
        const val QUEUE_ABSENT_GRACE_MS = 30_000L
        const val NO_PROGRESS_TIMEOUT_MS = 20L * 60L * 1000L
        const val OVERALL_TIMEOUT_MS = 60L * 60L * 1000L
        const val POLL_TIMEOUT_MS = 20_000L

        fun nowMs(): Long = System.nanoTime() / 1_000_000L
    }
}
