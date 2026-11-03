package com.muyuchat.mca.dsh.overlay

import android.app.Notification
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
import com.muyuchat.mca.R

/**
 * Keep-alive half of the app-outside display.
 *
 * The `:dsh` host already runs its own foreground service while the host process is up
 * (`DshRuntimeService`). What was missing is protection for the *UI process* while the floating mark
 * is out: without a foreground service, Android may reclaim this process as soon as the activity is
 * backgrounded, taking the overlay window and the app-local capability/gateway wiring with it.
 *
 * This service therefore owns no runtime of its own — it only:
 *  - holds the UI process at foreground priority while the user keeps the mark on screen;
 *  - re-attaches the window after a system restart when the user had it switched on;
 *  - asks the app composition to make sure the existing single DSH host is running (it never starts a
 *    second host or a second port).
 *
 * The user-visible stop entry is the notification action and the settings panel; both route to
 * [DshOverlayHost.stopBackgroundFromUser], which removes the window, stops this service and clears the
 * persisted switch.
 */
class DshBackgroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        runCatching {
            ensureChannel()
            // Promote immediately: Android starts this asynchronously after
            // startForegroundService and enforces the five-second foreground deadline.
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        }.onFailure { error ->
            Log.e(TAG, "无法进入前台，停止后台保持", error)
            stopSelf()
        }
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The notification is an explicit user command, not a lifecycle event: route it to the
            // single overlay owner, which removes the window and stops this service.
            DshOverlayHost.of(this).stopBackgroundFromUser()
            return START_NOT_STICKY
        }
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        }.onFailure { error ->
            Log.e(TAG, "无法刷新前台通知", error)
        }
        val host = DshOverlayHost.of(this)
        host.ensureRuntimeStarted()
        // A null intent means Android recreated us after a kill; reattach only if still switched on.
        if (intent == null) host.restoreIfEnabled()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mca_api)
            .setContentTitle("DSH 正在后台运行")
            .setContentText("App 外显示已开启，点按小鲸鱼可随时回到 DSH")
            .setContentIntent(DshOverlayIntents.workspacePendingIntent(this, CONTENT_REQUEST_CODE))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_stat_mca_api, "停止后台运行", stopPendingIntent())
            .build()

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        STOP_REQUEST_CODE,
        Intent(this, DshBackgroundService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "DSH 后台运行", NotificationManager.IMPORTANCE_LOW).apply {
                description = "App 外显示开启时，DSH 网关与会话在后台保持运行"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val TAG = "DshBackgroundService"
        private const val CHANNEL_ID = "dsh_background"
        // Package-wide notification ids: 11435 local API, 11436 chat worker, 11437 image worker,
        // 11438 generation tasks; 11440 stays free for this service.
        private const val NOTIFICATION_ID = 11440
        private const val CONTENT_REQUEST_CODE = 11441
        private const val STOP_REQUEST_CODE = 11442

        const val ACTION_STOP = "com.muyuchat.mca.dsh.overlay.action.STOP_BACKGROUND"

        @Volatile
        var instance: DshBackgroundService? = null
            private set

        /** True only while the foreground promotion actually happened. */
        val isRunning: Boolean get() = instance != null

        /** Idempotent; a refused foreground start is reported by the caller, never faked. */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, DshBackgroundService::class.java)
            )
            true
        }.onFailure { error ->
            Log.w(TAG, "无法启动 DSH 后台保持服务", error)
        }.getOrDefault(false)

        fun stop(context: Context) {
            runCatching {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, DshBackgroundService::class.java)
                )
            }.onFailure { error ->
                Log.w(TAG, "无法停止 DSH 后台保持服务", error)
            }
        }
    }
}
