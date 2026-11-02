package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.runtime.workflow.WorkflowSchedule
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

/** One durable workflow schedule: which definition version it starts and when it last ran. */
data class WorkflowScheduleRecord(
    val definitionId: String,
    val version: Long,
    val schedule: WorkflowSchedule,
    val enabled: Boolean = true,
    /** Null until the first tick seeds the schedule; seeds never execute the workflow. */
    val lastDeliveredMillis: Long? = null,
    val lastSlotKey: String? = null,
    /**
     * Frozen model plan an `AGENT_TASK` node starts with, persisted with the schedule so a wake
     * after process death cannot fall back to the chat page's current selection. Null means the
     * schedule has no authorized model: an `AGENT_TASK` node stays typed NOT_SUPPORTED.
     */
    val agentTask: WorkflowAgentTaskPlan? = null
) {
    init {
        require(definitionId.isNotBlank()) { "schedule definitionId must not be blank" }
        require(version >= 1L) { "schedule version must be positive" }
    }
}

sealed interface WorkflowScheduleClaim {
    data object Claimed : WorkflowScheduleClaim

    /** The slot is not newer than the last delivered one (already handled or clock rollback). */
    data object Stale : WorkflowScheduleClaim

    data object NotFound : WorkflowScheduleClaim
}

/**
 * Durable schedule rows plus an atomic latest-slot claim (N22).
 *
 * The claim is at-most-once per slot: it is committed **before** the workflow starts, so a crash
 * cannot double-fire a slot. A failed execution still consumes the slot; callers must surface the
 * failure instead of silently retrying.
 */
interface WorkflowScheduleStore {
    suspend fun load(): List<WorkflowScheduleRecord>

    suspend fun put(record: WorkflowScheduleRecord)

    /** Deletes one schedule; false when it did not exist. History and receipts are untouched. */
    suspend fun remove(definitionId: String, version: Long): Boolean

    /** Seeds an uninitialized schedule without executing it. */
    suspend fun seed(definitionId: String, version: Long, nowMillis: Long): Boolean

    suspend fun claim(
        definitionId: String,
        version: Long,
        slotKey: String,
        slotMillis: Long
    ): WorkflowScheduleClaim
}

internal object WorkflowScheduleZone {
    fun zone(text: String): ZoneId = ZoneId.of(text)

    fun day(text: String): DayOfWeek = DayOfWeek.valueOf(text)

    fun time(hour: Int, minute: Int): LocalTime = LocalTime.of(hour, minute)
}
