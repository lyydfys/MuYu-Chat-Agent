package com.muyuchat.mca

import android.os.IBinder
import android.os.Process
import com.muyuchat.core.nativebridge.NativeQnnBridge
import java.io.File
import kotlin.system.exitProcess
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.muyuchat.mca.SdxlImagePhaseWorkerService.Companion.PROCESS_LIFETIME

/**
 * T2B22-T1（b 工位）拆片：`executePhase` / `validatePrivatePaths` / `sendProgress` /
 * `sendError` / `cleanupLatent` / `scheduleProcessExit` 自
 * SdxlImagePhaseWorkerService.kt 逐字节平移为同包扩展函数
 * （private→internal + 接收者前缀，登记改写 6 处）。
 */

    internal suspend fun SdxlImagePhaseWorkerService.executePhase(
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

    internal fun SdxlImagePhaseWorkerService.validatePrivatePaths(request: SdxlImagePhaseRequest) {
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

    internal fun SdxlImagePhaseWorkerService.sendProgress(
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

    internal fun SdxlImagePhaseWorkerService.sendError(
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

    internal fun SdxlImagePhaseWorkerService.cleanupLatent(request: SdxlImagePhaseRequest) {
        listOf(
            request.latentPath,
            request.latentPath + ".part",
            request.metadataPath,
            request.metadataPath + ".part"
        ).forEach { runCatching { File(it).delete() } }
    }

    internal fun SdxlImagePhaseWorkerService.scheduleProcessExit(delayMs: Long = SDXL_PHASE_EXIT_DELAY_MS) {
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
