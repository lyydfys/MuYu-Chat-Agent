package com.muyuchat.mca.agent.extensions.mcp

import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.runtime.AgentToolSchemaValidator
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolAdapterTest {
    private val validator: AgentToolSchemaValidator = SupportedToolSchemaValidator()

    private fun tool(
        name: String = "echo",
        schema: String = """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}"""
    ) = McpRemoteTool(
        serverId = "srv",
        remoteName = name,
        displayName = "Echo",
        description = "Echo text",
        inputSchemaJson = schema,
        schemaHash = "a".repeat(64),
        readOnlyHint = true,
        destructiveHint = null
    )

    private class FakeCaller(
        var outcome: McpToolCallOutcome = McpToolCallOutcome.Success(listOf("ok"), null, emptyList())
    ) : McpToolCaller {
        var calls = 0
        override suspend fun callTool(remoteName: String, argumentsJson: String): McpToolCallOutcome {
            calls += 1
            return outcome
        }
    }

    @Test
    fun namespacesKeysAndMapsSupportedSchemas() {
        val caller = FakeCaller()
        val result = McpToolCatalogFactory(caller, validator).build("srv", listOf(tool()))
        assertEquals(1, result.accepted.size)
        val executor = result.accepted.single()
        assertEquals("mcp:srv:echo", executor.descriptor.toolKey)
        assertTrue(executor.descriptor.schemaHash.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(executor.descriptor.schemaJson.contains("additionalProperties"))
    }

    @Test
    fun unsupportedSchemaDisablesOnlyThatTool() {
        val caller = FakeCaller()
        val unsupported = tool(
            name = "weird",
            schema = """{"type":"object","properties":{"x":{"type":"string"}},"allOf":[{"required":["x"]}]}"""
        )
        val result = McpToolCatalogFactory(caller, validator).build("srv", listOf(tool(), unsupported))
        assertEquals(1, result.accepted.size)
        assertEquals(1, result.disabled.size)
        assertEquals("weird", result.disabled.single().remoteName)
    }

    @Test
    fun duplicateNamespacedKeysAreReportedAsCollisions() {
        val caller = FakeCaller()
        val result = McpToolCatalogFactory(caller, validator).build("srv", listOf(tool(), tool()))
        assertEquals(1, result.accepted.size)
        assertEquals(listOf("mcp:srv:echo"), result.collisions)
    }

    @Test
    fun remoteNameIsNeverAcceptedAsAnExecutorKeyWhenUnsafe() {
        val caller = FakeCaller()
        val unsafe = tool(name = "../escape")
        val result = McpToolCatalogFactory(caller, validator).build("srv", listOf(unsafe))
        assertTrue(result.accepted.isEmpty())
        assertEquals("mcp_remote_name_unsafe", result.disabled.single().reasonCode)
    }

    @Test
    fun executorMapsSuccessAndNeverLowersSideEffects() = runBlocking {
        val caller = FakeCaller()
        val executor = McpToolCatalogFactory(caller, validator).build("srv", listOf(tool())).accepted.single()
        val command = frozenCommand(executor)
        val result = executor.execute(command, context())
        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertEquals(1, caller.calls)
        assertTrue(result.possibleSideEffects)
    }

    @Test
    fun executorMapsTimeoutToUnknownWithSideEffects() = runBlocking {
        val caller = FakeCaller(McpToolCallOutcome.Failed(McpFailure.Timeout("mcp_timeout")))
        val executor = McpToolCatalogFactory(caller, validator).build("srv", listOf(tool())).accepted.single()
        val result = executor.execute(frozenCommand(executor), context())
        assertEquals(AgentToolResultStatus.UNKNOWN, result.status)
        assertTrue(result.possibleSideEffects)
        assertEquals("TOOL_TIMEOUT", result.errorCode)
    }

    @Test
    fun executorRejectsAForeignFrozenCommandWithoutCallingRemote() = runBlocking {
        val caller = FakeCaller()
        val executor = McpToolCatalogFactory(caller, validator).build("srv", listOf(tool())).accepted.single()
        val foreign = frozenCommand(executor).copy(toolKey = "workspace.list")
        val result = executor.execute(foreign, context())
        assertEquals(AgentToolResultStatus.NOT_EXECUTED, result.status)
        assertEquals(0, caller.calls)
    }

    @Test
    fun providerNameIsBoundedAsciiAndDependsOnServerAndTool() {
        val first = McpToolIdentity.providerName("srv", "echo")
        val second = McpToolIdentity.providerName("srv", "echo2")
        assertEquals(36, first.length)
        assertTrue(first.matches(Regex("^mcp_[0-9a-f]{32}$")))
        assertTrue(first != second)
    }

    private fun frozenCommand(executor: McpToolExecutor) = com.muyuchat.core.agent.contract.FrozenToolCommand(
        invocationId = "inv-1",
        runId = "run-1",
        stepId = "step-1",
        callOrdinal = 0,
        toolKey = executor.descriptor.toolKey,
        toolVersion = executor.descriptor.version,
        schemaHash = executor.descriptor.schemaHash,
        normalizedArgumentsJson = """{"text":"hi"}""",
        argumentsHash = "b".repeat(64),
        resourceFingerprint = "c".repeat(64),
        runEpoch = 1L,
        policyVersion = 1L,
        expiresAtMillis = Long.MAX_VALUE
    )

    private fun context() = com.muyuchat.core.agent.contract.AgentToolContext(
        taskId = "task-1",
        workspaceId = "workspace-1",
        runId = "run-1",
        stepIndex = 0,
        runEpoch = 1L,
        policyVersion = 1L,
        workspaceReadGranted = true
    )
}
