package com.muyuchat.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two text utilities shared across the app, the chat feature and the Local API.
 *
 * Both existed twice before this and are now one copy each, so this does not cover behaviour
 * that was untested. It matters because both sit on boundaries where two layers have to agree:
 * a trigger word is typed in the UI and validated again when it reaches the contract, and an
 * asset label ordering has to be the same on both sides wherever it feeds an identity.
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
}
