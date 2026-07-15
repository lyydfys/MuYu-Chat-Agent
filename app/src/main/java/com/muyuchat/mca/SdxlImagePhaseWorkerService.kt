package com.muyuchat.mca

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import com.muyuchat.core.nativebridge.NativeQnnBridge
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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
                if (activeRequestId != null) false else {
                    activeRequestId = request.requestId
                    cancelled.set(false)
                    true
                }
            }
            if (!accepted) {
                sendError(callback, request.requestId, "phase_worker_busy", "SDXL phase worker is busy.")
                return false
            }
            activeJob = scope.launch { executePhase(request, callback) }
            return true
        }

        override fun cancel(requestId: String): Boolean {
            val active = synchronized(stateLock) {
                activeRequestId?.takeIf { requestId.isBlank() || it == requestId }
            } ?: return false
            cancelled.set(true)
            runCatching { bridge.cancelImageGeneration() }
            return active.isNotBlank()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        cancelled.set(true)
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
        val profile = "V${request.expectedHtpArch}"
        var poller: Job? = null
        var lastJournalProgress: LocalImageProgress? = null
        try {
            validatePrivatePaths(request)
            require(NativeQnnBridge.isAvailable && NativeQnnBridge.runnerReady) {
                "QNN native phase runner is unavailable."
            }
            sendProgress(
                callback,
                request,
                LocalImageProgress(
                    phase = "${fixedPhase.wireName}_worker_started",
                    message = "SDXL ${fixedPhase.wireName} worker started in isolated $profile process.",
                    step = 0,
                    steps = 1,
                    elapsedMs = 0L,
                    secondsPerStep = 0.0,
                    threads = 0,
                    width = 1024,
                    height = 1024,
                    cancelRequested = false
                )
            )
            val journal = File(request.journalPath)
            runCatching { journal.delete() }
            runCatching { File(request.journalPath + ".tmp").delete() }
            poller = scope.launch {
                while (isActive) {
                    val observed = QnnImageStageJournal.readOrPrevious(
                        journal,
                        lastJournalProgress,
                        threads = 0,
                        width = 1024,
                        height = 1024
                    )
                    if (observed != null && observed != lastJournalProgress) {
                        lastJournalProgress = observed
                        sendProgress(callback, request, observed)
                    }
                    delay(200L)
                }
            }
            val params = JSONObject(request.paramsJson)
                .put("expectedHtpArch", request.expectedHtpArch)
                .put("progressJournalPath", request.journalPath)
                .toString()
            val nativeRaw = when (fixedPhase) {
                SdxlImagePhase.UNET -> bridge.runSdxlUnetPhase(
                    request.bundleRoot,
                    request.runtimeDirsJson,
                    params,
                    request.embeddingsPath,
                    request.latentPath
                )
                SdxlImagePhase.VAE -> {
                    SdxlLatentArtifact.validate(
                        requestId = request.requestId,
                        latentFile = File(request.latentPath),
                        metadataFile = File(request.metadataPath),
                        expectedProducerArch = SDXL_UNET_HTP_ARCH
                    )
                    bridge.runSdxlVaePhase(
                        request.bundleRoot,
                        request.runtimeDirsJson,
                        params,
                        request.latentPath,
                        request.outputPath
                    )
                }
            }
            poller.cancelAndJoin()
            poller = null
            QnnImageStageJournal.readOrPrevious(
                journal,
                lastJournalProgress,
                threads = 0,
                width = 1024,
                height = 1024
            )?.takeIf { it != lastJournalProgress }?.let { finalProgress ->
                lastJournalProgress = finalProgress
                sendProgress(callback, request, finalProgress)
            }
            val nativeResult = JSONObject(nativeRaw)
            check(nativeResult.optBoolean("ok")) {
                nativeResult.optString("message").ifBlank { "SDXL ${fixedPhase.wireName} phase failed." }
            }
            check(nativeResult.optInt("htpArchVersion") == request.expectedHtpArch) {
                "SDXL ${fixedPhase.wireName} native runtime profile mismatch."
            }
            val artifact = when (fixedPhase) {
                SdxlImagePhase.UNET -> {
                    SdxlLatentArtifact.publishMetadata(
                        requestId = request.requestId,
                        producerPid = pid,
                        nativeResult = nativeResult,
                        latentFile = File(request.latentPath),
                        metadataFile = File(request.metadataPath)
                    )
                    File(request.latentPath)
                }
                SdxlImagePhase.VAE -> File(request.outputPath).also {
                    require(it.isFile && it.length() > 0L) { "VAE phase did not publish a PNG." }
                }
            }
            callback.onComplete(
                SdxlImagePhaseProtocol.result(
                    SdxlImagePhaseResult(
                        requestId = request.requestId,
                        phase = fixedPhase,
                        workerPid = pid,
                        runtimeProfile = profile,
                        artifactPath = artifact.canonicalPath,
                        metadataPath = request.metadataPath,
                        nativeResultJson = nativeRaw
                    )
                )
            )
        } catch (error: Throwable) {
            poller?.cancelAndJoin()
            if (fixedPhase == SdxlImagePhase.UNET) cleanupLatent(request)
            if (fixedPhase == SdxlImagePhase.VAE) runCatching { File(request.outputPath).delete() }
            sendError(
                callback,
                request.requestId,
                if (cancelled.get()) "sdxl_phase_cancelled" else "sdxl_${fixedPhase.wireName}_phase_failed",
                error.message ?: "SDXL ${fixedPhase.wireName} phase failed."
            )
        } finally {
            synchronized(stateLock) {
                activeRequestId = null
                activeJob = null
            }
            scheduleProcessExit()
        }
    }

    private fun validatePrivatePaths(request: SdxlImagePhaseRequest) {
        val root = File(cacheDir, SDXL_TWO_PHASE_DIRECTORY).canonicalFile.apply { mkdirs() }
        listOf(
            request.embeddingsPath,
            request.latentPath,
            request.metadataPath,
            request.outputPath,
            request.journalPath
        ).filter(String::isNotBlank).forEach { raw ->
            val file = File(raw).canonicalFile
            require(file.path.startsWith(root.path + File.separator)) {
                "SDXL phase path is outside the private handoff directory."
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
                        runtimeProfile = "V${request.expectedHtpArch}",
                        progress = progress
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

    private fun scheduleProcessExit() {
        Handler(Looper.getMainLooper()).postDelayed(
            {
                stopSelf()
                exitProcess(0)
            },
            SDXL_PHASE_EXIT_DELAY_MS
        )
    }
}

internal class SdxlUnetWorkerService : SdxlImagePhaseWorkerService(SdxlImagePhase.UNET)

internal class SdxlVaeWorkerService : SdxlImagePhaseWorkerService(SdxlImagePhase.VAE)

internal const val SDXL_TWO_PHASE_DIRECTORY = "sdxl_two_phase"
internal const val SDXL_UNET_HTP_ARCH = 75
internal const val SDXL_VAE_HTP_ARCH = 73
private const val SDXL_PHASE_EXIT_DELAY_MS = 150L
