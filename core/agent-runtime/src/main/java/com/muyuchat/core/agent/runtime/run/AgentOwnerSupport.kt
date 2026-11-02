package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentCommandResult
import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentEventTypes
import com.muyuchat.core.agent.contract.AgentJournal
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentQuestion
import com.muyuchat.core.agent.contract.AgentQuestionStatus
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.AgentTransition
import com.muyuchat.core.agent.contract.AnswerAgentQuestion
import com.muyuchat.core.agent.contract.AnswerQuestionTransition
import com.muyuchat.core.agent.contract.AppendEventsTransition
import com.muyuchat.core.agent.contract.AskQuestionTransition
import com.muyuchat.core.agent.contract.CancelAgentRun
import com.muyuchat.core.agent.contract.ConsumeApprovalTransition
import com.muyuchat.core.agent.contract.ContinueAgentTask
import com.muyuchat.core.agent.contract.CreateAgentTask
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.contract.PauseAgentRun
import com.muyuchat.core.agent.contract.ResolveAgentApproval
import com.muyuchat.core.agent.contract.StartAgentRun
import com.muyuchat.core.agent.contract.UpdateRunTransition
import com.muyuchat.core.agent.contract.UpsertApprovalTransition
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.journal.AgentApprovalReader
import com.muyuchat.core.agent.runtime.journal.AgentCommandLedger
import com.muyuchat.core.agent.runtime.journal.AgentCommandRecord
import com.muyuchat.core.agent.runtime.journal.AgentJournalRejectedException
import com.muyuchat.core.agent.runtime.journal.AgentQuestionReader
import com.muyuchat.core.agent.runtime.journal.AgentTaskReader
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/** Idempotency replay, typed command errors and deterministic command fingerprints. */
internal suspend fun AgentRunOwner.replay(commandId: String, fingerprint: String): AgentCommandResult? {
        val record = ledger.findCommand(commandId) ?: return null
        if (record.contentHash != fingerprint) {
            return rejected(
                AgentErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT,
                "Command '$commandId' was already applied with different content."
            )
        }
        return AgentCommandResult.Accepted(
            appliedRevision = record.appliedRevision,
            runId = record.runId,
            idempotentReplay = true
        )
    }

internal suspend fun AgentRunOwner.commitAndRecord(
        commandId: String,
        fingerprint: String,
        runId: String?,
        transition: AgentTransition
    ): AgentCommandResult {
        val result = try {
            journal.commit(transition)
        } catch (rejected: AgentJournalRejectedException) {
            return fromJournal(rejected)
        }
        ledger.recordCommand(
            AgentCommandRecord(
                commandId = commandId,
                contentHash = fingerprint,
                appliedRevision = result.appliedRevision,
                taskRevision = result.taskRevision,
                runId = runId
            )
        )
        return AgentCommandResult.Accepted(result.appliedRevision, runId)
    }

internal fun AgentRunOwner.capacityRejection(): AgentCommandResult.Rejected? {
        if (active != null && waiting.size >= AgentContract.MAX_QUEUED_RUNS) {
            return rejected(
                AgentErrorCodes.RESOURCE_BUSY,
                "The agent run queue is full (${AgentContract.MAX_QUEUED_RUNS} waiting runs).",
                category = AgentErrorCategory.RESOURCE,
                retryability = AgentRetryability.SAFE_RETRY
            )
        }
        return null
    }

internal fun AgentRunOwner.revisionConflict(commandId: String): AgentCommandResult.Rejected = rejected(
        AgentErrorCodes.COMMAND_REVISION_CONFLICT,
        "Command '$commandId' expected a different revision; refresh and retry."
    )

internal fun AgentRunOwner.invalidState(commandId: String, message: String): AgentCommandResult.Rejected =
        rejected(AgentErrorCodes.COMMAND_INVALID_STATE, message)

