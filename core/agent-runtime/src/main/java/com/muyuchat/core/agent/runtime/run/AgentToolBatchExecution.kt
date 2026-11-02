package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentModelTurn
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AgentTurnToolCall
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.AgentControlMessage
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import com.muyuchat.core.agent.runtime.protocol.AgentTranscriptV2Record
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Stable observation code for a batched call that was frozen but never dispatched. */
internal const val BATCH_ABORTED_ERROR_CODE: String = "BATCH_ABORTED"

internal sealed interface AgentToolBatchOutcome {
    data class Continue(
        val spent: AgentSpentBudget,
        val run: AgentRun,
        val nextStepIndex: Int
    ) : AgentToolBatchOutcome

    data class Paused(
        val spent: AgentSpentBudget,
        val run: AgentRun,
        val reason: AgentPauseReason,
        val error: AgentError
    ) : AgentToolBatchOutcome

    data class Waiting(
        val run: AgentRun,
        val invocationId: String,
        val callHash: String,
        val approval: AgentApproval
    ) : AgentToolBatchOutcome

    /** At least one settled call is UNKNOWN; the loop must park for review instead of retrying. */
    data class NeedsReview(
        val spent: AgentSpentBudget,
        val run: AgentRun,
        val error: AgentError
    ) : AgentToolBatchOutcome
}

/**
 * Executes a bounded read-only batch (N06).
 *
 * Ordering contract:
 *  * budget precheck before anything runs; write calls / oversized batches / missing call ids were
 *    already refused as a whole by `batchProblem`;
 *  * proposal/freeze/`tool.proposed` are serial in input ordinal order;
 *  * only the executor invocations overlap, at most [MAX_PARALLEL_READ_ONLY_CALLS] at a time;
 *  * terminal settlement, `tool.result` and the transcript pair are committed serially in the same
 *    ordinal order, so the run journal keeps exactly one writer and the transcript stays a strict
 *    assistant→result pairing.
 *
 * If one call needs approval (or a per-call budget check refuses), the batch never partially
 * executes: every already-frozen but undispatched call is settled as NOT_EXECUTED with its own paired
 * observation and event, the aborting call keeps its assistant record only (the approval/user resume
 * pairs it), and the typed pause/waiting result is returned.
 */
