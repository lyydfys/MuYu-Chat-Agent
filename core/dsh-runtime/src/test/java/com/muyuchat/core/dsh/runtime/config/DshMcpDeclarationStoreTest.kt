package com.muyuchat.core.dsh.runtime.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D14 MCP write port: `<DSH_HOME>/mcp-servers.json` already had a typed reader and an official
 * `@deepseek-ai/dsh-mcp-client` overlay renderer, but no way for a user-facing surface to declare a
 * server. This fixture pins the write half:
 *
 *  - create/update/remove round-trip through the **production reader** and preserve entry order;
 *  - an invalid value is refused with the reader's own typed code and never touches the file;
 *  - an existing declaration that the reader refuses is never silently rewritten (no dropped
 *    entries, no second format);
 *  - bounds are enforced by re-reading the exact document that would be published;
 *  - the same-directory publish is atomic and leaves no temp file, and an identical rewrite is a
 *    byte-identical no-op;
 *  - the next seed regenerates the overlay from the new declaration exactly once, removes the row
 *    of a removed server, and keeps a hand-written user row;
 *  - diagnostics never echo a command/env/URL/header value.
 *
 * Scope: JVM. No Node host and no MCP server is started, so this proves the declaration/composition
 * contract, never a real transport/handshake (device MCP connect remains NOT_RUN).
 */
class DshMcpDeclarationStoreTest {

