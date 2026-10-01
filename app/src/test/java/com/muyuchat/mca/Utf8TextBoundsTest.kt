package com.muyuchat.mca

import com.muyuchat.core.engine.localPromptTextFootprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins UTF-8 byte-boundary arithmetic for prompt text, and proves the P2 extraction changed
 * nothing.
 *
 * Both functions sit between a byte count and a Kotlin string index. Getting either wrong
 * silently changes what the user typed -- truncated composer input, or a highlight on the
 * wrong part of a prompt -- which the plan forbids, so the multi-byte cases are covered
 * explicitly and the extraction is checked against the pre-extraction code.
 */
class Utf8TextBoundsTest {

    // ---- utf8ByteOffsetToUtf16 -------------------------------------------------------

    @Test
    fun `ascii offsets map one to one`() {
        val prompt = "hello"
        assertEquals(0, utf8ByteOffsetToUtf16(prompt, 0))
        assertEquals(3, utf8ByteOffsetToUtf16(prompt, 3))
        assertEquals(5, utf8ByteOffsetToUtf16(prompt, 5))
    }

    @Test
    fun `an offset outside the prompt is unknown`() {
        val prompt = "hello"
        assertNull(utf8ByteOffsetToUtf16(prompt, -1))
        assertNull(utf8ByteOffsetToUtf16(prompt, 6))
        assertNull(utf8ByteOffsetToUtf16(prompt, Int.MAX_VALUE))
        assertNull(utf8ByteOffsetToUtf16(prompt, Int.MIN_VALUE))
    }

    @Test
    fun `the end offset maps to the full string length`() {
        listOf("hello", "中", "a中b😀c", "").forEach { prompt ->
            assertEquals(
                "end offset must land at the end of \"$prompt\"",
                prompt.length,
                utf8ByteOffsetToUtf16(prompt, prompt.toByteArray(Charsets.UTF_8).size)
            )
        }
    }

    @Test
    fun `an offset inside a two byte character is unknown`() {
        val prompt = "é" // C3 A9
        assertNull(utf8ByteOffsetToUtf16(prompt, 1))
        assertEquals(1, utf8ByteOffsetToUtf16(prompt, 2))
    }

    @Test
    fun `an offset inside a three byte character is unknown`() {
        val prompt = "中" // E4 B8 AD
        assertNull(utf8ByteOffsetToUtf16(prompt, 1))
        assertNull(utf8ByteOffsetToUtf16(prompt, 2))
        assertEquals(1, utf8ByteOffsetToUtf16(prompt, 3))
    }

    @Test
    fun `an offset inside a four byte character is unknown`() {
        val prompt = "😀" // F0 9F 98 80, two UTF-16 units
        assertNull(utf8ByteOffsetToUtf16(prompt, 1))
        assertNull(utf8ByteOffsetToUtf16(prompt, 2))
        assertNull(utf8ByteOffsetToUtf16(prompt, 3))
        assertEquals(2, utf8ByteOffsetToUtf16(prompt, 4))
    }

    @Test
    fun `a mixed prompt maps only on character boundaries`() {
        val prompt = "a中b😀c" // 10 bytes, 6 UTF-16 units
        assertEquals(1, utf8ByteOffsetToUtf16(prompt, 1))
        assertEquals(2, utf8ByteOffsetToUtf16(prompt, 4))
        assertEquals(3, utf8ByteOffsetToUtf16(prompt, 5))
        assertEquals(5, utf8ByteOffsetToUtf16(prompt, 9))
        assertEquals(6, utf8ByteOffsetToUtf16(prompt, 10))

        listOf(2, 3, 6, 7, 8).forEach { offset ->
            assertNull("offset $offset splits a character", utf8ByteOffsetToUtf16(prompt, offset))
        }
    }

    // ---- takeUtf8Prefix -------------------------------------------------------------

    @Test
    fun `text within the budget is returned unchanged`() {
        val bounded = "hello".takeUtf8Prefix(10)
        assertEquals("hello", bounded.text)
        assertFalse(bounded.truncated)
        assertFalse("中".takeUtf8Prefix(3).truncated)
    }

    @Test
    fun `ascii text is cut at the budget`() {
        val bounded = "hello".takeUtf8Prefix(3)
        assertEquals("hel", bounded.text)
        assertTrue(bounded.truncated)
    }

    @Test
    fun `a multi byte character is never split`() {
        val bounded = "中中中".takeUtf8Prefix(4)
        assertEquals("中", bounded.text)
        assertTrue(bounded.truncated)
    }

