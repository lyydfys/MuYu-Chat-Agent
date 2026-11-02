package com.muyuchat.core.mediatek

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekNpugVaeCodecTest {
    @Test
    fun quantizeUsesHalfAwayFromZeroAndSaturates() {
        val values = floatArrayOf(-1.0f, -0.25f, 0.0f, 0.25f, 1.0f)
        val bytes = MediaTekNpugVaeCodec.quantizeInt16LittleEndian(values, scale = 0.5, zeroPoint = 0)
        val decoded = MediaTekNpugVaeCodec.dequantizeInt16LittleEndian(bytes, scale = 0.5, zeroPoint = 0)

        assertArrayEquals(floatArrayOf(-1.0f, -0.5f, 0.0f, 0.5f, 1.0f), decoded, 1e-6f)
        val saturated = MediaTekNpugVaeCodec.dequantizeInt16LittleEndian(
            MediaTekNpugVaeCodec.quantizeInt16LittleEndian(floatArrayOf(1e9f, -1e9f), 0.5, 0),
            0.5,
            0,
        )
        assertEquals(32767 * 0.5, saturated[0].toDouble(), 1e-6)
        assertEquals(-32768 * 0.5, saturated[1].toDouble(), 1e-6)
    }

    @Test
    fun zeroPointIsAppliedOnBothDirections() {
        val bytes = MediaTekNpugVaeCodec.quantizeInt16LittleEndian(floatArrayOf(0.0f), scale = 0.25, zeroPoint = 100)
        val decoded = MediaTekNpugVaeCodec.dequantizeInt16LittleEndian(bytes, scale = 0.25, zeroPoint = 100)
        assertEquals(0.0, decoded[0].toDouble(), 1e-6)
    }

    @Test
    fun ncdhwAndNhwcTransposeRoundTrip() {
        // NCDHW: channel 0 = 1,2,3,4; channel 1 = 5,6,7,8 for a 2x2 image.
        val ncdhw = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f)
        val nhwc = MediaTekNpugVaeCodec.ncdhwDepthOneToNhwc(ncdhw, channels = 2, height = 2, width = 2)
        assertArrayEquals(floatArrayOf(1f, 5f, 2f, 6f, 3f, 7f, 4f, 8f), nhwc, 0f)
        assertArrayEquals(ncdhw, MediaTekNpugVaeCodec.nhwcToNcdhwDepthOne(nhwc, 2, 2, 2), 0f)
    }

    @Test
    fun nonFiniteValuesAndBadShapesAreRejected() {
        assertTrue(runCatching {
            MediaTekNpugVaeCodec.quantizeInt16LittleEndian(floatArrayOf(Float.NaN), 1.0, 0)
        }.isFailure)
        assertTrue(runCatching {
            MediaTekNpugVaeCodec.dequantizeInt16LittleEndian(byteArrayOf(1), 1.0, 0)
        }.isFailure)
        assertTrue(runCatching {
            MediaTekNpugVaeCodec.ncdhwDepthOneToNhwc(floatArrayOf(1f), 1, 2, 2)
        }.isFailure)
    }
}
