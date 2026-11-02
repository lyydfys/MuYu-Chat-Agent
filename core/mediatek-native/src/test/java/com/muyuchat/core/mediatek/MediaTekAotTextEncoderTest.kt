package com.muyuchat.core.mediatek

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class MediaTekAotTextEncoderTest {
    private val textBin = File("E:/mtk-npu-reference-assets/text.bin")
    private val tokenizerFile = File("E:/mtk-npu-reference-assets/tok.bin")

    @Before
    fun requireReferenceAssets() {
        assumeTrue("reference text.bin is not present", textBin.isFile)
        assumeTrue("reference text.bin is incomplete", textBin.length() == 1_461_438_080L)
        assumeTrue("reference tok.bin is not present", tokenizerFile.isFile)
    }

    @Test(timeout = 900_000)
    fun encodesATinyPromptIntoTheLockedActxShape() {        val tokenizer = MediaTekAotTokenizer.parse(tokenizerFile)
        MediaTekAotTextWeights.open(textBin).use { weights ->
            val encoder = MediaTekAotTextEncoder(weights, tokenizer)
            encoder.requireSupportedModel()

            val first = encoder.encode("a", 256)
            assertEquals(256 * 1024, first.context.size)
            assertArrayEquals(intArrayOf(64), first.qwenTokens)
            assertArrayEquals(intArrayOf(3, 9, 1), first.t5Tokens)
            assertTrue(first.elapsedMs > 0)
            assertEquals(64, first.contextSha256.length)
            assertTrue(first.context.all { it.isFinite() })
            assertTrue(first.context.any { it != 0f })

            fun rowNonZero(row: Int): Boolean {
                val start = row * 1024
                return (start until start + 1024).any { first.context[it] != 0f }
            }
            assertTrue(rowNonZero(0))
            assertTrue(rowNonZero(1))
            assertTrue(rowNonZero(2))
            assertTrue((3 until 256).none { rowNonZero(it) })

            val second = encoder.encode("a", 256)
            assertEquals(first.contextSha256, second.contextSha256)

            val other = encoder.encode("cat", 256)
            assertNotEquals(first.contextSha256, other.contextSha256)
        }
    }

    @Test(timeout = 900_000)
    fun writesQwenHiddenForIndependentComparison() {
        val tokenizer = MediaTekAotTokenizer.parse(tokenizerFile)
        MediaTekAotTextWeights.open(textBin).use { weights ->
            val tokens = tokenizer.encodeQwen("a cat")
            val hidden = MediaTekAotTextEncoder(weights, tokenizer).encodeQwenHidden(tokens)
            val buffer = ByteBuffer.allocate(8 + tokens.size * 4 + hidden.size * 1024 * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(tokens.size)
            buffer.putInt(1024)
            tokens.forEach { buffer.putInt(it) }
            hidden.forEach { row -> row.forEach { buffer.putFloat(it) } }
            val output = File("../../build/ref-text/qwen-hidden-mca.f32")
            output.parentFile?.mkdirs()
            output.writeBytes(buffer.array())
        }
    }
}
