package com.muyuchat.mca

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.muyuchat.mca.agent.workflow.AgentParkWakeRunResult
import com.muyuchat.mca.agent.workflow.AgentParkWakeRunner

/**
 * WorkManager wake for one durable Agent park.
 *
 * It is deliberately thin: the worker borrows the process-scoped root, reconciles startup and asks
 * the owner to validate the exact `(runId, wakeId)` token. It never constructs an engine, catalog
 * or loop. A still-parked or already-consumed token is a successful wake (nothing to do); only an
 * infrastructure failure becomes a retryable WorkManager failure.
 */
class AgentParkWakeWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? McaApplication
            ?: return Result.failure(workDataOf(ERROR to "MCA application is unavailable."))
        val runId = inputData.getString(KEY_RUN_ID)
            ?: return Result.failure(workDataOf(ERROR to "The park wake has no run id."))
        val wakeId = inputData.getString(KEY_WAKE_ID)
            ?: return Result.failure(workDataOf(ERROR to "The park wake has no wake id."))
        app.beginBackgroundWork()
        val runner = AgentParkWakeRunner(
            wake = { app.processRuntimeOwner.wakeParkedRun(runId, wakeId) },
            settle = { app.finishBackgroundWork() }
        )
        return when (val result = runner.run()) {
            is AgentParkWakeRunResult.Completed -> Result.success(
                workDataOf(OUTCOME to result.outcome::class.simpleName.orEmpty())
            )

            is AgentParkWakeRunResult.Failed -> Result.failure(
                workDataOf(ERROR to "Park wake failed: ${result.error.message.orEmpty().take(300)}")
            )
        }
    }

    companion object {
        const val KEY_RUN_ID = "runId"
        const val KEY_WAKE_ID = "wakeId"
        const val OUTCOME = "outcome"
        private const val ERROR = "error"
    }
}
