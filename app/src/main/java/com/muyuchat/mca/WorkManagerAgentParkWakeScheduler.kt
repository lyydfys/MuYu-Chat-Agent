package com.muyuchat.mca

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.muyuchat.mca.agent.workflow.AgentParkWakePlan
import com.muyuchat.mca.agent.workflow.AgentParkWakeScheduler
import java.util.concurrent.TimeUnit

/**
 * Android WorkManager adapter for durable Agent park wakes.
 *
 * One unique work name per run: a newer park token for the same run replaces the pending wake
 * instead of stacking duplicates. WorkManager may delay the wake; the token and the owner's
 * serialized wake validation decide whether anything happens.
 */
class WorkManagerAgentParkWakeScheduler(context: Context) : AgentParkWakeScheduler {
    private val appContext = context.applicationContext

    override fun schedule(runId: String, wakeId: String): Boolean = try {
        val request = OneTimeWorkRequestBuilder<AgentParkWakeWorker>()
            .setInitialDelay(WAKE_DELAY_MINUTES, TimeUnit.MINUTES)
            .setInputData(
                workDataOf(
                    AgentParkWakeWorker.KEY_RUN_ID to runId,
                    AgentParkWakeWorker.KEY_WAKE_ID to wakeId
                )
            )
            .addTag(TAG)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            AgentParkWakePlan.uniqueName(runId),
            ExistingWorkPolicy.REPLACE,
            request
        )
        true
    } catch (_: Throwable) {
        false
    }

    override fun cancel(runId: String) {
        runCatching {
            WorkManager.getInstance(appContext)
                .cancelUniqueWork(AgentParkWakePlan.uniqueName(runId))
        }
    }

    private companion object {
        const val TAG = "mca-agent-park-wake"
        const val WAKE_DELAY_MINUTES = 15L
    }
}
