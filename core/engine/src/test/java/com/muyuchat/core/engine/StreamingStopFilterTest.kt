package com.muyuchat.core.engine

import org.junit.Assert.*
import org.junit.Test

class StreamingStopFilterTest {
    @Test fun detectsEveryChunkBoundaryWithoutLeakingMarker() {
        val marker = "[END]"
        for (split in 0..marker.length) {
            val filter = StreamingStopFilter(listOf(marker))
            val output = filter.accept("line\n  " + marker.take(split)) +
                filter.accept(marker.drop(split) + "discard") + filter.finish()
            assertEquals("line\n  ", output)
            assertTrue(filter.stopped)
        }
    }

    @Test fun incompleteMarkerIsPreservedAtNormalEnd() {
        val filter = StreamingStopFilter(listOf("[END]"))
        assertEquals("before ", filter.accept("before [EN"))
        assertEquals("[EN", filter.finish())
        assertFalse(filter.stopped)
    }

    @Test fun shortestObservedOffsetWinsAndWhitespaceIsPreserved() {
        val filter = StreamingStopFilter(listOf("long-stop", "STOP"))
        assertEquals("\t \n", filter.accept("\t \nSTOP long-stop"))
        assertEquals("", filter.accept("late"))
    }
}
