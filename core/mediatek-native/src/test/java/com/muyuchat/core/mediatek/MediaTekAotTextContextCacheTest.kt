package com.muyuchat.core.mediatek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MediaTekAotTextContextCacheTest {
    @Test
    fun returnsTheEntryForTheExactIdentity() {
        val cache = MediaTekAotTextContextCache(capacity = 2)
        val entry = entry("context-a")
        cache.put("weights|tokenizer|256|a cat", entry)

        assertSame(entry, cache.get("weights|tokenizer|256|a cat"))
        assertNull(cache.get("weights|tokenizer|256|a dog"))
        assertEquals(1, cache.size())
    }

    @Test
    fun evictsTheLeastRecentlyUsedEntry() {
        val cache = MediaTekAotTextContextCache(capacity = 2)
        val first = entry("first")
        val second = entry("second")
        val third = entry("third")
        cache.put("a", first)
        cache.put("b", second)
        assertSame(first, cache.get("a"))
        cache.put("c", third)

        assertSame(first, cache.get("a"))
        assertNull(cache.get("b"))
        assertSame(third, cache.get("c"))
        assertEquals(2, cache.size())
    }

    private fun entry(value: String): MediaTekAotTextContextCache.Entry =
        MediaTekAotTextContextCache.Entry(
            encoding = MediaTekAotTextEncoder.Encoding(
                context = FloatArray(4) { it + 1f },
                lk = 2,
                qwenTokens = intArrayOf(1),
                t5Tokens = intArrayOf(2, 3),
                elapsedMs = 7,
                contextSha256 = value,
                minimum = 0f,
                maximum = 4f,
                mean = 2.5f,
                standardDeviation = 1f,
            ),
            truncatedQwen = false,
            truncatedT5 = false,
        )
}
