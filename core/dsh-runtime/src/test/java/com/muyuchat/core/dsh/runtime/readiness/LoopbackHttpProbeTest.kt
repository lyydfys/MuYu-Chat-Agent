package com.muyuchat.core.dsh.runtime.readiness

import com.muyuchat.core.dsh.runtime.auth.DshLaunchEntry
import com.muyuchat.core.dsh.runtime.startup.DshStartupStage
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackHttpProbeTest {

    @Test
    fun preservesRealHttpErrorStatusBodyAndHeaders() {
        for (status in listOf(401, 403, 404, 500)) {
            LoopbackServer { _, _ ->
                Response(status, "error-$status", headers = mapOf("X-Dsh-Test" to "present"))
            }.use { server ->
                val response = LoopbackHttpProbe().get("${server.origin}/", null)
                assertEquals(status, response.code)
                assertEquals("error-$status", response.body)
                assertEquals("present", response.header("X-Dsh-Test"))
                assertNull(response.failure)
            }
        }
    }

    @Test
    fun emptyUnauthorizedBodyStillPreservesTheAuthenticationFence() {
        LoopbackServer { _, _ -> Response(401, "") }.use { server ->
            val response = LoopbackHttpProbe().get("${server.origin}/", null)
            assertEquals(401, response.code)
            assertEquals("", response.body)
            assertNull(response.failure)
        }
    }

    @Test
    fun actualHttpExchangeAndUnauthorizedFenceAllowTheOfficialReadinessSequence() {
        val paths = CopyOnWriteArrayList<String>()
        val cookie = "dsh-auth-test=v1.test.signature"
        val html = "<html><script>__DSH_BOOT_READY__</script>" +
            "<script src=\"/plugins/@deepseek-ai/dsh-client-modules/client.js\"></script></html>"
        LoopbackServer { path, headers ->
            paths += path
            when {
                path.startsWith("/?token=") -> Response(
                    303,
                    "",
                    headers = mapOf(
                        "Location" to "./",
                        "Set-Cookie" to "$cookie; Path=/; HttpOnly; SameSite=Strict"
                    )
                )
                headers["cookie"] != cookie -> Response(401, "authentication required")
                path.startsWith("/plugins/") -> Response(
                    200, "export const ready = true;\n".repeat(40), "application/javascript"
                )
                else -> Response(200, html, "text/html; charset=utf-8")
            }
        }.use { server ->
            val entry = DshLaunchEntry(
                "${server.origin}/?token=abcdefghijklmnopqrst", server.origin, server.port
            )
            val stages = mutableListOf<DshStartupStage>()
            val outcome = DshReadinessGate(
                probe = LoopbackHttpProbe(),
                stabilityDelayMillis = 0,
                readinessTimeoutMillis = 2_000,
                retryDelayMillis = 1
            ).awaitReady(entry, onStage = { stage -> stages += stage })
            assertTrue("Expected READY, got $outcome", outcome is DshReadinessGate.Outcome.Ready)
            assertEquals(server.origin, (outcome as DshReadinessGate.Outcome.Ready).origin)
            assertEquals(cookie, outcome.cookie)
            assertEquals(
                listOf(
                    DshStartupStage.PORT_LISTENING,
                    DshStartupStage.HTTP_AUTHENTICATED,
                    DshStartupStage.PLUGINS_LOADED
                ),
                stages
            )
            // One token exchange, index, client asset, stable index, unauthenticated index.
            assertEquals(5, paths.size)
            assertEquals("/", paths.last())
        }
    }

    private data class Response(
        val status: Int,
        val body: String,
        val contentType: String = "text/plain",
        val headers: Map<String, String> = emptyMap()
    )

    /** Real loopback HTTP, so HttpURLConnection's error-stream handling is exercised. */
    private class LoopbackServer(
        private val respond: (String, Map<String, String>) -> Response
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val failure = AtomicReference<Throwable?>()
        val port: Int get() = socket.localPort
        val origin: String get() = "http://127.0.0.1:$port"

        private val worker = thread(name = "dsh-probe-test-http", isDaemon = true) {
            try {
                while (!socket.isClosed) {
                    socket.accept().use { client ->
                        client.soTimeout = 2_000
                        val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val request = input.readLine() ?: return@use
                        val headers = mutableMapOf<String, String>()
                        var line = input.readLine()
                        while (!line.isNullOrEmpty()) {
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                            line = input.readLine()
                        }
                        val response = respond(request.split(' ')[1], headers)
                        val body = response.body.toByteArray(Charsets.UTF_8)
                        val head = buildString {
                            append("HTTP/1.1 ${response.status} Test\r\n")
                            append("Content-Length: ${body.size}\r\n")
                            append("Content-Type: ${response.contentType}\r\n")
                            append("Connection: close\r\n")
                            for ((name, value) in response.headers) append("$name: $value\r\n")
                            append("\r\n")
                        }
                        client.getOutputStream().apply {
                            write(head.toByteArray(Charsets.ISO_8859_1))
                            write(body)
                            flush()
                        }
                    }
                }
            } catch (error: Throwable) {
                if (error !is SocketException || !socket.isClosed) failure.set(error)
            }
        }

        override fun close() {
            socket.close()
            worker.join(3_000)
            assertFalse("HTTP fixture worker did not stop", worker.isAlive)
            assertNull("HTTP fixture failed", failure.get())
        }
    }
}
