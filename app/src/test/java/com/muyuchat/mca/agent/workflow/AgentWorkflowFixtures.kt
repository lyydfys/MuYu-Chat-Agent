package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentProtocolMode

internal fun testWorkflowBinding(
    providerId: String = "local",
    modelId: String = "model-1",
    runtime: String = "mnn",
    protocolMode: AgentProtocolMode = AgentProtocolMode.PROMPTED_V1,
    modelDisplayName: String = "Qwen3 4B"
): AgentModelBinding = AgentModelBinding(
    providerId = providerId,
    modelId = modelId,
    runtime = runtime,
    assetFingerprint = "fingerprint-1",
    protocolMode = protocolMode,
    modelDisplayName = modelDisplayName
)

internal fun testUserInput(
    id: String = "topic",
    label: String = "主题",
    required: Boolean = true,
    maxChars: Int = 200
): AgentWorkflowInputSource = AgentWorkflowInputSource(
    id = id,
    kind = AgentWorkflowInputKind.USER_TEXT,
    label = label,
    required = required,
    maxChars = maxChars
)

internal fun testWorkspaceInput(
    id: String = "brief",
    label: String = "简报",
    path: String = "inputs/brief.md",
    required: Boolean = true
): AgentWorkflowInputSource = AgentWorkflowInputSource(
    id = id,
    kind = AgentWorkflowInputKind.WORKSPACE_INPUT,
    label = label,
    path = path,
    required = required
)

internal fun testWorkflowSteps(
    inputs: List<AgentWorkflowInputSource> = listOf(testUserInput())
): List<AgentWorkflowStep> = buildList {
    inputs.forEach { input ->
        add(
            AgentWorkflowStep(
                id = "input_${input.id}",
                check = AgentWorkflowCheck.INPUTS_RESOLVED,
                title = "输入检查：${input.label}",
                inputId = input.id
            )
        )
    }
    add(AgentWorkflowStep("goal", AgentWorkflowCheck.GOAL_BOUNDED, "任务目标检查"))
    add(AgentWorkflowStep("tools", AgentWorkflowCheck.TOOLS_READ_ONLY, "只读工具检查"))
    add(AgentWorkflowStep("network", AgentWorkflowCheck.NETWORK_LOCAL_ONLY, "本地模式检查"))
    add(AgentWorkflowStep("approval", AgentWorkflowCheck.APPROVAL_STANDARD, "单次审批检查"))
    add(AgentWorkflowStep("model", AgentWorkflowCheck.MODEL_BOUND, "模型检查"))
}

internal fun testWorkflowRecipe(
    id: String = "weekly-review",
    version: Long = 1L,
    title: String = "每周回顾",
    inputs: List<AgentWorkflowInputSource> = listOf(testUserInput()),
    goalTemplate: String = if (inputs.isEmpty()) {
        "总结工作区中的记录"
    } else {
        "总结 {{input.${inputs.first().id}}} 的要点"
    },
    modelPolicy: AgentWorkflowModelPolicy = AgentWorkflowModelPolicy(
        networkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
        allowedProviderIds = emptyList()
    ),
    enabledToolKeys: List<String> = AgentWorkflowToolAllowlist.READ_ONLY_TOOL_KEYS,
    steps: List<AgentWorkflowStep> = testWorkflowSteps(inputs)
): AgentWorkflowRecipe = AgentWorkflowRecipe(
    id = id,
    version = version,
    title = title,
    goalTemplate = goalTemplate,
    description = "deterministic fixture",
    inputs = inputs,
    modelPolicy = modelPolicy,
    approvalPolicy = AgentWorkflowApprovalPolicy.STANDARD,
    enabledToolKeys = enabledToolKeys,
    steps = steps
)

internal fun testWorkflowRecord(
    recipe: AgentWorkflowRecipe = testWorkflowRecipe(),
    state: AgentWorkflowRecipeState = AgentWorkflowRecipeState.ACTIVE,
    revision: Long = 1L,
    updatedAtMillis: Long = 1_760_000_000_000L
): AgentWorkflowRecipeRecord = AgentWorkflowRecipeRecord(
    recipe = recipe,
    state = state,
    revision = revision,
    updatedAtMillis = updatedAtMillis
)
