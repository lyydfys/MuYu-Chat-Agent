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

internal const val LOCAL_IMAGE_GENERATION_CANCELLED_CODE = "image_generation_cancelled"

class LocalImageWorkerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private lateinit var provider: LocalImageProvider
    private lateinit var executionJournal: ImageExecutionJournalStore
    private lateinit var workerInputStore: LocalImageWorkerInputStore

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
            requestJournalCancellation(active.requestId)
            runCatching { provider.cancel() }
            active.job?.cancel(CancellationException("Local image generation was cancelled."))
            publishCancellationTerminal(active, "Local image generation was cancelled.")
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
                var transferredOutputs: List<File> = emptyList()
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
                        updateJournalOutputPath(request.requestId, output)
                        generated to output
                    }
                    transferredOutputs = publishedOutputs.map { it.second }
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
                    Log.i(
                        "MCA-LocalImage",
                        JSONObject()
                            .put("requestId", request.requestId)
                            .put("workerPid", Process.myPid())
                            .put("outputBytes", outputBytes)
                            .put("mimeType", result.mimeType)
                            .put(
                                "execution",
                                runCatching { JSONObject(result.executionMetadataJson) }.getOrElse { JSONObject() }
                            )
                            .toString()
                    )
                    if (delivered) {
                        markJournalTerminal(request.requestId, ImageExecutionPhase.COMPLETED)
                        transferredOutputs = emptyList()
                    } else {
                        markJournalTerminal(
                            request.requestId,
                            ImageExecutionPhase.FAILED,
                            errorCode = "RESULT_DELIVERY_FAILED",
                            errorMessage = "The generated image could not be delivered to the client."
                        )
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
    }

    override fun onCreate() {
        super.onCreate()
        provider = LocalImageProvider(applicationContext)
        workerInputStore = LocalImageWorkerInputStore(applicationContext)
        workerInputStore.clearOrphanedWorkerInputs()
        executionJournal = ImageExecutionJournalStore(
            File(filesDir, IMAGE_EXECUTION_JOURNAL_DIRECTORY)
        )
        recoverInterruptedExecutions()
        cleanupStaleResults()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        val active = synchronized(stateLock) {
            activeGeneration.also { activeGeneration = null }
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
    }

    private fun cancelForDeadClient(active: ActiveGeneration) {
        val isCurrent = synchronized(stateLock) { activeGeneration === active }
        if (!isCurrent) return
        if (!active.tryRequestCancellation()) return
        requestJournalCancellation(active.requestId)
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
        target.partial.outputStream().use { it.write(bytes) }
        check(target.partial.renameTo(target.output)) { "Unable to publish local image worker output." }
        return target.output
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
                modelFingerprint = resolution.profile.modelFingerprint,
                profileFingerprint = resolution.profile.bindingFingerprint,
                requestedSummaryJson = effectiveOptions.toJson()
                    .apply {
                        remove("inputImage")
                        remove("maskImage")
                        remove("controlImage")
                        put("productInput", effectiveOptions.inputAuditJson())
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

    private fun updateJournalFromProgress(requestId: String, progress: LocalImageProgress) {
        val targetPhase = progress.phase.toJournalPhase() ?: return
        runCatching {
            advanceJournalTo(
                requestId = requestId,
                targetPhase = targetPhase,
                observedStep = progress.step
            )
        }.onFailure { error ->
            Log.w("MCA-LocalImage", "Unable to persist image progress", error)
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
            Log.w("MCA-LocalImage", "Unable to persist native image evidence", error)
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

    private fun requestJournalCancellation(requestId: String) {
        runCatching {
            val current = executionJournal.read(requestId) ?: return@runCatching
            if (!current.phase.terminal && !current.cancellationRequested) {
                executionJournal.requestCancellation(requestId)
            }
        }.onFailure { error ->
            Log.w("MCA-LocalImage", "Unable to persist image cancellation", error)
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
            Log.w("MCA-LocalImage", "Unable to finish cancelled image journal", error)
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
            Log.w("MCA-LocalImage", "Unable to finish image journal", error)
        }
    }

    private fun recoverInterruptedExecutions() {
        runCatching {
            executionJournal.recoverInterrupted(cleanupRoots = listOf(cacheDir)) { pid ->
                pid > 0 && File("/proc/$pid").isDirectory
            }
        }.onSuccess { report ->
            if (report.interrupted.isNotEmpty() || report.invalidJournalFiles.isNotEmpty()) {
                Log.w(
                    "MCA-LocalImage",
                    "Recovered ${report.interrupted.size} interrupted image requests; " +
                        "invalid journals=${report.invalidJournalFiles.size}."
                )
            }
        }.onFailure { error ->
            Log.w("MCA-LocalImage", "Unable to recover image execution journals", error)
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
            "sampl" in normalized || "unet" in normalized || "denois" in normalized ->
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

        /**
         * Result publication is the commit point for native generation. Once the final output has
         * been written and callback delivery starts, a normal client unbind may destroy this bound
         * service before the coroutine resumes after the synchronous Binder callback. That teardown
         * must not turn the already-produced request into a cancellation.
         */
        fun tryBeginResultPublication(): Boolean = publicationGate.tryBeginResultPublication()

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
        internal const val RESULT_DIRECTORY = "local_image_worker_results"
        internal const val IMAGE_EXECUTION_JOURNAL_DIRECTORY = "image_execution_journal"
        private const val RESULT_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}

internal fun localImageWorkerErrorCode(error: Throwable): String {
    val raw = when (error) {
        is LocalImageProductContractException -> error.code
        is LocalImageWorkerRemoteException -> error.code
        is ImageNativeExecutionContractException -> error.code
        is ImageProfileResolutionException -> "invalid_image_execution_profile"
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

    val cancelRequested: Boolean
        get() = state == State.CANCELLATION_REQUESTED

    fun tryBeginResultPublication(): Boolean = synchronized(this) {
        if (state != State.RUNNING) return@synchronized false
        state = State.RESULT_PUBLICATION_STARTED
        true
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
