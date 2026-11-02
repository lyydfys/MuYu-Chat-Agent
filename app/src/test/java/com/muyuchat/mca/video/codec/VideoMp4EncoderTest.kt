package com.muyuchat.mca.video.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the pure parts of the encoder: PPM framing and limited-range BT.601 YUV420
 * conversion. Device codec/container behavior is NOT covered here (instrumentation, V1-04/V1-06).
 */
class VideoMp4EncoderTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val encoder = VideoMp4Encoder()

    @Test
    fun `reads a valid P6 frame and rejects trailing bytes`() {
        val dir = temporary.newFolder("frames")
        val pixels = byteArrayOf(
            1, 2, 3, 4, 5, 6,
            7, 8, 9, 10, 11, 12,
        )
        File(dir, "ok.ppm").writeBytes(ppm(2, 2, pixels))
        val target = ByteArray(pixels.size)
        assertArrayEquals(pixels, encoder.readPpm(dir, "ok.ppm", target, 2, 2))

        File(dir, "trailing.ppm").writeBytes(ppm(2, 2, pixels) + byteArrayOf(0))
        assertNull(encoder.readPpm(dir, "trailing.ppm", ByteArray(pixels.size), 2, 2))
    }

    @Test
    fun `rejects dimension mismatch and non-P6 magic`() {
        val dir = temporary.newFolder("bad")
        File(dir, "wrong.ppm").writeBytes(ppm(3, 1, ByteArray(9)))
        assertNull(encoder.readPpm(dir, "wrong.ppm", ByteArray(12), 2, 2))

        File(dir, "p5.ppm").writeBytes("P5\n1 1\n255\n\u0000".toByteArray(Charsets.ISO_8859_1))
        assertNull(encoder.readPpm(dir, "p5.ppm", ByteArray(3), 1, 1))
    }

    @Test
    fun `converts pure black and white to limited range YUV`() {
        val black = YuvFrameBuffer(2, 2)
        black.fillFromRgb(ByteArray(2 * 2 * 3) { 0 })
        assertEquals(16, black.y[0].toInt() and 0xff)
        assertEquals(128, black.u[0].toInt() and 0xff)
        assertEquals(128, black.v[0].toInt() and 0xff)

        val white = YuvFrameBuffer(2, 2)
        white.fillFromRgb(ByteArray(2 * 2 * 3) { 0xff.toByte() })
        assertEquals(235, white.y[0].toInt() and 0xff)
        assertEquals(128, white.u[0].toInt() and 0xff)
        assertEquals(128, white.v[0].toInt() and 0xff)
    }

    @Test
    fun `chroma planes average each two by two block`() {
        // Two 2x2 blocks: left red, right blue. Each chroma sample must keep its block's hue.
        val width = 4
        val height = 2
        val rgb = ByteArray(width * height * 3)
        for (row in 0 until height) {
            for (col in 0 until width) {
                val base = (row * width + col) * 3
                if (col < 2) {
                    rgb[base] = 0xff.toByte()
                } else {
                    rgb[base + 2] = 0xff.toByte()
                }
            }
        }
        val yuv = YuvFrameBuffer(width, height)
        yuv.fillFromRgb(rgb)
        assertEquals(2, yuv.u.size)
        assertTrue("red block must push V above neutral", (yuv.v[0].toInt() and 0xff) > 128)
        assertTrue("red block must not push U above neutral", (yuv.u[0].toInt() and 0xff) < 128)
        assertTrue("blue block must push U above neutral", (yuv.u[1].toInt() and 0xff) > 128)
        assertTrue("blue block must not push V above neutral", (yuv.v[1].toInt() and 0xff) < 128)
    }

    @Test
    fun `input size is the exact I420 frame size`() {
        val yuv = YuvFrameBuffer(6, 4)
        assertEquals(6 * 4 + 3 * 2 * 2, yuv.inputSize)
    }

    private fun ppm(width: Int, height: Int, pixels: ByteArray): ByteArray {
        val header = "P6\n$width $height\n255\n".toByteArray(Charsets.ISO_8859_1)
        return header + pixels
    }
}
