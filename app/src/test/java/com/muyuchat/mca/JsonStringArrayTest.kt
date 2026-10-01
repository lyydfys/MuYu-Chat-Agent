package com.muyuchat.mca

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the list-to-JSON-array conversion shared by ChatSessionStore and WebSearchProvider.
 *
 * The two previous copies were identical, so this does not cover behaviour that was untested
 * before. It exists because both callers write lists into persisted or transmitted JSON, so a
 * later change to one would otherwise be able to make the two sides of the app write arrays
 * differently.
 */
class JsonStringArrayTest {

    @Test
    fun `order is preserved`() {
        val array = listOf("a", "b", "c").toJsonArray()
        assertEquals(3, array.length())
        assertEquals("a", array.getString(0))
        assertEquals("b", array.getString(1))
        assertEquals("c", array.getString(2))
    }

    @Test
    fun `an empty list yields an empty array, not null`() {
        val array = listOf<String>().toJsonArray()
        assertEquals(0, array.length())
        assertEquals(JSONArray().toString(), array.toString())
    }

    @Test
    fun `values are written as strings`() {
        // The element type is String, so a value that looks numeric stays a JSON string. Pinned
        // so a later "helpful" coercion to numbers cannot pass unnoticed.
        val array = listOf("1", "true", "").toJsonArray()
        assertEquals(3, array.length())
        assertEquals("1", array.getString(0))
        assertEquals("true", array.getString(1))
        assertEquals("", array.getString(2))
    }
}
