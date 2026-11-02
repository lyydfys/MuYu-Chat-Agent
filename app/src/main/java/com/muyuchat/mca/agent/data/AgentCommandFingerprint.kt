package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentArtifact
import com.muyuchat.core.agent.contract.AgentArtifactCommit
import com.muyuchat.core.agent.contract.AgentEvent
import com.muyuchat.core.agent.contract.AgentModelAttempt
import com.muyuchat.core.agent.contract.AgentQuestion
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentStep
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTransition
import com.muyuchat.core.agent.contract.AnswerQuestionTransition
import com.muyuchat.core.agent.contract.AppendEventsTransition
import com.muyuchat.core.agent.contract.AskQuestionTransition
import com.muyuchat.core.agent.contract.ConsumeApprovalTransition
import com.muyuchat.core.agent.contract.CreateRunTransition
import com.muyuchat.core.agent.contract.CreateTaskTransition
import com.muyuchat.core.agent.contract.SupersedeRunTransition
import com.muyuchat.core.agent.contract.ToolInvocation
import com.muyuchat.core.agent.contract.UpdateRunTransition
import com.muyuchat.core.agent.contract.UpsertApprovalTransition
import com.muyuchat.core.agent.contract.UpsertArtifactTransition
import com.muyuchat.core.agent.contract.UpsertAttemptTransition
import com.muyuchat.core.agent.contract.UpsertInvocationTransition
import com.muyuchat.core.agent.contract.UpsertStepTransition
import java.security.MessageDigest

/**
 * Stable content hash for command idempotency (architecture 7.2).
 *
 * The hash covers the transition type and every content field, and deliberately excludes:
 *  * `commandId` — it is the lookup key, not content;
 *  * `expectedRevision`/`taskRevision` — a retry after another command advanced the revision must
 *    still replay the original result instead of inventing an idempotency conflict.
 *
 * Canonical encoding uses 8-byte big-endian length prefixes for every string field, so two different
 * field values can never collide through separator injection. The algorithm id is part of the hashed
 * material; changing the encoding requires a migration of `agent_commands.contentHash`.
 */
object AgentCommandFingerprint {
    const val ALGORITHM: String = "mca.agent.command.v1"

    fun commandType(transition: AgentTransition): String = when (transition) {
        is CreateTaskTransition -> "CREATE_TASK"
        is CreateRunTransition -> "CREATE_RUN"
        is UpdateRunTransition -> "UPDATE_RUN"
        is AppendEventsTransition -> "APPEND_EVENTS"
        is UpsertStepTransition -> "UPSERT_STEP"
        is UpsertAttemptTransition -> "UPSERT_ATTEMPT"
        is UpsertInvocationTransition -> "UPSERT_INVOCATION"
        is UpsertApprovalTransition -> "UPSERT_APPROVAL"
        is ConsumeApprovalTransition -> "CONSUME_APPROVAL"
        is UpsertArtifactTransition -> "UPSERT_ARTIFACT"
        is AskQuestionTransition -> "ASK_QUESTION"
        is AnswerQuestionTransition -> "ANSWER_QUESTION"
        is SupersedeRunTransition -> "SUPERSEDE_RUN"
    }

