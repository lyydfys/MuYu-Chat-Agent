package com.muyuchat.mca.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.muyuchat.mca.MainActivity
import com.muyuchat.mca.video.model.VideoModelComponentRole
import com.muyuchat.mca.video.model.Wan22Ti2v5bProfiles
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-model acceptance evidence for V1-06: drives the production MainActivity video entry with
 * the pinned Wan2.2 TI2V-5B bundle and waits for the real worker to settle. Skipped (not passed)
 * when the pinned bundle is not installed.
 */
@RunWith(AndroidJUnit4::class)
class VideoWorkspaceRealModelDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 3_600_000)
    fun textToVideoProducesPublishedMedia() {
        val manifest = Wan22Ti2v5bProfiles.Q4_0_Q8_0
        val root = File(context.filesDir, "video_models/wan2.2-ti2v-5b")
        val missing = manifest.components.filter { !File(root, it.relativePath).isFile }
        assumeTrue("pinned model bundle not installed: $missing", missing.isEmpty())

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand(
            "am start -W -n ${context.packageName}/${MainActivity::class.java.name}",
        )
        if (device.wait(Until.hasObject(By.text("稍后")), 8_000)) {
            device.findObject(By.text("稍后"))?.click()
        }
        if (!device.wait(Until.hasObject(By.desc("打开历史")), 10_000)) {
            device.findObject(By.desc("返回聊天"))?.click()
        }
        assertTrue("chat entry missing", device.wait(Until.hasObject(By.desc("打开历史")), 30_000))
        device.findObject(By.desc("打开历史"))!!.click()
        assertTrue(
            "video drawer entry missing",
            device.wait(Until.hasObject(By.desc("打开视频工作区")), 10_000),
        )
        device.findObject(By.desc("打开视频工作区"))!!.click()
        assertTrue(
            "video workspace did not render",
            device.wait(Until.hasObject(By.desc("文生视频（已选）")), 30_000),
        )
        // The workspace opens on the graph canvas now; the chain rows below are the stable
        // entry points for parameter assertions.
        device.findObject(By.text("简化"))?.click()
        device.waitForIdle(600)

        val prompt = "a red ball bouncing on a wooden table, cinematic light"
        device.findObject(By.descContains("文字输入"))!!.click()
        val fields = device.findObjects(By.clazz("android.widget.EditText"))
        val field = fields.firstOrNull { it.visibleBounds.top in 2_000..2_500 }
        assertTrue("prompt field missing", field != null)
        field!!.text = prompt
        device.waitForIdle(600)
        assertTrue("prompt field rejected the text", field.text == prompt)
        // Dismiss the ModalBottomSheet by tapping its scrim instead of BACK (BACK can fall
        // through to the page when the IME never opened for an accessibility setText).
        device.click(1_000, 300)
        device.waitForIdle(800)
        if (device.hasObject(By.text("删除此节点"))) {
            device.pressBack()
            device.waitForIdle(600)
        }
        if (!device.hasObject(By.desc("文生视频（已选）"))) {
            if (device.wait(Until.hasObject(By.desc("打开历史")), 5_000)) {
                device.findObject(By.desc("打开历史"))!!.click()
                if (device.wait(Until.hasObject(By.desc("打开视频工作区")), 10_000)) {
                    device.findObject(By.desc("打开视频工作区"))!!.click()
                    device.wait(Until.hasObject(By.desc("文生视频（已选）")), 20_000)
                }
            }
        }
        assertTrue(
            "prompt did not reach the draft",
            device.wait(Until.hasObject(By.descContains("文字输入，a red ball")), 10_000),
        )

        val started = System.currentTimeMillis()
        device.findObject(By.text("生成"))!!.click()
        val deadline = started + 3_300_000
        var status = ""
        var peakWorkerKb = 0L
        var peakMainKb = 0L
        while (System.currentTimeMillis() < deadline) {
            val node = device.findObject(By.textContains("状态："))
            status = node?.text.orEmpty()
            val worker = totalPssKb(device, "${context.packageName}:local_video")
            val main = totalPssKb(device, context.packageName)
            if (worker > peakWorkerKb) peakWorkerKb = worker
            if (main > peakMainKb) peakMainKb = main
            if (status.contains("已完成") || status.contains("失败") || status.contains("已取消")) break
            Thread.sleep(5_000)
        }
        val elapsedMs = System.currentTimeMillis() - started
        device.takeScreenshot(File(context.filesDir, "video-t2v-result.png"))

        val report = buildString {
            appendLine("mode=text_to_video")
            appendLine("prompt=$prompt")
            appendLine("status=$status")
            appendLine("elapsedMs=$elapsedMs")
            appendLine("peakMainPssKb=$peakMainKb")
            appendLine("peakWorkerPssKb=$peakWorkerKb")
            appendLine("resolved=${manifest.revision}")
            appendLine("dit=${manifest.componentFor(VideoModelComponentRole.DIT).sha256}")
            appendLine("encoder=${manifest.componentFor(VideoModelComponentRole.TEXT_ENCODER_UMT5_XXL).sha256}")
            appendLine("vae=${manifest.componentFor(VideoModelComponentRole.VAE_WAN22_5B).sha256}")
        }
        File(context.filesDir, "video-t2v-acceptance.txt").writeText(report, Charsets.UTF_8)
        android.util.Log.i("MCTAVideo", report)

        assertTrue("real T2V run did not complete: $status", status.contains("已完成"))
        device.findObject(By.descContains("作品库"))!!.click()
        assertTrue(
            "published media missing from library",
            device.wait(Until.hasObject(By.descContains("播放")), 20_000),
        )
        device.takeScreenshot(File(context.filesDir, "video-t2v-library.png"))
    }

    private fun totalPssKb(device: UiDevice, process: String): Long {
        val out = device.executeShellCommand("dumpsys meminfo $process")
        return Regex("TOTAL PSS:\\s+(\\d+)").find(out)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }
}
