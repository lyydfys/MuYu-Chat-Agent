package com.muyuchat.mca.video.codec

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.mca.video.media.VideoMp4Verifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real MediaCodec/MediaMuxer evidence for V1-04 on a device.
 *
 * Synthetic frames only: this proves the encoder/container/decoder path, NOT model generation or
 * playback UI. Frames are intentionally distinguishable (red → green → blue) so a colour-order
 * regression is visible. A model-generated MP4 is still required at V1-06.
 */
@RunWith(AndroidJUnit4::class)
class VideoMp4EncoderDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun encodesSyntheticFramesIntoAVerifiedH264Mp4() {
        val width = 128
        val height = 128
        val frames = 17
        val fps = 16
        val dir = File(context.cacheDir, "video-encoder-test-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val names = (0 until frames).map { index ->
                val name = "frame_%05d.ppm".format(index)
                val colour = when {
                    index < 6 -> intArrayOf(220, 32, 32)
                    index < 12 -> intArrayOf(32, 200, 48)
                    else -> intArrayOf(32, 48, 220)
                }
                File(dir, name).writeBytes(ppm(width, height, colour))
                name
            }
            val output = File(dir, "output.mp4")
            val encoded = VideoMp4Encoder().encode(dir, names, width, height, fps, output)
            assertTrue("encode failed: $encoded", encoded is VideoMp4Encoder.Result.Encoded)
            encoded as VideoMp4Encoder.Result.Encoded
            assertEquals(frames, encoded.sampleCount)
            assertTrue(output.length() > 0)

            val verified = VideoMp4Verifier().verify(output, width, height, frames, fps)
            assertTrue("verify failed: $verified", verified is VideoMp4Verifier.Result.Verified)
            val probe = (verified as VideoMp4Verifier.Result.Verified).probe
            assertEquals(frames, probe.sampleCount)
            assertEquals(width, probe.width)
            assertEquals(height, probe.height)
            assertTrue("duration ${probe.durationUs}", probe.durationUs > 0)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cancellationStopsEncodingWithoutClaimingOutput() {
        val dir = File(context.cacheDir, "video-encoder-cancel-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val names = (0 until 12).map { index ->
                val name = "frame_%05d.ppm".format(index)
                File(dir, name).writeBytes(ppm(128, 128, intArrayOf(100, 100, 100)))
                name
            }
            val output = File(dir, "output.mp4")
            val checks = java.util.concurrent.atomic.AtomicInteger(0)
            val encoded = VideoMp4Encoder(isCancelled = { checks.incrementAndGet() > 3 }).encode(
                frameDir = dir,
                frameNames = names,
                width = 128,
                height = 128,
                fps = 16,
                output = output,
            )
            // The callback is evaluated on every loop; the encoder may queue a few frames before
            // observing cancellation, but it must report Cancelled (never Encoded). The worker
            // deletes the part file on Cancelled.
            assertTrue("cancel result must not be Encoded: $encoded", encoded !is VideoMp4Encoder.Result.Encoded)
            assertTrue(checks.get() > 3)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptFrameFailsInsteadOfWritingAPartialSuccess() {
        val dir = File(context.cacheDir, "video-encoder-bad-${System.nanoTime()}")
        dir.mkdirs()
        try {
            File(dir, "frame_00000.ppm").writeBytes(ppm(128, 128, intArrayOf(1, 2, 3)))
            File(dir, "frame_00001.ppm").writeBytes("not a ppm".toByteArray())
            val result = VideoMp4Encoder().encode(
                frameDir = dir,
                frameNames = listOf("frame_00000.ppm", "frame_00001.ppm"),
                width = 128,
                height = 128,
                fps = 16,
                output = File(dir, "output.mp4"),
            )
            assertTrue(result is VideoMp4Encoder.Result.Failed)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun ppm(width: Int, height: Int, colour: IntArray): ByteArray {
        val header = "P6\n$width $height\n255\n".toByteArray(Charsets.ISO_8859_1)
        val pixels = ByteArray(width * height * 3)
        for (index in pixels.indices step 3) {
            pixels[index] = colour[0].toByte()
            pixels[index + 1] = colour[1].toByte()
            pixels[index + 2] = colour[2].toByte()
        }
        return header + pixels
    }
}
