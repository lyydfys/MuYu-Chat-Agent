package com.muyuchat.mca.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import com.muyuchat.mca.MainActivity
import com.muyuchat.mca.video.model.Wan22Ti2v5bProfiles
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Minimal real-model smoke through the production video entry: 64x64, 9 frames, 1 Euler step.
 * It proves the native load/condition/sample/decode/encode/publish chain and the media library
 * without claiming the 480x832x33x20 product budget (which is far slower on CPU).
 */
@RunWith(AndroidJUnit4::class)
class VideoWorkspaceSmokeDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 1_800_000)
    fun realSmokeProducesPublishedMedia() {
        val manifest = Wan22Ti2v5bProfiles.Q4_0_Q8_0
        val root = File(context.filesDir, "video_models/wan2.2-ti2v-5b")
        assumeTrue(
            "pinned model bundle not installed",
            manifest.components.all { File(root, it.relativePath).isFile },
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP")
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("am start -W -n ${context.packageName}/${MainActivity::class.java.name}")
        if (device.wait(Until.hasObject(By.text("稍后")), 8_000)) {
            device.findObject(By.text("稍后"))?.click()
        }
        if (!device.wait(Until.hasObject(By.desc("打开历史")), 10_000)) {
            device.findObject(By.desc("返回聊天"))?.click()
        }
        assertTrue(device.wait(Until.hasObject(By.desc("打开历史")), 30_000))
        device.findObject(By.desc("打开历史"))!!.click()
        assertTrue(device.wait(Until.hasObject(By.desc("打开视频工作区")), 10_000))
        device.findObject(By.desc("打开视频工作区"))!!.click()
        assertTrue(device.wait(Until.hasObject(By.desc("文生视频（已选）")), 30_000))
        // The workspace opens on the graph canvas now; the chain rows below are the stable
        // entry points for parameter assertions.
        device.findObject(By.text("简化"))?.click()
        device.waitForIdle(600)

        setFields(device, "条件构建", listOf("64", "64", "9"))
        closeSheet(device)
        assertTrue(
            "geometry summary did not update",
            device.wait(Until.hasObject(By.desc("条件构建，64×64 · 9 帧")), 10_000),
        )

        setFields(device, "采样器", listOf("1"))
        closeSheet(device)
        assertTrue(
            "steps summary did not update",
            device.wait(Until.hasObject(By.desc("采样器，euler · 1 步")), 10_000),
        )

        device.findObject(By.descContains("文字输入"))!!.click()
        val promptField = editFields(device).firstOrNull { it.visibleBounds.top in 2_000..2_500 }
        assertTrue("prompt field missing", promptField != null)
        promptField!!.text = "a red ball bouncing on a wooden table"
        closeSheet(device)
        assertTrue(
            "prompt summary did not update",
            device.wait(Until.hasObject(By.descContains("文字输入，a red ball")), 10_000),
        )

        val started = System.currentTimeMillis()
        device.findObject(By.text("生成"))!!.click()
        var status = ""
        var peak = 0L
        val deadline = started + 1_680_000
        while (System.currentTimeMillis() < deadline) {
            status = device.findObject(By.textContains("状态："))?.text.orEmpty()
            for (process in listOf("${context.packageName}:local_video", context.packageName)) {
                val pss = Regex("TOTAL PSS:\\s+(\\d+)")
                    .find(device.executeShellCommand("dumpsys meminfo $process"))
                    ?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                if (pss > peak) peak = pss
            }
            if (status.contains("已完成") || status.contains("失败") || status.contains("已取消") || status.contains("已中断")) break
            Thread.sleep(5_000)
        }
        val elapsed = System.currentTimeMillis() - started
        device.takeScreenshot(File(context.filesDir, "video-smoke-result.png"))
        val report = buildString {
            appendLine("mode=text_to_video_smoke")
            appendLine("prompt=a red ball bouncing on a wooden table")
            appendLine("resolved=64x64x9 fps=16 steps=1 sampler=euler guidance=6.0 flowShift=3.0")
            appendLine("status=$status")
            appendLine("elapsedMs=$elapsed")
            appendLine("peakPssKb=$peak")
            appendLine("revision=${manifest.revision}")
            appendLine("dit=${manifest.components[0].sha256}")
            appendLine("encoder=${manifest.components[1].sha256}")
            appendLine("vae=${manifest.components[2].sha256}")
        }
        File(context.filesDir, "video-smoke-acceptance.txt").writeText(report, Charsets.UTF_8)
        android.util.Log.i("MCTAVideo", report)

        assertTrue("smoke did not complete: $status", status.contains("已完成"))
        device.findObject(By.descContains("作品库"))!!.click()
        assertTrue(
            "published media missing from library",
            device.wait(Until.hasObject(By.descContains("播放")), 20_000),
        )
        device.takeScreenshot(File(context.filesDir, "video-smoke-library.png"))
    }

    private fun editFields(device: UiDevice): List<UiObject2> =
        device.findObjects(By.clazz("android.widget.EditText"))
            .filter { it.visibleBounds.top in 1_200..2_900 }
            .sortedBy { it.visibleBounds.top }

    private fun setFields(device: UiDevice, rowPrefix: String, values: List<String>) {
        device.findObject(By.descContains(rowPrefix))!!.click()
        val fields = editFields(device)
        assertTrue("$rowPrefix fields missing", fields.size >= values.size)
        values.forEachIndexed { index, value -> fields[index].text = value }
        device.waitForIdle(600)
    }

    private fun closeSheet(device: UiDevice) {
        device.click(1_000, 300)
        device.waitForIdle(600)
        if (device.hasObject(By.text("删除此节点"))) {
            device.pressBack()
            device.waitForIdle(600)
        }
    }
}
