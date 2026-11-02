package com.muyuchat.feature.agent.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun mcpItem(
    id: String,
    name: String = id,
    endpoint: String = "https://example.com/mcp",
    enabled: Boolean = false,
    allowLocal: Boolean = false,
    allowAgentNetwork: Boolean = false
) = AgentMcpServerItem(
    serverId = id,
    displayName = name,
    endpoint = endpoint,
    enabled = enabled,
    allowLocal = allowLocal,
    allowAgentNetwork = allowAgentNetwork
)

class AgentMcpCoordinatorTest {

    private class FakeAdminPort : AgentMcpAdminPort {
        var listResult: AgentMcpAdminListResult = AgentMcpAdminListResult.Loaded(emptyList())
        var addResult: AgentMcpAdminOutcome = AgentMcpAdminOutcome.Added(mcpItem("docs", "Docs"))
        var toggleResult: AgentMcpAdminOutcome? = null
        var networkResult: AgentMcpAdminOutcome = AgentMcpAdminOutcome.Updated(mcpItem("docs", "Docs"))
        var removeResult: AgentMcpAdminOutcome = AgentMcpAdminOutcome.Removed("docs", "Docs")
        var addGate: CompletableDeferred<Unit>? = null
        var throwOnAdd = false
        var throwOnList = false

        val addCalls = mutableListOf<Triple<String, String, Boolean>>()
        val toggleCalls = mutableListOf<Pair<String, Boolean>>()
        val networkCalls = mutableListOf<Pair<String, Boolean>>()
        val removeCalls = mutableListOf<String>()
        var listCalls = 0

        override suspend fun list(): AgentMcpAdminListResult {
            listCalls++
            if (throwOnList) error("boom")
            return listResult
        }

        override suspend fun add(
            displayName: String,
            endpoint: String,
            allowLocal: Boolean,
            bearerToken: String?
        ): AgentMcpAdminOutcome {
            addCalls += Triple(displayName, endpoint, allowLocal)
            addGate?.await()
            if (throwOnAdd) error("boom")
            return addResult
        }

        override suspend fun setEnabled(
            serverId: String,
            enabled: Boolean
        ): AgentMcpAdminOutcome {
            toggleCalls += serverId to enabled
            return toggleResult ?: if (enabled) {
                AgentMcpAdminOutcome.Enabled(mcpItem(serverId, enabled = true))
            } else {
                AgentMcpAdminOutcome.Disabled(mcpItem(serverId, enabled = false))
            }
        }

        override suspend fun remove(serverId: String): AgentMcpAdminOutcome {
            removeCalls += serverId
            return removeResult
        }

        override suspend fun setAgentNetworkAllowed(
            serverId: String,
            allowed: Boolean
        ): AgentMcpAdminOutcome {
            networkCalls += serverId to allowed
            return networkResult
        }
    }

    private class Harness(val port: FakeAdminPort = FakeAdminPort()) {
        val coordinator = AgentMcpCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            port = port
        )

