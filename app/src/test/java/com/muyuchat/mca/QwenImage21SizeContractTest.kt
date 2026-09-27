package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenImage21SizeContractTest {
    @Test
    fun upstreamExamplesAreDocumentedSeparatelyFromThePinnedMcaBundle() {
        assertEquals(512 to 512, QwenImage21SizeContract.SUPPORTED_SIZES.first())
        assertEquals(21, QwenImage21SizeContract.SUPPORTED_SIZES.size)
        QwenImage21SizeContract.SUPPORTED_SIZES.forEach { (width, height) ->
            assertTrue(QwenImage21SizeContract.isSupported(width, height))
        }
        assertEquals(512, QwenImage21SizeContract.DEFAULT_WIDTH)
        assertEquals(512, QwenImage21SizeContract.DEFAULT_HEIGHT)
        assertEquals(
            listOf(
                2048 to 2048,
                2400 to 1792,
                1792 to 2400,
                2528 to 1696,
                1696 to 2528,
                2752 to 1536,
                1536 to 2752,
            ),
            QwenImage21SizeContract.OFFICIAL_EXAMPLE_SIZES
        )
        assertTrue(QwenImage21SizeContract.RECOMMENDATION_SIZE_LINE.contains("7 种比例"))
        assertTrue(QwenImage21SizeContract.RECOMMENDATION_SIZE_LINE.contains("Standard"))
        assertTrue(QwenImage21SizeContract.RECOMMENDATION_SIZE_LINE.contains("512×512"))
        assertFalse(QwenImage21SizeContract.isSupported(672, 672))
        assertFalse(QwenImage21SizeContract.isSupported(1024, 1024))
        assertTrue(QwenImage21SizeContract.isNativeShape(672, 672))
        assertTrue(QwenImage21SizeContract.isNativeShape(1024, 1024))
        assertFalse(QwenImage21SizeContract.isNativeShape(255, 256))
        assertFalse(QwenImage21SizeContract.isNativeShape(256, 250))
    }

    @Test
    fun unsupportedSizeErrorContainsProblemAndNextStep() {
        val message = QwenImage21SizeContract.unsupportedSizeMessage(1024, 1024)
        assertTrue(message.contains("1024×1024"))
        assertTrue(message.contains("已验证尺寸"))
        assertTrue(message.contains("高分辨率运行包"))
        assertTrue(message.contains("32"))
    }
}
