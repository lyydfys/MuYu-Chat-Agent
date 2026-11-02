package com.muyuchat.feature.agent.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMcpProjectionTest {

    private fun item(
        id: String,
        name: String = id,
        endpoint: String = "https://example.com/mcp",
        enabled: Boolean = false,
        allowLocal: Boolean = false,
        statusLabel: String? = null,
        allowAgentNetwork: Boolean = false
    ) = AgentMcpServerItem(
        serverId = id,
        displayName = name,
        endpoint = endpoint,
        enabled = enabled,
        allowLocal = allowLocal,
        statusLabel = statusLabel,
        allowAgentNetwork = allowAgentNetwork
    )

    @Test
    fun `projects name endpoint enabled local flag and status`() {
        val projected = projectMcpServer(
            item("docs", name = "Docs", endpoint = "https://docs.example.com/mcp", allowLocal = true)
        )
        assertEquals("Docs", projected.displayName)
        assertEquals("https://docs.example.com/mcp", projected.endpoint)
        assertFalse(projected.enabled)
        assertTrue(projected.allowLocal)
        assertEquals("已停用", projected.statusLabel)

        val custom = projectMcpServer(item("a", enabled = true, statusLabel = "已连接"))
        assertEquals("已连接", custom.statusLabel)
    }

    @Test
    fun `agent network consent defaults off and projects an explicit grant`() {
        assertFalse(projectMcpServer(item("off")).allowAgentNetwork)
        assertTrue(projectMcpServer(item("on", allowAgentNetwork = true)).allowAgentNetwork)
    }

    @Test
    fun `enabled count empty loading and busy flags are derived`() {
        val state = projectMcpState(
            AgentMcpProjectionInput(
                servers = listOf(
                    item("b", name = "Beta", enabled = true),
                    item("a", name = "Alpha", enabled = false)
                )
            )
        )
        assertEquals(listOf("Alpha", "Beta"), state.servers.map { it.displayName })
        assertEquals(1, state.enabledCount)
        assertFalse(state.empty)
        assertTrue(state.canAdd)
        assertTrue(state.canToggle())
        assertTrue(state.canRemove())

        val loading = projectMcpState(AgentMcpProjectionInput(loading = true))
        assertTrue(loading.loading)
        assertFalse(loading.empty)

        val empty = projectMcpState(AgentMcpProjectionInput())
        assertTrue(empty.empty)
        assertTrue(empty.canAdd)

        val busy = projectMcpState(
            AgentMcpProjectionInput(
                servers = listOf(item("a")),
                busy = AgentMcpOperationKind.ADD,
                addDraft = AgentMcpAddDraftUi()
            )
        )
        assertFalse(busy.canAdd)
        assertFalse(busy.canToggle())
        assertFalse(busy.canRemove())
    }

    @Test
    fun `add draft validation rejects blank and unsafe forms`() {
        assertEquals("请输入服务器名称。", mcpDraftValidationError("", "https://example.com/mcp", false))
        assertEquals(
            "请输入 MCP 端点地址。",
            mcpDraftValidationError("Server", " ", false)
        )
        assertEquals(
            "远程服务器必须使用 HTTPS 地址。",
            mcpDraftValidationError("Server", "http://example.com/mcp", false)
        )
        assertTrue(
            mcpDraftValidationError("Server", "ftp://example.com/mcp", false)
                .orEmpty()
                .contains("https://")
        )
        assertEquals(
            "名称不能超过 128 个字符。",
            mcpDraftValidationError("n".repeat(129), "https://example.com/mcp", false)
        )
        assertEquals(
            "名称只能是一行，不能包含控制字符。",
            mcpDraftValidationError("bad\nname", "https://example.com/mcp", false)
        )
    }

    @Test
    fun `local server allows explicit http loopback but still requires a scheme`() {
        assertNull(
            mcpDraftValidationError("Local", "http://127.0.0.1:1234/mcp", allowLocal = true)
        )
        assertNull(
            mcpDraftValidationError("Remote", "https://example.com/mcp", allowLocal = false)
        )
        assertEquals(
            "端点必须以 https:// 开头（本地服务器可使用 http://）。",
            mcpDraftValidationError("Local", "127.0.0.1:1234/mcp", allowLocal = true)
        )
    }

    @Test
    fun `visible MCP surface wins routing and skills remains reachable`() {
        val mcp = AgentMcpUiState(visible = true, servers = listOf(projectMcpServer(item("a"))))
        val mcpState = projectWorkspaceState(projectionInput(mcpState = mcp))
        assertEquals(AgentWorkspaceSurface.MCP, mcpState.surface)
        assertEquals(listOf("a"), mcpState.mcp.servers.map { it.serverId })

        val skills = projectWorkspaceState(
            projectionInput(skillsState = AgentSkillsUiState(visible = true))
        )
        assertEquals(AgentWorkspaceSurface.SKILLS, skills.surface)

        assertEquals(AgentWorkspaceSurface.HOME, projectWorkspaceState(projectionInput()).surface)
    }

    @Test
    fun `remove confirmation is projected with the display name`() {
        val state = projectMcpState(
            AgentMcpProjectionInput(
                servers = listOf(item("a", name = "Alpha")),
                removeConfirmation = AgentMcpRemoveConfirmationUi("a", "Alpha")
            )
        )
        assertEquals("Alpha", state.removeConfirmation?.displayName)
    }
}
