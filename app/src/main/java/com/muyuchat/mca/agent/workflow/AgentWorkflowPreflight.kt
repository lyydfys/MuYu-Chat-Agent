package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentProtocolMode

/** One typed preflight failure. [message] is already user-safe and bounded. */
data class AgentWorkflowPreflightViolation(
    val code: String,
    val message: String
)

/** One declarative check row rendered by the preview; no executor or node state exists here. */
data class AgentWorkflowPreflightCheck(
    val check: AgentWorkflowCheck,
    val title: String,
    val passed: Boolean,
    val detail: String?
)

/**
 * Deterministic preview/launch preflight.
 *
 * It evaluates only declarative recipe fields plus the caller-provided input values and model
 * binding. The mandatory invariants (local-only network, read-only tools, standard one-shot
 * approval, bounded goal, resolved inputs, runnable local model) are always enforced even if a
 * recipe declares fewer steps. Declared steps add visible checks; they can never weaken an
 * invariant and they never execute anything.
 */
data class AgentWorkflowPreflightResult(
    val recipe: AgentWorkflowRecipe,
    val resolvedGoal: String,
    val checks: List<AgentWorkflowPreflightCheck>,
    val violations: List<AgentWorkflowPreflightViolation>
) {
    val canLaunch: Boolean get() = violations.isEmpty()

    val blockedMessage: String? get() = violations.firstOrNull()?.message
}

object AgentWorkflowPreflight {

    private val PLACEHOLDER_PATTERN = Regex("\\{\\{input\\.([a-z][a-z0-9_]{0,31})\\}\\}")
    private const val CLOUD_PROVIDER_PREFIX = "cloud:"
    private const val CLOUD_RUNTIME_PREFIX = "CLOUD_"

