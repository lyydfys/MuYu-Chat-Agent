package com.muyuchat.mca.presentation

import com.muyuchat.feature.agent.workspace.AgentToolPackageListResult
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageFileStore
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageFixtures
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageIngestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Port-mapping tests for the tool-package settings adapter's list path.
 *
 * They prove the adapter projects the real private preview index (id/name/version/tool count) and
 * does not invent a source label or a per-package effect. ZIP preview/rejection/leak behavior is
 * covered by [AgentToolPackageAdminPreviewTest]; no test here claims a package is active.
 */
class AgentToolPackageAdminServiceTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `maps a stored preview summary without inventing source or activation`() = runBlocking {
        val store = AgentToolPackageFileStore(
            temp.newFolder("tool-packages"),
            AgentToolPackageFixtures.hostCatalog()
        )
        val archive = AgentToolPackageFixtures.write(
            temp.newFolder("archive"),
            "research.mca",
            AgentToolPackageFixtures.validZip()
        )
        val ingested = store.preview("content://import/research", archive)
        assertTrue("expected a stored preview, got $ingested", ingested is AgentToolPackageIngestResult.Previewed)

        val result = AgentToolPackageAdminService(store, Dispatchers.Unconfined).list()

        assertTrue("expected a loaded list, got $result", result is AgentToolPackageListResult.Loaded)
        val item = (result as AgentToolPackageListResult.Loaded).packages.single()
        assertEquals("research-basics", item.packageId)
        assertEquals("Research basics", item.displayName)
        assertEquals("1.0.0", item.version)
        assertEquals(1, item.toolCount)
        assertEquals(
            AgentToolPackageAdminService.READ_ONLY_SIDE_EFFECT_SUMMARY,
            item.sideEffectSummary
        )
        assertNull("the index summary has no source label, so none may be invented", item.sourceLabel)
    }

    @Test
    fun `an empty preview store is an explicit empty list`() = runBlocking {
        val store = AgentToolPackageFileStore(
            temp.newFolder("empty-tool-packages"),
            AgentToolPackageFixtures.hostCatalog()
        )

        val result = AgentToolPackageAdminService(store, Dispatchers.Unconfined).list()

        assertTrue(result is AgentToolPackageListResult.Loaded)
        assertTrue((result as AgentToolPackageListResult.Loaded).packages.isEmpty())
    }

    @Test
    fun `the failure copy matches the coordinator fallback`() {
        assertEquals("TOOL_PACKAGE_ADMIN_FAILED", AgentToolPackageAdminService.FAILURE_CODE)
        assertEquals("工具包列表暂时无法载入，请稍后重试。", AgentToolPackageAdminService.FAILURE_MESSAGE)
        assertEquals("只读宿主操作", AgentToolPackageAdminService.READ_ONLY_SIDE_EFFECT_SUMMARY)
    }
}
