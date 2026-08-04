package com.muyuchat.mca

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.muyuchat.core.nativebridge.NativeQnnBridge
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

internal abstract class SdxlImagePhaseWorkerService(
    private val fixedPhase: SdxlImagePhase
) : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val cancelled = AtomicBoolean(false)
    private val bridge by lazy { NativeQnnBridge() }
    private var activeRequestId: String? = null
    private var activeJob: Job? = null

    private val binder = object : ISdxlImagePhaseWorker.Stub() {
        override fun execute(
            requestJson: String,
            callback: ISdxlImagePhaseWorkerCallback
        ): Boolean {
            val request = runCatching { SdxlImagePhaseProtocol.parseRequest(requestJson) }
                .getOrElse { error ->
                    sendError(callback, "", "invalid_phase_request", error.message.orEmpty())
                    scheduleProcessExit()
                    return false
                }
            if (request.phase != fixedPhase) {
                sendError(
                    callback,
                    request.requestId,
                    "phase_process_mismatch",
                    "${javaClass.simpleName} accepts only ${fixedPhase.wireName}."
                )
                scheduleProcessExit()
                return false
            }
            val accepted = synchronized(stateLock) {
                if (activeRequestId != null || !PROCESS_LIFETIME.tryClaimRequest()) false else {
                    activeRequestId = request.requestId
                    cancelled.set(false)
                    true
                }
            }
            if (!accepted) {
                sendError(callback, request.requestId, "phase_worker_busy", "SDXL phase worker is busy.")
                return false
            }
            val artifactRoot = File(cacheDir, SDXL_TWO_PHASE_DIRECTORY).canonicalFile
            val coordinationRoot = File(
                noBackupFilesDir,
                SDXL_TWO_PHASE_COORDINATION_DIRECTORY
            ).canonicalFile
            val phaseOwnership = runCatching {
                SdxlTwoPhaseRequestLease.acquirePhaseOwnership(
                    requestId = request.requestId,
                    paramsJson = request.paramsJson,
                    artifactRoot = artifactRoot,
                    coordinationRoot = coordinationRoot
                )
            }.getOrElse { error ->
                sendError(
                    callback,
                    request.requestId,
                    "phase_request_lease_rejected",
                    error.message.orEmpty().ifBlank { "Split-SDXL request lease is no longer executable." }
                )
                scheduleProcessExit()
                return false
            }
            PROCESS_LIFETIME.retainPhaseOwnershipUntilProcessDeath(phaseOwnership)
            activeJob = scope.launch { executePhase(request, callback) }
            return true
        }

        override fun cancel(requestId: String): Boolean {
            val active = synchronized(stateLock) {
                activeRequestId?.takeIf { requestId.isBlank() || it == requestId }
            } ?: return false
            cancelled.set(true)
            scheduleProcessExit(SDXL_PHASE_CANCEL_EXIT_DELAY_MS)
            runCatching { bridge.cancelImageGeneration() }
            return active.isNotBlank()
        }
    }

    override fun onBind(intent: Intent?): IBinder? =
        binder.takeIf { PROCESS_LIFETIME.acceptsRequests }

    override fun onDestroy() {
        cancelled.set(true)
        scheduleProcessExit(delayMs = 0L)
        runCatching { bridge.cancelImageGeneration() }
        activeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun executePhase(
        request: SdxlImagePhaseRequest,
        callback: ISdxlImagePhaseWorkerCallback
    ) {
        val pid = Process.myPid()
        val requestedProfile = sdxlTransportProfile(request.phaseHtpArch)
        var poller: Job? = null
        var lastJournalProgress: LocalImageProgress? = null
        val callbackBinder = callback.asBinder()
        val callbackDeathRecipient = IBinder.DeathRecipient {
            cancelled.set(true)
            scheduleProcessExit(SDXL_PHASE_CANCEL_EXIT_DELAY_MS)
            runCatching { bridge.cancelImageGeneration() }
            activeJob?.cancel()
        }
        try {
            callbackBinder.linkToDeath(callbackDeathRecipient, 0)
            request.requireNoSdxlProjectionPreview()
            validatePrivatePaths(request)
            val contract = SdxlImageExecutionContract.fromParams(request.paramsJson).also {
                it.validateRequestIdentity(request)
            }
            require(NativeQnnBridge.isAvailable && NativeQnnBridge.runnerReady) {
                "QNN native phase runner is unavailable."
            }
            sendProgress(
                callback,
                request,
                LocalImageProgress(
                    phase = "${fixedPhase.wireName}_worker_started",
                    message = "SDXL ${fixedPhase.wireName} worker started in isolated $requestedProfile process.",
                    step = if (fixedPhase == SdxlImagePhase.VAE) contract.steps else 0,
                    steps = contract.steps,
                    elapsedMs = 0L,
                    secondsPerStep = 0.0,
                    threads = 0,
                    width = contract.width,
                    height = contract.height,
                    cancelRequested = false
                )
            )
            val journal = File(request.journalPath)
            if (fixedPhase == SdxlImagePhase.UNET) {
                runCatching { QnnImageStageJournal.cleanupSdxlProjectionPreview(journal) }
            }
            runCatching { journal.delete() }
            runCatching { File(request.journalPath + ".tmp").delete() }
            poller = scope.launch {
                while (isActive) {
                    val observed = QnnImageStageJournal.readOrPrevious(
                        journal,
                        lastJournalProgress,
                        threads = 0,
                        width = contract.width,
                        height = contract.height
                    )
                    val normalized = observed?.forSdxlPhase(fixedPhase, contract)
                    if (normalized != null && normalized != lastJournalProgress) {
                        lastJournalProgress = normalized
                        sendProgress(callback, request, normalized)
                    }
                    delay(200L)
                }
            }
            val params = contract.paramsObject()
                // Native receives only this phase's own transport preference. The source
                // artifact producer transport is provenance and must never constrain load.
                .put("expectedHtpArch", request.phaseHtpArch)
                .put("progressJournalPath", request.journalPath)
                .toString()
            val runtimeProfileJson = request.runtimeProfile.toJson().toString()
            check(!cancelled.get()) { "SDXL phase was cancelled before native execution." }
            val nativeRaw = when (fixedPhase) {
                SdxlImagePhase.ENCODER -> bridge.runSdxlEncoderPhase(
                    request.bundleRoot,
                    runtimeProfileJson,
                    params,
                    request.inputTensorPath,
                    request.latentPath,
                    request.expectedVaeEncoderContextSha256
                )
                SdxlImagePhase.UNET -> {
                    if (request.sourceLatentPath.isNotBlank()) {
                        SdxlEncoderLatentArtifact.validate(
                            requestId = request.requestId,
                            latentFile = File(request.sourceLatentPath),
                            metadataFile = File(request.sourceMetadataPath),
                            expectedProducerArch = request.sourceArtifactProducerHtpArch,
                            contract = contract
                        )
                    }
                    bridge.runSdxlUnetPhase(
                        request.bundleRoot,
                        runtimeProfileJson,
                        params,
                        request.embeddingsPath,
                        request.sourceLatentPath,
                        request.latentPath
                    )
                }
                SdxlImagePhase.VAE -> {
                    SdxlLatentArtifact.validate(
                        requestId = request.requestId,
                        latentFile = File(request.latentPath),
                        metadataFile = File(request.metadataPath),
                        expectedProducerArch = request.sourceArtifactProducerHtpArch,
                        contract = contract
                    )
                    bridge.runSdxlVaePhase(
                        request.bundleRoot,
                        runtimeProfileJson,
                        params,
                        request.latentPath,
                        request.outputPath
                    )
                }
            }
            check(!cancelled.get()) { "SDXL phase was cancelled during native execution." }
            poller.cancelAndJoin()
            poller = null
            QnnImageStageJournal.readOrPrevious(
                journal,
                lastJournalProgress,
                threads = 0,
                width = contract.width,
                height = contract.height
            )?.forSdxlPhase(fixedPhase, contract)
                ?.takeIf { it != lastJournalProgress }?.let { finalProgress ->
                lastJournalProgress = finalProgress
                sendProgress(callback, request, finalProgress)
            }
            val nativeResult = JSONObject(nativeRaw)
            if (!nativeResult.optBoolean("ok")) {
                throw SdxlNativePhaseFailure(
                    executionStage = nativeResult.optString("executionStage"),
                    message = nativeResult.optString("message")
                        .ifBlank { "SDXL ${fixedPhase.wireName} phase failed." }
                )
            }
            val selectedHtpArch = validateSdxlNativeTransport(
                phase = fixedPhase,
                expectedRuntimeProfile = request.runtimeProfile,
                nativeResult = nativeResult
            )
            val selectedProfile = sdxlTransportProfile(selectedHtpArch)
            val proof = SdxlNativePhaseProof.fromNativeResult(nativeResult, fixedPhase)
            nativeResult.requireSdxlPreviewDisabledNativeResult(
                "Split-SDXL ${fixedPhase.wireName} native result"
            )
            val projectionPreviewAudit = SdxlProjectionPreviewAudit.NONE
            check(!cancelled.get()) { "SDXL phase was cancelled before artifact publication." }
            val artifact = when (fixedPhase) {
                SdxlImagePhase.ENCODER -> {
                    SdxlEncoderLatentArtifact.publishMetadata(
                        requestId = request.requestId,
                        producerPid = pid,
                        contract = contract,
                        proof = proof,
                        nativeResult = nativeResult,
                        latentFile = File(request.latentPath),
                        metadataFile = File(request.metadataPath)
                    )
                    check(!cancelled.get()) { "SDXL encoder was cancelled during metadata publication." }
                    File(request.latentPath)
                }
                SdxlImagePhase.UNET -> {
                    SdxlLatentArtifact.publishMetadata(
                        requestId = request.requestId,
                        producerPid = pid,
                        contract = contract,
                        proof = proof,
                        nativeResult = nativeResult,
                        latentFile = File(request.latentPath),
                        metadataFile = File(request.metadataPath)
                    )
                    check(!cancelled.get()) { "SDXL UNet was cancelled during metadata publication." }
                    File(request.latentPath)
                }
                SdxlImagePhase.VAE -> File(request.outputPath).also {
                    validateSdxlVaeNativeEvidence(contract, nativeResult)
                    require(it.isFile && it.length() > 0L) { "VAE phase did not publish a PNG." }
                    check(!cancelled.get()) { "SDXL VAE was cancelled after PNG publication." }
                }
            }
            check(!cancelled.get()) { "SDXL phase was cancelled before result publication." }
            callback.onComplete(
                SdxlImagePhaseProtocol.result(
                    SdxlImagePhaseResult(
                        requestId = request.requestId,
                        phase = fixedPhase,
                        workerPid = pid,
                        runtimeProfile = selectedProfile,
                        artifactPath = artifact.canonicalPath,
                        metadataPath = request.metadataPath,
                        nativeGenerationSequence = proof.nativeGenerationSequence,
                        nativeStageMask = proof.nativeStageMask,
                        nativeDetailStageMask = proof.nativeDetailStageMask,
                        conditioningArtifactSha256 = contract.conditioningArtifactSha256,
                        nativeResultJson = nativeRaw,
                        projectionPreviewAudit = projectionPreviewAudit
                    )
                )
            )
        } catch (error: Throwable) {
            poller?.cancelAndJoin()
            if (fixedPhase == SdxlImagePhase.UNET) {
                runCatching {
                    QnnImageStageJournal.cleanupSdxlProjectionPreview(File(request.journalPath))
                }
            }
            if (fixedPhase != SdxlImagePhase.VAE) cleanupLatent(request)
            if (fixedPhase == SdxlImagePhase.VAE) {
                runCatching { File(request.outputPath).delete() }
                runCatching { File(request.outputPath + ".part").delete() }
            }
            sendError(
                callback,
                request.requestId,
                when {
                    cancelled.get() -> "sdxl_phase_cancelled"
                    error is SdxlNativePhaseFailure ->
                        sdxlRuntimeCandidateFailureCode(fixedPhase, error.executionStage)
                            ?: "sdxl_${fixedPhase.wireName}_phase_failed"
                    else -> "sdxl_${fixedPhase.wireName}_phase_failed"
                },
                error.message ?: "SDXL ${fixedPhase.wireName} phase failed."
            )
        } finally {
            runCatching { callbackBinder.unlinkToDeath(callbackDeathRecipient, 0) }
            synchronized(stateLock) {
                activeJob = null
            }
            scheduleProcessExit()
        }
    }

    private fun validatePrivatePaths(request: SdxlImagePhaseRequest) {
        val root = File(cacheDir, SDXL_TWO_PHASE_DIRECTORY).canonicalFile.apply { mkdirs() }
        listOf(
            request.embeddingsPath,
            request.inputTensorPath,
            request.maskTensorPath,
            request.fullMaskTensorPath,
            request.sourceLatentPath,
            request.sourceMetadataPath,
            request.latentPath,
            request.metadataPath,
            request.outputPath,
            request.journalPath
        ).filter(String::isNotBlank).forEach { raw ->
            val file = File(raw).canonicalFile
            val declared = File(raw).absoluteFile
            val suffix = file.name.removePrefix(request.requestId)
            require(declared == file && file.parentFile == root &&
                file.name.startsWith(request.requestId) && suffix in SDXL_PHASE_ARTIFACT_SUFFIXES
            ) {
                "SDXL phase path is outside its exact private request namespace."
            }
        }
        val params = JSONObject(request.paramsJson)
        val taskMode = LocalImageTaskMode.fromWireName(params.getString("taskMode"))
        when (fixedPhase) {
            SdxlImagePhase.ENCODER -> require(
                request.inputTensorPath == params.getString("inputImageTensorPath")
            ) { "SDXL encoder request and params disagree on the source tensor path." }
            SdxlImagePhase.UNET -> if (taskMode == LocalImageTaskMode.INPAINT) {
                require(request.maskTensorPath == params.getString("maskImageTensorPath")) {
                    "SDXL inpaint UNet request and params disagree on the latent mask path."
                }
            }
            SdxlImagePhase.VAE -> if (taskMode == LocalImageTaskMode.INPAINT) {
                require(request.inputTensorPath == params.getString("inputImageTensorPath") &&
                    request.fullMaskTensorPath == params.getString("maskImageFullTensorPath")
                ) { "SDXL inpaint VAE request and params disagree on pixel-blend artifacts." }
            }
        }
        if (fixedPhase == SdxlImagePhase.ENCODER) {
            val workerInputRoot = File(
                cacheDir,
                LocalImageWorkerInputStore.WORKER_INPUT_DIRECTORY
            ).canonicalFile
            val source = File(
                params.getString("inputImagePath")
            ).canonicalFile
            require(source.path.startsWith(workerInputRoot.path + File.separator) && source.isFile) {
                "SDXL encoder source must stay inside the worker-owned input directory."
            }
        }
        require(File(request.bundleRoot).isDirectory) { "SDXL bundle root is missing." }
    }

    private fun sendProgress(
        callback: ISdxlImagePhaseWorkerCallback,
        request: SdxlImagePhaseRequest,
        progress: LocalImageProgress
    ) {
        runCatching {
            callback.onProgress(
                SdxlImagePhaseProtocol.progress(
                    SdxlImagePhaseProgress(
                        requestId = request.requestId,
                        phase = fixedPhase,
                        workerPid = Process.myPid(),
                        runtimeProfile = sdxlTransportProfile(request.phaseHtpArch),
                        progress = progress,
                        projectionPreviewAudit = SdxlProjectionPreviewAudit.NONE
                    )
                )
            )
        }
    }

    private fun sendError(
        callback: ISdxlImagePhaseWorkerCallback,
        requestId: String,
        code: String,
        message: String
    ) {
        runCatching {
            callback.onError(
                SdxlImagePhaseProtocol.error(
                    SdxlImagePhaseError(
                        requestId = requestId,
                        phase = fixedPhase,
                        workerPid = Process.myPid(),
                        code = code,
                        message = message.ifBlank { "SDXL ${fixedPhase.wireName} phase failed." }
                    )
                )
            )
        }
    }

    private fun cleanupLatent(request: SdxlImagePhaseRequest) {
        listOf(
            request.latentPath,
            request.latentPath + ".part",
            request.metadataPath,
            request.metadataPath + ".part"
        ).forEach { runCatching { File(it).delete() } }
    }

    private fun scheduleProcessExit(delayMs: Long = SDXL_PHASE_EXIT_DELAY_MS) {
        PROCESS_LIFETIME.beginRetirement()
        Thread(
            {
                if (delayMs > 0L) runCatching { Thread.sleep(delayMs) }
                Process.killProcess(Process.myPid())
                exitProcess(0)
            },
            "mca-sdxl-${fixedPhase.wireName}-self-exit"
        ).apply {
            isDaemon = true
            start()
        }
    }

    companion object {
        private val PROCESS_LIFETIME = SdxlPhaseProcessLifetime()
    }
}