    @Test
    fun `a four byte character that does not fit is dropped whole`() {
        val tooSmall = "😀".takeUtf8Prefix(3)
        assertEquals("a partial character must never be emitted", "", tooSmall.text)
        assertTrue(tooSmall.truncated)

        // Exactly fitting is not truncation: nothing was dropped.
        val exact = "😀".takeUtf8Prefix(4)
        assertEquals("😀", exact.text)
        assertFalse(exact.truncated)
    }

    @Test
    fun `an empty string is never reported as truncated`() {
        val bounded = "".takeUtf8Prefix(1)
        assertEquals("", bounded.text)
        assertFalse(bounded.truncated)
    }

    @Test
    fun `a non positive budget is rejected`() {
        listOf(0, -1).forEach { budget ->
            val failure = runCatching { "abc".takeUtf8Prefix(budget) }.exceptionOrNull()
            assertTrue(
                "budget $budget must fail loudly",
                failure is IllegalArgumentException
            )
        }
    }

    @Test
    fun `the result never exceeds the byte budget`() {
        val prompts = listOf("", "a", "hello", "中", "中中中", "😀", "a中b😀c", "é" .repeat(50))
        val budgets = listOf(1, 2, 3, 4, 5, 6, 7, 8, 15, 16, 100, 1024)
        prompts.forEach { prompt ->
            budgets.forEach { budget ->
                val bounded = prompt.takeUtf8Prefix(budget)
                val bytes = bounded.text.toByteArray(Charsets.UTF_8).size
                assertTrue(
                    "\"$prompt\" at budget $budget produced $bytes bytes",
                    bytes <= budget
                )
                assertTrue(
                    "\"$prompt\" at budget $budget must stay a prefix",
                    prompt.startsWith(bounded.text)
                )
            }
        }
    }

    // ---- extraction equivalence ------------------------------------------------------

    @Test
    fun `the extraction matches the original offset mapping on every input`() {
        val prompts = listOf(
            "", "a", "hello", "é", "中", "😀", "a中b😀c", "中".repeat(20), "😀".repeat(10),
            "\uD83D\uDE00 mixed 中文 and ascii", "line\nbreak\ttab"
        )
        var compared = 0

        prompts.forEach { prompt ->
            val byteLength = prompt.toByteArray(Charsets.UTF_8).size
            (-2..byteLength + 2).forEach { offset ->
                assertEquals(
                    "offset mapping diverged for \"$prompt\" at $offset",
                    originalUtf8ByteOffsetToUtf16(prompt, offset),
                    utf8ByteOffsetToUtf16(prompt, offset)
                )
                compared++
            }
        }

        assertTrue("the sweep must exercise the prompts", compared > 100)
    }

    @Test
    fun `the extraction matches the original prefix truncation on every budget`() {
        val prompts = listOf(
            "", "a", "hello", "é", "中", "😀", "a中b😀c", "中文测试内容", "😀😀😀",
            "mixed 中文 and 😀 text"
        )
        val budgets = (1..40).toList()
        var compared = 0

        prompts.forEach { prompt ->
            budgets.forEach { budget ->
                val expected = originalTakeUtf8Prefix(prompt, budget)
                val actual = prompt.takeUtf8Prefix(budget)
                assertEquals(
                    "prefix text diverged for \"$prompt\" at $budget",
                    expected.text,
                    actual.text
                )
                assertEquals(
                    "truncation flag diverged for \"$prompt\" at $budget",
                    expected.truncated,
                    actual.truncated
                )
                compared++
            }
        }

        assertEquals(10 * 40, compared)
    }

    // ---- verbatim copies of the pre-extraction implementations -----------------------

    private fun originalUtf8ByteOffsetToUtf16(prompt: String, byteOffset: Int): Int? {
        if (byteOffset !in 0..prompt.toByteArray(Charsets.UTF_8).size) return null
        val bytes = prompt.toByteArray(Charsets.UTF_8)
        val prefixBytes = bytes.copyOf(byteOffset)
        val prefix = runCatching { String(prefixBytes, Charsets.UTF_8) }.getOrNull() ?: return null
        return prefix.toByteArray(Charsets.UTF_8)
            .takeIf { it.contentEquals(prefixBytes) }
            ?.let { prefix.length }
    }

    private data class OriginalBoundedText(val text: String, val truncated: Boolean)

    private fun originalTakeUtf8Prefix(value: String, maxBytes: Int): OriginalBoundedText {
        require(maxBytes > 0)
        if (localPromptTextFootprint(value).utf8Bytes <= maxBytes) {
            return OriginalBoundedText(value, truncated = false)
        }
        val output = StringBuilder(minOf(value.length, maxBytes))
        var used = 0
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            val size = when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
            if (used > maxBytes - size) break
            output.appendCodePoint(codePoint)
            used += size
            index += Character.charCount(codePoint)
        }
        return OriginalBoundedText(output.toString(), truncated = true)
    }
}
