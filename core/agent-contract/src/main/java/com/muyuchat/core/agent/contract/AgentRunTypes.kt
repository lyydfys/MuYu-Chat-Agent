package com.muyuchat.core.agent.contract

/** Protocol actually available for one backend + model fingerprint (architecture 6.1). */
enum class AgentProtocolMode {
    NATIVE,
    PROMPTED_V1,
    NONE
}

/** Whether this run may reach the network at all; enforced by the app transport layer. */
enum class AgentNetworkPolicy {
    LOCAL_ONLY,
    NETWORK_ENABLED
}

/**
 * Immutable model binding frozen when the run is created. It never contains credentials and is
 * never silently replaced by the chat page's current selection.
 */
data class AgentModelBinding(
    val providerId: String,
    val modelId: String,
    val runtime: String,
    val assetFingerprint: String,
    val protocolMode: AgentProtocolMode,
    val modelDisplayName: String,
    val contextWindowTokens: Int? = null,
    val samplingJson: String = "{}",
    val capabilityEvidenceVersion: String? = null
) {
    init {
        AgentContract.requireNotBlank(providerId, "providerId")
        AgentContract.requireNotBlank(modelId, "modelId")
        AgentContract.requireNotBlank(runtime, "runtime")
        AgentContract.requireNotBlank(assetFingerprint, "assetFingerprint")
        AgentContract.requireNotBlank(modelDisplayName, "modelDisplayName")
        require(contextWindowTokens == null || contextWindowTokens > 0) {
            "contextWindowTokens must be positive when present"
        }
    }
}

/**
 * Pins one immutable workflow definition version to a run.
 *
 * A run whose policy carries this spec is executed by the workflow graph strategy under the one
 * [com.muyuchat.core.agent.runtime.run.AgentRunOwner]; it is not a second run owner or a child run.
 * The definition and version are frozen here so a later draft/publish cannot change a running graph.
 */
data class AgentWorkflowRunSpec(
    val definitionId: String,
    val version: Long
) {
    init {
        require(definitionId.isNotBlank()) { "workflow definitionId must not be blank" }
        require(version >= 1L) { "workflow definition version must be positive" }
    }
}

data class AgentRunPolicy(
    val networkPolicy: AgentNetworkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
    val policyVersion: Long = 1L,
    /** Enabled tool keys; null means the registry default set (still intersected with grants). */
    val enabledToolKeys: Set<String>? = null,
    val skillIds: Set<String> = emptySet(),
    val offlineForced: Boolean = true,
    /**
     * Memory spaces frozen for this run's task. Empty means the run has no memory read grant and
     * `memory.search` is never installed in its catalog. Old persisted policy JSON decodes to empty,
     * so this is an additive, source-compatible field.
     */
    val memorySpaceIds: Set<String> = emptySet(),
    /**
     * Exact published workflow version this run executes instead of the model loop. Absent for a
     * normal Agent run, including every row written before workflows were run-owned.
     */
    val workflow: AgentWorkflowRunSpec? = null
) {
    init {
        require(policyVersion > 0L) { "policyVersion must be positive" }
        require(!(networkPolicy == AgentNetworkPolicy.LOCAL_ONLY && !offlineForced)) {
            "LOCAL_ONLY implies offlineForced"
        }
    }
}

