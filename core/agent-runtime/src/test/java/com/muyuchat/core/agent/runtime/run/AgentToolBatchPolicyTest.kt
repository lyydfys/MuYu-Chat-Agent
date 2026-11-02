package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentModelTurn
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.AgentTurnFinish
import com.muyuchat.core.agent.contract.AgentTurnProtocol
import com.muyuchat.core.agent.contract.AgentTurnToolCall
import com.muyuchat.core.agent.runtime.testing.FakeToolCatalog
import com.muyuchat.core.agent.runtime.testing.RecordingToolExecutor
import com.muyuchat.core.agent.runtime.validation.AgentV1ToolSpecs
import com.muyuchat.core.agent.runtime.validation.SupportedToolSchemaValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N06 batch admission rules: the whole batch is refused when any call is not read-only, has no
 * provider call id or exceeds the bounded size; only then may up to three read-only executors overlap.
 */
class AgentToolBatchPolicyTest {

    private val validator = SupportedToolSchemaValidator()
    private val read = AgentV1ToolSpecs.descriptor(AgentV1ToolSpecs.WORKSPACE_READ_TEXT, validator)
    private val write = AgentV1ToolSpecs.descriptor(AgentV1ToolSpecs.ARTIFACT_WRITE_TEXT, validator)
    private val network = AgentToolDescriptor(
        toolKey = "http.get",
        version = "1.0.0",
        displayName = "HTTP GET",
        description = "fetch a page",
        schemaJson = """{"type":"object","additionalProperties":false,"properties":{}}""",
        schemaHash = "f".repeat(64),
        sideEffect = AgentToolSideEffect.NETWORK,
        dataClass = AgentDataClass.PUBLIC
    )

    private val catalog = FakeToolCatalog(
        listOf(read, write, network).associate { it.toolKey to RecordingToolExecutor(it) }
    )

    private fun call(callId: String?, toolKey: String = read.toolKey): AgentTurnToolCall =
        AgentTurnToolCall(
            callId = callId,
            name = toolKey,
            argumentsJson = """{"path":"inputs/a.txt"}"""
        )

    private fun turn(vararg calls: AgentTurnToolCall): AgentModelTurn = AgentModelTurn(
        protocol = AgentTurnProtocol.NATIVE,
        finish = AgentTurnFinish.TOOL_CALL,
        calls = calls.toList()
    )

    @Test
    fun aBoundedReadOnlyBatchIsAllowed() {
        assertNull(batchProblem(turn(call("call-1"), call("call-2")), catalog))
        assertNull(batchProblem(turn(call("call-1"), call("call-2"), call("call-3")), catalog))
        assertNull(
            "four calls are one bounded batch; only three executors may overlap",
            batchProblem(turn(call("call-1"), call("call-2"), call("call-3"), call("call-4")), catalog)
        )
        assertEquals(3, MAX_PARALLEL_READ_ONLY_CALLS)
        assertEquals(4, AgentPromptedLoopCoordinator.MAX_BATCH_CALLS)
    }

    @Test
    fun aWriteCallRefusesTheWholeBatch() {
        val reason = batchProblem(
            turn(call("call-1"), call("call-2", write.toolKey)),
            catalog
        )
        assertNotNull(reason)
        assertTrue(reason.orEmpty().contains("read-only"))
    }

    @Test
    fun aNetworkCallRefusesTheWholeBatch() {
        val reason = batchProblem(turn(call("call-1"), call("call-2", network.toolKey)), catalog)
        assertNotNull(reason)
        assertTrue(reason.orEmpty().contains("read-only"))
    }

    @Test
    fun aCallWithoutAProviderIdRefusesTheWholeBatch() {
        val reason = batchProblem(turn(call("call-1"), call(null)), catalog)
        assertNotNull(reason)
        assertTrue(reason.orEmpty().contains("call id"))
    }

    @Test
    fun anOversizedBatchIsRefusedBeforeAnyCallIsFrozen() {
        val calls = (1..5).map { call("call-$it") }.toTypedArray()
        val reason = batchProblem(turn(*calls), catalog)
        assertNotNull(reason)
        assertTrue(reason.orEmpty().contains("exceeds"))
    }
}
