package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import com.muyuchat.core.dsh.contract.console.DshConsoleAccountProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleAccountReason
import com.muyuchat.core.dsh.contract.console.DshConsoleAccountState
import com.muyuchat.core.dsh.contract.console.DshConsoleOfficialSurface
import com.muyuchat.core.dsh.contract.console.DshConsoleSnapshot
import com.muyuchat.core.dsh.contract.console.DshScreenCaptureConsentPhase
import com.muyuchat.core.dsh.contract.console.DshScreenCaptureConsentState

/** Visual weight of one console row; the screen maps it to existing MCA theme colours. */
enum class DshConsoleTone { NEUTRAL, POSITIVE, WARNING, NEGATIVE }

/** One label/value row of the read-only console projection. */
data class DshConsoleRow(
    val label: String,
    val value: String,
    val tone: DshConsoleTone = DshConsoleTone.NEUTRAL
)

/** One titled group of rows. */
data class DshConsoleSection(
    val title: String,
    val rows: List<DshConsoleRow>
) {
    init {
        require(rows.isNotEmpty()) { "console section must carry at least one row" }
    }
}

/** One official surface the console may ask the owner to navigate to. */
data class DshConsoleSurfaceUi(
    val surface: DshConsoleOfficialSurface,
    val label: String,
    /**
     * True only when the owner currently advertises the surface. A known surface can stay visible
     * with `available = false` so the action is a typed, disabled entry instead of disappearing
     * and letting the user believe the official page was reached.
     */
    val available: Boolean = true
)

/** Fully rendered, read-only console model. It holds no owner state and no write capability. */
data class DshConsoleUiModel(
    val revision: Long,
    val capturedAtMillis: Long,
    val sections: List<DshConsoleSection>,
    val surfaces: List<DshConsoleSurfaceUi>
) {
    init {
        require(revision >= 0L) { "console UI revision must be non-negative" }
        require(capturedAtMillis >= 0L) { "console UI capture time must be non-negative" }
    }
}

/**
 * Pure projection mapper for the DSH-only console (D20).
 *
 * It turns the read-only contract snapshot into display rows. It never reads runtime, capability,
 * maintenance or diagnostics state directly, never writes any of them, and never echoes a token,
 * URL query, cookie, prompt or raw path: every value either comes from a bounded contract field or
 * is a fixed label defined below.
 */
object DshConsoleProjector {

    fun project(snapshot: DshConsoleSnapshot): DshConsoleUiModel = DshConsoleUiModel(
        revision = snapshot.revision,
        capturedAtMillis = snapshot.capturedAtMillis,
        sections = buildList {
            add(runtimeSection(snapshot))
            add(shellSandboxSection(snapshot))
            add(accountSection(snapshot))
            add(capabilitiesSection(snapshot))
            add(maintenanceSection(snapshot))
            add(diagnosticsSection(snapshot))
        },
        surfaces = buildList {
            snapshot.officialSurfaces
                .sortedBy { it.ordinal }
                .forEach { add(DshConsoleSurfaceUi(it, surfaceLabel(it), available = true)) }
            // The account surface is part of the console contract even when the app composition
            // cannot route it: keep the action visible but typed unavailable instead of pretending
            // the official account page was opened. See DshConsoleIntentPolicy for the gate.
            if (DshConsoleOfficialSurface.ACCOUNT !in snapshot.officialSurfaces) {
                add(
                    DshConsoleSurfaceUi(
                        surface = DshConsoleOfficialSurface.ACCOUNT,
                        label = surfaceLabel(DshConsoleOfficialSurface.ACCOUNT),
                        available = false
                    )
                )
            }
        }.sortedBy { it.surface.ordinal }
    )

