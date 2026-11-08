package com.muyuchat.api.local.a2a

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A2A 1.0 wire format: request parsing, the discovery card and the JSON-RPC error envelope.
 *
 * Pure JUnit — no server, no socket, no agent runtime.
 */
class A2aProtocolTest {

    @Test
    fun sendMessageIsParsed() {
        val request = A2aProtocol.parseJsonRpc(
            """
            {"jsonrpc":"2.0","id":"req-1","method":"SendMessage",
             "params":{"message":{"text":"检查一下构建日志"}}}
            """.trimIndent()
        )
        assertNotNull(request)
        assertEquals(A2aMethod.SEND_MESSAGE, request!!.method)
        assertEquals("req-1", request.id)
        assertEquals("检查一下构建日志", request.messageText)
        assertNull("SendMessage has no task id", request.taskId)
    }

    @Test
    fun getTaskIsParsedAndAcceptsBothIdSpellings() {
        val byId = A2aProtocol.parseJsonRpc(
            """{"jsonrpc":"2.0","id":2,"method":"GetTask","params":{"id":"task-9"}}"""
        )
        assertEquals(A2aMethod.GET_TASK, byId!!.method)
        assertEquals("task-9", byId.taskId)

        val byTaskId = A2aProtocol.parseJsonRpc(
            """{"jsonrpc":"2.0","id":"x","method":"GetTask","params":{"taskId":"task-7"}}"""
        )
        assertEquals("task-7", byTaskId!!.taskId)
    }

    @Test
    fun unknownMethodsAndMalformedEnvelopesReturnNull() {
        assertNull(
            "unsupported method",
            A2aProtocol.parseJsonRpc("""{"jsonrpc":"2.0","id":"1","method":"DeleteTask","params":{}}""")
        )
        assertNull(
            "missing method",
            A2aProtocol.parseJsonRpc("""{"jsonrpc":"2.0","id":"1","params":{}}""")
        )
        assertNull(
            "wrong jsonrpc version",
            A2aProtocol.parseJsonRpc("""{"jsonrpc":"1.0","id":"1","method":"SendMessage","params":{}}""")
        )
        assertNull(
            "params not an object",
            A2aProtocol.parseJsonRpc("""{"jsonrpc":"2.0","id":"1","method":"SendMessage","params":[]}""")
        )
        assertNull("not json", A2aProtocol.parseJsonRpc("not json at all"))
        assertNull("empty body", A2aProtocol.parseJsonRpc("   "))
        assertNull("json array root", A2aProtocol.parseJsonRpc("[]"))
    }

    @Test
    fun missingParamsIsTreatedAsEmptyObject() {
        val request = A2aProtocol.parseJsonRpc(
            """{"jsonrpc":"2.0","id":"1","method":"SendMessage"}"""
        )
        assertNotNull(request)
        assertEquals(0, request!!.params.length())
        assertNull("no message text without params", request.messageText)
    }

    @Test
    fun blankMessageTextIsNotReported() {
        val request = A2aProtocol.parseJsonRpc(
            """{"jsonrpc":"2.0","id":"1","method":"SendMessage","params":{"message":{"text":"   "}}}"""
        )
        assertNull(request!!.messageText)
    }

    @Test
    fun agentCardHasEveryDocumentedField() {
        val card = AgentCard(
            name = "MCA Agent",
            version = "1.0.0",
            capabilities = listOf("chat", "device-shell")
        )
        val json = JSONObject(card.toJson())

        assertEquals("MCA Agent", json.getString("name"))
        assertEquals("1.0.0", json.getString("version"))
        assertEquals("1.0", json.getString("protocolVersion"))
        assertEquals(2, json.getJSONArray("capabilities").length())
        assertEquals("chat", json.getJSONArray("capabilities").getString(0))
        assertEquals("device-shell", json.getJSONArray("capabilities").getString(1))
        assertEquals("jsonrpc", json.getJSONObject("transport").getString("type"))
        assertEquals("/a2a", json.getJSONObject("transport").getString("path"))
        assertEquals("bearer", json.getJSONObject("authentication").getString("scheme"))
        assertTrue(json.getJSONObject("authentication").getBoolean("required"))
        // Discovery is unauthenticated, so the path is part of the published contract.
        assertEquals("/.well-known/agent-card.json", A2aProtocol.AGENT_CARD_PATH)
    }

    @Test
    fun agentCardRejectsBlankIdentity() {
        assertTrue(
            runCatching { AgentCard(" ", "1.0.0", emptyList()) }.exceptionOrNull()
                is IllegalArgumentException
        )
        assertTrue(
            runCatching { AgentCard("MCA", "", emptyList()) }.exceptionOrNull()
                is IllegalArgumentException
        )
    }

    @Test
    fun errorEnvelopeMatchesJsonRpcShape() {
        val json = JSONObject(
            A2aProtocol.jsonRpcError("req-42", A2aErrorCode.METHOD_NOT_FOUND, "unknown method")
        )
        assertEquals("2.0", json.getString("jsonrpc"))
        assertEquals("req-42", json.getString("id"))
        assertFalse(json.has("result"))
        assertEquals(-32601, json.getJSONObject("error").getInt("code"))
        assertEquals("unknown method", json.getJSONObject("error").getString("message"))
    }

    @Test
    fun errorEnvelopeKeepsANullId() {
        val json = JSONObject(
            A2aProtocol.jsonRpcError(null, A2aErrorCode.PARSE_ERROR, "bad json")
        )
        assertTrue("a parse error has no id", json.isNull("id"))
        assertEquals(-32700, json.getJSONObject("error").getInt("code"))
    }

    @Test
    fun successEnvelopeMatchesJsonRpcShape() {
        val json = JSONObject(
            A2aProtocol.jsonRpcResult("req-1", JSONObject().put("status", "accepted"))
        )
        assertEquals("2.0", json.getString("jsonrpc"))
        assertEquals("req-1", json.getString("id"))
        assertEquals("accepted", json.getJSONObject("result").getString("status"))
        assertFalse(json.has("error"))
    }

    @Test
    fun versionHeaderAcceptsOnlyTheSupportedMajor() {
        assertTrue(A2aProtocol.acceptsVersion("1.0"))
        assertTrue(A2aProtocol.acceptsVersion(" 1 "))
        assertTrue(A2aProtocol.acceptsVersion("1"))
        assertFalse(A2aProtocol.acceptsVersion("2.0"))
        assertFalse(A2aProtocol.acceptsVersion("0.9"))
        assertFalse(A2aProtocol.acceptsVersion(""))
        assertFalse(A2aProtocol.acceptsVersion(null))
    }

    @Test
    fun protocolConstantsMatchTheOperitContract() {
        assertEquals("/a2a", A2aProtocol.RPC_PATH)
        assertEquals("/.well-known/agent-card.json", A2aProtocol.AGENT_CARD_PATH)
        assertEquals("1.0", A2aProtocol.PROTOCOL_VERSION)
        assertEquals("a2a-version", A2aProtocol.VERSION_HEADER)
        assertEquals("SendMessage", A2aMethod.SEND_MESSAGE.wireName)
        assertEquals("GetTask", A2aMethod.GET_TASK.wireName)
        assertEquals(
            "a2a_task_bridge_not_implemented",
            A2aProtocol.TASK_BRIDGE_NOT_IMPLEMENTED
        )
    }
}
