package com.muyuchat.feature.modelhub

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFormattingTest {
    @Test
    fun downloadRatesKeepSmallTransfersVisibleAndSwitchUnitsAtTheirBoundaries() = withRootLocale {
        assertEquals("0 B/s", format("formatDownloadRate", -1L))
        assertEquals("0 B/s", format("formatDownloadRate", 0L))
        assertEquals("1 B/s", format("formatDownloadRate", 1L))
        assertEquals("1023 B/s", format("formatDownloadRate", 1023L))
        assertEquals("1.0 KB/s", format("formatDownloadRate", 1024L))
        assertEquals("1.5 KB/s", format("formatDownloadRate", 1536L))
        assertEquals("1.00 MB/s", format("formatDownloadRate", 1024L * 1024L))
        assertEquals("1.50 MB/s", format("formatDownloadRate", 1536L * 1024L))
        assertEquals("1.00 GB/s", format("formatDownloadRate", 1024L * 1024L * 1024L))
        assertEquals("1.50 GB/s", format("formatDownloadRate", 1536L * 1024L * 1024L))
    }

    @Test
    fun largestRateDoesNotWrapToNegativeOrRenderScientificNotation() = withRootLocale {
        val rate = format("formatDownloadRate", Long.MAX_VALUE)
        assertTrue(rate.endsWith(" GB/s"))
        assertFalse(rate.startsWith("-"))
        assertFalse(rate.contains("Infinity"))
        assertFalse(rate.contains("NaN"))
        assertFalse(rate.contains("E"))
    }

    @Test
    fun etaDurationKeepsLongPrecisionAndFormatsMinuteAndHourTransitions() {
        assertEquals("0秒", format("formatDuration", -1L))
        assertEquals("59秒", format("formatDuration", 59L))
        assertEquals("1分0秒", format("formatDuration", 60L))
        assertEquals("1分1秒", format("formatDuration", 61L))
        assertEquals("59分59秒", format("formatDuration", 3599L))
        assertEquals("1小时0分", format("formatDuration", 3600L))
        assertEquals("1小时1分", format("formatDuration", 3661L))
        assertEquals("2562047788015215小时30分", format("formatDuration", Long.MAX_VALUE))
    }

    // Exercise the production private formatters without changing their visibility
    // or creating a second implementation in the test.
    private fun format(name: String, value: Long): String =
        Class.forName("com.muyuchat.feature.modelhub.ModelHubScreenKt")
            .getDeclaredMethod(name, java.lang.Long.TYPE)
            .apply { isAccessible = true }
            .invoke(null, value) as String

    private fun withRootLocale(block: () -> Unit) = synchronized(Locale::class.java) {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ROOT)
            block()
        } finally {
            Locale.setDefault(original)
        }
    }
}