        fun prepareAdd(
            name: String = "Docs",
            endpoint: String = "https://docs.example.com/mcp",
            allowLocal: Boolean = false
        ) {
            coordinator.openAddDialog()
            coordinator.updateAddDisplayName(name)
            coordinator.updateAddEndpoint(endpoint)
            coordinator.updateAddAllowLocal(allowLocal)
        }
    }

    @Test
    fun `open loads servers sorted and projects enabled count`() {
        val harness = Harness()
        harness.port.listResult = AgentMcpAdminListResult.Loaded(
            listOf(
                mcpItem("b", name = "Beta", enabled = true),
                mcpItem("a", name = "Alpha")
            )
        )

        harness.coordinator.open()

        val state = harness.coordinator.state.value
        assertTrue(state.visible)
        assertFalse(state.loading)
        assertEquals(listOf("Alpha", "Beta"), state.servers.map { it.displayName })
        assertEquals(1, state.enabledCount)
    }

    @Test
    fun `list rejection shows only the safe message`() {
        val harness = Harness()
        harness.port.listResult = AgentMcpAdminListResult.Rejected(
            "MCP_STORE_CORRUPT_JSON",
            "MCP 配置已损坏，不会被覆盖。"
        )

        harness.coordinator.open()

        assertEquals("MCP_STORE_CORRUPT_JSON", harness.coordinator.state.value.error?.code)
        assertEquals("MCP 配置已损坏，不会被覆盖。", harness.coordinator.state.value.error?.message)
    }

    @Test
    fun `add dialog submits normalized values and closes on success`() {
        val harness = Harness()
        harness.port.addResult = AgentMcpAdminOutcome.Added(
            mcpItem("docs", "Docs", "https://docs.example.com/mcp")
        )
        harness.coordinator.open()
        harness.prepareAdd(name = "  Docs  ", endpoint = " https://docs.example.com/mcp ")

        harness.coordinator.submitAdd()

        assertEquals(
            listOf(Triple("Docs", "https://docs.example.com/mcp", false)),
            harness.port.addCalls
        )
        assertEquals(listOf("docs"), harness.coordinator.state.value.servers.map { it.serverId })
        assertNull(harness.coordinator.state.value.addDraft)
        assertTrue(harness.coordinator.state.value.notice.orEmpty().contains("已添加"))
    }

    @Test
    fun `blank add draft is rejected locally without calling the port`() {
        val harness = Harness()
        harness.coordinator.open()
        harness.coordinator.openAddDialog()

        harness.coordinator.submitAdd()

        assertTrue(harness.port.addCalls.isEmpty())
        assertEquals(
            "请输入服务器名称。",
            harness.coordinator.state.value.addDraft?.validationMessage
        )
        assertNull(harness.coordinator.state.value.busy)
    }

    @Test
    fun `remote cleartext is rejected locally but explicit local cleartext reaches the port`() {
        val harness = Harness()
        harness.coordinator.open()
        harness.prepareAdd(endpoint = "http://example.com/mcp", allowLocal = false)

        harness.coordinator.submitAdd()
        assertTrue(harness.port.addCalls.isEmpty())
        assertEquals(
            "远程服务器必须使用 HTTPS 地址。",
            harness.coordinator.state.value.addDraft?.validationMessage
        )

        harness.port.addResult = AgentMcpAdminOutcome.Added(
            mcpItem("local", "Local", "http://127.0.0.1:1234/mcp", allowLocal = true)
        )
        harness.prepareAdd(name = "Local", endpoint = "http://127.0.0.1:1234/mcp", allowLocal = true)
        harness.coordinator.submitAdd()

        assertEquals(
            listOf(Triple("Local", "http://127.0.0.1:1234/mcp", true)),
            harness.port.addCalls
        )
        assertEquals("local", harness.coordinator.state.value.servers.single().serverId)
    }

    @Test
    fun `add rejection keeps the draft open with safe validation copy`() {
        val harness = Harness()
        harness.coordinator.open()
        harness.port.addResult = AgentMcpAdminOutcome.Rejected(
            "MCP_ENDPOINT_PRIVATE_DENIED",
            "本地服务器需要明确允许。"
        )
        harness.prepareAdd(endpoint = "https://127.0.0.1:1234/mcp", allowLocal = false)

        harness.coordinator.submitAdd()

        assertEquals(1, harness.port.addCalls.size)
        assertEquals(
            "本地服务器需要明确允许。",
            harness.coordinator.state.value.addDraft?.validationMessage
        )
        assertEquals("MCP_ENDPOINT_PRIVATE_DENIED", harness.coordinator.state.value.error?.code)
        assertNull(harness.coordinator.state.value.busy)
    }

    @Test
    fun `only one operation may be in flight`() {
        val harness = Harness()
        harness.coordinator.open()
        harness.port.addGate = CompletableDeferred()
        harness.port.addResult = AgentMcpAdminOutcome.Added(mcpItem("docs", "Docs"))
        harness.prepareAdd()

        harness.coordinator.submitAdd()

        assertEquals(AgentMcpOperationKind.ADD, harness.coordinator.state.value.busy)
        assertFalse(harness.coordinator.state.value.canAdd)
        harness.coordinator.openAddDialog()
        harness.coordinator.requestRemove("docs")
        assertEquals(1, harness.port.addCalls.size)
        assertNull(harness.coordinator.state.value.removeConfirmation)

        harness.port.addGate?.complete(Unit)
        assertNull(harness.coordinator.state.value.busy)
        assertEquals(listOf("docs"), harness.coordinator.state.value.servers.map { it.serverId })
    }

    @Test
    fun `toggle calls the port and updates the row`() {
        val harness = Harness()
        harness.port.listResult = AgentMcpAdminListResult.Loaded(listOf(mcpItem("docs", "Docs")))
        harness.coordinator.open()
        harness.port.toggleResult = AgentMcpAdminOutcome.Enabled(
            mcpItem("docs", "Docs", enabled = true)
        )

        harness.coordinator.requestSetEnabled("docs", true)

        assertEquals(listOf("docs" to true), harness.port.toggleCalls)
        assertTrue(harness.coordinator.state.value.servers.single().enabled)
        assertTrue(harness.coordinator.state.value.notice.orEmpty().contains("已启用"))
    }

    @Test
    fun `remove requires confirmation and keeps snapshot copy`() {
        val harness = Harness()
        harness.port.listResult = AgentMcpAdminListResult.Loaded(
            listOf(mcpItem("docs", "Docs"))
        )
        harness.coordinator.open()
        harness.port.removeResult = AgentMcpAdminOutcome.Removed("docs", "Docs")

        harness.coordinator.requestRemove("docs")
        assertEquals("Docs", harness.coordinator.state.value.removeConfirmation?.displayName)
        assertTrue(harness.port.removeCalls.isEmpty())

        harness.coordinator.cancelRemove()
        assertNull(harness.coordinator.state.value.removeConfirmation)
        assertTrue(harness.port.removeCalls.isEmpty())

        harness.coordinator.requestRemove("docs")
        harness.coordinator.confirmRemove()

        assertEquals(listOf("docs"), harness.port.removeCalls)
        assertTrue(harness.coordinator.state.value.servers.isEmpty())
        assertTrue(
            harness.coordinator.state.value.notice.orEmpty()
                .contains("运行中的任务保留其工具快照")
        )
    }

    @Test
    fun `unexpected add throwable is mapped to a generic safe error`() {
        val harness = Harness()
        harness.coordinator.open()
        harness.port.throwOnAdd = true
        harness.prepareAdd()

        harness.coordinator.submitAdd()

        assertEquals("MCP_ADMIN_FAILED", harness.coordinator.state.value.error?.code)
        assertEquals(
            "MCP 服务器未能添加，请检查配置后重试。",
            harness.coordinator.state.value.error?.message
        )
    }

    @Test
    fun `dismiss clears status without losing servers`() {
        val harness = Harness()
        harness.port.listResult = AgentMcpAdminListResult.Loaded(listOf(mcpItem("docs")))
        harness.coordinator.open()
        harness.port.addResult = AgentMcpAdminOutcome.Rejected("BAD", "坏配置")
        harness.prepareAdd()
        harness.coordinator.submitAdd()
        assertEquals("坏配置", harness.coordinator.state.value.addDraft?.validationMessage)

        harness.coordinator.dismissStatus()

        assertNull(harness.coordinator.state.value.error)
        assertEquals(1, harness.coordinator.state.value.servers.size)
    }

    @Test
    fun `open is idempotent while visible and refreshes after close`() {
        val harness = Harness()
        harness.coordinator.open()
        harness.coordinator.open()
        assertEquals(1, harness.port.listCalls)

        harness.coordinator.close()
        assertFalse(harness.coordinator.state.value.visible)
        harness.coordinator.open()
        assertEquals(2, harness.port.listCalls)
    }

    @Test
    fun `granting Agent network consent updates the row and notice`() {
        val harness = Harness()
        harness.port.listResult = AgentMcpAdminListResult.Loaded(
            listOf(mcpItem("docs", "Docs"))
        )
        harness.coordinator.open()
        harness.port.networkResult = AgentMcpAdminOutcome.Updated(
            mcpItem("docs", "Docs", allowAgentNetwork = true)
        )

        harness.coordinator.requestSetAgentNetworkAllowed("docs", true)

        assertEquals(listOf("docs" to true), harness.port.networkCalls)
        assertTrue(harness.coordinator.state.value.servers.single().allowAgentNetwork)
        assertTrue(harness.coordinator.state.value.notice.orEmpty().contains("已允许"))
    }

    @Test
    fun `revoking Agent network consent updates the row and notice`() {
        val harness = Harness()
        harness.port.listResult = AgentMcpAdminListResult.Loaded(
            listOf(mcpItem("docs", "Docs", allowAgentNetwork = true))
        )
        harness.coordinator.open()
        harness.port.networkResult = AgentMcpAdminOutcome.Updated(
            mcpItem("docs", "Docs", allowAgentNetwork = false)
        )

        harness.coordinator.requestSetAgentNetworkAllowed("docs", false)

        assertEquals(listOf("docs" to false), harness.port.networkCalls)
        assertFalse(harness.coordinator.state.value.servers.single().allowAgentNetwork)
        assertTrue(harness.coordinator.state.value.notice.orEmpty().contains("已关闭"))
    }

    @Test
    fun `network consent rejection surfaces safe error and leaves consent off`() {
        val harness = Harness()
        harness.port.listResult = AgentMcpAdminListResult.Loaded(
            listOf(mcpItem("docs", "Docs"))
        )
        harness.coordinator.open()
        harness.port.networkResult = AgentMcpAdminOutcome.Rejected(
            "MCP_NETWORK_CONSENT_UNAVAILABLE",
            "网络许可无法保存，请稍后重试。"
        )

        harness.coordinator.requestSetAgentNetworkAllowed("docs", true)

        assertEquals("MCP_NETWORK_CONSENT_UNAVAILABLE", harness.coordinator.state.value.error?.code)
        assertEquals(
            "网络许可无法保存，请稍后重试。",
            harness.coordinator.state.value.error?.message
        )
        assertFalse(harness.coordinator.state.value.servers.single().allowAgentNetwork)
        assertNull(harness.coordinator.state.value.busy)
    }
}
