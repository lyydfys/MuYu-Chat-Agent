package com.muyuchat.feature.settings

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalApiSelfTestImageTest {
    private fun imageBytes(): ByteArray =
        Base64.getDecoder().decode(LOCAL_API_VISION_SELF_TEST_PNG_BASE64)

    @Test
    fun `vision self-test PNG has valid checksums for every chunk`() {
        val bytes = imageBytes()
        assertArrayEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
            bytes.copyOfRange(0, 8)
        )
        var offset = 8
        val chunks = mutableListOf<String>()
        while (offset < bytes.size) {
            assertTrue("complete PNG chunk header", bytes.size - offset >= 12)
            val length = ByteBuffer.wrap(bytes, offset, 4).int
            assertTrue("chunk data stays within PNG", length >= 0 && length <= bytes.size - offset - 12)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val expected = ByteBuffer.wrap(bytes, offset + 8 + length, 4).int.toLong() and 0xffffffffL
            val actual = CRC32().apply { update(bytes, offset + 4, length + 4) }.value
            assertEquals("$type CRC", expected, actual)
            chunks += type
            offset += length + 12
        }
        assertEquals(listOf("IHDR", "IDAT", "IEND"), chunks)
        assertEquals(bytes.size, offset)
    }

    @Test
    fun `vision self-test keeps its original dimensions and opaque white scanline`() {
        val bytes = imageBytes()
        assertEquals(1, ByteBuffer.wrap(bytes, 16, 4).int)
        assertEquals(1, ByteBuffer.wrap(bytes, 20, 4).int)
        assertEquals(8, bytes[24].toInt()) // Eight-bit samples.
        assertEquals(4, bytes[25].toInt()) // Grayscale with alpha.
        val scanline = InflaterInputStream(ByteArrayInputStream(bytes, 41, 11)).use { it.readBytes() }
        // Sub filter, first grayscale sample 255, first alpha sample 255.
        assertArrayEquals(byteArrayOf(1, 0xff.toByte(), 0xff.toByte()), scanline)
    }
}
