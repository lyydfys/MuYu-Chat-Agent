package com.muyuchat.core.agent.runtime.journal

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.ToolInvocation
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.protocol.AgentHashes

/**
 * Frozen resource scope persisted with a tool invocation.
 *
 * It is the core-neutral mirror of the app-side frozen scope: [taskRoot] is a logical identity,
 * [logicalPaths] is sorted/unique, and [networkDestinations] are canonical origins. Keeping it in
 * the journal seam lets the runtime/owner reconstruct the exact approved resources without importing
 * app security types.
 *
 * Exactly one shape is used:
 * - V1 workspace: [logicalPaths] non-empty, [typedPreconditionJson] null;
 * - V2 typed scope (N02c): [logicalPaths] empty and [typedPreconditionJson] the canonical
 *   [com.muyuchat.core.agent.contract.AgentScopePrecondition] JSON. A typed scope must never be
 *   combined with workspace paths, otherwise the approved resource identity would be ambiguous.
 */
data class AgentToolScope(
    val taskRoot: String,
    val logicalPaths: List<String>,
    val expectedOldHash: String?,
    val networkDestinations: List<String>,
    val typedPreconditionJson: String? = null
) {
    init {
        AgentContract.requireNotBlank(taskRoot, "taskRoot")
        if (typedPreconditionJson == null) {
            require(logicalPaths.isNotEmpty()) { "a frozen tool scope must name at least one resource path" }
        } else {
            require(logicalPaths.isEmpty()) {
                "a typed scope must not also name workspace logical paths"
            }
            require(typedPreconditionJson.startsWith("{")) {
                "a typed scope precondition must be a JSON object"
            }
        }
        require(logicalPaths == logicalPaths.distinct().sorted()) { "logical paths must be sorted and unique" }
        require(expectedOldHash == null || expectedOldHash.matches(Regex("^[0-9a-f]{64}$"))) {
            "expectedOldHash must be lowercase SHA-256 or null"
        }
        require(networkDestinations == networkDestinations.distinct().sorted()) {
            "network destinations must be sorted and unique"
        }
    }
}

/**
 * One validated tool call that must be durably recorded BEFORE it is frozen or decided.
 *
 * [idempotencyKey] is derived only from the stable identity `(runId, stepId, callOrdinal)` via
 * [idempotencyKey], so a retry of the same logical call can never create a second invocation row.
 */
data class AgentToolProposal(
    val invocationId: String,
    val runId: String,
    val taskId: String,
    val stepId: String,
    val stepIndex: Int,
    val callOrdinal: Int,
    val toolKey: String,
    val toolVersion: String,
    val schemaHash: String,
    val argumentsHash: String,
    val proposedAtMillis: Long
) {
    init {
        AgentContract.requireId(invocationId, "invocationId")
        AgentContract.requireId(runId, "runId")
        AgentContract.requireId(taskId, "taskId")
        AgentContract.requireId(stepId, "stepId")
        require(stepIndex >= 0) { "stepIndex must not be negative" }
        require(callOrdinal >= 0) { "callOrdinal must not be negative" }
        AgentContract.requireNotBlank(toolKey, "toolKey")
        AgentContract.requireNotBlank(toolVersion, "toolVersion")
        AgentContract.requireNotBlank(schemaHash, "schemaHash")
        AgentContract.requireNotBlank(argumentsHash, "argumentsHash")
        require(proposedAtMillis >= 0L) { "proposedAtMillis must not be negative" }
    }

    /** Stable proposal identity: a replay of the same logical call cannot mint a second row. */
    val idempotencyKey: String
        get() = idempotencyKey(runId, stepId, callOrdinal)

    companion object {
        fun idempotencyKey(runId: String, stepId: String, callOrdinal: Int): String =
            "inv-" + AgentHashes.sha256HexUtf8("$runId|$stepId|$callOrdinal").take(48)
    }
}

/**
 * The immutable command material recorded immediately after freeze and before the permission
 * decision. It carries the exact descriptor and resolved scope so the owner can reconstruct the
 * permission candidate after process restart without re-freezing from model text.
 */
data class AgentFrozenInvocation(
    val command: FrozenToolCommand,
    val descriptor: AgentToolDescriptor,
    val scope: AgentToolScope,
    val recordedAtMillis: Long
) {
    init {
        require(command.toolKey == descriptor.toolKey) { "frozen command toolKey does not match descriptor" }
        require(command.toolVersion == descriptor.version) { "frozen command tool version does not match descriptor" }
        require(command.schemaHash == descriptor.schemaHash) { "frozen command schema hash does not match descriptor" }
        require(scope.networkDestinations == command.networkDestinations) {
            "frozen scope network destinations must match the command"
        }
        require(recordedAtMillis >= 0L) { "recordedAtMillis must not be negative" }
    }
}

sealed interface AgentToolInvocationRecord {
    /** [invocation] is the durable row; a replay returns the existing row instead of a new one. */
    data class Recorded(val invocation: ToolInvocation) : AgentToolInvocationRecord

    /** Typed fail-closed rejection; no execution and no approval may follow it. */
    data class Rejected(val errorCode: String, val safeMessage: String) : AgentToolInvocationRecord
}

sealed interface AgentToolInvocationUpdate {
    data object Updated : AgentToolInvocationUpdate

