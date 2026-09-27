package com.muyuchat.mca

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Protects caller-side chat/image generation and persistence while the activity is backgrounded.
 * Every request holds its own lease, in addition to any isolated native worker service. A stale
 * completion can never remove the foreground protection of another chat or image request.
 */
class McaGenerationForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        runCatching {
            ensureChannel()
            // Android starts this service asynchronously after ContextCompat.startForegroundService.
            // The lease registry is process-local and may not yet contain the owner when the
            // platform delivers the start command (for example after a process recreation or an
            // OEM scheduling delay).  Promote immediately with a conservative notification so
            // the five-second foreground deadline can never be missed.  The task-specific text is
            // refreshed from onStartCommand once the current lease snapshot is available.
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildBootstrapNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        }.onFailure { error ->
            Log.e(TAG, "Unable to create generation notification channel", error)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            // Keep this call even though onCreate promotes the service. Some OEMs recreate a
            // service instance without delivering the original notification state; promoting
            // again is idempotent and keeps the deadline explicit at the command boundary.
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildBootstrapNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            leases.withCurrentTask { task ->
                // Pending Android start commands can arrive after their last owner finished.
                // There is also no resumable request when Android recreates an orphan service.
                if (intent == null || task == null) {
                    stopSelf(startId)
                } else {
                    ensureChannel()
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        buildNotification(task),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                }
            }
        }.onFailure { error ->
            // Notification construction and foreground promotion both run on Android's main
            // service thread. OEM failures must not escape and crash the entire UI/API process.
            Log.e(TAG, "Unable to enter foreground for generation tasks", error)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }

    private fun buildNotification(task: GenerationForegroundLeaseRegistry.Snapshot) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mca_api)
            .setContentTitle("MCA 正在生成")
            .setContentText(
                if (task.ownerCount > 1) {
                    "${task.ownerCount} 个生成任务在后台继续运行"
                } else {
                    when (task.kind) {
                        KIND_IMAGE -> "图像任务在后台继续运行"
                        KIND_API -> "本地 API 请求在后台继续运行"
                        else -> "聊天生成在后台继续运行"
                    }
                }
            )
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun buildBootstrapNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mca_api)
            .setContentTitle("MCA 正在准备任务")
            .setContentText("本地生成任务正在后台运行")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "MCA 生成任务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "聊天和图像生成任务的后台运行状态"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val TAG = "McaGenerationService"
        private const val CHANNEL_ID = "mca_generation"
        // Package-wide IDs must not collide with the isolated chat worker (11436),
        // image worker (11437), or Local API (11435), even across processes.
        private const val NOTIFICATION_ID = 11438
        private val leases = GenerationForegroundLeaseRegistry()
        const val KIND_CHAT = "chat"
        const val KIND_IMAGE = "image"
        const val KIND_API = "api"

        /** Returns null if Android synchronously rejects foreground service admission. */
        internal fun acquire(
            context: Context,
            kind: String = KIND_CHAT
        ): GenerationForegroundLease? = runCatching {
            leases.acquire(kind) {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, McaGenerationForegroundService::class.java)
                )
            }
        }.onFailure { error ->
            Log.e(TAG, "Unable to start generation foreground service", error)
        }.getOrNull()

        internal fun release(context: Context, lease: GenerationForegroundLease?) {
            if (lease == null) return
            runCatching {
                leases.release(lease) {
                    context.applicationContext.stopService(
                        Intent(context.applicationContext, McaGenerationForegroundService::class.java)
                    )
                }
            }.onFailure { error ->
                Log.w(TAG, "Unable to stop generation foreground service", error)
            }
        }
    }
}
