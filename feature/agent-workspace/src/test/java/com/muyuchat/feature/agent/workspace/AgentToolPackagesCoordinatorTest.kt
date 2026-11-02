package com.muyuchat.feature.agent.workspace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun packageItem(
    id: String,
    name: String = id,
    version: String = "1.0.0",
    tools: Int = 1,
    sideEffects: String = "只读：workspace.read_text",
    source: String? = "本机导入"
) = AgentToolPackageItem(
    packageId = id,
    displayName = name,
    version = version,
    toolCount = tools,
    sideEffectSummary = sideEffects,
    sourceLabel = source
)

private fun previewItem(
    id: String = "pkg-a",
    name: String = id,
    tools: Int = 1
) = AgentToolPackagePreviewItem(
    packageId = id,
    displayName = name,
    version = "1.0.0",
    toolCount = tools,
    sideEffectSummary = "只读",
    sourceLabel = "本机 ZIP 预览",
    tools = (1..tools).map { index ->
        AgentToolPackagePreviewToolItem(
            toolName = "read_$index",
            displayName = "Read $index",
            hostOperation = "workspace.read_text",
            sideEffectLabel = "只读",
            dataClassLabel = "私有",
            exposedParameters = listOf("offset", "limit"),
            timeoutMillis = 10_000L
        )
    },
    resourceCount = 1,
    totalExpandedBytes = 128L
)

class AgentToolPackagesCoordinatorTest {

    private class FakeDocuments : AgentDocumentLauncher {
        var next: AgentDocumentRef? = AgentDocumentRef("content://docs/tool.zip")
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
        var result: AgentToolPackageListResult = AgentToolPackageListResult.Loaded(emptyList())
        var previewResult: AgentToolPackagePreviewResult =
            AgentToolPackagePreviewResult.Previewed(previewItem())
        var throwOnList = false
        var throwOnPreview = false
        var listCalls = 0
        var previewCalls = 0

        override suspend fun list(): AgentToolPackageListResult {
            listCalls++
            if (throwOnList) error("boom")
            return result
        }

