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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.muyuchat.mca.R

class LocalApiForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val openPort = intent?.getBooleanExtra(EXTRA_OPEN_PORT, false) == true
        val notification = buildNotification(openPort)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
            description = "MCA 本地 API 保活通知"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "mca_local_api"
        private const val NOTIFICATION_ID = 11435
        private const val EXTRA_OPEN_PORT = "open_port"

        fun start(context: Context, openPort: Boolean) {
            val intent = Intent(context, LocalApiForegroundService::class.java)
                .putExtra(EXTRA_OPEN_PORT, openPort)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocalApiForegroundService::class.java))
        }
    }
}
