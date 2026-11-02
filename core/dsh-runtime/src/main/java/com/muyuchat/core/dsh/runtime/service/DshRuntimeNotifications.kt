package com.muyuchat.core.dsh.runtime.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig

/**
 * Foreground notification for the `:dsh` host process.
 *
 * The notification carries no token, no origin and no session content: only the host phase.
 * The stop action targets [DshRuntimeService] with [DshRuntimeIpc.ACTION_STOP] and cannot touch
 * MCA's generation service or the Kotlin Agent.
 */
object DshRuntimeNotifications {

    fun ensureChannel(context: Context, config: DshRuntimeConfig) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            config.notificationChannelId,
            "DSH runtime",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "DeepSeek Harness on-device runtime"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, config: DshRuntimeConfig, snapshot: DshRuntimeSnapshot): Notification {
        val channel = NotificationChannel(
            config.notificationChannelId,
            "DSH runtime",
            NotificationManager.IMPORTANCE_LOW
        )
        val builder = Notification.Builder(context, config.notificationChannelId)
            .setContentTitle("DeepSeek Harness")
            .setContentText(DshNotificationText.forSnapshot(snapshot))
            .setSmallIcon(smallIcon(context))
            .setOngoing(snapshot.phase != DshRuntimePhase.STOPPED)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(launchIntent(context))
            .addAction(
                Notification.Action.Builder(
                    null,
                    "停止",
                    stopPendingIntent(context)
                ).build()
            )
        @Suppress("UNUSED_VARIABLE")
        val unused = channel
        return builder.build()
    }

    private fun smallIcon(context: Context): Int {
        val icon = context.applicationInfo.icon
        return if (icon != 0) icon else android.R.drawable.stat_sys_download
    }

    private fun launchIntent(context: Context): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun stopPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DshRuntimeService::class.java).setAction(DshRuntimeIpc.ACTION_STOP)
        return PendingIntent.getService(
            context,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}

/**
 * Pure notification-copy policy, kept separate from Android builders so JVM tests can pin the
 * user-visible text without Robolectric or an Android runtime.
 *
 * FAILED must never claim that the runtime stopped. STOP_TIMEOUT keeps the stop fence and asks
 * for a retry; every other failure names the concrete code. STARTING/READY/STOPPING/STOPPED keep
 * the existing wording.
 */
internal object DshNotificationText {

    fun forSnapshot(snapshot: DshRuntimeSnapshot): String = when (snapshot.phase) {
        DshRuntimePhase.STARTING -> "正在启动本机 DSH 运行时…"
        DshRuntimePhase.READY -> "DSH 运行时正在运行"
        DshRuntimePhase.STOPPING -> "正在停止 DSH 运行时…"
        DshRuntimePhase.FAILED -> failedText(snapshot)
        DshRuntimePhase.STOPPED -> "DSH 运行时已停止"
    }

    private fun failedText(snapshot: DshRuntimeSnapshot): String {
        val code = snapshot.failure?.code
        return if (code == DshFailureCode.STOP_TIMEOUT) {
            "停止未确认，请重试"
        } else {
            "DSH 运行时不可用（${code?.name ?: "FAILED"}）"
        }
    }
}

