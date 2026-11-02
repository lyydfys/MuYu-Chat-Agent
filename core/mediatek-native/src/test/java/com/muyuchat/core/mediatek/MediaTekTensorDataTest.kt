package com.muyuchat.core.mediatek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MediaTekTensorDataTest {
    @Test
    fun floatTensorRequiresFiniteValuesAndMatchingShape() {
        val valid = MediaTekTensorData.Float32("hidden", listOf(1, 2), floatArrayOf(0.25f, -0.5f))
        assertNull(valid.validate("input"))
        assertEquals(2, valid.stats().count)

        val nonFinite = MediaTekTensorData.Float32("hidden", listOf(1, 2), floatArrayOf(1.0f, Float.POSITIVE_INFINITY))
        assertNotNull(nonFinite.validate("input"))

        val wrongShape = MediaTekTensorData.Float32("hidden", listOf(1, 3), floatArrayOf(1.0f, 2.0f))
        assertNotNull(wrongShape.validate("input"))
    }

    @Test
    fun tensorRejectsOverflowingAndOversizedShapes() {
        val overflowing = MediaTekTensorData.Int32(
            "tokens",
            listOf(Int.MAX_VALUE, Int.MAX_VALUE, 4),
            intArrayOf(0),
        )
        assertNotNull(overflowing.validate("input"))

        val tooLarge = MediaTekTensorData.Int8(
            "tokens",
            listOf(16 * 1024 * 1024 + 1),
            byteArrayOf(0),
        )
        assertNotNull(tooLarge.validate("input"))
    }

    @Test
    fun graphShapesRejectInvalidRankAndDimensions() {
        assertFalse(MediaTekNeuronGraphRunner.validShape(emptyList()))
        assertFalse(MediaTekNeuronGraphRunner.validShape(listOf(1, 0)))
        assertFalse(MediaTekNeuronGraphRunner.validShape(List(9) { 1 }))
        assertFalse(MediaTekNeuronGraphRunner.validShape(listOf(16 * 1024 * 1024 + 1)))
        assertEquals(true, MediaTekNeuronGraphRunner.validShape(listOf(1, 1024, 2048)))
    }

    @Test
    fun runnerRejectsDynamicGraphShapesBecauseLiteRtBindingsCannotResizeBuffers() {
        assertFalse(MediaTekNeuronGraphRunner.validContractShape(listOf(1, -1, 1024)))
        assertEquals(true, MediaTekNeuronGraphRunner.validContractShape(listOf(1, 512, 1024)))
        assertEquals(true, MediaTekNeuronGraphRunner.matchesShape(listOf(1, 512, 1024), listOf(1, 512, 1024)))
    }
}
