package com.muyuchat.mca

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.muyuchat.mca.agent.workflow.WorkflowTriggerOutcome
import com.muyuchat.mca.agent.workflow.WorkflowWakeRunResult
import com.muyuchat.mca.agent.workflow.WorkflowWakeRunner

/**
 * WorkManager wake for workflow schedules.
 *
 * It is deliberately thin: the worker obtains the process-scoped Agent root, never builds an
 * engine, catalog or second scheduler, and delegates the at-most-once slot claim to
 * [com.muyuchat.mca.agent.workflow.WorkflowScheduleService]. WorkManager may delay a wake; the
 * schedule claim and the periodic sweep decide what actually runs.
 *
 * A foreground notification is posted only when a slot is due, so an empty 15-minute sweep does
 * not surface UI. Long-running Agent nodes then rely on the existing Agent foreground lease.
 */
class WorkflowWakeWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {
    /**
     * Required by expedited work before API 31, where WorkManager posts the notification itself.
     * Keeping the same [ForegroundInfo] the worker would otherwise request means the user-visible
     * "scheduled workflow is running" surface is identical on both paths.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo = foreground()

    override suspend fun doWork(): Result {
        val app = applicationContext as? McaApplication
            ?: return Result.failure(workDataOf(ERROR to "MCA application is unavailable."))
        // Registered before the root may be created: a concurrent wake/sweep keeps it alive.
        app.beginBackgroundWork()
        val runner = WorkflowWakeRunner(
            schedules = { app.processRuntimeOwner.workflowSchedules() },
            settle = { app.finishBackgroundWork() }
        )
        return when (val result = runner.run { promoteToForeground() }) {
            is WorkflowWakeRunResult.Completed -> Result.success(outcomeCounts(result.outcomes))
            is WorkflowWakeRunResult.Failed -> Result.failure(
                workDataOf(ERROR to "Scheduled workflow wake failed: ${result.error.message.orEmpty().take(300)}")
            )
        }
    }

    private fun outcomeCounts(outcomes: List<WorkflowTriggerOutcome>): androidx.work.Data =
        workDataOf(
            "seeded" to outcomes.count { it is WorkflowTriggerOutcome.Seeded },
            "executed" to outcomes.count { it is WorkflowTriggerOutcome.Executed },
            "failed" to outcomes.count { it is WorkflowTriggerOutcome.Failed },
            "skipped" to outcomes.count { it is WorkflowTriggerOutcome.Skipped }
        )

    /**
     * Best effort, never a precondition. A backgrounded process may be refused the WorkManager
     * foreground service ("startForegroundService() not allowed ... mAllowStartForeground false");
     * the denial concerns the notification only. Letting it abort the wake skips `runDue()`, so the
     * due slot is never claimed and a backgrounded device silently stops delivering schedules.
     */
    private suspend fun promoteToForeground() {
        runCatching { setForeground(foreground()) }.onFailure { error ->
            Log.w(TAG, "scheduled wake continues without a foreground notification", error)
        }
    }

    private fun foreground(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "定时工作流", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("MCA 定时工作流")
            .setContentText("正在运行计划任务…")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val TAG = "WorkflowWakeWorker"
        const val CHANNEL = "mca-workflow-schedules"
        const val NOTIFICATION_ID = 0x4d4341
        const val ERROR = "error"
    }
}
