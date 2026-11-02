package com.muyuchat.core.mediatek

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekAnimaTensorOpsTest {
    @Test
    fun patchifyUsesChannelThenPatchPixelOrderAndZeroPadding() {
        val actual = MediaTekAnimaTensorOps.patchifyLatent(
            latent = floatArrayOf(1f, 2f, 3f, 4f),
            channels = 1,
            height = 2,
            width = 2,
            patchSize = 2,
            paddedChannelsPerPatch = 5,
        )
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 0f), actual, 0f)
    }

    @Test
    fun unpatchifyConsumesPatchPixelThenChannelOrder() {
        val actual = MediaTekAnimaTensorOps.unpatchifyPrediction(
            prediction = floatArrayOf(10f, 11f, 20f, 21f, 30f, 31f, 40f, 41f),
            channels = 2,
            height = 2,
            width = 2,
            patchSize = 2,
        )
        assertArrayEquals(floatArrayOf(10f, 20f, 30f, 40f, 11f, 21f, 31f, 41f), actual, 0f)
    }

    @Test
    fun cfgAndEulerFollowAnimaFormula() {
        val guided = MediaTekAnimaTensorOps.classifierFreeGuidance(
            positive = floatArrayOf(3f, -1f),
            negative = floatArrayOf(1f, 1f),
            scale = 2f,
        )
        assertArrayEquals(floatArrayOf(5f, -3f), guided, 0f)
        val updated = MediaTekAnimaTensorOps.eulerUpdate(floatArrayOf(2f, 4f), guided, 1.0, 0.5)
        assertArrayEquals(floatArrayOf(-0.5f, 5.5f), updated, 0f)
    }

    @Test
    fun float16DecoderHandlesNormalsSubnormalsAndRejectsNonFinite() {
        val values = MediaTekAnimaTensorOps.decodeFloat16LittleEndian(
            byteArrayOf(0x00, 0x3c, 0x00, 0xc0.toByte(), 0x01, 0x00),
            expectedElements = 3,
        )
        assertEquals(1f, values[0], 0f)
        assertEquals(-2f, values[1], 0f)
        assertEquals(5.9604645e-8f, values[2], 0f)
        assertTrue(runCatching {
            MediaTekAnimaTensorOps.decodeFloat16LittleEndian(byteArrayOf(0x00, 0x7c), 1)
        }.isFailure)
    }

    @Test
    fun standardNormalSeedMatchesTorchCompatibleMt19937ReferenceVector() {
        val values = MediaTekAnimaTensorOps.standardNormalLikeTorchMt19937(seed = 0L, count = 4)
        assertArrayEquals(
            floatArrayOf(1.5409961f, -0.2934289f, -2.1787894f, 0.56843126f),
            values,
            0.0000001f,
        )
    }
}
