package com.muyuchat.feature.chat

import org.junit.Assert.*
import org.junit.Test

class StreamingTailTrackerTest {
    @Test fun `one character fades and settles without a later token`() {
        val glyph = StreamingTailTracker().update("字", true, 10L).single()
        assertEquals(0.38f, glyph.alpha(10L), 0.001f)
        assertEquals(0.69f, glyph.alpha(80_000_010L), 0.001f)
        assertEquals(1f, glyph.alpha(160_000_010L), 0f)
    }

    @Test fun `append never restarts an existing glyph`() {
        val tracker = StreamingTailTracker()
        tracker.update("a", true, 0L)
        val next = tracker.update("ab", true, 80_000_000L)
        assertEquals(0L, next[0].bornNanos)
        assertEquals(80_000_000L, next[1].bornNanos)
        assertTrue(next[0].alpha(80_000_000L) > next[1].alpha(80_000_000L))
    }

    @Test fun `finish cancels all pending masks and historical text stays static`() {
        val tracker = StreamingTailTracker()
        tracker.update("a", true, 0L)
        assertTrue(tracker.update("ab", false, 20L).isEmpty())
        assertTrue(tracker.update("ab", false, 30L).isEmpty())
    }

    @Test fun `replacement cannot reuse the previous text clock`() {
        val tracker = StreamingTailTracker()
        tracker.update("abc", true, 0L)
        assertTrue(tracker.update("xyz", true, 100L).all { it.bornNanos == 100L })
    }

    @Test fun `mask state is bounded for arbitrarily long paragraphs`() {
        val tail = StreamingTailTracker().update("a".repeat(100_000), true, 0L)
        assertEquals(8, tail.size)
        assertEquals(99_992, tail.first().start)
    }

    @Test fun `surrogate combining emoji and flag clusters stay intact`() {
        for (cluster in listOf("😀", "e\u0301", "👩🏽‍💻", "🇨🇳", "1️⃣")) {
            val text = "x$cluster"
            val ranges = tailGlyphRanges(text)
            assertEquals(listOf("x", cluster), ranges.map { text.substring(it) })
        }
    }

    @Test fun `removed animations and slower system scale are respected`() {
        val glyph = StreamingTailGlyph(0, 1, 0L)
        assertEquals(1f, glyph.alpha(0L, 0f), 0f)
        assertEquals(0.69f, glyph.alpha(160_000_000L, 2f), 0.001f)
        assertEquals(1f, glyph.alpha(320_000_000L, 2f), 0f)
    }

    @Test fun `odd regional indicator runs are paired from their beginning`() {
        for (text in listOf("🇺🇸🇨", "🇺🇸🇨🇳🇯")) {
            val chunks = tailGlyphRanges(text).map { text.substring(it) }
            assertEquals("🇺🇸", chunks.first())
            assertEquals(2, chunks.last().length)
        }
        val tracker = StreamingTailTracker()
        tracker.update("🇺", true, 0L)
        assertEquals(0L, tracker.update("🇺🇸", true, 10L).single().bornNanos)
        val three = tracker.update("🇺🇸🇨", true, 20L)
        assertEquals(0L, three.first().bornNanos)
        assertEquals(20L, three.last().bornNanos)
    }

    @Test fun `frame clock uses deltas independent of epoch and does not age during idle`() {
        val clock = StreamingTailClock()
        clock.advance(7_000_000_000L, 1f)
        assertEquals(0L, clock.nowNanos)
        clock.advance(7_080_000_000L, 2f)
        assertEquals(40_000_000L, clock.nowNanos)
        clock.pause()
        clock.advance(99_000_000_000L, 1f)
        assertEquals(40_000_000L, clock.nowNanos)
        clock.finishAt(160_000_000L)
        assertEquals(160_000_000L, clock.nowNanos)
    }

    @Test fun `regional indicator extenders are never counted as another flag letter`() {
        for (cluster in listOf("🇺\u0301", "🇺\uFE0F", "🇺🇸\uFE0F")) {
            assertEquals(listOf(cluster), tailGlyphRanges(cluster).map { cluster.substring(it) })
        }
    }
}