internal fun AgentRunOwner.fromJournal(rejected: AgentJournalRejectedException): AgentCommandResult.Rejected =
        AgentCommandResult.Rejected(
            AgentError(
                code = rejected.errorCode,
                category = AgentErrorCategory.INTERNAL,
                retryability = AgentRetryability.AFTER_USER_ACTION,
                safeMessage = rejected.message.take(2 * 1024),
                correlationId = AGENT_RUN_OWNER_CORRELATION_ID
            )
        )

internal fun AgentRunOwner.rejected(
        code: String,
        message: String,
        category: AgentErrorCategory = AgentErrorCategory.INTERNAL,
        retryability: AgentRetryability = AgentRetryability.AFTER_USER_ACTION
    ): AgentCommandResult.Rejected = AgentCommandResult.Rejected(
        AgentError(
            code = code,
            category = category,
            retryability = retryability,
            safeMessage = message.take(2 * 1024),
            correlationId = AGENT_RUN_OWNER_CORRELATION_ID
        )
    )

internal fun AgentRunOwner.fingerprintCreate(command: CreateAgentTask): String = fingerprint(
        "type" to AgentJsonValue.Str("create_task"),
        "goal" to AgentJsonValue.Str(command.goal),
        "displayTitle" to AgentJsonValue.Str(command.displayTitle),
        "binding" to bindingJson(command.modelBinding),
        "policy" to policyJson(command.policy),
        "budget" to budgetJson(command.budget)
    )

internal fun AgentRunOwner.fingerprintStart(command: StartAgentRun): String = fingerprint(
        "type" to AgentJsonValue.Str("start_run"),
        "taskId" to AgentJsonValue.Str(command.taskId),
        "expectedTaskRevision" to AgentJsonValue.Num(command.expectedTaskRevision.toString())
    )

internal fun AgentRunOwner.fingerprintPause(command: PauseAgentRun): String = fingerprint(
        "type" to AgentJsonValue.Str("pause_run"),
        "runId" to AgentJsonValue.Str(command.runId),
        "expectedRunRevision" to AgentJsonValue.Num(command.expectedRunRevision.toString()),
        "reason" to AgentJsonValue.Str(command.reason.name)
    )

internal fun AgentRunOwner.fingerprintCancel(command: CancelAgentRun): String = fingerprint(
        "type" to AgentJsonValue.Str("cancel_run"),
        "runId" to AgentJsonValue.Str(command.runId),
        "expectedRunRevision" to AgentJsonValue.Num(command.expectedRunRevision.toString())
    )

internal fun AgentRunOwner.fingerprintContinue(command: ContinueAgentTask): String = fingerprint(
        "type" to AgentJsonValue.Str("continue_task"),
        "taskId" to AgentJsonValue.Str(command.taskId),
        "expectedTaskRevision" to AgentJsonValue.Num(command.expectedTaskRevision.toString()),
        "goalOverride" to (command.goalOverride?.let { AgentJsonValue.Str(it) } ?: AgentJsonValue.Null),
        "binding" to (command.modelBinding?.let { bindingJson(it) } ?: AgentJsonValue.Null),
        "policy" to (command.policy?.let { policyJson(it) } ?: AgentJsonValue.Null),
        "budget" to (command.budget?.let { budgetJson(it) } ?: AgentJsonValue.Null)
    )

internal fun AgentRunOwner.fingerprintAnswer(command: AnswerAgentQuestion): String = fingerprint(
        "type" to AgentJsonValue.Str("answer_question"),
        "runId" to AgentJsonValue.Str(command.runId),
        "expectedRunRevision" to AgentJsonValue.Num(command.expectedRunRevision.toString()),
        "questionId" to AgentJsonValue.Str(command.questionId),
        "answer" to AgentJsonValue.Str(command.answer)
    )

