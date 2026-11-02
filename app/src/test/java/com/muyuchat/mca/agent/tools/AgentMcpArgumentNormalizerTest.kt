package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.mca.agent.security.AgentArgumentNormalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMcpArgumentNormalizerTest {

    private val descriptor = AgentToolDescriptor(
        toolKey = "mcp:loopback:echo",
        version = "1",
        displayName = "Echo",
        description = "Echo",
        schemaJson = """{"type":"object","properties":{},"additionalProperties":false}""",
        schemaHash = "mcp-schema-hash",
        sideEffect = AgentToolSideEffect.EXTERNAL_SIDE_EFFECT,
        dataClass = AgentDataClass.PRIVATE
    )

    @Test
    fun prefixRouteAcceptsNestedMcpArgumentsWithoutWorkspacePaths() {
        val dispatcher = AgentDispatchToolArgumentNormalizer(
            routes = emptyList(),
            prefixRoutes = listOf(
                AgentMcpArgumentNormalizer.MCP_PREFIX to AgentMcpArgumentNormalizer()
            )
        )
        val normalized = dispatcher.normalize(descriptor, """{"text":"hi","nested":{"a":1}}""")
        assertTrue(normalized is AgentArgumentNormalization.Normalized)
        val value = normalized as AgentArgumentNormalization.Normalized
        assertTrue(value.logicalPaths.isEmpty())
        assertTrue(value.normalizedArgumentsJson.contains("\"nested\""))
    }

    @Test
    fun nonMcpKeysAndNonObjectArgumentsAreRejected() {
        val normalizer = AgentMcpArgumentNormalizer()
        assertTrue(
            normalizer.normalize(descriptor, "[]") is AgentArgumentNormalization.Rejected
        )
        assertTrue(
            normalizer.normalize(
                descriptor.copy(toolKey = "workspace.list"),
                "{}"
            ) is AgentArgumentNormalization.Rejected
        )
    }
    @Test
    fun mcpExecutorProvidesAnExactNetworkScope() {
        val client = object : com.muyuchat.mca.agent.extensions.mcp.McpToolCaller {
            override suspend fun callTool(
                remoteName: String,
                argumentsJson: String
            ): com.muyuchat.mca.agent.extensions.mcp.McpToolCallOutcome =
                com.muyuchat.mca.agent.extensions.mcp.McpToolCallOutcome.Failed(
                    com.muyuchat.mca.agent.extensions.mcp.McpFailure.Protocol("unused")
                )

            override fun currentSchemaHash(remoteName: String): String? = "mcp-schema-hash"
        }
        val executor = com.muyuchat.mca.agent.extensions.mcp.McpToolExecutor(
            descriptor = descriptor,
            client = client,
            remoteName = "echo",
            codec = com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec(),
            serverOrigin = "http://127.0.0.1:8765"
        )
        val scope = requireNotNull(executor.scopePrecondition())
        val network = scope.scopes.single() as com.muyuchat.core.agent.contract.AgentResourceScope.Network
        org.junit.Assert.assertEquals(listOf("http://127.0.0.1:8765"), network.origins)
        org.junit.Assert.assertEquals(setOf("POST"), network.methods)
        org.junit.Assert.assertTrue(
            scope.effects.contains(com.muyuchat.core.agent.contract.AgentEffectKind.NETWORK_WRITE)
        )

        val noOrigin = com.muyuchat.mca.agent.extensions.mcp.McpToolExecutor(
            descriptor = descriptor,
            client = client,
            remoteName = "echo",
            codec = com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec()
        )
        org.junit.Assert.assertNull(noOrigin.scopePrecondition())
    }

}
