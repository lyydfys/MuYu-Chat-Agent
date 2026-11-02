package com.muyuchat.mca.presentation

import com.muyuchat.feature.agent.workspace.AgentDocumentRef
import com.muyuchat.feature.agent.workspace.AgentExecutablePackageActionResult
import com.muyuchat.feature.agent.workspace.AgentExecutablePackageListResult
import com.muyuchat.feature.agent.workspace.AgentToolPackagePreviewResult
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageFileStore
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageFixtures
import com.muyuchat.mca.agent.toolpack.RecordingHostHandler
import com.muyuchat.mca.agent.toolpack.RhinoToolPackageScriptEngine
import com.muyuchat.mca.agent.toolpack.ToolPackageArchive
import com.muyuchat.mca.agent.toolpack.ToolPackageHostApprovalPort
import com.muyuchat.mca.agent.toolpack.ToolPackageHostOperationResult
import com.muyuchat.mca.agent.toolpack.ToolPackageInstallStore
import com.muyuchat.mca.agent.toolpack.ToolPackageLifecycleService
import com.muyuchat.mca.agent.toolpack.ToolPackageScriptRuntime
import com.muyuchat.mca.agent.toolpack.ThirdPartyTranslationFixtures
import com.muyuchat.mca.agent.toolpack.ToolpackFixtures
import com.muyuchat.mca.agent.toolpack.thirdpartyManifestJson
import com.muyuchat.mca.agent.toolpack.thirdpartyPackageEntries
import com.muyuchat.mca.agent.toolpack.thirdpartyZip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The settings adapter must classify a picked archive before the v1 preview store sees it, so an
 * third-party or executable-v2 package gets the answer that names its own format instead of the store's
 * "missing mca-tool-package.json". Legacy inert v1 keeps its existing read path, so nothing that
 * worked before changes behaviour.
 */
class AgentToolPackageImportGateTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun service(archive: ByteArray): AgentToolPackageAdminService {
        val store = AgentToolPackageFileStore(
            temp.newFolder("tool-packages"),
            AgentToolPackageFixtures.hostCatalog()
        )
        val lifecycle = ToolPackageLifecycleService(
            store = ToolPackageInstallStore(temp.newFolder("install-store")),
            runtime = ToolPackageScriptRuntime(
                RhinoToolPackageScriptEngine(),
                RecordingHostHandler(ToolPackageHostOperationResult.Returned("""{"content":"hello"}"""))
            ),
            approval = ToolPackageHostApprovalPort.DENY_ALL
        )
        return AgentToolPackageAdminService(
            store = store,
            ioDispatcher = Dispatchers.Unconfined,
            cacheDir = temp.newFolder("cache"),
            copyArchive = { _, destination -> destination.writeBytes(archive) },
            executableLifecycle = lifecycle
        )
    }

    @Test
    fun `a third-party archive is answered with its own code instead of a missing manifest`() = runBlocking {
        val zip = thirdpartyZip(thirdpartyPackageEntries("manifest.json", thirdpartyManifestJson()))

        val result = service(zip).preview(AgentDocumentRef("content://import/thirdparty"))

        assertTrue("expected a typed rejection, got $result", result is AgentToolPackagePreviewResult.Rejected)
        val thirdparty = result as AgentToolPackagePreviewResult.Rejected
        assertEquals(
            AgentToolPackageAdminService.IMPORT_FORMAT_THIRD_PARTY_CODE,
            thirdparty.code
        )
        assertTrue(
            "the rejection must state the one supported context",
            thirdparty.details.any { it.startsWith("支持的执行上下文：受限 CommonJS main(input)") }
        )
        assertTrue(
            "the rejection must name what this runtime does not support",
            thirdparty.details.any { it.contains("未支持：UI、WASM、sandbox/provider") }
        )
    }

    @Test
    fun `an executable v2 archive points at the install action`() = runBlocking {
        val zip = AgentToolPackageFixtures.zip(
            listOf(
                ToolPackageArchive.MANIFEST_FILE_NAME to ToolpackFixtures.manifestJson().toByteArray(),
                "main.js" to "// main\n".toByteArray(),
                ToolpackFixtures.TOOL_ENTRY to "// tool\n".toByteArray()
            )
        )

        val result = service(zip).preview(AgentDocumentRef("content://import/executable"))

        assertTrue("expected a typed rejection, got $result", result is AgentToolPackagePreviewResult.Rejected)
        val executable = result as AgentToolPackagePreviewResult.Rejected
        assertEquals(
            AgentToolPackageAdminService.IMPORT_FORMAT_V2_CODE,
            executable.code
        )
        assertTrue(
            "the rejection must show the parsed format line",
            executable.details.any { it.startsWith("格式：可执行工具包 v") }
        )
        assertTrue(
            "the rejection must show what the manifest declares",
            executable.details.any { it.startsWith("声明 host API：") }
        )
    }

    @Test
    fun `a third-party archive installs through the translation entry and stays disabled`() = runBlocking {
        val zip = ThirdPartyTranslationFixtures.archive()
        val admin = service(zip)

        val install = admin.installExecutable(AgentDocumentRef("content://install/thirdparty"))

        assertTrue("expected an applied install, got $install", install is AgentExecutablePackageActionResult.Applied)
        val applied = install as AgentExecutablePackageActionResult.Applied
        assertTrue(
            "the translated id must keep the collision-proof prefix, got ${applied.packageId}",
            applied.packageId?.startsWith("thirdparty-") == true
        )
        val listed = admin.listExecutable()
        assertTrue("expected a listed package, got $listed", listed is AgentExecutablePackageListResult.Loaded)
        val item = (listed as AgentExecutablePackageListResult.Loaded)
            .packages
            .single { it.packageId == applied.packageId }
        assertTrue("an installed translation must stay disabled", !item.enabled)
    }

    @Test
    fun `a legacy v1 archive still reaches the preview store unchanged`() = runBlocking {
        val result = service(AgentToolPackageFixtures.validZip())
            .preview(AgentDocumentRef("content://import/legacy"))

        assertTrue("legacy v1 must keep its read path, got $result", result is AgentToolPackagePreviewResult.Previewed)
    }
}