internal fun AgentRunOwner.fingerprintApproval(command: ResolveAgentApproval): String = fingerprint(
        "type" to AgentJsonValue.Str("resolve_approval"),
        "runId" to AgentJsonValue.Str(command.runId),
        "expectedRunRevision" to AgentJsonValue.Num(command.expectedRunRevision.toString()),
        "approvalId" to AgentJsonValue.Str(command.approvalId),
        "decision" to AgentJsonValue.Str(command.decision.name)
    )

internal fun AgentRunOwner.fingerprint(vararg fields: Pair<String, AgentJsonValue>): String =
        AgentHashes.sha256HexUtf8(
            codec.write(AgentJsonValues.canonical(AgentJsonValue.Obj(fields.toList())))
        )

internal fun AgentRunOwner.bindingJson(binding: AgentModelBinding): AgentJsonValue = AgentJsonValue.Obj(
        listOf(
            "providerId" to AgentJsonValue.Str(binding.providerId),
            "modelId" to AgentJsonValue.Str(binding.modelId),
            "runtime" to AgentJsonValue.Str(binding.runtime),
            "assetFingerprint" to AgentJsonValue.Str(binding.assetFingerprint),
            "protocolMode" to AgentJsonValue.Str(binding.protocolMode.name),
            "modelDisplayName" to AgentJsonValue.Str(binding.modelDisplayName),
            "contextWindowTokens" to (
                binding.contextWindowTokens?.let { AgentJsonValue.Num(it.toString()) }
                    ?: AgentJsonValue.Null
                ),
            "samplingJson" to AgentJsonValue.Str(binding.samplingJson),
            "capabilityEvidenceVersion" to (
                binding.capabilityEvidenceVersion?.let { AgentJsonValue.Str(it) } ?: AgentJsonValue.Null
                )
        )
    )

internal fun AgentRunOwner.policyJson(policy: AgentRunPolicy): AgentJsonValue = AgentJsonValue.Obj(
        listOf(
            "networkPolicy" to AgentJsonValue.Str(policy.networkPolicy.name),
            "policyVersion" to AgentJsonValue.Num(policy.policyVersion.toString()),
            "enabledToolKeys" to AgentJsonValue.Arr(
                policy.enabledToolKeys?.sorted()?.map { AgentJsonValue.Str(it) }.orEmpty()
            ),
            "skillIds" to AgentJsonValue.Arr(policy.skillIds.sorted().map { AgentJsonValue.Str(it) }),
            "offlineForced" to AgentJsonValue.Bool(policy.offlineForced)
        )
    )

internal fun AgentRunOwner.budgetJson(budget: AgentBudget): AgentJsonValue = AgentJsonValue.Obj(
        listOf(
            "modelAttempts" to AgentJsonValue.Num(budget.modelAttempts.toString()),
            "toolInvocations" to AgentJsonValue.Num(budget.toolInvocations.toString()),
            "callsPerModelResponse" to AgentJsonValue.Num(budget.callsPerModelResponse.toString()),
            "protocolRepairs" to AgentJsonValue.Num(budget.protocolRepairs.toString()),
            "modelWallTimeMillis" to AgentJsonValue.Num(budget.modelWallTimeMillis.toString()),
            "toolTimeoutMillis" to AgentJsonValue.Num(budget.toolTimeoutMillis.toString()),
            "runActiveTimeMillis" to AgentJsonValue.Num(budget.runActiveTimeMillis.toString()),
            "resourceWaitMillis" to AgentJsonValue.Num(budget.resourceWaitMillis.toString()),
            "toolResultToModelBytes" to AgentJsonValue.Num(budget.toolResultToModelBytes.toString()),
            "toolResultPersistBytes" to AgentJsonValue.Num(budget.toolResultPersistBytes.toString()),
            "textOutputTokens" to AgentJsonValue.Num(budget.textOutputTokens.toString()),
            "totalGeneratedTokens" to AgentJsonValue.Num(budget.totalGeneratedTokens.toString())
        )
    )

