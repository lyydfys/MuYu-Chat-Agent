package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentModelMessage
import com.muyuchat.core.agent.contract.AgentModelMessageKind
import com.muyuchat.core.agent.contract.AgentTranscriptRole
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** N05c request declarations and terminal-call reconstruction. */
class AgentResponsesWireTest {

    private fun readTool(): AgentToolDescriptor = AgentToolDescriptor(
        toolKey = "workspace.read_text",
        version = "1",
        displayName = "读取文本",
        description = "Read a UTF-8 text file under inputs/.",
        schemaJson = "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}," +
            "\"required\":[\"path\"],\"additionalProperties\":false}",
        schemaHash = "a".repeat(64),
        sideEffect = AgentToolSideEffect.READ_ONLY,
        dataClass = AgentDataClass.PRIVATE
    )

    @Test
    fun dottedToolKeysBecomeStableResponsesNamesAndMapBack() {
        val declarations = AgentResponsesToolDeclarations.declarations(
            listOf(readTool(), readTool().copy(toolKey = "artifact.write_text"))
        )

        assertEquals(2, declarations.toolsJson.length())
        val first = declarations.toolsJson.getJSONObject(0)
        assertEquals("function", first.getString("type"))
        assertEquals("artifact_write_text", first.getString("name"))
        assertEquals("workspace_read_text", declarations.toolsJson.getJSONObject(1).getString("name"))
        assertEquals(
            "workspace.read_text",
            declarations.wireNameToToolKey["workspace_read_text"]
        )
        assertTrue(first.getJSONObject("parameters").getBoolean("additionalProperties").not())
    }

    @Test
    fun aWireNameCollisionGetsADeterministicSuffix() {
        val mcp = readTool().copy(toolKey = "mcp:serv:read_text")
        val builtIn = readTool().copy(toolKey = "mcp_serv.read_text")

        val declarations = AgentResponsesToolDeclarations.declarations(listOf(mcp, builtIn))

        val names = (0 until declarations.toolsJson.length())
            .map { declarations.toolsJson.getJSONObject(it).getString("name") }
        assertEquals(2, names.distinct().size)
        assertEquals(
            "mcp_serv_read_text",
            declarations.wireNameToToolKey.entries.first { it.value == "mcp:serv:read_text" }.key
        )
        assertEquals(
            "mcp_serv_read_text",
            declarations.wireNameToToolKey.entries
                .first { it.value == "mcp_serv.read_text" }.key
                .substringBeforeLast('_')
        )
    }

    @Test
    fun declarationsRejectAnUnusableCatalogBeforeTheWire() {
        val bad = readTool().copy(
            schemaJson = "{\"type\":\"object\",\"properties\":{}}",
            description = "x"
        )
        assertTrue(AgentResponsesToolDeclarations.validate(listOf(bad))!!.contains("additional properties"))
        assertTrue(
            AgentResponsesToolDeclarations.validate(
                listOf(readTool().copy(schemaJson = "not json"))
            )!!.contains("unreadable schema")
        )
        assertEquals(null, AgentResponsesToolDeclarations.validate(listOf(readTool())))
    }

    @Test
    fun nativeCallAndOutputBecomeProviderInputItemsInTranscriptOrder() {
        val messages = listOf(
            AgentModelMessage("agent-msg-0", AgentTranscriptRole.SYSTEM, "instructions"),
            AgentModelMessage("agent-msg-1", AgentTranscriptRole.USER, "整理简报"),
            AgentModelMessage(
                "agent-msg-2",
                AgentTranscriptRole.ASSISTANT,
                """{"path":"inputs/a.txt"}""",
                kind = AgentModelMessageKind.FUNCTION_CALL,
                callId = "call_1",
                toolName = "workspace.read_text"
            ),
            AgentModelMessage(
                "agent-msg-3",
                AgentTranscriptRole.USER,
                """{"status":"SUCCEEDED"}""",
                kind = AgentModelMessageKind.FUNCTION_OUTPUT,
                callId = "call_1"
            )
        )

        val input = agentResponsesInputJson(messages)!!

        assertEquals(3, input.length())
        assertEquals("user", input.getJSONObject(0).getString("role"))
        val call = input.getJSONObject(1)
        assertEquals("function_call", call.getString("type"))
        assertEquals("call_1", call.getString("call_id"))
        assertEquals("workspace.read_text", call.getString("name"))
        assertEquals("""{"path":"inputs/a.txt"}""", call.getString("arguments"))
        val output = input.getJSONObject(2)
        assertEquals("function_call_output", output.getString("type"))
        assertEquals("call_1", output.getString("call_id"))
        assertEquals("""{"status":"SUCCEEDED"}""", output.getString("output"))
    }

