package com.muyuchat.mca.agent.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.mca.McaGenerationForegroundService
import com.muyuchat.mca.R

/**
 * Pure wire representation of the Agent notification stop action. Only runId + epoch are carried;
 * goal, prompt, file path, reasoning text, model key and approval details must never enter the Intent.
 */
data class AgentStopAction(
    val runId: String,
    val epoch: Long
) {
    init {
        AgentContract.requireId(runId, "runId")
        require(epoch > 0L) { "run epoch must be positive" }
    }
}

data class AgentStopActionExtras(
    val runId: String,
    val epoch: Long
)

sealed interface AgentStopActionDecodeResult {
    data class Decoded(val action: AgentStopAction) : AgentStopActionDecodeResult

    data class Rejected(val reason: AgentStopRejectionReason) : AgentStopActionDecodeResult
}

enum class AgentStopRejectionReason {
    MALFORMED_ACTION,
    NO_ACTIVE_RUN,
    DIFFERENT_RUN,
    STALE_EPOCH
}

sealed interface AgentStopRouteResult {
    data class Accepted(val key: AgentStopAction) : AgentStopRouteResult

    data class Rejected(val reason: AgentStopRejectionReason) : AgentStopRouteResult
}

/** Pure intent/epoch codec. Android Intent is a thin carrier around this object. */
object AgentStopActionCodec {
    fun encode(action: AgentStopAction): AgentStopActionExtras =
        AgentStopActionExtras(runId = action.runId, epoch = action.epoch)

    fun decode(runId: String?, epoch: Long?): AgentStopActionDecodeResult {
        if (runId.isNullOrBlank() || !AgentContract.isValidId(runId) || epoch == null || epoch <= 0L) {
            return AgentStopActionDecodeResult.Rejected(AgentStopRejectionReason.MALFORMED_ACTION)
        }
        return AgentStopActionDecodeResult.Decoded(AgentStopAction(runId = runId, epoch = epoch))
    }
}

/**
 * The only place a stale notification is rejected. Routing compares the full `(runId, epoch)` with
 * the owner's current run: a different run is rejected and an older epoch of the same run is stale.
 */
object AgentRunStopRouter {
    fun route(
        decoded: AgentStopActionDecodeResult,
        activeRun: AgentStopAction?
    ): AgentStopRouteResult {
        val action = when (decoded) {
            is AgentStopActionDecodeResult.Rejected ->
                return AgentStopRouteResult.Rejected(decoded.reason)

            is AgentStopActionDecodeResult.Decoded -> decoded.action
        }
        if (activeRun == null) {
            return AgentStopRouteResult.Rejected(AgentStopRejectionReason.NO_ACTIVE_RUN)
        }
        if (activeRun.runId != action.runId) {
            return AgentStopRouteResult.Rejected(AgentStopRejectionReason.DIFFERENT_RUN)
        }
        if (activeRun.epoch != action.epoch) {
            return AgentStopRouteResult.Rejected(AgentStopRejectionReason.STALE_EPOCH)
        }
        return AgentStopRouteResult.Accepted(action)
    }
}

data class AgentNotificationContent(
    val title: String,
    val text: String,
    val ongoing: Boolean
)

/**
 * Generic, privacy-safe notification copy. The function only accepts a state, so no task-specific
 * text can be introduced accidentally.
 */
object AgentRunNotificationText {
    private const val TITLE = "MCA Agent"

    fun forState(state: AgentRunState): AgentNotificationContent {
        val text = when (state) {
            AgentRunState.RUNNING -> "Agent 正在执行任务"
            AgentRunState.WAITING_APPROVAL -> "Agent 等待授权"
            AgentRunState.WAITING_RESOURCE,
            AgentRunState.WAITING_USER,
            AgentRunState.QUEUED -> "Agent 正在等待"

            AgentRunState.PAUSING,
            AgentRunState.STOPPING -> "Agent 正在停止"

            AgentRunState.PAUSED,
            AgentRunState.NEEDS_REVIEW,
            AgentRunState.INTERRUPTED -> "Agent 已暂停"

            AgentRunState.SUCCEEDED -> "Agent 已完成"

            AgentRunState.FAILED,
            AgentRunState.CANCELLED,
            AgentRunState.SUPERSEDED -> "Agent 已结束"
        }
        val ongoing = state == AgentRunState.RUNNING ||
            state == AgentRunState.PAUSING ||
            state == AgentRunState.STOPPING
        return AgentNotificationContent(title = TITLE, text = text, ongoing = ongoing)
    }
}

sealed interface AgentNotificationResult {
    data class Updated(val state: AgentRunState, val stopActionAvailable: Boolean) : AgentNotificationResult

    data object Cancelled : AgentNotificationResult

    data object AlreadyClear : AgentNotificationResult

    data class Rejected(val reason: AgentStopRejectionReason) : AgentNotificationResult

