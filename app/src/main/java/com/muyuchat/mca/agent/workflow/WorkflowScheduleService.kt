package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.runtime.workflow.WorkflowSchedule
import com.muyuchat.core.agent.runtime.workflow.WorkflowScheduleCalculator
import kotlinx.coroutines.CancellationException

object WorkflowScheduleErrorCodes {
    const val DEFINITION_NOT_FOUND = "WF_SCHEDULE_DEFINITION_NOT_FOUND"
    const val PLAN_INVALID = "WF_SCHEDULE_PLAN_INVALID"
    const val STORE_FAILED = "WF_SCHEDULE_STORE_FAILED"
}

data class WorkflowScheduleCommand(
    val definitionId: String,
    val version: Long,
    val schedule: WorkflowSchedule,
    val enabled: Boolean = true,
    /** Frozen model choice for AGENT_TASK nodes; null keeps them typed NOT_SUPPORTED. */
    val agentTask: WorkflowAgentTaskPlan? = null
) {
    init {
        require(definitionId.isNotBlank()) { "schedule definitionId must not be blank" }
        require(version >= 1L) { "schedule version must be positive" }
    }
}

sealed interface WorkflowScheduleOutcome {
    data class Saved(
        val record: WorkflowScheduleRecord,
        /** False means the row is durable but WorkManager could not install its wake. */
        val wakeArmed: Boolean
    ) : WorkflowScheduleOutcome

    data class Removed(
        val definitionId: String,
        val version: Long,
        val removed: Boolean
    ) : WorkflowScheduleOutcome

    data class Rejected(val code: String, val safeMessage: String) : WorkflowScheduleOutcome
}

/**
 * Production schedule administration over the durable schedule store and the shared entry facade.
 *
 * It is the only writer of schedule rows. A saved `AGENT_TASK` plan is the user's explicit model
 * choice; it is persisted with the row and reused by every later wake, so neither the chat page's
 * current selection nor a process restart can change which model an unattended run uses.
 *
 * The slot claim in [WorkflowScheduleStore] remains the at-most-once authority. WorkManager is only
 * a wake hint: a delayed, duplicated or replayed wake still goes through the same claim.
 */
class WorkflowScheduleService(
    private val store: WorkflowScheduleStore,
    private val definitions: AgentWorkflowDefinitionStore,
    private val facade: WorkflowTriggerFacade,
    private val wakes: WorkflowWakeScheduler,
    private val clock: () -> Long = System::currentTimeMillis
) : WorkflowWakeSource {
    suspend fun list(): List<WorkflowScheduleRecord> = store.load()

    suspend fun upsert(command: WorkflowScheduleCommand): WorkflowScheduleOutcome {
        val plan = command.agentTask
        if (plan != null && plan.policy.workflow != null) {
            return WorkflowScheduleOutcome.Rejected(
                WorkflowScheduleErrorCodes.PLAN_INVALID,
                "A scheduled agent task cannot pin another workflow definition."
            )
        }
        val definition = try {
            definitions.version(command.definitionId, command.version)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return WorkflowScheduleOutcome.Rejected(
                WorkflowScheduleErrorCodes.STORE_FAILED,
                "The workflow definition repository is unavailable."
            )
        } ?: return WorkflowScheduleOutcome.Rejected(
            WorkflowScheduleErrorCodes.DEFINITION_NOT_FOUND,
            "The workflow version is not published."
        )

        val existing = try {
            store.load().firstOrNull {
                it.definitionId == definition.definitionId && it.version == command.version
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return WorkflowScheduleOutcome.Rejected(
                WorkflowScheduleErrorCodes.STORE_FAILED,
                "The workflow schedule store is unavailable."
            )
        }
        val record = WorkflowScheduleRecord(
            definitionId = command.definitionId,
            version = command.version,
            schedule = command.schedule,
            enabled = command.enabled,
            // Editing a schedule must not replay slots that were already delivered before the edit.
            lastDeliveredMillis = existing?.lastDeliveredMillis,
            lastSlotKey = existing?.lastSlotKey,
            agentTask = command.agentTask
        )
        try {
            store.put(record)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return WorkflowScheduleOutcome.Rejected(
                WorkflowScheduleErrorCodes.STORE_FAILED,
                "The workflow schedule could not be saved."
            )
        }
        return WorkflowScheduleOutcome.Saved(record, armWakes(clock()))
    }

    suspend fun remove(definitionId: String, version: Long): WorkflowScheduleOutcome {
        val removed = try {
            store.remove(definitionId, version)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return WorkflowScheduleOutcome.Rejected(
                WorkflowScheduleErrorCodes.STORE_FAILED,
                "The workflow schedule could not be removed."
            )
        }
        wakes.cancel(definitionId, version)
        val anyEnabled = try {
            store.load().any { it.enabled }
        } catch (_: Throwable) {
            // The row is gone; a stale sweep is harmless because runDue sees no due rows.
            true
        }
        wakes.setSweepEnabled(anyEnabled)
        return WorkflowScheduleOutcome.Removed(definitionId, version, removed)
    }

    /**
     * Cold-start re-arm over the persisted rows. A force-stop clears WorkManager but not the
     * schedule rows, so the next process start must re-install every enabled wake and the sweep.
     * Re-arming never delivers a slot; the store's claim remains the at-most-once authority.
     */
    suspend fun rearm(): Boolean = armWakes(clock())

    /** Runs the claimed due slots and installs the next wake for every enabled row. */
    override suspend fun runDue(): List<WorkflowTriggerOutcome> {
        val outcomes = facade.scheduleTrigger(store).runDue()
        armWakes(clock())
        return outcomes
    }

    override suspend fun hasDue(): Boolean = hasDue(clock())

    /**
     * True when a tick would execute at least one slot. The worker uses this to decide whether a
     * foreground notification is warranted; seeding alone never runs a workflow.
     */
    suspend fun hasDue(nowMillis: Long): Boolean = store.load().any { record ->
        val last = record.lastDeliveredMillis ?: return@any false
        record.enabled &&
            WorkflowScheduleCalculator.latestMissedOccurrence(record.schedule, last, nowMillis) != null
    }

    private suspend fun armWakes(nowMillis: Long): Boolean {
        val records = try {
            store.load()
        } catch (_: Throwable) {
            return false
        }
        var armed = true
        records.forEach { record ->
            if (record.enabled) {
                armed = wakes.schedule(record, nowMillis) && armed
            } else {
                wakes.cancel(record.definitionId, record.version)
            }
        }
        return wakes.setSweepEnabled(records.any { it.enabled }) && armed
    }
}