internal suspend fun AgentPromptedLoopCoordinator.executeToolBatch(
    turn: AgentModelTurn,
    run: AgentRun,
    task: AgentTask,
    stepIndex: Int,
    catalog: AgentToolCatalog,
    spentStart: AgentSpentBudget,
    entries: MutableList<AgentTranscriptEntry>
): AgentToolBatchOutcome {
    val batchBudget = budgetPolicy.checkToolInvocationBatch(turn.calls.size, spentStart, run.budget)
    if (batchBudget is AgentBudgetDecision.Exceeded) {
        return AgentToolBatchOutcome.Paused(
            spentStart,
            run,
            batchBudget.pauseReason,
            batchBudget.error
        )
    }
    val emit: suspend (AgentRun, String, String?, String?) -> AgentRun =
        { eventRun, type, summary, invocationId ->
            appendEvent(eventRun, type, summary, invocationId)
        }
    val pipeline = AgentToolExecutionPipeline(
        toolLedger = toolLedger,
        clock = clock,
        codec = codec,
        catalog = catalog
    )
    var spent = spentStart
    var currentRun = run
    var step = stepIndex
    val calls = ArrayList<BatchCallRun>(turn.calls.size)
    var abort: AgentToolBatchOutcome? = null

    // Phase 1 - serial proposal/freeze in ordinal order (journal events stay single-writer).
    for (call in turn.calls) {
        val callId = call.callId
        if (callId == null) {
            abort = AgentToolBatchOutcome.Paused(
                spent,
                currentRun,
                AgentPauseReason.MODEL_PROTOCOL_ERROR,
                protocolError("Every batched call needs a provider call id.")
            )
            break
        }
        val envelope = nativeCallEnvelope(call)
        val callHash = AgentHashes.sha256HexUtf8(envelope)
        val preparation = prepareToolCall(
            message = AgentControlMessage.ToolCall(name = call.name, argumentsJson = call.argumentsJson),
            callHash = callHash,
            run = currentRun,
            task = task,
            stepIndex = step,
            catalog = catalog,
            spent = spent,
            providerCallId = callId
        )
        val batchCall = BatchCallRun.from(
            callId = callId,
            envelope = envelope,
            callHash = callHash,
            stepIndex = step,
            preparation = preparation
        )
        calls += batchCall
        when (preparation) {
            is AgentToolPreparation.Handled -> {
                spent = preparation.spent
                currentRun = preparation.run
                batchCall.observation = preparation.observation
                step += 1
            }
            is AgentToolPreparation.Ready -> {
                spent = preparation.spent
                currentRun = preparation.run
                step += 1
            }
            is AgentToolPreparation.Paused -> {
                spent = preparation.spent
                currentRun = preparation.run
                abort = AgentToolBatchOutcome.Paused(
                    preparation.spent,
                    preparation.run,
                    preparation.reason,
                    preparation.error
                )
            }
            is AgentToolPreparation.Waiting -> {
                currentRun = preparation.run
                abort = AgentToolBatchOutcome.Waiting(
                    preparation.run,
                    preparation.invocationId,
                    preparation.callHash,
                    preparation.approval
                )
            }
        }
        if (abort != null) break
    }

    val aborted = abort
    if (aborted != null) {
        currentRun = settleUndispatched(calls, currentRun, emit)
        // Record the pairs in ordinal order; the aborting call keeps its assistant record only, so an
        // approval/user resume can pair it exactly like the serial path does.
        for (call in calls) {
            entries += AgentTranscriptEntry.AssistantEnvelope(
                rawEnvelope = call.envelope,
                providerCallId = call.callId
            )
            call.observation?.let { observation ->
                entries += pairedResult(call, observation)
            }
        }
        return when (aborted) {
            is AgentToolBatchOutcome.Paused -> aborted.copy(run = currentRun)
            is AgentToolBatchOutcome.Waiting -> aborted.copy(run = currentRun)
            is AgentToolBatchOutcome.NeedsReview -> aborted.copy(run = currentRun)
            is AgentToolBatchOutcome.Continue -> aborted
        }
    }

    // Phase 2 - serial dispatch admission in ordinal order (RUNNING CAS + tool.started).
    var unknownResult: AgentToolResult? = null
    var dispatching: List<BatchCallRun> = emptyList()
    var results: Array<AgentToolResult?> = emptyArray()
    val dispatcher = parallelDispatcher
    try {
        for (call in calls) {
            val ready = call.preparation as? AgentToolPreparation.Ready ?: continue
            when (
                val began = pipeline.begin(
                    authorization = ready.authorization,
                    run = currentRun,
                    task = task,
                    stepIndex = call.stepIndex,
                    emit = emit,
                    readRun = { runId -> journal.readRun(runId)?.run }
                )
            ) {
                is AgentToolExecutionPipeline.Begin.Refused -> {
                    // begin() already settled the ledger row and emitted the typed tool.result.
                    call.observation = toObservation(began.outcome.result)
                    currentRun = began.outcome.run
                    if (began.outcome.result.status == AgentToolResultStatus.UNKNOWN) {
                        unknownResult = began.outcome.result
                    }
                }
                is AgentToolExecutionPipeline.Begin.Started -> {
                    call.started = began
                    // `tool.started` advanced the run revision; the next admission must chain from it
                    // or the journal rejects the following append as a stale revision.
                    currentRun = began.run
                }
            }
        }

        // Phase 3 - only the executor calls overlap; bounded to MAX_PARALLEL_READ_ONLY_CALLS.
        dispatching = calls.filter { it.started != null }
        results = arrayOfNulls(dispatching.size)
        coroutineScope {
            val gate = Semaphore(MAX_PARALLEL_READ_ONLY_CALLS)
            val tasks = dispatching.map { call ->
                val invoke: suspend () -> AgentToolResult = {
                    gate.withPermit { pipeline.invoke(requireNotNull(call.started)) }
                }
                if (dispatcher == null) async { invoke() } else async(dispatcher) { invoke() }
            }
            for (index in tasks.indices) results[index] = tasks[index].await()
        }
    } catch (cancelled: CancellationException) {
        var runNow = currentRun
        for (call in calls) {
            val started = call.started ?: continue
            runNow = pipeline.settleCancelled(started, runNow, emit)
        }
        // Any call frozen before the cancellation but never admitted is closed as NOT_EXECUTED so a
        // resumed loop cannot find a dangling VALIDATED row and freeze-fail on the same node.
        withContext(NonCancellable) {
            settleUndispatched(calls, runNow, emit)
        }
        throw cancelled
    }

    // Phase 4 - serial terminal settlement + transcript pairing in ordinal order.
    for ((index, call) in dispatching.withIndex()) {
        val outcome = pipeline.settle(
            started = requireNotNull(call.started),
            result = requireNotNull(results[index]) { "the batch call settled without a result" },
            run = currentRun,
            emit = emit,
            readRun = { runId -> journal.readRun(runId)?.run }
        )
        currentRun = outcome.run
        call.observation = toObservation(outcome.result)
        if (outcome.result.status == AgentToolResultStatus.UNKNOWN) {
            unknownResult = outcome.result
        }
    }
    for (call in calls) {
        entries += AgentTranscriptEntry.AssistantEnvelope(
            rawEnvelope = call.envelope,
            providerCallId = call.callId
        )
        call.observation?.let { observation ->
            entries += pairedResult(call, observation)
        }
    }
    val unknown = unknownResult
    if (unknown != null) {
        return AgentToolBatchOutcome.NeedsReview(
            spent = spent,
            run = currentRun,
            error = unknownToolResultError(run, unknown)
        )
    }
    return AgentToolBatchOutcome.Continue(spent = spent, run = currentRun, nextStepIndex = step)
}

