package com.muyuchat.core.mediatek

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class MediaTekAotTokenizerTest {
    private val container = File("E:/mtk-npu-reference-assets/tok.bin")

    @Before
    fun requireReferenceAsset() {
        assumeTrue("reference tok.bin is not present at ${container.absolutePath}", container.isFile)
    }

    private val tokenizer: MediaTekAotTokenizer by lazy { MediaTekAotTokenizer.parse(container) }

    @Test
    fun parsesTheReferenceContainer() {
        assertEquals(151387, tokenizer.mergeCount)
        assertEquals(32100, tokenizer.pieceCount)
        assertEquals(2, tokenizer.unkId)
        assertEquals(188, tokenizer.byteToken(0))
        assertEquals(32, tokenizer.byteToken('A'.code))
        assertEquals(220, tokenizer.byteToken(' '.code))
        assertEquals(MediaTekAotTokenizer.QWEN_END_OF_TEXT, 151643)
    }

    @Test
    fun qwenSplitterMatchesReferenceQuirks() {
        assertEquals(listOf("Hi", " ", " there", "!\n"), tokenizer.qwenPieces("Hi  there!\n"))
        assertEquals(listOf("a", " cat"), tokenizer.qwenPieces("a cat"))
        assertEquals(listOf("don", "'t", " stop"), tokenizer.qwenPieces("don't stop"))
        assertEquals(listOf("TRAILING", "   "), tokenizer.qwenPieces("TRAILING   "))
        assertEquals(listOf(" ", " leading", " spaces"), tokenizer.qwenPieces("  leading spaces"))
    }

    @Test
    fun qwenIdsMatchTheLockedVocab() {
        assertArrayEquals(intArrayOf(64, 8251), tokenizer.encodeQwen("a cat"))
        assertArrayEquals(intArrayOf(9707, 11, 1879, 0), tokenizer.encodeQwen("Hello, world!"))
        assertArrayEquals(intArrayOf(13048, 220, 1052, 4894), tokenizer.encodeQwen("Hi  there!\n"))
        assertArrayEquals(intArrayOf(15007, 944, 2936), tokenizer.encodeQwen("don't stop"))
        assertArrayEquals(
            intArrayOf(13629, 22362, 11, 1850, 4271, 11, 220, 16, 28552),
            tokenizer.encodeQwen("masterpiece, best quality, 1girl"),
        )
        assertArrayEquals(
            intArrayOf(46944, 101339, 18493, 100029, 15946),
            tokenizer.encodeQwen("一个女孩在雨中"),
        )
        assertArrayEquals(intArrayOf(141334, 42365, 1273), tokenizer.encodeQwen("😀 emoji test"))
    }

    @Test
    fun t5PiecesAndUnigramMatchTheReferenceVocabulary() {
        assertEquals(listOf("▁a", "▁cat"), tokenizer.t5Pieces("a cat"))
        assertEquals(listOf("▁"), tokenizer.t5Pieces("  "))
        assertArrayEquals(intArrayOf(21820), tokenizer.encodeT5("hello"))
        assertArrayEquals(intArrayOf(21820, 296), tokenizer.encodeT5("hello world"))
        assertArrayEquals(intArrayOf(3, 2), tokenizer.encodeT5("你好"))
        assertArrayEquals(intArrayOf(3), tokenizer.encodeT5("  "))
    }

    @Test
    fun promptEncodingAppliesReferenceLengthPolicy() {
        val full = tokenizer.encodePrompt("a cat", 256)
        assertArrayEquals(intArrayOf(64, 8251), full.qwen)
        assertArrayEquals(intArrayOf(3, 9, 1712, 1), full.t5)

        val short = tokenizer.encodePrompt("a cat", 3)
        assertArrayEquals(intArrayOf(64, 8251), short.qwen)
        assertArrayEquals(intArrayOf(3, 9, 1), short.t5)

        val empty = tokenizer.encodePrompt("", 256)
        assertArrayEquals(intArrayOf(151643), empty.qwen)
        assertArrayEquals(intArrayOf(3, 1), empty.t5)
    }

    @Test
    fun rejectsCorruptContainers() {
        val original = container.readBytes()
        assertTrue(failsWith(original.copyOfRange(0, 64)))
        val badMagic = original.copyOf()
        badMagic[0] = 'X'.code.toByte()
        assertTrue(failsWith(badMagic))
        val badVersion = original.copyOf()
        badVersion[4] = 3
        assertTrue(failsWith(badVersion))
    }

    private fun failsWith(bytes: ByteArray): Boolean = runCatching {
        MediaTekAotTokenizer.parse(bytes)
    }.isFailure
}
