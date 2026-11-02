package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceAdmissionSnapshot
import com.muyuchat.core.dsh.contract.console.DshConsoleAccountProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleAccountReason
import com.muyuchat.core.dsh.contract.console.DshConsoleAccountState
import com.muyuchat.core.dsh.contract.console.DshConsoleCapabilityProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleDiagnosticsProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleMaintenanceProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleOfficialSurface
import com.muyuchat.core.dsh.contract.console.DshConsoleRuntimeProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleShellSandboxProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleSnapshot
import com.muyuchat.core.dsh.contract.console.DshScreenCaptureConsentPhase
import com.muyuchat.core.dsh.contract.console.DshScreenCaptureConsentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshConsoleProjectorTest {

    @Test
    fun projectsRuntimeRowsFromTheReadOnlySnapshot() {
        val model = DshConsoleProjector.project(snapshot())

        val runtime = section(model, "运行状态")
        assertEquals("就绪", valueOf(runtime, "运行阶段"))
        assertEquals("7", valueOf(runtime, "运行代次"))
        assertEquals("http://127.0.0.1:34001", valueOf(runtime, "页面来源"))
        assertEquals("已渲染", valueOf(runtime, "首帧渲染"))
        assertEquals("已绑定", valueOf(runtime, "模型绑定"))
        assertEquals("未请求停止", valueOf(runtime, "停止状态"))
    }

    @Test
    fun failureCodeIsProjectedWithANegativeTone() {
        val model = DshConsoleProjector.project(
            snapshot(
                runtime = runtime().copy(phase = DshRuntimePhase.FAILED, failureCode = "NODE_START_FAILED")
            )
        )

        val row = requireNotNull(
            section(model, "运行状态").rows.firstOrNull { it.label == "失败代码" }
        )
        assertEquals("NODE_START_FAILED", row.value)
        assertEquals(DshConsoleTone.NEGATIVE, row.tone)
    }

    @Test
    fun capabilitiesShowStateAndToolRegistration() {
        val model = DshConsoleProjector.project(
            snapshot(
                capabilities = listOf(
                    DshConsoleCapabilityProjection(
                        DshCapability.ANDROID_SYSTEM,
                        DshCapabilityState.GRANTED,
                        toolRegistered = true
                    ),
                    DshConsoleCapabilityProjection(
                        DshCapability.ACCESSIBILITY,
                        DshCapabilityState.NOT_ENABLED,
                        toolRegistered = false,
                        detail = "服务未开启"
                    )
                )
            )
        )

        val section = section(model, "手机能力")
        assertEquals("可用 · 工具已注册", valueOf(section, "Android 系统"))
        assertEquals("未启用 · 工具未注册：服务未开启", valueOf(section, "无障碍"))
        assertEquals(DshConsoleTone.WARNING, rowOf(section, "无障碍").tone)
    }

    @Test
    fun emptyCapabilitiesProjectAnExplicitPlaceholder() {
        val model = DshConsoleProjector.project(snapshot(capabilities = emptyList()))

        assertEquals("尚未上报", valueOf(section(model, "手机能力"), "能力"))
    }

    @Test
    fun theWorkspaceTerminalFamilyIsProjectedWithItsOwnLabelAndHonestState() {
        fun terminalRow(
            state: DshCapabilityState,
            detail: String
        ): DshConsoleRow {
            val model = DshConsoleProjector.project(
                snapshot(
                    capabilities = listOf(
                        DshConsoleCapabilityProjection(
                            DshCapability.WORKSPACE_TERMINAL,
                            state,
                            toolRegistered = true,
                            detail = detail
                        )
                    )
                )
            )
            return rowOf(
                section(model, "手机能力"),
                DshConsoleProjector.capabilityLabel(DshCapability.WORKSPACE_TERMINAL)
            )
        }

        val granted = terminalRow(DshCapabilityState.GRANTED, "real pty · sessions=1 · seam=active")
        assertEquals("可用 · 工具已注册：real pty · sessions=1 · seam=active", granted.value)
        assertEquals(DshConsoleTone.POSITIVE, granted.tone)

        val degraded = terminalRow(
            DshCapabilityState.NOT_ENABLED,
            "no real pty (PIPE_FALLBACK: NATIVE_LOAD_FAILED: dlopen failed)"
        )
        assertTrue(degraded.value.startsWith("未启用 · 工具已注册：no real pty"))
        assertEquals(DshConsoleTone.WARNING, degraded.tone)
        assertEquals("工作区终端", DshConsoleProjector.capabilityLabel(DshCapability.WORKSPACE_TERMINAL))
    }

    @Test
    fun maintenanceProjectsTheAdmissionSnapshot() {
        val model = DshConsoleProjector.project(
            snapshot(
                maintenance = DshConsoleMaintenanceProjection(
                    admission = DshMaintenanceAdmissionSnapshot(
                        barrierActive = true,
                        mutationLeaseCount = 2,
                        readLeaseCount = 1
                    ),
                    journalHeadline = "BACKUP_STAGING",
                    journalEntries = 12
                )
            )
        )

        val section = section(model, "维护准入")
        assertEquals("持有中", valueOf(section, "维护屏障"))
        assertEquals("2", valueOf(section, "写租赁"))
        assertEquals("1", valueOf(section, "读租赁"))
        assertEquals("否", valueOf(section, "可获取屏障"))
        assertEquals("BACKUP_STAGING", valueOf(section, "维护日志"))
        assertEquals("12", valueOf(section, "日志条目"))
        assertEquals(DshConsoleTone.WARNING, rowOf(section, "维护屏障").tone)
    }

    @Test
    fun diagnosticsBecomeBoundedRows() {
        val model = DshConsoleProjector.project(
            snapshot(
                diagnostics = DshConsoleDiagnosticsProjection(
                    webViewGeneration = 3,
                    rendererRestarts = 2,
                    lastErrorCode = "RENDERER_GONE",
                    redactedLines = listOf("host ready", "page loaded")
                )
            )
        )

        val section = section(model, "诊断摘要")
        assertEquals("3", valueOf(section, "WebView 代次"))
        assertEquals("2", valueOf(section, "渲染进程重启"))
        assertEquals("RENDERER_GONE", valueOf(section, "最近错误"))
        assertEquals(listOf("host ready", "page loaded"), section.rows.map { it.value }.filter {
            it == "host ready" || it == "page loaded"
        })
    }

    @Test
    fun officialSurfacesAreSortedAndLabelled() {
        val model = DshConsoleProjector.project(
            snapshot(
                surfaces = setOf(
                    DshConsoleOfficialSurface.DIAGNOSTICS,
                    DshConsoleOfficialSurface.SETTINGS,
                    DshConsoleOfficialSurface.ACCOUNT
                )
            )
        )

        assertEquals(
            listOf(
                DshConsoleOfficialSurface.SETTINGS,
                DshConsoleOfficialSurface.ACCOUNT,
                DshConsoleOfficialSurface.DIAGNOSTICS
            ),
            model.surfaces.map { it.surface }
        )
        assertEquals("官方设置", model.surfaces.first().label)
    }

    @Test
    fun projectionIsDeterministic() {
        val snapshot = snapshot()

        assertEquals(
            DshConsoleProjector.project(snapshot),
            DshConsoleProjector.project(snapshot)
        )
    }

    @Test
    fun projectionNeverCarriesATokenOrQuery() {
        val model = DshConsoleProjector.project(snapshot())

        val text = model.sections.flatMap { section -> section.rows.map { it.label + it.value } }
            .joinToString(" ")
        assertFalse(text.contains("token", ignoreCase = true))
        assertFalse(text.contains("?"))
        assertTrue(text.contains("http://127.0.0.1:34001"))
    }

    @Test
    fun screenCaptureConsentIsProjectedAsItsOwnFact() {
        val granted = DshConsoleProjector.project(
            snapshot(
                screenCapture = DshScreenCaptureConsentState(
                    DshScreenCaptureConsentPhase.GRANTED
                )
            )
        )
        val grantedSection = section(granted, "手机能力")
        assertEquals("已授权", valueOf(grantedSection, "屏幕捕获"))
        assertEquals(DshConsoleTone.POSITIVE, rowOf(grantedSection, "屏幕捕获").tone)

        val cancelled = DshConsoleProjector.project(
            snapshot(
                capabilities = listOf(
                    DshConsoleCapabilityProjection(
                        DshCapability.VIRTUAL_SCREEN,
                        DshCapabilityState.GRANTED,
                        toolRegistered = true
                    )
                ),
                screenCapture = DshScreenCaptureConsentState(
                    phase = DshScreenCaptureConsentPhase.CANCELLED,
                    code = "consent_required"
                )
            )
        )
        val cancelledSection = section(cancelled, "手机能力")
        assertEquals("已取消（consent_required）", valueOf(cancelledSection, "屏幕捕获"))
        assertEquals(DshConsoleTone.WARNING, rowOf(cancelledSection, "屏幕捕获").tone)
    }

    @Test
    fun screenCaptureRowIsAbsentWhenNothingWasRequestedAndVscreenIsNotProjected() {
        val model = DshConsoleProjector.project(snapshot())

        assertFalse(
            section(model, "手机能力").rows.any { it.label == DshConsoleProjector.screenCaptureLabel }
        )
    }

    @Test
    fun accountSectionShowsTheAttemptStateAgeAndTypedReason() {
        val model = DshConsoleProjector.project(
            snapshot(
                account = DshConsoleAccountProjection(
                    state = DshConsoleAccountState.WAITING_CALLBACK,
                    attemptAgeMillis = 42_000L,
                    reason = DshConsoleAccountReason.CALLBACK_PENDING
                )
            )
        )

        val account = section(model, "账号")
        assertEquals("等待回调", valueOf(account, "账号登录"))
        assertEquals("42 秒", valueOf(account, "尝试时长"))
        assertEquals("已交由浏览器授权，等待官方回调", valueOf(account, "原因"))
        assertEquals(DshConsoleTone.WARNING, rowOf(account, "账号登录").tone)
    }

    @Test
    fun accountTimeoutsAndCancelsAreShownWithoutASuccessClaim() {
        val timedOut = DshConsoleProjector.project(
            snapshot(
                account = DshConsoleAccountProjection(
                    state = DshConsoleAccountState.TIMED_OUT,
                    attemptAgeMillis = 600_000L,
                    reason = DshConsoleAccountReason.ATTEMPT_TIMEOUT
                )
            )
        )
        val timedOutSection = section(timedOut, "账号")
        assertEquals("超时", valueOf(timedOutSection, "账号登录"))
        assertEquals("10 分", valueOf(timedOutSection, "尝试时长"))
        assertEquals("尝试超过期限", valueOf(timedOutSection, "原因"))

        val cancelled = DshConsoleProjector.project(
            snapshot(
                account = DshConsoleAccountProjection(
                    state = DshConsoleAccountState.CANCELLED,
                    attemptAgeMillis = 0L,
                    reason = DshConsoleAccountReason.ATTEMPT_CANCELLED
                )
            )
        )
        val cancelledSection = section(cancelled, "账号")
        assertEquals("已取消", valueOf(cancelledSection, "账号登录"))
        assertEquals("—", valueOf(cancelledSection, "尝试时长"))
        assertEquals(DshConsoleTone.NEUTRAL, rowOf(cancelledSection, "账号登录").tone)
    }

    @Test
    fun aMissingAccountSourceIsTypedUnavailableInsteadOfNotStarted() {
        val model = DshConsoleProjector.project(snapshot(account = DshConsoleAccountProjection()))

        val account = section(model, "账号")
        assertEquals("未知", valueOf(account, "账号登录"))
        assertEquals("账号宿主状态未接入", valueOf(account, "原因"))
        assertEquals("—", valueOf(account, "尝试时长"))
        assertEquals(DshConsoleTone.WARNING, rowOf(account, "账号登录").tone)
    }

    @Test
    fun onlyThePresenceObservedStateCarriesASignedInLabel() {
        DshConsoleAccountState.entries.forEach { state ->
            val label = DshConsoleProjector.accountStateLabel(state)
            if (state == DshConsoleAccountState.SIGNED_IN) {
                assertTrue("the presence state must say what it is", label.contains("\u51ed\u636e\u5b58\u5728"))
            } else {
                assertFalse("state $state must not claim a login", label.contains("\u5df2\u767b\u5f55"))
            }
            assertFalse("state $state must not claim success", label.contains("\u767b\u5f55\u6210\u529f"))
        }
        assertTrue(DshConsoleProjector.accountNote.contains("\u51ed\u636e\u5b58\u50a8"))
        assertTrue(DshConsoleProjector.accountNote.contains("\u4e0d\u8bfb\u53d6\u51ed\u636e\u5185\u5bb9"))
        assertFalse(DshConsoleProjector.accountNote.contains("\u767b\u5f55\u6210\u529f"))
    }

    @Test
    fun anAccountAttemptNeverLeaksAUrlQueryOrTokenIntoTheConsoleText() {
        val model = DshConsoleProjector.project(
            snapshot(
                account = DshConsoleAccountProjection(
                    state = DshConsoleAccountState.FAILED,
                    attemptAgeMillis = 5_000L,
                    reason = DshConsoleAccountReason.CREDENTIAL_STORE_FAILED
                )
            )
        )

        val accountText = section(model, "账号").rows.joinToString(" ") { it.label + it.value }
        assertFalse(accountText.contains("http", ignoreCase = true))
        assertFalse(accountText.contains("?"))
        assertFalse(accountText.contains("token", ignoreCase = true))
        assertFalse(accountText.contains("code", ignoreCase = true))
        assertFalse(accountText.contains("cookie", ignoreCase = true))
    }

    @Test
    fun theOfficialAccountActionIsVisibleButTypedUnavailableWhenTheOwnerCannotRouteIt() {
        val model = DshConsoleProjector.project(snapshot(surfaces = emptySet()))

        val account = requireNotNull(
            model.surfaces.firstOrNull { it.surface == DshConsoleOfficialSurface.ACCOUNT }
        )
        assertEquals("官方账号", account.label)
        assertFalse("no typed route is advertised by the owner", account.available)
    }

    @Test
    fun anAdvertisedOfficialAccountActionIsAvailable() {
        val model = DshConsoleProjector.project(
            snapshot(surfaces = setOf(DshConsoleOfficialSurface.ACCOUNT))
        )

        val account = requireNotNull(
            model.surfaces.firstOrNull { it.surface == DshConsoleOfficialSurface.ACCOUNT }
        )
        assertTrue(account.available)
        assertEquals(1, model.surfaces.count { it.surface == DshConsoleOfficialSurface.ACCOUNT })
    }

    @Test
    fun anAdvisoryShellSandboxIsProjectedAsHighTrustNotIsolation() {
        val model = DshConsoleProjector.project(
            snapshot(
                shellSandbox = DshConsoleShellSandboxProjection(
                    known = true,
                    enforced = false,
                    advisoryReason = "KERNEL_PROBE_FAILED"
                )
            )
        )

        val section = section(model, "命令执行")
        assertEquals("高信任执行（未隔离）", valueOf(section, "工作区写边界"))
        assertEquals(DshConsoleTone.WARNING, rowOf(section, "工作区写边界").tone)
        assertTrue(rowOf(section, "说明").value.contains("Landlock"))
    }

    @Test
    fun aKernelEnforcedShellSandboxShowsTheAbiAsPositive() {
        val model = DshConsoleProjector.project(
            snapshot(
                shellSandbox = DshConsoleShellSandboxProjection(
                    known = true,
                    enforced = true,
                    landlockAbi = 6
                )
            )
        )

        val section = section(model, "命令执行")
        assertTrue(valueOf(section, "工作区写边界").contains("ABI 6"))
        assertEquals(DshConsoleTone.POSITIVE, rowOf(section, "工作区写边界").tone)
    }

    @Test
    fun anUnprobedShellSandboxIsNeverProjectedAsEnforced() {
        val model = DshConsoleProjector.project(snapshot())

        val section = section(model, "命令执行")
        assertEquals("未探测", valueOf(section, "工作区写边界"))
        assertEquals(DshConsoleTone.WARNING, rowOf(section, "工作区写边界").tone)
    }

    private fun snapshot(
        runtime: DshConsoleRuntimeProjection = runtime(),
        account: DshConsoleAccountProjection = DshConsoleAccountProjection(),
        capabilities: List<DshConsoleCapabilityProjection> = listOf(
            DshConsoleCapabilityProjection(
                DshCapability.ANDROID_SYSTEM,
                DshCapabilityState.GRANTED,
                toolRegistered = true
            )
        ),
        maintenance: DshConsoleMaintenanceProjection = DshConsoleMaintenanceProjection(
            DshMaintenanceAdmissionSnapshot(
                barrierActive = false,
                mutationLeaseCount = 0,
                readLeaseCount = 0
            )
        ),
        diagnostics: DshConsoleDiagnosticsProjection = DshConsoleDiagnosticsProjection(),
        surfaces: Set<DshConsoleOfficialSurface> = setOf(DshConsoleOfficialSurface.SETTINGS),
        screenCapture: DshScreenCaptureConsentState = DshScreenCaptureConsentState(),
        shellSandbox: DshConsoleShellSandboxProjection = DshConsoleShellSandboxProjection()
    ): DshConsoleSnapshot = DshConsoleSnapshot(
        revision = 4,
        capturedAtMillis = 2_000L,
        runtime = runtime,
        capabilities = capabilities,
        screenCapture = screenCapture,
        account = account,
        maintenance = maintenance,
        diagnostics = diagnostics,
        shellSandbox = shellSandbox,
        officialSurfaces = surfaces
    )

    private fun runtime() = DshConsoleRuntimeProjection(
        phase = DshRuntimePhase.READY,
        generation = 7L,
        origin = "http://127.0.0.1:34001",
        stopRequested = false,
        firstFrameRendered = true,
        failureCode = null,
        modelBound = true
    )

    private fun section(model: DshConsoleUiModel, title: String): DshConsoleSection =
        requireNotNull(model.sections.firstOrNull { it.title == title }) {
            "missing console section '$title'; have ${model.sections.map { it.title }}"
        }

    private fun valueOf(section: DshConsoleSection, label: String): String =
        rowOf(section, label).value

    private fun rowOf(section: DshConsoleSection, label: String): DshConsoleRow =
        requireNotNull(section.rows.firstOrNull { it.label == label }) {
            "missing row '$label'; have ${section.rows.map { it.label }}"
        }
}
