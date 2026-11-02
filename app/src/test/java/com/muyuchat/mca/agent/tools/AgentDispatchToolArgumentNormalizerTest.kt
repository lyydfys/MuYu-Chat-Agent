package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.mca.agent.security.AgentArgumentNormalization
import com.muyuchat.mca.agent.security.AgentToolArgumentNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

/** N08: production normalizer dispatch must select the owning family before freeze. */
class AgentDispatchToolArgumentNormalizerTest {

    private class Recording(private val label: String) : AgentToolArgumentNormalizer {
        val calls = ArrayList<String>()
        override fun normalize(
            descriptor: AgentToolDescriptor,
            rawArgumentsJson: String
        ): AgentArgumentNormalization {
            calls += descriptor.toolKey
            return AgentArgumentNormalization.Normalized(
                normalizedArgumentsJson = """{"owner":"$label"}""",
                logicalPaths = emptyList(),
                expectedOldHash = null
            )
        }
    }

    private fun descriptor(key: String) = AgentToolDescriptor(
        toolKey = key,
        version = "1",
        displayName = key,
        description = key,
        schemaJson = "{}",
        schemaHash = "0".repeat(64),
        sideEffect = com.muyuchat.core.agent.contract.AgentToolSideEffect.READ_ONLY,
        dataClass = com.muyuchat.core.agent.contract.AgentDataClass.PRIVATE
    )

    @Test
    fun `a routed key goes to its family and never to the fallback`() {
        val memory = Recording("memory")
        val extended = Recording("extended")
        val fallback = Recording("fallback")
        val dispatch = AgentDispatchToolArgumentNormalizer(
            routes = listOf(
                setOf("memory.search") to memory,
                setOf("workspace.write_text") to extended
            ),
            fallback = fallback
        )

        val memoryResult = dispatch.normalize(descriptor("memory.search"), "{}")
        val extendedResult = dispatch.normalize(descriptor("workspace.write_text"), "{}")

        assertEquals(listOf("memory.search"), memory.calls)
        assertEquals(listOf("workspace.write_text"), extended.calls)
        assertEquals(emptyList<String>(), fallback.calls)
        assertEquals(
            """{"owner":"memory"}""",
            (memoryResult as AgentArgumentNormalization.Normalized).normalizedArgumentsJson
        )
        assertEquals(
            """{"owner":"extended"}""",
            (extendedResult as AgentArgumentNormalization.Normalized).normalizedArgumentsJson
        )
    }

    @Test
    fun `an unrouted key falls back to the V1 normalizer`() {
        val fallback = Recording("fallback")
        val dispatch = AgentDispatchToolArgumentNormalizer(
            routes = listOf(setOf("memory.search") to Recording("memory")),
            fallback = fallback
        )

        dispatch.normalize(descriptor("workspace.read_text"), "{}")

        assertEquals(listOf("workspace.read_text"), fallback.calls)
    }

    @Test
    fun `route order is deterministic for overlapping sets`() {
        val first = Recording("first")
        val second = Recording("second")
        val dispatch = AgentDispatchToolArgumentNormalizer(
            routes = listOf(setOf("test.shared") to first, setOf("test.shared") to second),
            fallback = Recording("fallback")
        )

        dispatch.normalize(descriptor("test.shared"), "{}")

        assertEquals(listOf("test.shared"), first.calls)
        assertEquals(emptyList<String>(), second.calls)
    }
}
