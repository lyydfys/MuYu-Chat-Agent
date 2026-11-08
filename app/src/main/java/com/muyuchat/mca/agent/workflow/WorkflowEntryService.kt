package com.muyuchat.mca.agent.workflow

import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Manual and scheduled entry points for one published workflow version (N20/N22).
 *
 * Both paths share the same execution port and record bounded run history: STARTED before the run,
 * then COMPLETED/FAILED/REJECTED with the finish time. A transient history write failure never
 * changes the execution result; the history store itself still fails closed on corrupt data. This is
 * an entry facade, not a new scheduler.
 *
 * [agentTask] is the frozen, credential-free model plan an `AGENT_TASK` node starts its child task
 * with; [runId] lets a caller replay the exact same checkpointed run (the receipt facade uses this).
 */
class WorkflowEntryService(
    private val executor: suspend (WorkflowExecutionRequest) -> WorkflowExecutionResult,
    private val history: WorkflowRunHistoryStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val runIdFactory: () -> String = { "wfrun-" + UUID.randomUUID() }
) {
    suspend fun runManual(
        definitionId: String,
        version: Long,
        agentTask: WorkflowAgentTaskPlan? = null,
        runId: String? = null,
        toolScope: WorkflowToolScope? = null
    ): WorkflowExecutionResult = run(
        request = WorkflowExecutionRequest(
            definitionId,
            version,
            runId = runId,
            agentTaskPlan = agentTask,
            toolScope = toolScope
        ),
        trigger = WorkflowTriggerKind.MANUAL,
        slotMillis = null
    )

    suspend fun runScheduled(
        definitionId: String,
        version: Long,
        slotMillis: Long,
        agentTask: WorkflowAgentTaskPlan? = null,
        runId: String? = null,
        toolScope: WorkflowToolScope? = null
    ): WorkflowExecutionResult = run(
        request = WorkflowExecutionRequest(
            definitionId,
            version,
            runId = runId,
            agentTaskPlan = agentTask,
            toolScope = toolScope
        ),
        trigger = WorkflowTriggerKind.SCHEDULE,
        slotMillis = slotMillis
    )

    /**
     * Entry for the registered intent trigger. It has no slot: two different calls are two
     * intentional runs, and replay protection is the caller's command id (see the facade).
     */
    suspend fun runIntent(
        definitionId: String,
        version: Long,
        agentTask: WorkflowAgentTaskPlan? = null,
        runId: String? = null,
        toolScope: WorkflowToolScope? = null
    ): WorkflowExecutionResult = run(
        request = WorkflowExecutionRequest(
            definitionId,
            version,
            runId = runId,
            agentTaskPlan = agentTask,
            toolScope = toolScope
        ),
        trigger = WorkflowTriggerKind.INTENT,
        slotMillis = null
    )

    private suspend fun run(
        request: WorkflowExecutionRequest,
        trigger: WorkflowTriggerKind,
        slotMillis: Long?
    ): WorkflowExecutionResult {
        val runId = request.runId ?: runIdFactory()
        val startedAt = clock()
        val started = WorkflowRunHistoryEntry(
            runId = runId,
            definitionId = request.definitionId,
            version = request.version,
            trigger = trigger,
            slotMillis = slotMillis,
            startedAtMillis = startedAt,
            status = WorkflowRunStatus.STARTED
        )
        writeHistory(started)
        val result = try {
            executor(request.copy(runId = runId))
        } catch (cancelled: CancellationException) {
            writeHistory(
                started.copy(
                    finishedAtMillis = clock(),
                    status = WorkflowRunStatus.FAILED,
                    errorCode = "WF_ENTRY_CANCELLED"
                )
            )
            throw cancelled
        } catch (_: Exception) {
            writeHistory(
                started.copy(
                    finishedAtMillis = clock(),
                    status = WorkflowRunStatus.FAILED,
                    errorCode = "WF_ENTRY_FAILED"
                )
            )
            return WorkflowExecutionResult.Rejected(
                runId = runId,
                errorCode = "WF_ENTRY_FAILED",
                safeMessage = "The workflow entry point could not start the run."
            )
        }
        writeHistory(terminal(started, result))
        return result
    }

    private fun terminal(
        started: WorkflowRunHistoryEntry,
        result: WorkflowExecutionResult
    ): WorkflowRunHistoryEntry = when (result) {
        is WorkflowExecutionResult.Completed -> started.copy(
            finishedAtMillis = clock(),
            status = WorkflowRunStatus.COMPLETED
        )
        is WorkflowExecutionResult.Failed -> started.copy(
            finishedAtMillis = clock(),
            status = WorkflowRunStatus.FAILED,
            errorCode = result.errorCode
        )
        is WorkflowExecutionResult.Rejected -> started.copy(
            finishedAtMillis = clock(),
            status = WorkflowRunStatus.REJECTED,
            errorCode = result.errorCode
        )
        // The run is still alive inside the owner; history keeps STARTED and names the real wait.
        is WorkflowExecutionResult.Suspended -> started.copy(
            errorCode = "WF_WAITING_APPROVAL"
        )
        is WorkflowExecutionResult.SuspendedForUser -> started.copy(
            errorCode = "WF_WAITING_USER"
        )
    }

    private suspend fun writeHistory(entry: WorkflowRunHistoryEntry) {
        try {
            history.put(entry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // History is evidence, not the state machine: a store failure must not fake or abort a
            // run that already started. The store's strict decoder still prevents silent resets.
        }
    }
}
