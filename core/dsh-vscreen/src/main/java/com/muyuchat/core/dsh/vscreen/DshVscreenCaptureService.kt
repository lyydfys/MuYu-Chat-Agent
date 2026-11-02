package com.muyuchat.core.dsh.vscreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service that makes the app eligible to hold a `MediaProjection` on Android 14+.
 *
 * It renders no preview and holds no display: the projection itself is owned by
 * [DshVscreenProjectionHolder] and the frames are read in the app process. The service exists
 * because `MediaProjectionManager.getMediaProjection` refuses to run unless a foreground service of
 * type `mediaProjection` is already active (targetSdk 34+), and because the user must be able to see
 * that screen capture is running.
 *
 * Lifecycle: started immediately before the consent result is handed to the holder, stopped by
 * `DshCapabilityAssembly.stop()` and whenever the grant is released. `START_NOT_STICKY` means a
 * killed process never restarts capture on its own.
 */
class DshVscreenCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foreground = true
        } catch (refused: RuntimeException) {
            // A background start or a missing mediaProjection permission is a real refusal; the
            // caller then reports consent_required instead of pretending capture is available.
            foreground = false
            stopSelf()
        } finally {
            startLatch.get()?.countDown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        foreground = false
        startLatch.get()?.countDown()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager != null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "虚拟屏幕采集",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mca virtual-screen capture"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("虚拟屏幕采集中")
            .setContentText("仅在本次虚拟屏幕会话期间运行")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 4_931
        const val CHANNEL_ID = "dsh_vscreen_capture"

        private val startLatch = AtomicReference<CountDownLatch?>(null)
        @Volatile
        private var foreground = false

        /** Starts the service; returns false when the platform refused the foreground start. */
        fun start(context: Context): Boolean = try {
            startLatch.set(CountDownLatch(1))
            foreground = false
            context.startForegroundService(Intent(context, DshVscreenCaptureService::class.java))
            true
        } catch (refused: RuntimeException) {
            startLatch.get()?.countDown()
            false
        }

        /**
         * Waits for [onStartCommand] to call [startForeground]. Blocks the caller; callers must not
         * be the main thread (the service callbacks run there and would deadlock the wait).
         */
        fun awaitForeground(timeoutMillis: Long): Boolean {
            if (foreground) return true
            val latch = startLatch.get() ?: return false
            return try {
                latch.await(timeoutMillis, TimeUnit.MILLISECONDS) && foreground
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DshVscreenCaptureService::class.java)) }
        }
    }
}
