package com.muyuchat.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the three text utilities shared across the app, the chat feature and the Local API.
 *
 * All three existed more than once before this and are now one copy each. The first two were
 * byte-identical duplicates; the third was not, and that difference is the reason this file also
 * pins it.
 *
 * They sit on boundaries where two layers have to agree: a trigger word is typed in the UI and
 * validated again when it reaches the contract, an asset label ordering has to be the same on both
 * sides wherever it feeds an identity, and the Han code-point set decides whether a Chinese prompt
 * passes the app's language admission and the API contract's gate.
 */
class TextUtilitiesTest {

    @Test
    fun `only ascii letters, digits and underscore are trigger word characters`() {
        ('a'..'z').forEach { assertTrue("$it", it.isAsciiTriggerWordCharacter()) }
        ('A'..'Z').forEach { assertTrue("$it", it.isAsciiTriggerWordCharacter()) }
        ('0'..'9').forEach { assertTrue("$it", it.isAsciiTriggerWordCharacter()) }
        assertTrue('_'.isAsciiTriggerWordCharacter())
    }

    @Test
    fun `everything else is rejected`() {
        listOf(' ', '-', '.', ':', '/', '\n', '\u0000', 'é', '中', '·').forEach {
            assertFalse("$it must not be a trigger word character", it.isAsciiTriggerWordCharacter())
        }
    }

    @Test
    fun `utf8 ordering is byte-wise and unsigned`() {
        assertEquals(0, compareUtf8Unsigned("", ""))
        assertTrue(compareUtf8Unsigned("a", "b") < 0)
        assertTrue(compareUtf8Unsigned("b", "a") > 0)
        assertTrue(compareUtf8Unsigned("a", "ab") < 0)
        assertTrue(compareUtf8Unsigned("ab", "a") > 0)
    }

    @Test
    fun `bytes above ascii sort after ascii, not before`() {
        // The `and 0xff` is what makes this true. Compared as signed bytes, 0xE4 (the first
        // byte of a multi-byte character) is negative and would sort before 'a', reversing the
        // meaning of a UTF-8 ordering.
        assertTrue("the unsigned form must place a CJK character after ASCII", compareUtf8Unsigned("中", "a") > 0)
        assertTrue(compareUtf8Unsigned("a", "中") < 0)
    }

    @Test
    fun `ordering is a total order consistent with sorting`() {
        val values = listOf("b", "a", "ab", "", "A", "中", "_")
        val sorted = values.sortedWith(::compareUtf8Unsigned)
        val resorted = sorted.sortedWith(::compareUtf8Unsigned)
        assertEquals("sorting must be stable under repetition", sorted, resorted)
        assertEquals("the empty string sorts first", "", sorted.first())
    }

    // ── isNativeChineseHanCodePoint ─────────────────────────────────────────────────────
    //
    // This predicate had two copies that were NOT identical: the app listed
    // U+2EBF0..U+2EE5D (CJK Unified Ideographs Extension I, Unicode 15.1) and api:local did not.
    // A prompt containing an Ext-I ideograph therefore passed the app's language admission and was
    // rejected by the API contract describing that same admission. The shared copy adopts the
    // union, so every range below — including the one that used to differ — is now pinned.

    @Test
    fun `every declared han range is accepted at both boundaries`() {
        val ranges = listOf(
            0x3400 to 0x4DBF,
            0x4E00 to 0x9FFF,
            0x20000 to 0x2A6DF,
            0x2A700 to 0x2B73F,
            0x2B740 to 0x2B81F,
            0x2B820 to 0x2CEAF,
            0x2CEB0 to 0x2EBEF,
            0x2EBF0 to 0x2EE5D,
            0x30000 to 0x3134A,
            0x31350 to 0x323AF
        )
        ranges.forEach { (first, last) ->
            assertTrue("U+%04X (first of its range)".format(first), isNativeChineseHanCodePoint(first))
            assertTrue("U+%04X (last of its range)".format(last), isNativeChineseHanCodePoint(last))
            if (last - first > 2) {
                val middle = (first + last) / 2
                assertTrue("U+%04X (middle of its range)".format(middle), isNativeChineseHanCodePoint(middle))
            }
        }
    }

    @Test
    fun `the ideographic zero is accepted`() {
        assertTrue(isNativeChineseHanCodePoint(0x3007))
    }

    @Test
    fun `extension I is accepted because the app side always accepted it`() {
        // The exact range that used to diverge. If this ever fails, the two layers disagree again.
        assertTrue("first Ext-I ideograph", isNativeChineseHanCodePoint(0x2EBF0))
        assertTrue("last Ext-I ideograph", isNativeChineseHanCodePoint(0x2EE5D))
    }

