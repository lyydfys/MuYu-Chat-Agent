package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs

/**
 * Stable, content-free workflow error codes.
 *
 * The code is part of the UI/port contract; the developer-facing message may contain a bounded
 * schema detail, while [AgentWorkflowException.safeMessage] is the only text a page may render.
 */
object AgentWorkflowErrorCodes {
    const val RECIPE_INVALID = "WORKFLOW_RECIPE_INVALID"
    const val UNSUPPORTED_SCHEMA = "WORKFLOW_UNSUPPORTED_SCHEMA"
    const val DUPLICATE_ID = "WORKFLOW_DUPLICATE_ID"
    const val VERSION_CONFLICT = "WORKFLOW_VERSION_CONFLICT"
    const val NOT_FOUND = "WORKFLOW_NOT_FOUND"
    const val ARCHIVED = "WORKFLOW_ARCHIVED"
    const val REVOKED = "WORKFLOW_REVOKED"
    const val STORE_FAILURE = "WORKFLOW_STORE_FAILURE"
    const val STORE_CORRUPT = "WORKFLOW_STORE_CORRUPT"
    const val LIMIT_EXCEEDED = "WORKFLOW_LIMIT_EXCEEDED"
    const val NETWORK_POLICY_UNSUPPORTED = "WORKFLOW_NETWORK_POLICY_UNSUPPORTED"
    const val WRITE_TOOL_NOT_ALLOWED = "WORKFLOW_WRITE_TOOL_NOT_ALLOWED"
    const val TOOL_NOT_ALLOWED = "WORKFLOW_TOOL_NOT_ALLOWED"
    const val INPUT_REQUIRED = "WORKFLOW_INPUT_REQUIRED"
    const val INPUT_UNKNOWN = "WORKFLOW_INPUT_UNKNOWN"
    const val INPUT_INVALID = "WORKFLOW_INPUT_INVALID"
    const val INPUT_TOO_LONG = "WORKFLOW_INPUT_TOO_LONG"
    const val GOAL_INVALID = "WORKFLOW_GOAL_INVALID"
    const val GOAL_TOO_LONG = "WORKFLOW_GOAL_TOO_LONG"
    const val MODEL_BINDING_INVALID = "WORKFLOW_MODEL_BINDING_INVALID"
    const val MODEL_REQUIRES_NETWORK = "WORKFLOW_MODEL_REQUIRES_NETWORK"
    const val MODEL_PROVIDER_NOT_ALLOWED = "WORKFLOW_MODEL_PROVIDER_NOT_ALLOWED"
    const val MODEL_UNAVAILABLE = "WORKFLOW_MODEL_UNAVAILABLE"
    const val TASK_CREATE_REJECTED = "WORKFLOW_TASK_CREATE_REJECTED"
    const val TASK_ANCHOR_MISSING = "WORKFLOW_TASK_ANCHOR_MISSING"
    const val RUN_START_REJECTED = "WORKFLOW_RUN_START_REJECTED"
    const val LAUNCH_FAILED = "WORKFLOW_LAUNCH_FAILED"
    const val ADMIN_UNAVAILABLE = "WORKFLOW_ADMIN_UNAVAILABLE"
}

/** Typed failure used by the parser, store, preflight, launcher and the app adapter. */
class AgentWorkflowException(
    val code: String,
    message: String,
    val safeMessage: String = AgentWorkflowSafeMessages.forCode(code),
    cause: Throwable? = null
) : Exception(message, cause) {
    init {
        require(code.isNotBlank()) { "workflow error code must not be blank" }
        require(safeMessage.isNotBlank()) { "workflow safe message must not be blank" }
    }
}

