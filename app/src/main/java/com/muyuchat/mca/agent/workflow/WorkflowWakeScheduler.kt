package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import com.muyuchat.core.agent.runtime.workflow.WorkflowScheduleCalculator
import com.muyuchat.core.agent.runtime.workflow.WorkflowWake

/**
 * Durable wake installation for scheduled workflow definitions.
 *
 * The scheduler owns no execution: it only asks the platform scheduler to wake
 * [com.muyuchat.mca.WorkflowWakeWorker], which runs the due schedule through the process root.
 * Returning false means the schedule is persisted but this process could not install its wake;
 * callers must surface that instead of claiming unattended execution is armed.
 */
interface WorkflowWakeScheduler {
    fun schedule(record: WorkflowScheduleRecord, nowMillis: Long): Boolean

    fun cancel(definitionId: String, version: Long)

    /** A periodic safety net catches up slots lost to process death or a stopped wake. */
    fun setSweepEnabled(enabled: Boolean): Boolean
}

/** Pure wake arithmetic so the policy can be tested without a platform scheduler. */
internal object WorkflowWakePlan {
    fun next(record: WorkflowScheduleRecord, nowMillis: Long): WorkflowWake? =
        if (!record.enabled) null else WorkflowScheduleCalculator.nextWake(record.schedule, nowMillis)

    fun uniqueName(definitionId: String, version: Long): String =
        "workflow-wake-" + AgentHashes.sha256HexUtf8("$definitionId@$version").take(32)
}
