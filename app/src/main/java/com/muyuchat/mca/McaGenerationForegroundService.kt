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
import com.muyuchat.mca.agent.platform.AgentRunNotificationProtocol

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
        if (intent?.action == AgentRunNotificationProtocol.ACTION_STOP_RUN) {
            // The Agent stop action is a control command, not a generation lease. Route it to the
            // process owner and never start/promote a foreground generation for it.
            agentStopActionHandler?.invoke(intent)
            return START_NOT_STICKY
        }
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

    private fun buildNotification(task: GenerationForegroundLeaseRegistry.Snapshot): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mca_api)
            .setContentTitle("MCA 正在生成")
            .setContentText(
                if (task.ownerCount > 1) {
                    "${task.ownerCount} 个生成任务在后台继续运行"
                } else {
                    when (task.kind) {
                        KIND_IMAGE -> "图像任务在后台继续运行"
                        KIND_API -> "本地 API 请求在后台继续运行"
                        KIND_AGENT -> "Agent 任务在后台继续运行"
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
        task.agentStopAction?.let { action ->
            val stopIntent = Intent(this, McaGenerationForegroundService::class.java)
                .setAction(AgentRunNotificationProtocol.ACTION_STOP_RUN)
                .putExtra(AgentRunNotificationProtocol.EXTRA_RUN_ID, action.runId)
                .putExtra(AgentRunNotificationProtocol.EXTRA_RUN_EPOCH, action.epoch)
            val stopPendingIntent = PendingIntent.getService(
                this,
                STOP_ACTION_REQUEST_CODE,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_stat_mca_api, "停止", stopPendingIntent)
        }
        return builder.build()
    }

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

        private const val WAKE_LOCK_TAG = "mca:generation"

        private var wakeLock: android.os.PowerManager.WakeLock? = null

        /**
         * Partial wake lock held for the whole generation lease.
         *
         * Generation is a long running CPU/GPU task; without this the device can suspend as soon as
         * the screen turns off, which is exactly the "切后台就断了" report. It is a partial lock, so
         * the screen still turns off normally.
         */
        private fun acquireWakeLock(context: Context) {
            runCatching {
                if (wakeLock?.isHeld == true) return
                val manager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = manager.newWakeLock(
                    android.os.PowerManager.PARTIAL_WAKE_LOCK,
                    WAKE_LOCK_TAG
                ).apply {
                    setReferenceCounted(false)
                    acquire(MAX_WAKE_LOCK_MILLIS)
                }
            }.onFailure { error -> Log.w(TAG, "Unable to acquire generation wake lock", error) }
        }

        private fun releaseWakeLock() {
            runCatching {
                wakeLock?.let { if (it.isHeld) it.release() }
            }
            wakeLock = null
        }

        /** Safety ceiling: a leaked lease can never pin the CPU forever. */
        private const val MAX_WAKE_LOCK_MILLIS: Long = 4 * 60 * 60 * 1000L

        private const val TAG = "McaGenerationService"
        private const val CHANNEL_ID = "mca_generation"
        // Package-wide IDs must not collide with the isolated chat worker (11436),
        // image worker (11437), or Local API (11435), even across processes.
        private const val NOTIFICATION_ID = 11438
        private const val STOP_ACTION_REQUEST_CODE = 11439
        private val leases = GenerationForegroundLeaseRegistry()
        const val KIND_CHAT = "chat"
        const val KIND_IMAGE = "image"
        const val KIND_API = "api"
        const val KIND_AGENT = "agent"

        /**
         * Process-local Agent stop-action sink. The Agent owner installs it when the runtime is
         * created and clears it on close; null means no Agent owner is alive, so the action is a no-op.
         */
        @Volatile
        internal var agentStopActionHandler: ((Intent) -> Unit)? = null

        /** Returns null if Android synchronously rejects foreground service admission. */
        internal fun acquire(
            context: Context,
            kind: String = KIND_CHAT,
            agentStopAction: com.muyuchat.mca.agent.platform.AgentStopAction? = null
        ): GenerationForegroundLease? = runCatching {
            val lease = leases.acquire(kind, agentStopAction) {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, McaGenerationForegroundService::class.java)
                )
            }
            // A foreground service keeps the process alive but does not keep the CPU awake: with
            // the screen off the device can still suspend and the generation stalls mid-stream.
            acquireWakeLock(context.applicationContext)
            lease
        }.onFailure { error ->
            Log.e(TAG, "Unable to start generation foreground service", error)
        }.getOrNull()

        internal fun release(context: Context, lease: GenerationForegroundLease?) {
            if (lease == null) return
            runCatching { releaseWakeLock() }
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
