package com.muyuchat.mca.agent.toolpack

import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** PKG-01 production seam: one entry point routes a package by its own format declaration. */
class ToolPackageImportPreviewTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val handler = RecordingHostHandler(
        ToolPackageHostOperationResult.Returned("""{"content":"hello"}""")
    )

    private fun service(root: File = temporary.newFolder("import-preview")): ToolPackageLifecycleService =
        ToolPackageLifecycleService(
            store = ToolPackageInstallStore(root),
            runtime = ToolPackageScriptRuntime(RhinoToolPackageScriptEngine(), handler),
            approval = ToolPackageHostApprovalPort.DENY_ALL
        )

    @Test
    fun theThreeLegalSamplesRouteToTheirOwnParsersWithoutInstallingAnything() = runBlocking {
        val service = service()

        val legacy = service.previewImport(AgentToolPackageFixtures.validZip())
        assertTrue("legacy v1 stays readable", legacy is ToolPackageImportPreview.LegacyInert)

        val executableZip = AgentToolPackageFixtures.zip(
            listOf(
                ToolPackageArchive.MANIFEST_FILE_NAME to ToolpackFixtures.manifestJson().toByteArray(),
                "main.js" to "// main\n".toByteArray(),
                ToolpackFixtures.TOOL_ENTRY to "// tool\n".toByteArray()
            )
        )
        val executable = service.previewImport(executableZip)
        assertTrue("v2 routes to the executable parser", executable is ToolPackageImportPreview.Executable)
        assertEquals(
            ToolpackFixtures.PACKAGE_ID,
            (executable as ToolPackageImportPreview.Executable).manifest.packageId
        )

        val thirdparty = service.previewImport(
            thirdpartyZip(thirdpartyPackageEntries("manifest.json", thirdpartyManifestJson()))
        )
        assertTrue("third-party routes to the translation service", thirdparty is ToolPackageImportPreview.ThirdParty)

        assertTrue("a preview must not install anything", service.list().isEmpty())
    }

    @Test
    fun anArchiveThatDeclaresTwoFormatsIsRejectedBeforeAnyParserRuns() {
        val both = AgentToolPackageFixtures.zip(
            listOf(
                ToolPackageArchive.MANIFEST_FILE_NAME to ToolpackFixtures.manifestJson().toByteArray(),
                "manifest.json" to thirdpartyManifestJson().toByteArray()
            )
        )
        val rejected = service().previewImport(both) as ToolPackageImportPreview.Rejected
        assertEquals(ToolPackageErrorCode.MANIFEST_FORMAT_AMBIGUOUS, rejected.code)
    }

    @Test
    fun aBrokenExecutableManifestIsARejectionNotAnException() {
        val broken = AgentToolPackageFixtures.zip(
            listOf(
                ToolPackageArchive.MANIFEST_FILE_NAME to "{not json".toByteArray(),
                "main.js" to "// main\n".toByteArray()
            )
        )
        val rejected = service().previewImport(broken) as ToolPackageImportPreview.Rejected
        assertEquals(ToolPackageErrorCode.MANIFEST_MALFORMED, rejected.code)
    }
}