/** One batched call and the facts it accumulates while the batch advances. */
private class BatchCallRun(
    val callId: String,
    val envelope: String,
    val callHash: String,
    val invocationId: String,
    val toolKey: String,
    val stepIndex: Int,
    val preparation: AgentToolPreparation
) {
    /** Terminal observation once known; null means the call has no result (approval/pause abort). */
    var observation: AgentJsonValue? = null

    /** Non-null once this call was admitted for dispatch (RUNNING CAS passed). */
    var started: AgentToolExecutionPipeline.Begin.Started? = null

    companion object {
        fun from(
            callId: String,
            envelope: String,
            callHash: String,
            stepIndex: Int,
            preparation: AgentToolPreparation
        ): BatchCallRun = when (preparation) {
            is AgentToolPreparation.Ready -> BatchCallRun(
                callId = callId,
                envelope = envelope,
                callHash = callHash,
                invocationId = preparation.invocationId,
                toolKey = preparation.toolKey,
                stepIndex = stepIndex,
                preparation = preparation
            )
            is AgentToolPreparation.Handled -> BatchCallRun(
                callId = callId,
                envelope = envelope,
                callHash = callHash,
                invocationId = preparation.invocationId,
                toolKey = preparation.toolKey,
                stepIndex = stepIndex,
                preparation = preparation
            )
            is AgentToolPreparation.Waiting -> BatchCallRun(
                callId = callId,
                envelope = envelope,
                callHash = callHash,
                invocationId = preparation.invocationId,
                toolKey = "",
                stepIndex = stepIndex,
                preparation = preparation
            )
            is AgentToolPreparation.Paused -> BatchCallRun(
                callId = callId,
                envelope = envelope,
                callHash = callHash,
                invocationId = "",
                toolKey = "",
                stepIndex = stepIndex,
                preparation = preparation
            )
        }
    }
}

/**
 * Settles frozen-but-undispatched calls as NOT_EXECUTED: terminal ledger row, typed `tool.result`
 * event and their observation (recorded by the caller in ordinal order). No executor is invoked.
 */
