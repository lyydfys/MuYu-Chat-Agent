package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationLedger
import com.muyuchat.core.agent.runtime.journal.AgentToolInvocationUpdate
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Execution half of the single tool-invocation pipeline (N03 stage 4).
 *
 * The proposal/freeze pipeline decides *whether* a frozen command may run; this class owns the
 * *execution lifecycle*: run/epoch/policy/expiry checks, persisted-command binding, catalog schema
 * freshness, RUNNING CAS, `tool.started`, executor invocation, terminal settlement and `tool.result`.
 * It still does not append events itself: the caller passes one emit callback, so the run journal
 * keeps a single writer.
 *
 * The lifecycle is split so a read-only batch can overlap only the executor calls (N06):
 *  * [begin] performs every check and the RUNNING CAS, writes `tool.started`, and returns the
 *    executor plus its context; it never invokes the executor.
 *  * [invoke] calls the executor and maps a crash to a typed UNKNOWN result. It writes nothing.
 *  * [settle] commits the terminal ledger row and `tool.result`; callers must run it on the single
 *    writer (serially and in input ordinal order).
 * [execute] keeps the historical single-call behavior by composing the three stages.
 */
class AgentToolExecutionPipeline(
    private val toolLedger: AgentToolInvocationLedger,
    private val clock: AgentMonotonicClock,
    private val codec: StrictJsonCodec = StrictAgentJsonCodec(),
    private val catalog: AgentToolCatalog
) {

    /** Outcome of the pre-execution stage: either the run may invoke the executor, or it is refused. */
    sealed interface Begin {
        data class Started(
            val run: AgentRun,
            val command: FrozenToolCommand,
            val executor: AgentToolExecutor,
            val context: AgentToolContext
        ) : Begin

        data class Refused(val outcome: AgentToolExecutionOutcome) : Begin
    }

    suspend fun execute(
        authorization: AuthorizedToolCommand,
        run: AgentRun,
        task: AgentTask,
        stepIndex: Int,
        emit: suspend (AgentRun, String, String?, String?) -> AgentRun,
        readRun: suspend (String) -> AgentRun?
    ): AgentToolExecutionOutcome {
        val began = begin(authorization, run, task, stepIndex, emit, readRun)
        if (began is Begin.Refused) return began.outcome
        val started = began as Begin.Started
        val result = try {
            invoke(started)
        } catch (cancelled: CancellationException) {
            settleCancelled(started, started.run, emit)
            throw cancelled
        }
        return settle(started, result, started.run, emit, readRun)
    }

    internal suspend fun begin(
        authorization: AuthorizedToolCommand,
        run: AgentRun,
        task: AgentTask,
        stepIndex: Int,
        emit: suspend (AgentRun, String, String?, String?) -> AgentRun,
        readRun: suspend (String) -> AgentRun?
    ): Begin {
        val command = authorization.command
        val now = clock.nowMillis()

        suspend fun refuse(errorCode: String, message: String): Begin {
            val persisted = toolLedger.readInvocation(command.invocationId)
            var runAfter = run
            // Only a row this attempt could have frozen itself is settled. WAITING_APPROVAL and
            // APPROVED belong to the approval transaction: a refused resume (expired/stale/foreign
            // command) must never cancel a pending approval or erase a durable dispatch intent.
            if (persisted != null &&
                (persisted.status == ToolInvocationStatus.PROPOSED ||
                    persisted.status == ToolInvocationStatus.VALIDATED)
            ) {
                withContext(NonCancellable) {
                    toolLedger.markTerminal(
                        invocationId = command.invocationId,
                        status = ToolInvocationStatus.NOT_EXECUTED,
                        possibleSideEffects = false,
                        resultRef = null,
                        atMillis = clock.nowMillis()
                    )
                }
                runAfter = try {
                    emit(
                        run,
                        AgentEventTypes.TOOL_RESULT,
                        AgentToolLifecycle.notExecutedSummary(
                            codec,
                            command.toolKey,
                            AgentToolResultStatus.NOT_EXECUTED,
                            errorCode
                        ),
                        command.invocationId
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // The terminal invocation row is authoritative; the audit event is best effort.
                    run
                }
            }
            return Begin.Refused(
                AgentToolExecutionOutcome(
                    run = runAfter,
                    result = AgentToolResult(
                        status = AgentToolResultStatus.NOT_EXECUTED,
                        summary = message,
                        errorCode = errorCode
                    )
                )
            )
        }

        if (command.runId != run.runId ||
            command.runEpoch != run.epoch ||
            command.policyVersion != run.policy.policyVersion
        ) {
            return refuse(
                AgentErrorCodes.INTERNAL_INVARIANT,
                "The authorized command does not belong to this run."
            )
        }
        if (command.expiresAtMillis <= now) {
            return refuse(
                AgentErrorCodes.PERMISSION_EXPIRED,
                "The frozen tool command expired before execution."
            )
        }
        val persisted = toolLedger.readInvocation(command.invocationId) ?: return refuse(
            AgentErrorCodes.INTERNAL_INVARIANT,
            "The frozen tool invocation is not recorded."
        )
        if (!persisted.matchesFrozenCommand(command)) {
            return refuse(
                AgentErrorCodes.INTERNAL_INVARIANT,
                "The authorized command does not match the persisted invocation."
            )
        }
        val executor = catalog.find(command.toolKey) ?: return refuse(
            AgentErrorCodes.TOOL_NOT_FOUND,
            "The requested tool is not available in this run."
        )
        val descriptor = executor.descriptor
        if (descriptor.toolKey != command.toolKey ||
            descriptor.version != command.toolVersion ||
            descriptor.schemaHash != command.schemaHash
        ) {
            return refuse(
                AgentToolLifecycle.TOOL_SCHEMA_STALE,
                "The tool definition changed after this call was frozen."
            )
        }
        val running = toolLedger.markRunning(command.invocationId, now)
        if (running !is AgentToolInvocationUpdate.Updated) {
            return replayRefusal(command, run)
        }
        val startedRun = try {
            emit(
                run,
                AgentEventTypes.TOOL_STARTED,
                AgentToolLifecycle.startedSummary(codec, command),
                command.invocationId
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { markExecutionNotStarted(command.invocationId) }
            throw cancelled
        } catch (rejected: Throwable) {
            withContext(NonCancellable) { markExecutionNotStarted(command.invocationId) }
            throw rejected
        }
        val context = AgentToolContext(
            taskId = task.taskId,
            workspaceId = task.workspaceId,
            runId = run.runId,
            stepIndex = stepIndex,
            runEpoch = run.epoch,
            policyVersion = run.policy.policyVersion,
            workspaceReadGranted = true
        )
        return Begin.Started(
            run = startedRun,
            command = command,
            executor = executor,
            context = context
        )
    }

    /**
     * Invokes exactly one executor. It touches no journal/ledger state, so several calls may run
     * concurrently; a crash becomes a typed UNKNOWN observation (possible side effects stay visible)
     * and only coroutine cancellation propagates.
     */
    internal suspend fun invoke(started: Begin.Started): AgentToolResult = try {
        started.executor.execute(started.command, started.context)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        AgentToolResult(
            status = AgentToolResultStatus.UNKNOWN,
            summary = "Tool execution failed unexpectedly; side effects are unknown.",
            errorCode = AgentErrorCodes.TOOL_RESULT_UNKNOWN,
            possibleSideEffects = true
        )
    }

    internal suspend fun settle(
        started: Begin.Started,
        result: AgentToolResult,
        run: AgentRun,
        emit: suspend (AgentRun, String, String?, String?) -> AgentRun,
        readRun: suspend (String) -> AgentRun?
    ): AgentToolExecutionOutcome =
        completeExecution(run, started.command, result, emit, readRun)

    /**
     * Terminal settlement for a cancelled call: the row is closed and the audit event is bounded.
     *
     * Cancellation here always happens after the RUNNING gate, so the executor may have started and
     * produced effects. The row therefore records `possibleSideEffects=true`; claiming otherwise
     * would let a later recovery treat an uncertain cancellation as a clean no-op.
     *
     * [run] is the latest committed run revision; settlement must chain from it because several
     * batch calls may have been admitted after this call's own `tool.started` revision.
     */
    internal suspend fun settleCancelled(
        started: Begin.Started,
        run: AgentRun,
        emit: suspend (AgentRun, String, String?, String?) -> AgentRun
    ): AgentRun = withContext(NonCancellable) {
        toolLedger.markTerminal(
            invocationId = started.command.invocationId,
            status = ToolInvocationStatus.CANCELLED,
            possibleSideEffects = true,
            resultRef = null,
            atMillis = clock.nowMillis()
        )
        runCatching {
            emit(
                run,
                AgentEventTypes.TOOL_RESULT,
                AgentToolLifecycle.notExecutedSummary(
                    codec,
                    started.command.toolKey,
                    AgentToolResultStatus.CANCELLED,
                    AgentErrorCodes.TOOL_FAILED
                ),
                started.command.invocationId
            )
        }.getOrDefault(run)
    }

    private suspend fun markExecutionNotStarted(invocationId: String) {
        toolLedger.markTerminal(
            invocationId = invocationId,
            status = ToolInvocationStatus.NOT_EXECUTED,
            possibleSideEffects = false,
            resultRef = null,
            atMillis = clock.nowMillis()
        )
    }

    /**
     * A lost RUNNING CAS means another attempt owns the execution gate or the row already settled.
     * The row is the only evidence that the side effect may have happened, so this path never
     * rewrites it as NOT_EXECUTED and never dispatches again. Pre-execution rows keep the historical
     * fail-closed NOT_EXECUTED result; anything at/after the gate becomes typed UNKNOWN for review.
     */
    private suspend fun replayRefusal(command: FrozenToolCommand, run: AgentRun): Begin {
        val current = try {
            toolLedger.readInvocation(command.invocationId)
        } catch (_: Throwable) {
            null
        }
        val result = when (current?.status) {
            ToolInvocationStatus.RUNNING,
            ToolInvocationStatus.APPROVED,
            ToolInvocationStatus.UNKNOWN -> AgentToolResult(
                status = AgentToolResultStatus.UNKNOWN,
                summary = "The invocation was already dispatched and its effect cannot be proven.",
                errorCode = AgentErrorCodes.TOOL_RESULT_UNKNOWN,
                possibleSideEffects = true
            )
            ToolInvocationStatus.SUCCEEDED,
            ToolInvocationStatus.FAILED -> AgentToolResult(
                status = AgentToolResultStatus.UNKNOWN,
                summary = "The invocation already settled; this replay cannot reuse its stored result.",
                errorCode = AgentErrorCodes.TOOL_RESULT_UNKNOWN,
                possibleSideEffects = true
            )
            ToolInvocationStatus.DENIED -> AgentToolResult(
                status = AgentToolResultStatus.DENIED,
                summary = "The invocation was already settled as denied.",
                errorCode = AgentErrorCodes.PERMISSION_DENIED
            )
            ToolInvocationStatus.CANCELLED -> AgentToolResult(
                status = AgentToolResultStatus.CANCELLED,
                summary = "The invocation was already settled as cancelled.",
                errorCode = AgentErrorCodes.TOOL_FAILED
            )
            else -> AgentToolResult(
                status = AgentToolResultStatus.NOT_EXECUTED,
                summary = "The invocation cannot start execution because it is no longer executable.",
                errorCode = AgentErrorCodes.INTERNAL_INVARIANT
            )
        }
        return Begin.Refused(AgentToolExecutionOutcome(run = run, result = result))
    }

    private suspend fun completeExecution(
        run: AgentRun,
        command: FrozenToolCommand,
        result: AgentToolResult,
        emit: suspend (AgentRun, String, String?, String?) -> AgentRun,
        readRun: suspend (String) -> AgentRun?
    ): AgentToolExecutionOutcome {
        withContext(NonCancellable) {
            toolLedger.markTerminal(
                invocationId = command.invocationId,
                status = result.status.toInvocationStatus(),
                possibleSideEffects = result.possibleSideEffects,
                resultRef = result.fullResultRef,
                atMillis = clock.nowMillis()
            )
        }
        val runAfter = try {
            emit(
                run,
                AgentEventTypes.TOOL_RESULT,
                AgentToolLifecycle.resultSummary(codec, command.toolKey, result),
                command.invocationId
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The durable invocation row already holds the terminal result; a late revision conflict
            // on the audit event must not rewrite it. Continue from the latest run revision.
            readRun(run.runId) ?: run
        }
        return AgentToolExecutionOutcome(runAfter, result)
    }
}
