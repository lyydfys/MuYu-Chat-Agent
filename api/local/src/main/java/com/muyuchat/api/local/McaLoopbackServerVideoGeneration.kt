package com.muyuchat.api.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Authenticated `POST /v1/video` transport handler.
 *
 * The handler owns HTTP only: it parses early for a fast 4xx, delegates the run to the app-owned
 * provider (which is the single video owner's only API entry), and maps the provider's structured
 * failures onto the same error envelope the other Local API routes use. It never starts a second
 * owner, worker or state writer, and it never fabricates progress.
 *
 * The request is synchronous, like `POST /v1/images/generations`: the call returns once the run
 * reaches a terminal state. A client that disconnects while waiting cancels that exact run through
 * the owner's existing `requestCancel` path so a dropped curl cannot leave native work stranded.
 */
internal suspend fun McaLoopbackServer.handleVideoGeneration(
    socket: Socket,
    body: String,
    clientRequestId: String?,
) {
    val request = try {
        VideoGenerationApiContract.parseRequest(body)
    } catch (error: VideoGenerationContractException) {
        writeError(socket, error.httpStatus.toHttpStatus(), error.code, error.message)
        return
    }

    // The video owner and the image owner share the same native executive. Reporting a busy
    // runtime up front is the same negotiated answer the image route gives; it is not a device
    // admission decision and never depends on hardware identity.
    val busy = try {
        LocalApiRuntime.busyState()
    } catch (_: Throwable) {
        writeError(
            socket,
            "503 Service Unavailable",
            "video_control_plane_failed",
            "The local runtime could not report video generation availability.",
        )
        return
    }
    if (busy.busy) {
        writeError(
            socket,
            "409 Conflict",
            "video_runtime_busy",
            busy.message.ifBlank { "Another local runtime operation is in progress." },
            detailsJson = busy.detailsJson,
            retryAfterMs = busy.retryAfterMs.coerceAtLeast(0L),
        )
        return
    }

    val requestId = clientRequestId ?: "vid-${UUID.randomUUID()}"
    val providerResponse = try {
        withVideoClientDisconnectCancellation(socket, requestId) {
            LocalApiRuntime.generateVideo(requestId, request.rawBody)
        }
    } catch (cancelled: CancellationException) {
        writeError(
            socket,
            "409 Conflict",
            "video_generation_cancelled",
            cancelled.message ?: "Local video generation was cancelled.",
        )
        return
    } catch (error: VideoGenerationProviderException) {
        writeError(
            socket,
            error.httpStatus.toHttpStatus(),
            error.code,
            error.message,
            detailsJson = error.detailsJson,
            retryAfterMs = error.retryAfterMs,
        )
        return
    } catch (error: Throwable) {
        writeError(
            socket,
            "500 Internal Server Error",
            "video_generation_failed",
            error.message ?: "Local video generation failed.",
        )
        return
    }
    if (providerResponse == null) {
        writeError(
            socket,
            "503 Service Unavailable",
            "video_runtime_unavailable",
            "The local video runtime is not attached.",
        )
        return
    }
    writeJson(socket, providerResponse, extraHeaders = mapOf("X-MCA-Request-Id" to requestId))
}

/**
 * Detects a vanished client while a long native run is in flight and forwards exactly one stop to
 * the owner. A plain EOF after the request body is NOT a disconnect (HTTP clients may half-close
 * their write side and keep reading), matching the image route's rule.
 */
private suspend fun <T> McaLoopbackServer.withVideoClientDisconnectCancellation(
    socket: Socket,
    requestId: String,
    block: suspend () -> T,
): T = coroutineScope {
    val completed = AtomicBoolean(false)
    val input = socket.getInputStream()
    val disconnectMonitor = launch {
        while (isActive && !completed.get()) {
            try {
                if (input.read() < 0) return@launch
            } catch (_: SocketTimeoutException) {
                // Keep polling so a dropped peer stays visible during a long native run.
            } catch (_: IOException) {
                if (!completed.get()) runCatching { LocalApiRuntime.stopVideo(requestId) }
                return@launch
            }
        }
    }
    val peerProbe = launch {
        while (isActive && !completed.get()) {
            delay(250L)
            if (completed.get()) return@launch
            try {
                socket.sendUrgentData(0)
            } catch (error: IOException) {
                if (error.isPeerDisconnect()) {
                    if (!completed.get()) runCatching { LocalApiRuntime.stopVideo(requestId) }
                    return@launch
                }
            }
        }
    }
    try {
        block()
    } finally {
        completed.set(true)
        runCatching { if (!socket.isInputShutdown) socket.shutdownInput() }
        withContext(NonCancellable) {
            disconnectMonitor.cancel()
            peerProbe.cancel()
        }
    }
}