private class SdxlNativePhaseFailure(
    val executionStage: String,
    message: String
) : IllegalStateException(message)

internal fun sdxlRuntimeCandidateFailureCode(
    phase: SdxlImagePhase,
    executionStage: String
): String? {
    val retryable = when (phase) {
        SdxlImagePhase.ENCODER -> executionStage in setOf(
            "runtime_unavailable",
            "sdxl_encoder_load_failed",
            "encoder_profile_mismatch"
        )
        SdxlImagePhase.UNET -> executionStage in setOf(
            "runtime_unavailable",
            "unet_load_failed",
            "unet_profile_mismatch"
        )
        SdxlImagePhase.VAE -> executionStage in setOf(
            "runtime_unavailable",
            "vae_load_failed",
            "vae_profile_mismatch"
        )
    }
    return if (retryable) "sdxl_${phase.wireName}_runtime_candidate_failed" else null
}

internal fun isSdxlRuntimeCandidateFailureCode(
    phase: SdxlImagePhase,
    code: String
): Boolean = code == "sdxl_${phase.wireName}_runtime_candidate_failed"

internal class SdxlPhaseProcessLifetime {
    private val state = AtomicInteger(STATE_ACCEPTING)

    @Volatile
    private var phaseOwnership: SdxlRequestFileLock? = null

