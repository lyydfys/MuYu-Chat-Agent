package com.muyuchat.mca

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.muyuchat.mca.agent.workflow.WorkflowScheduleRecord
import com.muyuchat.mca.agent.workflow.WorkflowWakePlan
import com.muyuchat.mca.agent.workflow.WorkflowWakeScheduler
import java.util.concurrent.TimeUnit

/**
 * Android WorkManager adapter for [WorkflowWakeScheduler].
 *
 * This class lives outside the platform-free workflow package: the schedule core only knows a
 * narrow interface, while the Android wake hint and its periodic safety net stay in the app root.
 * Every operation is best-effort and reports failure; no WorkManager exception becomes a fake
 * "armed" result.
 */
class WorkManagerWorkflowWakeScheduler(context: Context) : WorkflowWakeScheduler {
    private val appContext = context.applicationContext

    override fun schedule(record: WorkflowScheduleRecord, nowMillis: Long): Boolean {
        val name = WorkflowWakePlan.uniqueName(record.definitionId, record.version)
        val wake = WorkflowWakePlan.next(record, nowMillis)
        return try {
            val manager = WorkManager.getInstance(appContext)
            if (wake == null) {
                manager.cancelUniqueWork(name)
            } else {
                val builder = OneTimeWorkRequestBuilder<WorkflowWakeWorker>().addTag(TAG)
                // WorkManager refuses a request that is both expedited and delayed
                // ("Expedited jobs cannot be delayed", WorkRequest.build), so the two branches are
                // exclusive. A wake that is already due is expedited, which is also what lets it
                // post its foreground notification off-screen; a deferred wake is ordinary
                // one-time work, and the worker tolerates a refused promotion instead of skipping
                // the due claim.
                if (wake.delayMillis > 0L) {
                    builder.setInitialDelay(wake.delayMillis, TimeUnit.MILLISECONDS)
                } else {
                    builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
                manager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, builder.build())
            }
            Log.d(LOG_TAG, "wake $name enqueue accepted, delayMs=${wake?.delayMillis ?: -1L}")
            true
        } catch (failure: Throwable) {
            // A silently swallowed cause made a real re-arm failure indistinguishable from a
            // harness artifact across three device runs; the caller only sees `false`.
            Log.w(LOG_TAG, "wake $name could not be installed", failure)
            false
        }
    }

    override fun cancel(definitionId: String, version: Long) {
        val name = WorkflowWakePlan.uniqueName(definitionId, version)
        runCatching {
            WorkManager.getInstance(appContext).cancelUniqueWork(name)
        }.onFailure { failure ->
            Log.w(LOG_TAG, "wake $name could not be cancelled", failure)
        }
    }

    override fun setSweepEnabled(enabled: Boolean): Boolean = try {
        val manager = WorkManager.getInstance(appContext)
        if (enabled) {
            manager.enqueueUniquePeriodicWork(
                SWEEP_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WorkflowWakeWorker>(
                    SWEEP_MINUTES,
                    TimeUnit.MINUTES
                ).addTag(TAG).build()
            )
        } else {
            manager.cancelUniqueWork(SWEEP_NAME)
        }
        true
    } catch (failure: Throwable) {
        Log.w(LOG_TAG, "periodic sweep could not be ${if (enabled) "armed" else "cancelled"}", failure)
        false
    }

    companion object {
        const val TAG = "mca-workflow-wake"
        const val SWEEP_NAME = "mca-workflow-wake-sweep"

        /** Logcat tag for wake installation failures; the work tag above is not a log tag. */
        const val LOG_TAG = "WorkflowWake"

        /** WorkManager's minimum periodic interval; the calculator itself never goes below 15 min. */
        const val SWEEP_MINUTES = 15L
    }
}