    fun evaluate(
        recipe: AgentWorkflowRecipe,
        inputValues: Map<String, String>,
        modelBinding: AgentModelBinding
    ): AgentWorkflowPreflightResult {
        val violations = mutableListOf<AgentWorkflowPreflightViolation>()
        val inputFailureMessages = LinkedHashMap<String, String>()
        val resolvedValues = LinkedHashMap<String, String>()

        fun violate(code: String, message: String) {
            if (violations.none { it.code == code && it.message == message }) {
                violations += AgentWorkflowPreflightViolation(code, message)
            }
        }

        // ---- input sources ----------------------------------------------------------------
        val userInputs = recipe.inputs.filter { it.kind == AgentWorkflowInputKind.USER_TEXT }
        val userIds = userInputs.mapTo(HashSet()) { it.id }
        inputValues.keys.forEach { key ->
            if (key !in userIds) {
                violate(
                    AgentWorkflowErrorCodes.INPUT_UNKNOWN,
                    "输入项不在模板声明中：${boundedLabel(key)}"
                )
            }
        }
        recipe.inputs.forEach { source ->
            when (source.kind) {
                AgentWorkflowInputKind.USER_TEXT -> {
                    val label = source.label
                    val raw = inputValues[source.id].orEmpty().trim()
                    val maxChars = source.maxChars ?: AgentWorkflowRecipeLimits.MAX_INPUT_VALUE_CHARS
                    when {
                        raw.isEmpty() -> {
                            resolvedValues[source.id] = ""
                            if (source.required) {
                                val message = "请填写「${label}」"
                                inputFailureMessages[source.id] = message
                                violate(AgentWorkflowErrorCodes.INPUT_REQUIRED, message)
                            }
                        }

                        raw.codePointCount(0, raw.length) > maxChars ||
                            raw.toByteArray(Charsets.UTF_8).size >
                            AgentWorkflowRecipeLimits.MAX_INPUT_VALUE_BYTES -> {
                            val message = "「${label}」超过允许长度"
                            inputFailureMessages[source.id] = message
                            violate(AgentWorkflowErrorCodes.INPUT_TOO_LONG, message)
                            resolvedValues[source.id] = raw.take(maxChars)
                        }

                        raw.any { it == '\u0000' || (it.isISOControl() && it != '\n' && it != '\t') } -> {
                            val message = "「${label}」包含不允许的控制字符"
                            inputFailureMessages[source.id] = message
                            violate(AgentWorkflowErrorCodes.INPUT_INVALID, message)
                            resolvedValues[source.id] = raw.filterNot {
                                it == '\u0000' || (it.isISOControl() && it != '\n' && it != '\t')
                            }.take(maxChars)
                        }

                        else -> resolvedValues[source.id] = raw
                    }
                }

                AgentWorkflowInputKind.WORKSPACE_INPUT -> {
                    resolvedValues[source.id] = source.path.orEmpty()
                }
            }
        }

        // ---- goal resolution --------------------------------------------------------------
        val resolvedGoal = PLACEHOLDER_PATTERN.replace(recipe.goalTemplate) { match ->
            resolvedValues[match.groupValues[1]].orEmpty()
        }
        var goalFailed = false
        if (resolvedGoal.isBlank()) {
            goalFailed = true
            violate(AgentWorkflowErrorCodes.GOAL_INVALID, "任务目标为空，请补充模板输入")
        }
        if (resolvedGoal.toByteArray(Charsets.UTF_8).size >
            AgentWorkflowRecipeLimits.MAX_GOAL_TEMPLATE_BYTES
        ) {
            goalFailed = true
            violate(AgentWorkflowErrorCodes.GOAL_TOO_LONG, "任务目标超过长度限制")
        }
        if (resolvedGoal.any {
                it == '\u0000' || (it.isISOControl() && it != '\n' && it != '\t')
            }
        ) {
            goalFailed = true
            violate(AgentWorkflowErrorCodes.GOAL_INVALID, "任务目标包含不允许的控制字符")
        }

        // ---- network policy ---------------------------------------------------------------
        val networkFailed = recipe.modelPolicy.networkPolicy != AgentNetworkPolicy.LOCAL_ONLY
        if (networkFailed) {
            violate(
                AgentWorkflowErrorCodes.NETWORK_POLICY_UNSUPPORTED,
                "任务模板只允许本地模式"
            )
        }

        // ---- tools ------------------------------------------------------------------------
        var toolsFailed = false
        recipe.enabledToolKeys.forEach { toolKey ->
            when {
                !AgentWorkflowToolAllowlist.isKnownTool(toolKey) -> {
                    toolsFailed = true
                    violate(
                        AgentWorkflowErrorCodes.TOOL_NOT_ALLOWED,
                        "模板引用了不可用的工具"
                    )
                }

                !AgentWorkflowToolAllowlist.isReadOnlyTool(toolKey) -> {
                    toolsFailed = true
                    violate(
                        AgentWorkflowErrorCodes.WRITE_TOOL_NOT_ALLOWED,
                        "模板只能使用只读工具"
                    )
                }
            }
        }

        // ---- approval policy --------------------------------------------------------------
        // The frozen type only has STANDARD, so this invariant cannot be weakened by data; the
        // explicit check keeps the contract visible if a future enum ever gains another value.
        val approvalFailed = recipe.approvalPolicy != AgentWorkflowApprovalPolicy.STANDARD
        if (approvalFailed) {
            violate(
                AgentWorkflowErrorCodes.RECIPE_INVALID,
                "任务模板必须使用单次审批策略"
            )
        }

        // ---- model binding ----------------------------------------------------------------
        var modelFailed = false
        if (modelBinding.protocolMode == AgentProtocolMode.NONE) {
            modelFailed = true
            violate(
                AgentWorkflowErrorCodes.MODEL_BINDING_INVALID,
                "所选模型不支持 Agent 工具协议"
            )
        }
        val modelNeedsNetwork = modelBinding.providerId.startsWith(CLOUD_PROVIDER_PREFIX) ||
            modelBinding.runtime.startsWith(CLOUD_RUNTIME_PREFIX)
        if (!networkFailed && modelNeedsNetwork) {
            modelFailed = true
            violate(
                AgentWorkflowErrorCodes.MODEL_REQUIRES_NETWORK,
                "本地模式不能使用云端模型"
            )
        }
        if (recipe.modelPolicy.allowedProviderIds.isNotEmpty() &&
            modelBinding.providerId !in recipe.modelPolicy.allowedProviderIds
        ) {
            modelFailed = true
            violate(
                AgentWorkflowErrorCodes.MODEL_PROVIDER_NOT_ALLOWED,
                "任务模板不允许使用所选模型"
            )
        }

        // ---- declarative step rows --------------------------------------------------------
        val checks = mutableListOf<AgentWorkflowPreflightCheck>()
        val declared = HashSet<AgentWorkflowCheck>()
        recipe.steps.forEach { step ->
            declared += step.check
            val (passed, detail) = when (step.check) {
                AgentWorkflowCheck.INPUTS_RESOLVED -> {
                    val inputId = step.inputId
                    val message = inputId?.let { inputFailureMessages[it] }
                    (message == null) to message
                }

                AgentWorkflowCheck.GOAL_BOUNDED -> (!goalFailed) to goalDetail(goalFailed, violations)
                AgentWorkflowCheck.TOOLS_READ_ONLY -> (!toolsFailed) to toolDetail(toolsFailed, violations)
                AgentWorkflowCheck.NETWORK_LOCAL_ONLY -> (!networkFailed) to
                    if (networkFailed) AgentWorkflowSafeMessages.forCode(
                        AgentWorkflowErrorCodes.NETWORK_POLICY_UNSUPPORTED
                    ) else null

                AgentWorkflowCheck.APPROVAL_STANDARD -> (!approvalFailed) to null
                AgentWorkflowCheck.MODEL_BOUND -> (!modelFailed) to modelDetail(modelFailed, violations)
            }
            checks += AgentWorkflowPreflightCheck(step.check, step.title, passed, detail)
        }

        // Always expose a row for every mandatory invariant that the recipe did not enumerate.
        val coveredInputs = recipe.steps
            .filter { it.check == AgentWorkflowCheck.INPUTS_RESOLVED }
            .mapNotNull { it.inputId }
            .toSet()
        if (recipe.inputs.any { it.id !in coveredInputs }) {
            val detail = recipe.inputs
                .firstNotNullOfOrNull { inputFailureMessages[it.id] }
            checks += AgentWorkflowPreflightCheck(
                AgentWorkflowCheck.INPUTS_RESOLVED,
                "输入检查",
                detail == null,
                detail
            )
        }
        if (AgentWorkflowCheck.GOAL_BOUNDED !in declared) {
            checks += AgentWorkflowPreflightCheck(
                AgentWorkflowCheck.GOAL_BOUNDED,
                "目标长度检查",
                !goalFailed,
                goalDetail(goalFailed, violations)
            )
        }
        if (AgentWorkflowCheck.TOOLS_READ_ONLY !in declared) {
            checks += AgentWorkflowPreflightCheck(
                AgentWorkflowCheck.TOOLS_READ_ONLY,
                "只读工具检查",
                !toolsFailed,
                toolDetail(toolsFailed, violations)
            )
        }
        if (AgentWorkflowCheck.NETWORK_LOCAL_ONLY !in declared) {
            checks += AgentWorkflowPreflightCheck(
                AgentWorkflowCheck.NETWORK_LOCAL_ONLY,
                "本地网络检查",
                !networkFailed,
                if (networkFailed) AgentWorkflowSafeMessages.forCode(
                    AgentWorkflowErrorCodes.NETWORK_POLICY_UNSUPPORTED
                ) else null
            )
        }
        if (AgentWorkflowCheck.APPROVAL_STANDARD !in declared) {
            checks += AgentWorkflowPreflightCheck(
                AgentWorkflowCheck.APPROVAL_STANDARD,
                "单次审批检查",
                !approvalFailed,
                null
            )
        }
        if (AgentWorkflowCheck.MODEL_BOUND !in declared) {
            checks += AgentWorkflowPreflightCheck(
                AgentWorkflowCheck.MODEL_BOUND,
                "模型检查",
                !modelFailed,
                modelDetail(modelFailed, violations)
            )
        }

        return AgentWorkflowPreflightResult(
            recipe = recipe,
            resolvedGoal = resolvedGoal,
            checks = checks,
            violations = violations
        )
    }