/** Fixed V1 budgets (architecture 6.6). All limits are inclusive maxima. */
data class AgentBudget(
    val modelAttempts: Int = 12,
    val toolInvocations: Int = 8,
    val callsPerModelResponse: Int = 1,
    val protocolRepairs: Int = 1,
    val modelWallTimeMillis: Long = 180_000L,
    val toolTimeoutMillis: Long = 30_000L,
    val runActiveTimeMillis: Long = 900_000L,
    val resourceWaitMillis: Long = 120_000L,
    val toolResultToModelBytes: Int = AgentContract.MAX_TOOL_RESULT_TO_MODEL_BYTES,
    val toolResultPersistBytes: Int = AgentContract.MAX_TOOL_RESULT_PERSIST_BYTES,
    val textOutputTokens: Int = 2_048,
    val totalGeneratedTokens: Int = 24_576
) {
    init {
        require(modelAttempts > 0) { "modelAttempts must be positive" }
        require(toolInvocations >= 0) { "toolInvocations must not be negative" }
        require(callsPerModelResponse == 1) { "V1 executes exactly one tool call per model response" }
        require(protocolRepairs in 0..1) { "V1 allows at most one protocol repair per run" }
        require(modelWallTimeMillis > 0L && runActiveTimeMillis > 0L && toolTimeoutMillis > 0L)
        require(toolResultToModelBytes in 1..AgentContract.MAX_TOOL_RESULT_TO_MODEL_BYTES)
        require(toolResultPersistBytes in toolResultToModelBytes..AgentContract.MAX_TOOL_RESULT_PERSIST_BYTES)
        require(textOutputTokens > 0 && totalGeneratedTokens > 0)
    }
}

data class AgentSpentBudget(
    val modelAttempts: Int = 0,
    val toolInvocations: Int = 0,
    val protocolRepairs: Int = 0,
    val modelWallTimeMillis: Long = 0L,
    val runActiveTimeMillis: Long = 0L,
    val generatedTokens: Int = 0,
    val estimated: Boolean = true
)

enum class AgentRunState {
    QUEUED,
    RUNNING,
    WAITING_RESOURCE,
    WAITING_APPROVAL,
    WAITING_USER,
    PAUSING,
    STOPPING,
    PAUSED,
    NEEDS_REVIEW,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    SUPERSEDED,
    INTERRUPTED;

    val isTerminal: Boolean
        get() = this == SUCCEEDED || this == FAILED || this == CANCELLED || this == SUPERSEDED

    /**
     * True only while this run holds the single execution slot.
     *
     * [WAITING_USER] is deliberately **excluded**: it is a park, not work. The runtime already
     * releases the admission slot when the loop asks the user a question
     * (`AgentOwnerExecution`'s park path sets the active run id to null), so a run sitting here
     * consumes no execution resource. Counting it as an occupant made one unanswered question — a
     * two-hour one was observed on device — block every later run with "已到运行上限 0 轮 0 步"
     * and made the task impossible to delete.
     *
     * Callers that used this to mean "not finished yet" must ask [isTerminal] instead; asking a
     * parked run is not a stale run.
     */
    val holdsActiveSlot: Boolean
        get() = this == QUEUED || this == RUNNING || this == WAITING_RESOURCE ||
            this == WAITING_APPROVAL || this == PAUSING || this == STOPPING

    /**
     * True when deleting a task must first cancel the run that owns it.
     *
     * Wider than [holdsActiveSlot] on purpose. A parked [WAITING_USER] run occupies no execution
     * slot, yet an open question is still bound to it: removing its rows would strand the answer
     * the user may still give, so the cancel closes the question first. Conversely a merely
     * resumable run ([PAUSED], [NEEDS_REVIEW], [INTERRUPTED]) has nothing in flight -- deleting it
     * is how a user discards a stopped task, and requiring a cancel would make every deletion of a
     * paused task emit a cancel command that has nothing to cancel.
     */
    val needsCancelBeforeDeletion: Boolean
        get() = this == QUEUED || this == RUNNING || this == WAITING_RESOURCE ||
            this == WAITING_APPROVAL || this == WAITING_USER || this == PAUSING ||
            this == STOPPING

    /**
     * True when a workflow waiting on this run may stop waiting and report a typed outcome.
     *
     * Wider than [isTerminal] and narrower than `!holdsActiveSlot`: a merely resumable state
     * ([PAUSED], [NEEDS_REVIEW], [INTERRUPTED]) has settled -- nobody is working and the workflow
     * must not hang on it -- while a parked [WAITING_USER] run has not: it still owes the answer
     * the workflow is waiting for.
     */
    val isSettledForWorkflow: Boolean
        get() = isTerminal || this == PAUSED || this == NEEDS_REVIEW || this == INTERRUPTED
}

