package com.muyuchat.mca.presentation

import com.muyuchat.feature.agent.workspace.AgentDocumentRef
import com.muyuchat.feature.agent.workspace.AgentExecutablePackageActionResult
import com.muyuchat.feature.agent.workspace.AgentExecutablePackageListResult
import com.muyuchat.feature.agent.workspace.AgentExecutablePackageSupport
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageFileStore
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageFixtures
import com.muyuchat.mca.agent.toolpack.RecordingHostHandler
import com.muyuchat.mca.agent.toolpack.RhinoToolPackageScriptEngine
import com.muyuchat.mca.agent.toolpack.ToolPackageInstallStore
import com.muyuchat.mca.agent.toolpack.ToolPackageLifecycleService
import com.muyuchat.mca.agent.toolpack.ToolPackageScriptRuntime
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * App adapter behavior for the executable (format v2) settings surface.
 *
 * The real lifecycle service, real content-addressed store and real Rhino engine are used with a
 * tiny in-test ZIP; the SAF copy is replaced by an explicit archive copier. No device, no SAF and no
 * process execution are involved.
 */
class AgentExecutableToolPackageAdapterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun manifest(version: String = "1.0.0"): String =
        """{"schemaVersion":2,"packageId":"exec-fixture","packageVersion":"$version",""" +
            """"hostApiVersion":1,"displayName":"Exec fixture","description":"R6 fixture",""" +
            """"entry":"main.js","hostApis":[],"tools":[{"name":"echo_pkg","displayName":"Echo",""" +
            """"description":"Echo input","entry":"tools/echo.js","inputSchema":""" +
            """"{\"additionalProperties\":false,\"properties\":{},\"type\":\"object\"}",""" +
            """"timeoutMillis":5000}]}"""

    private fun executableZip(version: String = "1.0.0"): ByteArray {
        val entries = mapOf(
            "mca-tool-package.json" to manifest(version).toByteArray(),
            "main.js" to "// registration\n".toByteArray(),
            "tools/echo.js" to "function main(input) { return { ok: true }; }".toByteArray()
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private class Harness(
        temp: TemporaryFolder,
        archive: ByteArray,
        copyFailure: Throwable? = null,
        withLifecycle: Boolean = true
    ) {
        val previewRoot: File = temp.newFolder("toolpackages")
        val cache: File = temp.newFolder("cache")
        val executableRoot: File = temp.newFolder("toolpackages-v2")
        val lifecycle: ToolPackageLifecycleService? = if (withLifecycle) {
            ToolPackageLifecycleService(
                store = ToolPackageInstallStore(executableRoot),
                runtime = ToolPackageScriptRuntime(
                    RhinoToolPackageScriptEngine(),
                    RecordingHostHandler()
                )
            )
        } else {
            null
        }
        val service = AgentToolPackageAdminService(
            store = AgentToolPackageFileStore(
                previewRoot,
                AgentToolPackageFixtures.hostCatalog()
            ),
            ioDispatcher = Dispatchers.Unconfined,
            cacheDir = cache,
            copyArchive = { _, destination ->
                copyFailure?.let { throw it }
                destination.writeBytes(archive)
            },
            executableLifecycle = lifecycle
        )
    }

    private fun harness(copyFailure: Throwable? = null, withLifecycle: Boolean = true): Harness =
        Harness(temp, executableZip(), copyFailure, withLifecycle)

    @Test
    fun `an unwired adapter answers not supported without touching the preview store`() = runBlocking {
        val service = harness(withLifecycle = false).service

        val list = service.listExecutable()
        assertTrue(list is AgentExecutablePackageListResult.Rejected)
        assertEquals(
            AgentExecutablePackageSupport.NOT_SUPPORTED_CODE,
            (list as AgentExecutablePackageListResult.Rejected).code
        )

        val action = service.enableExecutable("exec-fixture")
        assertTrue(action is AgentExecutablePackageActionResult.Rejected)
        assertEquals(
            AgentExecutablePackageSupport.NOT_SUPPORTED_CODE,
            (action as AgentExecutablePackageActionResult.Rejected).code
        )
        assertNull(service.executablePackageLifecycle())
    }

    @Test
    fun `install writes a verified but disabled package`() = runBlocking {
        val harness = harness()

        val installed = harness.service.installExecutable(AgentDocumentRef("content://docs/exec.zip"))

        assertTrue("$installed", installed is AgentExecutablePackageActionResult.Applied)
        val list = harness.service.listExecutable() as AgentExecutablePackageListResult.Loaded
        val item = list.packages.single()
        assertEquals("exec-fixture", item.packageId)
        assertEquals("1.0.0", item.version)
        assertEquals(1, item.toolCount)
        assertFalse(item.enabled)
        assertFalse(item.revoked)
        assertFalse(item.rollbackAvailable)
        assertTrue(item.contentVerified)
        assertNull(item.problemCode)
    }

    @Test
    fun `enable and disable round-trip through the real lifecycle service`() = runBlocking {
        val harness = harness()
        harness.service.installExecutable(AgentDocumentRef("content://docs/exec.zip"))

        val enabled = harness.service.enableExecutable("exec-fixture")
        assertTrue(enabled is AgentExecutablePackageActionResult.Applied)
        assertTrue(harness.service.listExecutable().first().enabled)

        val disabled = harness.service.disableExecutable("exec-fixture")
        assertTrue(disabled is AgentExecutablePackageActionResult.Applied)
        assertFalse(harness.service.listExecutable().first().enabled)
    }

    @Test
    fun `update keeps a rollback target and rollback restores the previous version`() = runBlocking {
        val harness = harness()
        harness.service.installExecutable(AgentDocumentRef("content://docs/exec.zip"))
        harness.service.enableExecutable("exec-fixture")
        val updated = harness.lifecycle!!.updateArchive(executableZip("2.0.0"))
        assertTrue(updated is com.muyuchat.mca.agent.toolpack.ToolPackageLifecycleOutcome.Applied)
        val afterUpdate = harness.service.listExecutable().first()
        assertEquals("2.0.0", afterUpdate.version)
        assertTrue(afterUpdate.enabled)
        assertTrue(afterUpdate.rollbackAvailable)

        val rolledBack = harness.service.rollbackExecutable("exec-fixture")

        assertTrue(rolledBack is AgentExecutablePackageActionResult.Applied)
        val afterRollback = harness.service.listExecutable().first()
        assertEquals("1.0.0", afterRollback.version)
        assertTrue(afterRollback.enabled)
    }

    @Test
    fun `revocation blocks enable and clearing it restores the capability`() = runBlocking {
        val harness = harness()
        harness.service.installExecutable(AgentDocumentRef("content://docs/exec.zip"))

        assertTrue(harness.service.revokeExecutable("exec-fixture") is AgentExecutablePackageActionResult.Applied)
        val revoked = harness.service.listExecutable().first()
        assertTrue(revoked.revoked)
        assertFalse(revoked.enabled)

        val blocked = harness.service.enableExecutable("exec-fixture")
        assertTrue(blocked is AgentExecutablePackageActionResult.Rejected)
        assertEquals("TOOLPACK_REVOKED", (blocked as AgentExecutablePackageActionResult.Rejected).code)

        assertTrue(
            harness.service.clearExecutableRevocation("exec-fixture")
                is AgentExecutablePackageActionResult.Applied
        )
        assertFalse(harness.service.listExecutable().first().revoked)
        assertTrue(harness.service.enableExecutable("exec-fixture") is AgentExecutablePackageActionResult.Applied)
    }

    @Test
    fun `uninstall removes the row and garbage collection reports what it purged`() = runBlocking {
        val harness = harness()
        harness.service.installExecutable(AgentDocumentRef("content://docs/exec.zip"))
        harness.service.enableExecutable("exec-fixture")

        val uninstalled = harness.service.uninstallExecutable("exec-fixture")
        assertTrue(uninstalled is AgentExecutablePackageActionResult.Applied)
        assertTrue(harness.service.listExecutable().packages().isEmpty())

        val gc = harness.service.collectExecutableGarbage()
        assertTrue(gc is AgentExecutablePackageActionResult.Applied)
        assertTrue((gc as AgentExecutablePackageActionResult.Applied).notice.contains("已清理"))
    }

    @Test
    fun `an unknown package id stays a typed lifecycle rejection`() = runBlocking {
        val harness = harness()

        val result = harness.service.enableExecutable("missing-package")

        assertTrue(result is AgentExecutablePackageActionResult.Rejected)
        assertEquals("TOOLPACK_NOT_INSTALLED", (result as AgentExecutablePackageActionResult.Rejected).code)
    }

    @Test
    fun `a copy failure is reported as an unavailable install`() = runBlocking {
        val harness = harness(copyFailure = IllegalStateException("provider gone"))

        val result = harness.service.installExecutable(AgentDocumentRef("content://docs/exec.zip"))

        assertTrue(result is AgentExecutablePackageActionResult.Rejected)
        assertEquals(
            AgentExecutablePackageSupport.INSTALL_UNAVAILABLE_CODE,
            (result as AgentExecutablePackageActionResult.Rejected).code
        )
    }

    @Test
    fun `a malformed archive is rejected by the lifecycle with a typed code`() = runBlocking {
        val broken = Harness(temp, "not a zip".toByteArray())
        val result = broken.service.installExecutable(AgentDocumentRef("content://docs/broken.zip"))

        assertTrue(result is AgentExecutablePackageActionResult.Rejected)
        assertEquals(
            "TOOLPACK_ARCHIVE_INVALID",
            (result as AgentExecutablePackageActionResult.Rejected).code
        )
        assertTrue(broken.service.listExecutable().packages().isEmpty())
    }

    private fun AgentExecutablePackageListResult.first() =
        (this as AgentExecutablePackageListResult.Loaded).packages.single()

    private fun AgentExecutablePackageListResult.packages(): List<com.muyuchat.feature.agent.workspace.AgentExecutablePackageItem> =
        (this as AgentExecutablePackageListResult.Loaded).packages
}