    /** The CAS lost, the row is missing, or the status does not allow this transition. */
    data class Rejected(val errorCode: String, val safeMessage: String) : AgentToolInvocationUpdate
}

/**
 * Narrow, runtime-facing tool-invocation ledger.
 *
 * Contract for every adapter (must be one atomic transaction per call):
 * - `recordProposed` writes the step and the `PROPOSED` invocation in one transaction, is idempotent
 *   on `(runId, stepId, callOrdinal)` and rejects a missing or foreign run;
 * - `recordFrozen` CASes `PROPOSED -> VALIDATED` with the exact frozen hashes and the bounded frozen
 *   command payload; it must reject a command whose identity differs from the proposed row;
 * - `markWaitingApproval`, `markRunning` and `markTerminal` are single conditional transitions;
 *   `markRunning` succeeds at most once (the execution gate), and `markTerminal` never overwrites a
 *   different terminal value;
 * - `markApproved` records the durable dispatch intent (N03): an authorized invocation that has not
 *   yet been handed to an executor. It is idempotent for an already-APPROVED row and never starts
 *   execution (that stays with `markRunning`);
 * - `readFrozenInvocation` returns the recorded command/descriptor/scope or null; a corrupt payload
 *   is never reported as usable.
 */
interface AgentToolInvocationLedger {
    suspend fun recordProposed(proposal: AgentToolProposal): AgentToolInvocationRecord

    suspend fun recordFrozen(frozen: AgentFrozenInvocation): AgentToolInvocationRecord

    suspend fun markWaitingApproval(invocationId: String, atMillis: Long): AgentToolInvocationUpdate

    /**
     * Records the one-shot authorization as a durable dispatch intent, committed in the same
     * transaction as the approval CAS and the run transition. Recovery treats a leftover APPROVED
     * row as "authorized but not confirmed executed" (NEEDS_REVIEW), never as evidence of success.
     */
    suspend fun markApproved(invocationId: String, atMillis: Long): AgentToolInvocationUpdate

    suspend fun markRunning(invocationId: String, atMillis: Long): AgentToolInvocationUpdate

    suspend fun markTerminal(
        invocationId: String,
        status: ToolInvocationStatus,
        possibleSideEffects: Boolean,
        resultRef: String?,
        atMillis: Long
    ): AgentToolInvocationUpdate

    suspend fun readInvocation(invocationId: String): ToolInvocation?

    suspend fun readFrozenInvocation(invocationId: String): AgentFrozenInvocation?
}

/**
 * Fail-closed default for owners/harnesses with no durable tool ledger. It can never record or run
 * anything, so a wiring mistake surfaces as a typed NOT_EXECUTED result instead of an unledgered
 * execution.
 */
object NoAgentToolInvocationLedger : AgentToolInvocationLedger {
    private fun rejected(): AgentToolInvocationRecord = AgentToolInvocationRecord.Rejected(
        com.muyuchat.core.agent.contract.AgentErrorCodes.INTERNAL_INVARIANT,
        "The tool invocation ledger is not configured."
    )

    private fun updateRejected(): AgentToolInvocationUpdate = AgentToolInvocationUpdate.Rejected(
        com.muyuchat.core.agent.contract.AgentErrorCodes.INTERNAL_INVARIANT,
        "The tool invocation ledger is not configured."
    )

    override suspend fun recordProposed(proposal: AgentToolProposal): AgentToolInvocationRecord = rejected()

    override suspend fun recordFrozen(frozen: AgentFrozenInvocation): AgentToolInvocationRecord = rejected()

    override suspend fun markWaitingApproval(invocationId: String, atMillis: Long): AgentToolInvocationUpdate =
        updateRejected()

    override suspend fun markApproved(invocationId: String, atMillis: Long): AgentToolInvocationUpdate =
        updateRejected()

    override suspend fun markRunning(invocationId: String, atMillis: Long): AgentToolInvocationUpdate =
        updateRejected()

    override suspend fun markTerminal(
        invocationId: String,
        status: ToolInvocationStatus,
        possibleSideEffects: Boolean,
        resultRef: String?,
        atMillis: Long
    ): AgentToolInvocationUpdate = updateRejected()

    override suspend fun readInvocation(invocationId: String): ToolInvocation? = null

    override suspend fun readFrozenInvocation(invocationId: String): AgentFrozenInvocation? = null
}

/**
 * Statuses that may still enter `RUNNING`; anything else must not execute.
 *
 * `WAITING_APPROVAL` is deliberately absent: the approval transaction must first record the durable
 * `APPROVED` dispatch intent, so an unapproved row can never be started by a stale or forged resume.
 */
fun ToolInvocationStatus.canStartExecution(): Boolean =
    this == ToolInvocationStatus.VALIDATED ||
        this == ToolInvocationStatus.APPROVED

/**
 * Placeholder resource fingerprint for a `PROPOSED` row written before freeze. It is never
 * executable: the frozen identity must replace it before any approval/authorization can match.
 */
const val PENDING_RESOURCE_FINGERPRINT: String = "pending"

/** Pre-execution statuses a recovery pass may settle as NOT_EXECUTED without side effects. */
fun ToolInvocationStatus.isPreExecution(): Boolean =
    this == ToolInvocationStatus.PROPOSED ||
        this == ToolInvocationStatus.VALIDATED ||
        this == ToolInvocationStatus.WAITING_APPROVAL ||
        this == ToolInvocationStatus.APPROVED
