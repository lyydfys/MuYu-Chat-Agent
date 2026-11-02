package com.muyuchat.core.dsh.runtime.config

import com.muyuchat.core.dsh.contract.DshGatewayEndpoint
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D02: the official frontend must start with zero MCA models, and an MCA-less (account-only)
 * generation must still be a valid, honest configuration.
 *
 * The account-only path is what lets the official DeepSeek account provider and the official UI
 * work when no MCA gateway is bound. It writes no `llm-pi-ai` MCA provider row (no invented
 * baseURL/token), never names the official account provider (so never disables it), keeps every
 * platform patch row, and preserves overlay rows that MCA does not own.
 */
class DshAccountOnlySeedTest {

    @Test
    fun accountOnlySeedPublishesAnHonestConfigurationWithoutAnMcaProvider() {
        val root = tempRoot("dsh-account-only")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            assertTrue(config.homeDir.mkdirs())

            val seeder = DshGatewayConfigSeeder(config)
            val outcome = seeder.seedAccountOnly()
            assertTrue("an account-only generation must seed, was $outcome", outcome is DshSeedOutcome.Seeded)

            val overlay = File(config.homeDir, DshGatewayConfigSeeder.OVERLAY_NAME).readText()
            assertFalse("no MCA provider may be advertised without a bound gateway", overlay.contains("llm-pi-ai"))
            assertFalse("no MCA baseURL may be invented", overlay.contains("baseURL"))
            assertFalse("the official DeepSeek account provider must not be touched", overlay.contains("deepseek-account"))
            assertTrue(overlay.contains("- id: bash-sandbox\n  disabled: true"))
            assertTrue(overlay.contains("- insert:\n    # mca:managed-insert\n    - id: bash-local"))
            assertFalse(
                "the official sandbox service must stay enabled",
                overlay.contains("- id: sandbox\n  disabled: true")
            )
            for ((id, name) in TOOL_PACKAGES) {
                assertTrue(
                    "the account-only overlay must still enable the official tool package $id",
                    overlay.contains("    - id: $id\n      name: '$name'")
                )
            }
            assertTrue(overlay.contains("defaultPreset: workspace-write"))
            assertFalse(overlay.contains("approval: never"))

            val facts = DshComposedConfigInspector.inspect(overlay)
            assertFalse(facts.mcaProviderPresent)
            assertTrue(facts.bashLocalInserted)
            assertTrue(DshComposedConfigInspector.violations(facts, requireMcaProvider = false).isEmpty())

            val report = seeder.lastReport
            assertNotNull(report)
            assertEquals(DshConfigProviderState.McaNotBound, report!!.provider)
            assertEquals(64, report.overlaySha256.length)
            assertTrue(report.preservedUserRows.isEmpty())

            val marker = markerJson(config)
            assertEquals("mca-not-bound", marker.getString("providerState"))
            assertEquals(report.overlaySha256, marker.getString("overlaySha256"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aZeroModelCatalogIsALegalBoundProviderState() {
        val root = tempRoot("dsh-empty-catalog")
        val catalog = FakeCatalog("""{"object":"list","data":[]}""")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            val seeder = DshGatewayConfigSeeder(config)
            val outcome = seeder.seed(catalog.endpoint)
            assertTrue("an empty catalog is legal, was $outcome", outcome is DshSeedOutcome.Seeded)

            val overlay = File(config.homeDir, DshGatewayConfigSeeder.OVERLAY_NAME).readText()
            assertTrue(overlay.contains("- id: llm-pi-ai"))
            assertTrue(overlay.contains("models: []"))
            assertEquals(
                DshConfigProviderState.McaCatalogEmpty(catalog.endpoint.baseUrl),
                seeder.lastReport!!.provider
            )
            assertEquals("mca-catalog-empty", markerJson(config).getString("providerState"))
        } finally {
            catalog.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun accountOnlyReseedRemovesTheStaleMcaProviderRowAndPreservesUserRows() {
        val root = tempRoot("dsh-account-only-reseed")
        val catalog = FakeCatalog(
            """{"object":"list","data":[{"id":"mca-local:a","name":"A"}]}"""
        )
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            val seeder = DshGatewayConfigSeeder(config)
            assertTrue(seeder.seed(catalog.endpoint) is DshSeedOutcome.Seeded)
            val overlayFile = File(config.homeDir, DshGatewayConfigSeeder.OVERLAY_NAME)
            assertTrue(overlayFile.readText().contains("- id: llm-pi-ai"))

            // A user/other-owner patch row added after the MCA generation.
            overlayFile.appendText(
                "- id: user-project-patch\n  enabled: false\n"
            )

            val outcome = seeder.seedAccountOnly()
            assertTrue("account-only reseed must succeed, was $outcome", outcome is DshSeedOutcome.Seeded)
            val overlay = overlayFile.readText()
            assertFalse("the stale MCA provider row must be removed", overlay.contains("llm-pi-ai"))
            assertTrue("an unmanaged row must survive the merge", overlay.contains("user-project-patch"))
            assertEquals(listOf("user-project-patch"), seeder.lastReport!!.preservedUserRows)
            assertTrue(
                "the regenerated managed rows are reported as conflicts",
                seeder.lastReport!!.managedConflicts.contains("llm-pi-ai")
            )
            assertFalse(seeder.lastReport!!.previousOverlayBackedUp)
        } finally {
            catalog.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun anUnreadablePreviousOverlayIsKeptAsABackupInsteadOfBeingMerged() {
        val root = tempRoot("dsh-corrupt-overlay")
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            seedPayloadTemplate(config)
            assertTrue(DshGatewayConfigSeeder(config).seedAccountOnly() is DshSeedOutcome.Seeded)
            val overlayFile = File(config.homeDir, DshGatewayConfigSeeder.OVERLAY_NAME)
            val garbage = "not: a patch\n```\n"
            overlayFile.writeText(garbage)

            val seeder = DshGatewayConfigSeeder(config)
            val outcome = seeder.seedAccountOnly()
            assertTrue("a corrupt overlay must not block the boot, was $outcome", outcome is DshSeedOutcome.Seeded)

            val backup = File(config.homeDir, DshGatewayConfigSeeder.OVERLAY_BACKUP_NAME)
            assertTrue("the unreadable previous file must be preserved", backup.isFile)
            assertEquals(garbage, backup.readText())
            assertTrue(seeder.lastReport!!.previousOverlayBackedUp)
            val overlay = overlayFile.readText()
            assertTrue(overlay.contains("- insert:"))
            assertFalse(overlay.contains("not: a patch"))
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ helpers

    private companion object {
        val TOOL_PACKAGES = listOf(
            "tool-shizuku" to "@deepseek-ai/dsh-tool-shizuku",
            "tool-android" to "@deepseek-ai/dsh-tool-android",
            "tool-accessibility" to "@deepseek-ai/dsh-tool-accessibility",
            "tool-vscreen" to "@deepseek-ai/dsh-tool-vscreen",
            "tool-browser" to "@deepseek-ai/dsh-tool-browser"
        )
    }

    private fun tempRoot(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()

    private fun seedPayloadTemplate(config: DshRuntimeConfig) {
        val root = File(config.payloadDir, "dshhome")
        val profile = File(root, "profiles/web")
        assertTrue(profile.mkdirs() || profile.isDirectory)
        File(profile, "cordis.yml").writeText("bundles:\n  - @deepseek-ai/dsh-base\n")
        File(profile, "package.json").writeText("""{"name":"dsh-profile-web","private":true}""")
        File(profile, "pnpm-workspace.yaml").writeText("packages:\n  - .\n")
        File(root, "settings.yaml").writeText("ui-conversation:\n  busyEnter: steer\n")
    }

    private fun markerJson(config: DshRuntimeConfig): JSONObject =
        JSONObject(
            File(config.homeDir, DshGatewayConfigSeeder.TEMPLATE_MARKER_NAME)
                .readText(Charsets.UTF_8)
        )

    /** One-shot authenticated `/v1/models` listener; the seeder reads it exactly once. */
    private class FakeCatalog(private val body: String) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val authorization = AtomicReference<String?>(null)
        private val worker = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("Authorization:", ignoreCase = true)) {
                            authorization.set(line.substringAfter(':').trim())
                        }
                        if (line.isEmpty()) break
                    }
                    val response = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                        "Connection: close\r\n\r\n$body"
                    socket.getOutputStream().apply {
                        write(response.toByteArray(Charsets.UTF_8))
                        flush()
                    }
                }
            }
        }

        val endpoint = DshGatewayEndpoint(
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            bearerToken = "test-bearer-token",
            catalogRevision = "rev-test"
        )

        override fun close() {
            runCatching { server.close() }
            worker.join(2_000)
        }
    }
}

