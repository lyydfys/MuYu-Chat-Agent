package com.muyuchat.api.local

import com.muyuchat.core.engine.ChatRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

    internal suspend fun McaLoopbackServer.handleImageGeneration(socket: Socket, body: String, clientRequestId: String?) {
        val cancellationEpoch = synchronized(imageRequestLock) { imageRequestCancellationEpoch }
        val request = try {
            ImageGenerationApiContract.parseRequest(body)
        } catch (error: ImageGenerationContractException) {
            writeError(
                socket,
                "400 Bad Request",
                error.code,
                error.message
            )
            return
        }

        val busy = try {
            LocalApiRuntime.busyState()
        } catch (_: Throwable) {
            writeError(
                socket,
                "503 Service Unavailable",
                "image_control_plane_failed",
                "The local runtime could not report image generation availability."
            )
            return
        }
        if (busy.busy) {
            writeError(
                socket,
                "409 Conflict",
                "image_generation_busy",
                busy.message.ifBlank { "Another local runtime operation is in progress." },
                detailsJson = busy.detailsJson,
                retryAfterMs = busy.retryAfterMs.coerceAtLeast(0L)
            )
            return
        }

        val requestId = clientRequestId ?: "img-${UUID.randomUUID()}"
        val requestJob = requireNotNull(currentCoroutineContext()[Job]) {
            "Local API image generation requires a request coroutine."
        }
        if (!registerImageRequest(requestId, requestJob, cancellationEpoch)) {
            writeError(
                socket,
                "409 Conflict",
                "image_generation_cancelled",
                "Local image generation was cancelled before provider admission."
            )
            return
        }
        val providerResponse = try {
            withImageClientDisconnectCancellation(socket, requestId, requestJob) {
                LocalApiRuntime.generateImage(requestId, request.rawBody)
            }
        } catch (cancelled: CancellationException) {
            writeError(
                socket,
                "409 Conflict",
                "image_generation_cancelled",
                cancelled.message ?: "Local image generation was cancelled."
            )
            return
        } catch (error: ImageGenerationProviderException) {
            writeError(
                socket,
                error.httpStatus.toHttpStatus(),
                error.code,
                error.message,
                detailsJson = error.detailsJson,
                retryAfterMs = error.retryAfterMs
            )
            return
        } catch (error: Throwable) {
            val mapped = mapImageProviderFailure(error)
            writeError(
                socket,
                mapped.status.toHttpStatus(),
                mapped.code,
                mapped.message
            )
            return
        } finally {
            unregisterImageRequest(requestId, requestJob)
        }
        if (providerResponse == null) {
            writeError(
                socket,
                "503 Service Unavailable",
                "image_runtime_unavailable",
                "The local image runtime is not attached."
            )
            return
        }

        val response = try {
            ImageGenerationApiContract.parseResponse(requestId, request, providerResponse)
        } catch (error: ImageGenerationContractException) {
            writeError(
                socket,
                "502 Bad Gateway",
                error.code,
                error.message
            )
            return
        }
        writeJson(socket, response.rawBody, extraHeaders = mapOf("X-MCA-Request-Id" to requestId))
    }

    internal fun McaLoopbackServer.validatedClientRequestId(headers: Map<String, String>): String? {
        val value = headers["x-mca-request-id"] ?: return null
        if (!REQUEST_ID_PATTERN.matches(value)) {
            throw HttpRequestRejected(
                "400 Bad Request", "invalid_request_id",
                "X-MCA-Request-Id must contain 1 to 128 safe ASCII identifier characters."
            )
        }
        synchronized(clientRequestIdLock) {
            if (!usedClientRequestIds.add(value)) {
                throw HttpRequestRejected(
                    "409 Conflict", "request_id_conflict",
                    "X-MCA-Request-Id has already been used by this Local API listener."
                )
            }
        }
        return value
    }

    /**
     * The server owns one coroutine per authenticated image request. Explicit stop and server
     * shutdown cancel that exact coroutine; MainViewModel then forwards cancellation to the
     * native image worker and releases its coordinator lease in the provider's single finally.
     */
    private fun McaLoopbackServer.registerImageRequest(
        requestId: String,
        requestJob: Job,
        cancellationEpoch: Long
    ): Boolean = synchronized(imageRequestLock) {
        if (imageRequestAdmissionPauseCount > 0 || cancellationEpoch != imageRequestCancellationEpoch) {
            false
        } else {
            if (requestId in activeImageRequestJobs) return@synchronized false
            activeImageRequestJobs[requestId] = requestJob
            true
        }
    }

    private fun McaLoopbackServer.unregisterImageRequest(requestId: String, requestJob: Job) {
        synchronized(imageRequestLock) {
            if (activeImageRequestJobs[requestId] === requestJob) {
                activeImageRequestJobs.remove(requestId)
            }
        }
    }

    private fun McaLoopbackServer.cancelImageRequest(requestId: String, requestJob: Job, reason: String) {
        val ownsRequest = synchronized(imageRequestLock) {
            activeImageRequestJobs[requestId] === requestJob
        }
        if (ownsRequest) requestJob.cancel(CancellationException(reason))
    }

    internal fun McaLoopbackServer.cancelActiveImageRequests(reason: String): List<Job> {
        val requests = synchronized(imageRequestLock) {
            imageRequestCancellationEpoch += 1L
            imageRequestAdmissionPauseCount += 1
            activeImageRequestJobs.values.toList()
        }
        requests.forEach { requestJob -> requestJob.cancel(CancellationException(reason)) }
        return requests
    }

    /**
     * A reset/IO failure after the request body has been consumed means the response peer is gone.
     * Plain EOF is intentionally not treated as cancellation: an HTTP client may half-close its
     * request output while continuing to read the response (the JVM contract tests do this).
     */
    private suspend fun <T> McaLoopbackServer.withImageClientDisconnectCancellation(
        socket: Socket,
        requestId: String,
        requestJob: Job,
        block: suspend () -> T
    ): T = coroutineScope {
        val completed = AtomicBoolean(false)
        val input = socket.getInputStream()
        val disconnectMonitor = launch {
            while (isActive && !completed.get()) {
                try {
                    if (input.read() < 0) return@launch
                } catch (_: SocketTimeoutException) {
                    // Keep polling so cancellation of a long native image request remains visible.
                } catch (error: IOException) {
                    if (!completed.get()) {
                        cancelImageRequest(
                            requestId = requestId,
                            requestJob = requestJob,
                            reason = "Local API image client disconnected: ${error.javaClass.simpleName}."
                        )
                    }
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
                        cancelImageRequest(
                            requestId = requestId,
                            requestJob = requestJob,
                            reason = "Local API image client disconnected: ${error.javaClass.simpleName}."
                        )
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
                disconnectMonitor.cancelAndJoin()
                peerProbe.cancelAndJoin()
            }
        }
    }

    private data class MappedImageProviderFailure(
        val status: Int,
        val code: String,
        val message: String
    )

    /** Converts legacy/unstructured provider failures into actionable API errors. */
    private fun mapImageProviderFailure(error: Throwable): MappedImageProviderFailure {
        val message = error.message.orEmpty().ifBlank { "Local image generation failed." }
        val normalized = message.lowercase()
        return when {
            normalized.contains("no configured local image model") ||
                normalized.contains("no local image model") ->
                MappedImageProviderFailure(
                    503,
                    "image_runtime_unavailable",
                    "No configured local image model is selected. Load a complete image model in the image page, then retry."
                )
            normalized.contains("generation in progress") ||
                normalized.contains("image_generation_busy") ||
                normalized.contains("another local image") ->
                MappedImageProviderFailure(409, "image_generation_busy", message)
            normalized.contains("contextbinary is missing") ||
                normalized.contains("bundle is missing") ||
                normalized.contains("model bundle") && normalized.contains("missing") ->
                MappedImageProviderFailure(
                    409,
                    "image_model_not_ready",
                    "$message Re-download or re-import the complete model bundle, then retry."
                )
            normalized.contains("timeout") || normalized.contains("timed out") ->
                MappedImageProviderFailure(504, "image_generation_timeout", message)
            normalized.contains("worker") && normalized.contains("unavailable") ->
                MappedImageProviderFailure(503, "image_worker_unavailable", message)
            else -> MappedImageProviderFailure(500, "image_generation_failed", message)
        }
    }

    internal fun ChatRequest.localApiVisionRejection(): LocalApiVisionRejection? {
        if (!hasImageAttachments()) return null
        val capability = currentLocalApiVisionCapability()
        if (!capability.runtime.isMnnRuntimeToken()) return null
        if (!capability.visionReady) {
            return LocalApiVisionRejection(
                code = "mnn_vision_not_ready",
                message = "本地 MNN 模型「${capability.displayName}」没有就绪的视觉组件，图片请求未下发。" +
                    "请加载包含可读 visual.mnn 的完整多模态包后重试。"
            )
        }
        return null
    }

    private fun ChatRequest.hasImageAttachments(): Boolean =
        messages.any { message ->
            message.imageAttachments.any { attachment ->
                attachment.hasInlineData || attachment.uriString.isNotBlank()
            }
        }

    private fun currentLocalApiVisionCapability(): LocalApiVisionCapability {
        val loaded = LocalApiRuntime.loadedModelJsonProvider().toJsonObject() ?: JSONObject()
        val modelId = loaded.optString("id")
        val catalogEntry = currentCatalogEntry(modelId)
        val nativeStats = LocalApiRuntime.engine?.nativeStatsJson().toJsonObject()
        val records = listOfNotNull(loaded, nativeStats, catalogEntry)
        val runtime = records.firstNotNullOfOrNull { it.runtimeToken().takeIf(String::isNotBlank) }.orEmpty()
        val displayName = listOf(
            loaded.firstNonBlank("displayName", "display_name", "name"),
            catalogEntry?.firstNonBlank("displayName", "display_name", "name").orEmpty(),
            modelId
        ).firstOrNull { it.isNotBlank() }.orEmpty().ifBlank { "当前 MNN 模型" }
        return LocalApiVisionCapability(
            displayName = displayName,
            runtime = runtime,
            visionReady = records.firstNotNullOfOrNull { it.visionReadyFlag() } ?: false
        )
    }

    private fun currentCatalogEntry(modelId: String): JSONObject? {
        if (modelId.isBlank()) return null
        val root = LocalApiRuntime.modelsJsonProvider().toJsonObject() ?: return null
        val data = root.optJSONArray("data") ?: return null
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            if (item.optString("id") == modelId) return item
        }
        return null
    }

    private fun String?.toJsonObject(): JSONObject? =
        runCatching { JSONObject(orEmpty()) }.getOrNull()

    private fun JSONObject.firstNonBlank(vararg keys: String): String =
        keys.asSequence().map(::optString).firstOrNull { it.isNotBlank() }.orEmpty()

    private fun JSONObject.runtimeToken(): String =
        firstNonBlank("runtime", "backend").ifBlank {
            optJSONObject("stats")?.firstNonBlank("runtime", "backend").orEmpty()
        }

    private fun JSONObject.visionReadyFlag(): Boolean? =
        firstBoolean("vision_ready", "visionReady", "supports_vision", "supportsVision")
            ?: optJSONObject("stats")?.visionReadyFlag()
            ?: optJSONObject("capabilities")?.firstBoolean("supportsVision", "supports_vision")

    private data class LocalApiVisionCapability(
        val displayName: String,
        val runtime: String,
        val visionReady: Boolean
    )

    internal data class LocalApiVisionRejection(
        val code: String,
        val message: String
    )

    private fun String.isMnnRuntimeToken(): Boolean {
        val normalized = trim().lowercase()
        return normalized == "mnn" ||
            normalized == "mnn_cpu" ||
            normalized == "mnn_llm" ||
            normalized == "mnn-llm" ||
            normalized.startsWith("mnn_") ||
            normalized.startsWith("mnn-")
    }

        internal val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