    private fun goalDetail(
        failed: Boolean,
        violations: List<AgentWorkflowPreflightViolation>
    ): String? = if (!failed) {
        null
    } else {
        violations.firstOrNull {
            it.code == AgentWorkflowErrorCodes.GOAL_INVALID ||
                it.code == AgentWorkflowErrorCodes.GOAL_TOO_LONG
        }?.message
    }

    private fun toolDetail(
        failed: Boolean,
        violations: List<AgentWorkflowPreflightViolation>
    ): String? = if (!failed) {
        null
    } else {
        violations.firstOrNull {
            it.code == AgentWorkflowErrorCodes.TOOL_NOT_ALLOWED ||
                it.code == AgentWorkflowErrorCodes.WRITE_TOOL_NOT_ALLOWED
        }?.message
    }

    private fun modelDetail(
        failed: Boolean,
        violations: List<AgentWorkflowPreflightViolation>
    ): String? = if (!failed) {
        null
    } else {
        violations.firstOrNull {
            it.code == AgentWorkflowErrorCodes.MODEL_BINDING_INVALID ||
                it.code == AgentWorkflowErrorCodes.MODEL_REQUIRES_NETWORK ||
                it.code == AgentWorkflowErrorCodes.MODEL_PROVIDER_NOT_ALLOWED
        }?.message
    }

    private fun boundedLabel(value: String): String = value.take(32)
}
