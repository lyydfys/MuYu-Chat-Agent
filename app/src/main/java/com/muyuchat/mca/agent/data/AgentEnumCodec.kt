package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentArtifactCommitPhase
import com.muyuchat.core.agent.contract.AgentArtifactCommitState
import com.muyuchat.core.agent.contract.AgentAttemptTerminal
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentQuestionStatus
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentStepKind
import com.muyuchat.core.agent.contract.AgentStepStatus
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.ToolInvocationStatus

/**
 * Stable persistence codes for every enum column.
 *
 * Room columns are `TEXT`; the value is always the enum constant name. Ordinals are never
 * persisted: inserting a new constant anywhere in an enum must never reinterpret an existing row.
 * [AgentEnumCodec.decode] rejects unknown codes instead of guessing, so a downgraded/corrupt database
 * surfaces a typed storage error rather than silently mapping to the wrong state.
 */
fun AgentTaskLifecycle.encode(): String = name

fun AgentDisplayStatus.encode(): String = name

fun AgentRunState.encode(): String = name

fun AgentPauseReason.encode(): String = name

fun AgentAttemptTerminal.encode(): String = name

fun AgentStepKind.encode(): String = name

fun AgentStepStatus.encode(): String = name

fun ToolInvocationStatus.encode(): String = name

fun AgentApprovalStatus.encode(): String = name

fun AgentApprovalDecision.encode(): String = name

fun AgentArtifactCommitState.encode(): String = name

fun AgentArtifactCommitPhase.encode(): String = name

fun AgentQuestionStatus.encode(): String = name

object AgentEnumCodec {
    fun decodeTaskLifecycle(raw: String): AgentTaskLifecycle =
        decode(raw, AgentTaskLifecycle.entries, "task.lifecycle")

    fun decodeDisplayStatus(raw: String): AgentDisplayStatus =
        decode(raw, AgentDisplayStatus.entries, "task.displayStatus")

    fun decodeRunState(raw: String): AgentRunState =
        decode(raw, AgentRunState.entries, "run.state")

    fun decodePauseReason(raw: String): AgentPauseReason =
        decode(raw, AgentPauseReason.entries, "run.reason")

    fun decodeAttemptTerminal(raw: String): AgentAttemptTerminal =
        decode(raw, AgentAttemptTerminal.entries, "model_attempt.terminal")

    fun decodeStepKind(raw: String): AgentStepKind =
        decode(raw, AgentStepKind.entries, "step.kind")

    fun decodeStepStatus(raw: String): AgentStepStatus =
        decode(raw, AgentStepStatus.entries, "step.status")

    fun decodeInvocationStatus(raw: String): ToolInvocationStatus =
        decode(raw, ToolInvocationStatus.entries, "invocation.status")

    fun decodeApprovalStatus(raw: String): AgentApprovalStatus =
        decode(raw, AgentApprovalStatus.entries, "approval.status")

    fun decodeApprovalDecision(raw: String): AgentApprovalDecision =
        decode(raw, AgentApprovalDecision.entries, "approval.decision")

    fun decodeArtifactCommitState(raw: String): AgentArtifactCommitState =
        decode(raw, AgentArtifactCommitState.entries, "artifact.commitState")

    fun decodeArtifactCommitPhase(raw: String): AgentArtifactCommitPhase =
        decode(raw, AgentArtifactCommitPhase.entries, "artifact_commit.phase")

    fun decodeQuestionStatus(raw: String): AgentQuestionStatus =
        decode(raw, AgentQuestionStatus.entries, "question.status")

    private fun <T : Enum<T>> decode(raw: String, values: List<T>, column: String): T =
        values.firstOrNull { it.name == raw }
            ?: throw AgentDataIntegrityException(
                AgentDataErrorCodes.STORAGE_CORRUPTION,
                "$column has an unknown persisted value: '$raw'"
            )
}

/** Extension capability kind. Persisted as its stable name; no ordinals. */
enum class AgentExtensionKind {
    SKILL,
    MCP;

    companion object {
        fun decode(raw: String): AgentExtensionKind =
            entries.firstOrNull { it.name == raw }
                ?: throw AgentDataIntegrityException(
                    AgentDataErrorCodes.STORAGE_CORRUPTION,
                    "extension.kind has an unknown persisted value: '$raw'"
                )
    }
}

/** Local permission grant scope. Export excludes this table (architecture 10.6). */
enum class AgentGrantScope {
    WORKSPACE_READ,
    INPUT_READ,
    EGRESS_PROVIDER;

    companion object {
        fun decode(raw: String): AgentGrantScope =
            entries.firstOrNull { it.name == raw }
                ?: throw AgentDataIntegrityException(
                    AgentDataErrorCodes.STORAGE_CORRUPTION,
                    "grant.scope has an unknown persisted value: '$raw'"
                )
    }
}
