package com.muyuchat.mca

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.system.exitProcess
import org.json.JSONObject

internal const val LOCAL_IMAGE_GENERATION_CANCELLED_CODE = "image_generation_cancelled"

private val LOCAL_IMAGE_SAFE_LOG_COUNT_FIELDS = listOf(
    "nativeGenerationSequence",
    "nativeStageMask",
    "nativeDetailStageMask",
    "unetExecutionCount",
    "controlNetExecutionCount",
    "controlImageExecutionCount",
    "actualDiffusionModelComputeCount",
    "actualPositiveDiffusionModelComputeCount",
    "actualNegativeDiffusionModelComputeCount",
    "actualAuxiliaryDiffusionModelComputeCount",
    "physicalComputeCount",
    "physicalComputeSuccessCount",
    "physicalTileComputeCount",
    "physicalTileComputeSuccessCount"
)

private val LOCAL_IMAGE_SAFE_LOG_BOOLEAN_FIELDS = listOf(
    "tiledExecution",
    "executionCompleted"
)

private val LOCAL_IMAGE_SAFE_LOG_UINT64_HEX_FIELDS = listOf(
    QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD,
    "encoderNativeDetailStageMaskHex",
    "unetNativeDetailStageMaskHex",
    "vaeNativeDetailStageMaskHex"
)

private val LOCAL_IMAGE_UINT64_HEX_PATTERN = Regex("^[0-9a-f]{16}$")

private val LOCAL_IMAGE_SAFE_LOG_OPERATIONS = setOf(
    "IMAGE_GENERATION",
    "ESRGAN_UPSCALE"
)

internal fun localImageWorkerCompletionLogSummary(
    requestId: String,
    workerPid: Int,
    operation: String,
    runtime: LocalImageRuntime,
    outputCount: Int,
    outputBytes: Long,
    executionMetadataJson: String
): String {
    val summary = JSONObject()
        .put("requestId", requestId)
        .put("workerPid", workerPid)
        .put("operation", operation.takeIf(LOCAL_IMAGE_SAFE_LOG_OPERATIONS::contains) ?: "UNKNOWN")
        .put("runtime", runtime.name)
        .put("outputCount", outputCount.coerceAtLeast(0))
        .put("outputBytes", outputBytes.coerceAtLeast(0L))
    val metadata = runCatching { JSONObject(executionMetadataJson) }.getOrNull()
        ?: return summary.toString()
    val sources = listOfNotNull(metadata.optJSONObject("nativeEffective"), metadata)

    LOCAL_IMAGE_SAFE_LOG_COUNT_FIELDS.forEach { field ->
        var safeValue: Long? = null
        for (source in sources) {
            val raw = source.opt(field) as? Number ?: continue
            val doubleValue = raw.toDouble()
            val longValue = raw.toLong()
            if (doubleValue.isFinite() && doubleValue == longValue.toDouble() && longValue >= 0L) {
                safeValue = longValue
                break
            }
        }
        safeValue?.let { summary.put(field, it) }
    }
    LOCAL_IMAGE_SAFE_LOG_BOOLEAN_FIELDS.forEach { field ->
        val safeValue = sources.firstNotNullOfOrNull { source -> source.opt(field) as? Boolean }
        safeValue?.let { summary.put(field, it) }
    }
    LOCAL_IMAGE_SAFE_LOG_UINT64_HEX_FIELDS.forEach { field ->
        val safeValue = sources.firstNotNullOfOrNull { source ->
            (source.opt(field) as? String)?.takeIf(LOCAL_IMAGE_UINT64_HEX_PATTERN::matches)
        }
        safeValue?.let { summary.put(field, it) }
    }
    return summary.toString()
}

private fun MutableCollection<File>.deleteWorkerOutputs() {
    forEach { file -> runCatching { file.delete() } }
    clear()
}

private fun logLocalImageWorkerInternalFailure(action: String, error: Throwable) {
    val safeAction = action.filter { it.isLetterOrDigit() || it == '_' }.take(48)
        .ifBlank { "unknown" }
    val safeType = error.javaClass.simpleName
        .filter { it.isLetterOrDigit() || it == '_' }
        .take(64)
        .ifBlank { "Throwable" }
    Log.w("MCA-LocalImage", "internal_failure action=$safeAction type=$safeType")
}

class LocalImageWorkerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private lateinit var provider: LocalImageProvider
    private lateinit var executionJournal: ImageExecutionJournalStore
    private lateinit var workerInputStore: LocalImageWorkerInputStore
    private var rejectedRetiringProcess = false

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
            if (!active.tryRequestCancellation()) return false
            scheduleSelfExit(active)
            requestJournalCancellation(active.requestId)
            runCatching { provider.cancel() }
            active.job?.cancel(CancellationException("Local image generation was cancelled."))
            publishCancellationTerminal(active, "Local image generation was cancelled.")
            return true
        }

        override fun cancelAndExit(requestJson: String): Boolean {
            val requestedId = runCatching {
                LocalImageWorkerProtocol.parseCancelRequestId(requestJson)
            }.getOrNull() ?: return false
            val active = synchronized(stateLock) {
                activeGeneration?.takeIf { it.requestId == requestedId }
            } ?: return false
            scheduleSelfExit(active)
            if (active.tryRequestCancellation()) {
                requestJournalCancellation(active.requestId)
                runCatching { provider.cancel() }
                active.job?.cancel(CancellationException("Local image generation timed out."))
                publishCancellationTerminal(active, "Local image generation timed out.")
            }
            return true
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
            if (!promoteActiveOperationToForeground("IMAGE_GENERATION")) {
                runCatching { provider.cancel() }
                finish(active)
                sendError(
                    callback = callback,
                    requestId = request.requestId,
                    code = "foreground_start_failed",
                    message = "Android did not allow the local image worker to enter foreground execution."
                )
                return false
            }

            val effectiveOptions = if (request.options.seed == null || request.options.seed == -1) {
                request.options.copy(
                    seed = (System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt()
                )
            } else {
                request.options
            }
            active.deathRecipient = IBinder.DeathRecipient {
                cancelForDeadClient(active)
            }
            try {
                callback.asBinder().linkToDeath(requireNotNull(active.deathRecipient), 0)
            } catch (_: RemoteException) {
                active.tryRequestCancellation()
                runCatching { provider.cancel() }
                finish(active)
                return false
            }

            val job = scope.launch {
                val transferredOutputs = mutableListOf<File>()
                var workerInputs: LocalImageWorkerInputs? = null
                try {
                    workerInputs = workerInputStore.materialize(request.requestId, effectiveOptions)
                    val workerOptions = workerInputs.options
                    prepareExecutionJournal(request, workerOptions, workerInputs.directory)
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
                            requestOptionsJson = workerOptions.inputAuditJson().toString()
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
                            options = workerOptions,
                            onProgress = { progress ->
                                updateJournalFromProgress(request.requestId, progress)
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
                    advanceJournalTo(request.requestId, ImageExecutionPhase.PUBLISHING)
                    updateJournalFromNativeResult(request.requestId, result.executionMetadataJson)
                    val publishedOutputs = result.outputs.map { generated ->
                        val target = prepareResultTarget(
                            requestId = request.requestId,
                            mimeType = generated.mimeType,
                            index = generated.index
                        )
                        updateJournalOutputPath(request.requestId, target.partial)
                        val output = writeResultFile(target, generated.bytes)
                        transferredOutputs += output
                        updateJournalOutputPath(request.requestId, output)
                        updateJournalOutputArtifact(
                            requestId = request.requestId,
                            output = output,
                            index = generated.index,
                            mimeType = generated.mimeType,
                            seed = generated.seed
                        )
                        generated to output
                    }
                    val outputBytes = transferredOutputs.sumOf(File::length)
                    if (!active.tryBeginResultPublication()) {
                        throw CancellationException("Image generation was cancelled before result publication.")
                    }
                    val delivered = sendComplete(
                        callback = callback,
                        requestId = request.requestId,
                        outputs = publishedOutputs.map { (generated, output) ->
                            LocalImageWorkerProtocol.OutputEnvelope(
                                index = generated.index,
                                outputPath = output.canonicalPath,
                                mimeType = generated.mimeType,
                                seed = generated.seed
                            )
                        },
                        executionMetadataJson = result.executionMetadataJson
                    )
                    try {
                        if (delivered) {
                            markJournalTerminal(request.requestId, ImageExecutionPhase.COMPLETED)
                            Log.i(
                                "MCA-LocalImage",
                                localImageWorkerCompletionLogSummary(
                                    requestId = request.requestId,
                                    workerPid = Process.myPid(),
                                    operation = "IMAGE_GENERATION",
                                    runtime = request.model.runtime,
                                    outputCount = transferredOutputs.size,
                                    outputBytes = outputBytes,
                                    executionMetadataJson = result.executionMetadataJson
                                )
                            )
                            transferredOutputs.clear()
                        } else {
                            transferredOutputs.deleteWorkerOutputs()
                            markJournalTerminal(
                                request.requestId,
                                ImageExecutionPhase.FAILED,
                                errorCode = "RESULT_DELIVERY_FAILED",
                                errorMessage = "The generated image could not be delivered to the client."
                            )
                        }
                    } finally {
                        active.markResultPublicationTerminalAttemptFinished()
                    }
                } catch (error: CancellationException) {
                    val message = error.message ?: "The image client or worker was cancelled."
                    finishJournalCancelled(request.requestId, message)
                    publishCancellationTerminal(active, message)
                    // Client death and service teardown have no live callback to notify.
                } catch (error: Throwable) {
                    val errorCode = localImageWorkerErrorCode(error)
                    val message = error.message ?: "Local image generation failed."
                    if (active.tryBeginErrorPublication()) {
                        transferredOutputs.deleteWorkerOutputs()
                        markJournalTerminal(
                            request.requestId,
                            ImageExecutionPhase.FAILED,
                            errorCode = errorCode.uppercase(),
                            errorMessage = message
                        )
                        sendError(
                            callback = callback,
                            requestId = request.requestId,
                            code = errorCode,
                            message = message
                        )
                    } else if (active.cancelRequested) {
                        finishJournalCancelled(request.requestId, message)
                        publishCancellationTerminal(active, message)
                    }
                } finally {
                    transferredOutputs.forEach { it.delete() }
                    workerInputStore.cleanup(workerInputs?.directory)
                    finish(active)
                }
            }
            synchronized(stateLock) {
                if (activeGeneration === active) {
                    active.job = job
                    if (active.cancelRequested) {
                        job.cancel(CancellationException("Local image generation was cancelled."))
                    }
                } else {
                    job.cancel()
                }
            }
            return true
        }

        override fun upscale(requestJson: String, callback: ILocalImageWorkerCallback): Boolean =
            acceptUpscale(requestJson, callback)
    }

    private fun acceptUpscale(
        requestJson: String,
        callback: ILocalImageWorkerCallback
    ): Boolean {
        val request = runCatching {
            LocalImageWorkerProtocol.parseUpscaleRequest(requestJson)
        }.getOrElse { error ->
            sendError(
                callback = callback,
                requestId = requestIdFromMalformedPayload(requestJson),
                code = "invalid_request",
                message = error.message ?: "Invalid local image upscale request."
            )
            return false
        }
        val active = ActiveGeneration(request.requestId, callback)
        val accepted = synchronized(stateLock) {
            if (activeGeneration != null) {
                false
            } else {
                provider.begin(LocalImageRuntime.STABLE_DIFFUSION_CPP)
                activeGeneration = active
                true
            }
        }
        if (!accepted) {
            sendError(
                callback = callback,
                requestId = request.requestId,
                code = "worker_busy",
                message = "Another local image operation is already running."
            )
            return false
        }
        if (!promoteActiveOperationToForeground("ESRGAN_UPSCALE")) {
            runCatching { provider.cancel() }
            finish(active)
            sendError(
                callback = callback,
                requestId = request.requestId,
                code = "foreground_start_failed",
                message = "Android did not allow the local image worker to enter foreground execution."
            )
            return false
        }
        active.deathRecipient = IBinder.DeathRecipient { cancelForDeadClient(active) }
        try {
            callback.asBinder().linkToDeath(requireNotNull(active.deathRecipient), 0)
        } catch (_: RemoteException) {
            active.tryRequestCancellation()
            runCatching { provider.cancel() }
            finish(active)
            return false
        }

        val job = scope.launch {
            var transferredOutput: File? = null
            var workerInputs: LocalImageWorkerUpscaleInputs? = null
            try {
                val preparingDelivered = sendProgress(
                    callback,
                    request.requestId,
                    LocalImageProgress(
                        phase = "worker_preparing",
                        message = "Verifying the local ESRGAN inputs and model lease.",
                        step = 0,
                        steps = 1,
                        elapsedMs = 0L,
                        secondsPerStep = 0.0,
                        threads = request.threads,
                        width = request.input.width,
                        height = request.input.height,
                        cancelRequested = false
                    )
                )
                if (!preparingDelivered) cancelForDeadClient(active)
                coroutineContext.ensureActive()
                val preparedInputs = workerInputStore.materializeUpscale(
                    requestId = request.requestId,
                    input = request.input,
                    upscaler = request.upscaler,
                    isCancelled = {
                        active.cancelRequested || coroutineContext[Job]?.isActive != true
                    }
                )
                workerInputs = preparedInputs
                coroutineContext.ensureActive()
                prepareUpscaleExecutionJournal(request, preparedInputs)
                val startedDelivered = sendProgress(
                    callback,
                    request.requestId,
                    LocalImageProgress(
                        phase = "worker_started",
                        message = "Local ESRGAN worker started.",
                        step = 0,
                        steps = 1,
                        elapsedMs = 0L,
                        secondsPerStep = 0.0,
                        threads = request.threads,
                        width = preparedInputs.input.width,
                        height = preparedInputs.input.height,
                        cancelRequested = false,
                        requestOptionsJson = JSONObject()
                            .put("operation", "ESRGAN_UPSCALE")
                            .put("targetScale", request.targetScale)
                            .put("tileSize", request.tileSize)
                            .put("threads", request.threads)
                            .put("input", preparedInputs.input.toJson(includePath = false))
                            .put("upscaler", preparedInputs.upscaler.toJson(includePath = false))
                            .toString()
                    )
                )
                if (!startedDelivered) cancelForDeadClient(active)
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
                                    steps = 1,
                                    elapsedMs = 0L,
                                    secondsPerStep = 0.0,
                                    threads = request.threads,
                                    width = preparedInputs.input.width,
                                    height = preparedInputs.input.height,
                                    cancelRequested = false
                                )
                            )
                            if (!delivered) cancelForDeadClient(active)
                        }
                    }
                ) {
                    provider.upscale(
                        input = preparedInputs.input,
                        upscaler = preparedInputs.upscaler,
                        targetScale = request.targetScale,
                        tileSize = request.tileSize,
                        threads = request.threads,
                        onProgress = { progress ->
                            updateJournalFromProgress(request.requestId, progress)
                            val delivered = sendProgress(
                                callback,
                                request.requestId,
                                active.withAccumulatedStages(progress)
                            )
                            if (!delivered) cancelForDeadClient(active)
                        }
                    )
                }
                preparedInputs.close()
                coroutineContext.ensureActive()
                advanceJournalTo(request.requestId, ImageExecutionPhase.PUBLISHING, observedStep = 1)
                updateJournalFromNativeResult(request.requestId, result.executionMetadataJson)
                val output = result.outputs.single()
                val target = prepareResultTarget(
                    requestId = request.requestId,
                    mimeType = output.mimeType,
                    index = 0
                )
                updateJournalOutputPath(request.requestId, target.partial)
                transferredOutput = writeResultFile(target, output.bytes)
                updateJournalOutputPath(request.requestId, requireNotNull(transferredOutput))
                updateJournalOutputArtifact(
                    requestId = request.requestId,
                    output = requireNotNull(transferredOutput),
                    index = 0,
                    mimeType = output.mimeType,
                    seed = output.seed
                )
                if (!active.tryBeginResultPublication()) {
                    throw CancellationException("Image upscale was cancelled before result publication.")
                }
                val delivered = sendComplete(
                    callback = callback,
                    requestId = request.requestId,
                    outputs = listOf(
                        LocalImageWorkerProtocol.OutputEnvelope(
                            index = 0,
                            outputPath = requireNotNull(transferredOutput).canonicalPath,
                            mimeType = output.mimeType,
                            seed = null
                        )
                    ),
                    executionMetadataJson = result.executionMetadataJson
                )
                try {
                    if (delivered) {
                        markJournalTerminal(request.requestId, ImageExecutionPhase.COMPLETED)
                        Log.i(
                            "MCA-LocalImage",
                            localImageWorkerCompletionLogSummary(
                                requestId = request.requestId,
                                workerPid = Process.myPid(),
                                operation = "ESRGAN_UPSCALE",
                                runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
                                outputCount = 1,
                                outputBytes = transferredOutput.length(),
                                executionMetadataJson = result.executionMetadataJson
                            )
                        )
                        transferredOutput = null
                    } else {
                        transferredOutput.delete()
                        transferredOutput = null
                        markJournalTerminal(
                            request.requestId,
                            ImageExecutionPhase.FAILED,
                            errorCode = "RESULT_DELIVERY_FAILED",
                            errorMessage = "The upscaled image could not be delivered to the client."
                        )
                    }
                } finally {
                    active.markResultPublicationTerminalAttemptFinished()
                }
            } catch (error: CancellationException) {
                val message = error.message ?: "The image upscale was cancelled."
                finishJournalCancelled(request.requestId, message)
                publishCancellationTerminal(active, message)
            } catch (error: Throwable) {
                val errorCode = localImageWorkerErrorCode(error)
                val message = error.message ?: "Local image upscale failed."
                if (active.tryBeginErrorPublication()) {
                    transferredOutput?.delete()
                    transferredOutput = null
                    markJournalTerminal(
                        request.requestId,
                        ImageExecutionPhase.FAILED,
                        errorCode = errorCode.uppercase(),
                        errorMessage = message
                    )
                    sendError(callback, request.requestId, errorCode, message)
                } else if (active.cancelRequested) {
                    finishJournalCancelled(request.requestId, message)
                    publishCancellationTerminal(active, message)
                }
            } finally {
                transferredOutput?.delete()
                workerInputs?.close()
                workerInputStore.cleanup(workerInputs?.directory)
                finish(active)
            }
        }
        synchronized(stateLock) {
            if (activeGeneration === active) {
                active.job = job
                if (active.cancelRequested) {
                    job.cancel(CancellationException("Local image upscale was cancelled."))
                }
            } else {
                job.cancel()
            }
        }
        return true
    }

    override fun onCreate() {
        super.onCreate()
        ensureForegroundChannel()
        if (PROCESS_RETIREMENT.isRetiring) {
            rejectedRetiringProcess = true
            return
        }
        provider = LocalImageProvider(applicationContext)
        workerInputStore = LocalImageWorkerInputStore(applicationContext)
        // Encoder phases can still consume the worker-owned source image after the parent process
        // dies. Acquire every abandoned split request lock exclusively before deleting either the
        // handoff artifacts or worker_inputs; numeric PIDs are not process identity.
        SdxlTwoPhaseRequestLease.recoverAbandonedRequests(
            artifactRoot = File(cacheDir, SDXL_TWO_PHASE_DIRECTORY),
            coordinationRoot = File(
                noBackupFilesDir,
                SDXL_TWO_PHASE_COORDINATION_DIRECTORY
            )
        )
        runCatching {
            QnnInputImageArtifact.cleanupStaleSharedArtifacts(cacheDir)
        }.onFailure { error ->
            logLocalImageWorkerInternalFailure("cleanup_stale_shared_qnn_artifacts", error)
        }
        workerInputStore.clearOrphanedWorkerInputs()
        executionJournal = ImageExecutionJournalStore(
            File(filesDir, IMAGE_EXECUTION_JOURNAL_DIRECTORY)
        )
        recoverInterruptedExecutions()
        cleanupStaleResults()
    }

    override fun onBind(intent: Intent?): IBinder? =
        binder.takeUnless { rejectedRetiringProcess || PROCESS_RETIREMENT.isRetiring }

    override fun onDestroy() {
        leaveActiveOperationForeground()
        if (rejectedRetiringProcess) {
            scope.cancel()
            super.onDestroy()
            return
        }
        PROCESS_RETIREMENT.beginRetirement()
        val active = synchronized(stateLock) {
            activeGeneration.also { activeGeneration = null }
        }
        val awaitResultPublication = active?.resultPublicationStarted == true
        if (awaitResultPublication) {
            scheduleResultPublicationSelfExit(requireNotNull(active))
        } else {
            scheduleUnconditionalSelfExit()
        }
        if (active != null) {
            if (active.tryRequestCancellation()) {
                finishJournalCancelled(active.requestId, "The image worker service stopped.")
                runCatching { provider.cancel() }
                active.job?.cancel()
            }
            unlinkDeathRecipient(active)
        }
        scope.cancel()
        cleanupPartialResults()
        super.onDestroy()
        if (awaitResultPublication) return
        // This is the exact disposable :local_image Linux process, not a historical callback PID.
        // Retire it synchronously so Android cannot construct a new service instance beside a
        // still-blocked native call from the destroyed instance.
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    private fun cancelForDeadClient(active: ActiveGeneration) {
        val isCurrent = synchronized(stateLock) { activeGeneration === active }
        if (!isCurrent) return
        if (!active.tryRequestCancellation()) return
        scheduleSelfExit(active)
        requestJournalCancellation(active.requestId)
        runCatching { provider.cancel() }
        active.job?.cancel(CancellationException("Local image client disconnected."))
    }

    private fun scheduleSelfExit(active: ActiveGeneration) {
        Thread(
            {
                runCatching { Thread.sleep(SELF_EXIT_GRACE_MS) }
                val stillOwnsTimedOutRequest = synchronized(stateLock) {
                    activeGeneration === active
                }
                if (stillOwnsTimedOutRequest) {
                    // This code executes inside the exact disposable worker instance. Killing self
                    // cannot target a PID that Android has already reassigned to another process.
                    Process.killProcess(Process.myPid())
                    exitProcess(0)
                }
            },
            "mca-local-image-self-exit"
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun scheduleUnconditionalSelfExit() {
        Thread(
            {
                runCatching { Thread.sleep(SELF_EXIT_GRACE_MS) }
                Process.killProcess(Process.myPid())
                exitProcess(0)
            },
            "mca-local-image-retire"
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun scheduleResultPublicationSelfExit(active: ActiveGeneration) {
        Thread(
            {
                runCatching {
                    active.awaitResultPublicationTerminalAttempt(
                        RESULT_PUBLICATION_EXIT_TIMEOUT_MS
                    )
                }
                Process.killProcess(Process.myPid())
                exitProcess(0)
            },
            "mca-local-image-publication-exit"
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun finish(active: ActiveGeneration) {
        synchronized(stateLock) {
            if (activeGeneration === active) {
                activeGeneration = null
                leaveActiveOperationForeground()
            }
        }
        unlinkDeathRecipient(active)
    }

    private fun promoteActiveOperationToForeground(operation: String): Boolean {
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mca_api)
            .setContentTitle("MCA 本地图像任务")
            .setContentText("正在执行 $operation，返回应用可查看进度")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return runCatching {
            ServiceCompat.startForeground(
                this,
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            true
        }.onFailure { error ->
            logLocalImageWorkerInternalFailure("start_foreground", error)
        }.getOrDefault(false)
    }

    private fun leaveActiveOperationForeground() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private fun ensureForegroundChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "本地图像任务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "本地生图和超分任务状态"
            }
        )
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
        outputs: List<LocalImageWorkerProtocol.OutputEnvelope>,
        executionMetadataJson: String
    ): Boolean = runCatching {
        callback.onComplete(
            LocalImageWorkerProtocol.result(
                requestId = requestId,
                workerPid = Process.myPid(),
                outputs = outputs,
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

    private fun publishCancellationTerminal(
        active: ActiveGeneration,
        message: String
    ): Boolean {
        if (!active.tryBeginCancellationTerminalPublication()) return false
        return sendError(
            callback = active.callback,
            requestId = active.requestId,
            code = LOCAL_IMAGE_GENERATION_CANCELLED_CODE,
            message = message.ifBlank { "Local image generation was cancelled." }
        )
    }

    private fun prepareResultTarget(
        requestId: String,
        mimeType: String,
        index: Int = 0
    ): ResultFileTarget {
        val outputDir = resultDirectory().apply { mkdirs() }
        cleanupStaleResults()
        val safeId = requestId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val extension = when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            else -> "png"
        }
        val baseName = "${safeId.ifBlank { "image" }}-${UUID.randomUUID()}-${index.toString().padStart(3, '0')}"
        return ResultFileTarget(
            partial = File(outputDir, "$baseName.$extension.part"),
            output = File(outputDir, "$baseName.$extension")
        )
    }

    private fun writeResultFile(target: ResultFileTarget, bytes: ByteArray): File {
        return try {
            FileOutputStream(target.partial).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            durableMoveWithinParent(
                source = target.partial,
                target = target.output,
                move = { source, output ->
                    check(source.renameTo(output)) {
                        "Unable to publish local image worker output."
                    }
                }
            )
            target.output
        } catch (error: Throwable) {
            runCatching { target.partial.delete() }
            runCatching { target.output.delete() }
            throw error
        }
    }

    private fun prepareExecutionJournal(
        request: LocalImageWorkerProtocol.GenerateRequest,
        effectiveOptions: LocalImageGenerationOptions,
        inputDirectory: File
    ) {
        val resolution = resolveLocalImageExecutionProfile(
            model = request.model,
            options = effectiveOptions,
            bundleRoot = request.model.workerBundleRoot()
        )
        executionJournal.read(request.requestId)?.let { existing ->
            require(existing.phase.terminal) {
                "A non-terminal image request already uses id ${request.requestId}."
            }
            check(executionJournal.deleteTerminal(request.requestId)) {
                "Unable to replace terminal image journal ${request.requestId}."
            }
        }
        val now = System.currentTimeMillis().coerceAtLeast(1L)
        executionJournal.create(
            ImageExecutionJournalEntry(
                requestId = request.requestId,
                modelId = request.model.id,
                modelName = request.model.displayName,
                recommendationId = resolution.profile.provenance.recommendationId.orEmpty(),
                recommendationRevision = resolution.profile.provenance.recommendationRevision.orEmpty(),
                modelFingerprint = resolution.profile.modelFingerprint,
                profileFingerprint = resolution.profile.bindingFingerprint,
                requestedSummaryJson = effectiveOptions.toJson()
                    .apply {
                        remove("inputImage")
                        remove("maskImage")
                        remove("controlImage")
                        put("productInput", effectiveOptions.inputAuditJson())
                        request.batchLineage?.let { put("batchLineage", it.toJson()) }
                    }
                    .put("runtime", request.model.runtime.name)
                    .put("family", request.model.family.name)
                    .toString(),
                resolvedSummaryJson = ImageExecutionProfileNativeContract
                    .toNativeParamsJson(resolution)
                    .put("productInput", effectiveOptions.inputAuditJson())
                    .toString(),
                phase = ImageExecutionPhase.PREPARING,
                step = 0,
                steps = resolution.layers.resolved.steps,
                workerPid = Process.myPid(),
                createdAtMs = now,
                inputTempPaths = listOfNotNull(
                    effectiveOptions.inputImage?.path,
                    effectiveOptions.maskImage?.path,
                    effectiveOptions.controlImage?.path,
                    inputDirectory.canonicalPath
                )
            )
        )
    }

    private fun prepareUpscaleExecutionJournal(
        request: LocalImageWorkerProtocol.UpscaleRequest,
        inputs: LocalImageWorkerUpscaleInputs
    ) {
        executionJournal.read(request.requestId)?.let { existing ->
            require(existing.phase.terminal) {
                "A non-terminal image request already uses id ${request.requestId}."
            }
            check(executionJournal.deleteTerminal(request.requestId)) {
                "Unable to replace terminal image journal ${request.requestId}."
            }
        }
        val now = System.currentTimeMillis().coerceAtLeast(1L)
        val requested = JSONObject()
            .put("operation", "ESRGAN_UPSCALE")
            .put("targetScale", request.targetScale)
            .put("tileSize", request.tileSize)
            .put("threads", request.threads)
            .put("input", inputs.input.toJson(includePath = false))
            .put("upscaler", inputs.upscaler.toJson(includePath = false))
        executionJournal.create(
            ImageExecutionJournalEntry(
                requestId = request.requestId,
                modelId = inputs.upscaler.id,
                modelName = inputs.upscaler.name,
                modelFingerprint = inputs.upscaler.sha256,
                profileFingerprint = "stable-diffusion-cpp-esrgan-upscale-v1",
                requestedSummaryJson = requested.toString(),
                resolvedSummaryJson = JSONObject(requested.toString())
                    .put("runtime", LocalImageRuntime.STABLE_DIFFUSION_CPP.name)
                    .put("backendMode", "cpu")
                    .toString(),
                phase = ImageExecutionPhase.PREPARING,
                step = 0,
                steps = 1,
                workerPid = Process.myPid(),
                createdAtMs = now,
                inputTempPaths = listOf(inputs.input.path, inputs.directory.canonicalPath)
            )
        )
    }

    private fun updateJournalFromProgress(requestId: String, progress: LocalImageProgress) {
        val targetPhase = progress.phase.toJournalPhase() ?: return
        runCatching {
            advanceJournalTo(
                requestId = requestId,
                targetPhase = targetPhase,
                observedStep = progress.step
            )
        }.onFailure { error ->
            logLocalImageWorkerInternalFailure("persist_progress", error)
        }
    }

    private fun advanceJournalTo(
        requestId: String,
        targetPhase: ImageExecutionPhase,
        observedStep: Int? = null
    ) {
        var current = executionJournal.read(requestId) ?: return
        if (current.phase.terminal) return
        val targetRank = targetPhase.executionRank()
        while (current.phase.executionRank() < targetRank) {
            val nextPhase = when (current.phase) {
                ImageExecutionPhase.PREPARING -> if (targetPhase == ImageExecutionPhase.CONDITIONING) {
                    ImageExecutionPhase.CONDITIONING
                } else {
                    ImageExecutionPhase.SAMPLING
                }
                ImageExecutionPhase.CONDITIONING -> ImageExecutionPhase.SAMPLING
                ImageExecutionPhase.SAMPLING -> ImageExecutionPhase.DECODING
                ImageExecutionPhase.DECODING -> ImageExecutionPhase.PUBLISHING
                else -> break
            }
            current = executionJournal.update(
                current.copy(
                    phase = nextPhase,
                    step = current.nextObservedStep(observedStep),
                    updatedAtMs = System.currentTimeMillis().coerceAtLeast(current.updatedAtMs + 1L)
                )
            )
        }
        if (current.phase == targetPhase || current.phase.executionRank() > targetRank) {
            val nextStep = current.nextObservedStep(observedStep)
            if (nextStep != current.step) {
                executionJournal.update(
                    current.copy(
                        step = nextStep,
                        updatedAtMs = System.currentTimeMillis().coerceAtLeast(current.updatedAtMs + 1L)
                    )
                )
            }
        }
    }

    private fun updateJournalFromNativeResult(requestId: String, raw: String) {
        if (raw.isBlank()) return
        runCatching {
            val metadata = JSONObject(raw)
            val current = executionJournal.read(requestId) ?: return@runCatching
            if (current.phase.terminal) return@runCatching
            val sequence = if (metadata.has("nativeGenerationSequence") &&
                !metadata.isNull("nativeGenerationSequence")
            ) {
                metadata.getLong("nativeGenerationSequence")
            } else {
                current.nativeGenerationSequence
            }
            val stageMask = metadata.optLong("nativeStageMask", current.nativeStageMask)
            executionJournal.update(
                current.copy(
                    nativeStageMask = current.nativeStageMask or stageMask,
                    nativeGenerationSequence = sequence,
                    updatedAtMs = System.currentTimeMillis().coerceAtLeast(current.updatedAtMs + 1L)
                )
            )
        }.onFailure { error ->
            logLocalImageWorkerInternalFailure("persist_native_evidence", error)
        }
    }

    private fun updateJournalOutputPath(requestId: String, output: File) {
        val current = executionJournal.read(requestId)
            ?: error("Image execution journal disappeared before result publication.")
        check(!current.phase.terminal) {
            "Image execution journal became terminal before result publication."
        }
        executionJournal.update(
            current.copy(
                outputTempPath = output.canonicalPath,
                outputTempPaths = (current.outputTempPaths + output.canonicalPath).distinct(),
                updatedAtMs = System.currentTimeMillis().coerceAtLeast(current.updatedAtMs + 1L)
            )
        )
    }

    private fun updateJournalOutputArtifact(
        requestId: String,
        output: File,
        index: Int,
        mimeType: String,
        seed: Long?
    ) {
        val canonical = output.canonicalFile
        require(canonical.isFile && canonical.length() > 0L) {
            "Published image output is missing before provenance hashing."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        canonical.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        val artifact = ImageExecutionOutputArtifact(
            index = index,
            mimeType = mimeType,
            bytes = canonical.length(),
            sha256 = digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            },
            seed = seed
        )
        val current = executionJournal.read(requestId)
            ?: error("Image execution journal disappeared before output provenance publication.")
        check(!current.phase.terminal) {
            "Image execution journal became terminal before output provenance publication."
        }
        require(index == current.outputArtifacts.size) {
            "Published image output provenance arrived out of order."
        }
        executionJournal.update(
            current.copy(
                outputArtifacts = current.outputArtifacts + artifact,
                updatedAtMs = System.currentTimeMillis().coerceAtLeast(current.updatedAtMs + 1L)
            )
        )
    }

    private fun requestJournalCancellation(requestId: String) {
        runCatching {
            val current = executionJournal.read(requestId) ?: return@runCatching
            if (!current.phase.terminal && !current.cancellationRequested) {
                executionJournal.requestCancellation(requestId)
            }
        }.onFailure { error ->
            logLocalImageWorkerInternalFailure("persist_cancellation", error)
        }
    }

    private fun finishJournalCancelled(requestId: String, message: String) {
        runCatching {
            val current = executionJournal.read(requestId) ?: return@runCatching
            if (current.phase.terminal) return@runCatching
            executionJournal.finishCancelled(
                requestId = requestId,
                cleanupRoots = listOf(cacheDir),
                message = message
            )
        }.onFailure { error ->
            logLocalImageWorkerInternalFailure("finish_cancelled_journal", error)
        }
    }

    private fun markJournalTerminal(
        requestId: String,
        phase: ImageExecutionPhase,
        errorCode: String = "",
        errorMessage: String = ""
    ) {
        runCatching {
            var current = executionJournal.read(requestId) ?: return@runCatching
            if (current.phase.terminal) return@runCatching
            if (phase == ImageExecutionPhase.COMPLETED && current.steps > 0 && current.step < current.steps) {
                current = executionJournal.update(
                    current.copy(
                        step = current.steps,
                        updatedAtMs = System.currentTimeMillis().coerceAtLeast(current.updatedAtMs + 1L)
                    )
                )
            }
            executionJournal.markTerminal(requestId, phase, errorCode, errorMessage)
        }.onFailure { error ->
            logLocalImageWorkerInternalFailure("finish_terminal_journal", error)
        }
    }

    private fun recoverInterruptedExecutions() {
        runCatching {
            // onCreate belongs to a new LocalImageWorkerService instance. A journal persisted by
            // the previous instance cannot represent work owned by this instance even if Android
            // has already reused its numeric PID for another process.
            executionJournal.recoverInterrupted(cleanupRoots = listOf(cacheDir)) { false }
        }.onSuccess { report ->
            if (report.interrupted.isNotEmpty() || report.invalidJournalFiles.isNotEmpty()) {
                Log.w(
                    "MCA-LocalImage",
                    "Recovered ${report.interrupted.size} interrupted image requests; " +
                        "invalid journals=${report.invalidJournalFiles.size}."
                )
            }
        }.onFailure { error ->
            logLocalImageWorkerInternalFailure("recover_journals", error)
        }
    }

    private fun LocalImageModelRecord.workerBundleRoot(): File? =
        bundleRoot?.let(::File)?.takeIf(File::isDirectory)
            ?: File(path).takeIf(File::isDirectory)
            ?: File(path).parentFile?.takeIf(File::isDirectory)

    private fun String.toJournalPhase(): ImageExecutionPhase? {
        val normalized = trim().lowercase()
        return when {
            normalized.isBlank() -> null
            "condition" in normalized || "text_encoder" in normalized || "tokeniz" in normalized ->
                ImageExecutionPhase.CONDITIONING
            "sampl" in normalized || "unet" in normalized || "denois" in normalized ||
                "upscal" in normalized ->
                ImageExecutionPhase.SAMPLING
            "decod" in normalized || "vae" in normalized -> ImageExecutionPhase.DECODING
            "publish" in normalized || "writing" in normalized || normalized == "completed" ->
                ImageExecutionPhase.PUBLISHING
            else -> ImageExecutionPhase.PREPARING
        }
    }

    private fun ImageExecutionPhase.executionRank(): Int = when (this) {
        ImageExecutionPhase.PREPARING -> 0
        ImageExecutionPhase.CONDITIONING -> 1
        ImageExecutionPhase.SAMPLING -> 2
        ImageExecutionPhase.DECODING -> 3
        ImageExecutionPhase.PUBLISHING -> 4
        ImageExecutionPhase.COMPLETED,
        ImageExecutionPhase.FAILED,
        ImageExecutionPhase.CANCELLED,
        ImageExecutionPhase.INTERRUPTED -> 5
    }

    private fun ImageExecutionJournalEntry.nextObservedStep(observedStep: Int?): Int {
        if (observedStep == null) return step
        val upper = if (steps > 0) steps else observedStep.coerceAtLeast(0)
        return maxOf(step, observedStep.coerceIn(0, upper))
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
        private val publicationGate = LocalImageWorkerPublicationGate()
        var deathRecipient: IBinder.DeathRecipient? = null
        var job: Job? = null

        val cancelRequested: Boolean
            get() = publicationGate.cancelRequested

        val resultPublicationStarted: Boolean
            get() = publicationGate.resultPublicationStarted

        /**
         * Result publication is the commit point for native generation. Once the final output has
         * been written and callback delivery starts, a normal client unbind may destroy this bound
         * service before the coroutine resumes after the synchronous Binder callback. That teardown
         * must not turn the already-produced request into a cancellation.
         */
        fun tryBeginResultPublication(): Boolean = publicationGate.tryBeginResultPublication()

        fun markResultPublicationTerminalAttemptFinished() =
            publicationGate.markResultPublicationTerminalAttemptFinished()

        fun awaitResultPublicationTerminalAttempt(timeoutMs: Long): Boolean =
            publicationGate.awaitResultPublicationTerminalAttempt(timeoutMs)

        fun tryRequestCancellation(): Boolean = publicationGate.tryRequestCancellation()

        fun tryBeginCancellationTerminalPublication(): Boolean =
            publicationGate.tryBeginCancellationTerminalPublication()

        fun tryBeginErrorPublication(): Boolean = publicationGate.tryBeginErrorPublication()

        private var lastStageTrace: List<String> = emptyList()

        fun withAccumulatedStages(progress: LocalImageProgress): LocalImageProgress = synchronized(this) {
            lastStageTrace = accumulateNativeStageTrace(lastStageTrace, progress.stageTrace)
            if (progress.stageTrace == lastStageTrace) progress else progress.copy(stageTrace = lastStageTrace)
        }
    }

    private data class ResultFileTarget(
        val partial: File,
        val output: File
    )

    companion object {
        private val PROCESS_RETIREMENT = LocalImageWorkerProcessRetirementGate()
        internal const val RESULT_DIRECTORY = "local_image_worker_results"
        internal const val IMAGE_EXECUTION_JOURNAL_DIRECTORY = "image_execution_journal"
        private const val FOREGROUND_CHANNEL_ID = "mca_local_image"
        private const val FOREGROUND_NOTIFICATION_ID = 11437
        private const val SELF_EXIT_GRACE_MS = 250L
        private const val RESULT_PUBLICATION_EXIT_TIMEOUT_MS = 5_000L
        private const val RESULT_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}

internal class LocalImageWorkerProcessRetirementGate {
    private val retiring = AtomicBoolean(false)

    val isRetiring: Boolean
        get() = retiring.get()

    fun beginRetirement(): Boolean = retiring.compareAndSet(false, true)
}

internal fun localImageWorkerErrorCode(error: Throwable): String {
    val raw = when (error) {
        is LocalImageProductContractException -> error.code
        is LocalImageWorkerRemoteException -> error.code
        is ImageNativeExecutionContractException -> error.code
        is ImageProfileResolutionException -> error.localImagePublicErrorCode()
        else -> "generation_failed"
    }
    return raw.trim()
        .lowercase()
        .replace(Regex("[^a-z0-9_.-]+"), "_")
        .trim('_')
        .take(80)
        .ifBlank { "generation_failed" }
}

/**
 * Serializes result, cancellation, and normal-error terminal publication for a worker request.
 * Result publication wins once the output file is complete; cancellation or error wins only while
 * native work is still in progress.
 */
internal class LocalImageWorkerPublicationGate {
    private enum class State {
        RUNNING,
        CANCELLATION_REQUESTED,
        RESULT_PUBLICATION_STARTED,
        ERROR_PUBLICATION_STARTED
    }

    @Volatile
    private var state: State = State.RUNNING

    private var cancellationTerminalPublicationStarted: Boolean = false
    private val resultPublicationTerminalAttemptFinished = CountDownLatch(1)

    val cancelRequested: Boolean
        get() = state == State.CANCELLATION_REQUESTED

    val resultPublicationStarted: Boolean
        get() = state == State.RESULT_PUBLICATION_STARTED

    fun tryBeginResultPublication(): Boolean = synchronized(this) {
        if (state != State.RUNNING) return@synchronized false
        state = State.RESULT_PUBLICATION_STARTED
        true
    }

    fun markResultPublicationTerminalAttemptFinished() {
        resultPublicationTerminalAttemptFinished.countDown()
    }

    fun awaitResultPublicationTerminalAttempt(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0L) { "Result publication exit timeout must be non-negative." }
        return resultPublicationTerminalAttemptFinished.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun tryRequestCancellation(): Boolean = synchronized(this) {
        if (state != State.RUNNING) return@synchronized false
        state = State.CANCELLATION_REQUESTED
        true
    }

    fun tryBeginCancellationTerminalPublication(): Boolean = synchronized(this) {
        if (state != State.CANCELLATION_REQUESTED || cancellationTerminalPublicationStarted) {
            return@synchronized false
        }
        cancellationTerminalPublicationStarted = true
        true
    }

    fun tryBeginErrorPublication(): Boolean = synchronized(this) {
        if (state != State.RUNNING) return@synchronized false
        state = State.ERROR_PUBLICATION_STARTED
        true
    }
}