    private fun runtimeSection(snapshot: DshConsoleSnapshot): DshConsoleSection {
        val runtime = snapshot.runtime
        val rows = mutableListOf(
            DshConsoleRow("运行阶段", phaseLabel(runtime.phase), phaseTone(runtime.phase)),
            DshConsoleRow("运行代次", runtime.generation.toString()),
            DshConsoleRow("页面来源", runtime.origin ?: "—"),
            DshConsoleRow(
                "首帧渲染",
                if (runtime.firstFrameRendered) "已渲染" else "未渲染",
                if (runtime.firstFrameRendered) DshConsoleTone.POSITIVE else DshConsoleTone.NEUTRAL
            ),
            DshConsoleRow(
                "模型绑定",
                if (runtime.modelBound) "已绑定" else "未绑定",
                if (runtime.modelBound) DshConsoleTone.POSITIVE else DshConsoleTone.WARNING
            ),
            DshConsoleRow(
                "停止状态",
                if (runtime.stopRequested) "已请求停止" else "未请求停止",
                if (runtime.stopRequested) DshConsoleTone.WARNING else DshConsoleTone.NEUTRAL
            )
        )
        runtime.failureCode?.let { code ->
            rows += DshConsoleRow("失败代码", code, DshConsoleTone.NEGATIVE)
        }
        return DshConsoleSection("运行状态", rows)
    }

    /**
     * The shell boundary is a fact, not a permission label. It is rendered as POSITIVE only when
     * the seeder's functional probe proved a real kernel write denial; an unprobed host and a host
     * whose kernel has no Landlock both read as high-trust execution.
     */
    private fun shellSandboxSection(snapshot: DshConsoleSnapshot): DshConsoleSection {
        val fact = snapshot.shellSandbox
        val value: String
        val detail: String
        val tone: DshConsoleTone
        when {
            !fact.known -> {
                value = "未探测"
                detail = "尚未探测内核写限制；不能视为工作区隔离"
                tone = DshConsoleTone.WARNING
            }

            fact.enforced && fact.partial -> {
                value = "内核限制（Landlock ABI ${fact.landlockAbi}，部分）"
                detail = "写限制已启用；truncate 未受该 ABI 控制"
                tone = DshConsoleTone.WARNING
            }

            fact.enforced -> {
                value = "内核限制（Landlock ABI ${fact.landlockAbi}）"
                detail = "工作区外写入由内核拒绝"
                tone = DshConsoleTone.POSITIVE
            }

            else -> {
                value = "高信任执行（未隔离）"
                detail = "内核未提供 Landlock；命令可写到工作区之外，仅由审批与信任约束"
                tone = DshConsoleTone.WARNING
            }
        }
        return DshConsoleSection(
            "命令执行",
            listOf(
                DshConsoleRow("工作区写边界", value, tone),
                DshConsoleRow("说明", detail, tone)
            )
        )
    }

    /**
     * The account attempt is a host-side observation, not a credential projection. The row never
     * says "logged in": the console can only show whether a bounded attempt is waiting, ended, or
     * unprovable. The official account page remains the only place that shows a confirmed login.
     */
    private fun accountSection(snapshot: DshConsoleSnapshot): DshConsoleSection {
        val account = snapshot.account
        val tone = accountStateTone(account.state)
        val rows = mutableListOf(
            DshConsoleRow("账号登录", accountStateLabel(account.state), tone),
            DshConsoleRow("尝试时长", attemptAgeLabel(account.attemptAgeMillis), tone)
        )
        if (account.reason != DshConsoleAccountReason.NONE) {
            rows += DshConsoleRow("原因", accountReasonLabel(account.reason), tone)
        }
        rows += DshConsoleRow(accountNoteLabel, accountNote, DshConsoleTone.NEUTRAL)
        return DshConsoleSection("账号", rows)
    }

    private fun capabilitiesSection(snapshot: DshConsoleSnapshot): DshConsoleSection {
        val rows = if (snapshot.capabilities.isEmpty()) {
            mutableListOf(DshConsoleRow("能力", "尚未上报", DshConsoleTone.NEUTRAL))
        } else {
            snapshot.capabilities.map { capability ->
                val tool = if (capability.toolRegistered) "工具已注册" else "工具未注册"
                val detail = capability.detail?.let { "：$it" }.orEmpty()
                DshConsoleRow(
                    capabilityLabel(capability.capability),
                    "${stateLabel(capability.state)} · $tool$detail",
                    stateTone(capability.state)
                )
            }.toMutableList()
        }
        // The capture consent is its own user-presence fact, not a property of the vscreen bridge
        // state: show it whenever that family is projected or a request exists in this process.
        if (snapshot.screenCapture.phase != DshScreenCaptureConsentPhase.IDLE ||
            snapshot.capabilities.any { it.capability == DshCapability.VIRTUAL_SCREEN }
        ) {
            rows += DshConsoleRow(
                screenCaptureLabel,
                screenCaptureValue(snapshot.screenCapture),
                screenCaptureTone(snapshot.screenCapture.phase)
            )
        }
        return DshConsoleSection("手机能力", rows)
    }

