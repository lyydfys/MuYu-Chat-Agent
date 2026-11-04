package com.muyuchat.mca.dsh.overlay

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wiring contract for the top streaming strip.
 *
 * Layout and timing rules are covered by [DshOverlayStripLogicTest]; these cases pin that the optional
 * window is actually created/removed by the existing overlay service, that the strip owns a pending
 * approval before the ball, and that the strip persistence uses its own `dsh_overlay_strip` document.
 */
class DshOverlayStripWiringTest {

    @Test
    fun `the service owns a second window that follows the strip switch`() {
        val service = source("app/src/main/java/com/muyuchat/mca/dsh/overlay/DshOverlayService.kt")

        assertTrue(service.contains("DshOverlayStripUiState()"))
        assertTrue(service.contains("fun applyStripSettings(next: DshOverlayStripSettings)"))
        assertTrue(service.contains("private fun syncStripWindow()"))
        assertTrue(service.contains("DshOverlayStripContent("))
        assertTrue(service.contains("private fun removeStripWindow()"))
        assertTrue(service.contains("DshOverlayStripLogic.FADE_VISIBLE_MILLIS"))
        assertTrue(service.contains("onTap = { returnToDsh() }"))
    }

    @Test
    fun `the strip wins the approval card and hands it back when switched off`() {
        val service = source("app/src/main/java/com/muyuchat/mca/dsh/overlay/DshOverlayService.kt")
        val host = source("app/src/main/java/com/muyuchat/mca/dsh/overlay/DshOverlayHost.kt")

        assertTrue(service.contains("stripApprovalQueue.present(request.id)"))
        assertTrue(service.contains("stripUi.approval = card"))
        assertTrue(service.contains("ui.approval = null"))
        assertTrue(service.contains("private fun moveApprovalToCurrentOwner()"))
        assertTrue(service.contains("if (stripSettings.enabled)"))
        assertTrue(service.contains("private fun decideStripApproval"))
        assertTrue(host.contains("DshOverlayStripStore(appContext)"))
        assertTrue(host.contains("strip = stripStore.loadSettings().toStripUi()"))
        assertTrue(host.contains("override fun setStripEnabled(enabled: Boolean)"))
        assertTrue(host.contains("DshOverlayService.instance?.applyStripSettings(next)"))
    }

    @Test
    fun `the strip document and the in-app settings surface use the strip key prefix`() {
        val store = source("app/src/main/java/com/muyuchat/mca/dsh/overlay/DshOverlayStripStore.kt")
        assertTrue(store.contains("\"dsh_overlay_strip\""))

        val panel = source(
            "feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/ui/DshOverlayPanel.kt"
        )
        assertTrue(panel.contains("顶部流式悬浮条"))
        assertTrue(panel.contains("DshOverlayStripSurfaceUi"))
        assertTrue(panel.contains("不透明度"))
        assertTrue(panel.contains("避开状态栏"))

        val port = source(
            "feature/dsh-workspace/src/main/java/com/muyuchat/feature/dsh/workspace/ui/DshOverlayUiPort.kt"
        )
        assertTrue(port.contains("val strip: DshOverlayStripUi"))
        assertTrue(port.contains("fun setStripEnabled(enabled: Boolean): String? = null"))
        assertTrue(port.contains("fun saveStripSettings(strip: DshOverlayStripUi): String? = null"))

        val window = source("app/src/main/java/com/muyuchat/mca/dsh/overlay/DshOverlayStripWindow.kt")
        assertFalse(window.contains("WebView("))
        assertTrue(window.contains("StripProgressBar"))
    }

    private fun source(relative: String): String {
        var root: File? = File(System.getProperty("user.dir")).absoluteFile
        while (root != null) {
            val candidate = File(root, relative)
            if (candidate.isFile) return candidate.readText()
            root = root.parentFile
        }
        error("source not found: $relative from ${System.getProperty("user.dir")}")
    }
}
