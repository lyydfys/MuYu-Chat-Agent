package com.muyuchat.mca

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real device entry/render evidence for the video workspace (V0-02/V1-05/V1-06).
 *
 * Uses UiAutomator rather than Espresso: this Android 16 device no longer exposes the removed
 * `InputManager.getInstance` reflective target Espresso relies on. The test starts the production
 * MainActivity, opens the real drawer entry ("打开视频工作区"), switches to image-to-video, captures
 * a screenshot, and returns. Native generation is NOT run (weights absent).
 */
@RunWith(AndroidJUnit4::class)
class VideoWorkspaceDeviceTest {

    @Test
    fun drawerEntryOpensVideoWorkspaceAndImageModeShowsPicker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        // Launch through the shell: instrumentation startActivity can be rejected by background
        // activity-launch limits when another app owns the foreground. Do not force-stop the
        // target package here: the instrumentation itself runs in that process.
        device.executeShellCommand(
            "am start -W -n ${context.packageName}/${MainActivity::class.java.name}",
        )
        // The production shell can show a one-time release notice on cold launch. Its appearance
        // time varies with the update check, so poll both states instead of assuming the notice is
        // already on screen when the fixed 8 s window starts (observed flake: 2026-10-26 19:5x,
        // "chat did not become visible" when the notice appeared after the window).
        val chatDeadline = System.currentTimeMillis() + 90_000
        var chatVisible = false
        while (System.currentTimeMillis() < chatDeadline) {
            if (device.hasObject(By.desc("打开历史"))) {
                chatVisible = true
                break
            }
            val dismiss = device.findObject(By.text("稍后"))
            if (dismiss != null) {
                dismiss.click()
            } else {
                device.findObject(By.desc("返回聊天"))?.click()
            }
            Thread.sleep(500)
        }
        assertTrue("chat did not become visible", chatVisible)
        device.findObject(By.desc("打开历史"))!!.click()
        assertTrue(
            "video entry missing from the drawer",
            device.wait(Until.hasObject(By.desc("打开视频工作区")), 20_000),
        )
        device.findObject(By.desc("打开视频工作区"))!!.click()
        assertTrue(
            "video workspace did not render",
            device.wait(Until.hasObject(By.desc("文生视频（已选）")), 20_000),
        )
        device.findObject(By.desc("图生视频"))!!.click()
        assertTrue(
            "image-to-video picker did not render",
            device.wait(Until.hasObject(By.text("选择输入图片")), 20_000),
        )
        val screenshot = File(context.filesDir, "video-workspace-device.png")
        assertTrue("screenshot was not written", device.takeScreenshot(screenshot))
        assertTrue("screenshot is empty", screenshot.length() > 0)
        device.pressBack()
        assertTrue(
            "back did not return to chat",
            device.wait(Until.hasObject(By.desc("打开历史")), 20_000),
        )
    }
}