    data class Unavailable(val error: AgentError) : AgentNotificationResult
}

/**
 * Builds/updates/cancels the single Agent run notification. It is separate from the foreground
 * service notification: the FGS lease comes from [AgentForegroundLease], while this notification is
 * an optional user-facing status/stop surface. A notification failure never aborts a run.
 */
class AgentRunNotificationController(
    context: Context
) {
    private val appContext: Context = context.applicationContext
    private val lock = Any()
    private var activeRun: AgentStopAction? = null

    fun update(
        action: AgentStopAction,
        state: AgentRunState
    ): AgentNotificationResult {
        synchronized(lock) { activeRun = action }
        val content = AgentRunNotificationText.forState(state)
        val stopIntent = stopPendingIntent(action)
        return try {
            ensureChannel()
            val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_mca_api)
                .setContentTitle(content.title)
                .setContentText(content.text)
                .setOngoing(content.ongoing)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(false)
            if (stopIntent != null) {
                builder.addAction(0, "停止", stopIntent)
            }
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, builder.build())
            AgentNotificationResult.Updated(state = state, stopActionAvailable = stopIntent != null)
        } catch (failure: Throwable) {
            AgentNotificationResult.Unavailable(notificationUnavailableError(action.runId, failure))
        }
    }

    fun cancel(action: AgentStopAction): AgentNotificationResult {
        val current = synchronized(lock) { activeRun }
        when (val routed = AgentRunStopRouter.route(
            AgentStopActionDecodeResult.Decoded(action),
            current
        )) {
            is AgentStopRouteResult.Rejected -> return AgentNotificationResult.Rejected(routed.reason)
            is AgentStopRouteResult.Accepted -> Unit
        }
        return try {
            NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
            synchronized(lock) { activeRun = null }
            AgentNotificationResult.Cancelled
        } catch (failure: Throwable) {
            AgentNotificationResult.Unavailable(notificationUnavailableError(action.runId, failure))
        }
    }

    /** Clears the notification regardless of run identity; used when the process owner is torn down. */
    fun clear(): Boolean = try {
        NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
        synchronized(lock) { activeRun = null }
        true
    } catch (_: Throwable) {
        false
    }

    private fun stopPendingIntent(action: AgentStopAction): PendingIntent? = try {
        val intent = Intent(appContext, McaGenerationForegroundService::class.java)
            .setAction(AgentRunNotificationProtocol.ACTION_STOP_RUN)
            .putExtra(AgentRunNotificationProtocol.EXTRA_RUN_ID, action.runId)
            .putExtra(AgentRunNotificationProtocol.EXTRA_RUN_EPOCH, action.epoch)
        PendingIntent.getService(
            appContext,
            REQUEST_CODE_BASE + (action.runId.hashCode() and 0x0FFF),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    } catch (_: Throwable) {
        null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Agent 任务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Agent 任务的后台运行状态"
                setShowBadge(false)
            }
        )
    }

    private fun notificationUnavailableError(runId: String, failure: Throwable): AgentError =
        platformError(
            code = AgentPlatformErrorCodes.NOTIFICATION_UNAVAILABLE,
            correlationId = runId,
            causeRef = "agent_notification_failed:${failure.javaClass.simpleName}",
            safeMessage = "Agent 任务通知不可用，任务继续在应用内运行。"
        )

    companion object {
        const val CHANNEL_ID = "mca_agent_runs"
        const val NOTIFICATION_ID = 11439
        private const val REQUEST_CODE_BASE = 41000
    }
}

/**
 * Android wire constants and Intent decoder used by the existing generation service after the
 * reported minimal service hunk. The pure codec above is what unit tests exercise.
 */
object AgentRunNotificationProtocol {
    const val ACTION_STOP_RUN = "com.muyuchat.mca.agent.action.STOP_RUN"
    const val EXTRA_RUN_ID = "com.muyuchat.mca.agent.extra.RUN_ID"
    const val EXTRA_RUN_EPOCH = "com.muyuchat.mca.agent.extra.RUN_EPOCH"

    fun decodeStopIntent(intent: Intent?): AgentStopActionDecodeResult {
        val safeIntent = intent
            ?: return AgentStopActionDecodeResult.Rejected(AgentStopRejectionReason.MALFORMED_ACTION)
        if (safeIntent.action != ACTION_STOP_RUN) {
            return AgentStopActionDecodeResult.Rejected(AgentStopRejectionReason.MALFORMED_ACTION)
        }
        val runId = safeIntent.getStringExtra(EXTRA_RUN_ID)
        val epoch = if (safeIntent.hasExtra(EXTRA_RUN_EPOCH)) {
            safeIntent.getLongExtra(EXTRA_RUN_EPOCH, -1L)
        } else {
            null
        }
        return AgentStopActionCodec.decode(runId, epoch)
    }
}
