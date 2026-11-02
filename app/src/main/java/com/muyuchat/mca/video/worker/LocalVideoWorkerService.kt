package com.muyuchat.mca.video.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.muyuchat.mca.ILocalVideoWorker
import com.muyuchat.mca.ILocalVideoWorkerCallback
import com.muyuchat.mca.MainActivity
import com.muyuchat.mca.NativeMediaExecutionGate
import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoRunStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The independent `:local_video` foreground worker (proposal §7.3, V1-02b / P0-B).
 *
 * Thin Android shell around [VideoWorkerCore]: single-active admission, identity-checked
 * cancel/cancelAndExit, binder-death cancellation, specialUse FGS notification, and orderly
 * shutdown. The worker never writes Room state; outcomes travel back through the callback and
 * the process owner settles. Physical execution goes through [NativeMediaExecutionGate] — the
 * SAME cross-process lock domain as image generation — and the lease is held for the whole
 * executor block, including the JNI call and media encoding.
 */
class LocalVideoWorkerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var core: VideoWorkerCore
    private val pipeline by lazy { VideoWorkerPipeline(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        core = VideoWorkerCore(
            scope = scope,
            withGate = { notifyWaiting, isCancelled, block ->
                NativeMediaExecutionGate.withLease(
                    applicationContext,
                    isCancelled = isCancelled,
                    onWaiting = { notifyWaiting() },
                ) { block() }
            },
            executorBody = { execution -> pipeline.execute(execution) },
            exitProcess = {
                // Watchdog escalation kills this disposable worker process; the owner observes
                // binder death and settles the attempt as INTERRUPTED per the recovery contract.
                android.os.Process.killProcess(android.os.Process.myPid())
                throw IllegalStateException("unreachable")
            },
        )
    }

    override fun onBind(intent: Intent?): IBinder = LocalVideoWorkerBinder()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    inner class LocalVideoWorkerBinder : ILocalVideoWorker.Stub() {

        override fun begin(
            runId: String?,
            attemptId: String?,
            requestId: String?,
            snapshotJson: String?,
            executionJson: String?,
            backend: String?,
            callback: ILocalVideoWorkerCallback?,
        ): Boolean {
            if (runId == null || attemptId == null || requestId == null || snapshotJson == null || backend == null) {
                return false
            }
            val client = callback ?: return false
            // Binder death must cancel THIS request only; a stale death callback must never stop
            // the next attempt. Reuse the same recipient for link/unlink.
            val deathRecipient = IBinder.DeathRecipient { core.onClientDied(requestId) }
            runCatching { client.asBinder().linkToDeath(deathRecipient, 0) }
            enterForeground()
            val admitted = core.begin(
                VideoWorkerCore.Attempt(runId, attemptId, requestId, snapshotJson, backend, executionJson.orEmpty()),
                object : VideoWorkerCore.Events {
                    override fun onStage(attempt: String, stage: VideoRunStage, step: Long?, stepTotal: Long?) {
                        runCatching { client.onStage(stage.name, step ?: 0L, stepTotal ?: 0L, step != null) }
                    }

                    override fun onOutcome(attempt: String, outcome: VideoWorkerCore.WorkerOutcome) {
                        val (kind, code, detail) = when (outcome) {
                            is VideoWorkerCore.WorkerOutcome.Succeeded ->
                                Triple("succeeded", null, core.executionDetailFor(attempt))
                            is VideoWorkerCore.WorkerOutcome.Failed -> Triple("failed", outcome.code.name, outcome.detail)
                            is VideoWorkerCore.WorkerOutcome.Stopped -> Triple("stopped", VideoErrorCode.CANCELLED_AFTER_NATIVE_STOP.name, null)
                        }
                        runCatching { client.onOutcome(kind, code, detail) }
                        runCatching { client.asBinder().unlinkToDeath(deathRecipient, 0) }
                        leaveForegroundAndStopWhenIdle()
                    }

                    override fun onWaitingForLease(attempt: String) {
                        runCatching { client.onStage(VideoRunStage.NONE.name, 0L, 0L, false) }
                    }

                    override fun onIdle() {
                        leaveForegroundAndStopWhenIdle()
                    }
                },
            )
            if (!admitted) {
                runCatching { client.asBinder().unlinkToDeath(deathRecipient, 0) }
                leaveForegroundAndStopWhenIdle()
            }
            return admitted
        }

        override fun cancel(requestId: String?) {
            requestId ?: return
            core.cancel(requestId)
        }

        override fun cancelAndExit(requestId: String?) {
            requestId ?: return
            core.cancelAndExit(requestId)
        }

        override fun shutdown(reason: String?) {
            stopSelf()
        }
    }

    private fun enterForeground() {
        ensureForegroundChannel()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("MCA 本地视频任务")
            .setContentText("正在执行视频生成，返回应用可查看进度")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching {
            ServiceCompat.startForeground(
                this,
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }
    }

    private fun leaveForegroundAndStopWhenIdle() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (core.activeAttemptId == null) stopSelf()
    }

    private fun ensureForegroundChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(FOREGROUND_CHANNEL_ID, "本地视频任务", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val FOREGROUND_CHANNEL_ID = "local_video_worker"
        private const val FOREGROUND_NOTIFICATION_ID = 4101
    }
}