    val acceptsRequests: Boolean
        get() = state.get() == STATE_ACCEPTING

    val isRetiring: Boolean
        get() = state.get() == STATE_RETIRING

    val hasRetainedPhaseOwnership: Boolean
        get() = phaseOwnership != null

    fun tryClaimRequest(): Boolean = state.compareAndSet(STATE_ACCEPTING, STATE_CLAIMED)

    fun beginRetirement(): Boolean = state.getAndSet(STATE_RETIRING) != STATE_RETIRING

    fun retainPhaseOwnershipUntilProcessDeath(ownership: SdxlRequestFileLock) {
        synchronized(this) {
            check(state.get() != STATE_ACCEPTING) {
                "SDXL phase ownership requires a claimed process."
            }
            check(phaseOwnership == null) {
                "SDXL phase process already owns a request lease."
            }
            // Deliberately never close this lock. The isolated phase process is disposable, and
            // its death is the only boundary that may release recovery/admission for this request.
            phaseOwnership = ownership
        }
    }

    private companion object {
        const val STATE_ACCEPTING = 0
        const val STATE_CLAIMED = 1
        const val STATE_RETIRING = 2
    }
}

private fun LocalImageProgress.forSdxlPhase(
    phase: SdxlImagePhase,
    contract: SdxlImageExecutionContract
): LocalImageProgress {
    requireSdxlPreviewDisabled("Split-SDXL phase journal progress")
    val params = contract.paramsObject()
    val taskMode = LocalImageTaskMode.fromWireName(params.getString("taskMode"))
    val ultraFix = contract.ultraFixRequestOrNull() != null
    val mappedStep = sdxlPhaseProgressStep(
        phase = phase,
        taskMode = taskMode,
        fullSteps = contract.steps,
        beginIndex = params.optInt("img2imgBeginIndex", 0),
        effectiveSteps = params.optInt("effectiveDenoiseSteps", contract.steps),
        physicalStep = step,
        ultraFix = ultraFix
    )
    return copy(
        step = mappedStep,
        steps = contract.steps,
        width = contract.width,
        height = contract.height
    ).withSdxlPreviewDisabled()
}

