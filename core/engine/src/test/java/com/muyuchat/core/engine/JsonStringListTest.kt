package com.muyuchat.core.engine

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the JSON array string extraction shared by the chat and OpenAI-compatible wire paths.
 *
 * The two previous copies were byte-identical, so this does not cover behaviour that was
 * untested before. It matters because both callers sit on a wire path: a divergence would mean
 * the same JSON array is read one way by one path and another way by the other, and the
 * difference would show up as a field silently losing entries rather than as an error.
 */
class JsonStringListTest {

    @Test
    fun `entries keep their order`() {
        assertEquals(
            listOf("a", "b", "c"),
            JSONArray("""["a","b","c"]""").toStringList()
        )
    }

    @Test
    fun `blank entries are dropped`() {
        assertEquals(
            listOf("a", "b"),
            JSONArray("""["a","","   ","b",null]""").toStringList()
        )
    }

    @Test
    fun `an empty or all-blank array yields nothing`() {
        assertEquals(emptyList<String>(), JSONArray("[]").toStringList())
        assertEquals(emptyList<String>(), JSONArray("""["","  "]""").toStringList())
    }

    @Test
    fun `entries are not whitespace-normalised`() {
        // This is what separates this function from the copy in app/WebSearchProvider.kt, which
        // calls normalizeWhitespace() first and would return "a b" here. Pinned so that a later
        // "why are there two of these" change cannot quietly start collapsing whitespace on the
        // two wire paths.
        assertEquals(
            listOf("a  b", " c "),
            JSONArray("""["a  b"," c "]""").toStringList()
        )
    }

    @Test
    fun `non-string entries are coerced rather than skipped`() {
        // optString turns them into their textual form, so a numeric entry survives instead of
        // being silently dropped from the list.
        assertEquals(
            listOf("1", "true"),
            JSONArray("""[1,true]""").toStringList()
        )
    }
}
