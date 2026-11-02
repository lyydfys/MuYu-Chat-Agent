package com.muyuchat.mca.agent.extensions.mcp

import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentMcpServerStoreTest {
    private lateinit var filesDir: File
    private lateinit var store: AgentMcpServerStore

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("mcp-store-test").toFile()
        store = AgentMcpServerStore(filesDir)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `roundtrip persists config without any token field`() {
        val added = store.add(record("alpha", "http://127.0.0.1:1234/mcp", allowLocal = true))
        assertTrue(added is AgentMcpServerMutation.Applied)
        val loaded = store.load()
        assertTrue(loaded is AgentMcpServerLoad.Loaded)
        val servers = (loaded as AgentMcpServerLoad.Loaded).servers
        assertEquals(1, servers.size)
        assertEquals("alpha", servers.single().serverId)
        assertTrue(servers.single().allowLocal)
        assertTrue(servers.single().enabled)

        val reopened = AgentMcpServerStore(filesDir).load()
        assertEquals(servers, (reopened as AgentMcpServerLoad.Loaded).servers)
        assertTrue(File(store.path.parentFile, "servers.json.tmp").let { !it.exists() })
        val text = store.path.readText(Charsets.UTF_8)
        assertFalse(text.contains("token", ignoreCase = true))
        assertFalse(text.contains("bearer", ignoreCase = true))
        assertFalse(text.contains("authorization", ignoreCase = true))
    }

    @Test
    fun `duplicate server id is rejected and the file is unchanged`() {
        assertTrue(store.add(record("alpha", "https://example.com/mcp")) is AgentMcpServerMutation.Applied)
        val before = store.path.readText(Charsets.UTF_8)
        val duplicate = store.add(record("alpha", "https://other.example.com/mcp"))
        assertTrue(duplicate is AgentMcpServerMutation.Rejected)
        assertEquals("MCP_DUPLICATE_SERVER_ID", (duplicate as AgentMcpServerMutation.Rejected).code)
        assertEquals(before, store.path.readText(Charsets.UTF_8))
    }

    @Test
    fun `endpoint policy rejects remote cleartext and accepts explicit loopback`() {
        val remoteCleartext = store.add(record("remote-http", "http://example.com/mcp"))
        assertTrue(remoteCleartext is AgentMcpServerMutation.Rejected)
        assertEquals("MCP_ENDPOINT_CLEARTEXT_DENIED", (remoteCleartext as AgentMcpServerMutation.Rejected).code)

        val localWithoutFlag = store.add(record("local-no-flag", "http://127.0.0.1:1234/mcp"))
        assertTrue(localWithoutFlag is AgentMcpServerMutation.Rejected)
        assertEquals("MCP_ENDPOINT_PRIVATE_DENIED", (localWithoutFlag as AgentMcpServerMutation.Rejected).code)

        val loopback = store.add(record("local", "http://127.0.0.1:1234/mcp", allowLocal = true))
        assertTrue(loopback is AgentMcpServerMutation.Applied)
        assertEquals(1, (loopback as AgentMcpServerMutation.Applied).servers.size)
    }

    @Test
    fun `oversized store file is fenced`() {
        store.path.parentFile.mkdirs()
        store.path.writeText("x".repeat(AgentMcpServerStore.MAX_FILE_BYTES + 1), Charsets.UTF_8)
        assertEquals("MCP_STORE_TOO_LARGE", (store.load() as AgentMcpServerLoad.Corrupt).reasonCode)
    }

    @Test
    fun `credentials fragments and non http schemes are rejected`() {
        listOf(
            "https://user:secret@example.com/mcp" to "MCP_ENDPOINT_EMBEDS_CREDENTIALS",
            "https://example.com/mcp#frag" to "MCP_ENDPOINT_FRAGMENT",
            "https://example.com/mcp?access_token=secret" to "MCP_ENDPOINT_QUERY_DENIED",
            "ws://example.com/mcp" to "MCP_ENDPOINT_SCHEME",
            "https://" to "MCP_ENDPOINT_HOST",
            "https://example.com/" + "a".repeat(2_100) to "MCP_ENDPOINT_TOO_LONG"
        ).forEach { (endpoint, code) ->
            val result = store.add(record("srv", endpoint))
            assertTrue("$endpoint must be rejected", result is AgentMcpServerMutation.Rejected)
            assertEquals(code, (result as AgentMcpServerMutation.Rejected).code)
        }
    }

    @Test
    fun `corrupt store is reported and never overwritten`() {
        store.path.parentFile.mkdirs()
        store.path.writeText("{not json", Charsets.UTF_8)
        val corruptBytes = store.path.readBytes()

        val loaded = store.load()
        assertTrue(loaded is AgentMcpServerLoad.Corrupt)
        val add = store.add(record("alpha", "https://example.com/mcp"))
        assertTrue(add is AgentMcpServerMutation.Rejected)
        assertEquals("MCP_STORE_CORRUPT_JSON", (add as AgentMcpServerMutation.Rejected).code)
        assertTrue(corruptBytes.contentEquals(store.path.readBytes()))
    }

    @Test
    fun `unknown schema version and duplicate persisted ids are fenced`() {
        store.path.parentFile.mkdirs()
        store.path.writeText("""{"schemaVersion":9,"servers":[]}""", Charsets.UTF_8)
        assertTrue(store.load() is AgentMcpServerLoad.Corrupt)

        store.path.writeText(
            """{"schemaVersion":1,"servers":[""" +
                """{"serverId":"alpha","displayName":"Alpha","endpoint":"https://example.com/mcp","enabled":true},""" +
                """{"serverId":"alpha","displayName":"Alpha 2","endpoint":"https://example.com/mcp2","enabled":true}]}""",
            Charsets.UTF_8
        )
        assertEquals("MCP_STORE_DUPLICATE_ID", (store.load() as AgentMcpServerLoad.Corrupt).reasonCode)
    }

    @Test
    fun `enabled flag and removal roundtrip`() {
        store.add(record("alpha", "https://example.com/mcp"))
        val disabled = store.setEnabled("alpha", false)
        assertTrue(disabled is AgentMcpServerMutation.Applied)
        assertFalse((disabled as AgentMcpServerMutation.Applied).servers.single().enabled)
        assertEquals(false, (AgentMcpServerStore(filesDir).load() as AgentMcpServerLoad.Loaded).servers.single().enabled)

        val removed = store.remove("alpha")
        assertTrue(removed is AgentMcpServerMutation.Applied)
        assertTrue((store.load() as AgentMcpServerLoad.Loaded).servers.isEmpty())
        assertTrue(store.remove("alpha") is AgentMcpServerMutation.Rejected)
    }

    @Test
    fun `server count is bounded`() {
        repeat(AgentMcpServerStore.MAX_SERVERS) { index ->
            val added = store.add(record("srv$index", "https://example.com/$index"))
            assertTrue(added is AgentMcpServerMutation.Applied)
        }
        val overflow = store.add(record("srv-overflow", "https://example.com/overflow"))
        assertTrue(overflow is AgentMcpServerMutation.Rejected)
        assertEquals("MCP_SERVER_LIMIT", (overflow as AgentMcpServerMutation.Rejected).code)
    }

    @Test
    fun `record validates stable id display name and creation time`() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentMcpServerRecord("UPPER", "Name", "https://example.com/mcp")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentMcpServerRecord("ok-id", " ", "https://example.com/mcp")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentMcpServerRecord("ok-id", "Name", "https://example.com/mcp", createdAtMillis = -1L)
        }
        assertNotNull(record("ok-id", "https://example.com/mcp").toClientConfig())
    }

    @Test
    fun `allowAgentNetwork defaults to false and roundtrips true`() {
        store.add(record("alpha", "https://example.com/mcp"))
        assertEquals(false, (store.load() as AgentMcpServerLoad.Loaded).servers.single().allowAgentNetwork)

        store.add(record("beta", "https://example.com/mcp2", allowAgentNetwork = true))
        val loaded = (store.load() as AgentMcpServerLoad.Loaded).servers.associateBy { it.serverId }
        assertEquals(false, loaded.getValue("alpha").allowAgentNetwork)
        assertEquals(true, loaded.getValue("beta").allowAgentNetwork)
        val root = JSONObject(store.path.readText(Charsets.UTF_8))
        val betaJson = (0 until root.getJSONArray("servers").length())
            .map { root.getJSONArray("servers").getJSONObject(it) }
            .first { it.getString("serverId") == "beta" }
        assertTrue(betaJson.getBoolean("allowAgentNetwork"))
    }

    @Test
    fun `legacy json without consent field decodes to false and can be set`() {
        writeStore(serverJson())

        val loaded = store.load() as AgentMcpServerLoad.Loaded
        assertEquals(false, loaded.servers.single().allowAgentNetwork)

        val allowed = store.setAgentNetworkAllowed("legacy", true)
        assertTrue(allowed is AgentMcpServerMutation.Applied)
        assertEquals(true, (store.load() as AgentMcpServerLoad.Loaded).servers.single().allowAgentNetwork)
    }

    @Test
    fun `wrong typed consent field fails closed as corrupt`() {
        writeStore(serverJson().put("allowAgentNetwork", "true"))

        assertEquals("MCP_STORE_INVALID_RECORD", (store.load() as AgentMcpServerLoad.Corrupt).reasonCode)
        val mutation = store.setAgentNetworkAllowed("legacy", true)
        assertTrue(mutation is AgentMcpServerMutation.Rejected)
    }

    @Test
    fun `set agent network consent and revoke is atomic and no-op safe`() {
        store.add(record("alpha", "https://example.com/mcp"))

        val allowed = store.setAgentNetworkAllowed("alpha", true)
        assertTrue(allowed is AgentMcpServerMutation.Applied)
        assertEquals(true, (allowed as AgentMcpServerMutation.Applied).servers.single().allowAgentNetwork)
        assertEquals(true, (store.load() as AgentMcpServerLoad.Loaded).servers.single().allowAgentNetwork)

        val revoked = store.setAgentNetworkAllowed("alpha", false)
        assertTrue(revoked is AgentMcpServerMutation.Applied)
        assertEquals(false, (store.load() as AgentMcpServerLoad.Loaded).servers.single().allowAgentNetwork)

        val noop = store.setAgentNetworkAllowed("alpha", false)
        assertTrue(noop is AgentMcpServerMutation.Applied)
        assertEquals(1, (noop as AgentMcpServerMutation.Applied).servers.size)
        assertEquals(false, noop.servers.single().allowAgentNetwork)
    }

    @Test
    fun `set agent network consent rejects an unknown id`() {
        val mutation = store.setAgentNetworkAllowed("missing", true)
        assertTrue(mutation is AgentMcpServerMutation.Rejected)
        assertEquals("MCP_SERVER_NOT_FOUND", (mutation as AgentMcpServerMutation.Rejected).code)
    }

    @Test
    fun `authorize records the canonical origin and explicit private boundary`() {
        store.add(record("alpha", "https://example.com/mcp"))

        val authorized = store.authorizeForAgent("alpha")

        assertTrue(authorized is AgentMcpServerMutation.Applied)
        val loaded = (store.load() as AgentMcpServerLoad.Loaded).servers.single()
        assertTrue(loaded.allowAgentNetwork)
        val authorization = loaded.agentAuthorization!!
        assertEquals("https://example.com", authorization.origin)
        assertEquals(com.muyuchat.mca.agent.security.AgentEgressTransport.REMOTE_MCP, authorization.transport)
        assertEquals(setOf(com.muyuchat.core.agent.contract.AgentDataClass.PRIVATE), authorization.dataClasses)
        assertTrue(authorization.grantedAtMillis > 0L)
        assertTrue(authorization.coversProjectedTools())

        // No credential material may ever be written next to the authorization.
        val raw = store.path.readText(Charsets.UTF_8).lowercase()
        assertFalse(raw.contains("bearer"))
        assertFalse(raw.contains("token"))
        assertFalse(raw.contains("password"))
        assertFalse(raw.contains("secret"))
    }

    @Test
    fun `authorization survives reload and revoke removes it`() {
        store.add(record("alpha", "https://example.com/mcp"))
        store.authorizeForAgent("alpha")

        val reopened = (AgentMcpServerStore(filesDir).load() as AgentMcpServerLoad.Loaded).servers.single()
        assertEquals("https://example.com", reopened.agentAuthorization?.origin)

        val revoked = store.revokeAgentAuthorization("alpha")
        assertTrue(revoked is AgentMcpServerMutation.Applied)
        val loaded = (AgentMcpServerStore(filesDir).load() as AgentMcpServerLoad.Loaded).servers.single()
        assertFalse(loaded.allowAgentNetwork)
        assertEquals(null, loaded.agentAuthorization)
    }

    @Test
    fun `a disabled server cannot be authorized`() {
        store.add(record("alpha", "https://example.com/mcp"))
        store.setEnabled("alpha", false)

        val outcome = store.authorizeForAgent("alpha")

        assertTrue(outcome is AgentMcpServerMutation.Rejected)
        assertEquals("MCP_SERVER_DISABLED", (outcome as AgentMcpServerMutation.Rejected).code)
        assertEquals(null, (store.load() as AgentMcpServerLoad.Loaded).servers.single().agentAuthorization)
    }

    @Test
    fun `hand-edited authorization data class fails closed as corrupt`() {
        store.add(record("alpha", "https://example.com/mcp"))
        store.authorizeForAgent("alpha")
        val root = JSONObject(store.path.readText(Charsets.UTF_8))
        root.getJSONArray("servers").getJSONObject(0)
            .getJSONObject("agentAuthorization")
            .put("dataClasses", JSONArray(listOf("PUBLIC")))
        store.path.writeText(root.toString(), Charsets.UTF_8)

        assertEquals("MCP_STORE_INVALID_RECORD", (store.load() as AgentMcpServerLoad.Corrupt).reasonCode)
    }

    @Test
    fun `hand-edited authorization origin fails closed as corrupt`() {
        store.add(record("alpha", "https://example.com/mcp"))
        store.authorizeForAgent("alpha")
        val root = JSONObject(store.path.readText(Charsets.UTF_8))
        root.getJSONArray("servers").getJSONObject(0)
            .getJSONObject("agentAuthorization")
            .put("origin", "https://evil.example.net")
        store.path.writeText(root.toString(), Charsets.UTF_8)

        assertEquals("MCP_STORE_INVALID_RECORD", (store.load() as AgentMcpServerLoad.Corrupt).reasonCode)
    }

    @Test
    fun `authorization without the consent flag fails closed as corrupt`() {
        store.add(record("alpha", "https://example.com/mcp"))
        store.authorizeForAgent("alpha")
        val root = JSONObject(store.path.readText(Charsets.UTF_8))
        root.getJSONArray("servers").getJSONObject(0).put("allowAgentNetwork", false)
        store.path.writeText(root.toString(), Charsets.UTF_8)

        assertEquals("MCP_STORE_INVALID_RECORD", (store.load() as AgentMcpServerLoad.Corrupt).reasonCode)
    }

    private fun serverJson(serverId: String = "legacy"): JSONObject = JSONObject()
        .put("serverId", serverId)
        .put("displayName", "Legacy $serverId")
        .put("endpoint", "https://example.com/mcp")
        .put("enabled", true)
        .put("allowLocal", false)
        .put("createdAtMillis", 1_000L)

    private fun writeStore(vararg records: JSONObject) {
        store.path.parentFile.mkdirs()
        val servers = JSONArray()
        records.forEach { servers.put(it) }
        store.path.writeText(
            JSONObject()
                .put("schemaVersion", AgentMcpServerStore.SCHEMA_VERSION)
                .put("servers", servers)
                .toString(),
            Charsets.UTF_8
        )
    }

    private fun record(
        serverId: String,
        endpoint: String,
        allowLocal: Boolean = false,
        enabled: Boolean = true,
        allowAgentNetwork: Boolean = false
    ): AgentMcpServerRecord = AgentMcpServerRecord(
        serverId = serverId,
        displayName = "Server $serverId",
        endpoint = endpoint,
        enabled = enabled,
        allowLocal = allowLocal,
        createdAtMillis = 1_000L,
        allowAgentNetwork = allowAgentNetwork
    )

    private fun assertThrows(expected: Class<out Throwable>, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (expected.isInstance(error)) return
            throw error
        }
        throw AssertionError("expected ${expected.simpleName}")
    }
}

