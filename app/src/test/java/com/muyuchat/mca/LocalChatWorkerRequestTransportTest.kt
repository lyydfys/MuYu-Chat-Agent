package com.muyuchat.mca

import android.content.Context
import android.content.ContextWrapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChatWorkerRequestTransportTest {
    @Test
    fun multiMegabyteUnicodePromptRoundTripsOutsideBinderPayload() {
        val largeMessages = "[{\"role\":\"user\",\"content\":\"${"长文本🙂".repeat(350_000)}\"}]"
        val request = LocalChatWorkerRequestTransport.BeginRequest(
            messagesJson = largeMessages,
            paramsJson = "{\"n_ctx\":1048576}",
            restoreStatePath = "/private/cache/read.state",
            writeStatePath = "/private/cache/write.state",
            fixedSystemPrompt = "固定角色设定"
        )

        val encoded = encode(request)
        val decoded = LocalChatWorkerRequestTransport.read(
            DataInputStream(ByteArrayInputStream(encoded))
        )

        assertEquals(request, decoded)
        assertEquals(true, decoded.hasPrefixCache)
    }

    @Test
    fun jsonExpansionBeyondEightMiBRoundTrips() {
        val expandedMessages = LocalChatWorkerRequestTransport.BeginRequest(
            messagesJson = "x".repeat(8 * 1024 * 1024 + 1),
            paramsJson = "{}",
            restoreStatePath = null,
            writeStatePath = null,
            fixedSystemPrompt = null
        )

        assertEquals(
            expandedMessages,
            LocalChatWorkerRequestTransport.read(
                DataInputStream(ByteArrayInputStream(encode(expandedMessages)))
            )
        )
    }

    @Test
    fun fieldSpecificLimitsAreEnforcedBeforeNativeExecution() {
        assertThrows(IllegalArgumentException::class.java) {
            encode(request(messagesJson = "x".repeat(16 * 1024 * 1024 + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            encode(request(paramsJson = "x".repeat(1 * 1024 * 1024 + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            encode(request(fixedSystemPrompt = "x".repeat(128 * 1024 + 1)))
        }
    }

    @Test
    fun truncatedPayloadIsRejectedBeforeNativeExecution() {
        val valid = encode(request())
        assertThrows(IOException::class.java) {
            LocalChatWorkerRequestTransport.read(
                DataInputStream(ByteArrayInputStream(valid.copyOf(valid.size - 1)))
            )
        }
    }

    @Test
    fun stalePromptPayloadsAreRemovedWithoutTouchingUnrelatedCacheFiles() {
        val cache = Files.createTempDirectory("mca-worker-transport").toFile()
        val requestDirectory = File(cache, "local_chat_requests").also { it.mkdirs() }
        val stale = File(requestDirectory, "begin-stale.bin").apply {
            writeText("private prompt")
            setLastModified(System.currentTimeMillis() - 2L * 24L * 60L * 60L * 1000L)
        }
        val unrelated = File(requestDirectory, "other.bin").apply { writeText("keep") }

        val payload = LocalChatWorkerRequestTransport.write(FakeContext(cache), request())

        assertFalse(stale.exists())
        assertTrue(unrelated.exists())
        assertTrue(payload.isFile)
        payload.delete()
        unrelated.delete()
        requestDirectory.delete()
        cache.delete()
    }

    private fun request(
        messagesJson: String = "[]",
        paramsJson: String = "{}",
        fixedSystemPrompt: String? = null
    ) = LocalChatWorkerRequestTransport.BeginRequest(
        messagesJson = messagesJson,
        paramsJson = paramsJson,
        restoreStatePath = null,
        writeStatePath = null,
        fixedSystemPrompt = fixedSystemPrompt
    )

    private fun encode(request: LocalChatWorkerRequestTransport.BeginRequest): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                LocalChatWorkerRequestTransport.write(output, request)
            }
            bytes.toByteArray()
        }

    private class FakeContext(private val cache: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = cache
    }
}
