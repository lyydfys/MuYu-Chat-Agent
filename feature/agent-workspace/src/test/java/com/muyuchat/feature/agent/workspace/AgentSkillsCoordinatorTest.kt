package com.muyuchat.feature.agent.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun skillItem(
    id: String,
    name: String = id,
    enabled: Boolean = false,
    version: Long = 1L,
    packageVersion: String = "1.0.0",
    toolCount: Int = 1
) = AgentSkillAdminItem(
    skillId = id,
    name = name,
    version = version,
    packageVersion = packageVersion,
    enabled = enabled,
    allowedToolCount = toolCount
)

class AgentSkillsCoordinatorTest {

    private class FakeAdminPort : AgentSkillAdminPort {
        var listResult: AgentSkillAdminListResult = AgentSkillAdminListResult.Loaded(emptyList())
        var importZipResult: AgentSkillAdminOutcome = AgentSkillAdminOutcome.Rejected(
            "SKILL_INVALID",
            "Skill 包无效。"
        )
        var importTreeResult: AgentSkillAdminOutcome = AgentSkillAdminOutcome.Rejected(
            "SKILL_INVALID",
            "Skill 目录无效。"
        )
        var toggleResult: AgentSkillAdminOutcome? = null
        var uninstallResult: AgentSkillAdminOutcome = AgentSkillAdminOutcome.Uninstalled("a", "A")
        var importGate: CompletableDeferred<Unit>? = null
        var throwOnList = false
        var throwOnImport = false

        val zipCalls = mutableListOf<AgentDocumentRef>()
        val treeCalls = mutableListOf<AgentDocumentRef>()
        val toggleCalls = mutableListOf<Pair<String, Boolean>>()
        val uninstallCalls = mutableListOf<String>()
        var listCalls = 0

        override suspend fun list(): AgentSkillAdminListResult {
            listCalls++
            if (throwOnList) error("boom")
            return listResult
        }

        override suspend fun importZip(source: AgentDocumentRef): AgentSkillAdminOutcome {
            zipCalls += source
            importGate?.await()
            if (throwOnImport) error("boom")
            return importZipResult
        }

        override suspend fun importTree(source: AgentDocumentRef): AgentSkillAdminOutcome {
            treeCalls += source
            if (throwOnImport) error("boom")
            return importTreeResult
        }

        override suspend fun setEnabled(
            skillId: String,
            enabled: Boolean
        ): AgentSkillAdminOutcome {
            toggleCalls += skillId to enabled
            return toggleResult ?: if (enabled) {
                AgentSkillAdminOutcome.Enabled(skillItem(skillId, enabled = true))
            } else {
                AgentSkillAdminOutcome.Disabled(skillItem(skillId, enabled = false))
            }
        }

        override suspend fun uninstall(skillId: String): AgentSkillAdminOutcome {
            uninstallCalls += skillId
            return uninstallResult
        }
    }

    private class FakeLauncher : AgentDocumentLauncher {
        var openCount = 0
        var treeCount = 0
        private var openCallback: ((AgentDocumentRef?) -> Unit)? = null
        private var treeCallback: ((AgentDocumentRef?) -> Unit)? = null

        override fun createDocument(
            suggestedFileName: String,
            onResult: (AgentDocumentRef?) -> Unit
        ) = Unit

        override fun openDocument(onResult: (AgentDocumentRef?) -> Unit) {
            openCount++
            openCallback = onResult
        }

        override fun openTree(onResult: (AgentDocumentRef?) -> Unit) {
            treeCount++
            treeCallback = onResult
        }

        fun pickZip(source: AgentDocumentRef?) {
            openCallback?.invoke(source)
        }

        fun pickTree(source: AgentDocumentRef?) {
            treeCallback?.invoke(source)
        }
    }

