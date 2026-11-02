package com.muyuchat.core.agent.contract

/**
 * Closed set of durable domain transitions. A transition is validated as a whole and committed
 * transactionally: the journal never accepts arbitrary SQL, reducer lambdas or partial records.
 */
sealed interface AgentTransition {
    val commandId: String
    val taskId: String?
    val runId: String?
    val expectedRevision: Long?
}

/** Creates the task only; the first run is started by a separate idempotent command. */
data class CreateTaskTransition(
    override val commandId: String,
    val task: AgentTask
) : AgentTransition {
    override val taskId: String = task.taskId
    override val runId: String? = null
    override val expectedRevision: Long? = null
}

/** Creates a run and, when continuing, supersedes the previous run in the same transaction. */
data class CreateRunTransition(
    override val commandId: String,
    val run: AgentRun,
    val taskRevision: Long,
    val taskDisplay: AgentDisplayStatus,
    val successorOfRunId: String? = null
) : AgentTransition {
    override val taskId: String = run.taskId
    override val runId: String = run.runId
    override val expectedRevision: Long = taskRevision
}

/** Full run CAS update plus the transactional task display summary. */
data class UpdateRunTransition(
    override val commandId: String,
    val run: AgentRun,
    val taskDisplay: AgentDisplayStatus,
    override val expectedRevision: Long
) : AgentTransition {
    override val taskId: String = run.taskId
    override val runId: String = run.runId
}

/** Appends one or more events and advances run.lastSequence in the same commit. */
data class AppendEventsTransition(
    override val commandId: String,
    val runIdValue: String,
    val events: List<AgentEvent>,
    override val expectedRevision: Long?
) : AgentTransition {
    override val taskId: String? = null
    override val runId: String = runIdValue
}

data class UpsertStepTransition(
    override val commandId: String,
    val step: AgentStep,
    override val expectedRevision: Long
) : AgentTransition {
    override val taskId: String? = null
    override val runId: String = step.runId
}

data class UpsertAttemptTransition(
    override val commandId: String,
    val attempt: AgentModelAttempt,
    override val expectedRevision: Long
) : AgentTransition {
    override val taskId: String? = null
    override val runId: String = attempt.runId
}

data class UpsertInvocationTransition(
    override val commandId: String,
    val invocation: ToolInvocation,
    override val expectedRevision: Long
) : AgentTransition {
    override val taskId: String? = null
    override val runId: String = invocation.runId
}

data class UpsertApprovalTransition(
    override val commandId: String,
    val approval: AgentApproval,
    override val expectedRevision: Long
) : AgentTransition {
    override val taskId: String? = null
    override val runId: String? = null
}

/** One-shot approval consumption. [nextRun] is the state transition it unblocks, if any. */
data class ConsumeApprovalTransition(
    override val commandId: String,
    val approvalId: String,
    val consumedAtMillis: Long,
    val nextRun: AgentRun?,
    val taskDisplay: AgentDisplayStatus?,
    override val expectedRevision: Long,
    /** DENY must be persisted as DENIED, not mislabelled ALLOW_ONCE. */
    val decision: AgentApprovalDecision = AgentApprovalDecision.ALLOW_ONCE
) : AgentTransition {
    override val taskId: String? = nextRun?.taskId
    override val runId: String? = nextRun?.runId
}

data class UpsertArtifactTransition(
    override val commandId: String,
    val artifact: AgentArtifact,
    val commit: AgentArtifactCommit?,
    override val expectedRevision: Long
) : AgentTransition {
    override val taskId: String = artifact.taskId
    override val runId: String? = null
}

data class AskQuestionTransition(
    override val commandId: String,
    val question: AgentQuestion,
    val nextRun: AgentRun,
    val taskDisplay: AgentDisplayStatus,
    override val expectedRevision: Long
) : AgentTransition {
    override val taskId: String = nextRun.taskId
    override val runId: String = nextRun.runId
}

data class AnswerQuestionTransition(
    override val commandId: String,
    val questionId: String,
    val answer: String,
    val answeredAtMillis: Long,
    val nextRun: AgentRun,
    val taskDisplay: AgentDisplayStatus,
    override val expectedRevision: Long
) : AgentTransition {
    override val taskId: String = nextRun.taskId
    override val runId: String = nextRun.runId
}

/** Marks an old terminal run superseded without creating a run (recovery/cleanup path). */
data class SupersedeRunTransition(
    override val commandId: String,
    val previousRunId: String,
    val successorRunId: String,
    val updatedRun: AgentRun,
    override val expectedRevision: Long
) : AgentTransition {
    override val taskId: String = updatedRun.taskId
    override val runId: String = previousRunId
}

data class AgentCommitResult(
    val appliedRevision: Long,
    val taskRevision: Long? = null,
    val runRevision: Long? = null,
    val eventSequences: List<Long> = emptyList(),
    val idempotentReplay: Boolean = false
)

/**
 * Transactional persistence boundary. Room is the V1 implementation; deterministic tests use an
 * in-memory implementation with the same atomicity and idempotency contract.
 */
interface AgentJournal {
    suspend fun readRun(runId: String): AgentRunSnapshot?

    /** Non-terminal runs for explicit startup recovery; never auto-resumes execution. */
    suspend fun listNonTerminalRuns(limit: Int): List<AgentRunSnapshot>

    suspend fun readEvents(runId: String, afterSequence: Long, limit: Int): AgentEventPage

    suspend fun commit(command: AgentTransition): AgentCommitResult
}
