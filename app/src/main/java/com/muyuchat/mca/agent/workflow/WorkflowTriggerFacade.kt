package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import kotlinx.coroutines.CancellationException

/** Stable codes of the manual/schedule entry facade. */
object WorkflowEntryErrorCodes {
    const val RECEIPT_STORE_UNAVAILABLE = "WF_RECEIPT_STORE_UNAVAILABLE"
    const val RECEIPT_CORRUPT = "WF_RECEIPT_CORRUPT"
    const val SCHEDULE_SLOT_INVALID = "WF_SCHEDULE_SLOT_INVALID"
}

/**
 * One admin/user manual request.
 *
 * [commandId] is the idempotency key: repeating the same command id returns the original receipt and
 * never starts a second run. [runId] optionally pins/replays an existing checkpointed run. [agentTask]
 * is the frozen model plan an `AGENT_TASK` node needs; null keeps that node typed NOT_SUPPORTED.
 */
data class WorkflowManualRunCommand(
    val commandId: String,
    val definitionId: String,
    val version: Long,
    val runId: String? = null,
    val agentTask: WorkflowAgentTaskPlan? = null,
    /** Existing task/workspace identity for TOOL nodes; null keeps them typed NOT_SUPPORTED. */
    val toolScope: WorkflowToolScope? = null
) {
    init {
        AgentContract.requireId(commandId, "manual commandId")
        require(definitionId.isNotBlank()) { "manual definitionId must not be blank" }
        require(version >= 1L) { "manual version must be positive" }
    }
}

/** One explicit schedule slot request (replay path for an already claimed slot). */
data class WorkflowScheduledRunCommand(
    val commandId: String,
    val definitionId: String,
    val version: Long,
    val slotMillis: Long,
    val runId: String? = null,
    val agentTask: WorkflowAgentTaskPlan? = null,
    /** Existing task/workspace identity for TOOL nodes; null keeps them typed NOT_SUPPORTED. */
    val toolScope: WorkflowToolScope? = null
) {
    init {
        AgentContract.requireId(commandId, "scheduled commandId")
        require(definitionId.isNotBlank()) { "scheduled definitionId must not be blank" }
        require(version >= 1L) { "scheduled version must be positive" }
        require(slotMillis > 0L) { "scheduled slot must be positive" }
    }
}

/**
 * One external-intent run request.
 *
 * [commandId] is the caller-supplied idempotency key (the trigger derives it from the intent
 * identity), so a duplicated broadcast returns the original receipt instead of starting a second
 * run. There is no slot: an intent is an explicit, one-off request.
 */
data class WorkflowIntentRunCommand(
    val commandId: String,
    val definitionId: String,
    val version: Long,
    val runId: String? = null,
    val agentTask: WorkflowAgentTaskPlan? = null,
    val toolScope: WorkflowToolScope? = null
) {
    init {
        AgentContract.requireId(commandId, "intent commandId")
        require(definitionId.isNotBlank()) { "intent definitionId must not be blank" }
        require(version >= 1L) { "intent version must be positive" }
    }
}

/**
 * Manual/schedule entry facade over [WorkflowEntryService] and [WorkflowScheduleTrigger] (N20/N22).
 *
 * This is the admin service the user-facing entry calls, not a UI: it owns no ViewModel, no
 * WorkManager and no second scheduler. Manual, scheduled and intent runs share one execution port
 * and one receipt store, so:
 * - a repeated [WorkflowManualRunCommand.commandId] returns the stored receipt (`idempotentReplay`);
 * - a repeated run id (double click, replay after a lost response) is matched by run id;
 * - a scheduled wake goes through [WorkflowScheduleTrigger] with the same receipt check, while the
 *   slot claim in the schedule store stays the at-most-once authority;
 * - an external intent goes through [runIntent] with a command id the trigger derived from the
 *   intent identity, so a duplicated broadcast is a replay and not a second run.
 *
 * Receipts are evidence of a terminal result. The run checkpoint remains the authority that prevents
 * node re-execution, so a receipt-store outage does not turn a replay into a second execution.
 */
