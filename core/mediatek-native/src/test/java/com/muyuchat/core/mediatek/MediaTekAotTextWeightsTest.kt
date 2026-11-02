package com.muyuchat.core.mediatek

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekAotTextWeightsTest {
    @Test
    fun readsAFixtureWithAlignedDataBase() {
        val file = File.createTempFile("mca-atxw", ".bin")
        try {
            writeFixture(file)
            MediaTekAotTextWeights.open(file).use { weights ->
                assertEquals(2, weights.tensorCount)
                assertEquals(0, weights.tensor("matrix").offset)
                assertEquals(8, weights.tensor("vector").offset)
                assertEquals(64, weights.dataBase)
                assertArrayEquals(floatArrayOf(5f, 6f), weights.vector("vector"), 0f)
                assertArrayEquals(floatArrayOf(1f, 3f), weights.linear(floatArrayOf(1f, 0f), "matrix"), 1e-6f)
                assertArrayEquals(floatArrayOf(3f, 4f), weights.embedding("matrix", 1), 0f)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun decodesHalfPrecisionValues() {
        assertEquals(0f, MediaTekAotTextWeights.halfToFloat(0), 0f)
        assertEquals(1f, MediaTekAotTextWeights.halfToFloat(0x3c00), 0f)
        assertEquals(-2f, MediaTekAotTextWeights.halfToFloat(0xc000), 0f)
        assertEquals(0.5f, MediaTekAotTextWeights.halfToFloat(0x3800), 0f)
        assertTrue(MediaTekAotTextWeights.halfToFloat(0x7c00).isInfinite())
        assertTrue(MediaTekAotTextWeights.halfToFloat(0x7e00).isNaN())
    }

    @Test
    fun rejectsCorruptFixtures() {
        val file = File.createTempFile("mca-atxw-bad", ".bin")
        try {
            writeFixture(file)
            val bytes = file.readBytes()
            val badMagic = bytes.copyOf()
            badMagic[0] = 'X'.code.toByte()
            assertTrue(fails(badMagic))
            val badVersion = bytes.copyOf()
            badVersion[4] = 2
            assertTrue(fails(badVersion))
            assertTrue(fails(bytes.copyOfRange(0, 16)))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsThePublishedFileTruncatedByOneByte() {
        val published = File("E:/mtk-npu-reference-assets/text.bin")
        org.junit.Assume.assumeTrue("published text.bin is absent", published.isFile)
        val partial = File.createTempFile("mca-atxw-trunc", ".bin")
        try {
            RandomAccessFile(published, "r").use { input ->
                RandomAccessFile(partial, "rw").use { output ->
                    val buffer = ByteArray(1 shl 20)
                    var remaining = published.length() - 1
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            assertFalse(runCatching { MediaTekAotTextWeights.open(partial).close() }.isSuccess)
        } finally {
            partial.delete()
        }
    }

    private fun fails(bytes: ByteArray): Boolean {
        val file = File.createTempFile("mca-atxw-fail", ".bin")
        return try {
            file.writeBytes(bytes)
            runCatching { MediaTekAotTextWeights.open(file).close() }.isFailure
        } finally {
            file.delete()
        }
    }

    private fun writeFixture(file: File) {
        val matrix = floatArrayOf(1f, 2f, 3f, 4f)
        val vector = floatArrayOf(5f, 6f)
        val directory = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        directory.putInt(MediaTekAotTextWeights.MAGIC)
        directory.putInt(MediaTekAotTextWeights.VERSION)
        directory.putInt(2)
        directory.putShort(6)
        directory.put("matrix".toByteArray())
        directory.put(2)
        directory.putInt(2)
        directory.putInt(2)
        directory.putLong(0)
        directory.putShort(6)
        directory.put("vector".toByteArray())
        directory.put(1)
        directory.putInt(2)
        directory.putLong(8)
        val dataBase = MediaTekAotTextWeights.align64(directory.position())
        val data = ByteBuffer.allocate(dataBase + 12).order(ByteOrder.LITTLE_ENDIAN)
        directory.flip()
        data.put(directory)
        data.position(dataBase)
        matrix.forEach { data.putShort(halfBits(it)) }
        vector.forEach { data.putShort(halfBits(it)) }
        file.writeBytes(data.array())
    }

    private fun halfBits(value: Float): Short {
        if (value == 0f) return 0
        var bits = 0
        var remaining = value
        while (remaining >= 2f) {
            remaining /= 2f
            bits++
        }
        while (remaining < 1f) {
            remaining *= 2f
            bits--
        }
        val fraction = ((remaining - 1f) * 1024f).toInt()
        return (((bits + 15) shl 10) or fraction).toShort()
    }
}
