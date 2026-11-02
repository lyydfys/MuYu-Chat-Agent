package com.muyuchat.core.agent.contract

/**
 * Append-only audit/timeline entry. The relational facts stay authoritative; events are committed
 * in the same transaction as the state change they describe (architecture 10.1).
 */
data class AgentEvent(
    val eventId: String,
    val runId: String,
    val sequence: Long,
    val type: String,
    val createdAtMillis: Long,
    val commandId: String? = null,
    val causationId: String? = null,
    val redactedPayloadRef: String? = null,
    val summary: String? = null,
    val version: Int = 1,
    val schemaVersion: Int = AgentContract.SCHEMA_VERSION
) {
    init {
        AgentContract.requireId(eventId, "eventId")
        AgentContract.requireId(runId, "runId")
        require(sequence > 0L) { "event sequence must be positive" }
        AgentContract.requireNotBlank(type, "event type")
        commandId?.let { AgentContract.requireId(it, "commandId") }
        causationId?.let { AgentContract.requireId(it, "causationId") }
        summary?.let {
            require(it.toByteArray(Charsets.UTF_8).size <= 4 * 1024) { "event summary exceeds 4 KiB" }
        }
    }
}

data class AgentEventPage(
    val events: List<AgentEvent>,
    val nextSequence: Long? = null
)

/** Stable event type names. Values are persisted and must not be renamed without migration. */
object AgentEventTypes {
    const val TASK_CREATED = "task.created"
    const val RUN_QUEUED = "run.queued"
    const val RUN_STATE_CHANGED = "run.state_changed"
    const val RUN_SUPERSEDED = "run.superseded"
    const val MODEL_ATTEMPT_STARTED = "model.attempt_started"
    const val MODEL_ATTEMPT_SETTLED = "model.attempt_settled"
    const val PROTOCOL_ERROR = "protocol.error"
    const val TOOL_PROPOSED = "tool.proposed"
    const val TOOL_APPROVAL_REQUESTED = "tool.approval_requested"
    const val TOOL_APPROVAL_RESOLVED = "tool.approval_resolved"
    const val TOOL_STARTED = "tool.started"
    const val TOOL_RESULT = "tool.result"
    const val QUESTION_ASKED = "user.question_asked"
    const val QUESTION_ANSWERED = "user.question_answered"
    const val ARTIFACT_COMMITTED = "artifact.committed"
    /** The immutable catalog identity (hash + tool keys) the run started with (N04). */
    const val TOOL_CATALOG_RESOLVED = "tool.catalog_resolved"

    /** The execution rebuilt the exact recorded catalog surface after drift (N04). */
    const val TOOL_CATALOG_RESTORED = "tool.catalog_restored"
    const val BUDGET_PAUSED = "budget.paused"
    const val RUN_INTERRUPTED = "run.interrupted"
    const val RUN_TERMINAL = "run.terminal"
}
