package com.muyuchat.core.modelstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PushbackInputStream
import kotlin.math.min

class GgufMetadataReaderTest {
    @Test
    fun ggufMagicAndMetadataSurviveLegalShortBulkReads() {
        val stream = ShortBulkReadInputStream(minimalGguf(version = 3), maxBulkBytes = 1)

        val metadata = GgufMetadataReader.read(stream, "model.gguf")

        assertTrue(metadata.isGguf)
        assertEquals(3, metadata.version)
    }

    @Test
    fun prefixReaderMakesProgressWhenProviderReturnsZeroFromBulkRead() {
        val stream = ZeroThenShortBulkReadInputStream("GGUFrest".toByteArray())

        assertEquals("GGUF", stream.readPrefix(4).toString(Charsets.US_ASCII))
        assertEquals('r'.code, stream.read())
    }

    @Test
    fun probedMagicCanBeReplayedIntoTheSingleStableCopyStream() {
        val payload = minimalGguf(version = 3)
        val source = PushbackInputStream(ShortBulkReadInputStream(payload, maxBulkBytes = 2), 4)
        val header = source.readPrefix(4)

        source.unread(header)

        assertTrue(payload.contentEquals(source.readBytes()))
    }

    @Test
    fun truncatedMagicIsNotAcceptedAsGguf() {
        val metadata = GgufMetadataReader.read(ByteArrayInputStream("GGU".toByteArray()), "model.gguf")

        assertFalse(metadata.isGguf)
    }

    @Test
    fun readsTheExactArchitectureContextLength() {
        val metadata = GgufMetadataReader.read(
            ByteArrayInputStream(ggufWithContext("qwen35moe", 262_144)),
            "renamed-model.gguf"
        )

        assertTrue(metadata.isGguf)
        assertEquals("qwen35moe", metadata.architecture)
        assertEquals(262_144, metadata.contextLength)
    }

    private fun ggufWithContext(architecture: String, contextLength: Int): ByteArray =
        ByteArrayOutputStream().apply {
            write("GGUF".toByteArray())
            write(uint32Le(3L))
            write(uint64Le(0L))
            write(uint64Le(2L))
            writeGgufString("general.architecture")
            write(uint32Le(8))
            writeGgufString(architecture)
            writeGgufString("$architecture.context_length")
            write(uint32Le(4))
            write(uint32Le(contextLength.toLong()))
        }.toByteArray()

    private fun ByteArrayOutputStream.writeGgufString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        write(uint64Le(bytes.size.toLong()))
        write(bytes)
    }

    private fun minimalGguf(version: Int): ByteArray = buildList<Byte> {
        addAll("GGUF".toByteArray().toList())
        addAll(uint32Le(version.toLong()).toList())
        addAll(uint64Le(0L).toList()) // tensor count
        addAll(uint64Le(0L).toList()) // metadata count
    }.toByteArray()

    private fun uint32Le(value: Long): ByteArray =
        ByteArray(4) { index -> ((value ushr (index * 8)) and 0xff).toByte() }

    private fun uint64Le(value: Long): ByteArray =
        ByteArray(8) { index -> ((value ushr (index * 8)) and 0xff).toByte() }

    private class ShortBulkReadInputStream(
        bytes: ByteArray,
        private val maxBulkBytes: Int
    ) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, min(length, maxBulkBytes))
    }

    private class ZeroThenShortBulkReadInputStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        private var returnedZero = false

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!returnedZero) {
                returnedZero = true
                return 0
            }
            return delegate.read(buffer, offset, min(length, 1))
        }
    }
}