internal fun sdxlPhaseProgressStep(
    phase: SdxlImagePhase,
    taskMode: LocalImageTaskMode,
    fullSteps: Int,
    beginIndex: Int,
    effectiveSteps: Int,
    physicalStep: Int,
    ultraFix: Boolean = false
): Int {
    require(fullSteps > 0) { "SDXL progress requires positive full steps." }
    if (phase == SdxlImagePhase.VAE) return fullSteps
    if (phase == SdxlImagePhase.UNET && ultraFix) {
        require(taskMode == LocalImageTaskMode.IMG2IMG && effectiveSteps > 0) {
            "Split-SDXL UltraFix progress requires an encoder-backed img2img tail."
        }
        val physicalTotal = Math.multiplyExact(effectiveSteps, 2)
        return ((physicalStep.coerceIn(0, physicalTotal).toLong() * fullSteps) /
            physicalTotal).toInt().coerceIn(0, fullSteps)
    }
    if (phase != SdxlImagePhase.UNET ||
        taskMode !in setOf(LocalImageTaskMode.IMG2IMG, LocalImageTaskMode.INPAINT)
    ) {
        return physicalStep.coerceIn(0, fullSteps)
    }
    require(beginIndex in 0 until fullSteps && effectiveSteps == fullSteps - beginIndex) {
        "SDXL img2img progress schedule is inconsistent."
    }
    return (beginIndex + physicalStep.coerceIn(0, effectiveSteps)).coerceAtMost(fullSteps)
}

