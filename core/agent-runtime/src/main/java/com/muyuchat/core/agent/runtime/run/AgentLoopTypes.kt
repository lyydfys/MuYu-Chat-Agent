package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.contract.FrozenToolCommand

/** Identity of one model generation attempt; every callback carries it. */
data class AgentAttemptIdentity(
    val runId: String,
    val epoch: Long,
    val stepIndex: Int,
    val attemptOrdinal: Int,
    val attemptId: String
) {
    init {
        com.muyuchat.core.agent.contract.AgentContract.requireId(runId, "runId")
        com.muyuchat.core.agent.contract.AgentContract.requireId(attemptId, "attemptId")
        require(epoch > 0L) { "epoch must be positive" }
        require(stepIndex >= 0) { "stepIndex must not be negative" }
        require(attemptOrdinal >= 1) { "attemptOrdinal must be >= 1" }
    }
}

data class AgentAttemptResultCallback(
    val identity: AgentAttemptIdentity,
    val result: AgentModelResult
)

sealed interface AgentAttemptCallbackDecision {
    data object Accepted : AgentAttemptCallbackDecision

    data class Stale(val errorCode: String, val safeMessage: String) : AgentAttemptCallbackDecision
}

/**
 * Tracks the one active attempt per run so late callbacks from an old epoch/attempt can never
 * update a new run. Synchronized is intentional: its critical sections are tiny and never call
 * suspend functions or I/O.
 */
class AgentActiveAttemptRegistry {
    private val lock = Any()
    private val active = HashMap<String, AgentAttemptIdentity>()

    fun start(identity: AgentAttemptIdentity) {
        synchronized(lock) { active[identity.runId] = identity }
    }

    fun clear(runId: String) {
        synchronized(lock) { active.remove(runId) }
    }

    fun active(runId: String): AgentAttemptIdentity? = synchronized(lock) { active[runId] }

    fun verify(run: AgentRun, callback: AgentAttemptResultCallback): AgentAttemptCallbackDecision {
        val identity = callback.identity
        return synchronized(lock) {
            if (run.state.isTerminal) {
                return@synchronized stale("The run is already terminal.")
            }
            if (identity.runId != run.runId) {
                return@synchronized stale("The callback belongs to another run.")
            }
            if (identity.epoch != run.epoch) {
                return@synchronized stale("The callback epoch is stale.")
            }
            verify(identity)
        }
    }

    fun verify(identity: AgentAttemptIdentity): AgentAttemptCallbackDecision {
        return synchronized(lock) {
            val expected = active[identity.runId]
                ?: return@synchronized stale("No attempt is active for this run.")
            if (expected != identity) {
                return@synchronized stale("The callback attempt is not the active attempt.")
            }
            AgentAttemptCallbackDecision.Accepted
        }
    }

    private fun stale(message: String): AgentAttemptCallbackDecision.Stale =
        AgentAttemptCallbackDecision.Stale(AgentErrorCodes.RESOURCE_LEASE_LOST, message)
}

/** Authoritative transcript plus the counters a resumed execution needs. */
data class AgentLoopTranscript(
    val entries: List<AgentTranscriptEntry>,
    val nextStepIndex: Int,
    val repairsUsed: Int
) {
    init {
        require(nextStepIndex >= 0) { "nextStepIndex must not be negative" }
        require(repairsUsed >= 0) { "repairsUsed must not be negative" }
    }
}

/**
 * Resume data for the same run after user input or an approval decision. It never creates a new
 * run and never carries chat/page state.
 *
 * [toolDecision] is the only way a previously frozen tool call can run on resume. A stored command
 * alone is never sufficient: it must either arrive as an [AuthorizedToolCommand] produced by the
 * one-shot authorization seam, or be reported as a typed not-executed outcome.
 */
data class AgentLoopResume(
    val transcript: AgentLoopTranscript,
    val questionId: String? = null,
    val userAnswer: String? = null,
    val toolDecision: AgentResumeToolDecision? = null
)

sealed interface AgentResumeToolDecision {
    /**
     * [callHash] is the hash of the original model envelope that proposed the call, so the paired
     * tool result keeps the same transcript identity as a fresh execution.
     */
    data class Authorized(
        val authorization: AuthorizedToolCommand,
        val callHash: String
    ) : AgentResumeToolDecision

    /** The call must not execute; the typed observation is appended to the transcript instead. */
    data class NotExecuted(
        val invocationId: String,
        val callHash: String,
        val toolKey: String,
        val status: AgentToolResultStatus,
        val safeMessage: String,
        val errorCode: String? = null
    ) : AgentResumeToolDecision
}

sealed interface AgentLoopResult {
    val run: AgentRun
    val transcript: AgentLoopTranscript

    data class Completed(
        override val run: AgentRun,
        override val transcript: AgentLoopTranscript,
        val finalText: String,
        val artifactIds: List<String>
    ) : AgentLoopResult

    data class WaitingUser(
        override val run: AgentRun,
        override val transcript: AgentLoopTranscript,
        val questionText: String,
        val options: List<String>
    ) : AgentLoopResult

    data class WaitingApproval(
        override val run: AgentRun,
        override val transcript: AgentLoopTranscript,
        val invocationId: String,
        val callHash: String,
        val approval: AgentApproval?
    ) : AgentLoopResult

