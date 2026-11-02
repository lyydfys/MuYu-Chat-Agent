package com.muyuchat.feature.agent.workspace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun executableItem(
    id: String = "exec-pkg",
    name: String = "Exec",
    version: String = "1.0.0",
    enabled: Boolean = false,
    revoked: Boolean = false,
    rollback: Boolean = false,
    tools: Int = 1,
    verified: Boolean = true,
    problem: String? = null
) = AgentExecutablePackageItem(
    packageId = id,
    displayName = name,
    version = version,
    enabled = enabled,
    revoked = revoked,
    rollbackAvailable = rollback,
    toolCount = tools,
    contentVerified = verified,
    problemCode = problem
)

class AgentExecutablePackagesCoordinatorTest {

    private class FakeDocuments : AgentDocumentLauncher {
        var next: AgentDocumentRef? = AgentDocumentRef("content://docs/exec.zip")
        var openCalls = 0

        override fun createDocument(
            suggestedFileName: String,
            onResult: (AgentDocumentRef?) -> Unit
        ) = Unit

        override fun openDocument(onResult: (AgentDocumentRef?) -> Unit) {
            openCalls++
            onResult(next)
        }
    }

    private class FakePort : AgentToolPackageAdminPort {
        var executableList: AgentExecutablePackageListResult =
            AgentExecutablePackageListResult.Loaded(emptyList())
        var actionResult: AgentExecutablePackageActionResult =
            AgentExecutablePackageActionResult.Applied("exec-pkg", "已启用")
        var installResult: AgentExecutablePackageActionResult =
            AgentExecutablePackageActionResult.Applied("exec-pkg", "已安装并校验；默认未启用。")
        var throwOnList = false
        var throwOnAction = false
        var throwOnInstall = false

        var listCalls = 0
        var installCalls = 0
        val actions = mutableListOf<Pair<String, String>>()

        override suspend fun list(): AgentToolPackageListResult =
            AgentToolPackageListResult.Loaded(emptyList())

        override suspend fun preview(source: AgentDocumentRef): AgentToolPackagePreviewResult =
            AgentToolPackagePreviewResult.Rejected("NOT_USED", "not used")

        override suspend fun listExecutable(): AgentExecutablePackageListResult {
            listCalls++
            if (throwOnList) error("boom")
            return executableList
        }

        override suspend fun installExecutable(source: AgentDocumentRef): AgentExecutablePackageActionResult {
            installCalls++
            if (throwOnInstall) error("boom")
            return installResult
        }

        override suspend fun enableExecutable(packageId: String): AgentExecutablePackageActionResult =
            record("ENABLE", packageId)

        override suspend fun disableExecutable(packageId: String): AgentExecutablePackageActionResult =
            record("DISABLE", packageId)

        override suspend fun rollbackExecutable(packageId: String): AgentExecutablePackageActionResult =
            record("ROLLBACK", packageId)

        override suspend fun revokeExecutable(packageId: String): AgentExecutablePackageActionResult =
            record("REVOKE", packageId)

        override suspend fun clearExecutableRevocation(packageId: String): AgentExecutablePackageActionResult =
            record("CLEAR_REVOCATION", packageId)

        override suspend fun uninstallExecutable(packageId: String): AgentExecutablePackageActionResult =
            record("UNINSTALL", packageId)

        override suspend fun collectExecutableGarbage(): AgentExecutablePackageActionResult =
            AgentExecutablePackageActionResult.Applied(null, "已清理 1 项未引用内容。")

        private fun record(kind: String, packageId: String): AgentExecutablePackageActionResult {
            actions += kind to packageId
            if (throwOnAction) error("boom")
            return actionResult
        }
    }