enum class AgentPauseReason {
    NONE,
    RESOURCE_BUSY,
    APPROVAL_EXPIRED,
    APPROVAL_STALE,
    MODEL_PROTOCOL_ERROR,
    MODEL_BINDING_CHANGED,
    CONTEXT_LIMIT,
    PERMISSION_DENIED_LOOP,
    BUDGET_LIMIT,
    FOREGROUND_SERVICE_UNAVAILABLE,
    USER_REQUESTED,
    INTERRUPTED,
    UNKNOWN
}

data class AgentRun(
    val runId: String,
    val taskId: String,
    val runOrdinal: Long,
    val state: AgentRunState,
    val reason: AgentPauseReason,
    val epoch: Long,
    val modelBinding: AgentModelBinding,
    val policy: AgentRunPolicy,
    val budget: AgentBudget,
    val spentBudget: AgentSpentBudget,
    val lastSequence: Long,
    val revision: Long,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val schemaVersion: Int = AgentContract.SCHEMA_VERSION
) {
    init {
        AgentContract.requireId(runId, "runId")
        AgentContract.requireId(taskId, "taskId")
        require(runOrdinal >= 1L) { "runOrdinal must be >= 1" }
        require(epoch > 0L) { "run epoch must be positive" }
        require(revision >= 0L) { "run revision must not be negative" }
        require(lastSequence >= 0L) { "lastSequence must not be negative" }
    }
}

data class AgentRunSnapshot(
    val run: AgentRun,
    val task: AgentTask,
    val activeQuestion: AgentQuestion? = null,
    val pendingApproval: AgentApproval? = null,
    val steps: List<AgentStep> = emptyList(),
    val artifacts: List<AgentArtifact> = emptyList()
)

data class StartAgentRun(
    val commandId: String,
    val taskId: String,
    val expectedTaskRevision: Long
) {
    init {
        AgentContract.requireId(commandId, "commandId")
        AgentContract.requireId(taskId, "taskId")
        require(expectedTaskRevision >= 0L) { "expectedTaskRevision must not be negative" }
    }
}

/**
 * Requests a pause of one run.
 *
 * [reason] is typed and limited to command-pausable reasons. It defaults to USER_REQUESTED so the
 * existing UI path is source-compatible; the foreground-service guard uses
 * FOREGROUND_SERVICE_UNAVAILABLE so the final PAUSED run records the real reason instead of
 * silently downgrading to USER_REQUESTED. Loop-internal recovery reasons (budget/resource/
 * protocol/approval/interrupted) are not command-triggered and are rejected here.
 */
data class PauseAgentRun(
    val commandId: String,
    val runId: String,
    val expectedRunRevision: Long,
    val reason: AgentPauseReason = AgentPauseReason.USER_REQUESTED
) {
    init {
        AgentContract.requireId(commandId, "commandId")
        AgentContract.requireId(runId, "runId")
        require(expectedRunRevision >= 0L) { "expectedRunRevision must not be negative" }
        require(
            reason == AgentPauseReason.USER_REQUESTED ||
                reason == AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE
        ) {
            "PauseAgentRun.reason must be USER_REQUESTED or FOREGROUND_SERVICE_UNAVAILABLE, " +
                "got ${reason.name}"
        }
    }
}

data class CancelAgentRun(
    val commandId: String,
    val runId: String,
    val expectedRunRevision: Long
) {
    init {
        AgentContract.requireId(commandId, "commandId")
        AgentContract.requireId(runId, "runId")
        require(expectedRunRevision >= 0L) { "expectedRunRevision must not be negative" }
    }
}
