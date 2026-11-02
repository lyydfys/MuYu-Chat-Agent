package com.muyuchat.mca.agent.toolpack

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `install -> verify -> enable -> catalog` projection with a fake script engine.
 *
 * The engine returns a fixed JSON document, so this proves the projection/enable/verify wiring and
 * the frozen executor binding — not JavaScript semantics (there is no JS engine in this build).
 */
class ToolpackCatalogProjectionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val engine = FakeScriptEngine { ToolPackageEngineResult.Returned("""{"answer":42}""") }
    private val runtime = ToolPackageScriptRuntime(engine, RecordingHostHandler())
    private val bundle = ToolpackFixtures.bundle()

    private fun projection(store: ToolPackageInstallStore) = ToolPackageCatalogProjection(store, runtime)

    private suspend fun install(store: ToolPackageInstallStore) {
        val result = store.install(bundle.manifest.canonicalJson.toByteArray(), bundle.scripts)
        assertTrue("$result", result is ToolPackageInstallResult.Installed)
    }

    @Test
    fun `an enabled read-only package appears in the catalog and executes`() = runBlocking {
        val store = ToolPackageInstallStore(temporary.newFolder("projection"))
        install(store)
        assertTrue(store.enable(bundle.contentHash) is ToolPackageActivationResult.Activated)

        val projected = projection(store).project()

        assertEquals(listOf(bundle.contentHash), projected.includedContentHashes)
        assertTrue(projected.skipped.isEmpty())
        val key = "pkg.research_basics.read_brief"
        val executor = projected.catalog.find(key)
        assertNotNull(executor)
        assertEquals(listOf(key), projected.catalog.snapshot().map { it.toolKey })
        assertEquals(bundle.versionIdentity, executor!!.descriptor.version)

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
        assertTrue(result.dataJson!!.contains("\"answer\":42"))
        assertEquals(1, engine.invocations)
    }

    @Test
    fun `an installed but disabled package never enters the catalog`() = runBlocking {
        val store = ToolPackageInstallStore(temporary.newFolder("disabled"))
        install(store)

        val projected = projection(store).project()

        assertTrue(projected.catalog.snapshot().isEmpty())
        assertTrue(projected.includedContentHashes.isEmpty())
    }

    @Test
    fun `disabling an enabled package removes it from the next projection`() = runBlocking {
        val store = ToolPackageInstallStore(temporary.newFolder("disable-live"))
        install(store)
        store.enable(bundle.contentHash)
        assertEquals(1, projection(store).project().catalog.snapshot().size)

        store.disable(bundle.contentHash)

        assertTrue(projection(store).project().catalog.snapshot().isEmpty())
    }

    @Test
    fun `tampered content is skipped with a typed reason and contributes no tools`() = runBlocking {
        val root = temporary.newFolder("tampered")
        val store = ToolPackageInstallStore(root)
        install(store)
        store.enable(bundle.contentHash)
        File(File(root, "content/${bundle.contentHash}"), "scripts/main.js").writeText("// tampered\n")

        val projected = projection(store).project()

        assertTrue(projected.catalog.snapshot().isEmpty())
        val skip = projected.skipped.single()
        assertEquals("TOOLPACK_CONTENT_HASH_MISMATCH", skip.code)
        assertEquals(bundle.contentHash, skip.contentHash)
    }

    @Test
    fun `a write-capable package stays out of the catalog even after install`() = runBlocking {
        val store = ToolPackageInstallStore(temporary.newFolder("write-package"))
        val writeBundle = ToolpackFixtures.bundle(
            ToolpackFixtures.manifestJson(hostApis = listOf(ToolPackageHostApis.ARTIFACT_WRITE_TEXT))
        )
        val installed = store.install(writeBundle.manifest.canonicalJson.toByteArray(), writeBundle.scripts)
        assertTrue(installed is ToolPackageInstallResult.Installed)
        store.enable(writeBundle.contentHash)

        val projected = projection(store).project()

        assertTrue(projected.catalog.snapshot().isEmpty())
        assertTrue(projected.includedContentHashes.isEmpty())
    }
}