internal class SdxlEncoderWorkerService : SdxlImagePhaseWorkerService(SdxlImagePhase.ENCODER)

internal class SdxlUnetWorkerService : SdxlImagePhaseWorkerService(SdxlImagePhase.UNET)

internal class SdxlVaeWorkerService : SdxlImagePhaseWorkerService(SdxlImagePhase.VAE)

internal const val SDXL_TWO_PHASE_DIRECTORY = "sdxl_two_phase"
internal const val SDXL_TWO_PHASE_COORDINATION_DIRECTORY = "sdxl_two_phase_coordination"
internal const val SDXL_ARCHIVE_CONTEXT_HTP_ARCH = 75
private const val SDXL_PHASE_EXIT_DELAY_MS = 150L
private const val SDXL_PHASE_CANCEL_EXIT_DELAY_MS = 25L
private val SDXL_PHASE_ARTIFACT_SUFFIXES = setOf(
    ".sdxl-conditioning.f32",
    ".input-rgb-nchw.f32",
    ".inpaint-mask-latent.f32",
    ".inpaint-mask-full.f32",
    ".encoder-latent.f32",
    ".encoder-latent.json",
    ".latent.f32",
    ".latent.json",
    ".png",
    ".encoder-stage.json",
    ".unet-stage.json",
    ".vae-stage.json"
)
