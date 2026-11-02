package com.muyuchat.core.agent.runtime.testing

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.runtime.AgentToolSchemaValidator
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.runtime.protocol.PromptedV1Decoder

/** Deterministic, non-secret fixtures shared by agent-runtime K02/K03 tests. */
object AgentTestFixtures {

    const val RUN_ID = "run-1"
    const val TASK_ID = "task-1"
    const val WORKSPACE_ID = "workspace-1"

    fun binding(
        protocolMode: AgentProtocolMode = AgentProtocolMode.PROMPTED_V1,
        contextWindowTokens: Int? = 8_192
    ): AgentModelBinding = AgentModelBinding(
        providerId = "local",
        modelId = "mnn-qwen3-4b",
        runtime = "MNN_CPU",
        assetFingerprint = "sha256:fixture",
        protocolMode = protocolMode,
        modelDisplayName = "Qwen3 4B fixture",
        contextWindowTokens = contextWindowTokens
    )

    fun policy(): AgentRunPolicy = AgentRunPolicy()

    fun task(
        taskId: String = TASK_ID,
        workspaceId: String = WORKSPACE_ID,
        goal: String = "整理 inputs/brief.txt 并生成 artifacts/report.md",
        state: AgentDisplayStatus = AgentDisplayStatus.IDLE,
        lastRunId: String? = null,
        revision: Long = 0L
    ): AgentTask = AgentTask(
        taskId = taskId,
        workspaceId = workspaceId,
        goal = goal,
        lifecycle = AgentTaskLifecycle.ACTIVE,
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L,
        displayStatus = state,
        lastRunId = lastRunId,
        revision = revision
    )

    fun run(
        runId: String = RUN_ID,
        taskId: String = TASK_ID,
        runOrdinal: Long = 1L,
        state: AgentRunState = AgentRunState.QUEUED,
        reason: com.muyuchat.core.agent.contract.AgentPauseReason =
            com.muyuchat.core.agent.contract.AgentPauseReason.NONE,
        epoch: Long = 1L,
        budget: AgentBudget = AgentBudget(),
        spentBudget: AgentSpentBudget = AgentSpentBudget(),
        revision: Long = 1L,
        lastSequence: Long = 0L
    ): AgentRun = AgentRun(
        runId = runId,
        taskId = taskId,
        runOrdinal = runOrdinal,
        state = state,
        reason = reason,
        epoch = epoch,
        modelBinding = binding(),
        policy = policy(),
        budget = budget,
        spentBudget = spentBudget,
        lastSequence = lastSequence,
        revision = revision,
        createdAtMillis = 1_000L,
        updatedAtMillis = 1_000L
    )

    fun toolDescriptors(validator: AgentToolSchemaValidator): List<AgentToolDescriptor> =
        AgentV1ToolSpecs.descriptors(validator)

    fun decoder(validator: AgentToolSchemaValidator): PromptedV1Decoder {
        val keys = toolDescriptors(validator).map { it.toolKey }.toSet()
        return PromptedV1Decoder({ keys })
    }

    fun toolEnvelope(
        name: String = AgentV1ToolSpecs.WORKSPACE_READ_TEXT,
        argumentsJson: String = """{"path":"inputs/brief.txt"}"""
    ): String =
        """{"protocol":"mca.agent.v1","kind":"tool","call":{"name":"$name","arguments":$argumentsJson}}"""

    fun finalEnvelope(
        text: String = "已整理完成。",
        artifactIds: List<String> = emptyList()
    ): String {
        val ids = artifactIds.joinToString(",") { "\"$it\"" }
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return """{"protocol":"mca.agent.v1","kind":"final","text":"$escaped","artifactIds":[$ids]}"""
    }

    fun askUserEnvelope(
        question: String = "报告需要面向哪类读者？",
        options: List<String> = listOf("开发者", "管理者")
    ): String {
        val escapedQuestion = question.replace("\\", "\\\\").replace("\"", "\\\"")
        val optionsJson = options.joinToString(",") {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        return """{"protocol":"mca.agent.v1","kind":"ask_user","question":"$escapedQuestion","options":[$optionsJson]}"""
    }
}