    private fun maintenanceSection(snapshot: DshConsoleSnapshot): DshConsoleSection {
        val maintenance = snapshot.maintenance
        val admission = maintenance.admission
        val rows = mutableListOf(
            DshConsoleRow(
                "维护屏障",
                if (admission.barrierActive) "持有中" else "空闲",
                if (admission.barrierActive) DshConsoleTone.WARNING else DshConsoleTone.NEUTRAL
            ),
            DshConsoleRow(
                "写租赁",
                admission.mutationLeaseCount.toString(),
                if (admission.mutationLeaseCount > 0) DshConsoleTone.WARNING else DshConsoleTone.NEUTRAL
            ),
            DshConsoleRow("读租赁", admission.readLeaseCount.toString()),
            DshConsoleRow(
                "可获取屏障",
                if (admission.barrierAcquirable) "是" else "否",
                if (admission.barrierAcquirable) DshConsoleTone.POSITIVE else DshConsoleTone.WARNING
            )
        )
        maintenance.journalHeadline?.let { headline ->
            rows += DshConsoleRow("维护日志", headline)
        }
        rows += DshConsoleRow("日志条目", maintenance.journalEntries.toString())
        return DshConsoleSection("维护准入", rows)
    }

    private fun diagnosticsSection(snapshot: DshConsoleSnapshot): DshConsoleSection {
        val diagnostics = snapshot.diagnostics
        val rows = mutableListOf(
            DshConsoleRow("WebView 代次", diagnostics.webViewGeneration.toString()),
            DshConsoleRow(
                "渲染进程重启",
                diagnostics.rendererRestarts.toString(),
                if (diagnostics.rendererRestarts > 0) DshConsoleTone.WARNING else DshConsoleTone.NEUTRAL
            )
        )
        diagnostics.lastErrorCode?.let { code ->
            rows += DshConsoleRow("最近错误", code, DshConsoleTone.NEGATIVE)
        }
        diagnostics.redactedLines.forEachIndexed { index, line ->
            rows += DshConsoleRow(if (index == 0) "诊断日志" else "", line)
        }
        return DshConsoleSection("诊断摘要", rows)
    }

    fun phaseLabel(phase: DshRuntimePhase): String = when (phase) {
        DshRuntimePhase.STOPPED -> "已停止"
        DshRuntimePhase.STARTING -> "启动中"
        DshRuntimePhase.READY -> "就绪"
        DshRuntimePhase.STOPPING -> "停止中"
        DshRuntimePhase.FAILED -> "失败"
    }

    fun phaseTone(phase: DshRuntimePhase): DshConsoleTone = when (phase) {
        DshRuntimePhase.READY -> DshConsoleTone.POSITIVE
        DshRuntimePhase.FAILED -> DshConsoleTone.NEGATIVE
        DshRuntimePhase.STARTING, DshRuntimePhase.STOPPING -> DshConsoleTone.WARNING
        DshRuntimePhase.STOPPED -> DshConsoleTone.NEUTRAL
    }

    fun capabilityLabel(capability: DshCapability): String = when (capability) {
        // One-shot families: a chooser pick or a create-document save never grants a directory.
        DshCapability.FILE_PICK -> "文件选择（单次）"
        DshCapability.FILE_SAVE -> "文件保存（单次）"
        DshCapability.NOTIFICATION -> "通知"
        DshCapability.DEVICE_ACTION -> "设备操作"
        DshCapability.ANDROID_SYSTEM -> "Android 系统"
        DshCapability.PRIVILEGED_SHELL -> "特权 Shell"
        DshCapability.ACCESSIBILITY -> "无障碍"
        DshCapability.VIRTUAL_SCREEN -> "虚拟屏"
        DshCapability.BROWSER -> "浏览器"
        DshCapability.WORKSPACE_TERMINAL -> "工作区终端"
        DshCapability.WORKSPACE_TREE -> "工作区目录（持久授权）"
    }

