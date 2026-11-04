package com.muyuchat.core.dsh.contract.runtime

/**
 * Pure data model of the console's "运行状态自检" block.
 *
 * It lives in the contract module because two sides need it: the app process collects the real facts
 * (`RangeSelfCheckSource`) and the DSH console feature renders them. Putting the model in the contract
 * keeps the feature free of an app-module dependency — the console receives rows, it never probes.
 *
 * Nothing here describes *how* a fact is obtained, and no field may carry a filesystem path, a token or
 * an exception: the rows are user-facing copy plus a stable status/action enum.
 */
enum class RuntimeSelfCheckStatus {
    READY,
    FAILED,

    /** Not probed yet (or the probe could not run) — never rendered as a failure. */
    UNKNOWN;

    val label: String
        get() = when (this) {
            READY -> "正常"
            FAILED -> "异常"
            UNKNOWN -> "未检测"
        }
}

/** Repairs the console may offer. Anything not listed here must not render a button. */
enum class RuntimeSelfCheckRepair(val buttonLabel: String) {
    RESTART_RUNTIME("重启运行时"),
    USE_FALLBACK_PORT("使用回退端口")
}

/** One row of the self-check block. */
data class RuntimeSelfCheckRow(
    val key: String,
    val title: String,
    val status: RuntimeSelfCheckStatus,
    /** One sentence, safe for the UI: no filesystem paths, no ids, no exception text. */
    val detail: String,
    /** Null when this build cannot fix the item; the row then only offers "重新检查". */
    val repair: RuntimeSelfCheckRepair?
) {
    init {
        require(key.isNotBlank()) { "self-check row needs a stable key" }
        require(title.isNotBlank()) { "self-check row needs a title" }
        require(detail.isNotBlank()) { "self-check row needs a detail line" }
    }

    val statusLabel: String get() = status.label

    val repairLabel: String? get() = repair?.buttonLabel
}

/** Facts collected before building rows; every field is nullable so "not probed" is expressible. */
data class RuntimeSelfCheckProbe(
    val processRunning: Boolean? = null,
    val webPort: Int? = null,
    val webPortAvailable: Boolean? = null,
    val gatewayHealthy: Boolean? = null,
    val terminalPtyReady: Boolean? = null,
    val configDirectoryReady: Boolean? = null
)

/**
 * Builds the five rows.
 *
 * Ordering is fixed (process, Web port, gateway, terminal PTY, config directory) because the console
 * renders them in this order and the tests pin the keys, not only the copy. The two repair actions are
 * attached only where this build really performs them; the gateway, the PTY and the configuration
 * directory deliberately get no button.
 */
object RuntimeSelfCheck {

    const val KEY_PROCESS: String = "process"
    const val KEY_WEB_PORT: String = "web_port"
    const val KEY_GATEWAY: String = "gateway"
    const val KEY_TERMINAL: String = "terminal_pty"
    const val KEY_CONFIG_DIR: String = "config_dir"

    fun rows(probe: RuntimeSelfCheckProbe): List<RuntimeSelfCheckRow> = listOf(
        processRow(probe),
        webPortRow(probe),
        gatewayRow(probe),
        terminalRow(probe),
        configDirRow(probe)
    )

    fun repairableRows(rows: List<RuntimeSelfCheckRow>): List<RuntimeSelfCheckRow> =
        rows.filter { it.repair != null }

    private fun processRow(probe: RuntimeSelfCheckProbe) = RuntimeSelfCheckRow(
        key = KEY_PROCESS,
        title = "运行时进程",
        status = statusOf(probe.processRunning),
        detail = when (probe.processRunning) {
            true -> "DSH 运行时进程在运行"
            false -> "DSH 运行时进程未运行，需要重新启动"
            null -> "尚未检测运行时进程"
        },
        repair = if (probe.processRunning == false) RuntimeSelfCheckRepair.RESTART_RUNTIME else null
    )

    private fun webPortRow(probe: RuntimeSelfCheckProbe) = RuntimeSelfCheckRow(
        key = KEY_WEB_PORT,
        title = "Web 端口",
        status = when {
            probe.webPort == null || probe.webPortAvailable == null -> RuntimeSelfCheckStatus.UNKNOWN
            probe.webPortAvailable -> RuntimeSelfCheckStatus.READY
            else -> RuntimeSelfCheckStatus.FAILED
        },
        detail = when {
            probe.webPort == null || probe.webPortAvailable == null -> "尚未探测 Web 端口"
            probe.webPortAvailable -> "端口 ${probe.webPort} 可用"
            else -> "端口 ${probe.webPort} 被占用，可回退到相邻端口"
        },
        repair = if (probe.webPort != null && probe.webPortAvailable == false) {
            RuntimeSelfCheckRepair.USE_FALLBACK_PORT
        } else {
            null
        }
    )

    private fun gatewayRow(probe: RuntimeSelfCheckProbe) = RuntimeSelfCheckRow(
        key = KEY_GATEWAY,
        title = "模型网关",
        status = statusOf(probe.gatewayHealthy),
        detail = when (probe.gatewayHealthy) {
            true -> "模型网关健康检查通过"
            false -> "模型网关健康检查失败，请查看诊断"
            null -> "尚未检测模型网关"
        },
        repair = null
    )

    private fun terminalRow(probe: RuntimeSelfCheckProbe) = RuntimeSelfCheckRow(
        key = KEY_TERMINAL,
        title = "终端 PTY",
        status = statusOf(probe.terminalPtyReady),
        detail = when (probe.terminalPtyReady) {
            true -> "终端 PTY 可用"
            false -> "终端 PTY 不可用，请在设备通道页排查"
            null -> "尚未检测终端 PTY"
        },
        repair = null
    )

    private fun configDirRow(probe: RuntimeSelfCheckProbe) = RuntimeSelfCheckRow(
        key = KEY_CONFIG_DIR,
        title = "DSH 配置目录",
        status = statusOf(probe.configDirectoryReady),
        detail = when (probe.configDirectoryReady) {
            true -> "DSH 配置目录可读写"
            false -> "DSH 配置目录不可用，请重新运行安装或修复"
            null -> "尚未检测 DSH 配置目录"
        },
        repair = null
    )

    private fun statusOf(ready: Boolean?): RuntimeSelfCheckStatus = when (ready) {
        true -> RuntimeSelfCheckStatus.READY
        false -> RuntimeSelfCheckStatus.FAILED
        null -> RuntimeSelfCheckStatus.UNKNOWN
    }
}
