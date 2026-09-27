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
import com.muyuchat.mca.R

class LocalApiForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        // Android starts the foreground-service timeout as soon as
        // startForegroundService() is accepted. Promote from onCreate before any
        // queued start/stop command can race with onStartCommand().
        runCatching {
            ensureChannel()
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(openPort = requestedOpenPort),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            synchronized(stateLock) {
                serviceCreated = true
                serviceForeground = true
            }
        }.onFailure { error ->
            Log.e(TAG, "Unable to promote local API service during creation", error)
            synchronized(stateLock) {
                serviceCreated = true
                serviceForeground = false
            }
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val openPort = intent?.getBooleanExtra(EXTRA_OPEN_PORT, requestedOpenPort) ?: requestedOpenPort
        synchronized(stateLock) {
            requestedOpenPort = openPort
        }
        runCatching {
            ensureChannel()
            // onCreate normally performed the first promotion. Repeat it here to
            // update the notification after a bind-mode change and to keep the
            // null-intent recreation path safe on OEM Android builds.
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(openPort),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            synchronized(stateLock) {
                serviceCreated = true
                serviceForeground = true
            }
            if (intent == null || !isRequested()) {
                stopSelf(startId)
            }
        }.onFailure { error ->
            // Android may reject promotion independently of the start request.
            // Keep that platform failure out of the main thread's uncaught path.
            Log.e(TAG, "Unable to promote local API to foreground", error)
            synchronized(stateLock) { serviceForeground = false }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val restartAfterDestroy = synchronized(stateLock) {
            val wasForeground = serviceForeground
            serviceCreated = false
            serviceForeground = false
            // A future start must issue a new startForegroundService request.
            startRequested = false
            requestedRunning && wasForeground
        }
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
        // A stop request can overlap a new start request while Android is still
        // dispatching onDestroy(). Re-issue the request after teardown when the
        // desired state is running, so the new owner cannot be stranded without
        // a service notification.
        if (restartAfterDestroy) {
            runCatching {
                synchronized(stateLock) { startRequested = true }
                ContextCompat.startForegroundService(
                    applicationContext,
                    Intent(applicationContext, LocalApiForegroundService::class.java)
                        .putExtra(EXTRA_OPEN_PORT, requestedOpenPort)
                )
            }.onFailure { error ->
                synchronized(stateLock) { startRequested = false }
                Log.e(TAG, "Unable to restart local API foreground service", error)
            }
        }
    }

    private fun buildNotification(openPort: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mca_api)
            .setContentTitle("MCA 本地 API 正在运行")
            .setContentText(if (openPort) "已开放同网段访问，请只在可信网络中使用。" else "已允许同设备客户端访问。")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "本地 API",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "MCA 本地 API 运行状态通知"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "McaLocalApiService"
        private const val CHANNEL_ID = "mca_local_api"
        private const val NOTIFICATION_ID = 11435
        private const val EXTRA_OPEN_PORT = "open_port"
        private val stateLock = Any()
        @Volatile private var requestedRunning = false
        @Volatile private var requestedOpenPort = false
        @Volatile private var startRequested = false
        @Volatile private var serviceCreated = false
        @Volatile private var serviceForeground = false

        fun start(context: Context, openPort: Boolean): Boolean =
            runCatching {
                val shouldStart = synchronized(stateLock) {
                    requestedRunning = true
                    requestedOpenPort = openPort
                    if (startRequested) {
                        false
                    } else {
                        startRequested = true
                        true
                    }
                }
                if (shouldStart) {
                    val intent = Intent(context, LocalApiForegroundService::class.java)
                        .putExtra(EXTRA_OPEN_PORT, openPort)
                    ContextCompat.startForegroundService(context, intent)
                }
                true
            }.onFailure { error ->
                synchronized(stateLock) {
                    requestedRunning = false
                    startRequested = false
                }
                Log.e(TAG, "Unable to start local API foreground service", error)
            }.getOrDefault(false)

        /**
         * Returns true only after Android has accepted the service and the
         * notification-backed foreground promotion has completed.  Starting a
         * foreground service is asynchronous; callers must not expose a
         * listener that depends on the service until this becomes true.
         */
        fun isForegroundReady(): Boolean = synchronized(stateLock) {
            requestedRunning && serviceCreated && serviceForeground
        }

        fun stop(context: Context) {
            val shouldStop = synchronized(stateLock) {
                requestedRunning = false
                // If the start request has not reached onCreate yet, do not cancel
                // it. The service will promote itself first and then observe the
                // false desired state in onStartCommand(). This closes the Android
                // start/stop race that caused the process crash.
                serviceCreated || serviceForeground
            }
            if (!shouldStop) return
            runCatching {
                context.stopService(Intent(context, LocalApiForegroundService::class.java))
            }.onFailure { error ->
                Log.w(TAG, "Unable to stop local API foreground service", error)
            }
        }

        private fun isRequested(): Boolean = requestedRunning
    }
}