private suspend fun AgentPromptedLoopCoordinator.settleUndispatched(
    calls: List<BatchCallRun>,
    run: AgentRun,
    emit: suspend (AgentRun, String, String?, String?) -> AgentRun
): AgentRun {
    var currentRun = run
    for (call in calls) {
        if (call.preparation !is AgentToolPreparation.Ready ||
            call.observation != null ||
            call.started != null
        ) {
            continue
        }
        markBatchInvocationTerminal(call.invocationId, ToolInvocationStatus.NOT_EXECUTED)
        currentRun = emit(
            currentRun,
            AgentEventTypes.TOOL_RESULT,
            AgentToolLifecycle.notExecutedSummary(
                codec,
                call.toolKey,
                AgentToolResultStatus.NOT_EXECUTED,
                BATCH_ABORTED_ERROR_CODE
            ),
            call.invocationId
        )
        call.observation = toolObservation(
            AgentToolResultStatus.NOT_EXECUTED,
            "The batch was not dispatched because another call paused before execution.",
            errorCode = BATCH_ABORTED_ERROR_CODE
        )
    }
    return currentRun
}

internal fun AgentPromptedLoopCoordinator.nativeResultObservation(
    callId: String,
    observation: AgentJsonValue
): AgentJsonValue = transcriptV2.recordValue(
    AgentTranscriptV2Record.FunctionCallOutput(callId = callId, result = observation)
)

private fun AgentPromptedLoopCoordinator.pairedResult(
    call: BatchCallRun,
    observation: AgentJsonValue
): AgentTranscriptEntry.ToolResult = AgentTranscriptEntry.ToolResult(
    invocationId = call.invocationId,
    callHash = call.callHash,
    toolKey = call.toolKey,
    result = nativeResultObservation(call.callId, observation),
    providerCallId = call.callId
)

/**
 * Pairs every freshly appended tool result with the provider call it answers (in place).
 *
 * The record keeps the call id inside the reversible transcript output record, so a later reader can
 * recover the exact pair without trusting entry order alone.
 */
internal fun AgentPromptedLoopCoordinator.pairNativeResults(
    entries: MutableList<AgentTranscriptEntry>,
    fromIndex: Int,
    callId: String
) {
    for (index in fromIndex until entries.size) {
        val entry = entries[index]
        if (entry is AgentTranscriptEntry.ToolResult && entry.providerCallId == null) {
            entries[index] = entry.copy(
                providerCallId = callId,
                result = nativeResultObservation(callId, entry.result)
            )
        }
    }
}

private suspend fun AgentPromptedLoopCoordinator.markBatchInvocationTerminal(
    invocationId: String,
    status: ToolInvocationStatus
) {
    if (invocationId.isBlank()) return
    try {
        toolLedger.markTerminal(
            invocationId = invocationId,
            status = status,
            possibleSideEffects = false,
            resultRef = null,
            atMillis = clock.nowMillis()
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        // The typed transcript observation already fails closed; the row is closed best-effort.
    }
}

/**
 * Canonical assistant call record (transcript v2) for one provider call.
 *
 * A call without a provider id keeps the legacy V1 bridge envelope: it is prompted text, not a
 * native pair. Non-object arguments are recorded as `{}` and are rejected by the schema validator
 * before any executor runs.
 */
internal fun AgentPromptedLoopCoordinator.nativeCallEnvelope(call: AgentTurnToolCall): String {
    val parsed = (codec.parse(call.argumentsJson) as? AgentJsonParseResult.Parsed)?.value
    val arguments = parsed as? AgentJsonValue.Obj ?: AgentJsonValue.Obj(emptyList())
    val callId = call.callId
    if (callId == null) {
        return codec.write(
            AgentJsonValues.canonical(
                AgentJsonValue.Obj(
                    listOf(
                        "protocol" to AgentJsonValue.Str("mca.agent.v1"),
                        "kind" to AgentJsonValue.Str("tool"),
                        "call" to AgentJsonValue.Obj(
                            listOf(
                                "name" to AgentJsonValue.Str(call.name),
                                "arguments" to arguments
                            )
                        )
                    )
                )
            )
        )
    }
    return transcriptV2.encode(
        AgentTranscriptV2Record.AssistantFunctionCall(
            callId = callId,
            toolKey = call.name,
            arguments = arguments
        )
    )
}
