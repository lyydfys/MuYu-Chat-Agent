package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolExecutor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.FrozenToolCommand
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** N20: the workflow registry bridge resolves only already registered catalog executors. */
class CatalogWorkflowToolRegistryTest {

    private fun descriptor(key: String) = AgentToolDescriptor(
        toolKey = key,
        version = "1",
        displayName = key,
        description = key,
        schemaJson = "{}",
        schemaHash = "0".repeat(64),
        sideEffect = AgentToolSideEffect.READ_ONLY,
        dataClass = AgentDataClass.PRIVATE
    )

    private fun result() = AgentToolResult(
        status = AgentToolResultStatus.SUCCEEDED,
        summary = "ok"
    )

    @Test
    fun `a registered tool exposes its own descriptor and executor`() = runBlocking {
        val descriptor = descriptor("workspace.read_text")
        var executed = false
        val executor = object : AgentToolExecutor {
            override val descriptor: AgentToolDescriptor = descriptor
            override suspend fun execute(command: FrozenToolCommand, context: AgentToolContext): AgentToolResult {
                executed = true
                return result()
            }
        }
        val catalog = object : AgentToolCatalog {
            override fun find(toolKey: String): AgentToolExecutor? =
                executor.takeIf { toolKey == descriptor.toolKey }
            override fun snapshot(): List<AgentToolDescriptor> = listOf(descriptor)
        }
        val registry = CatalogWorkflowToolRegistry(catalog)

        val target = registry.find(descriptor.toolKey)!!
        assertEquals(descriptor, target.descriptor)

        target.execute(
            FrozenToolCommand(
                invocationId = "inv-1",
                runId = "run-1",
                stepId = "step-1",
                callOrdinal = 0,
                toolKey = descriptor.toolKey,
                toolVersion = descriptor.version,
                schemaHash = descriptor.schemaHash,
                normalizedArgumentsJson = "{}",
                argumentsHash = "a".repeat(64),
                resourceFingerprint = "resource",
                runEpoch = 1L,
                policyVersion = 1L,
                expiresAtMillis = 1_000L
            ),
            AgentToolContext(
                taskId = "task-1",
                workspaceId = "workspace-1",
                runId = "run-1",
                stepIndex = 0,
                runEpoch = 1L,
                policyVersion = 1L,
                workspaceReadGranted = true
            )
        )
        assertEquals(true, executed)
    }

    @Test
    fun `an unregistered tool is null instead of a synthesized executor`() {
        val catalog = object : AgentToolCatalog {
            override fun find(toolKey: String): AgentToolExecutor? = null
            override fun snapshot(): List<AgentToolDescriptor> = emptyList()
        }
        assertNull(CatalogWorkflowToolRegistry(catalog).find("workspace.missing"))
    }
}
