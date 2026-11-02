package com.muyuchat.mca.agent.workflow

import kotlinx.coroutines.CancellationException

/**
 * The narrow slice of schedule administration a wake needs.
 *
 * A Worker cannot upsert, remove or inspect credentials; it only asks whether a slot is due and
 * then claims/runs it. Keeping this interface narrow prevents the wake path from becoming a second
 * schedule admin surface.
 */
interface WorkflowWakeSource {
    suspend fun hasDue(): Boolean

    suspend fun runDue(): List<WorkflowTriggerOutcome>
}

internal sealed interface WorkflowWakeRunResult {
    data class Completed(val outcomes: List<WorkflowTriggerOutcome>) : WorkflowWakeRunResult

    data class Failed(val error: Throwable) : WorkflowWakeRunResult
}

/**
 * One scheduled wake, without Android or WorkManager types.
 *
 * [schedules] is always the process-scoped Agent root; this runner never constructs an engine,
 * assembly or scheduler of its own. [settle] is invoked exactly once on success, failure and
 * cancellation, which is what lets a worker-only process release its root without an Activity.
 */
internal class WorkflowWakeRunner(
    private val schedules: suspend () -> WorkflowWakeSource,
    private val settle: () -> Unit
) {
    suspend fun run(onDue: suspend () -> Unit = {}): WorkflowWakeRunResult {
        return try {
            val source = schedules()
            if (source.hasDue()) onDue()
            WorkflowWakeRunResult.Completed(source.runDue())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            WorkflowWakeRunResult.Failed(error)
        } finally {
            settle()
        }
    }
}
