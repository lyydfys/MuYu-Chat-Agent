package com.muyuchat.mca.agent.memory

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.mca.agent.tools.AgentToolJson
import com.muyuchat.mca.agent.tools.FakeAgentWorkspaceFilePort
import com.muyuchat.mca.agent.tools.builtInAgentToolCatalog
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `memory.search` through the existing descriptor 鈫?broker 鈫?executor chain.
 *
 * The broker/approval pipeline itself is covered by the security tests; here the focus is that the
 * descriptor is a valid catalog entry, the executor enforces the frozen selection a second time, and
 * every failure maps to a truthful typed status instead of a success.
 */
class AgentMemorySearchToolTest {
    private val persistence = InMemoryAgentMemoryPersistence()
    private val store = AgentMemoryTestFixtures.store(persistence, AgentMemoryTestFixtures.sequentialIds())

    @Test
    fun `descriptor is a canonical read-only private catalog entry`() {
        val descriptor = AgentMemoryToolDescriptors.MEMORY_SEARCH
        assertEquals("memory.search", descriptor.toolKey)
        assertEquals("1", descriptor.version)
        assertEquals(AgentToolSideEffect.READ_ONLY, descriptor.sideEffect)
        assertEquals(AgentDataClass.PRIVATE, descriptor.dataClass)
        assertEquals(10_000L, descriptor.timeoutMillis)
        // Canonical text and an up-to-date hash: what the frozen command hashes and what the
        // catalog duplicate/schema checks compare.
        assertEquals(AgentToolJson.canonical(JSONObject(descriptor.schemaJson)), descriptor.schemaJson)
        assertEquals(AgentToolJson.sha256Hex(descriptor.schemaJson), descriptor.schemaHash)
        val schema = JSONObject(descriptor.schemaJson)
        assertEquals(false, schema.getBoolean("additionalProperties"))
        assertEquals("query", schema.getJSONArray("required").getString(0))
        val propertyNames = schema.getJSONObject("properties").keys().asSequence().toSet()
        assertEquals(setOf("maxItems", "query", "spaceId"), propertyNames)
    }

    @Test
    fun `a granted task receives bounded citations`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedItem("memory-1", "space-1", "The launch plan is approved for October.")
        val tool = tool(grant("space-1"))
        val result = tool.execute(
            AgentMemoryTestFixtures.command(AgentMemoryTestFixtures.searchArguments("launch plan")),
            AgentMemoryTestFixtures.context()
        )
        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertEquals(false, result.possibleSideEffects)
        val data = dataOf(result)
        assertEquals(1, data.getInt("citationCount"))
        val citation = data.getJSONArray("citations").getJSONObject(0)
        assertEquals("space-1", citation.getString("spaceId"))
        assertEquals("memory-1", citation.getString("memoryId"))
        assertEquals("memory-1:0", citation.getString("chunkId"))
        assertTrue(citation.getString("sourceHash").matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(citation.getString("excerpt").length <= AgentMemoryLimits.MAX_EXCERPT_CHARS)
        assertTrue(citation.getInt("score") > 0)
        assertFalse(data.getBoolean("truncated"))
        val limits = data.getJSONObject("limits")
        assertEquals(AgentMemoryLimits.MAX_CANDIDATE_CHUNKS, limits.getInt("maxCandidates"))
        assertEquals(AgentMemoryLimits.MAX_SCAN_CHUNKS, limits.getInt("maxScanChunks"))
        assertEquals(AgentMemoryLimits.MAX_RETURNED_CITATIONS, limits.getInt("maxCitations"))
        assertTrue(data.getInt("scannedChunks") in 1..AgentMemoryLimits.MAX_SCAN_CHUNKS)
    }

    @Test
    fun `memory get reads one item inside the frozen selection`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedItem("memory-1", "space-1", "The launch plan is approved for October.")
        val getTool = MemoryGetTool(store, FixedMemoryGrantSource(grant("space-1")))

