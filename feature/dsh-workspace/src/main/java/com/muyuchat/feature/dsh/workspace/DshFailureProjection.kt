package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimeFailure

/**
 * User-facing projection of one host failure.
 *
 * The projection is intentionally prompt-free and token-free: it carries only the stable failure
 * code, a specific Chinese explanation and the retry affordance. `DshRuntimeFailure.diagnostics`
 * is engineering evidence for local logs and is never copied into this model.
 */
data class DshFailureProjection(
    val code: DshFailureCode,
    val title: String,
    val message: String,
    val retryable: Boolean,
    val primaryActionLabel: String,
    /** True when a previously ready host process exited and the page must be restarted. */
    val runtimeExited: Boolean
)

/**
 * Maps every frozen [DshFailureCode] to a concrete user-facing message. There is no generic
 * "启动失败" bucket: the DSH host contract distinguishes payload, ABI, exec, loader, auth-entry,
 * gateway, crash and stop-fence failures, and the UI must be specific about which one happened.
 */
object DshFailureProjectionMapper {

    fun project(failure: DshRuntimeFailure?): DshFailureProjection {
        val code = failure?.code ?: DshFailureCode.INTERNAL
        val retryable = failure?.retryable ?: defaultRetryable(code)
        val runtimeExited = code == DshFailureCode.PROCESS_EXITED
        return DshFailureProjection(
            code = code,
            title = "DSH ${titleFor(code, runtimeExited)}",
            message = messageFor(code),
            retryable = retryable,
            primaryActionLabel = if (runtimeExited) "重新启动" else "重试",
            runtimeExited = runtimeExited
        )
    }

    /** A ready host reached STOPPED without reporting a failure: the runtime is gone either way. */
    fun unexpectedStop(): DshFailureProjection = project(
        DshRuntimeFailure(
            code = DshFailureCode.PROCESS_EXITED,
            message = "DSH runtime stopped without a failure code",
            retryable = true
        )
    )

    /** Raised by the UI when the official page answers 401/403 instead of a clean page. */
    fun authenticationRejected(): DshFailureProjection = project(
        DshRuntimeFailure(
            code = DshFailureCode.AUTH_ENTRY_MISSING,
            message = "The official DSH page rejected the browser session",
            retryable = true
        )
    )

    private fun titleFor(code: DshFailureCode, runtimeExited: Boolean): String = when {
        runtimeExited -> "运行时已退出"
        code == DshFailureCode.PAYLOAD_MISSING -> "运行组件缺失"
        code == DshFailureCode.PAYLOAD_INTEGRITY -> "运行组件校验失败"
        code == DshFailureCode.ABI_UNSUPPORTED -> "设备架构不受支持"
        code == DshFailureCode.NODE_NOT_EXECUTABLE -> "Node 无法启动"
        code == DshFailureCode.NODE_START_FAILED -> "Node 启动失败"
        code == DshFailureCode.LOADER_NOT_READY -> "官方页面未就绪"
        code == DshFailureCode.AUTH_ENTRY_MISSING -> "认证入口不可用"
        code == DshFailureCode.GATEWAY_UNAVAILABLE -> "模型网关不可用"
        code == DshFailureCode.STOP_TIMEOUT -> "停止超时"
        code == DshFailureCode.STARTUP_RECOVERY_REQUIRED -> "启动恢复未完成"
        else -> "启动失败"
    }

    private fun messageFor(code: DshFailureCode): String = when (code) {
        DshFailureCode.PAYLOAD_MISSING ->
            "应用内没有找到 DSH 运行组件。请更新或重新安装 MCA 后再试。"
        DshFailureCode.PAYLOAD_INTEGRITY ->
            "DSH 运行组件未通过完整性校验，文件可能损坏。请更新或重新安装 MCA。"
        DshFailureCode.ABI_UNSUPPORTED ->
            "当前设备的 ABI 没有对应的 DSH Node 可执行文件，此构建无法在本机运行 DSH。"
        DshFailureCode.NODE_NOT_EXECUTABLE ->
            "DSH 的 Node 可执行文件无法在此设备上启动（系统拒绝执行或依赖不完整）。"
        DshFailureCode.NODE_START_FAILED ->
            "DSH 的 Node 进程在官方 Web 服务就绪前退出，未能启动页面。"
        DshFailureCode.LOADER_NOT_READY ->
            "DSH 官方 Loader 未在限定时间内就绪，页面尚未可用。"
        DshFailureCode.AUTH_ENTRY_MISSING ->
            "未能取得官方 DSH 的认证入口，或官方页面拒绝了当前浏览器会话。"
        DshFailureCode.GATEWAY_UNAVAILABLE ->
            "MCA 模型网关当前不可用，DSH 无法启动。请确认本机有可用模型后重试。"
        DshFailureCode.PROCESS_EXITED ->
            "运行 DSH 的进程意外结束。重新启动会创建新的运行代次；官方 DSH 会话数据仍由 DSH 自身保存。"
        DshFailureCode.STOP_TIMEOUT ->
            "停止 DSH 运行时超时，宿主仍保持停止状态。重试会重新建立运行时。"
        DshFailureCode.STARTUP_RECOVERY_REQUIRED ->
            "上次的 DSH 维护或隔离试跑没有确认结束，为避免两个运行时同时存在，本次没有启动。重试会重新执行恢复检查；若持续出现，请到 DSH 控制台查看维护状态。"
        DshFailureCode.INTERNAL ->
            "DSH 宿主内部错误。可重试一次；若持续出现，请导出诊断信息。"
    }

    private fun defaultRetryable(code: DshFailureCode): Boolean = when (code) {
        DshFailureCode.PAYLOAD_MISSING,
        DshFailureCode.PAYLOAD_INTEGRITY,
        DshFailureCode.ABI_UNSUPPORTED -> false
        else -> true
    }
}