    @Test
    fun createUpdateAndRemoveRoundTripThroughTheProductionReader() {
        val root = tempRoot("dsh-mcp-store-roundtrip")
        try {
            val home = File(root, "home").also { assertTrue(it.mkdirs() || it.isDirectory) }
            val store = DshMcpDeclarationStore(home)

            val stdio = DshMcpStdioServer(
                serverName = "github",
                command = "node",
                args = listOf("/data/user/0/com.muyuchat.mca/files/dsh/workspace/mcp/github.js"),
                env = mapOf("GITHUB_TOKEN" to "from-env")
            )
            val http = DshMcpHttpServer(
                serverName = "remote",
                url = "http://127.0.0.1:8931/mcp",
                headers = mapOf("Authorization" to "Bearer local-token")
            )

            assertTrue(store.create(stdio) is DshMcpWriteOutcome.Written)
            assertEquals(listOf(stdio), store.read().servers)
            assertTrue(store.read().diagnostics.isEmpty())
            assertEquals(
                "the declaration must stay on schema version 1",
                DshMcpServerConfigLoader.SCHEMA_VERSION,
                org.json.JSONObject(store.declarationFile.readText()).getInt("version")
            )

            assertTrue(store.create(http) is DshMcpWriteOutcome.Written)
            assertEquals(listOf(stdio, http), store.read().servers)

            val updated = stdio.copy(args = listOf("/workspace/mcp/github.js", "--stdio"))
            assertTrue(store.update(updated) is DshMcpWriteOutcome.Written)
            assertEquals(
                "update replaces in place and keeps the other entry",
                listOf(updated, http),
                store.read().servers
            )

            val bytesBeforeNoOp = store.declarationFile.readBytes()
            assertTrue(store.update(updated) is DshMcpWriteOutcome.Written)
            assertTrue(
                "an identical update must be a byte-identical no-op",
                bytesBeforeNoOp.contentEquals(store.declarationFile.readBytes())
            )
            assertNoTempFiles(home)

            assertTrue(store.remove("github") is DshMcpWriteOutcome.Written)
            assertEquals(listOf(http), store.read().servers)
            val unknownRemoval = store.remove("github")
            assertTrue(unknownRemoval is DshMcpWriteOutcome.Refused)
            assertEquals(
                listOf(DshMcpConfigFailureCode.MISSING_FIELD),
                (unknownRemoval as DshMcpWriteOutcome.Refused).diagnostics.map { it.code }
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidInputIsRefusedWithTheReaderCodeAndTheFileIsUntouched() {
        val root = tempRoot("dsh-mcp-store-invalid")
        try {
            val home = File(root, "home").also { assertTrue(it.mkdirs() || it.isDirectory) }
            val store = DshMcpDeclarationStore(home)
            assertTrue(
                store.create(DshMcpStdioServer("ok", "node", emptyList(), emptyMap()))
                    is DshMcpWriteOutcome.Written
            )
            val before = store.declarationFile.readBytes()

            assertRefusal(
                store.create(DshMcpStdioServer("blank", "   ", emptyList(), emptyMap())),
                DshMcpConfigFailureCode.COMMAND_INVALID
            )
            assertRefusal(
                store.create(DshMcpStdioServer("bad name", "node", emptyList(), emptyMap())),
                DshMcpConfigFailureCode.SERVER_NAME_INVALID
            )
            assertRefusal(
                store.create(DshMcpStdioServer("control", "node\nsecond", emptyList(), emptyMap())),
                DshMcpConfigFailureCode.COMMAND_INVALID
            )
            assertRefusal(
                store.create(DshMcpHttpServer("badurl", "ftp://127.0.0.1/x", emptyMap())),
                DshMcpConfigFailureCode.URL_INVALID
            )
            assertRefusal(
                store.create(
                    DshMcpStdioServer("badenv", "node", emptyList(), mapOf("TOKEN" to "SUPER_SECRET\nVALUE"))
                ),
                DshMcpConfigFailureCode.ENV_INVALID
            )
            assertRefusal(
                store.update(DshMcpStdioServer("missing", "node", emptyList(), emptyMap())),
                DshMcpConfigFailureCode.MISSING_FIELD
            )
            assertRefusal(
                store.remove("bad name"),
                DshMcpConfigFailureCode.SERVER_NAME_INVALID
            )

            assertTrue(
                "a refused write must never change the published document",
                before.contentEquals(store.declarationFile.readBytes())
            )
            assertEquals(
                "the existing valid entry must survive every refusal",
                listOf("ok"),
                store.read().servers.map { it.serverName }
            )
            assertFalse(
                "a refused env value must never appear in a diagnostic",
                store.create(
                    DshMcpStdioServer("badenv2", "node", emptyList(), mapOf("TOKEN" to "SUPER_SECRET\nVALUE"))
                ).toString().contains("SUPER_SECRET")
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun anExistingDeclarationTheReaderRefusesIsNeverOverwritten() {
        val root = tempRoot("dsh-mcp-store-existing-invalid")
        try {
            val home = File(root, "home").also { assertTrue(it.mkdirs() || it.isDirectory) }
            val store = DshMcpDeclarationStore(home)

            val invalidJson = "{ not json"
            store.declarationFile.writeText(invalidJson)
            assertRefusal(
                store.create(DshMcpStdioServer("ok", "node", emptyList(), emptyMap())),
                DshMcpConfigFailureCode.JSON_INVALID
            )
            assertEquals(invalidJson, store.declarationFile.readText())

            val unknownKey = """{"version":1,"servers":[],"extra":true}"""
            store.declarationFile.writeText(unknownKey)
            assertRefusal(
                store.create(DshMcpStdioServer("ok", "node", emptyList(), emptyMap())),
                DshMcpConfigFailureCode.UNKNOWN_FIELD
            )
            assertEquals(unknownKey, store.declarationFile.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun hardBoundsAreEnforcedByReReadingTheExactDocument() {
        val root = tempRoot("dsh-mcp-store-bounds")
        try {
            val home = File(root, "home").also { assertTrue(it.mkdirs() || it.isDirectory) }
            val store = DshMcpDeclarationStore(home)

            for (index in 0 until DshMcpServerConfigLoader.MAX_SERVERS) {
                assertTrue(
                    "server $index must be accepted",
                    store.create(DshMcpStdioServer("s$index", "node", emptyList(), emptyMap()))
                        is DshMcpWriteOutcome.Written
                )
            }
            assertRefusal(
                store.create(DshMcpStdioServer("one-too-many", "node", emptyList(), emptyMap())),
                DshMcpConfigFailureCode.TOO_MANY_SERVERS
            )
            assertEquals(
                DshMcpServerConfigLoader.MAX_SERVERS,
                store.read().servers.size
            )

            // A max-size entry still fits the 64 KiB document bound; a second one does not.
            val almostTooBig = DshMcpStdioServer(
                serverName = "s0",
                command = "node",
                args = List(DshMcpServerConfigLoader.MAX_ARGS) { "a".repeat(DshMcpServerConfigLoader.MAX_ARG_CHARS) },
                env = (0 until DshMcpServerConfigLoader.MAX_ENV_ENTRIES).associate {
                    "V$it" to "b".repeat(DshMcpServerConfigLoader.MAX_ENV_VALUE_CHARS)
                }
            )
            assertTrue(
                "one max-size entry still fits the document bound",
                store.update(almostTooBig) is DshMcpWriteOutcome.Written
            )
            val oversized = almostTooBig.copy(
                serverName = "s1",
                args = List(DshMcpServerConfigLoader.MAX_ARGS) {
                    "c".repeat(DshMcpServerConfigLoader.MAX_ARG_CHARS)
                },
                env = emptyMap()
            )
            assertRefusal(
                store.update(oversized),
                DshMcpConfigFailureCode.FILE_TOO_LARGE
            )
            assertEquals(
                "a refused oversized document must leave the previous one valid",
                DshMcpServerConfigLoader.MAX_SERVERS,
                store.read().servers.size
            )
            assertEquals("s0", store.read().servers.first().serverName)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aForeignPathAtTheDeclarationTargetIsRefused() {
        val root = tempRoot("dsh-mcp-store-shape")
        try {
            val home = File(root, "home").also { assertTrue(it.mkdirs() || it.isDirectory) }
            assertTrue(File(home, DshMcpServerConfigLoader.FILE_NAME).mkdirs())
            val store = DshMcpDeclarationStore(home)
            val outcome = store.create(DshMcpStdioServer("ok", "node", emptyList(), emptyMap()))
            assertTrue("a directory at the target must be refused, got $outcome", outcome is DshMcpWriteOutcome.IoFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun theNextSeedRegeneratesTheOverlayOnceAndDropsOnlyTheRemovedRow() {
        val root = tempRoot("dsh-mcp-store-overlay")
        try {
            val config = payloadTemplateConfig(root)
            val seeder = DshGatewayConfigSeeder(config)
            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            val store = DshMcpDeclarationStore(config.homeDir)
            val overlayFile = File(config.homeDir, DshGatewayConfigSeeder.OVERLAY_NAME)

            val github = DshMcpStdioServer(
                serverName = "github",
                command = "node",
                args = listOf("/workspace/mcp/github.js"),
                env = mapOf("GITHUB_TOKEN" to "from-env")
            )
            val remote = DshMcpHttpServer(
                serverName = "remote",
                url = "http://127.0.0.1:8931/mcp",
                headers = emptyMap()
            )
            assertTrue(store.create(github) is DshMcpWriteOutcome.Written)
            assertTrue(store.create(remote) is DshMcpWriteOutcome.Written)

            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            val overlay = overlayFile.readText()
            assertEquals(listOf("mcp-github", "mcp-remote"), seeder.lastReport!!.mcpServerRows)
            assertTrue(seeder.lastReport!!.mcpDiagnostics.isEmpty())
            assertExactlyOneRow(overlay, "mcp-github")
            assertExactlyOneRow(overlay, "mcp-remote")
            assertTrue(overlay.contains("""    command: "node""""))
            assertTrue(overlay.contains("""      GITHUB_TOKEN: "from-env""""))

            val steady = overlayFile.readText()
            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            assertEquals("a steady-state reseed must not change a byte", steady, overlayFile.readText())

            // A hand-written official row from another owner must not be touched by any of this.
            overlayFile.appendText(USER_MCP_ROW)
            val updatedGithub = github.copy(args = listOf("/workspace/mcp/github-v2.js"))
            assertTrue(store.update(updatedGithub) is DshMcpWriteOutcome.Written)
            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            val afterUpdate = overlayFile.readText()
            assertEquals(listOf("mcp-github", "mcp-remote"), seeder.lastReport!!.mcpServerRows)
            assertExactlyOneRow(afterUpdate, "mcp-github")
            assertTrue(afterUpdate.contains("\"/workspace/mcp/github-v2.js\""))
            assertFalse(afterUpdate.contains("\"/workspace/mcp/github.js\""))
            assertExactlyOneRow(afterUpdate, "mcp-local-github")
            assertEquals(listOf("mcp-local-github"), seeder.lastReport!!.preservedUserRows)

            // Removing one server drops only its generated row; ownership is re-evaluated on seed.
            assertTrue(store.remove("remote") is DshMcpWriteOutcome.Written)
            assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
            val afterRemoval = overlayFile.readText()
            assertEquals(listOf("mcp-github"), seeder.lastReport!!.mcpServerRows)
            assertExactlyOneRow(afterRemoval, "mcp-github")
            assertEquals("the removed server must lose its row", 0, countRows(afterRemoval, "mcp-remote"))
            assertEquals(1, countRows(afterRemoval, "mcp-local-github"))
            assertEquals(
                "a reseed must not duplicate rows after a declaration change",
                afterRemoval,
                run {
                    assertTrue(seeder.seedAccountOnly() is DshSeedOutcome.Seeded)
                    overlayFile.readText()
                }
            )
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun assertRefusal(outcome: DshMcpWriteOutcome, code: DshMcpConfigFailureCode) {
        assertTrue("expected a Refused($code), got $outcome", outcome is DshMcpWriteOutcome.Refused)
        assertEquals(listOf(code), (outcome as DshMcpWriteOutcome.Refused).diagnostics.map { it.code })
    }

    private fun assertExactlyOneRow(overlay: String, rowId: String) {
        assertEquals("expected exactly one $rowId row", 1, countRows(overlay, rowId))
    }

    private fun countRows(overlay: String, rowId: String): Int =
        overlay.lines().count { it.trim() == "- id: $rowId" }

    private fun assertNoTempFiles(home: File) {
        val temps = home.listFiles()?.filter { it.name.endsWith(".mca-tmp") }.orEmpty()
        assertTrue("a publish must not leave a temp file: $temps", temps.isEmpty())
    }

    private fun payloadTemplateConfig(root: File): DshRuntimeConfig {
        val config = DshRuntimeConfig(dataRoot = root).validated()
        val templateRoot = File(config.payloadDir, "dshhome")
        val templateProfile = File(templateRoot, "profiles/web")
        assertTrue(templateProfile.mkdirs() || templateProfile.isDirectory)
        File(templateProfile, "cordis.yml").writeText("[]\n")
        File(templateProfile, "package.json").writeText(PACKAGE_TEMPLATE)
        File(templateProfile, "pnpm-workspace.yaml").writeText(WORKSPACE_TEMPLATE)
        File(templateRoot, "settings.yaml").writeText(SETTINGS_TEMPLATE)
        return config
    }

    private fun tempRoot(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()

    private companion object {
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

        /** Hand-written by a user/another owner: no MCA marker, so the merge must preserve it. */
        const val USER_MCP_ROW = """- id: mcp-local-github
  name: '@deepseek-ai/dsh-mcp-client'
  config:
    transport: stdio
    serverName: localgithub
    command: node
"""
    }

    @Test
    fun importDocumentReplacesTheWholeListOnlyWhenTheReaderAcceptsIt() {
        val root = tempRoot("dsh-mcp-store-import")
        try {
            val home = File(root, "home").also { assertTrue(it.mkdirs() || it.isDirectory) }
            val store = DshMcpDeclarationStore(home)
            val stdio = DshMcpStdioServer(serverName = "alpha", command = "node", args = listOf("a.js"), env = emptyMap())
            val http = DshMcpHttpServer(serverName = "beta", url = "http://127.0.0.1:8932/mcp", headers = emptyMap())
            assertTrue(store.create(stdio) is DshMcpWriteOutcome.Written)
            assertTrue(store.create(http) is DshMcpWriteOutcome.Written)
            val twoServers = store.declarationFile.readText(Charsets.UTF_8)

            // Remove the file so the import really publishes a fresh declaration.
            assertTrue(store.declarationFile.delete())
            val imported = store.importDocument(twoServers)
            assertTrue("a reader-accepted document must import", imported is DshMcpWriteOutcome.Imported)
            assertEquals(2, (imported as DshMcpWriteOutcome.Imported).serverCount)
            assertEquals(listOf(stdio, http), store.read().servers)
            assertNoTempFiles(home)

            // An invalid document changes nothing.
            val before = store.declarationFile.readBytes()
            val refused = store.importDocument("{\"version\":1,\"servers\":[{\"name\":\"bad\"}]}")
            assertTrue(refused is DshMcpWriteOutcome.Refused)
            assertTrue(before.contentEquals(store.declarationFile.readBytes()))

            // An already-invalid declaration is never silently replaced by an import.
            store.declarationFile.writeText("{not json", Charsets.UTF_8)
            val invalidHome = store.importDocument(twoServers)
            assertTrue(invalidHome is DshMcpWriteOutcome.Refused)
            assertEquals("{not json", store.declarationFile.readText(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }

}