    fun stateLabel(state: DshCapabilityState): String = when (state) {
        DshCapabilityState.GRANTED -> "可用"
        DshCapabilityState.DENIED -> "已拒绝"
        DshCapabilityState.NOT_DECLARED -> "未声明"
        DshCapabilityState.NOT_ENABLED -> "未启用"
        DshCapabilityState.UNSUPPORTED -> "不支持"
    }

    fun stateTone(state: DshCapabilityState): DshConsoleTone = when (state) {
        DshCapabilityState.GRANTED -> DshConsoleTone.POSITIVE
        DshCapabilityState.DENIED, DshCapabilityState.UNSUPPORTED -> DshConsoleTone.NEGATIVE
        DshCapabilityState.NOT_DECLARED, DshCapabilityState.NOT_ENABLED -> DshConsoleTone.WARNING
    }

    /** Fixed label of the screen-capture consent row; no owner content is copied here. */
    const val screenCaptureLabel: String = "屏幕捕获"

    fun screenCaptureValue(consent: DshScreenCaptureConsentState): String {
        val label = when (consent.phase) {
            DshScreenCaptureConsentPhase.IDLE -> "未申请"
            DshScreenCaptureConsentPhase.REQUESTED -> "已请求"
            DshScreenCaptureConsentPhase.AWAITING_USER -> "等待系统授权"
            DshScreenCaptureConsentPhase.GRANTED -> "已授权"
            DshScreenCaptureConsentPhase.CANCELLED -> "已取消"
            DshScreenCaptureConsentPhase.REJECTED -> "被拒绝"
            DshScreenCaptureConsentPhase.RELEASED -> "已释放"
        }
        return consent.code?.let { "$label（$it）" } ?: label
    }

    fun screenCaptureTone(phase: DshScreenCaptureConsentPhase): DshConsoleTone = when (phase) {
        DshScreenCaptureConsentPhase.GRANTED -> DshConsoleTone.POSITIVE
        DshScreenCaptureConsentPhase.REJECTED -> DshConsoleTone.NEGATIVE
        DshScreenCaptureConsentPhase.REQUESTED,
        DshScreenCaptureConsentPhase.AWAITING_USER,
        DshScreenCaptureConsentPhase.CANCELLED -> DshConsoleTone.WARNING

        DshScreenCaptureConsentPhase.IDLE,
        DshScreenCaptureConsentPhase.RELEASED -> DshConsoleTone.NEUTRAL
    }

    /** Fixed label/note of the account row. The note never claims an account identity. */
    const val accountNoteLabel: String = "说明"
    const val accountNote: String =
        "宿主只在官方凭据存储写入后显示已登录；不读取凭据内容，账号身份以官方页面为准"

    fun accountStateLabel(state: DshConsoleAccountState): String = when (state) {
        DshConsoleAccountState.NOT_STARTED -> "未开始"
        DshConsoleAccountState.WAITING_BROWSER -> "等待浏览器"
        DshConsoleAccountState.WAITING_CALLBACK -> "等待回调"
        DshConsoleAccountState.TIMED_OUT -> "超时"
        DshConsoleAccountState.CANCELLED -> "已取消"
        DshConsoleAccountState.UNKNOWN -> "未知"
        DshConsoleAccountState.FAILED -> "失败"
        DshConsoleAccountState.SIGNED_IN -> "已登录（凭据存在）"
    }

    fun accountStateTone(state: DshConsoleAccountState): DshConsoleTone = when (state) {
        DshConsoleAccountState.NOT_STARTED,
        DshConsoleAccountState.CANCELLED -> DshConsoleTone.NEUTRAL

        DshConsoleAccountState.WAITING_BROWSER,
        DshConsoleAccountState.WAITING_CALLBACK,
        DshConsoleAccountState.TIMED_OUT,
        DshConsoleAccountState.UNKNOWN -> DshConsoleTone.WARNING

        DshConsoleAccountState.FAILED -> DshConsoleTone.NEGATIVE
        DshConsoleAccountState.SIGNED_IN -> DshConsoleTone.POSITIVE
    }

