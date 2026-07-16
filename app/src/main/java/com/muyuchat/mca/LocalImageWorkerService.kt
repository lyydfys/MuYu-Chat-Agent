package com.muyuchat.mca

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class LocalImageWorkerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private lateinit var provider: LocalImageProvider

    @Volatile
    private var activeGeneration: ActiveGeneration? = null

    private val binder = object : ILocalImageWorker.Stub() {
        override fun begin(requestJson: String) {
            val runtime = LocalImageWorkerProtocol.parseBeginRuntime(requestJson)
            synchronized(stateLock) {
                check(activeGeneration == null) { "A local image generation is already running." }
                provider.begin(runtime)
            }
        }

        override fun cancel(requestJson: String): Boolean {
            val requestedId = runCatching {
                LocalImageWorkerProtocol.parseCancelRequestId(requestJson)
            }.getOrNull()
            val active = synchronized(stateLock) {
                activeGeneration?.takeIf { requestedId == null || it.requestId == requestedId }
            } ?: return false
            active.cancelRequested = true
            return provider.cancel()
        }

        override fun generate(requestJson: String, callback: ILocalImageWorkerCallback): Boolean {
            val request = runCatching {
                LocalImageWorkerProtocol.parseGenerateRequest(requestJson)
            }.getOrElse { error ->
                sendError(
                    callback = callback,
                    requestId = requestIdFromMalformedPayload(requestJson),
                    code = "invalid_request",
                    message = error.message ?: "Invalid local image generation request."
                )
                return false
            }

            val active = ActiveGeneration(request.requestId, callback)
            val accepted = synchronized(stateLock) {
                if (activeGeneration != null) {
                    false
                } else {
                    provider.begin(request.model.runtime)
                    activeGeneration = active
                    true
                }
            }
            if (!accepted) {
                sendError(
                    callback = callback,
                    requestId = request.requestId,
                    code = "worker_busy",
                    message = "Another local image generation is already running."
                )
                return false
            }

            active.deathRecipient = IBinder.DeathRecipient {
                cancelForDeadClient(active)
            }
            try {
                callback.asBinder().linkToDeath(requireNotNull(active.deathRecipient), 0)
            } catch (_: RemoteException) {
                active.cancelRequested = true
                runCatching { provider.cancel() }
                finish(active)
                return false
            }

            val job = scope.launch {
                var transferredOutput: File? = null
                try {
                    val startedDelivered = sendProgress(
                        callback,
                        request.requestId,
                        LocalImageProgress(
                            phase = "worker_started",
                            message = "Local image worker started.",
                            step = 0,
                            steps = 0,
                            elapsedMs = 0L,
                            secondsPerStep = 0.0,
                            threads = 0,
                            width = 0,
                            height = 0,
                            cancelRequested = false,
                            requestOptionsJson = request.options.toJson().toString()
                        )
                    )
                    if (!startedDelivered) {
                        cancelForDeadClient(active)
                    }
                    var waitingReported = false
                    val result = LocalImageExecutionGate.withLease(
                        context = applicationContext,
                        isCancelled = { active.cancelRequested },
                        onWaiting = {
                            if (!waitingReported) {
                                waitingReported = true
                                val delivered = sendProgress(
                                    callback,
                                    request.requestId,
                                    LocalImageProgress(
                                        phase = "waiting_for_native_lease",
                                        message = "Waiting for another native image task to finish.",
                                        step = 0,
                                        steps = 0,
                                        elapsedMs = 0L,
                                        secondsPerStep = 0.0,
                                        threads = 0,
                                        width = 0,
                                        height = 0,
                                        cancelRequested = false
                                    )
                                )
                                if (!delivered) cancelForDeadClient(active)
                            }
                        }
                    ) {
                        provider.generate(
                            model = request.model,
                            prompt = request.prompt,
                            options = request.options,
                            onProgress = { progress ->
                                val delivered = sendProgress(
                                    callback,
                                    request.requestId,
                                    active.withAccumulatedStages(progress)
                                )
                                if (!delivered) cancelForDeadClient(active)
                            }
                        )
                    }
                    coroutineContext.ensureActive()
                    val output = writeResultFile(request.requestId, result)
                    transferredOutput = output
                    val delivered = sendComplete(
                        callback = callback,
                        requestId = request.requestId,
                        outputFile = output,
                        mimeType = result.mimeType,
                        executionMetadataJson = result.executionMetadataJson
                    )
                    Log.i(
                        "MCA-LocalImage",
                        JSONObject()
                            .put("requestId", request.requestId)
                            .put("workerPid", Process.myPid())
                            .put("outputBytes", output.length())
                            .put("mimeType", result.mimeType)
                            .put(
                                "execution",
                                runCatching { JSONObject(result.executionMetadataJson) }.getOrElse { JSONObject() }
                            )
                            .toString()
                    )
                    if (delivered) {
                        transferredOutput = null
                    }
                } catch (_: CancellationException) {
                    // Client death and service teardown have no live callback to notify.
                } catch (error: Throwable) {
                    sendError(
                        callback = callback,
                        requestId = request.requestId,
                        code = "generation_failed",
                        message = error.message ?: "Local image generation failed."
                    )
                } finally {
                    transferredOutput?.delete()
                    finish(active)
                }
            }
            synchronized(stateLock) {
                if (activeGeneration === active) {
                    active.job = job
                } else {
                    job.cancel()
                }
            }
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        provider = LocalImageProvider(applicationContext)
        cleanupStaleResults()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        val active = synchronized(stateLock) {
            activeGeneration.also { activeGeneration = null }
        }
        if (active != null) {
            runCatching { provider.cancel() }
            active.job?.cancel()
            unlinkDeathRecipient(active)
        }
        scope.cancel()
        cleanupPartialResults()
        super.onDestroy()
    }

    private fun cancelForDeadClient(active: ActiveGeneration) {
        val isCurrent = synchronized(stateLock) { activeGeneration === active }
        if (!isCurrent) return
        active.cancelRequested = true
        runCatching { provider.cancel() }
        active.job?.cancel(CancellationException("Local image client disconnected."))
    }

    private fun finish(active: ActiveGeneration) {
        synchronized(stateLock) {
            if (activeGeneration === active) activeGeneration = null
        }
        unlinkDeathRecipient(active)
    }

    private fun unlinkDeathRecipient(active: ActiveGeneration) {
        val recipient = active.deathRecipient ?: return
        runCatching { active.callback.asBinder().unlinkToDeath(recipient, 0) }
    }

    private fun sendProgress(
        callback: ILocalImageWorkerCallback,
        requestId: String,
        progress: LocalImageProgress
    ): Boolean = runCatching {
        callback.onProgress(
            LocalImageWorkerProtocol.progress(
                requestId = requestId,
                workerPid = Process.myPid(),
                progress = progress
            )
        )
    }.isSuccess

    private fun sendComplete(
        callback: ILocalImageWorkerCallback,
        requestId: String,
        outputFile: File,
        mimeType: String,
        executionMetadataJson: String
    ): Boolean = runCatching {
        callback.onComplete(
            LocalImageWorkerProtocol.result(
                requestId = requestId,
                workerPid = Process.myPid(),
                outputPath = outputFile.canonicalPath,
                mimeType = mimeType,
                executionMetadataJson = executionMetadataJson
            )
        )
    }.isSuccess

    private fun sendError(
        callback: ILocalImageWorkerCallback,
        requestId: String,
        code: String,
        message: String
    ): Boolean = runCatching {
        callback.onError(
            LocalImageWorkerProtocol.error(
                requestId = requestId,
                workerPid = Process.myPid(),
                code = code,
                message = message
            )
        )
    }.isSuccess

    private fun writeResultFile(requestId: String, result: LocalImageResult): File {
        val outputDir = resultDirectory().apply { mkdirs() }
        cleanupStaleResults()
        val safeId = requestId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val extension = when (result.mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            else -> "png"
        }
        val baseName = "${safeId.ifBlank { "image" }}-${UUID.randomUUID()}"
        val partial = File(outputDir, "$baseName.$extension.part")
        val output = File(outputDir, "$baseName.$extension")
        partial.outputStream().use { it.write(result.bytes) }
        check(partial.renameTo(output)) { "Unable to publish local image worker output." }
        return output
    }

    private fun cleanupStaleResults() {
        val staleBefore = System.currentTimeMillis() - RESULT_MAX_AGE_MS
        resultDirectory().listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(".part") || file.lastModified() < staleBefore)) {
                runCatching { file.delete() }
            }
        }
    }

    private fun cleanupPartialResults() {
        resultDirectory().listFiles { file -> file.isFile && file.name.endsWith(".part") }
            ?.forEach { runCatching { it.delete() } }
    }

    private fun resultDirectory(): File = File(cacheDir, RESULT_DIRECTORY)

    private fun requestIdFromMalformedPayload(raw: String): String =
        runCatching { JSONObject(raw).optString("requestId") }.getOrDefault("")

    private class ActiveGeneration(
        val requestId: String,
        val callback: ILocalImageWorkerCallback
    ) {
        var deathRecipient: IBinder.DeathRecipient? = null
        var job: Job? = null

        @Volatile
        var cancelRequested: Boolean = false

        private var lastStageTrace: List<String> = emptyList()

        fun withAccumulatedStages(progress: LocalImageProgress): LocalImageProgress = synchronized(this) {
            lastStageTrace = accumulateNativeStageTrace(lastStageTrace, progress.stageTrace)
            if (progress.stageTrace == lastStageTrace) progress else progress.copy(stageTrace = lastStageTrace)
        }
    }

    companion object {
        internal const val RESULT_DIRECTORY = "local_image_worker_results"
        private const val RESULT_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
