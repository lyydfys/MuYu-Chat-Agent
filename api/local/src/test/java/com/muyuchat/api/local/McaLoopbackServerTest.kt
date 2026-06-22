package com.muyuchat.api.local

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class McaLoopbackServerTest {
    @Test
    fun preflightAlwaysReturnsCorsHeaders() {
        withServer(apiKey = "secret") { port ->
            val response = rawHttp(
                port,
                "OPTIONS /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nOrigin: http://localhost\r\n\r\n"
            )

            assertTrue(response.startsWith("HTTP/1.1 204 No Content"))
            assertTrue(response.contains("Access-Control-Allow-Origin: *"))
            assertTrue(response.contains("Access-Control-Allow-Private-Network: true"))
        }
    }

    @Test
    fun healthAndWebPageDoNotRequireApiKey() {
        withServer(apiKey = "secret") { port ->
            val health = rawHttp(port, "GET /health HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            val page = rawHttp(port, "GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

            assertTrue(health.startsWith("HTTP/1.1 200 OK"))
            assertTrue(health.contains("MuYu Chat Agent"))
            assertTrue(page.startsWith("HTTP/1.1 200 OK"))
            assertTrue(page.contains("MCA Web Chat"))
        }
    }

    @Test
    fun protectedRoutesReturnOpenAiStyleErrorJsonWithoutKey() {
        withServer(apiKey = "secret") { port ->
            val response = rawHttp(port, "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

            assertTrue(response.startsWith("HTTP/1.1 401 Unauthorized"))
            assertTrue(response.contains("\"type\":\"mca_error\""))
            assertTrue(response.contains("\"code\":\"unauthorized\""))
        }
    }

    @Test
    fun protectedRoutesAcceptXApiKeyHeader() {
        withServer(apiKey = "secret") { port ->
            val response = rawHttp(
                port,
                "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\nX-API-Key: secret\r\n\r\n"
            )

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("\"object\":\"list\""))
        }
    }

    @Test
    fun chatRouteReturnsJsonErrorWhenEngineIsUnavailable() {
        withServer(apiKey = "secret") { port ->
            val body = """{"messages":[{"role":"user","content":"hi"}],"stream":false}"""
            val response = rawHttp(
                port,
                "POST /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Authorization: Bearer secret\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray().size}\r\n\r\n" +
                    body
            )

            assertTrue(response.startsWith("HTTP/1.1 503 Service Unavailable"))
            assertTrue(response.contains("\"code\":\"engine_unavailable\""))
        }
    }

    private fun withServer(apiKey: String, block: (Int) -> Unit) {
        val port = freePort()
        val server = McaLoopbackServer(port = port, bindHost = "127.0.0.1", apiKey = apiKey)
        try {
            LocalApiRuntime.engine = null
            LocalApiRuntime.modelsJsonProvider = { """{"object":"list","data":[]}""" }
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
