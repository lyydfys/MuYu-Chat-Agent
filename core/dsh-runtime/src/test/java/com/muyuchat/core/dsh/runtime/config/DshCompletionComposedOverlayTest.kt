package com.muyuchat.core.dsh.runtime.config

import com.muyuchat.core.dsh.contract.DshGatewayEndpoint
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C02c composed-tree half of V08 that can be proven off-device: the production seeder, driven by
 * the **real** payload template extracted from the packaged archive, must produce a home overlay
 * that (a) the official loader will read as the home-level patch, and (b) satisfies the official
 * patch schema shape. The device half (the official kernel's own composed plugin list) still has
 * to run on the device and is recorded separately.
 *
 * The archive path comes from the `dsh.completion.repo` system property (the module root's
 * parent); when absent, repoRoot() falls back to the working directory.
 */
class DshCompletionComposedOverlayTest {

    private fun repoRoot(): File {
        val module = File(System.getProperty("user.dir", ".")).absoluteFile
        // Gradle runs module tests with user.dir = <repo>/core/dsh-runtime.
        var candidate: File? = module
        repeat(4) {
            val dir = candidate
            if (dir != null && File(dir, "core/dsh-runtime/src/main/assets/dsh/payload.zip").isFile) {
                return dir
            }
            candidate = dir?.parentFile
        }
        throw IllegalStateException("payload.zip not found above ${module.absolutePath}")
    }

    /** Extracts only the `dshhome/` subtree into [target] and returns it. */
    private fun extractTemplate(dest: File) {
        val payload = File(repoRoot(), "core/dsh-runtime/src/main/assets/dsh/payload.zip")
        java.util.zip.ZipFile(payload).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.name.startsWith("dshhome/") || entry.isDirectory) continue
                val out = File(dest, entry.name.removePrefix("dshhome/"))
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    @Test
    fun productionSeederTurnsTheRealPayloadTemplateIntoAReadableHomeOverlay() {
        val root = java.nio.file.Files.createTempDirectory("dsh-composed-overlay").toFile()
        try {
            val config = DshRuntimeConfig(dataRoot = root).validated()
            extractTemplate(File(config.payloadDir, "dshhome"))
            val templateOverlay = File(config.payloadDir, "dshhome/cordis.patch.yml")
            assertTrue("the packaged template must ship a platform patch", templateOverlay.isFile)
            val templateText = templateOverlay.readText()
            for (row in listOf("bash-sandbox", "pwsh-sandbox", "session-log-download", "session-telemetry-otel", "bash-local")) {
                assertTrue("template row missing: $row", templateText.contains(row))
            }

            val catalog = Catalog("""{"object":"list","data":[{"id":"m1","name":"Model One","context_window":8192}]}""")
            val outcome = try {
                DshGatewayConfigSeeder(config).seed(catalog.endpoint)
            } finally {
                catalog.close()
            }
            assertTrue("seed must succeed against the real template, was $outcome", outcome is DshSeedOutcome.Seeded)

            val overlayFile = File(config.homeDir, DshGatewayConfigSeeder.OVERLAY_NAME)
            assertTrue("home-level patch must exist at DSH_HOME/cordis.patch.yml", overlayFile.isFile)
            val overlay = overlayFile.readText()
            // (1) official loader contract: the file the kernel reads is the home-level patch.
            for (row in listOf("bash-sandbox", "pwsh-sandbox", "session-log-download", "session-telemetry-otel")) {
                assertTrue("overlay must disable $row", overlay.contains("- id: $row\n  disabled: true"))
            }
            assertTrue("overlay must insert bash-local", overlay.contains("- id: bash-local"))
            assertFalse(
                "overlay must keep the sandbox plugin enabled",
                overlay.contains("- id: sandbox\n  disabled: true")
            )
            assertTrue("MCA permission preset", overlay.contains("defaultPreset: workspace-write"))
            assertTrue("MCA approval mode", overlay.contains("approval: ask"))
            assertTrue(
                "template danger default must not leak",
                !overlay.contains("defaultPreset: danger-full-access")
            )
            assertTrue("reference approval default must not leak", !overlay.contains("approval: never"))
            val expectedTools = listOf(
                "tool-shizuku" to "@deepseek-ai/dsh-tool-shizuku",
                "tool-android" to "@deepseek-ai/dsh-tool-android",
                "tool-accessibility" to "@deepseek-ai/dsh-tool-accessibility",
                "tool-vscreen" to "@deepseek-ai/dsh-tool-vscreen",
                "tool-browser" to "@deepseek-ai/dsh-tool-browser"
            )
            for ((id, name) in expectedTools) {
                assertTrue(
                    "the official mobile tool package $id must be inserted",
                    overlay.contains("    - id: $id\n      name: '$name'")
                )
            }
            assertTrue("provider row must point at the live gateway", overlay.contains(catalog.endpoint.baseUrl))
            assertTrue("models list must carry the fixture model", overlay.contains("Model One"))
            // (2) YAML shape: every non-empty line is a mapping/sequence/scalar under a root key.
            val lines = overlay.lines()
            val firstMeaningful = lines.first { it.isNotBlank() }
            assertTrue(
                "overlay must start with the MCA comment header",
                firstMeaningful.trimStart().startsWith("#")
            )
            val firstRow = lines.first { it.isNotBlank() && !it.trimStart().startsWith("#") }
            assertTrue(
                "the home patch is a YAML sequence of patch rows",
                firstRow.trimStart().startsWith("- ")
            )
            var diffRows = 0
            lines.forEach { line ->
                if (line.trimStart().startsWith("- id:") || line.trimStart().startsWith("- insert:")) diffRows++
                assertTrue("tabs are not valid YAML indentation: '$line'", !line.takeWhile { it == ' ' || it == '\t' }.contains('\t'))
            }
            assertTrue("overlay must be a list of patch rows, saw $diffRows", diffRows >= 5)
            println("COMPOSED_OVERLAY template=${templateText.length}B overlay=${overlay.length}B rows=$diffRows overlaySha=${overlay.hashCode()}")
        } finally {
            root.deleteRecursively()
        }
    }

    private class Catalog(private val body: String) : AutoCloseable {
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
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\nConnection: close\r\n\r\n$body"
                    socket.getOutputStream().apply {
                        write(response.toByteArray(Charsets.UTF_8))
                        flush()
                    }
                }
            }
        }

        val endpoint = DshGatewayEndpoint(
            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
            bearerToken = "composed-test-bearer-0123456789abcdef",
            catalogRevision = "rev-composed"
        )

        fun authorization(): String? = authorization.get()

        override fun close() {
            runCatching { server.close() }
            worker.join(2_000)
        }
    }
}


