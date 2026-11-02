package com.muyuchat.core.agent.contract

/** One model generation attempt inside a run; every attempt uses a never-reused request id. */
data class AgentModelAttempt(
    val attemptId: String,
    val runId: String,
    val stepIndex: Int,
    val ordinal: Int,
    val backendRequestId: String,
    val backendIdentityRef: String?,
    val bindingFingerprint: String,
    val startedAtMillis: Long,
    val terminal: AgentAttemptTerminal,
    val finishedAtMillis: Long? = null,
    val usageJson: String? = null,
    val schemaVersion: Int = AgentContract.SCHEMA_VERSION
) {
    init {
        AgentContract.requireId(attemptId, "attemptId")
        AgentContract.requireId(runId, "runId")
        require(stepIndex >= 0) { "stepIndex must not be negative" }
        require(ordinal >= 1) { "attempt ordinal must be >= 1" }
        AgentContract.requireId(backendRequestId, "backendRequestId")
    }
}

enum class AgentAttemptTerminal {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    STOPPING,
    UNKNOWN
}

enum class AgentStepKind { MODEL, TOOL, USER_QUESTION, APPROVAL }

enum class AgentStepStatus {
    PENDING,
    RUNNING,
    WAITING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    NOT_EXECUTED,
    UNKNOWN
}

data class AgentStep(
    val stepId: String,
    val runId: String,
    val index: Int,
    val kind: AgentStepKind,
    val status: AgentStepStatus,
    val modelAttemptId: String?,
    val invocationId: String?,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val schemaVersion: Int = AgentContract.SCHEMA_VERSION
) {
    init {
        AgentContract.requireId(stepId, "stepId")
        AgentContract.requireId(runId, "runId")
        require(index >= 0) { "step index must not be negative" }
    }
}

/** Model clarification question. Only the current WAITING_USER question can be answered once. */
data class AgentQuestion(
    val questionId: String,
    val runId: String,
    val stepIndex: Int,
    val text: String,
    val options: List<String>,
    val status: AgentQuestionStatus,
    val askedAtMillis: Long,
    val answeredAtMillis: Long? = null,
    val answer: String? = null,
    val schemaVersion: Int = AgentContract.SCHEMA_VERSION
) {
    init {
        AgentContract.requireId(questionId, "questionId")
        AgentContract.requireId(runId, "runId")
        require(text.isNotBlank()) { "question text must not be blank" }
        require(text.toByteArray(Charsets.UTF_8).size <= 4 * 1024) { "question text exceeds 4 KiB" }
        require(options.size <= 3) { "at most three question options are supported" }
        require(options.all { it.length <= 128 }) { "question option exceeds 128 characters" }
    }
}

enum class AgentQuestionStatus { OPEN, ANSWERED, CANCELLED }

data class AnswerAgentQuestion(
    val commandId: String,
    val runId: String,
    val expectedRunRevision: Long,
    val questionId: String,
    val answer: String
) {
    init {
        AgentContract.requireId(commandId, "commandId")
        AgentContract.requireId(runId, "runId")
        AgentContract.requireId(questionId, "questionId")
        require(expectedRunRevision >= 0L) { "expectedRunRevision must not be negative" }
        require(answer.isNotBlank()) { "answer must not be blank" }
    }
}