    private class Harness {
        val port = FakePort()
        val documents = FakeDocuments()
        val coordinator = AgentToolPackagesCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            port = port,
            documents = documents
        )
    }

    @Test
    fun `open loads the executable list and projects status`() {
        val harness = Harness()
        harness.port.executableList = AgentExecutablePackageListResult.Loaded(
            listOf(
                executableItem(id = "b", name = "Beta", enabled = true, rollback = true, tools = 2),
                executableItem(id = "a", name = "Alpha", revoked = true)
            )
        )

        harness.coordinator.open()

        val executable = harness.coordinator.state.value.executable
        assertTrue(executable.supported)
        assertEquals(listOf("Alpha", "Beta"), executable.items.map { it.displayName })
        assertEquals("已撤销", executable.items.single { it.packageId == "a" }.statusLabel)
        val beta = executable.items.single { it.packageId == "b" }
        assertEquals("已开启", beta.statusLabel)
        assertTrue(beta.enabled)
        assertTrue(beta.rollbackAvailable)
        assertTrue(beta.canDisable)
        assertFalse(beta.canEnable)
        assertTrue(executable.canInstall)
    }

    @Test
    fun `a not-supported backend flips the block to inert without an error banner`() {
        val harness = Harness()
        harness.port.executableList = AgentExecutablePackageListResult.Rejected(
            AgentExecutablePackageSupport.NOT_SUPPORTED_CODE,
            AgentExecutablePackageSupport.NOT_SUPPORTED_MESSAGE
        )

        harness.coordinator.open()

        val executable = harness.coordinator.state.value.executable
        assertFalse(executable.supported)
        assertNull(executable.error)
        assertTrue(executable.items.isEmpty())
        assertFalse(executable.canInstall)
    }

    @Test
    fun `install opens the picker once and surfaces the applied notice`() {
        val harness = Harness()

        harness.coordinator.open()
        harness.coordinator.installExecutablePackage()

        assertEquals(1, harness.documents.openCalls)
        assertEquals(1, harness.port.installCalls)
        assertEquals(
            "已安装并校验；默认未启用。",
            harness.coordinator.state.value.executable.notice
        )
        assertNull(harness.coordinator.state.value.executable.error)
        assertFalse(harness.coordinator.state.value.executable.installing)
    }

    @Test
    fun `a cancelled picker never reaches the install port`() {
        val harness = Harness()
        harness.documents.next = null
        harness.coordinator.open()

        harness.coordinator.installExecutablePackage()

        assertEquals(1, harness.documents.openCalls)
        assertEquals(0, harness.port.installCalls)
    }

    @Test
    fun `enable asks for confirmation and only calls the port after confirm`() {
        val harness = Harness()
        harness.port.executableList = AgentExecutablePackageListResult.Loaded(
            listOf(executableItem(id = "exec-pkg"))
        )
        harness.coordinator.open()

        harness.coordinator.requestExecutableAction(
            "exec-pkg",
            AgentExecutablePackageAction.ENABLE
        )

        val confirmation = harness.coordinator.state.value.executable.confirmation
        assertNotNull(confirmation)
        assertEquals(AgentExecutablePackageAction.ENABLE, confirmation!!.action)
        assertEquals("启用", confirmation.confirmLabel)
        assertTrue(harness.port.actions.isEmpty())

        harness.coordinator.confirmExecutableAction()

        assertEquals(listOf("ENABLE" to "exec-pkg"), harness.port.actions)
        assertNull(harness.coordinator.state.value.executable.confirmation)
        assertEquals("已启用", harness.coordinator.state.value.executable.notice)
    }

    @Test
    fun `cancelling a confirmation performs no action`() {
        val harness = Harness()
        harness.coordinator.open()
        harness.coordinator.requestExecutableAction(
            "exec-pkg",
            AgentExecutablePackageAction.UNINSTALL
        )
        assertNotNull(harness.coordinator.state.value.executable.confirmation)

        harness.coordinator.cancelExecutableAction()

        assertNull(harness.coordinator.state.value.executable.confirmation)
        assertTrue(harness.port.actions.isEmpty())
    }

    @Test
    fun `rollback revoke and uninstall each require their own confirmation`() {
        val harness = Harness()
        harness.coordinator.open()
        val confirmations = listOf(
            AgentExecutablePackageAction.ROLLBACK to "回滚",
            AgentExecutablePackageAction.REVOKE to "撤销",
            AgentExecutablePackageAction.UNINSTALL to "卸载"
        )

        confirmations.forEach { (action, label) ->
            harness.coordinator.requestExecutableAction("exec-pkg", action)
            val confirmation = harness.coordinator.state.value.executable.confirmation
            assertNotNull("$action must be confirmed", confirmation)
            assertEquals(label, confirmation!!.confirmLabel)
            harness.coordinator.cancelExecutableAction()
        }
        assertTrue("no action may run before confirmation", harness.port.actions.isEmpty())
    }

    @Test
    fun `clearing a revocation is immediate and needs no confirmation`() {
        val harness = Harness()
        harness.port.executableList = AgentExecutablePackageListResult.Loaded(
            listOf(executableItem(id = "exec-pkg", revoked = true))
        )
        harness.coordinator.open()

        harness.coordinator.requestExecutableAction(
            "exec-pkg",
            AgentExecutablePackageAction.CLEAR_REVOCATION
        )

        assertNull(harness.coordinator.state.value.executable.confirmation)
        assertEquals(listOf("CLEAR_REVOCATION" to "exec-pkg"), harness.port.actions)
    }

    @Test
    fun `a rejected action surfaces the typed message and clears busy`() {
        val harness = Harness()
        harness.port.actionResult = AgentExecutablePackageActionResult.Rejected(
            "TOOLPACK_REVOKED",
            "This package was revoked; clear the revocation before enabling it again."
        )
        harness.coordinator.open()
        harness.coordinator.requestExecutableAction("exec-pkg", AgentExecutablePackageAction.ENABLE)

        harness.coordinator.confirmExecutableAction()

        val executable = harness.coordinator.state.value.executable
        assertEquals("TOOLPACK_REVOKED", executable.error?.code)
        assertNull(executable.notice)
        assertNull(executable.busyPackageId)
    }

    @Test
    fun `a thrown action failure becomes a generic safe error`() {
        val harness = Harness()
        harness.port.throwOnAction = true
        harness.coordinator.open()
        harness.coordinator.requestExecutableAction("exec-pkg", AgentExecutablePackageAction.DISABLE)

        harness.coordinator.confirmExecutableAction()

        assertEquals(
            AgentExecutablePackageSupport.ACTION_FAILED_CODE,
            harness.coordinator.state.value.executable.error?.code
        )
    }

    @Test
    fun `a thrown list failure keeps the last rows and shows the typed error`() {
        val harness = Harness()
        harness.port.executableList = AgentExecutablePackageListResult.Loaded(
            listOf(executableItem(id = "exec-pkg"))
        )
        harness.coordinator.open()
        assertEquals(1, harness.coordinator.state.value.executable.items.size)

        harness.port.throwOnList = true
        harness.coordinator.refreshExecutable()

        val executable = harness.coordinator.state.value.executable
        assertEquals(AgentExecutablePackageSupport.LIST_FAILED_CODE, executable.error?.code)
        assertEquals(1, executable.items.size)
        assertTrue(executable.supported)
    }

    @Test
    fun `dismissing the status clears both the v1 and executable banners`() {
        val harness = Harness()
        harness.port.actionResult = AgentExecutablePackageActionResult.Rejected("X", "失败")
        harness.coordinator.open()
        harness.coordinator.requestExecutableAction("exec-pkg", AgentExecutablePackageAction.ENABLE)
        harness.coordinator.confirmExecutableAction()
        assertNotNull(harness.coordinator.state.value.executable.error)

        harness.coordinator.dismissStatus()

        assertNull(harness.coordinator.state.value.executable.error)
        assertNull(harness.coordinator.state.value.executable.notice)
        assertNull(harness.coordinator.state.value.error)
    }

    @Test
    fun `unverified and revoked rows never offer the wrong action`() {
        val broken = projectExecutablePackageItem(
            executableItem(id = "broken", verified = false, problem = "TOOLPACK_CONTENT_HASH_MISMATCH")
        )
        assertEquals("校验失败", broken.statusLabel)
        assertFalse(broken.canEnable)
        assertFalse(broken.canRollback)
        assertNotNull(broken.problemLabel)

        val revoked = projectExecutablePackageItem(executableItem(id = "revoked", revoked = true))
        assertEquals("已撤销", revoked.statusLabel)
        assertFalse(revoked.canEnable)
        assertTrue(revoked.canClearRevocation)
        assertTrue(revoked.canUninstall)

        val disabled = projectExecutablePackageItem(executableItem(id = "plain"))
        assertEquals("已停用", disabled.statusLabel)
        assertTrue(disabled.canEnable)
        assertFalse(disabled.canDisable)
        assertFalse(disabled.canRollback)
    }

    @Test
    fun `confirmation copy is action-specific and clearing revocation has none`() {
        val enable = executablePackageConfirmation("p", "Pkg", AgentExecutablePackageAction.ENABLE)
        val uninstall = executablePackageConfirmation("p", "Pkg", AgentExecutablePackageAction.UNINSTALL)
        val clear = executablePackageConfirmation(
            "p",
            "Pkg",
            AgentExecutablePackageAction.CLEAR_REVOCATION
        )

        assertTrue(enable!!.body.contains("下一次运行"))
        assertTrue(uninstall!!.body.contains("删除"))
        assertNull(clear)
        assertEquals("Pkg", enable.displayName)
    }
}
