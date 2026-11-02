package com.muyuchat.mca.agent.toolpack

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Install / verify / enable / disable / rollback / recover / GC of the executable package store.
 *
 * All evidence is JVM file-system behavior with fake scripts; no JavaScript engine, no real package
 * download and no device storage are involved.
 */
class ToolpackInstallStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun store(
        root: File = temporary.newFolder("store"),
        faultInjector: (String) -> Unit = {}
    ): ToolPackageInstallStore = ToolPackageInstallStore(root, clockMillis = { 1_000L }, faultInjector = faultInjector)

    private fun manifestJson(
        packageId: String = ToolpackFixtures.PACKAGE_ID,
        packageVersion: String = "1.0.0",
        hostApis: List<String> = listOf(ToolPackageHostApis.WORKSPACE_READ_TEXT)
    ): String = ToolpackFixtures.manifestJson(
        packageId = packageId,
        packageVersion = packageVersion,
        hostApis = hostApis
    )

    private fun contentRoot(root: File): File = File(root, "content")

    private fun stateRoot(root: File): File = File(root, "state")

    @Test
    fun `install stores content-addressed bytes and a disabled activation record`() = runBlocking {
        val root = temporary.newFolder("install")
        val store = store(root)
        val bundle = ToolpackFixtures.bundle(manifestJson())

        val result = store.install(bundle.manifest.canonicalJson.toByteArray(), bundle.scripts)

        assertTrue("$result", result is ToolPackageInstallResult.Installed)
        val record = (result as ToolPackageInstallResult.Installed).record
        assertEquals(ToolpackFixtures.PACKAGE_ID, record.packageId)
        assertEquals(bundle.contentHash, record.contentHash)
        assertFalse("a fresh install must not be active", record.enabled)
        assertEquals(1L, record.revision)
        assertTrue(!(result.reusedContent))

        val stored = File(contentRoot(root), bundle.contentHash)
        assertTrue(File(stored, "manifest.json").isFile)
        assertTrue(File(stored, "scripts/tools/read_brief.js").isFile)
        assertTrue(store.verify(bundle.contentHash) is ToolPackageVerifyResult.Valid)
    }

    @Test
    fun `installing identical bytes reuses the content address`() = runBlocking {
        val root = temporary.newFolder("reuse")
        val store = store(root)
        val bundle = ToolpackFixtures.bundle(manifestJson())

        store.install(bundle.manifest.canonicalJson.toByteArray(), bundle.scripts)
        val second = store.install(bundle.manifest.canonicalJson.toByteArray(), bundle.scripts)

        val installed = second as ToolPackageInstallResult.Installed
        assertTrue(installed.reusedContent)
        assertEquals(2L, installed.record.revision)
        assertEquals(
            listOf(bundle.contentHash),
            contentRoot(root).listFiles()!!.map { it.name }
        )
    }

    @Test
    fun `enable activates only verified read-only content`() = runBlocking {
        val root = temporary.newFolder("enable")
        val store = store(root)
        val bundle = ToolpackFixtures.bundle(manifestJson())
        store.install(bundle.manifest.canonicalJson.toByteArray(), bundle.scripts)

        val enabled = store.enable(bundle.contentHash)

        assertTrue("$enabled", enabled is ToolPackageActivationResult.Activated)
        assertEquals(
            listOf(bundle.contentHash),
            store.activeRecords().map { it.contentHash }
        )

        val disabled = store.disable(bundle.contentHash)
        assertTrue(disabled is ToolPackageActivationResult.Activated)
        assertTrue(store.activeRecords().isEmpty())
        // Disabling keeps the immutable content, so a later enable needs no reinstall.
        assertTrue(File(contentRoot(root), bundle.contentHash).isDirectory)
    }

    @Test
    fun `write-capable packages are refused activation`() = runBlocking {
        val root = temporary.newFolder("write-refused")
        val store = store(root)
        val bundle = ToolpackFixtures.bundle(
            manifestJson(hostApis = listOf(ToolPackageHostApis.ARTIFACT_WRITE_TEXT))
        )
        store.install(bundle.manifest.canonicalJson.toByteArray(), bundle.scripts)

        val enabled = store.enable(bundle.contentHash)

        assertTrue(enabled is ToolPackageActivationResult.Rejected)
        assertEquals(
            ToolPackageErrorCode.WRITE_CAPABILITY_UNSUPPORTED.agentCode,
            (enabled as ToolPackageActivationResult.Rejected).code
        )
        assertTrue(store.activeRecords().isEmpty())
    }

    @Test
    fun `tampered content fails verification and cannot be enabled`() = runBlocking {
        val root = temporary.newFolder("tamper")
        val store = store(root)
        val bundle = ToolpackFixtures.bundle(manifestJson())
        store.install(bundle.manifest.canonicalJson.toByteArray(), bundle.scripts)

        File(File(contentRoot(root), bundle.contentHash), "scripts/tools/read_brief.js")
            .writeText("// tampered\n")

        val verified = store.verify(bundle.contentHash)
        assertTrue(verified is ToolPackageVerifyResult.Rejected)
        assertEquals("TOOLPACK_CONTENT_HASH_MISMATCH", (verified as ToolPackageVerifyResult.Rejected).code)

        val enabled = store.enable(bundle.contentHash)
        assertTrue(enabled is ToolPackageActivationResult.Rejected)
        assertTrue(store.activeRecords().isEmpty())
    }

    @Test
    fun `invalid manifest leaves no content and no state behind`() = runBlocking {
        val root = temporary.newFolder("invalid")
        val store = store(root)
        val invalid = """{"schemaVersion":2,"packageId":"Bad Id"}"""

        val result = store.install(invalid.toByteArray(), mapOf("main.js" to "//"))

        assertTrue(result is ToolPackageInstallResult.Rejected)
        assertEquals("TOOLPACK_MANIFEST_INVALID", (result as ToolPackageInstallResult.Rejected).code)
        assertTrue(contentRoot(root).listFiles().orEmpty().isEmpty())
        assertTrue(stateRoot(root).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `an in-process failure rolls the previous activation back`() = runBlocking {
        val root = temporary.newFolder("rollback")
        val first = ToolpackFixtures.bundle(manifestJson(packageVersion = "1.0.0"))
        val setup = store(root)
        setup.install(first.manifest.canonicalJson.toByteArray(), first.scripts)
        setup.enable(first.contentHash)

        var fail = false
        val failing = ToolPackageInstallStore(
            root,
            clockMillis = { 2_000L },
            faultInjector = { phase -> if (fail && phase == "after_state") throw IllegalStateException("boom") }
        )
        val second = ToolpackFixtures.bundle(manifestJson(packageVersion = "2.0.0"))
        fail = true
        val result = failing.install(second.manifest.canonicalJson.toByteArray(), second.scripts)

        assertTrue(result is ToolPackageInstallResult.Rejected)
        // The previous content/activation is intact and the failed content was rolled back.
        val record = setup.scan().records.single()
        assertEquals(first.contentHash, record.contentHash)
        assertTrue(record.enabled)
        assertFalse(File(contentRoot(root), second.contentHash).exists())
        assertTrue(File(root, "staging").listFiles().orEmpty().isEmpty())
        assertTrue(File(root, "journal").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a crash before the active pointer is written is repaired by recover`() = runBlocking {
        val root = temporary.newFolder("crash-before-pointer")
        val crashing = ToolPackageInstallStore(
            root,
            clockMillis = { 3_000L },
            faultInjector = { phase -> if (phase == "after_content") throw ToolPackageSimulatedCrash(phase) }
        )
        val bundle = ToolpackFixtures.bundle(manifestJson())

        var crashed = false
        try {
            crashing.install(bundle.manifest.canonicalJson.toByteArray(), bundle.scripts)
        } catch (_: RuntimeException) {
            crashed = true
        }
        assertTrue(crashed)
        assertTrue(File(contentRoot(root), bundle.contentHash).isDirectory)
        assertTrue(File(root, "journal").listFiles().orEmpty().isNotEmpty())

        val recovered = store(root).recover()

        assertEquals(1, recovered.repairedTransactions)
        assertEquals(listOf(bundle.contentHash), recovered.deletedContentHashes)
        assertFalse(File(contentRoot(root), bundle.contentHash).exists())
        assertTrue(File(root, "journal").listFiles().orEmpty().isEmpty())
        assertTrue(store(root).install(bundle.manifest.canonicalJson.toByteArray(), bundle.scripts)
            is ToolPackageInstallResult.Installed)
    }

    @Test
    fun `a crash after the active pointer keeps the installed content`() = runBlocking {
        val root = temporary.newFolder("crash-after-pointer")
        val crashing = ToolPackageInstallStore(
            root,
            clockMillis = { 4_000L },
            faultInjector = { phase -> if (phase == "after_state") throw ToolPackageSimulatedCrash(phase) }
        )
        val bundle = ToolpackFixtures.bundle(manifestJson())

        try {
            crashing.install(bundle.manifest.canonicalJson.toByteArray(), bundle.scripts)
        } catch (_: RuntimeException) {
            // simulated process death right after the state pointer was written
        }

        val recovered = store(root).recover()

        assertEquals(1, recovered.repairedTransactions)
        assertTrue(recovered.deletedContentHashes.isEmpty())
        assertTrue(File(contentRoot(root), bundle.contentHash).isDirectory)
        assertEquals(1, store(root).scan().records.size)
    }

    @Test
    fun `garbage collection removes unreferenced content and dangling state only`() = runBlocking {
        val root = temporary.newFolder("gc")
        val store = store(root)
        val active = ToolpackFixtures.bundle(manifestJson(packageId = "research-basics"))
        val inactive = ToolpackFixtures.bundle(manifestJson(packageId = "research-extra"))
        store.install(active.manifest.canonicalJson.toByteArray(), active.scripts)
        store.enable(active.contentHash)
        store.install(inactive.manifest.canonicalJson.toByteArray(), inactive.scripts)

        // An orphan content directory (no state record) and a state record whose content vanished.
        val orphanHash = "f".repeat(64)
        File(contentRoot(root), orphanHash).mkdirs()
        File(contentRoot(root), inactive.contentHash).deleteRecursively()

        val protectedHash = "9".repeat(64)
        val report = store.collectGarbage(protectedContentHashes = setOf(protectedHash))

        assertEquals(listOf(orphanHash), report.deletedContentHashes)
        assertEquals(listOf("research-extra.json"), report.deletedDanglingStateRecords)
        assertTrue(report.keptContentHashes.containsAll(listOf(active.contentHash, protectedHash)))
        assertTrue(orphanHash !in report.keptContentHashes)
        assertTrue(File(contentRoot(root), active.contentHash).isDirectory)
        assertFalse(File(contentRoot(root), orphanHash).exists())
        assertEquals(listOf(active.contentHash), store.activeRecords().map { it.contentHash })
    }
}
