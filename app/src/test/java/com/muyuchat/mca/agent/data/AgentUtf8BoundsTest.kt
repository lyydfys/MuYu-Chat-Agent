package com.muyuchat.mca.agent.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentUtf8BoundsTest {
    @Test
    fun `ascii window consumes every byte`() {
        val decoded = AgentUtf8Bounds.decodeCompletePrefix("hello".toByteArray(), atEof = true)
        assertEquals("hello", decoded.text)
        assertEquals(5, decoded.consumedBytes)
    }

    @Test
    fun `empty window is empty`() {
        val decoded = AgentUtf8Bounds.decodeCompletePrefix(ByteArray(0), atEof = false)
        assertEquals("", decoded.text)
        assertEquals(0, decoded.consumedBytes)
    }

    @Test
    fun `incomplete trailing sequence is trimmed when more bytes may follow`() {
        val bytes = "aé".toByteArray(Charsets.UTF_8)
        val decoded = AgentUtf8Bounds.decodeCompletePrefix(bytes.copyOf(2), atEof = false)
        assertEquals("a", decoded.text)
        assertEquals(1, decoded.consumedBytes)
        val full = AgentUtf8Bounds.decodeCompletePrefix(bytes, atEof = true)
        assertEquals("aé", full.text)
        assertEquals(3, full.consumedBytes)
    }

    @Test
    fun `incomplete sequence at eof is invalid`() {
        val bytes = "é".toByteArray(Charsets.UTF_8)
        try {
            AgentUtf8Bounds.decodeCompletePrefix(bytes.copyOf(1), atEof = true)
            fail("truncated eof must fail")
        } catch (error: AgentStorageException) {
            assertEquals(AgentDataErrorCodes.INPUT_INVALID, error.code)
        }
    }

    @Test
    fun `malformed overlong surrogate and invalid leads are rejected`() {
        listOf(
            byteArrayOf(0xC0.toByte(), 0x80.toByte()),
            byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()),
            byteArrayOf(0x80.toByte()),
            byteArrayOf(0xFF.toByte())
        ).forEach { bytes ->
            try {
                AgentUtf8Bounds.decodeCompletePrefix(bytes, atEof = true)
                fail("expected malformed UTF-8 rejection for ${bytes.toList()}")
            } catch (error: AgentStorageException) {
                assertEquals(AgentDataErrorCodes.INPUT_INVALID, error.code)
            }
        }
    }

    @Test
    fun `two three and four byte characters decode strictly`() {
        val text = "é€𝄞"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val decoded = AgentUtf8Bounds.decodeCompletePrefix(bytes, atEof = true)
        assertEquals(text, decoded.text)
        assertEquals(bytes.size, decoded.consumedBytes)
        assertTrue(AgentUtf8Bounds.isCharBoundary(bytes[0].toInt()))
        assertFalse(AgentUtf8Bounds.isCharBoundary(bytes[1].toInt()))
    }
}