    fun fingerprint(transition: AgentTransition): String {
        val writer = CanonicalWriter()
        writer.value(ALGORITHM)
        writer.value(commandType(transition))
        when (transition) {
            is CreateTaskTransition -> writer.task(transition.task)
            is CreateRunTransition -> {
                writer.run(transition.run)
                writer.value(transition.taskDisplay.name)
                writer.value(transition.successorOfRunId)
            }
            is UpdateRunTransition -> {
                writer.run(transition.run)
                writer.value(transition.taskDisplay.name)
            }
            is AppendEventsTransition -> {
                writer.value(transition.runIdValue)
                writer.value(transition.events.size.toLong())
                transition.events.forEach(writer::event)
            }
            is UpsertStepTransition -> writer.step(transition.step)
            is UpsertAttemptTransition -> writer.attempt(transition.attempt)
            is UpsertInvocationTransition -> writer.invocation(transition.invocation)
            is UpsertApprovalTransition -> writer.approval(transition.approval)
            is ConsumeApprovalTransition -> {
                writer.value(transition.approvalId)
                writer.value(transition.consumedAtMillis)
                writer.value(transition.nextRun != null)
                transition.nextRun?.let(writer::run)
                writer.value(transition.taskDisplay?.name)
                writer.value(transition.decision.name)
            }
            is UpsertArtifactTransition -> {
                writer.artifact(transition.artifact)
                writer.value(transition.commit != null)
                transition.commit?.let(writer::commit)
            }
            is AskQuestionTransition -> {
                writer.question(transition.question)
                writer.run(transition.nextRun)
                writer.value(transition.taskDisplay.name)
            }
            is AnswerQuestionTransition -> {
                writer.value(transition.questionId)
                writer.value(transition.answer)
                writer.value(transition.answeredAtMillis)
                writer.run(transition.nextRun)
                writer.value(transition.taskDisplay.name)
            }
            is SupersedeRunTransition -> {
                writer.value(transition.previousRunId)
                writer.value(transition.successorRunId)
                writer.run(transition.updatedRun)
            }
        }
        return sha256Hex(writer.finish().toByteArray(Charsets.UTF_8))
    }

    /** Scope id stored on `agent_commands`: task when known, otherwise the run. */
    fun scopeId(transition: AgentTransition): String =
        transition.taskId ?: transition.runId ?: "global"

    /**
     * Full creation-command fingerprint. [CreateTaskTransition] deliberately carries no binding, so
     * `AgentTaskRepository` hashes the complete `CreateAgentTask` content itself before inserting the
     * task and its command-log row in one transaction.
     */
    fun createTaskFingerprint(command: com.muyuchat.core.agent.contract.CreateAgentTask): String {
        val writer = CanonicalWriter()
        writer.value(ALGORITHM)
        writer.value("CREATE_TASK_COMMAND")
        writer.value(command.goal)
        writer.value(command.displayTitle)
        writer.value(AgentRecordJson.encodeBinding(command.modelBinding))
        writer.value(AgentRecordJson.encodePolicy(command.policy))
        writer.value(AgentRecordJson.encodeBudget(command.budget))
        // Additive: only non-empty selections extend the pre-memory hash material, so a replay of a
        // command created before memory existed still matches its stored content hash.
        val memorySpaceIds = command.memorySpaceIds
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
        if (memorySpaceIds.isNotEmpty()) {
            writer.value("memorySpaceIds")
            writer.value(memorySpaceIds.size.toLong())
            memorySpaceIds.forEach { id -> writer.value(id) }
        }
        return sha256Hex(writer.finish().toByteArray(Charsets.UTF_8))
    }

    private class CanonicalWriter {
        private val builder = StringBuilder(512)

        fun value(raw: String?) {
            if (raw == null) {
                builder.append("-1:")
                return
            }
            val bytes = raw.toByteArray(Charsets.UTF_8)
            builder.append(bytes.size).append(':').append(raw)
        }

        fun value(raw: Long) {
            builder.append('#').append(raw).append(';')
        }

        fun value(raw: Long?) {
            if (raw == null) {
                builder.append("-1:")
            } else {
                value(raw.toLong())
            }
        }

        fun value(raw: Int) {
            builder.append('#').append(raw).append(';')
        }

        fun value(raw: Boolean) {
            builder.append(if (raw) 'T' else 'F')
        }

        fun task(value: AgentTask) {
            builder.append("task{")
            this.value(value.taskId)
            this.value(value.workspaceId)
            this.value(value.goal)
            this.value(value.lifecycle.name)
            this.value(value.createdAtMillis)
            this.value(value.updatedAtMillis)
            this.value(value.displayStatus.name)
            this.value(value.lastRunId)
            this.value(value.revision)
            this.value(value.schemaVersion)
            builder.append('}')
        }

