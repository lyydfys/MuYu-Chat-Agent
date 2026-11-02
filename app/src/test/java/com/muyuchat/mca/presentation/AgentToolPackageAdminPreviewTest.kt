package com.muyuchat.mca.presentation

import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.feature.agent.workspace.AgentDocumentRef
import com.muyuchat.feature.agent.workspace.AgentToolPackageListResult
import com.muyuchat.feature.agent.workspace.AgentToolPackagePreviewItem
import com.muyuchat.feature.agent.workspace.AgentToolPackagePreviewResult
import com.muyuchat.feature.agent.workspace.AgentToolPackagePreviewToolItem
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageFileStore
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageFixtures
import com.muyuchat.mca.agent.tools.AgentToolCatalogAssembler
import com.muyuchat.mca.agent.tools.AgentToolCatalogAssemblyResult
import com.muyuchat.mca.agent.tools.AgentToolSource
import com.muyuchat.mca.agent.tools.FakeAgentWorkspaceFilePort
import com.muyuchat.mca.agent.tools.builtInAgentToolCatalog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Behavior tests for the app-side admin adapter.
 *
 * They exercise the real private preview store through the adapter: list, ZIP preview, typed
 * rejection, no run-catalog exposure and no credential/path leak in any returned record. Android
 * SAF plumbing is replaced by an explicit archive copier in the internal constructor, so these are
 * plain JVM tests (no Robolectric, no device).
 */
class AgentToolPackageAdminPreviewTest {

    @get:Rule
    val temp = TemporaryFolder()

    private class Harness(
        temp: TemporaryFolder,
        archive: ByteArray = AgentToolPackageFixtures.validZip(),
        copyFailure: Throwable? = null
    ) {
        val root: File = temp.newFolder("toolpackages")
        val cache: File = temp.newFolder("cache")
        val store = AgentToolPackageFileStore(
            root,
            AgentToolPackageFixtures.hostCatalog()
        )
        val service = AgentToolPackageAdminService(
            store = store,
            ioDispatcher = Dispatchers.Unconfined,
            cacheDir = cache,
            copyArchive = { _, destination ->
                copyFailure?.let { throw it }
                destination.writeBytes(archive)
            }
        )
    }

    @Test
    fun `admin service previews a picked zip and lists the installed preview`() = runBlocking {
        val harness = Harness(temp)

        val before = harness.service.list()
        assertTrue(before is AgentToolPackageListResult.Loaded)
        assertTrue((before as AgentToolPackageListResult.Loaded).packages.isEmpty())

        val result = harness.service.preview(AgentDocumentRef("content://docs/research.zip"))
        assertTrue("expected a preview, got $result", result is AgentToolPackagePreviewResult.Previewed)
        val preview = (result as AgentToolPackagePreviewResult.Previewed).preview
        assertEquals("research-basics", preview.packageId)
        assertEquals("1.0.0", preview.version)
        assertEquals(1, preview.toolCount)
        assertTrue(preview.previewOnly)
        assertEquals("workspace.read_text", preview.tools.single().hostOperation)
        assertEquals("只读", preview.tools.single().sideEffectLabel)
        assertEquals(1, preview.resourceCount)

        val after = harness.service.list()
        assertTrue(after is AgentToolPackageListResult.Loaded)
        val item = (after as AgentToolPackageListResult.Loaded).packages.single()
        assertEquals(preview.packageId, item.packageId)
        assertEquals(preview.displayName, item.displayName)
        assertEquals(1, item.toolCount)
        assertEquals(AgentToolPackageAdminService.READ_ONLY_SIDE_EFFECT_SUMMARY, item.sideEffectSummary)
        assertEquals(1, harness.store.summaries().size)
    }

    @Test
    fun `an archive without a root manifest is rejected and installs nothing`() = runBlocking {
        val harness = Harness(
            temp,
            archive = AgentToolPackageFixtures.zip(emptyList())
        )

        val result = harness.service.preview(AgentDocumentRef("content://docs/empty.zip"))
        assertTrue("expected a rejection, got $result", result is AgentToolPackagePreviewResult.Rejected)
        assertEquals(
            "MISSING_MANIFEST",
            (result as AgentToolPackagePreviewResult.Rejected).code
        )
        assertEquals("ZIP 根目录缺少 mca-tool-package.json。", result.safeMessage)
        assertTrue(harness.store.summaries().isEmpty())
        val listed = harness.service.list()
        assertTrue(listed is AgentToolPackageListResult.Loaded)
        assertTrue((listed as AgentToolPackageListResult.Loaded).packages.isEmpty())
    }

    @Test
    fun `an executable manifest field is rejected before any snapshot is committed`() = runBlocking {
        val manifest = AgentToolPackageFixtures.manifest(
            extraRoot = ",\"main\":\"index.js\""
        )
        val harness = Harness(
            temp,
            archive = AgentToolPackageFixtures.validZip(manifestJson = manifest)
        )

        val result = harness.service.preview(AgentDocumentRef("content://docs/script.zip"))
        assertTrue(result is AgentToolPackagePreviewResult.Rejected)
        assertEquals(
            "MANIFEST_EXECUTABLE_FIELD",
            (result as AgentToolPackagePreviewResult.Rejected).code
        )
        assertTrue(harness.store.summaries().isEmpty())
        assertTrue(File(harness.root, "managed").listFiles().isNullOrEmpty())
    }

