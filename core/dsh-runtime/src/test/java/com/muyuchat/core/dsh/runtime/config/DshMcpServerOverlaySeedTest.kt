package com.muyuchat.core.dsh.runtime.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `<DSH_HOME>/mcp-servers.json` -> exactly one official `@deepseek-ai/dsh-mcp-client` row.
 *
 * `@deepseek-ai/dsh-mcp-client` is bundled in the verified payload but no shipped layer names it, so
 * MCP was unreachable: the only configuration surface is the app-private home overlay. This test
 * pins the smallest honest bridge MCA can own:
 *  - absent declaration file -> zero rows, byte-identical overlay across reseeds (today's behavior);
 *  - one valid stdio/streamable-http server -> exactly one official row, appended once, stable
 *    across a second reseed;
 *  - invalid entries and injection attempts -> typed, value-free diagnostics and no row;
 *  - bounds -> the whole file is refused rather than truncated;
 *  - a server removed from the file loses its generated row, while a hand-written
 *    `dsh-mcp-client` row keeps being preserved.
 *
 * Scope: this is a JVM fixture of the seeder/merge contract. It does not start the official Node
 * kernel, so it proves the composed *configuration*, never that an MCP server connects.
 */
class DshMcpServerOverlaySeedTest {

    @Test
    fun anAbsentDeclarationFileKeepsTheOverlayByteIdenticalAndAppendsNothing() {
        val root = tempRoot("dsh-mcp-absent")
        try {
            val config = payloadTemplateConfig(root)
            val seeder = DshGatewayConfigSeeder(config)
            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            val overlayTarget = overlayFile(config)
            val first = overlayTarget.readText()

            assertTrue(
                "the static overlay must carry the shell-env contributor row",
                first.contains(
                    "    - id: mca-shell-env\n" +
                        "      name: '@mca/dsh-shell-env'\n" +
                        "      config:\n" +
                        "        payloadDir: "
                )
            )
            assertFalse(first.contains("dsh-mcp-client"))
            assertFalse(first.lines().any { it.trim().startsWith("- id: mcp-") })
            assertTrue(seeder.lastReport!!.mcpServerRows.isEmpty())
            assertTrue(seeder.lastReport!!.mcpDiagnostics.isEmpty())

            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            assertEquals("a reseed without a declaration must not change a byte", first, overlayTarget.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aValidStdioServerBecomesExactlyOneOfficialRowThatSurvivesASecondReseed() {
        val root = tempRoot("dsh-mcp-stdio")
        try {
            val config = payloadTemplateConfig(root)
            val seeder = DshGatewayConfigSeeder(config)
            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            // The home must already be seeded before the declaration is dropped in: a home whose
            // only content is this file is classified as incomplete, never as first run.
            writeMcpConfig(config, STDIO_DECLARATION)

            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            val overlay = overlayFile(config).readText()
            assertEquals(listOf("mcp-github"), seeder.lastReport!!.mcpServerRows)
            assertTrue(seeder.lastReport!!.mcpDiagnostics.isEmpty())
            assertEquals(
                "the official row must have the documented shape",
                EXPECTED_STDIO_ROW,
                rowBlock(overlay, "mcp-github")
            )
            assertExactlyOneRow(overlay, "mcp-github")
            assertTrue(
                "planned MCP rows are appended after the MCA insert block",
                overlay.indexOf("- id: mcp-github") > overlay.indexOf("- insert:")
            )
            assertTrue(
                "MCA keeps regenerating its own rows in the same file",
                overlay.contains("- id: bash-sandbox\n  disabled: true")
            )
            assertTrue(
                "an MCP row must not trip the overlay honesty gate",
                DshComposedConfigInspector.violations(
                    DshComposedConfigInspector.inspect(overlay),
                    requireMcaProvider = false
                ).isEmpty()
            )

            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            val second = overlayFile(config).readText()
            assertEquals("a second reseed must not duplicate or grow the row", EXPECTED_STDIO_ROW, rowBlock(second, "mcp-github"))
            assertExactlyOneRow(second, "mcp-github")
            assertEquals("the whole overlay stays byte-identical on a steady-state reseed", overlay, second)
            assertEquals(listOf("mcp-github"), seeder.lastReport!!.mcpServerRows)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aStreamableHttpServerEmitsUrlAndHeadersWithoutInventingOptionalFields() {
        val root = tempRoot("dsh-mcp-http")
        try {
            val config = payloadTemplateConfig(root)
            val seeder = DshGatewayConfigSeeder(config)
            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            writeMcpConfig(config, HTTP_DECLARATION)

            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            val overlay = overlayFile(config).readText()
            assertEquals(listOf("mcp-remote"), seeder.lastReport!!.mcpServerRows)
            val block = rowBlock(overlay, "mcp-remote")
            assertEquals(EXPECTED_HTTP_ROW, block)
            assertFalse("optional official fields are left at their defaults", block.contains("failOnStartupError"))
            assertFalse("an absent args list must not be emitted as YAML null", block.contains("args:"))
            assertFalse("an absent env map must not be emitted as YAML null", block.contains("env:"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidEntriesAreRefusedWithTypedCodesAndNeverReachTheOverlay() {
        val root = tempRoot("dsh-mcp-invalid")
        try {
            val config = payloadTemplateConfig(root)
            val seeder = DshGatewayConfigSeeder(config)
            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            writeMcpConfig(config, MIXED_VALIDITY_DECLARATION)

            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            val overlay = overlayFile(config).readText()
            assertEquals(
                "only the valid entry may become a row",
                listOf("mcp-ok"),
                seeder.lastReport!!.mcpServerRows
            )
            assertEquals(
                listOf(
                    DshMcpConfigFailureCode.TRANSPORT_UNSUPPORTED,
                    DshMcpConfigFailureCode.MISSING_FIELD,
                    DshMcpConfigFailureCode.MISSING_FIELD,
                    DshMcpConfigFailureCode.ENV_INVALID,
                    DshMcpConfigFailureCode.SERVER_NAME_DUPLICATE,
                    DshMcpConfigFailureCode.UNKNOWN_FIELD
                ),
                seeder.lastReport!!.mcpDiagnostics.map { it.code }
            )
            assertExactlyOneRow(overlay, "mcp-ok")
            assertFalse("a refused entry must not be composed", overlay.contains("bad-env"))
            assertFalse("a foreign key must be refused, not applied", overlay.contains("unknown-key"))
            assertFalse(
                "a refused entry's env value must never be written anywhere",
                overlay.contains("SUPER_SECRET")
            )
            assertFalse(
                "diagnostics must never carry a value read from the file",
                seeder.lastReport!!.mcpDiagnostics.toString().contains("SUPER_SECRET")
            )
            assertTrue(
                "diagnostics locate the entry without echoing its raw name",
                seeder.lastReport!!.mcpDiagnostics.first().location == "servers[0]"
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun serverNameCommandArgumentAndUrlInjectionIsRejectedInsteadOfBecomingYaml() {
        val root = tempRoot("dsh-mcp-injection")
        try {
            val config = payloadTemplateConfig(root)
            val seeder = DshGatewayConfigSeeder(config)
            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            writeMcpConfig(config, INJECTION_DECLARATION)

            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            val overlay = overlayFile(config).readText()
            assertTrue("no injected entry may be composed", seeder.lastReport!!.mcpServerRows.isEmpty())
            assertEquals(
                listOf(
                    DshMcpConfigFailureCode.SERVER_NAME_INVALID,
                    DshMcpConfigFailureCode.COMMAND_INVALID,
                    DshMcpConfigFailureCode.ARG_INVALID,
                    DshMcpConfigFailureCode.URL_INVALID
                ),
                seeder.lastReport!!.mcpDiagnostics.map { it.code }
            )
            assertFalse("no injected row id may appear", overlay.contains("- id: injected"))
            assertFalse("no injected key may appear", overlay.contains("injected:"))
            assertFalse(overlay.contains("dsh-mcp-client"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun wholeFileRefusalsAreTypedAndNeverHalfApplied() {
        val root = tempRoot("dsh-mcp-bounds")
        try {
            val config = payloadTemplateConfig(root)
            val seeder = DshGatewayConfigSeeder(config)
            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            val overlayTarget = overlayFile(config)
            val baseline = overlayTarget.readText()

            assertWholeFileRefusal(config, seeder, "{ not json", DshMcpConfigFailureCode.JSON_INVALID)
            assertWholeFileRefusal(
                config,
                seeder,
                """{"version":3,"servers":[]}""",
                DshMcpConfigFailureCode.SCHEMA_VERSION_UNSUPPORTED
            )
            assertWholeFileRefusal(
                config,
                seeder,
                """{"version":1,"servers":[],"extra":true}""",
                DshMcpConfigFailureCode.UNKNOWN_FIELD
            )
            assertWholeFileRefusal(config, seeder, tooManyServersDeclaration(), DshMcpConfigFailureCode.TOO_MANY_SERVERS)
            assertWholeFileRefusal(
                config,
                seeder,
                oversizedDeclaration(),
                DshMcpConfigFailureCode.FILE_TOO_LARGE
            )
            assertEquals(
                "a refused declaration file must leave the overlay untouched",
                baseline,
                overlayTarget.readText()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun removingAServerDropsItsGeneratedRowWhileAUserWrittenRowIsPreserved() {
        val root = tempRoot("dsh-mcp-removal")
        try {
            val config = payloadTemplateConfig(root)
            val seeder = DshGatewayConfigSeeder(config)
            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            writeMcpConfig(config, TWO_SERVERS_DECLARATION)
            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            val overlayTarget = overlayFile(config)
            assertEquals(listOf("mcp-github", "mcp-remote"), seeder.lastReport!!.mcpServerRows)
            assertExactlyOneRow(overlayTarget.readText(), "mcp-github")
            assertExactlyOneRow(overlayTarget.readText(), "mcp-remote")

            // Another owner's hand-written MCP row: no MCA marker, so the merge must keep it.
            overlayTarget.appendText(USER_MCP_ROW)

            writeMcpConfig(config, STDIO_DECLARATION)
            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            val overlay = overlayTarget.readText()
            assertEquals(listOf("mcp-github"), seeder.lastReport!!.mcpServerRows)
            assertExactlyOneRow(overlay, "mcp-github")
            assertEquals(
                "a removed server must lose its generated row",
                0,
                countRows(overlay, "mcp-remote")
            )
            assertEquals(
                "a hand-written dsh-mcp-client row stays preserved",
                1,
                countRows(overlay, "mcp-local-github")
            )
            assertEquals(listOf("mcp-local-github"), seeder.lastReport!!.preservedUserRows)
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ assertions

    private fun assertWholeFileRefusal(
        config: DshRuntimeConfig,
        seeder: DshGatewayConfigSeeder,
        declaration: String,
        code: DshMcpConfigFailureCode
    ) {
        writeMcpConfig(config, declaration)
        assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
        assertEquals(listOf(code), seeder.lastReport!!.mcpDiagnostics.map { it.code })
        assertTrue(seeder.lastReport!!.mcpServerRows.isEmpty())
        assertTrue(
            "a whole-file refusal composes no MCP row",
            seeder.lastReport!!.managedConflicts.none { it.startsWith("mcp-") }
        )
    }

    private fun assertExactlyOneRow(overlay: String, rowId: String) {
        assertEquals("expected exactly one $rowId row", 1, countRows(overlay, rowId))
    }

    private fun countRows(overlay: String, rowId: String): Int =
        overlay.lines().count { it.trim() == "- id: $rowId" }

    /** The full block of [rowId], from `- id:` up to the next top-level row. */
    private fun rowBlock(overlay: String, rowId: String): String {
        val lines = overlay.lines()
        val start = lines.indexOfFirst { it.trim() == "- id: $rowId" }
        assertTrue("row $rowId must exist", start >= 0)
        val indent = lines[start].indexOf("- id:")
        var end = start + 1
        while (end < lines.size) {
            val line = lines[end]
            val nextRow = line.startsWith(" ".repeat(indent) + "- ")
            val dedented = line.isNotBlank() && line.length <= indent && !line.startsWith(" ")
            if (nextRow || dedented) break
            end++
        }
        return lines.subList(start, end).joinToString("\n").trimEnd()
    }

    // ------------------------------------------------------------------ fixtures

    private fun payloadTemplateConfig(root: File): DshRuntimeConfig {
        val config = DshRuntimeConfig(dataRoot = root).validated()
        val templateRoot = File(config.payloadDir, "dshhome")
        val templateProfile = File(templateRoot, "profiles/web")
        assertTrue(templateProfile.mkdirs() || templateProfile.isDirectory)
        File(templateProfile, "cordis.yml").writeText(CORDIS_TEMPLATE)
        File(templateProfile, "package.json").writeText(PACKAGE_TEMPLATE)
        File(templateProfile, "pnpm-workspace.yaml").writeText(WORKSPACE_TEMPLATE)
        File(templateRoot, "settings.yaml").writeText(SETTINGS_TEMPLATE)
        return config
    }

    private fun overlayFile(config: DshRuntimeConfig): File =
        File(config.homeDir, DshGatewayConfigSeeder.OVERLAY_NAME)

    private fun writeMcpConfig(config: DshRuntimeConfig, declaration: String): File {
        val file = File(config.homeDir, DshMcpServerConfigLoader.FILE_NAME)
        file.parentFile?.mkdirs()
        file.writeText(declaration)
        return file
    }

    private fun tempRoot(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()

    private fun tooManyServersDeclaration(): String = buildString {
        append("""{"version":1,"servers":[""")
        for (index in 0..DshMcpServerConfigLoader.MAX_SERVERS) {
            if (index > 0) append(',')
            append("""{"transport":"stdio","serverName":"s$index","command":"node"}""")
        }
        append("]}")
    }

    /** Valid JSON, but larger than the bound: padding whitespace keeps it parseable if it were read. */
    private fun oversizedDeclaration(): String =
        """{"version":1,"servers":[]}""" + " ".repeat(DshMcpServerConfigLoader.MAX_FILE_BYTES + 1)

    private companion object {
        const val CORDIS_TEMPLATE = "[]\n"

        const val PACKAGE_TEMPLATE = """{
  "name": "dsh-profile-web",
  "private": true,
  "dependencies": {},
  "dsh": {
    "profile": {
      "bundles": [
        "@deepseek-ai/dsh-base",
        "@deepseek-ai/dsh-web-app"
      ]
    }
  }
}
"""

        const val WORKSPACE_TEMPLATE = "packages:\n  - .\n\nnodeLinker: hoisted\n"

        const val SETTINGS_TEMPLATE = "ui-onboarding:\n  welcomeNoticeVersion: 2026-08-13.1\n"

        const val STDIO_DECLARATION = """{
  "version": 1,
  "servers": [
    {
      "transport": "stdio",
      "serverName": "github",
      "command": "node",
      "args": ["/data/user/0/com.muyuchat.mca/files/dsh/workspace/mcp/github.js"],
      "env": { "GITHUB_TOKEN": "from-env" }
    }
  ]
}
"""

        val EXPECTED_STDIO_ROW = buildString {
            appendLine("    - id: mcp-github")
            appendLine("      ${DshMcpOverlayRows.OWNED_ROW_MARKER}")
            appendLine("      name: '@deepseek-ai/dsh-mcp-client'")
            appendLine("      config:")
            appendLine("        transport: stdio")
            appendLine("        serverName: github")
            appendLine("        command: \"node\"")
            appendLine("        args:")
            appendLine("          - \"/data/user/0/com.muyuchat.mca/files/dsh/workspace/mcp/github.js\"")
            appendLine("        env:")
            appendLine("          GITHUB_TOKEN: \"from-env\"")
        }.trimEnd()

        const val HTTP_DECLARATION = """{
  "version": 1,
  "servers": [
    {
      "transport": "streamable-http",
      "serverName": "remote",
      "url": "http://127.0.0.1:8931/mcp",
      "headers": { "Authorization": "Bearer local-token" }
    }
  ]
}
"""

        val EXPECTED_HTTP_ROW = buildString {
            appendLine("    - id: mcp-remote")
            appendLine("      ${DshMcpOverlayRows.OWNED_ROW_MARKER}")
            appendLine("      name: '@deepseek-ai/dsh-mcp-client'")
            appendLine("      config:")
            appendLine("        transport: streamable-http")
            appendLine("        serverName: remote")
            appendLine("        url: \"http://127.0.0.1:8931/mcp\"")
            appendLine("        headers:")
            appendLine("          Authorization: \"Bearer local-token\"")
        }.trimEnd()

        const val MIXED_VALIDITY_DECLARATION = """{
  "version": 1,
  "servers": [
    { "transport": "sse", "serverName": "a", "command": "node" },
    { "transport": "stdio", "serverName": "ok", "command": "node" },
    { "transport": "stdio", "serverName": "missing-command" },
    { "transport": "streamable-http", "serverName": "no-url" },
    { "transport": "stdio", "serverName": "bad-env", "command": "node", "env": { "TOKEN": "SUPER_SECRET\nVALUE" } },
    { "transport": "stdio", "serverName": "ok", "command": "node" },
    { "transport": "stdio", "serverName": "unknown-key", "command": "node", "cwd": "/tmp" }
  ]
}
"""

        const val INJECTION_DECLARATION = """{
  "version": 1,
  "servers": [
    { "transport": "stdio", "serverName": "evil\n- id: injected", "command": "node" },
    { "transport": "stdio", "serverName": "ok1", "command": "node\n- id: injected" },
    { "transport": "stdio", "serverName": "ok2", "command": "node", "args": ["a\nb"] },
    { "transport": "streamable-http", "serverName": "ok3", "url": "http://127.0.0.1:9/mcp\n- id: injected" }
  ]
}
"""

        const val TWO_SERVERS_DECLARATION = """{
  "version": 1,
  "servers": [
    {
      "transport": "stdio",
      "serverName": "github",
      "command": "node",
      "args": ["/data/user/0/com.muyuchat.mca/files/dsh/workspace/mcp/github.js"],
      "env": { "GITHUB_TOKEN": "from-env" }
    },
    {
      "transport": "streamable-http",
      "serverName": "remote",
      "url": "http://127.0.0.1:8931/mcp"
    }
  ]
}
"""

        /** Written by a user/another owner: absolutely no MCA marker, so it must be preserved. */
        const val USER_MCP_ROW = """- id: mcp-local-github
  name: '@deepseek-ai/dsh-mcp-client'
  config:
    transport: stdio
    serverName: localgithub
    command: node
"""
    }
}
