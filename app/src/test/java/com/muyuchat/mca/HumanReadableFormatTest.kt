package com.muyuchat.mca

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins human-readable number formatting, and proves the P2 extraction changed nothing.
 *
 * These three functions build the text next to download and progress UI, and none of them
 * had a test. Their thresholds have non-obvious edges -- seconds disappear once a duration
 * reaches an hour, and a byte count has no unit below a megabyte -- so the cases that a
 * refactor would most plausibly break are asserted explicitly.
 *
 * Each function is also compared against a verbatim copy of its pre-extraction body.
 */
class HumanReadableFormatTest {

    // ---- formatPercent ---------------------------------------------------------------

    @Test
    fun `a percentage keeps one decimal place and a percent sign`() {
        assertEquals("0.0%", formatPercent(0.0))
        assertEquals("42.0%", formatPercent(42.0))
        assertEquals("100.0%", formatPercent(100.0))
        assertEquals("42.1%", formatPercent(42.1))
    }

    @Test
    fun `a percentage is not clamped or rounded to a whole number`() {
        assertEquals("0.0%", formatPercent(0.04))
        assertEquals("-1.5%", formatPercent(-1.5))
        assertEquals("1000.0%", formatPercent(1000.0))
    }

    // ---- formatDuration --------------------------------------------------------------

    @Test
    fun `a negative duration is clamped to zero`() {
        assertEquals("0秒", formatDuration(-1))
        assertEquals("0秒", formatDuration(Long.MIN_VALUE))
    }

    @Test
    fun `durations below a minute show seconds`() {
        assertEquals("0秒", formatDuration(0))
        assertEquals("1秒", formatDuration(1))
        assertEquals("59秒", formatDuration(59))
    }

    @Test
    fun `durations below an hour show minutes and seconds`() {
        assertEquals("1分0秒", formatDuration(60))
        assertEquals("1分1秒", formatDuration(61))
        assertEquals("59分59秒", formatDuration(3599))
    }

    @Test
    fun `seconds are dropped once a duration reaches an hour`() {
        assertEquals("1小时0分", formatDuration(3600))
        // 3661s is 1h 1m 1s; the trailing second does not appear.
        assertEquals("1小时1分", formatDuration(3661))
        assertEquals("2小时0分", formatDuration(7200))
        assertEquals("23小时59分", formatDuration(86399))
    }

    // ---- formatBytes -----------------------------------------------------------------

    @Test
    fun `byte counts below a megabyte render as zero megabytes`() {
        // There is no kilobyte or byte form; this is preserved, not endorsed.
        assertEquals("0.0 MB", formatBytes(0))
        assertEquals("0.0 MB", formatBytes(1023))
        assertEquals("0.0 MB", formatBytes(1024))
    }

    @Test
    fun `byte counts below a gibibyte render as megabytes`() {
        assertEquals("1.0 MB", formatBytes(1024L * 1024L))
        assertEquals("1.5 MB", formatBytes((1.5 * 1024 * 1024).toLong()))
        // One byte short of 1 GiB stays in megabytes rather than rounding up to 1.00 GB.
        assertEquals("1024.0 MB", formatBytes(1024L * 1024L * 1024L - 1))
    }

    @Test
    fun `a gibibyte and above render as gigabytes with two decimals`() {
        assertEquals("1.00 GB", formatBytes(1024L * 1024L * 1024L))
        assertEquals("2.00 GB", formatBytes(2L * 1024L * 1024L * 1024L))
        assertEquals("1.50 GB", formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `a negative byte count stays in the megabyte branch`() {
        assertEquals("-0.0 MB", formatBytes(-1))
    }

    @Test
    fun `formatted bytes and percents follow the default locale`() {
        // Documents inherited behaviour rather than a decision made during the extraction:
        // neither function passes a Locale, so a device using a comma decimal separator
        // renders "1,50 GB". P2 preserves it because changing it changes visible output.
        assertEquals(String.format(Locale.getDefault(), "%.2f GB", 1.5), formatBytes(1610612736L))
        assertEquals(String.format(Locale.getDefault(), "%.1f%%", 42.0), formatPercent(42.0))
    }

    // ---- extraction equivalence ------------------------------------------------------

    @Test
    fun `the extraction matches the original percent formatting`() {
        listOf(0.0, 0.04, 0.05, 1.0, 42.0, 42.1, 99.99, 100.0, 1000.0, -1.5, Double.MAX_VALUE)
            .forEach { value ->
                assertEquals(
                    "percent diverged for $value",
                    originalFormatPercent(value),
                    formatPercent(value)
                )
            }
    }

    @Test
    fun `the extraction matches the original duration formatting`() {
        var compared = 0
        (-5L..(3L * 3600L + 120L)).forEach { seconds ->
            assertEquals(
                "duration diverged for $seconds",
                originalFormatDuration(seconds),
                formatDuration(seconds)
            )
            compared++
        }
        listOf(Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE / 60).forEach { seconds ->
            assertEquals(
                "duration diverged for $seconds",
                originalFormatDuration(seconds),
                formatDuration(seconds)
            )
            compared++
        }
        assertEquals("the sweep must cover the whole range", true, compared > 10_000)
    }

    @Test
    fun `the extraction matches the original byte formatting`() {
        val gib = 1024L * 1024L * 1024L
        val cases = mutableListOf<Long>()
        cases += listOf(-1L, 0L, 1L, 1023L, 1024L, 1024L * 1024L - 1, 1024L * 1024L)
        cases += (1L..40L).map { it * 1024L * 1024L }
        cases += listOf(gib - 1, gib, gib + 1, 2 * gib, 3 * gib, 512L * gib, Long.MAX_VALUE)
        cases.forEach { bytes ->
            assertEquals(
                "bytes diverged for $bytes",
                originalFormatBytes(bytes),
                formatBytes(bytes)
            )
        }
    }

    // ---- verbatim copies of the pre-extraction implementations -----------------------

    private fun originalFormatPercent(value: Double): String = "%.1f%%".format(value)

    private fun originalFormatDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val minutes = safe / 60
        val remainSeconds = safe % 60
        val hours = minutes / 60
        val remainMinutes = minutes % 60
        return when {
            hours > 0 -> "${hours}小时${remainMinutes}分"
            minutes > 0 -> "${minutes}分${remainSeconds}秒"
            else -> "${remainSeconds}秒"
        }
    }

    private fun originalFormatBytes(bytes: Long): String {
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        val mb = bytes / 1024.0 / 1024.0
        return if (gb >= 1.0) "%.2f GB".format(gb) else "%.1f MB".format(mb)
    }
}
