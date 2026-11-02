package com.muyuchat.core.dsh.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `POST /notify` adapter over `NotificationManager`.
 *
 * Ownership: one instance per application process; it holds no Activity and uses the application
 * context, so it cannot leak a resumed surface. The content intent is the app's own launcher
 * intent resolved by `PackageManager` — never an arbitrary component supplied by a caller — which
 * keeps the notification's tap target same-origin.
 *
 * [state] separates the three negative reasons honestly: missing manifest declaration, runtime
 * permission denied, and the user having switched the app's notifications off.
 */
class AndroidDshNotificationPort(
    context: Context,
    private val smallIconResId: Int = android.R.drawable.ic_dialog_info,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : DshNotificationPort {

    private val appContext: Context = context.applicationContext

    private val notificationManager: NotificationManager? =
        appContext.getSystemService(NotificationManager::class.java)

    /** Ids this port posted, so the lifecycle cancel reports a real count instead of a guess. */
    private val postedIds: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    init {
        ensureChannel()
    }

    override fun state(): DshCapabilityState {
        if (AndroidDshPermissionChecks.notificationPermissionRequired()) {
            if (!AndroidDshPermissionChecks.declares(
                    appContext,
                    AndroidDshPermissionChecks.postNotificationsPermission()
                )
            ) {
                return DshCapabilityState.NOT_DECLARED
            }
            if (!AndroidDshPermissionChecks.runtimeGranted(
                    appContext,
                    AndroidDshPermissionChecks.postNotificationsPermission()
                )
            ) {
                return DshCapabilityState.DENIED
            }
        }
        if (notificationManager == null) return DshCapabilityState.UNSUPPORTED
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            return DshCapabilityState.NOT_ENABLED
        }
        return DshCapabilityState.GRANTED
    }

    override suspend fun post(
        title: String,
        text: String,
        notificationId: Int
    ): DshNotificationPostOutcome = withContext(dispatcher) {
        when (state()) {
            DshCapabilityState.GRANTED -> Unit
            DshCapabilityState.NOT_DECLARED -> return@withContext DshNotificationPostOutcome.Failed(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.PERMISSION_DENIED,
                    "应用未声明通知权限（POST_NOTIFICATIONS）"
                )
            )
            DshCapabilityState.NOT_ENABLED -> return@withContext DshNotificationPostOutcome.Failed(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.NOT_ENABLED,
                    "通知已被系统或用户关闭，请在系统设置中开启"
                )
            )
            DshCapabilityState.DENIED, DshCapabilityState.UNSUPPORTED ->
                return@withContext DshNotificationPostOutcome.Failed(
                    DshAndroidActionFailure.of(
                        DshAndroidActionErrorCode.PERMISSION_DENIED,
                        "未授予通知权限（POST_NOTIFICATIONS）"
                    )
                )
        }

        val launchIntent = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?: return@withContext DshNotificationPostOutcome.Failed(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.INTERNAL,
                    "本应用没有可打开的入口 Activity"
                )
            )
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            appContext,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(smallIconResId)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(contentIntent)
            .build()

        try {
            NotificationManagerCompat.from(appContext).notify(notificationId, notification)
            postedIds.add(notificationId)
            DshNotificationPostOutcome.Posted(notificationId)
        } catch (_: SecurityException) {
            DshNotificationPostOutcome.Failed(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.PERMISSION_DENIED,
                    "系统拒绝了通知（权限或通知渠道被禁用）"
                )
            )
        } catch (_: RuntimeException) {
            DshNotificationPostOutcome.Failed(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.INTERNAL,
                    "通知构建失败"
                )
            )
        }
    }

    override suspend fun cancel(notificationId: Int): Boolean = withContext(dispatcher) {
        val manager = notificationManager ?: return@withContext false
        manager.cancel(notificationId)
        postedIds.remove(notificationId)
        true
    }

    override suspend fun cancelAll(): Int = withContext(dispatcher) {
        val manager = notificationManager ?: return@withContext 0
        val owned = postedIds.toList()
        owned.forEach(manager::cancel)
        manager.cancelAll()
        postedIds.clear()
        owned.size
    }

    private fun ensureChannel() {
        val manager = notificationManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = CHANNEL_DESCRIPTION
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "dsh_agent"
        private const val CHANNEL_NAME = "DSH 通知"
        private const val CHANNEL_DESCRIPTION = "DeepSeek Harness 代理发送的完成与提醒通知"
    }
}
