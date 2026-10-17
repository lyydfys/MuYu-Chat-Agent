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


class LocalImageWorkerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    internal lateinit var provider: LocalImageProvider
    internal lateinit var executionJournal: ImageExecutionJournalStore
    internal lateinit var workerInputStore: LocalImageWorkerInputStore
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
            }.getOrElse { return false }
            // Check ownership and signal the provider while holding the same lock used to publish
            // a replacement activeGeneration. Otherwise an old cancel could select request A,
            // then call provider.cancel() after request B has taken over the shared provider.
            val active = synchronized(stateLock) {
                activeGeneration
                    ?.takeIf { localImageCancelRequestMatches(requestedId, it.requestId) }
                    ?.takeIf { it.tryRequestCancellation() }
                    ?.also { runCatching { provider.cancel() } }
            } ?: return false
            scheduleSelfExit(active)
            requestJournalCancellation(active.requestId)
            active.job?.cancel(CancellationException("Local image generation was cancelled."))
            publishCancellationTerminal(active, "Local image generation was cancelled.")
            return true
        }

        override fun cancelAndExit(requestJson: String): Boolean {
            val requestedId = runCatching {
                LocalImageWorkerProtocol.parseCancelRequestId(requestJson)
            }.getOrNull() ?: return false
            val cancellation = synchronized(stateLock) {
                val active = activeGeneration
                    ?.takeIf { localImageCancelRequestMatches(requestedId, it.requestId) }
                    ?: return@synchronized null
                val newlyRequested = active.tryRequestCancellation()
                if (newlyRequested) runCatching { provider.cancel() }
                active to newlyRequested
            } ?: return false
            val active = cancellation.first
            scheduleSelfExit(active)
            if (cancellation.second) {
                requestJournalCancellation(active.requestId)
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

            // Keep malformed/partial downloaded bundles out of the native entry point even when
            // a caller bypasses MainViewModel (smoke harnesses and authenticated API callers can
            // reach the worker directly). This turns an avoidable native crash into a structured
            // 409-style product error and leaves the disposable worker reusable.
            request.model.localImageStructuralReadinessMessage()?.let { readiness ->
                sendError(
                    callback = callback,
                    requestId = request.requestId,
                    code = "image_model_not_ready",
                    message = "The selected local image model is not ready: $readiness " +
                        "Re-download or re-import the complete model bundle, then retry."
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
                runGenerateJob(this, active, request, effectiveOptions, callback)
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
            runUpscaleJob(this, active, request, callback)
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

    internal fun cancelForDeadClient(active: ActiveGeneration) {
        val isCurrentCancellation = synchronized(stateLock) {
            if (activeGeneration !== active || !active.tryRequestCancellation()) {
                false
            } else {
                runCatching { provider.cancel() }
                true
            }
        }
        if (!isCurrentCancellation) return
        scheduleSelfExit(active)
        requestJournalCancellation(active.requestId)
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

    internal fun finish(active: ActiveGeneration) {
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


    internal class ActiveGeneration(
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

    internal data class ResultFileTarget(
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
        internal const val RESULT_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