class WorkflowTriggerFacade(
    private val entry: WorkflowEntryService,
    private val receipts: WorkflowEntryReceiptStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val codec: StrictJsonCodec = StrictAgentJsonCodec()

    suspend fun runManual(command: WorkflowManualRunCommand): WorkflowEntryReceipt = runCommand(
        commandId = command.commandId,
        definitionId = command.definitionId,
        version = command.version,
        trigger = WorkflowTriggerKind.MANUAL,
        slotMillis = null,
        requestedRunId = command.runId,
        agentTask = command.agentTask,
        toolScope = command.toolScope
    )

    suspend fun runScheduled(command: WorkflowScheduledRunCommand): WorkflowEntryReceipt = runCommand(
        commandId = command.commandId,
        definitionId = command.definitionId,
        version = command.version,
        trigger = WorkflowTriggerKind.SCHEDULE,
        slotMillis = command.slotMillis,
        requestedRunId = command.runId
            ?: defaultScheduledRunId(command.definitionId, command.version, command.slotMillis),
        agentTask = command.agentTask,
        toolScope = command.toolScope
    )

    /** External-intent entry (registered trigger). Same idempotency, no schedule slot. */
    suspend fun runIntent(command: WorkflowIntentRunCommand): WorkflowEntryReceipt = runCommand(
        commandId = command.commandId,
        definitionId = command.definitionId,
        version = command.version,
        trigger = WorkflowTriggerKind.INTENT,
        slotMillis = null,
        requestedRunId = command.runId,
        agentTask = command.agentTask,
        toolScope = command.toolScope
    )

    /**
     * Scheduler loop adapter. `agentTask` is only a fallback for callers that never persist a plan;
     * a schedule record's own frozen plan (carried on [WorkflowExecutionRequest.agentTaskPlan])
     * always wins, so a wake after process death cannot use another schedule's model.
     */
    fun scheduleTrigger(
        store: WorkflowScheduleStore,
        agentTask: WorkflowAgentTaskPlan? = null
    ): WorkflowScheduleTrigger = WorkflowScheduleTrigger(
        store = store,
        clock = clock,
        runIdFactory = { definitionId, version, slotMillis ->
            defaultScheduledRunId(definitionId, version, slotMillis)
        },
        executor = { request, slotMillis ->
            val runId = request.runId
                ?: defaultScheduledRunId(request.definitionId, request.version, slotMillis)
            runCommand(
                commandId = scheduledCommandId(runId),
                definitionId = request.definitionId,
                version = request.version,
                trigger = WorkflowTriggerKind.SCHEDULE,
                slotMillis = slotMillis,
                requestedRunId = runId,
                agentTask = request.agentTaskPlan ?: agentTask,
                toolScope = request.toolScope
            ).toExecutionResult()
        }
    )

    private suspend fun runCommand(
        commandId: String,
        definitionId: String,
        version: Long,
        trigger: WorkflowTriggerKind,
        slotMillis: Long?,
        requestedRunId: String?,
        agentTask: WorkflowAgentTaskPlan?,
        toolScope: WorkflowToolScope?
    ): WorkflowEntryReceipt {
        val cached = try {
            receipts.get(commandId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return failureReceipt(
                commandId,
                definitionId,
                version,
                trigger,
                slotMillis,
                WorkflowEntryErrorCodes.RECEIPT_STORE_UNAVAILABLE,
                "The workflow receipt store is unavailable."
            )
        }
        if (cached != null) return cached.copy(idempotentReplay = true)

        if (requestedRunId != null) {
            val byRunId = try {
                receipts.findByRunId(requestedRunId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return failureReceipt(
                    commandId,
                    definitionId,
                    version,
                    trigger,
                    slotMillis,
                    WorkflowEntryErrorCodes.RECEIPT_STORE_UNAVAILABLE,
                    "The workflow receipt store is unavailable."
                )
            }
            if (byRunId != null) return byRunId.copy(idempotentReplay = true)
        }

        val result = when (trigger) {
            WorkflowTriggerKind.MANUAL -> entry.runManual(
                definitionId = definitionId,
                version = version,
                agentTask = agentTask,
                runId = requestedRunId,
                toolScope = toolScope
            )

            WorkflowTriggerKind.INTENT -> entry.runIntent(
                definitionId = definitionId,
                version = version,
                agentTask = agentTask,
                runId = requestedRunId,
                toolScope = toolScope
            )

            WorkflowTriggerKind.SCHEDULE -> {
                val slot = slotMillis
                    ?: return failureReceipt(
                        commandId,
                        definitionId,
                        version,
                        trigger,
                        null,
                        WorkflowEntryErrorCodes.SCHEDULE_SLOT_INVALID,
                        "A scheduled run needs its slot."
                    )
                entry.runScheduled(
                    definitionId = definitionId,
                    version = version,
                    slotMillis = slot,
                    agentTask = agentTask,
                    runId = requestedRunId,
                    toolScope = toolScope
                )
            }
        }
        val receipt = receiptOf(commandId, definitionId, version, trigger, slotMillis, result)
        try {
            receipts.put(receipt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The run already happened; the checkpoint makes a replay safe, so a receipt outage is
            // reported through the missing row rather than by faking a different result.
        }
        return receipt
    }

    private fun receiptOf(
        commandId: String,
        definitionId: String,
        version: Long,
        trigger: WorkflowTriggerKind,
        slotMillis: Long?,
        result: WorkflowExecutionResult
    ): WorkflowEntryReceipt = WorkflowEntryReceipt(
        commandId = commandId,
        definitionId = definitionId,
        version = version,
        trigger = trigger,
        runId = result.runId,
        slotMillis = slotMillis,
        status = when (result) {
            is WorkflowExecutionResult.Completed -> WorkflowEntryStatus.COMPLETED
            is WorkflowExecutionResult.Failed -> WorkflowEntryStatus.FAILED
            is WorkflowExecutionResult.Rejected -> WorkflowEntryStatus.REJECTED
            is WorkflowExecutionResult.Suspended -> WorkflowEntryStatus.PAUSED
            is WorkflowExecutionResult.SuspendedForUser -> WorkflowEntryStatus.PAUSED
        },
        errorCode = when (result) {
            is WorkflowExecutionResult.Completed -> null
            is WorkflowExecutionResult.Failed -> result.errorCode
            is WorkflowExecutionResult.Rejected -> result.errorCode
            is WorkflowExecutionResult.Suspended -> "WF_WAITING_APPROVAL"
            is WorkflowExecutionResult.SuspendedForUser -> "WF_WAITING_USER"
        },
        safeMessage = when (result) {
            is WorkflowExecutionResult.Completed -> null
            is WorkflowExecutionResult.Failed -> result.safeMessage.take(MAX_MESSAGE_CHARS)
            is WorkflowExecutionResult.Rejected -> result.safeMessage.take(MAX_MESSAGE_CHARS)
            is WorkflowExecutionResult.Suspended ->
                "The workflow run is waiting for approval in its Agent run."
            is WorkflowExecutionResult.SuspendedForUser ->
                "The workflow run is waiting for your decision in its Agent run."
        },
        outputsJson = (result as? WorkflowExecutionResult.Completed)?.let { completed ->
            codec.write(AgentJsonValues.obj(completed.outputs.map { it.key to it.value }))
        },
        completedAtMillis = clock().coerceAtLeast(1L)
    )

    private fun failureReceipt(
        commandId: String,
        definitionId: String,
        version: Long,
        trigger: WorkflowTriggerKind,
        slotMillis: Long?,
        code: String,
        message: String
    ): WorkflowEntryReceipt = WorkflowEntryReceipt(
        commandId = commandId,
        definitionId = definitionId,
        version = version,
        trigger = trigger,
        runId = null,
        slotMillis = slotMillis,
        status = WorkflowEntryStatus.REJECTED,
        errorCode = code,
        safeMessage = message,
        completedAtMillis = clock().coerceAtLeast(1L)
    )

    private fun WorkflowEntryReceipt.toExecutionResult(): WorkflowExecutionResult {
        val effectiveRunId = runId
        return when (status) {
            WorkflowEntryStatus.COMPLETED -> {
                val outputs = parseOutputs(outputsJson)
                    ?: return WorkflowExecutionResult.Rejected(
                        effectiveRunId,
                        WorkflowEntryErrorCodes.RECEIPT_CORRUPT,
                        "The stored workflow receipt cannot reproduce its outputs."
                    )
                WorkflowExecutionResult.Completed(
                    runId = effectiveRunId
                        ?: return WorkflowExecutionResult.Rejected(
                            null,
                            WorkflowEntryErrorCodes.RECEIPT_CORRUPT,
                            "A completed workflow receipt has no run id."
                        ),
                    outputs = outputs,
                    outcomes = emptyList()
                )
            }

            WorkflowEntryStatus.FAILED -> WorkflowExecutionResult.Failed(
                runId = effectiveRunId.orEmpty(),
                errorCode = errorCode ?: "WF_ENTRY_FAILED",
                safeMessage = safeMessage ?: "The workflow entry point failed.",
                outcomes = emptyList()
            )

            WorkflowEntryStatus.REJECTED -> WorkflowExecutionResult.Rejected(
                runId = effectiveRunId,
                errorCode = errorCode ?: "WF_ENTRY_REJECTED",
                safeMessage = safeMessage ?: "The workflow entry point rejected the request."
            )

            // A replayed receipt cannot reconstruct an approval object; the live Agent run is the
            // authority. Report the real parked state instead of inventing a resume.
            WorkflowEntryStatus.PAUSED -> WorkflowExecutionResult.Rejected(
                runId = effectiveRunId,
                errorCode = errorCode ?: "WF_WAITING_APPROVAL",
                safeMessage = safeMessage ?: "The workflow run is parked in its Agent run."
            )
        }
    }

    private fun parseOutputs(json: String?): Map<String, AgentJsonValue>? {
        if (json == null) return emptyMap()
        val parsed = codec.parse(json) as? AgentJsonParseResult.Parsed ?: return null
        val objectValue = parsed.value as? AgentJsonValue.Obj ?: return null
        return objectValue.fields.toMap()
    }

    private companion object {
        const val MAX_MESSAGE_CHARS = 400
    }
}

/** Deterministic, checkpoint-safe run id for one schedule slot. */
internal fun defaultScheduledRunId(
    definitionId: String,
    version: Long,
    slotMillis: Long
): String = "wfrun-sched-" + AgentHashes
    .sha256HexUtf8("$definitionId@$version#$slotMillis")
    .take(40)

/** Deterministic, bounded idempotency key for a schedule-triggered run. */
internal fun scheduledCommandId(runId: String): String =
    "sched-" + AgentHashes.sha256HexUtf8(runId).take(48)
