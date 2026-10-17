package com.muyuchat.mca

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.muyuchat.core.nativebridge.NativeQnnBridge
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal abstract class SdxlImagePhaseWorkerService(
    internal val fixedPhase: SdxlImagePhase
) : Service() {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val stateLock = Any()
    internal val cancelled = AtomicBoolean(false)
    internal val bridge by lazy { NativeQnnBridge() }
    private var activeRequestId: String? = null
    internal var activeJob: Job? = null

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

    companion object {
        internal val PROCESS_LIFETIME = SdxlPhaseProcessLifetime()
    }
}

internal class SdxlEncoderWorkerService : SdxlImagePhaseWorkerService(SdxlImagePhase.ENCODER)

internal class SdxlUnetWorkerService : SdxlImagePhaseWorkerService(SdxlImagePhase.UNET)

internal class SdxlVaeWorkerService : SdxlImagePhaseWorkerService(SdxlImagePhase.VAE)

internal const val SDXL_TWO_PHASE_DIRECTORY = "sdxl_two_phase"
internal const val SDXL_TWO_PHASE_COORDINATION_DIRECTORY = "sdxl_two_phase_coordination"
internal const val SDXL_ARCHIVE_CONTEXT_HTP_ARCH = 75
internal const val SDXL_PHASE_EXIT_DELAY_MS = 150L
internal const val SDXL_PHASE_CANCEL_EXIT_DELAY_MS = 25L
internal val SDXL_PHASE_ARTIFACT_SUFFIXES = setOf(
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