        override suspend fun preview(source: AgentDocumentRef): AgentToolPackagePreviewResult {
            previewCalls++
            if (throwOnPreview) error("boom")
            return previewResult
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
    fun `open loads the real package list and totals tool counts`() {
        val harness = Harness()
        harness.port.result = AgentToolPackageListResult.Loaded(
            listOf(
                packageItem("pkg-b", name = "Beta", tools = 2),
                packageItem("pkg-a", name = "Alpha", tools = 1)
            )
        )

        harness.coordinator.open()

        val state = harness.coordinator.state.value
        assertTrue(state.visible)
        assertFalse(state.loading)
        assertEquals(listOf("Alpha", "Beta"), state.items.map { it.displayName })
        assertEquals(3, state.totalToolCount)
        assertEquals("2 个工具", state.items.single { it.packageId == "pkg-b" }.toolCountLabel)
        assertFalse(state.empty)
    }

    @Test
    fun `loaded empty result is an explicit empty state and no fabricated row`() {
        val harness = Harness()
        harness.port.result = AgentToolPackageListResult.Loaded(emptyList())

        harness.coordinator.open()

        assertTrue(harness.coordinator.state.value.empty)
        assertTrue(harness.coordinator.state.value.items.isEmpty())
        assertEquals(0, harness.coordinator.state.value.totalToolCount)
    }

    @Test
    fun `preview opens the picker and shows the read-only preview`() {
        val harness = Harness()
        harness.port.result = AgentToolPackageListResult.Loaded(emptyList())
        harness.port.previewResult = AgentToolPackagePreviewResult.Previewed(
            previewItem(id = "pkg-a", name = "Alpha", tools = 2)
        )
        harness.coordinator.open()

        harness.coordinator.requestPreview()

        assertEquals(1, harness.documents.openCalls)
        assertEquals(1, harness.port.previewCalls)
        val state = harness.coordinator.state.value
        assertFalse(state.previewing)
        assertEquals("pkg-a", state.preview?.packageId)
        assertEquals(2, state.preview?.tools?.size)
        assertEquals("workspace.read_text", state.preview?.tools?.first()?.hostOperation)
        assertTrue(state.notice.orEmpty().contains("不会启用"))
        assertNull(state.error)
    }

    @Test
    fun `cancelled picker never reaches the preview port`() {
        val harness = Harness()
        harness.port.result = AgentToolPackageListResult.Loaded(emptyList())
        harness.documents.next = null
        harness.coordinator.open()

        harness.coordinator.requestPreview()

        assertEquals(1, harness.documents.openCalls)
        assertEquals(0, harness.port.previewCalls)
        assertNull(harness.coordinator.state.value.preview)
        assertNull(harness.coordinator.state.value.error)
    }

    @Test
    fun `a rejected preview surfaces only the safe message and no preview`() {
        val harness = Harness()
        harness.port.result = AgentToolPackageListResult.Loaded(emptyList())
        harness.port.previewResult = AgentToolPackagePreviewResult.Rejected(
            code = "MISSING_MANIFEST",
            safeMessage = "ZIP 根目录缺少清单。"
        )
        harness.coordinator.open()

        harness.coordinator.requestPreview()

        val state = harness.coordinator.state.value
        assertFalse(state.previewing)
        assertNull(state.preview)
        assertEquals("MISSING_MANIFEST", state.error?.code)
        assertEquals("ZIP 根目录缺少清单。", state.error?.message)
    }

    @Test
    fun `a thrown preview failure becomes a generic safe error`() {
        val harness = Harness()
        harness.port.result = AgentToolPackageListResult.Loaded(emptyList())
        harness.port.throwOnPreview = true
        harness.coordinator.open()

        harness.coordinator.requestPreview()

        assertEquals(
            "TOOL_PACKAGE_ADMIN_FAILED",
            harness.coordinator.state.value.error?.code
        )
        assertEquals(
            "工具包预览失败，请重试。",
            harness.coordinator.state.value.error?.message
        )
    }

    @Test
    fun `preview is available but catalog exposure and import stay closed`() {
        val harness = Harness()
        harness.coordinator.open()

        assertTrue(AgentToolPackagePolicy.APPROVAL_CLOSURE_COMPLETE)
        assertFalse(AgentToolPackagePolicy.PACKAGE_CATALOG_EXPOSURE_ENABLED)
        assertTrue(harness.coordinator.state.value.previewEnabled)
        assertFalse(harness.coordinator.state.value.importEnabled)
        assertFalse(harness.coordinator.state.value.catalogExposureEnabled)
        assertEquals(
            AgentToolPackagePolicy.PACKAGE_CATALOG_EXPOSURE_REQUIRED_COPY,
            harness.coordinator.state.value.gateMessage
        )

        harness.coordinator.requestImport()
        assertEquals(
            AgentToolPackagePolicy.PACKAGE_CATALOG_EXPOSURE_REQUIRED_COPY,
            harness.coordinator.state.value.notice
        )
        // No enable/import method exists on the port; the gate notice never calls it.
        assertEquals(0, harness.port.previewCalls)
        assertEquals(1, harness.port.listCalls)
    }

    @Test
    fun `rejection surfaces only the safe message and keeps the empty list`() {
        val harness = Harness()
        harness.port.result = AgentToolPackageListResult.Rejected(
            code = "PACKAGE_LIST_DENIED",
            safeMessage = "无法读取工具包。"
        )

        harness.coordinator.open()

        assertEquals("PACKAGE_LIST_DENIED", harness.coordinator.state.value.error?.code)
        assertEquals("无法读取工具包。", harness.coordinator.state.value.error?.message)
        assertFalse(harness.coordinator.state.value.loading)
        assertTrue(harness.coordinator.state.value.items.isEmpty())
    }

    @Test
    fun `a thrown list failure becomes a generic safe error`() {
        val harness = Harness()
        harness.port.throwOnList = true

        harness.coordinator.open()

        assertEquals(
            "TOOL_PACKAGE_ADMIN_FAILED",
            harness.coordinator.state.value.error?.code
        )
        assertEquals(
            "工具包列表暂时无法载入，请稍后重试。",
            harness.coordinator.state.value.error?.message
        )
    }

    @Test
    fun `the unavailable port is the safe default`() {
        val coordinator = AgentToolPackagesCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            port = AgentToolPackageAdminPort.Unavailable,
            documents = FakeDocuments()
        )

        coordinator.open()
        coordinator.requestPreview()

        assertEquals(
            "TOOL_PACKAGE_ADMIN_UNAVAILABLE",
            coordinator.state.value.error?.code
        )
        assertTrue(coordinator.state.value.items.isEmpty())
        assertNull(coordinator.state.value.preview)
    }

    @Test
    fun `open is idempotent while visible and refresh reloads`() {
        val harness = Harness()
        harness.port.result = AgentToolPackageListResult.Loaded(emptyList())
        harness.coordinator.open()
        harness.coordinator.open()
        assertEquals(1, harness.port.listCalls)

        harness.coordinator.requestImport()
        assertTrue(harness.coordinator.state.value.notice != null)
        harness.coordinator.dismissStatus()
        assertNull(harness.coordinator.state.value.notice)

        harness.coordinator.refresh()
        assertEquals(2, harness.port.listCalls)
        assertFalse(harness.coordinator.state.value.loading)
    }

    @Test
    fun `preview projection bounds names and labels tool metadata`() {
        val projected = projectToolPackagePreview(
            previewItem(id = "pkg-a", name = "A".repeat(300), tools = 1)
        )

        assertTrue(projected.displayName.length <= AgentToolPackageLimits.MAX_DISPLAY_NAME_CHARS)
        assertEquals("1 个工具", projected.toolCountLabel)
        assertEquals("1 个资源", projected.resourceLabel)
        assertEquals("128 B", projected.sizeLabel)
        assertEquals("10 秒", projected.tools.single().timeoutLabel)
        assertTrue(projected.previewOnly)

        val state = projectToolPackagesState(
            AgentToolPackagesProjectionInput(
                visible = true,
                preview = projected
            )
        )
        assertFalse(state.importEnabled)
        assertEquals(projected, state.preview)
    }

    @Test
    fun `the state has no enable toggle member at all`() {
        try {
            val method = AgentToolPackagesUiState::class.java.getDeclaredMethod("getEnabled")
            fail("tool-package UI state must not expose an enable toggle: $method")
        } catch (_: NoSuchMethodException) {
            // Expected: activation is unrepresentable on the page state.
        }
    }
}
