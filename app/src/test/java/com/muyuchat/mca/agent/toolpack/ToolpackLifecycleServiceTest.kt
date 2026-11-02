package com.muyuchat.mca.agent.toolpack

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Production lifecycle service (R4) over the real file store.
 *
 * Everything is JVM: real content-addressed files, the real Rhino engine for the execution case, a
 * recording host handler (no device, no SAF, no network). Android/UI wiring is NOT_RUN.
 */
class ToolpackLifecycleServiceTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val handler = RecordingHostHandler(
        ToolPackageHostOperationResult.Returned("""{"content":"hello"}""")
    )

    private fun service(root: File = temporary.newFolder("lifecycle")): ToolPackageLifecycleService =
        ToolPackageLifecycleService(
            store = ToolPackageInstallStore(root),
            runtime = ToolPackageScriptRuntime(RhinoToolPackageScriptEngine(), handler),
            approval = ToolPackageHostApprovalPort.DENY_ALL
        )

    private fun pureManifest(
        packageVersion: String = "1.0.0",
        packageId: String = ToolpackFixtures.PACKAGE_ID
    ): String = ToolpackFixtures.manifestJson(
        packageId = packageId,
        packageVersion = packageVersion,
        hostApis = emptyList(),
        tools = listOf(ToolpackFixtures.toolJson(inputSchema = ToolpackFixtures.PARAMETER_SCHEMA))
    )

    @Test
    fun `a state-change listener observes only applied transitions and never fails the operation`() =
        runBlocking {
            var notifications = 0
            val root = temporary.newFolder("listener")
            val service = ToolPackageLifecycleService(
                store = ToolPackageInstallStore(root),
                runtime = ToolPackageScriptRuntime(RhinoToolPackageScriptEngine(), handler),
                onStateChanged = {
                    notifications += 1
                    throw IllegalStateException("listener fault must be ignored")
                }
            )

            val installed = applied(service.install(pureManifest().toByteArray(), pureScripts()))
            assertEquals(1, notifications)
            rejected(service.enable("missing-package"), "TOOLPACK_NOT_INSTALLED")
            assertEquals("a rejection must not notify", 1, notifications)
            applied(service.enable(installed.status!!.packageId))
            assertEquals(2, notifications)
        }

    private fun pureScripts(body: String = "function main(input) { return { path: input.path, sum: 1 + 1 }; }") =
        ToolpackFixtures.scripts(ToolpackFixtures.TOOL_ENTRY to body)

    private fun writeManifest(): String = ToolpackFixtures.manifestJson(
        hostApis = listOf(ToolPackageHostApis.ARTIFACT_WRITE_TEXT)
    )

    private fun install(
        service: ToolPackageLifecycleService,
        manifest: String = pureManifest(),
        scripts: Map<String, String> = pureScripts()
    ): ToolPackageLifecycleOutcome = runBlocking {
        service.install(manifest.toByteArray(), scripts)
    }

    private fun applied(outcome: ToolPackageLifecycleOutcome): ToolPackageLifecycleOutcome.Applied {
        assertTrue("expected Applied, got $outcome", outcome is ToolPackageLifecycleOutcome.Applied)
        return outcome as ToolPackageLifecycleOutcome.Applied
    }

    private fun rejected(outcome: ToolPackageLifecycleOutcome, code: String) {
        assertTrue("expected Rejected, got $outcome", outcome is ToolPackageLifecycleOutcome.Rejected)
        assertEquals(code, (outcome as ToolPackageLifecycleOutcome.Rejected).code)
    }

    @Test
    fun `full lifecycle install enable project disable update rollback uninstall`() = runBlocking {
        val root = temporary.newFolder("full")
        val service = service(root)

        val installed = applied(service.install(pureManifest("1.0.0").toByteArray(), pureScripts()))
        val first = requireNotNull(installed.status)
        assertFalse("a fresh install must not be active", first.enabled)
        assertTrue(first.contentVerified)
        assertEquals("research-basics", first.packageId)
        assertEquals(listOf("pkg.research_basics.read_brief"), first.toolKeys)

        val enabled = applied(service.enable(first.packageId))
        assertTrue(requireNotNull(enabled.status).enabled)
        assertEquals(1, service.enabledExecutors().size)

        val disabled = applied(service.disable(first.packageId))
        assertFalse(requireNotNull(disabled.status).enabled)
        assertTrue(service.enabledExecutors().isEmpty())

        applied(service.enable(first.packageId))
        val updated = applied(service.update(pureManifest("2.0.0").toByteArray(), pureScripts()))
        val second = requireNotNull(updated.status)
        assertEquals("2.0.0", second.packageVersion)
        assertTrue("an update of an active package stays active", second.enabled)
        assertEquals(first.contentHash, second.previousContentHash)
        assertTrue(second.contentHash != first.contentHash)
        assertEquals(first.contentHash, updated.detail.previousContentHash)

        val rolledBack = applied(service.rollback(first.packageId))
        val restored = requireNotNull(rolledBack.status)
        assertEquals(first.contentHash, restored.contentHash)
        assertEquals(second.contentHash, restored.previousContentHash)
        assertTrue(restored.enabled)

        val uninstalled = applied(service.uninstall(first.packageId))
        assertNull(uninstalled.status)
        assertNull(service.status(first.packageId))
        assertTrue(service.enabledExecutors().isEmpty())
        assertTrue(uninstalled.detail.purgedContentHashes.containsAll(listOf(first.contentHash, second.contentHash)))
        assertFalse(File(File(root, "content"), first.contentHash).exists())
        assertFalse(File(File(root, "content"), second.contentHash).exists())
    }

    @Test
    fun `installing identical bytes is idempotent`() = runBlocking {
        val service = service()
        val first = applied(service.install(pureManifest().toByteArray(), pureScripts()))
        val second = applied(service.install(pureManifest().toByteArray(), pureScripts()))

        assertTrue(second.detail.idempotentReplay)
        assertTrue(second.detail.reusedContent)
        assertEquals(first.status!!.contentHash, second.status!!.contentHash)
        assertEquals(2L, second.status!!.revision)
        assertEquals(1, service.list().size)
    }

    @Test
    fun `installing different bytes over an active package is refused as update-required`() = runBlocking {
        val service = service()
        val first = applied(service.install(pureManifest("1.0.0").toByteArray(), pureScripts()))
        applied(service.enable(first.status!!.packageId))

        rejected(
            service.install(pureManifest("2.0.0").toByteArray(), pureScripts()),
            "TOOLPACK_UPDATE_REQUIRED"
        )
        val status = requireNotNull(service.status(first.status.packageId))
        assertEquals("1.0.0", status.packageVersion)
        assertTrue(status.enabled)
    }

    @Test
    fun `tampered content is refused at enable time`() = runBlocking {
        val root = temporary.newFolder("tamper")
        val service = service(root)
        val installed = applied(service.install(pureManifest().toByteArray(), pureScripts()))
        val hash = installed.status!!.contentHash
        File(File(File(root, "content"), hash), "scripts/tools/read_brief.js").writeText("// tampered\n")

        rejected(service.enable(installed.status.packageId), "TOOLPACK_CONTENT_HASH_MISMATCH")
        val status = requireNotNull(service.status(installed.status.packageId))
        assertFalse(status.enabled)
        assertFalse(status.contentVerified)
        assertTrue(service.enabledExecutors().isEmpty())
    }

    @Test
    fun `write-capable packages cannot be enabled`() = runBlocking {
        val service = service()
        val installed = applied(service.install(writeManifest().toByteArray(), pureScripts()))

        rejected(service.enable(installed.status!!.packageId), "TOOLPACK_WRITE_UNSUPPORTED")
        assertFalse(requireNotNull(service.status(installed.status.packageId)).enabled)
        assertTrue(service.enabledExecutors().isEmpty())
    }

    @Test
    fun `revocation blocks enable until it is cleared`() = runBlocking {
        val service = service()
        val installed = applied(service.install(pureManifest().toByteArray(), pureScripts()))
        val packageId = installed.status!!.packageId
        applied(service.enable(packageId))

        val revoked = applied(service.revoke(packageId))
        assertFalse(requireNotNull(revoked.status).enabled)
        assertTrue(requireNotNull(revoked.status).revoked)
        rejected(service.enable(packageId), "TOOLPACK_REVOKED")

        val cleared = applied(service.clearRevocation(packageId))
        assertFalse(requireNotNull(cleared.status).revoked)
        assertTrue(applied(service.enable(packageId)).status!!.enabled)
    }

    @Test
    fun `gc keeps active and rollback content and removes orphan content`() = runBlocking {
        val root = temporary.newFolder("gc")
        val service = service(root)
        val first = applied(service.install(pureManifest("1.0.0").toByteArray(), pureScripts()))
        applied(service.enable(first.status!!.packageId))
        val second = applied(service.update(pureManifest("2.0.0").toByteArray(), pureScripts()))
        val oldHash = first.status.contentHash
        val newHash = second.status!!.contentHash

        val orphan = "f".repeat(64)
        File(File(root, "content"), orphan).mkdirs()

        val gc = applied(service.collectGarbage())

        assertTrue(gc.detail.purgedContentHashes.contains(orphan))
        assertFalse(File(File(root, "content"), orphan).exists())
        assertTrue(File(File(root, "content"), oldHash).isDirectory)
        assertTrue(File(File(root, "content"), newHash).isDirectory)
        assertTrue(gc.detail.retainedContentHashes.containsAll(listOf(oldHash, newHash)))
    }

    @Test
    fun `uninstall deactivates before removal and leaves no active record`() = runBlocking {
        val service = service()
        val installed = applied(service.install(pureManifest().toByteArray(), pureScripts()))
        val packageId = installed.status!!.packageId
        applied(service.enable(packageId))

        val outcome = applied(service.uninstall(packageId))

        assertNull(outcome.status)
        assertNull(service.status(packageId))
        assertTrue(service.list().isEmpty())
    }

    @Test
    fun `rollback without a previous version is rejected`() = runBlocking {
        val service = service()
        val installed = applied(service.install(pureManifest().toByteArray(), pureScripts()))

        rejected(service.rollback(installed.status!!.packageId), "TOOLPACK_NO_ROLLBACK_TARGET")
    }

    @Test
    fun `lifecycle operations on an unknown package are typed not-installed`() = runBlocking {
        val service = service()

        rejected(service.enable("missing-package"), "TOOLPACK_NOT_INSTALLED")
        rejected(service.disable("missing-package"), "TOOLPACK_NOT_INSTALLED")
        rejected(service.revoke("missing-package"), "TOOLPACK_NOT_INSTALLED")
        rejected(service.uninstall("missing-package"), "TOOLPACK_NOT_INSTALLED")
        assertNull(service.status("missing-package"))
    }

    @Test
    fun `an invalid manifest is rejected without creating state`() = runBlocking {
        val root = temporary.newFolder("invalid")
        val service = service(root)

        rejected(
            service.install("""{"schemaVersion":2,"packageId":"Bad"}""".toByteArray(), pureScripts()),
            "TOOLPACK_MANIFEST_INVALID"
        )
        assertTrue(service.list().isEmpty())
        assertTrue(File(root, "state").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `enabled executors run a real rhino script tool`() = runBlocking {
        val service = service()
        val installed = applied(service.install(pureManifest().toByteArray(), pureScripts()))
        applied(service.enable(installed.status!!.packageId))

        val executor = service.enabledExecutors().single()
        val command = ToolpackFixtures.command(
            toolKey = executor.descriptor.toolKey,
            toolVersion = executor.descriptor.version,
            schemaHash = executor.descriptor.schemaHash
        )

        val result = executor.execute(command, ToolpackFixtures.context())

        assertEquals(
            com.muyuchat.core.agent.contract.AgentToolResultStatus.SUCCEEDED,
            result.status
        )
        assertTrue(result.dataJson!!.contains("\"sum\":2"))
        assertTrue(result.dataJson!!.contains("\"path\":\"notes.txt\""))
    }

    @Test
    fun `installArchive installs a strict zip and rejects unsafe entries`() = runBlocking {
        val service = service()
        val manifest = pureManifest()

        val good = zip(
            ToolPackageArchive.MANIFEST_FILE_NAME to manifest.toByteArray(),
            ToolpackFixtures.MAIN_ENTRY to "// registration\n".toByteArray(),
            ToolpackFixtures.TOOL_ENTRY to
                "function main(input) { return { fromArchive: true }; }\n".toByteArray()
        )
        val installed = applied(service.installArchive(good))
        assertTrue(installed.status!!.contentVerified)

        val nested = zip(
            "nested/mca-tool-package.json" to manifest.toByteArray(),
            ToolpackFixtures.TOOL_ENTRY to "function main(input) { return {}; }".toByteArray()
        )
        rejected(service.installArchive(nested), "TOOLPACK_MANIFEST_MISSING")

        val nonScript = zip(
            ToolPackageArchive.MANIFEST_FILE_NAME to manifest.toByteArray(),
            ToolpackFixtures.MAIN_ENTRY to "//\n".toByteArray(),
            ToolpackFixtures.TOOL_ENTRY to "function main(input) { return {}; }".toByteArray(),
            "README.md" to "docs".toByteArray()
        )
        rejected(service.installArchive(nonScript), "TOOLPACK_ARCHIVE_INVALID")

        val traversal = zip(
            ToolPackageArchive.MANIFEST_FILE_NAME to manifest.toByteArray(),
            "../escape.js" to "function main(input) { return {}; }".toByteArray()
        )
        rejected(service.installArchive(traversal), "TOOLPACK_ARCHIVE_INVALID")

        val empty = zip()
        rejected(service.installArchive(empty), "TOOLPACK_MANIFEST_MISSING")
    }

    @Test
    fun `updateArchive switches an active package to the archived version`() = runBlocking {
        val service = service()
        val first = applied(service.install(pureManifest("1.0.0").toByteArray(), pureScripts()))
        applied(service.enable(first.status!!.packageId))
        val archive = zip(
            ToolPackageArchive.MANIFEST_FILE_NAME to pureManifest("2.0.0").toByteArray(),
            ToolpackFixtures.MAIN_ENTRY to "// registration\n".toByteArray(),
            ToolpackFixtures.TOOL_ENTRY to "function main(input) { return { v: 2 }; }".toByteArray()
        )

        val updated = applied(service.updateArchive(archive))

        assertEquals("2.0.0", updated.status!!.packageVersion)
        assertTrue(updated.status.enabled)
        assertEquals(first.status.contentHash, updated.status.previousContentHash)
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
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
}
