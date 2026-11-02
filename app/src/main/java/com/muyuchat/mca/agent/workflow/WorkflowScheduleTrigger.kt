package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.runtime.workflow.WorkflowScheduleCalculator
import kotlinx.coroutines.CancellationException

sealed interface WorkflowTriggerOutcome {
    val definitionId: String
    val version: Long

    data class Seeded(
        override val definitionId: String,
        override val version: Long
    ) : WorkflowTriggerOutcome

    data class Idle(
        override val definitionId: String,
        override val version: Long
    ) : WorkflowTriggerOutcome

    data class Executed(
        override val definitionId: String,
        override val version: Long,
        val slotMillis: Long,
        val result: WorkflowExecutionResult
    ) : WorkflowTriggerOutcome

    data class Skipped(
        override val definitionId: String,
        override val version: Long,
        val reason: String
    ) : WorkflowTriggerOutcome

    data class Failed(
        override val definitionId: String,
        override val version: Long,
        val slotMillis: Long,
        val errorCode: String,
        val safeMessage: String
    ) : WorkflowTriggerOutcome
}

/**
 * Schedule trigger over [WorkflowExecutionService] (N22).
 *
 * Semantics: the first tick only **seeds** an uninitialized schedule (no historical fire); later
 * ticks claim at most the most recent missed slot and start that fixed definition version. The slot
 * is claimed before execution, so at-most-once is preserved even if the workflow fails or the
 * process dies; failures are reported, never silently retried in the same slot.
 */
class WorkflowScheduleTrigger(
    private val store: WorkflowScheduleStore,
    private val executor: suspend (WorkflowExecutionRequest, Long) -> WorkflowExecutionResult,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Deterministic run id for one (definition, version, slot). Production passes a checkpoint-safe
     * id derived from a digest; the default preserves the historical readable form for callers that
     * already guarantee a safe definition id.
     */
    private val runIdFactory: (definitionId: String, version: Long, slotMillis: Long) -> String =
        { definitionId, _, slotMillis -> "wfrun-" + definitionId + "-" + slotMillis }
) {
    suspend fun runDue(): List<WorkflowTriggerOutcome> {
        val now = clock()
        val records = store.load().sortedWith(compareBy({ it.definitionId }, { it.version }))
        val outcomes = ArrayList<WorkflowTriggerOutcome>(records.size)
        for (record in records) {
            if (!record.enabled) {
                outcomes += WorkflowTriggerOutcome.Skipped(
                    record.definitionId,
                    record.version,
                    "disabled"
                )
                continue
            }
            val last = record.lastDeliveredMillis
            if (last == null) {
                store.seed(record.definitionId, record.version, now)
                outcomes += WorkflowTriggerOutcome.Seeded(record.definitionId, record.version)
                continue
            }
            val slot = WorkflowScheduleCalculator.latestMissedOccurrence(record.schedule, last, now)
            if (slot == null) {
                outcomes += WorkflowTriggerOutcome.Idle(record.definitionId, record.version)
                continue
            }
            val slotKey = slot.toString()
            when (store.claim(record.definitionId, record.version, slotKey, slot)) {
                WorkflowScheduleClaim.Stale -> outcomes += WorkflowTriggerOutcome.Skipped(
                    record.definitionId,
                    record.version,
                    "slot already handled"
                )
                WorkflowScheduleClaim.NotFound -> outcomes += WorkflowTriggerOutcome.Skipped(
                    record.definitionId,
                    record.version,
                    "schedule disappeared"
                )
                WorkflowScheduleClaim.Claimed -> {
                    val result = try {
                        executor(
                            WorkflowExecutionRequest(
                                definitionId = record.definitionId,
                                version = record.version,
                                runId = runIdFactory(record.definitionId, record.version, slot),
                                agentTaskPlan = record.agentTask
                            ),
                            slot
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        outcomes += WorkflowTriggerOutcome.Failed(
                            record.definitionId,
                            record.version,
                            slot,
                            "WF_TRIGGER_FAILED",
                            "The scheduled workflow could not be started."
                        )
                        continue
                    }
                    outcomes += WorkflowTriggerOutcome.Executed(
                        record.definitionId,
                        record.version,
                        slot,
                        result
                    )
                }
            }
        }
        return outcomes
    }
}