    @Test
    fun aTextOnlyTranscriptKeepsTheSharedChatInput() {
        assertEquals(
            null,
            agentResponsesInputJson(
                listOf(
                    AgentModelMessage("agent-msg-0", AgentTranscriptRole.SYSTEM, "instructions"),
                    AgentModelMessage("agent-msg-1", AgentTranscriptRole.USER, "goal")
                )
            )
        )
    }

    @Test
    fun completedOutputKeepsEveryFunctionCallInProviderOrder() {
        val data = """
            {"type":"response.completed","response":{"status":"completed","output":[
              {"type":"function_call","call_id":"call_1","name":"workspace_read_text","arguments":"{}"},
              {"type":"function_call","call_id":"call_2","name":"workspace_list","arguments":"{}"}
            ]}}
        """.trimIndent()

        val calls = AgentResponsesTurnProbe.calls(
            data,
            mapOf(
                "workspace_read_text" to "workspace.read_text",
                "workspace_list" to "workspace.list"
            )
        )

        assertEquals(listOf("call_1", "call_2"), calls.map { it.callId })
        assertEquals(listOf("workspace.read_text", "workspace.list"), calls.map { it.name })
    }

    @Test
    fun completedOutputReconstructsCallIdsNamesAndArguments() {
        val data = """
            {"type":"response.completed","response":{"status":"completed","output":[
              {"type":"message","role":"assistant","content":[]},
              {"type":"function_call","call_id":"call_1","name":"workspace_read_text",
               "arguments":"{\"path\":\"inputs/a.txt\"}"}
            ]}}
        """.trimIndent()

        val calls = AgentResponsesTurnProbe.calls(
            data,
            mapOf("workspace_read_text" to "workspace.read_text")
        )

        assertEquals(1, calls.size)
        assertEquals("call_1", calls.single().callId)
        assertEquals("workspace.read_text", calls.single().name)
        assertEquals("""{"path":"inputs/a.txt"}""", calls.single().argumentsJson)
        assertEquals("responses:completed", AgentResponsesTurnProbe.terminalProof(data))
    }

    @Test
    fun nonTerminalOrMalformedEventsNeverProduceCalls() {
        assertTrue(
            AgentResponsesTurnProbe.calls(
                """{"type":"response.output_item.added","item":{"type":"function_call","name":"x"}}""",
                emptyMap()
            ).isEmpty()
        )
        assertTrue(AgentResponsesTurnProbe.calls("not json", emptyMap()).isEmpty())
        assertFalse(AgentResponsesTurnProbe.isCompletedEvent("not json"))
        assertEquals(null, AgentResponsesTurnProbe.terminalProof("not json"))
    }

    @Test
    fun aCompletedTurnWithoutFunctionCallsStaysATextTurn() {
        val data = """{"type":"response.completed","response":{"status":"completed","output":[]}}"""

        assertTrue(AgentResponsesTurnProbe.calls(data, emptyMap()).isEmpty())
        assertEquals("responses:completed", AgentResponsesTurnProbe.terminalProof(data))
        assertEquals(
            "completed",
            JSONObject("""{"status":"completed"}""").getString("status")
        )
    }
}