/** User-facing fallback text for every stable code. Unknown codes degrade to a generic message. */
object AgentWorkflowSafeMessages {
    fun forCode(code: String): String = when (code) {
        AgentWorkflowErrorCodes.RECIPE_INVALID -> "任务模板格式无效，请检查模板内容。"
        AgentWorkflowErrorCodes.UNSUPPORTED_SCHEMA -> "任务模板版本暂不支持。"
        AgentWorkflowErrorCodes.DUPLICATE_ID -> "同名同版本的任务模板已存在。"
        AgentWorkflowErrorCodes.VERSION_CONFLICT -> "任务模板版本冲突，请刷新后重试。"
        AgentWorkflowErrorCodes.NOT_FOUND -> "任务模板不存在或已被移除。"
        AgentWorkflowErrorCodes.ARCHIVED -> "任务模板已归档，不能创建任务。"
        AgentWorkflowErrorCodes.REVOKED -> "任务模板已撤销，不能创建任务。"
        AgentWorkflowErrorCodes.STORE_FAILURE -> "任务模板存储暂时不可用。"
        AgentWorkflowErrorCodes.STORE_CORRUPT -> "任务模板存储已损坏，已停止读取。"
        AgentWorkflowErrorCodes.LIMIT_EXCEEDED -> "任务模板数量或长度超过限制。"
        AgentWorkflowErrorCodes.NETWORK_POLICY_UNSUPPORTED ->
            "任务模板只允许本地模式，不能声明联网策略。"
        AgentWorkflowErrorCodes.WRITE_TOOL_NOT_ALLOWED ->
            "任务模板只能使用只读工具，不能声明写入工具。"
        AgentWorkflowErrorCodes.TOOL_NOT_ALLOWED -> "任务模板引用了不可用的工具。"
        AgentWorkflowErrorCodes.INPUT_REQUIRED -> "请填写模板要求的输入。"
        AgentWorkflowErrorCodes.INPUT_UNKNOWN -> "提交了模板未声明的输入。"
        AgentWorkflowErrorCodes.INPUT_INVALID -> "输入内容包含不允许的字符。"
        AgentWorkflowErrorCodes.INPUT_TOO_LONG -> "输入内容超过模板限制。"
        AgentWorkflowErrorCodes.GOAL_INVALID -> "任务目标无效。"
        AgentWorkflowErrorCodes.GOAL_TOO_LONG -> "任务目标超过长度限制。"
        AgentWorkflowErrorCodes.MODEL_BINDING_INVALID -> "所选模型不支持 Agent 工具协议。"
        AgentWorkflowErrorCodes.MODEL_REQUIRES_NETWORK -> "本地模式不能使用云端模型。"
        AgentWorkflowErrorCodes.MODEL_PROVIDER_NOT_ALLOWED -> "任务模板不允许使用所选模型。"
        AgentWorkflowErrorCodes.MODEL_UNAVAILABLE -> "模板绑定的模型当前不可用。"
        AgentWorkflowErrorCodes.TASK_CREATE_REJECTED -> "任务创建被拒绝。"
        AgentWorkflowErrorCodes.TASK_ANCHOR_MISSING ->
            "任务已提交但未能确认任务身份，请到任务列表查看。"
        AgentWorkflowErrorCodes.RUN_START_REJECTED -> "任务已创建，但启动被拒绝。"
        AgentWorkflowErrorCodes.LAUNCH_FAILED -> "任务模板启动失败，请稍后重试。"
        AgentWorkflowErrorCodes.ADMIN_UNAVAILABLE -> "任务模板管理尚未接入。"
        else -> "任务模板操作失败，请稍后重试。"
    }
}

/**
 * Code-owned allowlist of tools a recipe may request.
 *
 * Only the frozen built-in V1 tools are addressable and only while their host side effect is
 * READ_ONLY. A recipe can narrow this list but can never add an MCP, package, shell, UI or write
 * operation.
 */
object AgentWorkflowToolAllowlist {
    val READ_ONLY_TOOL_KEYS: List<String> =
        AgentV1ToolSpecs.TOOL_KEYS
            .filter { key -> AgentV1ToolSpecs.sideEffect(key) == AgentToolSideEffect.READ_ONLY }
            .sorted()

    val KNOWN_TOOL_KEYS: Set<String> = AgentV1ToolSpecs.TOOL_KEYS.toSet()

    fun isReadOnlyTool(toolKey: String): Boolean = toolKey in READ_ONLY_TOOL_KEYS

    fun isKnownTool(toolKey: String): Boolean = toolKey in KNOWN_TOOL_KEYS
}

internal fun workflowFailure(
    code: String,
    detail: String,
    cause: Throwable? = null
): Nothing = throw AgentWorkflowException(code, detail, cause = cause)
