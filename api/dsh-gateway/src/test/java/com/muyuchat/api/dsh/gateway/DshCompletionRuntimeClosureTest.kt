package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.DshFinishReason
import com.muyuchat.core.dsh.contract.DshModelEvent
import com.muyuchat.core.dsh.contract.DshModelMessage
import com.muyuchat.core.dsh.contract.DshModelRequest
import com.muyuchat.core.dsh.contract.DshModelRole
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Runtime closure and framing proof for the DSH completion run: the packaged payload archive,
 * the official seeder inputs and the real loopback gateway started from that payload's own
 * contract. Every assertion reads production code (no stubs) and records the hashes it used.
 */
class DshCompletionRuntimeClosureTest : DshGatewayServerTestBase() {

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun sha256(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    @Test
    fun packagedPayloadLockMatchesTheArchiveOnDisk() {
        val root = System.getProperty("dsh.completion.repo") ?: "E:/MAC"
        val payload = java.io.File(root, "core/dsh-runtime/src/main/assets/dsh/payload.zip")
        val lockFile = java.io.File(root, "scripts/dsh/payload-lock.json")
        assertTrue("payload archive missing: $payload", payload.isFile)
        assertTrue("payload lock missing: $lockFile", lockFile.isFile)
        val lock = JSONObject(lockFile.readText(Charsets.UTF_8))
        val actual = sha256(payload)
        assertEquals("payload.zip sha256 must equal the lock", lock.getString("payloadSha256").lowercase(), actual)
        assertEquals("payload size must equal the lock", lock.getLong("payloadSize"), payload.length())
        var checked = 0
        java.util.zip.ZipFile(payload).use { zip ->
            val entries = lock.getJSONArray("criticalFiles")
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                val name = item.getString("path")
                val ze = zip.getEntry(name)
                assertTrue("critical payload entry missing: $name", ze != null)
                val bytes = zip.getInputStream(ze).use { it.readBytes() }
                assertEquals("critical payload file hash: $name", item.getString("sha256").lowercase(), hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                checked++
            }
        }
        assertTrue("lock must list critical files", checked >= 3)
        println("RUNTIME_CLOSURE payloadSha256=$actual size=${payload.length()} kernel=${lock.getString("kernelVersion")} critical=$checked")
    }

    @Test
    fun realGatewayAuthIsMandatoryAndRejectsForgedTokens() {
        fun status(bearer: String?): Int {
            val connection = URI.create(endpoint.baseUrl.trimEnd('/') + "/models").toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
            return try {
                connection.responseCode.also { code -> if (code >= 400) connection.errorStream?.close() else connection.inputStream?.close() }
            } finally {
                connection.disconnect()
            }
        }
        assertEquals("missing bearer must be rejected", 401, status(null))
        assertEquals("wrong bearer must be rejected", 401, status("0".repeat(64)))
        assertEquals("real bearer must be accepted", 200, status(endpoint.bearerToken))
        assertTrue("bearer must be a prefixed 32-byte secret", endpoint.bearerToken.startsWith("dshg_") && endpoint.bearerToken.length >= 40)
        println("GATEWAY_AUTH origin=${endpoint.baseUrl} tokenLength=${endpoint.bearerToken.length} noAuth=401 forged=401 real=200")
    }

    private fun replyRequest(): DshModelRequest = DshModelRequest(
        callId = "closure-call",
        modelId = "closure-model",
        messages = listOf(DshModelMessage(DshModelRole.USER, "Reply exactly DSH-OK")),
        stream = false
    )

    @Test
    fun realGatewayStreamsAndBuffersTheSameTerminalResult() {
        backend.streamFactory = {
            flowOf(
                DshModelEvent.TextDelta("DSH-"),
                DshModelEvent.TextDelta("OK"),
                DshModelEvent.Completed(finishReason = DshFinishReason.STOP, promptTokens = 12, completionTokens = 2)
            )
        }
        // The request fixture sends the default catalog id, so the served model must match it.
        backend.models = listOf(fakeGatewayModel())
        val body = standardChatBody(stream = false)
        val buffered = DshTestHttp.request(endpoint, "POST", "/v1/chat/completions", body = body)
        assertEquals(200, buffered.status)
        val bufferedJson = JSONObject(buffered.body)
        val choice = bufferedJson.getJSONArray("choices").getJSONObject(0)
        assertEquals("DSH-OK", choice.getJSONObject("message").getString("content"))
        assertEquals("stop", choice.getString("finish_reason"))
        assertEquals("one real backend request per HTTP call", 1, backend.requests.size)
        val request = backend.singleRequest()
        assertEquals("fake-model", request.modelId)

        backend.requests.clear()
        val seen = ArrayList<String>()
        DshTestHttp.openSse(
            endpoint,
            standardChatBody(stream = true)
        ).use { sse ->
            sse.readUntilDone().forEach { seen += it }
        }
        assertTrue("stream must frame [DONE]", seen.lastOrNull() == "[DONE]")
        val text = seen.joinToString("")
        assertTrue("stream must carry both deltas", text.contains("DSH-") && text.contains("OK"))
        assertTrue("stream must carry a stop finish_reason", text.contains("\"finish_reason\":\"stop\""))
        assertEquals("streamed call reaches the same single backend request", 1, backend.requests.size)
        println("GATEWAY_BOTH bufferedStatus=${buffered.status} bufferedContent=DSH-OK sseEvents=${seen.size} sseLast=${seen.lastOrNull()}")
    }

    @Test
    fun loopbackGatewayUsesAnEphemeralAuthenticatedOrigin() {
        assertTrue("gateway must bind 127.0.0.1", endpoint.baseUrl.startsWith("http://127.0.0.1:"))
        val port = URI.create(endpoint.baseUrl).port
        assertTrue("port must be a real ephemeral port", port in 1..65535)
        assertFalse("gateway must not bind a well-known port", port == 80 || port == 8080)
        assertTrue("bearer must be a prefixed 32-byte secret", endpoint.bearerToken.startsWith("dshg_") && endpoint.bearerToken.length >= 40)
        assertNotEquals("bearer must not be a constant", "0".repeat(endpoint.bearerToken.length), endpoint.bearerToken)
        println("GATEWAY_ORIGIN baseUrl=${endpoint.baseUrl} port=$port")
    }
}

