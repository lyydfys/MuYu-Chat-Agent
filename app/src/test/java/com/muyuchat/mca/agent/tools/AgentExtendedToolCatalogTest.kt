package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolSideEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentExtendedToolCatalogTest {
    private val port = FakeAgentWorkspaceFilePort()

    @Test
    fun `extended catalog keeps the v1 surface and adds the workspace write tools`() {
        val catalog = extendedAgentToolCatalog(port)

        assertEquals(
            listOf(
                "artifact.write_text",
                "device.info",
                "workspace.edit_text",
                "workspace.list",
                "workspace.read_text",
                "workspace.search_text",
                "workspace.write_text"
            ),
            catalog.snapshot().map { it.toolKey }
        )
        catalog.snapshot().forEach { descriptor ->
            assertEquals(64, descriptor.schemaHash.length)
            assertEquals(AgentToolJson.sha256Hex(descriptor.schemaJson), descriptor.schemaHash)
            assertTrue(descriptor.schemaJson.contains("\"additionalProperties\":false"))
            assertNotNull(catalog.find(descriptor.toolKey))
        }
    }

    @Test
    fun `http and terminal tools are only registered when their dependency exists`() {
        val base = extendedAgentToolCatalog(port)
        assertNull(base.find("http.get"))
        assertNull(base.find("terminal.exec"))

        val withHttp = extendedAgentToolCatalog(port, httpClient = FakeHttpTextClient())
        assertNotNull(withHttp.find("http.get"))
        assertNull(withHttp.find("terminal.exec"))
        assertEquals(
            AgentToolSideEffect.NETWORK,
            withHttp.find("http.get")!!.descriptor.sideEffect
        )

        val withTerminal = extendedAgentToolCatalog(
            port,
            terminalSandbox = FakeTerminalSandbox()
        )
        assertNotNull(withTerminal.find("terminal.exec"))
        assertEquals(
            AgentToolSideEffect.EXTERNAL_SIDE_EFFECT,
            withTerminal.find("terminal.exec")!!.descriptor.sideEffect
        )

        val unavailable = extendedAgentToolCatalog(
            port,
            terminalSandbox = FakeTerminalSandbox(available = false)
        )
        assertNull(unavailable.find("terminal.exec"))
    }

    @Test
    fun `the frozen v1 catalog is unchanged for existing callers`() {
        val catalog = builtInAgentToolCatalog(port)

        assertEquals(4, catalog.snapshot().size)
        assertEquals(
            listOf(
                "artifact.write_text",
                "workspace.list",
                "workspace.read_text",
                "workspace.search_text"
            ),
            catalog.snapshot().map { it.toolKey }
        )
        assertEquals(4, builtInAgentToolExecutors(port).size)
    }

    @Test
    fun `write text descriptors declare a workspace write effect`() {
        val catalog = extendedAgentToolCatalog(port)

        assertEquals(
            AgentToolSideEffect.WORKSPACE_WRITE,
            catalog.find("workspace.write_text")!!.descriptor.sideEffect
        )
        assertEquals(
            AgentToolSideEffect.WORKSPACE_WRITE,
            catalog.find("workspace.edit_text")!!.descriptor.sideEffect
        )
    }
}