        val result = getTool.execute(
            AgentMemoryTestFixtures.command(
                AgentMemoryTestFixtures.getArguments("memory-1"),
                toolKey = AgentMemoryToolDescriptors.TOOL_KEY_GET,
                schemaHash = AgentMemoryToolDescriptors.MEMORY_GET.schemaHash
            ),
            AgentMemoryTestFixtures.context()
        )

        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        val data = dataOf(result)
        assertEquals("memory-1", data.getString("itemId"))
        assertEquals("space-1", data.getString("spaceId"))
        assertEquals("The launch plan is approved for October.", data.getString("text"))
    }

    @Test
    fun `memory get hides foreign and revoked items as not found`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedSpace("space-2")
        persistence.seedItem("memory-1", "space-1", "visible")
        persistence.seedItem("memory-2", "space-2", "other-space secret")
        persistence.seedItem("memory-3", "space-1", "revoked secret", revoked = true)
        val getTool = MemoryGetTool(store, FixedMemoryGrantSource(grant("space-1")))

        listOf("memory-2", "memory-3").forEach { itemId ->
            val result = getTool.execute(
                AgentMemoryTestFixtures.command(
                    AgentMemoryTestFixtures.getArguments(itemId),
                    toolKey = AgentMemoryToolDescriptors.TOOL_KEY_GET,
                    schemaHash = AgentMemoryToolDescriptors.MEMORY_GET.schemaHash
                ),
                AgentMemoryTestFixtures.context()
            )
            assertEquals(AgentToolResultStatus.FAILED, result.status)
            assertTrue(
                "no foreign/revoked text may leak for $itemId",
                result.dataJson.orEmpty().contains("secret").not()
            )
        }
    }

    @Test
    fun `memory get rejects a legacy pseudo-path approval`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedItem("memory-1", "space-1", "alpha")
        val getTool = MemoryGetTool(store, FixedMemoryGrantSource(grant("space-1")))

        val result = getTool.execute(
            AgentMemoryTestFixtures.command(
                AgentMemoryTestFixtures.getArguments("memory-1"),
                toolKey = AgentMemoryToolDescriptors.TOOL_KEY_GET,
                schemaHash = AgentMemoryToolDescriptors.MEMORY_GET.schemaHash,
                legacyPrecondition = true
            ),
            AgentMemoryTestFixtures.context()
        )

        assertEquals(AgentToolResultStatus.DENIED, result.status)
        assertEquals(AgentErrorCodes.RESOURCE_SCOPE_OUTDATED, result.errorCode)
    }

    @Test
    fun `memory get verifies the citation hash before returning text`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedItem("memory-1", "space-1", "The launch plan is approved for October.")
        val getTool = MemoryGetTool(store, FixedMemoryGrantSource(grant("space-1")))
        val correctHash = AgentMemoryText.sha256("The launch plan is approved for October.")

        val fresh = getTool.execute(
            AgentMemoryTestFixtures.command(
                AgentMemoryTestFixtures.getArguments("memory-1", contentHash = correctHash),
                toolKey = AgentMemoryToolDescriptors.TOOL_KEY_GET,
                schemaHash = AgentMemoryToolDescriptors.MEMORY_GET.schemaHash
            ),
            AgentMemoryTestFixtures.context()
        )
        assertEquals(AgentToolResultStatus.SUCCEEDED, fresh.status)

        val stale = getTool.execute(
            AgentMemoryTestFixtures.command(
                AgentMemoryTestFixtures.getArguments("memory-1", contentHash = "b".repeat(64)),
                toolKey = AgentMemoryToolDescriptors.TOOL_KEY_GET,
                schemaHash = AgentMemoryToolDescriptors.MEMORY_GET.schemaHash
            ),
            AgentMemoryTestFixtures.context()
        )
        assertEquals(AgentToolResultStatus.FAILED, stale.status)
        assertTrue(stale.dataJson.orEmpty().contains("October").not())
    }

    @Test
    fun `no frozen selection denies and the store is not consulted`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedItem("memory-1", "space-1", "alpha line")
        val grants = FixedMemoryGrantSource(null)
        val tool = MemorySearchTool(store, grants)
        val result = tool.execute(
            AgentMemoryTestFixtures.command(AgentMemoryTestFixtures.searchArguments("alpha")),
            AgentMemoryTestFixtures.context()
        )
        assertEquals(AgentToolResultStatus.DENIED, result.status)
        assertEquals(AgentErrorCodes.PERMISSION_DENIED, result.errorCode)
        assertEquals(1, grants.lookups)
        assertNull(dataOf(result).optJSONArray("citations"))
    }

    @Test
    fun `a revoked selection denies with scope revoked`() = runBlocking {
        persistence.seedSpace("space-1")
        val result = tool(AgentMemoryReadGrant("task-1", listOf("space-1"), revoked = true)).execute(
            AgentMemoryTestFixtures.command(AgentMemoryTestFixtures.searchArguments("alpha")),
            AgentMemoryTestFixtures.context()
        )
        assertEquals(AgentToolResultStatus.DENIED, result.status)
        assertEquals(AgentErrorCodes.PERMISSION_SCOPE_REVOKED, result.errorCode)
    }

    @Test
    fun `a space outside the selection is denied`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedSpace("space-2")
        persistence.seedItem("memory-2", "space-2", "alpha secret")
        val result = tool(grant("space-1")).execute(
            AgentMemoryTestFixtures.command(
                AgentMemoryTestFixtures.searchArguments("alpha", spaceId = "space-2")
            ),
            AgentMemoryTestFixtures.context()
        )
        assertEquals(AgentToolResultStatus.DENIED, result.status)
        assertEquals(AgentErrorCodes.PERMISSION_DENIED, result.errorCode)
    }

    @Test
    fun `a frozen memory scope outside the grant is denied`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedSpace("space-2")
        val result = tool(grant("space-1")).execute(
            AgentMemoryTestFixtures.command(
                AgentMemoryTestFixtures.searchArguments("alpha"),
                spaceIds = setOf("space-2")
            ),
            AgentMemoryTestFixtures.context()
        )
        assertEquals(AgentToolResultStatus.DENIED, result.status)
        assertEquals(AgentErrorCodes.RESOURCE_SCOPE_OUTDATED, result.errorCode)
    }

    @Test
    fun `a frozen memory scope inside the grant is accepted`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedItem("memory-1", "space-1", "alpha line")
        val result = tool(grant("space-1")).execute(
            AgentMemoryTestFixtures.command(
                AgentMemoryTestFixtures.searchArguments("alpha"),
                spaceIds = setOf("space-1")
            ),
            AgentMemoryTestFixtures.context()
        )
        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
    }

    @Test
    fun `a legacy pseudo-path approval must be re-requested`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedItem("memory-1", "space-1", "alpha line")
        val result = tool(grant("space-1")).execute(
            AgentMemoryTestFixtures.command(
                AgentMemoryTestFixtures.searchArguments("alpha"),
                legacyPrecondition = true
            ),
            AgentMemoryTestFixtures.context()
        )
        assertEquals(AgentToolResultStatus.DENIED, result.status)
        assertEquals(AgentErrorCodes.RESOURCE_SCOPE_OUTDATED, result.errorCode)
    }

    @Test
    fun `a deleted selected space denies instead of returning partial results`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedItem("memory-1", "space-1", "alpha line")
        val result = tool(AgentMemoryReadGrant("task-1", listOf("space-1", "space-gone"))).execute(
            AgentMemoryTestFixtures.command(
                AgentMemoryTestFixtures.searchArguments("alpha"),
                spaceIds = setOf("space-1", "space-gone")
            ),
            AgentMemoryTestFixtures.context()
        )
        assertEquals(AgentToolResultStatus.DENIED, result.status)
        assertEquals(AgentErrorCodes.PERMISSION_SCOPE_REVOKED, result.errorCode)
    }

    @Test
    fun `invalid arguments are a typed input failure`() = runBlocking {
        persistence.seedSpace("space-1")
        val tool = tool(grant("space-1"))
        val context = AgentMemoryTestFixtures.context()
        val invalidArguments = listOf(
            "{}",
            "{\"query\":\"\"}",
            "{\"query\":\"${"q".repeat(AgentMemoryLimits.MAX_QUERY_CHARS + 1)}\"}",
            "{\"query\":\"alpha\",\"extra\":1}",
            "{\"query\":\"alpha\",\"maxItems\":0}",
            "{\"query\":\"alpha\",\"maxItems\":11}",
            "{\"query\":\"alpha\",\"maxItems\":\"3\"}",
            "{\"query\":\"alpha\",\"spaceId\":\"*\"}",
            "{\"query\":\"alpha\",\"spaceId\":3}"
        )
        invalidArguments.forEach { arguments ->
            val result = tool.execute(AgentMemoryTestFixtures.command(arguments), context)
            assertEquals(
                "arguments must be rejected: $arguments",
                AgentToolResultStatus.FAILED,
                result.status
            )
            assertEquals(AgentErrorCodes.TOOL_INPUT_INVALID, result.errorCode)
        }
    }

    @Test
    fun `tool identity is re-checked before any argument parsing`() = runBlocking {
        persistence.seedSpace("space-1")
        val tool = tool(grant("space-1"))
        val context = AgentMemoryTestFixtures.context()
        val wrongKey = tool.execute(
            AgentMemoryTestFixtures.command(
                AgentMemoryTestFixtures.searchArguments("alpha"),
                toolKey = "workspace.list"
            ),
            context
        )
        assertEquals(AgentToolResultStatus.NOT_EXECUTED, wrongKey.status)
        assertEquals(AgentErrorCodes.TOOL_NOT_FOUND, wrongKey.errorCode)

        val staleSchema = tool.execute(
            AgentMemoryTestFixtures.command(
                AgentMemoryTestFixtures.searchArguments("alpha"),
                schemaHash = "0".repeat(64)
            ),
            context
        )
        assertEquals(AgentToolResultStatus.NOT_EXECUTED, staleSchema.status)
        assertEquals(AgentErrorCodes.TOOL_INPUT_INVALID, staleSchema.errorCode)
    }

    @Test
    fun `an empty result is a successful empty citation list`() = runBlocking {
        persistence.seedSpace("space-1")
        persistence.seedItem("memory-1", "space-1", "coffee machine notes")
        val result = tool(grant("space-1")).execute(
            AgentMemoryTestFixtures.command(AgentMemoryTestFixtures.searchArguments("quantum")),
            AgentMemoryTestFixtures.context()
        )
        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        val data = dataOf(result)
        assertEquals(0, data.getInt("citationCount"))
        assertEquals(0, data.getJSONArray("citations").length())
    }

    @Test
    fun `truncation is reported to the model and in the result`() = runBlocking {
        persistence.seedSpace("space-1")
        (1..5).forEach { index ->
            persistence.seedItem("memory-$index", "space-1", "alpha item number $index")
        }
        val result = tool(grant("space-1")).execute(
            AgentMemoryTestFixtures.command(
                AgentMemoryTestFixtures.searchArguments("alpha", maxItems = 2)
            ),
            AgentMemoryTestFixtures.context()
        )
        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertTrue(result.truncated)
        val data = dataOf(result)
        assertTrue(data.getBoolean("truncated"))
        assertEquals(2, data.getInt("citationCount"))
    }

    @Test
    fun `the catalog rule exposes memory only for a live local-only selection`() = runBlocking {
        persistence.seedSpace("space-1")
        val grants = FixedMemoryGrantSource(grant("space-1"))
        val local = AgentMemoryToolSet.forRun(
            store,
            grants,
            taskId = "task-1",
            networkPolicy = AgentNetworkPolicy.LOCAL_ONLY
        )
        assertEquals(listOf("memory.get", "memory.search"), local.map { it.descriptor.toolKey }.sorted())

        val network = AgentMemoryToolSet.forRun(
            store,
            grants,
            taskId = "task-1",
            networkPolicy = AgentNetworkPolicy.NETWORK_ENABLED
        )
        assertTrue(network.isEmpty())

        val noSelection = AgentMemoryToolSet.forRun(
            store,
            FixedMemoryGrantSource(null),
            taskId = "task-1",
            networkPolicy = AgentNetworkPolicy.LOCAL_ONLY
        )
        assertTrue(noSelection.isEmpty())

        val revoked = AgentMemoryToolSet.forRun(
            store,
            FixedMemoryGrantSource(AgentMemoryReadGrant("task-1", listOf("space-1"), revoked = true)),
            taskId = "task-1",
            networkPolicy = AgentNetworkPolicy.LOCAL_ONLY
        )
        assertTrue(revoked.isEmpty())
    }

    @Test
    fun `a frozen policy selection must equal the persisted task selection`() = runBlocking {
        persistence.seedSpace("space-1")
        val grants = FixedMemoryGrantSource(grant("space-1"))

        val matching = AgentMemoryToolSet.forRun(
            store,
            grants,
            taskId = "task-1",
            networkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
            frozenSpaceIds = setOf("space-1")
        )
        assertEquals(listOf("memory.get", "memory.search"), matching.map { it.descriptor.toolKey }.sorted())

        val widened = AgentMemoryToolSet.forRun(
            store,
            grants,
            taskId = "task-1",
            networkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
            frozenSpaceIds = setOf("space-1", "space-2")
        )
        assertTrue(widened.isEmpty())

        val dropped = AgentMemoryToolSet.forRun(
            store,
            grants,
            taskId = "task-1",
            networkPolicy = AgentNetworkPolicy.LOCAL_ONLY,
            frozenSpaceIds = emptySet()
        )
        assertTrue(dropped.isEmpty())
    }

    @Test
    fun `installing memory tools preserves the base descriptors and rejects duplicates`() = runBlocking {
        val base = builtInAgentToolCatalog(FakeAgentWorkspaceFilePort())
        val tool = tool(grant("space-1"))
        val installed = AgentMemoryToolSet.withMemoryTools(base, listOf(tool))
        assertEquals(
            (base.snapshot().map { it.toolKey } + "memory.search").sorted(),
            installed.snapshot().map { it.toolKey }
        )
        base.snapshot().forEach { descriptor ->
            assertSame(base.find(descriptor.toolKey), installed.find(descriptor.toolKey))
        }
        assertSame(tool, installed.find("memory.search"))
        assertSame(base, AgentMemoryToolSet.withMemoryTools(base, emptyList()))
        assertThrows(IllegalArgumentException::class.java) {
            AgentMemoryToolSet.withMemoryTools(installed, listOf(tool))
        }
        assertEquals(setOf("memory.search", "memory.get"), AgentMemoryToolSet.toolKeys())
    }

    private fun tool(grant: AgentMemoryReadGrant?): MemorySearchTool =
        MemorySearchTool(store, FixedMemoryGrantSource(grant))

    /** The shared tool-result envelope nests the tool document under `data`. */
    private fun dataOf(result: AgentToolResult): JSONObject =
        JSONObject(requireNotNull(result.dataJson)).getJSONObject("data")

    private fun grant(vararg spaceIds: String): AgentMemoryReadGrant =
        AgentMemoryReadGrant("task-1", spaceIds.toList().sorted())
}

