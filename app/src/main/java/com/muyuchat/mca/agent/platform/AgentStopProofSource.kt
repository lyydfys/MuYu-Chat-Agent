package com.muyuchat.mca.agent.platform

import com.muyuchat.core.agent.contract.AgentContract

/**
 * One exact Agent execution: run epoch + the never-reused model request id. `attemptId` is optional
 * because recovery may only know the persisted attempt row; `backendRequestId` is mandatory because
 * the engine stop token is scoped by request id.
 */
data class AgentExecutionRef(
    val runId: String,
    val runEpoch: Long,
    val attemptId: String?,
    val backendRequestId: String
) {
    init {
        AgentContract.requireId(runId, "runId")
        require(runEpoch > 0L) { "runEpoch must be positive" }
        attemptId?.let { AgentContract.requireId(it, "attemptId") }
        AgentContract.requireId(backendRequestId, "backendRequestId")
    }
}

/**
 * Evidence that one exact Agent request/worker is quiescent. `Quiescent` is the only value that may
 * release the native lifecycle fence; every other value keeps the fence and produces NEEDS_REVIEW.
 */
sealed interface AgentQuiescenceEvidence {
    data class Quiescent(val evidenceRef: String) : AgentQuiescenceEvidence

    data class NotQuiescent(val evidenceRef: String) : AgentQuiescenceEvidence

    data class Unknown(val reasonRef: String) : AgentQuiescenceEvidence

    data class TimedOut(val waitedMillis: Long) : AgentQuiescenceEvidence
}

/**
 * Narrow typed port for K04's engine/worker stop proof. This platform slice deliberately does not
 * implement engine internals; the implementation belongs to the K04 adapter and must provide:
 *
 * 1. `McaInferenceService.activeGenerationStopToken()` released for `backendRequestId`;
 * 2. `ChatExecutionFacade.snapshot(backendRequestId)` terminal (or absent after eviction);
 * 3. for isolated workers, the existing worker owner's exact session/process exit or safe-idle
 *    confirmation for that request, not a new JNI/Binder active-state poll.
 *
 * `read` is a non-blocking snapshot; `awaitQuiescent` may poll those owner surfaces with a bounded
 * timeout. Neither method may call `runner.requestStop()` itself or hold the engine lifecycle mutex.
 */
interface AgentStopProofSource {
    suspend fun read(ref: AgentExecutionRef): AgentQuiescenceEvidence

    suspend fun awaitQuiescent(
        ref: AgentExecutionRef,
        timeoutMillis: Long
    ): AgentQuiescenceEvidence

    companion object {
        /** Safe default for callers that have not wired K04 yet: never claims quiescence. */
        val Unavailable: AgentStopProofSource = object : AgentStopProofSource {
            override suspend fun read(ref: AgentExecutionRef): AgentQuiescenceEvidence =
                AgentQuiescenceEvidence.Unknown("stop_proof_source_unavailable")

            override suspend fun awaitQuiescent(
                ref: AgentExecutionRef,
                timeoutMillis: Long
            ): AgentQuiescenceEvidence = AgentQuiescenceEvidence.Unknown("stop_proof_source_unavailable")
        }
    }
}
