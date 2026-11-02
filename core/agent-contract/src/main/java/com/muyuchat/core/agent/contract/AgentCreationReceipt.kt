package com.muyuchat.core.agent.contract

/**
 * Stable, persisted answer to "which task did this create command create?" (N01 / DESIGN 3.2).
 *
 * The mapping `creationCommandId -> taskId/workspaceId` is committed in the same transaction as the
 * task and its workspace grant. Callers (Agent page, workflow launcher, triggers) must use this
 * receipt instead of scanning unseen ids or matching goal text.
 */
data class AgentCreationReceipt(
    val creationCommandId: String,
    val taskId: String,
    val workspaceId: String,
    val appliedRevision: Long,
    val idempotentReplay: Boolean,
    val schemaVersion: Int = AgentContract.SCHEMA_VERSION
) {
    init {
        AgentContract.requireId(creationCommandId, "creationCommandId")
        AgentContract.requireId(taskId, "taskId")
        AgentContract.requireId(workspaceId, "workspaceId")
        require(appliedRevision >= 0L) { "appliedRevision must not be negative" }
    }
}

/**
 * Durable task anchor written next to a task row. It can be replayed from Room after a process
 * restart; when no anchor can be proven the caller receives
 * [AgentErrorCodes.TASK_ANCHOR_UNAVAILABLE] rather than a guessed task.
 */
data class AgentTaskAnchor(
    val commandId: String,
    val taskId: String,
    val workspaceId: String,
    val commandFingerprint: String,
    val createdAtMillis: Long,
    val schemaVersion: Int = AgentContract.SCHEMA_VERSION
) {
    init {
        AgentContract.requireId(commandId, "commandId")
        AgentContract.requireId(taskId, "taskId")
        AgentContract.requireId(workspaceId, "workspaceId")
        AgentContract.requireNotBlank(commandFingerprint, "commandFingerprint")
        require(createdAtMillis >= 0L) { "createdAtMillis must not be negative" }
    }
}