    private class Harness(
        val port: FakeAdminPort = FakeAdminPort(),
        val launcher: FakeLauncher = FakeLauncher()
    ) {
        val coordinator = AgentSkillsCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            port = port,
            documents = launcher
        )
    }

    private val reference = AgentDocumentRef("content://skill/one")

    @Test
    fun `open loads the list once and projects enabled count`() {
        val harness = Harness()
        harness.port.listResult = AgentSkillAdminListResult.Loaded(
            listOf(skillItem("b", name = "Beta", enabled = true), skillItem("a", name = "Alpha"))
        )

        harness.coordinator.open()

        val state = harness.coordinator.state.value
        assertTrue(state.visible)
        assertFalse(state.loading)
        assertEquals(listOf("Alpha", "Beta"), state.items.map { it.name })
        assertEquals(1, state.enabledCount)
        assertEquals("1 个工具", state.items.first().allowedToolsLabel)
    }

    @Test
    fun `list and import rejections surface only the safe message`() {
        val listFailing = Harness()
        listFailing.port.listResult = AgentSkillAdminListResult.Rejected(
            "LIST_DENIED",
            "无法读取 Skills。"
        )
        listFailing.coordinator.open()
        assertEquals("LIST_DENIED", listFailing.coordinator.state.value.error?.code)
        assertEquals("无法读取 Skills。", listFailing.coordinator.state.value.error?.message)

        val importFailing = Harness()
        importFailing.port.listResult = AgentSkillAdminListResult.Loaded(emptyList())
        importFailing.coordinator.open()
        importFailing.coordinator.requestImportZip()
        importFailing.launcher.pickZip(reference)
        assertEquals("SKILL_INVALID", importFailing.coordinator.state.value.error?.code)
        assertEquals("Skill 包无效。", importFailing.coordinator.state.value.error?.message)
    }

    @Test
    fun `zip import adds a row and tree update replaces it`() {
        val harness = Harness()
        harness.port.listResult = AgentSkillAdminListResult.Loaded(emptyList())
        harness.coordinator.open()

        harness.port.importZipResult = AgentSkillAdminOutcome.Imported(
            skillItem("alpha", name = "Alpha", packageVersion = "2.0.0", toolCount = 2)
        )
        harness.coordinator.requestImportZip()
        harness.launcher.pickZip(reference)
        assertEquals(listOf("alpha"), harness.coordinator.state.value.items.map { it.skillId })
        assertEquals("2.0.0", harness.coordinator.state.value.items.single().versionLabel)
        assertTrue(harness.coordinator.state.value.notice.orEmpty().contains("已导入"))

        harness.port.importTreeResult = AgentSkillAdminOutcome.Updated(
            skillItem("alpha", name = "Alpha", version = 2L, packageVersion = "2.1.0", toolCount = 3)
        )
        harness.coordinator.requestImportTree()
        harness.launcher.pickTree(AgentDocumentRef("content://skill/tree"))
        assertEquals(1, harness.coordinator.state.value.items.size)
        assertEquals("2.1.0", harness.coordinator.state.value.items.single().versionLabel)
        assertTrue(harness.coordinator.state.value.notice.orEmpty().contains("已更新"))
    }

    @Test
    fun `cancelled picker is a notice and does not call the port`() {
        val harness = Harness()
        harness.port.listResult = AgentSkillAdminListResult.Loaded(emptyList())
        harness.coordinator.open()

        harness.coordinator.requestImportZip()
        harness.launcher.pickZip(null)

        assertEquals("已取消导入", harness.coordinator.state.value.notice)
        assertTrue(harness.port.zipCalls.isEmpty())
    }

    @Test
    fun `only one operation may be in flight`() {
        val harness = Harness()
        harness.port.listResult = AgentSkillAdminListResult.Loaded(emptyList())
        harness.coordinator.open()
        harness.port.importGate = CompletableDeferred()
        harness.port.importZipResult = AgentSkillAdminOutcome.Imported(skillItem("alpha"))

        harness.coordinator.requestImportZip()
        harness.launcher.pickZip(reference)

        assertEquals(AgentSkillOperationKind.IMPORT_ZIP, harness.coordinator.state.value.busy)
        assertFalse(harness.coordinator.state.value.canImport)

        harness.coordinator.requestImportTree()
        harness.coordinator.requestUninstall("alpha")
        assertEquals(0, harness.launcher.treeCount)
        assertNull(harness.coordinator.state.value.uninstallConfirmation)

        harness.port.importGate?.complete(Unit)
        assertNull(harness.coordinator.state.value.busy)
        assertEquals(listOf("alpha"), harness.coordinator.state.value.items.map { it.skillId })
    }

    @Test
    fun `enabling a fifth skill is rejected locally with the max copy`() {
        val harness = Harness()
        harness.port.listResult = AgentSkillAdminListResult.Loaded(
            listOf(
                skillItem("a", enabled = true),
                skillItem("b", enabled = true),
                skillItem("c", enabled = true),
                skillItem("d", enabled = true),
                skillItem("e", enabled = false)
            )
        )
        harness.coordinator.open()

        harness.coordinator.requestSetEnabled("e", true)

        assertTrue(harness.port.toggleCalls.isEmpty())
        assertTrue(harness.coordinator.state.value.limitReached)
        assertTrue(
            harness.coordinator.state.value.notice.orEmpty().contains("最多同时启用 4 个 Skill")
        )
    }

    @Test
    fun `toggle calls the port and updates the row`() {
        val harness = Harness()
        harness.port.listResult = AgentSkillAdminListResult.Loaded(
            listOf(skillItem("alpha", enabled = true))
        )
        harness.coordinator.open()
        harness.port.toggleResult = AgentSkillAdminOutcome.Disabled(
            skillItem("alpha", enabled = false)
        )

        harness.coordinator.requestSetEnabled("alpha", false)

        assertEquals(listOf("alpha" to false), harness.port.toggleCalls)
        assertFalse(harness.coordinator.state.value.items.single().enabled)
        assertTrue(harness.coordinator.state.value.notice.orEmpty().contains("待审批已失效"))
    }

    @Test
    fun `uninstall requires explicit confirmation and keeps snapshot copy`() {
        val harness = Harness()
        harness.port.listResult = AgentSkillAdminListResult.Loaded(
            listOf(skillItem("alpha", name = "Alpha", enabled = true))
        )
        harness.coordinator.open()
        harness.port.uninstallResult = AgentSkillAdminOutcome.Uninstalled("alpha", "Alpha")

        harness.coordinator.requestUninstall("alpha")
        assertEquals("Alpha", harness.coordinator.state.value.uninstallConfirmation?.name)
        assertTrue(harness.port.uninstallCalls.isEmpty())

        harness.coordinator.cancelUninstall()
        assertNull(harness.coordinator.state.value.uninstallConfirmation)
        assertTrue(harness.port.uninstallCalls.isEmpty())

        harness.coordinator.requestUninstall("alpha")
        harness.coordinator.confirmUninstall()

        assertEquals(listOf("alpha"), harness.port.uninstallCalls)
        assertTrue(harness.coordinator.state.value.items.isEmpty())
        assertTrue(
            harness.coordinator.state.value.notice.orEmpty().contains("运行中的任务会保留其 Skill 快照")
        )
    }

    @Test
    fun `unexpected throwable is mapped to a generic safe error`() {
        val harness = Harness()
        harness.port.listResult = AgentSkillAdminListResult.Loaded(emptyList())
        harness.coordinator.open()
        harness.port.throwOnImport = true
        harness.port.importZipResult = AgentSkillAdminOutcome.Imported(skillItem("alpha"))

        harness.coordinator.requestImportZip()
        harness.launcher.pickZip(reference)

        assertEquals("SKILL_ADMIN_FAILED", harness.coordinator.state.value.error?.code)
        assertEquals(
            "Skill 未能导入，请检查包内容后重试。",
            harness.coordinator.state.value.error?.message
        )
    }

    @Test
    fun `dismiss clears error but not items`() {
        val harness = Harness()
        harness.port.listResult = AgentSkillAdminListResult.Loaded(listOf(skillItem("alpha")))
        harness.coordinator.open()
        harness.port.importZipResult = AgentSkillAdminOutcome.Rejected("BAD", "坏包")
        harness.coordinator.requestImportZip()
        harness.launcher.pickZip(reference)
        assertEquals("坏包", harness.coordinator.state.value.error?.message)

        harness.coordinator.dismissStatus()

        assertNull(harness.coordinator.state.value.error)
        assertEquals(1, harness.coordinator.state.value.items.size)
    }

    @Test
    fun `open is idempotent while visible and refreshes after close`() {
        val harness = Harness()
        harness.port.listResult = AgentSkillAdminListResult.Loaded(emptyList())
        harness.coordinator.open()
        harness.coordinator.open()
        assertEquals(1, harness.port.listCalls)

        harness.coordinator.close()
        assertFalse(harness.coordinator.state.value.visible)
        harness.coordinator.open()
        assertEquals(2, harness.port.listCalls)
    }
}