        fun run(value: AgentRun) {
            builder.append("run{")
            this.value(value.runId)
            this.value(value.taskId)
            this.value(value.runOrdinal)
            this.value(value.state.name)
            this.value(value.reason.name)
            this.value(value.epoch)
            binding(value.modelBinding)
            policy(value.policy)
            budget(value.budget)
            spent(value.spentBudget)
            this.value(value.lastSequence)
            this.value(value.revision)
            this.value(value.createdAtMillis)
            this.value(value.updatedAtMillis)
            this.value(value.schemaVersion)
            builder.append('}')
        }

        private fun binding(value: com.muyuchat.core.agent.contract.AgentModelBinding) {
            builder.append("binding{")
            this.value(value.providerId)
            this.value(value.modelId)
            this.value(value.runtime)
            this.value(value.assetFingerprint)
            this.value(value.protocolMode.name)
            this.value(value.modelDisplayName)
            this.value(value.contextWindowTokens?.toLong())
            this.value(value.samplingJson)
            this.value(value.capabilityEvidenceVersion)
            builder.append('}')
        }

        private fun policy(value: com.muyuchat.core.agent.contract.AgentRunPolicy) {
            builder.append("policy{")
            this.value(value.networkPolicy.name)
            this.value(value.policyVersion)
            val keys = value.enabledToolKeys?.sorted()
            this.value(keys?.size?.toLong())
            keys?.forEach { this.value(it) }
            val skills = value.skillIds.sorted()
            this.value(skills.size.toLong())
            skills.forEach { this.value(it) }
            this.value(value.offlineForced)
            // Additive: absent for the empty set, so a run/command persisted before memory existed
            // re-fingerprints to the same value and keeps idempotent replay intact.
            if (value.memorySpaceIds.isNotEmpty()) {
                val memory = value.memorySpaceIds
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .sorted()
                this.value("memorySpaceIds")
                this.value(memory.size.toLong())
                memory.forEach { this.value(it) }
            }
            builder.append('}')
        }

        private fun budget(value: com.muyuchat.core.agent.contract.AgentBudget) {
            builder.append("budget{")
            this.value(value.modelAttempts)
            this.value(value.toolInvocations)
            this.value(value.callsPerModelResponse)
            this.value(value.protocolRepairs)
            this.value(value.modelWallTimeMillis)
            this.value(value.toolTimeoutMillis)
            this.value(value.runActiveTimeMillis)
            this.value(value.resourceWaitMillis)
            this.value(value.toolResultToModelBytes)
            this.value(value.toolResultPersistBytes)
            this.value(value.textOutputTokens)
            this.value(value.totalGeneratedTokens)
            builder.append('}')
        }

        private fun spent(value: com.muyuchat.core.agent.contract.AgentSpentBudget) {
            builder.append("spent{")
            this.value(value.modelAttempts)
            this.value(value.toolInvocations)
            this.value(value.protocolRepairs)
            this.value(value.modelWallTimeMillis)
            this.value(value.runActiveTimeMillis)
            this.value(value.generatedTokens)
            this.value(value.estimated)
            builder.append('}')
        }

        fun step(value: AgentStep) {
            builder.append("step{")
            this.value(value.stepId)
            this.value(value.runId)
            this.value(value.index)
            this.value(value.kind.name)
            this.value(value.status.name)
            this.value(value.modelAttemptId)
            this.value(value.invocationId)
            this.value(value.startedAtMillis)
            this.value(value.endedAtMillis ?: -1L)
            this.value(value.endedAtMillis != null)
            this.value(value.schemaVersion)
            builder.append('}')
        }

