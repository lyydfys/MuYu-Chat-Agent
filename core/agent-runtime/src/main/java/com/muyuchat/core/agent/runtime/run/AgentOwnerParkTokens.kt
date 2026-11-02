package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentQuestionStatus
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import kotlinx.coroutines.CancellationException

/** Deterministic wake identity: a replayed park rewrites the same token, never a second row. */
internal fun agentParkWakeId(runId: String, revision: Long, kind: AgentParkKind): String =
    "wake-" + AgentHashes.sha256HexUtf8("$runId@$revision@${kind.name}").take(32)

/**
 * Persists the durable token after the park's run transition already committed.
 *
 * A failure (oversized transcript, unavailable store) never rolls the durable park back: the run row
 * stays authoritative, the in-memory transcript still serves this process, and a later restart
 * fails closed through the startup fence instead of resuming from truncated material.
 */
internal suspend fun AgentRunOwner.recordParkToken(
    run: AgentRun,
    transcript: AgentLoopTranscript,
    kind: AgentParkKind,
    approvalId: String? = null,
    invocationId: String? = null,
    questionId: String? = null
) {
    val token = try {
        AgentParkToken(
            runId = run.runId,
            epoch = run.epoch,
            checkpointRevision = run.revision,
            wakeId = agentParkWakeId(run.runId, run.revision, kind),
            kind = kind,
            approvalId = approvalId,
            invocationId = invocationId,
            questionId = questionId,
            transcriptJson = AgentParkTranscriptCodec.encode(transcript),
            createdAtMillis = clock.nowMillis()
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        return
    }
    runCatching { parkTokens.record(token) }
}

/**
 * Returns the durable transcript only when the token still belongs to this exact run revision and
 * park kind. A stale token is not resume material.
 */
internal suspend fun AgentRunOwner.parkedTranscript(
    run: AgentRun,
    kind: AgentParkKind
): AgentLoopTranscript? {
    val token = runCatching { parkTokens.load(run.runId) }.getOrNull() ?: return null
    if (token.kind != kind ||
        token.epoch != run.epoch ||
        token.checkpointRevision != run.revision
    ) {
        return null
    }
    return try {
        token.transcript()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }
}

internal suspend fun AgentRunOwner.clearParkToken(runId: String) {
    runCatching { parkTokens.delete(runId) }
}

/**
 * Rebuilds the in-memory pending-approval binding from durable rows after a process restart.
 *
 * The approval and invocation rows are authoritative; the transcript token supplies the exact
 * `callHash` input (the last assistant envelope). Anything missing or no longer bound to the current
 * epoch/policy returns null so the command fails closed instead of inventing authority.
 */
internal suspend fun AgentRunOwner.rebuildPendingApproval(
    run: AgentRun,
    approvalId: String
): PendingApprovalExecution? {
    val approval: AgentApproval = runCatching { approvalReader.readApproval(approvalId) }
        .getOrNull()
        ?.takeIf {
            it.status == AgentApprovalStatus.PENDING &&
                it.runEpoch == run.epoch &&
                it.policyVersion == run.policy.policyVersion
        }
        ?: return null
    val invocation = runCatching { toolLedger.readInvocation(approval.invocationId) }
        .getOrNull()
        ?.takeIf {
            it.runId == run.runId && it.status == ToolInvocationStatus.WAITING_APPROVAL
        }
        ?: return null
    val transcript = parkedTranscript(run, AgentParkKind.APPROVAL) ?: return null
    val envelope = transcript.entries
        .asReversed()
        .firstNotNullOfOrNull { it as? AgentTranscriptEntry.AssistantEnvelope }
        ?: return null
    return PendingApprovalExecution(
        approvalId = approval.approvalId,
        invocationId = invocation.invocationId,
        callHash = AgentHashes.sha256HexUtf8(envelope.rawEnvelope),
        toolKey = invocation.toolKey
    )
}

/**
 * Validates one wake without ever executing a resume.
 *
 * A parked run stays parked (the caller re-arms its UI/worker); a resume that already committed
 * `RUNNING` means the process died before the launch, so the token is consumed and the caller gets a
 * typed stale result. Approval authority is never reconstructed from storage here.
 */
internal suspend fun AgentRunOwner.wakeParkedLocked(
    runId: String,
    wakeId: String
): AgentParkWakeResult {
    val token = runCatching { parkTokens.load(runId) }.getOrNull()
        ?: return AgentParkWakeResult.AlreadyConsumed
    if (token.wakeId != wakeId) return AgentParkWakeResult.AlreadyConsumed
    val snapshot = journal.readRun(runId)
    if (snapshot == null) {
        clearParkToken(runId)
        return AgentParkWakeResult.Stale(
            runId,
            AgentErrorCodes.COMMAND_NOT_FOUND,
            "The parked run no longer exists."
        )
    }
    val run = snapshot.run
    if (run.epoch != token.epoch || token.checkpointRevision > run.revision) {
        clearParkToken(runId)
        return AgentParkWakeResult.Stale(
            runId,
            AgentErrorCodes.RESOURCE_LEASE_LOST,
            "The park token belongs to a stale run revision."
        )
    }
    when (run.state) {
        AgentRunState.WAITING_APPROVAL, AgentRunState.WAITING_USER -> {
            if (token.checkpointRevision != run.revision) {
                clearParkToken(runId)
                return AgentParkWakeResult.Stale(
                    runId,
                    AgentErrorCodes.RESOURCE_LEASE_LOST,
                    "The parked run moved past the token revision."
                )
            }
            return AgentParkWakeResult.StillParked(runId, token.kind)
        }

        AgentRunState.RUNNING -> {
            // A user answer committed RUNNING before the process died. The durable question answer
            // is the exact resume input; no authorization is involved, so this is safe to requeue.
            // An approval park is deliberately not resumed: its one-shot authorization is gone.
            resumeAnsweredUserPark(run, token)?.let { return it }
            return pauseLostResume(run, token)
        }

        else -> Unit
    }
    clearParkToken(runId)
    return AgentParkWakeResult.Stale(
        runId,
        AgentErrorCodes.PERMISSION_EXPIRED,
        "The parked resume lost its one-shot authorization and must be resolved again."
    )
}

/**
 * Fails a RUNNING run whose durable resume material can no longer be used.
 *
 * Leaving it RUNNING would strand the run: no execution is active and no later command can prove
 * the resume. The invocation of an approval park is settled `NOT_EXECUTED` first, so a later
 * dispatch can never claim a call that did not run.
 */
private suspend fun AgentRunOwner.pauseLostResume(
    run: AgentRun,
    token: AgentParkToken
): AgentParkWakeResult {
    val invocationId = token.invocationId
    if (invocationId != null) {
        try {
            toolLedger.markTerminal(
                invocationId = invocationId,
                status = ToolInvocationStatus.NOT_EXECUTED,
                possibleSideEffects = false,
                resultRef = null,
                atMillis = clock.nowMillis()
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The pause below still fails closed; the terminal settlement is defense in depth.
        }
    }
    pendingResumes.remove(run.runId)
    suspended.remove(run.runId)
    pendingApprovals.remove(run.runId)
    val reason = if (token.kind == AgentParkKind.APPROVAL) {
        AgentPauseReason.APPROVAL_STALE
    } else {
        AgentPauseReason.INTERRUPTED
    }
    val paused = run.copy(
        state = AgentRunState.PAUSED,
        reason = reason,
        revision = run.revision + 1,
        updatedAtMillis = clock.nowMillis()
    )
    val committed = try {
        journal.commit(
            com.muyuchat.core.agent.contract.UpdateRunTransition(
                ids.nextId("cmd"),
                paused,
                com.muyuchat.core.agent.contract.AgentDisplayStatus.PAUSED,
                run.revision
            )
        )
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        false
    }
    if (!committed && journal.readRun(run.runId)?.run?.state == AgentRunState.RUNNING) {
        // Keep the token so a later wake can retry the fail-closed pause instead of stranding it.
        return AgentParkWakeResult.Stale(
            run.runId,
            AgentErrorCodes.RESOURCE_LEASE_LOST,
            "The stranded run could not be paused yet; the wake token is kept for a retry."
        )
    }
    clearParkToken(run.runId)
    return AgentParkWakeResult.Stale(
        run.runId,
        AgentErrorCodes.PERMISSION_EXPIRED,
        "The parked resume lost its one-shot authorization and was paused."
    )
}

/** Requeues a user-answer resume only when the durable question really carries that answer. */
private suspend fun AgentRunOwner.resumeAnsweredUserPark(
    run: AgentRun,
    token: AgentParkToken
): AgentParkWakeResult? {
    if (token.kind != AgentParkKind.USER) return null
    val questionId = token.questionId ?: return null
    val question = runCatching { questionReader.readQuestion(questionId) }.getOrNull() ?: return null
    if (question.runId != run.runId || question.status != AgentQuestionStatus.ANSWERED) return null
    val answer = question.answer?.takeIf { it.isNotBlank() } ?: return null
    val transcript = try {
        token.transcript()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        return null
    }
    pendingResumes[run.runId] = PendingOwnerResume(
        runId = run.runId,
        epoch = run.epoch,
        resume = AgentLoopResume(
            transcript = transcript,
            questionId = questionId,
            userAnswer = answer
        ),
        kind = PendingResumeKind.ANSWER
    )
    clearParkToken(run.runId)
    schedule(run)
    return AgentParkWakeResult.Resumed(run.runId, token.wakeId)
}
