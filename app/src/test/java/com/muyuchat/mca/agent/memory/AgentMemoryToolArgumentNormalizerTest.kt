package com.muyuchat.mca.agent.memory

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.mca.agent.security.AgentArgumentNormalization
import com.muyuchat.mca.agent.security.AgentFreezeRequest
import com.muyuchat.mca.agent.security.AgentFreezeResult
import com.muyuchat.mca.agent.security.AgentFrozenCommandFactory
import com.muyuchat.mca.agent.tools.AgentToolDescriptors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `memory.search` joins the frozen-command normalizer/descriptor path without changing the four
 * built-in tools.
 *
 * The production composition injects [AgentMemoryToolArgumentNormalizer] into
 * [AgentFrozenCommandFactory]; these tests exercise that exact seam, then prove a memory command
 * can actually be frozen with canonical arguments and a non-empty resource scope.
 */
class AgentMemoryToolArgumentNormalizerTest {
    private val normalizer = AgentMemoryToolArgumentNormalizer()

    @Test
    fun `memory get arguments are canonical and reuse the frozen memory scope`() {
        val normalized = normalizer.normalize(
            AgentMemoryToolDescriptors.MEMORY_GET,
            """{"itemId":"memory-1"}"""
        ) as AgentArgumentNormalization.Normalized

        assertEquals(
            """{"contentHash":null,"itemId":"memory-1","spaceId":null}""",
            normalized.normalizedArgumentsJson
        )
        assertEquals(listOf(AgentMemoryToolDescriptors.FROZEN_SCOPE_PATH), normalized.logicalPaths)
        assertNull(normalized.expectedOldHash)
    }

    @Test
    fun `invalid memory get arguments are rejected before approval`() {
        val missing = normalizer.normalize(AgentMemoryToolDescriptors.MEMORY_GET, "{}")
        assertTrue(missing is AgentArgumentNormalization.Rejected)

        val unknown = normalizer.normalize(
            AgentMemoryToolDescriptors.MEMORY_GET,
            """{"itemId":"memory-1","query":"x"}"""
        )
        assertTrue(unknown is AgentArgumentNormalization.Rejected)

        val badHash = normalizer.normalize(
            AgentMemoryToolDescriptors.MEMORY_GET,
            """{"itemId":"memory-1","contentHash":"not-a-hash"}"""
        )
        assertTrue(badHash is AgentArgumentNormalization.Rejected)
    }

    @Test
    fun `memory search arguments are validated defaulted and canonical`() {
        val normalized = normalizer.normalize(
            AgentMemoryToolDescriptors.MEMORY_SEARCH,
            """{"query":"launch plan"}"""
        ) as AgentArgumentNormalization.Normalized

        assertEquals(
            """{"maxItems":4,"query":"launch plan","spaceId":null}""",
            normalized.normalizedArgumentsJson
        )
        assertEquals(listOf(AgentMemoryToolDescriptors.FROZEN_SCOPE_PATH), normalized.logicalPaths)
        assertNull(normalized.expectedOldHash)
    }

    @Test
    fun `space id and max items are preserved when supplied`() {
        val normalized = normalizer.normalize(
            AgentMemoryToolDescriptors.MEMORY_SEARCH,
            """{"query":"alpha","spaceId":"space-a","maxItems":2}"""
        ) as AgentArgumentNormalization.Normalized

        assertEquals(
            """{"maxItems":2,"query":"alpha","spaceId":"space-a"}""",
            normalized.normalizedArgumentsJson
        )
    }

    @Test
    fun `invalid memory search arguments are rejected before approval`() {
        val longQuery = "{\"query\":\"" + "q".repeat(AgentMemoryLimits.MAX_QUERY_CHARS + 1) + "\"}"
        val invalid = listOf(
            "{}",
            """{"query":""}""",
            longQuery,
            """{"query":"alpha","extra":1}""",
            """{"query":"alpha","maxItems":0}""",
            """{"query":"alpha","maxItems":11}""",
            """{"query":"alpha","maxItems":"3"}""",
            """{"query":"alpha","spaceId":"*"}""",
            """{"query":"alpha","spaceId":3}""",
            """{"query":"alpha","spaceId":null} extra"""
        )
        invalid.forEach { raw ->
            val result = normalizer.normalize(AgentMemoryToolDescriptors.MEMORY_SEARCH, raw)
            assertTrue("arguments must be rejected: $raw", result is AgentArgumentNormalization.Rejected)
            assertEquals(
                AgentErrorCodes.TOOL_INPUT_INVALID,
                (result as AgentArgumentNormalization.Rejected).error.code
            )
        }
    }

    @Test
    fun `the existing four built-ins still use the strict built-in normalizer`() {
        val normalized = normalizer.normalize(
            AgentToolDescriptors.WORKSPACE_LIST,
            """{"path":"inputs"}"""
        ) as AgentArgumentNormalization.Normalized

        assertEquals(
            """{"cursor":null,"limit":100,"path":"inputs"}""",
            normalized.normalizedArgumentsJson
        )
        assertEquals(listOf("inputs"), normalized.logicalPaths)
    }

    @Test
    fun `a memory search command freezes through the production factory seam`() {
        val factory = AgentFrozenCommandFactory(normalizer = AgentMemoryToolArgumentNormalizer())
        val frozen = factory.freeze(
            AgentFreezeRequest(
                descriptor = AgentMemoryToolDescriptors.MEMORY_SEARCH,
                rawArgumentsJson = """{"query":"launch plan"}""",
                invocationId = "invocation-1",
                runId = "run-1",
                stepId = "step-1",
                callOrdinal = 0,
                workspaceId = "workspace-1",
                taskId = "task-1",
                runEpoch = 1L,
                policyVersion = 1L,
                nowMillis = 1_700_000_000_000L
            )
        )

        assertTrue("expected a frozen memory.search command, got $frozen", frozen is AgentFreezeResult.Frozen)
        val frozenCommand = frozen as AgentFreezeResult.Frozen
        assertEquals("memory.search", frozenCommand.command.toolKey)
        assertEquals(
            """{"maxItems":4,"query":"launch plan","spaceId":null}""",
            frozenCommand.command.normalizedArgumentsJson
        )
        assertEquals(
            listOf(AgentMemoryToolDescriptors.FROZEN_SCOPE_PATH),
            frozenCommand.scope.logicalPaths
        )
        val command = frozenCommand.command
        assertTrue(command.argumentsHash.matches(Regex("^[0-9a-f]{64}$")))
    }
}