        fun attempt(value: AgentModelAttempt) {
            builder.append("attempt{")
            this.value(value.attemptId)
            this.value(value.runId)
            this.value(value.stepIndex)
            this.value(value.ordinal)
            this.value(value.backendRequestId)
            this.value(value.backendIdentityRef)
            this.value(value.bindingFingerprint)
            this.value(value.startedAtMillis)
            this.value(value.terminal.name)
            this.value(value.finishedAtMillis ?: -1L)
            this.value(value.finishedAtMillis != null)
            this.value(value.usageJson)
            this.value(value.schemaVersion)
            builder.append('}')
        }

        fun invocation(value: ToolInvocation) {
            builder.append("invocation{")
            this.value(value.invocationId)
            this.value(value.runId)
            this.value(value.stepId)
            this.value(value.callOrdinal)
            this.value(value.toolKey)
            this.value(value.toolVersion)
            this.value(value.schemaHash)
            this.value(value.frozenArgumentsRef)
            this.value(value.argumentsHash)
            this.value(value.resourceFingerprint)
            this.value(value.status.name)
            this.value(value.idempotencyKey)
            this.value(value.resultRef)
            this.value(value.createdAtMillis)
            this.value(value.updatedAtMillis)
            this.value(value.schemaVersion)
            builder.append('}')
        }

        fun approval(value: AgentApproval) {
            builder.append("approval{")
            this.value(value.approvalId)
            this.value(value.invocationId)
            this.value(value.principal)
            this.value(value.argumentsHash)
            this.value(value.toolSchemaHash)
            this.value(value.resourceFingerprint)
            this.value(value.policyVersion)
            this.value(value.runEpoch)
            this.value(value.expiresAtMillis)
            this.value(value.status.name)
            this.value(value.createdAtMillis)
            this.value(value.consumedAtMillis ?: -1L)
            this.value(value.consumedAtMillis != null)
            this.value(value.schemaVersion)
            builder.append('}')
        }

        fun event(value: AgentEvent) {
            builder.append("event{")
            this.value(value.eventId)
            this.value(value.runId)
            this.value(value.sequence)
            this.value(value.type)
            this.value(value.createdAtMillis)
            this.value(value.commandId)
            this.value(value.causationId)
            this.value(value.redactedPayloadRef)
            this.value(value.summary)
            this.value(value.version)
            this.value(value.schemaVersion)
            builder.append('}')
        }

        fun question(value: AgentQuestion) {
            builder.append("question{")
            this.value(value.questionId)
            this.value(value.runId)
            this.value(value.stepIndex)
            this.value(value.text)
            this.value(value.options.size.toLong())
            value.options.forEach { this.value(it) }
            this.value(value.status.name)
            this.value(value.askedAtMillis)
            this.value(value.answeredAtMillis ?: -1L)
            this.value(value.answeredAtMillis != null)
            this.value(value.answer)
            this.value(value.schemaVersion)
            builder.append('}')
        }

        fun artifact(value: AgentArtifact) {
            builder.append("artifact{")
            this.value(value.artifactId)
            this.value(value.taskId)
            this.value(value.invocationId)
            this.value(value.relativePath)
            this.value(value.mediaType)
            this.value(value.sizeBytes)
            this.value(value.sha256)
            this.value(value.commitState.name)
            this.value(value.createdAtMillis)
            this.value(value.schemaVersion)
            builder.append('}')
        }

        fun commit(value: AgentArtifactCommit) {
            builder.append("commit{")
            this.value(value.commitId)
            this.value(value.invocationId)
            this.value(value.artifactId)
            this.value(value.stagingRef)
            this.value(value.targetLogicalPath)
            this.value(value.expectedOldHash)
            this.value(value.newHash)
            this.value(value.newSizeBytes)
            this.value(value.phase.name)
            this.value(value.updatedAtMillis)
            this.value(value.schemaVersion)
            builder.append('}')
        }

        fun finish(): String = builder.toString()
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