    @Test
    fun `a previewed package never enters the production run catalog`() = runBlocking {
        val harness = Harness(temp)
        val result = harness.service.preview(AgentDocumentRef("content://docs/research.zip"))
        assertTrue(result is AgentToolPackagePreviewResult.Previewed)
        val preview = (result as AgentToolPackagePreviewResult.Previewed).preview

        val assembled = AgentToolCatalogAssembler().assemble(
            builtInCatalog = builtInAgentToolCatalog(FakeAgentWorkspaceFilePort()),
            enabledSkills = emptyList(),
            mcpTools = emptyList()
        )
        assertTrue(assembled is AgentToolCatalogAssemblyResult.Assembled)
        val toolKeys = (assembled as AgentToolCatalogAssemblyResult.Assembled)
            .catalog.snapshot().map { it.toolKey }
        assertTrue(toolKeys.none { it.startsWith("pkg.") })
        assertEquals(4, toolKeys.size)
        assertFalse(toolKeys.contains("pkg.${preview.packageId}.read_brief"))

        // No package source exists in the catalog assembly enum, and no package snapshot is a catalog.
        assertEquals(
            listOf(AgentToolSource.BUILT_IN, AgentToolSource.MCP),
            AgentToolSource.values().toList()
        )
        val assembleMethod = AgentToolCatalogAssembler::class.java.methods
            .single { it.name == "assemble" }
        assertEquals(
            "the run-catalog assembler must not accept a package tool source",
            3,
            assembleMethod.parameterCount
        )
        assertFalse(AgentToolCatalog::class.java.isAssignableFrom(AgentToolPackageFileStore::class.java))
        listOf(AgentToolPackagePreviewItem::class.java, AgentToolPackagePreviewToolItem::class.java)
            .forEach { type ->
                assertTrue(type.methods.none { it.returnType.name.contains("AgentToolExecutor") })
                assertTrue(type.methods.none { it.name.startsWith("execute") })
                assertTrue(type.methods.none { it.name == "getEnabled" || it.name == "isEnabled" })
            }
    }

    @Test
    fun `production factory roots one preview store under agent toolpackages`() {
        val source = File(
            projectRoot(),
            "app/src/main/java/com/muyuchat/mca/presentation/AgentToolPackageAdminService.kt"
        ).readText(Charsets.UTF_8)

        assertTrue(
            source.contains(
                "AgentDataAssembly.AGENT_ROOT_DIRECTORY + File.separator + \"toolpackages\""
            )
        )
        assertTrue(source.contains("BuiltInToolPackageHostCatalog.readOnly"))
        assertTrue(source.contains("AgentToolPackageFileStore(root, hostCatalog)"))
        assertFalse(source.contains("AgentToolCatalogAssembler"))
    }

    @Test
    fun `admin results never leak the source uri cache path or store path`() = runBlocking {
        val harness = Harness(temp)
        val secretUri = "content://provider/package.zip?token=SECRET-TOKEN"

        val result = harness.service.preview(AgentDocumentRef(secretUri))
        assertTrue(result is AgentToolPackagePreviewResult.Previewed)
        val preview = (result as AgentToolPackagePreviewResult.Previewed).preview
        val rendered = buildString {
            append(preview.packageId).append('\n')
            append(preview.displayName).append('\n')
            append(preview.version).append('\n')
            append(preview.sideEffectSummary).append('\n')
            append(preview.sourceLabel).append('\n')
            preview.tools.forEach { tool ->
                append(tool.toolName).append('\n')
                append(tool.displayName).append('\n')
                append(tool.hostOperation).append('\n')
                append(tool.sideEffectLabel).append('\n')
                append(tool.dataClassLabel).append('\n')
                append(tool.exposedParameters.joinToString(",")).append('\n')
            }
        }
        assertFalse(rendered.contains("SECRET-TOKEN"))
        assertFalse(rendered.contains("content://"))
        assertFalse(rendered.contains(harness.root.absolutePath))
        assertFalse(rendered.contains(harness.cache.absolutePath))
        assertEquals(AgentToolPackageAdminService.PREVIEW_SOURCE_LABEL, preview.sourceLabel)

        val item = (harness.service.list() as AgentToolPackageListResult.Loaded).packages.single()
        assertFalse(item.sourceLabel.orEmpty().contains("content://"))
        assertEquals(AgentToolPackageAdminService.READ_ONLY_SIDE_EFFECT_SUMMARY, item.sideEffectSummary)
    }

    @Test
    fun `a provider failure with a private path is mapped to safe copy`() = runBlocking {
        val harness = Harness(
            temp,
            copyFailure = IllegalStateException(
                "open failed for C:\\private\\vault?token=SECRET-TOKEN"
            )
        )

        val result = harness.service.preview(AgentDocumentRef("content://docs/private.zip"))
        assertTrue(result is AgentToolPackagePreviewResult.Rejected)
        val rejected = result as AgentToolPackagePreviewResult.Rejected
        assertEquals(AgentToolPackageAdminService.FAILURE_CODE, rejected.code)
        assertFalse(rejected.safeMessage.contains("SECRET-TOKEN"))
        assertFalse(rejected.safeMessage.contains("C:\\private"))
        assertFalse(rejected.safeMessage.contains("content://"))
        assertTrue(harness.store.summaries().isEmpty())
        assertNotNull(rejected.safeMessage.takeIf { it.isNotBlank() })
    }

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("project root not found from ${System.getProperty("user.dir")}")
    }
}
