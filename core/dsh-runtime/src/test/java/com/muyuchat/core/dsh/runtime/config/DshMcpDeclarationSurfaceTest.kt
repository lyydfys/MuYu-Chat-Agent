package com.muyuchat.core.dsh.runtime.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The declared MCP surface must be as honest as the pinned payload.
 *
 * Measured from the packaged payload (not from documentation):
 * - `@deepseek-ai/dsh-mcp-client` registers tools and exposes resource operations;
 * - the shipped `dsh-base` bundle inserts `@deepseek-ai/dsh-mcp-resources`, whose three model
 *   tools call `resources/list`, `resources/templates/list` and `resources/read`;
 * - no payload plugin calls `prompts/list` or `prompts/get`.
 *
 * These tests pin the declaration consequences: `tools`/`resources` are accepted and preserved,
 * `prompts` is refused with a typed code and never written, and the stdio env contract refuses to
 * re-introduce the harness-reserved `DSH_*` namespace while still allowing an explicit credential
 * (the official client deliberately merges the declaration's env over its scrubbed parent env).
 *
 * Scope: JVM declaration validation only. No Node host and no MCP server is started.
 */
class DshMcpDeclarationSurfaceTest {

    @Test
    fun toolsAndResourcesAreAcceptedPreservedAndNeverForwardedToTheOfficialRow() {
        val root = tempRoot("dsh-mcp-capabilities")
        try {
            val home = home(root)
            val store = DshMcpDeclarationStore(home)
            val stdio = DshMcpStdioServer(
                serverName = "github",
                command = "node",
                args = listOf("server.js"),
                env = emptyMap(),
                capabilities = setOf(DshMcpCapability.TOOLS, DshMcpCapability.RESOURCES)
            )
            val http = DshMcpHttpServer(
                serverName = "remote",
                url = "http://127.0.0.1:8931/mcp",
                headers = emptyMap(),
                capabilities = setOf(DshMcpCapability.RESOURCES)
            )
            assertTrue(store.create(stdio) is DshMcpWriteOutcome.Written)
            assertTrue(store.create(http) is DshMcpWriteOutcome.Written)

            assertEquals(listOf(stdio, http), store.read().servers)
            assertTrue(
                "the capability expectation must survive the declaration round-trip",
                store.declarationFile.readText(Charsets.UTF_8).contains("\"resources\"")
            )
            assertEquals(
                "a document using capabilities must declare the capability schema version",
                DshMcpServerConfigLoader.CAPABILITY_SCHEMA_VERSION,
                org.json.JSONObject(store.declarationFile.readText()).getInt("version")
            )

            val overlay = DshMcpOverlayRows.render(store.read().servers)
            assertFalse(
                "the official mcp-client schema has no capabilities key; it must not be forwarded",
                overlay.contains("capabilities")
            )
            assertTrue(overlay.contains("- id: mcp-github"))
            assertTrue(overlay.contains("- id: mcp-remote"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun promptsIsRefusedWithATypedCodeAndNothingIsWritten() {
        val root = tempRoot("dsh-mcp-prompts")
        try {
            val home = home(root)
            val store = DshMcpDeclarationStore(home)
            val ok = DshMcpStdioServer("ok", "node", emptyList(), emptyMap())
            assertTrue(store.create(ok) is DshMcpWriteOutcome.Written)
            val before = store.declarationFile.readBytes()

            val prompts = DshMcpStdioServer(
                serverName = "prompted",
                command = "node",
                args = emptyList(),
                env = emptyMap(),
                capabilities = setOf(DshMcpCapability.TOOLS, DshMcpCapability.PROMPTS)
            )
            assertRefusal(store.create(prompts), DshMcpConfigFailureCode.CAPABILITY_UNSUPPORTED)
            assertTrue(
                "a refused capability must leave the published declaration untouched",
                before.contentEquals(store.declarationFile.readBytes())
            )

            val document = """{
  "version": 2,
  "servers": [
    {
      "transport": "stdio",
      "serverName": "prompted",
      "command": "node",
      "capabilities": ["prompts"]
    }
  ]
}
"""
            assertRefusal(store.importDocument(document), DshMcpConfigFailureCode.CAPABILITY_UNSUPPORTED)
            assertTrue(before.contentEquals(store.declarationFile.readBytes()))
            assertEquals(listOf("ok"), store.read().servers.map { it.serverName })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun capabilityFieldsOnTheLegacySchemaVersionAreRefusedInsteadOfHalfRead() {
        val root = tempRoot("dsh-mcp-capability-version")
        try {
            val home = home(root)
            val store = DshMcpDeclarationStore(home)
            val legacy = """{
  "version": 1,
  "servers": [
    {
      "transport": "stdio",
      "serverName": "alpha",
      "command": "node",
      "capabilities": ["resources"]
    }
  ]
}
"""
            assertRefusal(store.importDocument(legacy), DshMcpConfigFailureCode.SCHEMA_VERSION_UNSUPPORTED)
            assertFalse(store.declarationFile.exists())

            // The same document on the capability schema version is accepted.
            assertTrue(
                store.importDocument(legacy.replace("\"version\": 1", "\"version\": 2"))
                    is DshMcpWriteOutcome.Imported
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun theCapabilityArrayIsBoundedClosedAndDuplicateFree() {
        val root = tempRoot("dsh-mcp-capability-shape")
        try {
            val home = home(root)
            val store = DshMcpDeclarationStore(home)

            assertRefusal(
                store.importDocument(declarationWithCapabilities("""["tools","mystery"]""")),
                DshMcpConfigFailureCode.CAPABILITY_INVALID
            )
            assertRefusal(
                store.importDocument(declarationWithCapabilities("""["tools","tools"]""")),
                DshMcpConfigFailureCode.CAPABILITY_INVALID
            )
            assertRefusal(
                store.importDocument(declarationWithCapabilities("""["tools","resources","prompts","tools"]""")),
                DshMcpConfigFailureCode.CAPABILITY_INVALID
            )
            assertRefusal(
                store.importDocument(declarationWithCapabilities("\"tools\"")),
                DshMcpConfigFailureCode.FIELD_TYPE_INVALID
            )
            assertFalse(
                "no rejected document may be published",
                store.declarationFile.exists()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reservedDshEnvNamesAreRefusedWhileAnExplicitCredentialIsAllowed() {
        val root = tempRoot("dsh-mcp-env-isolation")
        try {
            val home = home(root)
            val store = DshMcpDeclarationStore(home)

            assertRefusal(
                store.create(DshMcpStdioServer("dsh", "node", emptyList(), mapOf("DSH_HOME" to "/tmp"))),
                DshMcpConfigFailureCode.ENV_KEY_RESERVED
            )
            assertRefusal(
                store.create(DshMcpStdioServer("dsh", "node", emptyList(), mapOf("dsh_workspace" to "/tmp"))),
                DshMcpConfigFailureCode.ENV_KEY_RESERVED
            )
            assertFalse(
                "a refused env entry must not be published",
                store.declarationFile.exists()
            )

            val explicitCredential = DshMcpStdioServer(
                serverName = "github",
                command = "node",
                args = emptyList(),
                env = mapOf("GITHUB_TOKEN" to "explicit-value")
            )
            val written = store.create(explicitCredential)
            assertTrue("an explicitly declared credential is the user's decision, got $written", written is DshMcpWriteOutcome.Written)
            assertEquals(listOf(explicitCredential), store.read().servers)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun thePinnedPayloadSurfaceMatrixStatesTheRealBoundary() {
        assertEquals(
            DshMcpCapabilitySupport.SERVED,
            DshMcpPinnedPayloadSurface.supportOf(DshMcpCapability.TOOLS)
        )
        assertEquals(
            DshMcpCapabilitySupport.SERVED,
            DshMcpPinnedPayloadSurface.supportOf(DshMcpCapability.RESOURCES)
        )
        assertEquals(
            DshMcpCapabilitySupport.BLOCKED,
            DshMcpPinnedPayloadSurface.supportOf(DshMcpCapability.PROMPTS)
        )
        assertTrue(DshMcpCapability.TOOLS.servedByPinnedPayload)
        assertTrue(DshMcpCapability.RESOURCES.servedByPinnedPayload)
        assertFalse(DshMcpCapability.PROMPTS.servedByPinnedPayload)

        assertEquals(
            listOf("list_mcp_resources", "list_mcp_resource_templates", "read_mcp_resource"),
            DshMcpPinnedPayloadSurface.resourceTools
        )
        assertTrue(DshMcpPinnedPayloadSurface.stdioEnvironment.inheritsHostEnvironment)
        assertTrue(DshMcpPinnedPayloadSurface.stdioEnvironment.declarationEnvMergedLast)
        assertFalse(
            "the payload inherits a scrubbed parent environment, not an explicit allowlist",
            DshMcpPinnedPayloadSurface.stdioEnvironment.explicitAllowlist
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun assertRefusal(outcome: DshMcpWriteOutcome, code: DshMcpConfigFailureCode) {
        assertTrue("expected a Refused($code), got $outcome", outcome is DshMcpWriteOutcome.Refused)
        assertEquals(listOf(code), (outcome as DshMcpWriteOutcome.Refused).diagnostics.map { it.code })
    }

    private fun declarationWithCapabilities(capabilitiesJson: String): String = """{
  "version": 2,
  "servers": [
    {
      "transport": "stdio",
      "serverName": "alpha",
      "command": "node",
      "capabilities": $capabilitiesJson
    }
  ]
}
"""

    private fun home(root: File): File =
        File(root, "home").also { assertTrue(it.mkdirs() || it.isDirectory) }

    private fun tempRoot(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()
}
