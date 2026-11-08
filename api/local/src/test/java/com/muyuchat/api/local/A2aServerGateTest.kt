package com.muyuchat.api.local

import com.muyuchat.api.local.a2a.A2aProtocol
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The A2A surface is opt-in.
 *
 * These cases pin the gate itself: with `a2aEnabled = false` (the default every existing constructor
 * call gets) both A2A paths are 404 and the rest of the Local API behaves exactly as before; only with
 * the flag does the discovery card appear. A real socket is used because the gate lives in the request
 * handler, not in a pure function.
 */
class A2aServerGateTest {

    @Test
    fun disabledByDefaultSoA2aPathsAre404() {
        withServer(a2aEnabled = false) { port ->
            val card = rawHttp(port, "GET ${A2aProtocol.AGENT_CARD_PATH} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertTrue("agent card must 404 while disabled, got $card", card.startsWith("HTTP/1.1 404"))

            val rpc = rawHttp(port, a2aPost("""{"jsonrpc":"2.0","id":"1","method":"SendMessage"}"""))
            assertTrue("POST /a2a must 404 while disabled, got $rpc", rpc.startsWith("HTTP/1.1 404"))
        }
    }

    @Test
    fun disablingA2aLeavesExistingRoutesUntouched() {
        withServer(a2aEnabled = false) { port ->
            assertTrue(
                "health must keep working",
                rawHttp(port, "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").startsWith("HTTP/1.1 200")
            )
            assertTrue(
                "models must keep working",
                rawHttp(port, "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").startsWith("HTTP/1.1 200")
            )
            assertTrue(
                "an unknown path still 404s for an authenticated caller",
                rawHttp(port, authenticatedGet("/nope")).startsWith("HTTP/1.1 404")
            )
        }
    }

    @Test
    fun enablingServesTheAgentCardWithoutAuthentication() {
        withServer(a2aEnabled = true) { port ->
            val response = rawHttp(port, "GET ${A2aProtocol.AGENT_CARD_PATH} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertTrue("agent card must be public", response.startsWith("HTTP/1.1 200"))
            val card = JSONObject(response.substringAfter("\r\n\r\n"))
            assertEquals("MCA Agent", card.getString("name"))
            assertEquals(A2aProtocol.PROTOCOL_VERSION, card.getString("protocolVersion"))
            assertTrue(card.getJSONArray("capabilities").length() > 0)
        }
    }

    @Test
    fun enablingStillRequiresBearerAuthForTheRpcEndpoint() {
        withServer(a2aEnabled = true) { port ->
            val unauthenticated = rawHttp(
                port,
                "POST ${A2aProtocol.RPC_PATH} HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Content-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"
            )
            assertTrue(
                "POST /a2a must not be public just because it is enabled",
                unauthenticated.startsWith("HTTP/1.1 401")
            )
        }
    }

    @Test
    fun enabledRpcAnswersATypedErrorInsteadOfPretendingSuccess() {
        withServer(a2aEnabled = true) { port ->
            val response = rawHttp(
                port,
                a2aPost("""{"jsonrpc":"2.0","id":"req-1","method":"SendMessage","params":{"message":{"text":"hi"}}}""")
            )
            assertTrue(response.startsWith("HTTP/1.1 200"))
            val error = JSONObject(response.substringAfter("\r\n\r\n")).getJSONObject("error")
            assertEquals(
                A2aProtocol.TASK_BRIDGE_NOT_IMPLEMENTED,
                error.getString("message")
            )
            assertFalse("no result may be returned before the bridge exists", response.contains("\"result\""))
        }
    }

    @Test
    fun a2aRequiresTheVersionHeader() {
        withServer(a2aEnabled = true) { port ->
            val response = rawHttp(
                port,
                "POST ${A2aProtocol.RPC_PATH} HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Authorization: Bearer secret\r\nContent-Type: application/json\r\n" +
                    "Content-Length: 2\r\n\r\n{}"
            )
            assertTrue(response.startsWith("HTTP/1.1 200"))
            val json = JSONObject(response.substringAfter("\r\n\r\n"))
            assertEquals(-32600, json.getJSONObject("error").getInt("code"))
        }
    }

    private fun authenticatedGet(path: String): String =
        "GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer secret\r\n\r\n"

    private fun a2aPost(body: String): String = buildString {
        append("POST ${A2aProtocol.RPC_PATH} HTTP/1.1\r\n")
        append("Host: 127.0.0.1\r\n")
        append("Authorization: Bearer secret\r\n")
        append("A2A-Version: ${A2aProtocol.PROTOCOL_VERSION}\r\n")
        append("Content-Type: application/json\r\n")
        append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n")
        append(body)
    }

    private fun withServer(a2aEnabled: Boolean, block: (Int) -> Unit) {
        val port = freePort()
        val server = McaLoopbackServer(
            port = port,
            bindHost = "127.0.0.1",
            apiKey = "secret",
            a2aEnabled = a2aEnabled
        )
        try {
            server.clearProcessIdempotencyCacheForTests()
            server.start()
            block(port)
        } finally {
            server.shutdown()
        }
    }

    private fun rawHttp(port: Int, request: String): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            socket.shutdownOutput()
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
