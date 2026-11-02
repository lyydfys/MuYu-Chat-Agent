package com.muyuchat.api.dsh.gateway

import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the idle keep-alive shape: an SSE comment alone does not reset the official pi-ai client's
 * 300 s stream watchdog, so the heartbeat must also carry a valid empty-delta data event.
 */
class DshGatewaySseWriterTest {

    @Test
    fun idleHeartbeatWritesAnEmptyDeltaDataEventBeforeDone() {
        ServerSocket(0).use { server ->
            val client = Socket("127.0.0.1", server.localPort)
            val accepted: Socket = server.accept()
            try {
                val writer = DshGatewaySseWriter(accepted)
                writer.start("chatcmpl-test", 123L, "fake-model", "call-1")
                writer.idleHeartbeat("mca-heartbeat")
                writer.done()
                val text = readUntilDone(client)
                assertTrue("role chunk present", text.contains("\"role\":\"assistant\""))
                assertTrue(
                    "empty delta data event present",
                    text.contains("chat.completion.chunk") && text.contains("\"delta\":{}")
                )
                assertTrue("comment retained for debugging", text.contains(": mca-heartbeat"))
                assertTrue("stream terminated", text.contains("[DONE]"))
            } finally {
                runCatching { accepted.close() }
                runCatching { client.close() }
            }
        }
    }

    private fun readUntilDone(socket: Socket): String {
        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
        val text = StringBuilder()
        val buffer = CharArray(2048)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            text.append(buffer, 0, read)
            if (text.contains("[DONE]")) break
        }
        return text.toString()
    }
}