    @Test
    fun `radicals, description characters and compatibility ideographs stay rejected`() {
        // The documented rationale: these are not normal prompt text and must not become an
        // accidental Unicode bypass. Each one sits just outside every declared range.
        val rejected = listOf(
            0x2E80 to "CJK Radicals Supplement",
            0x2F00 to "Kangxi Radicals",
            0x2FF0 to "Ideographic Description Characters",
            0xF900 to "CJK Compatibility Ideographs",
            0xFA6E to "inside the compatibility block",
            0x2F800 to "CJK Compatibility Ideographs Supplement"
        )
        rejected.forEach { (codePoint, label) ->
            assertFalse("U+%04X (%s) must not count as prompt Han text".format(codePoint, label), isNativeChineseHanCodePoint(codePoint))
        }
    }

    @Test
    fun `ascii and the boundaries just outside the han ranges are rejected`() {
        listOf(0x00, ' '.code, 'A'.code, 'z'.code, '0'.code, '_'.code).forEach {
            assertFalse("U+%04X".format(it), isNativeChineseHanCodePoint(it))
        }
        // One below the first range and one above the last.
        assertFalse("U+33FF is below 0x3400", isNativeChineseHanCodePoint(0x33FF))
        assertFalse("U+323B0 is above 0x323AF", isNativeChineseHanCodePoint(0x323B0))
    }

    @Test
    fun `the suppression ranges do not leak into each other`() {
        // 0x2EBEF ends Ext F and 0x2EBF0 starts Ext I; both sides of that seam must be accepted,
        // because the seam is exactly what the two old copies disagreed about.
        assertTrue(isNativeChineseHanCodePoint(0x2EBEF))
        assertTrue(isNativeChineseHanCodePoint(0x2EBF0))
        // 0x2EE5D ends Ext I and 0x2EE5E must not be accepted by any later range.
        assertTrue(isNativeChineseHanCodePoint(0x2EE5D))
        assertFalse(isNativeChineseHanCodePoint(0x2EE5E))
    }

    // ── isSafeAsciiDiffusionPromptCodePoint / SAFE_ASCII_PROMPT_PUNCTUATION ─────────────
    //
    // These were two differently named constants holding the same 28 characters, feeding two
    // byte-identical predicate bodies. They agreed when consolidated — which is the point: the
    // sibling Han predicate in the same two files did not, so this pair is pinned before it drifts.

    @Test
    fun `whitespace, ascii letters and digits are safe prompt code points`() {
        listOf(' ', '\n', '\r').forEach { assertTrue("U+%04X".format(it.code), isSafeAsciiDiffusionPromptCodePoint(it.code)) }
        ('A'..'Z').forEach { assertTrue("$it", isSafeAsciiDiffusionPromptCodePoint(it.code)) }
        ('a'..'z').forEach { assertTrue("$it", isSafeAsciiDiffusionPromptCodePoint(it.code)) }
        ('0'..'9').forEach { assertTrue("$it", isSafeAsciiDiffusionPromptCodePoint(it.code)) }
    }

    @Test
    fun `every declared punctuation character is safe`() {
        assertEquals("the shared set is the 28 characters the two old constants held", 28, SAFE_ASCII_PROMPT_PUNCTUATION.toSet().size)
        SAFE_ASCII_PROMPT_PUNCTUATION.forEach {
            assertTrue("'$it'", isSafeAsciiDiffusionPromptCodePoint(it.code))
        }
    }

    @Test
    fun `the punctuation set carries no whitespace, letters or digits`() {
        // If it did, the predicate would accept input the ASCII branches above are meant to bound,
        // and the set would silently widen the accepted alphabet.
        SAFE_ASCII_PROMPT_PUNCTUATION.forEach {
            assertFalse("'$it' must not be whitespace", it.isWhitespace())
            assertFalse("'$it' must not be a letter or digit", it.isLetterOrDigit())
        }
    }

    @Test
    fun `non ascii and control code points are not safe prompt text`() {
        listOf(0x00, 0x01, 0x07, 0x1B, 0x7F to "DEL", 0xE9 to "é", 0x4E2D to "中", 0x1F600 to "an emoji").forEach { entry ->
            val codePoint = if (entry is Pair<*, *>) entry.first as Int else entry as Int
            assertFalse("U+%04X".format(codePoint), isSafeAsciiDiffusionPromptCodePoint(codePoint))
        }
    }

    @Test
    fun `a code point above the char range is rejected instead of being truncated`() {
        // The predicate guards with `codePoint <= Char.MAX_VALUE.code` before calling toChar().
        // Without that guard a value such as 0x1F600 or 0x10041 would be truncated to a BMP char
        // that may well be punctuation, letting an out-of-range code point through.
        assertFalse(isSafeAsciiDiffusionPromptCodePoint(Char.MAX_VALUE.code + 1))
        assertFalse(isSafeAsciiDiffusionPromptCodePoint(0x1F600))
        assertFalse(isSafeAsciiDiffusionPromptCodePoint(0x10041))
    }
}
