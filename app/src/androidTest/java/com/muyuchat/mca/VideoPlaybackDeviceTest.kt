package com.muyuchat.mca

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.muyuchat.mca.video.codec.VideoMp4Encoder
import com.muyuchat.mca.video.data.VideoMediaAssetEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Real device playback proof for V1-05/V1-06 with a synthetic (not model-generated) MP4.
 *
 * Publishes a private media row + owned file, opens the production drawer/library/player, starts
 * playback and asserts the platform player did not surface an error. Model output is still
 * required for the V1-06 acceptance; this test separates the playback chain from generation.
 */
@RunWith(AndroidJUnit4::class)
class VideoPlaybackDeviceTest {

    @Test
    fun libraryPlaysAnOwnedMp4WithoutPlayerError() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val mediaId = "media-playback-${UUID.randomUUID().toString().lowercase()}"
        val mediaRoot = File(context.filesDir, "video/media").apply { mkdirs() }
        val file = File(mediaRoot, "$mediaId.mp4")
        val dir = File(context.cacheDir, "playback-frames-${UUID.randomUUID()}").apply { mkdirs() }
        val dao = McaRoomDatabase.get(context).videoWorkflowDao()
        try {
            val names = (0 until 17).map { index ->
                val name = "frame_%05d.ppm".format(index)
                File(dir, name).writeBytes(ppm(128, 128, index))
                name
            }
            val encoded = VideoMp4Encoder().encode(dir, names, 128, 128, 16, file)
            assertTrue("synthetic encode failed: $encoded", encoded is VideoMp4Encoder.Result.Encoded)
            val meta = encoded as VideoMp4Encoder.Result.Encoded
            runBlocking {
                dao.insertMedia(
                    VideoMediaAssetEntity(
                        mediaId = mediaId,
                        runId = "v-playback",
                        mimeType = "video/mp4",
                        relativePath = file.name,
                        sizeBytes = file.length(),
                        sha256 = "playback",
                        width = 128,
                        height = 128,
                        durationUs = meta.durationUs,
                        fps = 16,
                        frameCount = 17,
                        codec = "h264",
                        hasAudio = false,
                        createdAt = System.currentTimeMillis(),
                        deletedAt = null,
                    ),
                )
            }

            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            assertTrue("chat did not become visible", device.wait(Until.hasObject(By.desc("打开历史")), 60_000))
            device.findObject(By.desc("打开历史"))!!.click()
            assertTrue("video entry missing", device.wait(Until.hasObject(By.desc("打开视频工作区")), 20_000))
            device.findObject(By.desc("打开视频工作区"))!!.click()
            assertTrue("video workspace missing", device.wait(Until.hasObject(By.desc("文生视频（已选）")), 20_000))
            assertTrue("library action missing", device.wait(Until.hasObject(By.desc("作品库")), 10_000))
            device.findObject(By.desc("作品库"))!!.click()
            assertTrue("library did not open", device.wait(Until.hasObject(By.desc("播放视频")), 20_000))
            device.findObject(By.desc("播放视频"))!!.click()
            assertTrue("player did not open", device.wait(Until.hasObject(By.desc("关闭播放器")), 20_000))
            val play = device.findObject(By.desc("开始播放"))
            play?.click()
            Thread.sleep(2_500)
            assertTrue("player showed an error", !device.hasObject(By.textContains("播放错误")))
            assertTrue("playback did not start", device.hasObject(By.desc("暂停播放")))
            device.takeScreenshot(File(context.filesDir, "video-playback-device.png"))
            device.pressBack()
        } finally {
            runCatching {
                runBlocking {
                    dao.markMediaDeleted(mediaId, System.currentTimeMillis())
                    dao.deleteTombstonedMedia(mediaId)
                }
            }
            file.delete()
            dir.deleteRecursively()
        }
    }

    private fun ppm(width: Int, height: Int, index: Int): ByteArray {
        val header = "P6\n$width $height\n255\n".toByteArray(Charsets.ISO_8859_1)
        val pixels = ByteArray(width * height * 3)
        for (offset in pixels.indices step 3) {
            pixels[offset] = (20 + index * 12).coerceAtMost(255).toByte()
            pixels[offset + 1] = 100.toByte()
            pixels[offset + 2] = 180.toByte()
        }
        return header + pixels
    }
}