    fun accountReasonLabel(reason: DshConsoleAccountReason): String = when (reason) {
        DshConsoleAccountReason.NONE -> ""
        DshConsoleAccountReason.AUTHORIZE_URL_PENDING -> "正在等待官方授权地址"
        DshConsoleAccountReason.CALLBACK_PENDING -> "已交由浏览器授权，等待官方回调"
        DshConsoleAccountReason.OFFICIAL_EXCHANGE_PENDING -> "官方正在交换授权结果"
        DshConsoleAccountReason.ATTEMPT_TIMEOUT -> "尝试超过期限"
        DshConsoleAccountReason.ATTEMPT_CANCELLED -> "登录尝试已取消"
        DshConsoleAccountReason.ATTEMPT_BUSY -> "已有进行中的登录尝试"
        DshConsoleAccountReason.BROWSER_HANDLER_MISSING -> "设备没有可用的浏览器"
        DshConsoleAccountReason.CALLBACK_ORIGIN_INVALID -> "回调地址不符合官方环回要求"
        DshConsoleAccountReason.AUTHORIZE_URL_INVALID -> "授权地址不符合官方格式"
        DshConsoleAccountReason.POPUP_ORIGIN_UNTRUSTED -> "弹窗来源不可信"
        DshConsoleAccountReason.OFFICIAL_MODULE_MISSING -> "载荷不含官方账号模块"
        DshConsoleAccountReason.CALLBACK_STATE_MISMATCH -> "回调状态不匹配"
        DshConsoleAccountReason.CREDENTIAL_STORE_FAILED -> "官方凭据存储失败"
        DshConsoleAccountReason.PROTOCOL_ERROR -> "官方协议数据异常"
        DshConsoleAccountReason.RUNTIME_GENERATION_LOST -> "DSH 运行时已停止或重启"
        DshConsoleAccountReason.OFFICIAL_OUTCOME_UNOBSERVED -> "官方结果无法在应用侧确认"
        DshConsoleAccountReason.OUTCOME_UNVERIFIED -> "外部步骤结果无法确认"
        DshConsoleAccountReason.SOURCE_UNAVAILABLE -> "账号宿主状态未接入"
        DshConsoleAccountReason.OTHER_FAILURE -> "其他失败"
        DshConsoleAccountReason.CREDENTIAL_STORE_PRESENT -> "官方凭据已写入（仅表示凭据存在）"
    }

    /**
     * Bounded attempt-age text. The value is the age observed by this console, never an official
     * attempt-start claim; zero renders as an explicit "—" so terminal/unknown rows cannot imply
     * a measured duration that was never observed.
     */
    fun attemptAgeLabel(ageMillis: Long): String {
        val bounded = ageMillis.coerceIn(0L, DshConsoleAccountProjection.MAX_ATTEMPT_AGE_MILLIS)
        if (bounded <= 0L) return "—"
        val totalSeconds = bounded / 1_000L
        if (totalSeconds < 60L) return "$totalSeconds 秒"
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (seconds == 0L) "$minutes 分" else "$minutes 分 $seconds 秒"
    }

    fun surfaceLabel(surface: DshConsoleOfficialSurface): String = when (surface) {
        DshConsoleOfficialSurface.SETTINGS -> "官方设置"
        DshConsoleOfficialSurface.ACCOUNT -> "官方账号"
        DshConsoleOfficialSurface.PLUGINS -> "官方插件"
        DshConsoleOfficialSurface.SESSIONS -> "官方会话"
        DshConsoleOfficialSurface.BROWSER -> "AI 浏览器"
        DshConsoleOfficialSurface.VIRTUAL_SCREEN -> "虚拟屏"
        DshConsoleOfficialSurface.MAINTENANCE -> "数据维护"
        DshConsoleOfficialSurface.DIAGNOSTICS -> "诊断"
    }
}
