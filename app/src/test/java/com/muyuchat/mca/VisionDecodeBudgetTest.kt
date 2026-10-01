package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the vision decode budget, and proves the P2 extraction changed nothing.
 *
 * `sampleSize` decides how far a user's image is downsampled before it reaches
 * `BitmapFactory`. It has two jobs that pull in opposite directions: it must keep a
 * malformed or very large image from exhausting the process, and it must not throw away
 * more detail than the budget requires. It had no test before this extraction.
 *
 * The equivalence sweep compares the extracted implementation against a test-local copy of
 * the original `MainViewModel` algorithm over a wide input grid. That is the migration
 * proof: the same function is evaluated both ways and every result must agree.
 */
class VisionDecodeBudgetTest {

    @Test
    fun `non positive dimensions are not sampled`() {
        assertEquals(1, VisionDecodeBudget.sampleSize(0, 100, 1280))
        assertEquals(1, VisionDecodeBudget.sampleSize(100, 0, 1280))
        assertEquals(1, VisionDecodeBudget.sampleSize(-1, -1, 1280))
        assertEquals(1, VisionDecodeBudget.sampleSize(0, 0, 1280))
    }

    @Test
    fun `an image inside both limits is decoded at full size`() {
        assertEquals(1, VisionDecodeBudget.sampleSize(1, 1, 1280))
        assertEquals(1, VisionDecodeBudget.sampleSize(1280, 720, 1280))
        assertEquals(1, VisionDecodeBudget.sampleSize(2560, 1, 1280))
    }

    @Test
    fun `the edge limit is exclusive so one pixel past it starts sampling`() {
        // The comparison is `>` rather than `>=`: exactly maxEdge * 2 still decodes whole.
        assertEquals(1, VisionDecodeBudget.sampleSize(2560, 1, 1280))
        assertEquals(2, VisionDecodeBudget.sampleSize(2561, 1, 1280))
    }

    @Test
    fun `the pixel budget samples even when the edge limit is satisfied`() {
        // 2560x2560 is within the edge limit but is 6.5 MP, so the budget forces halving.
        assertEquals(2, VisionDecodeBudget.sampleSize(2560, 2560, 1280))
    }

    @Test
    fun `a typical phone photo halves once`() {
        assertEquals(2, VisionDecodeBudget.sampleSize(4032, 3024, 1280))
    }

    @Test
    fun `a very large image is reduced to the budget`() {
        val sample = VisionDecodeBudget.sampleSize(20000, 20000, 1280)
        assertEquals(16, sample)
        assertWithinBudget(20000, 20000, 1280, sample)
    }

    @Test
    fun `extreme dimensions stop at the doubling guard instead of overflowing`() {
        val sample = VisionDecodeBudget.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE, 1)
        assertEquals(1 shl 29, sample)
        assertTrue("the guard must keep the sample positive", sample > 0)
    }

    @Test
    fun `the result is always a positive power of two`() {
        val widths = listOf(0, 1, 2, 1023, 1280, 2560, 2561, 4096, 12000, 65535, Int.MAX_VALUE)
        val heights = widths
        widths.forEach { width ->
            heights.forEach { height ->
                val sample = VisionDecodeBudget.sampleSize(width, height, 1280)
                assertTrue("sample must be positive for ${width}x$height", sample >= 1)
                assertTrue(
                    "sample must stay a power of two for ${width}x$height but was $sample",
                    Integer.highestOneBit(sample) == sample
                )
            }
        }
    }

    @Test
    fun `the extraction matches the original algorithm on every sampled input`() {
        val maxEdges = listOf(1, 2, 320, 640, 1024, 1280, 2048, 4096)
        val dimensions = listOf(
            -5, -1, 0, 1, 2, 3, 255, 256, 511, 512, 640, 1023, 1024, 1279, 1280, 1281,
            1920, 2000, 2048, 2559, 2560, 2561, 3024, 4032, 4096, 8192, 12000, 16384,
            65535, 1 shl 20, Int.MAX_VALUE
        )
        var compared = 0

        maxEdges.forEach { maxEdge ->
            dimensions.forEach { width ->
                dimensions.forEach { height ->
                    assertEquals(
                        "sample size diverged for ${width}x$height at maxEdge=$maxEdge",
                        originalSampleSize(width, height, maxEdge),
                        VisionDecodeBudget.sampleSize(width, height, maxEdge)
                    )
                    compared++
                }
            }
        }

        assertEquals("the sweep must actually exercise the grid", 8 * 31 * 31, compared)
    }

    @Test
    fun `the budget constants keep their original values`() {
        assertEquals(1280, VisionDecodeBudget.MAX_EDGE)
        assertEquals(4_000_000L, VisionDecodeBudget.MAX_DECODE_PIXELS)
    }

    /** Verbatim copy of the pre-extraction `MainViewModel.calculateImageSampleSize`. */
    private fun originalSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (
            maxOf(width / sample, height / sample) > maxEdge * 2 ||
            (width.toLong() / sample.toLong()) * (height.toLong() / sample.toLong()) > 4_000_000L
        ) {
            if (sample >= (1 shl 29)) break
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun assertWithinBudget(width: Int, height: Int, maxEdge: Int, sample: Int) {
        val sampledWidth = width / sample
        val sampledHeight = height / sample
        assertTrue(
            "sampled edge $sampledWidth x $sampledHeight must fit the $maxEdge budget",
            maxOf(sampledWidth, sampledHeight) <= maxEdge * 2
        )
        assertTrue(
            "sampled pixels must fit ${VisionDecodeBudget.MAX_DECODE_PIXELS}",
            sampledWidth.toLong() * sampledHeight.toLong() <= VisionDecodeBudget.MAX_DECODE_PIXELS
        )
    }
}