    data class Paused(
        override val run: AgentRun,
        override val transcript: AgentLoopTranscript,
        val reason: AgentPauseReason,
        val error: AgentError?
    ) : AgentLoopResult

    /**
     * The execution can no longer prove the state of an external effect. The run is parked in
     * NEEDS_REVIEW (not FAILED/PAUSED), keeps the known facts, and may be continued explicitly;
     * the ledger/receipt must prevent an automatic replay.
     */
    data class NeedsReview(
        override val run: AgentRun,
        override val transcript: AgentLoopTranscript,
        val error: AgentError
    ) : AgentLoopResult

    data class Failed(
        override val run: AgentRun,
        override val transcript: AgentLoopTranscript,
        val error: AgentError
    ) : AgentLoopResult

    data class Cancelled(
        override val run: AgentRun,
        override val transcript: AgentLoopTranscript,
        val error: AgentError? = null
    ) : AgentLoopResult
}

fun interface AgentRunExecutor {
    suspend fun execute(
        run: AgentRun,
        task: AgentTask,
        resume: AgentLoopResume?,
        progress: AgentModelProgressSink
    ): AgentLoopResult
}

fun interface AgentRunSpecProvider {
    suspend fun specFor(task: AgentTask): AgentRunSpec
}

data class AgentRunSpec(
    val modelBinding: AgentModelBinding,
    val policy: AgentRunPolicy,
    val budget: AgentBudget
)

data class AgentFrozenCommandRequest(
    val run: AgentRun,
    val task: AgentTask,
    val stepId: String,
    val invocationId: String,
    val callOrdinal: Int,
    val descriptor: AgentToolDescriptor,
    val normalizedArgumentsJson: String,
    val argumentsHash: String,
    val callHash: String,
    /** Native provider call id (N05c); frozen so a resumed approval keeps its call pairing. */
    val providerCallId: String? = null,
    /** Exact typed scope supplied by the executor when it implements [AgentTypedScopeSource]. */
    val typedScope: com.muyuchat.core.agent.contract.AgentScopePrecondition? = null
)

/**
 * K07 seam: K03 creates and validates the call but never freezes or approves it itself. The hook
 * returns a ready command, a pending approval, or a typed denial/failure.
 */
sealed interface AgentFrozenCommandDecision {
    data class ReadyToExecute(val command: FrozenToolCommand) : AgentFrozenCommandDecision

    data class WaitingApproval(
        val command: FrozenToolCommand,
        val approval: AgentApproval?
    ) : AgentFrozenCommandDecision

    data class Denied(
        val errorCode: String,
        val safeMessage: String,
        val possibleSideEffects: Boolean = false
    ) : AgentFrozenCommandDecision

    data class Failed(
        val errorCode: String,
        val safeMessage: String,
        val possibleSideEffects: Boolean = false
    ) : AgentFrozenCommandDecision
}

fun interface AgentFrozenCommandHook {
    suspend fun freeze(request: AgentFrozenCommandRequest): AgentFrozenCommandDecision
}

/**
 * Resolves the immutable tool catalog snapshot for exactly one run. Implementations may merge
 * built-ins with run-scoped Skills/MCP/cloud tools; the coordinator calls this once per
 * [AgentPromptedLoopCoordinator.execute] and builds the strict decoder from the same snapshot.
 */
fun interface AgentRunToolCatalogProvider {
    suspend fun catalogFor(run: AgentRun, task: AgentTask): AgentToolCatalog

    /**
     * Best-effort reconstruction of the *recorded* surface when the current catalog hash drifted
     * (N04). Returning null or a catalog whose hash/identities do not match keeps the fail-closed
     * drift error. Implementations must never widen the recorded set nor invent a tool.
     */
    suspend fun catalogForRecorded(
        run: AgentRun,
        task: AgentTask,
        recorded: AgentRecordedCatalog
    ): AgentToolCatalog? = null

    /**
     * Releases all run-scoped resources acquired by [catalogFor] (for example per-run MCP
     * clients). The coordinator invokes this exactly once per execution after model/tool activity
     * ends, including success, run failure, cancellation and provider-resolution failure.
     *
     * The default is a no-op so stateless catalogs and SAM lambdas stay source-compatible.
     * A non-cancellation exception here must not replace the run's [AgentLoopResult]; a
     * [kotlinx.coroutines.CancellationException] still propagates as cancellation.
     */
    suspend fun release(run: AgentRun) {}
}

/** Stable runtime error codes for per-run tool-catalog resolution. */
object AgentRunToolCatalogErrorCodes {
    /** The provider failed before any model/tool work; the run fails with a typed Tool error. */
    const val TOOL_CATALOG_UNAVAILABLE: String = "TOOL_CATALOG_UNAVAILABLE"

    /**
     * A resumed execution resolved a different catalog than the one recorded when the run started
     * (N04). Continuing would silently change the tools/approvals reachable by the model, so the
     * execution fails closed and the user starts a new run.
     */
    const val TOOL_CATALOG_DRIFT: String = "TOOL_CATALOG_DRIFT"
}

/** K05/K08 seam: returns the subset of final artifact ids that is not registered for this task. */
fun interface AgentArtifactVerifier {
    suspend fun verify(taskId: String, artifactIds: List<String>): List<String>
}
