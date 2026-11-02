package com.muyuchat.feature.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards image-task-rootcause F1/F2 (D1/D2): a rejected image submission must never leave the image
 * page with zero visible feedback.
 *
 * The first two tests pin the routing/fallback decision rules. The last two pin the wiring in the
 * real sources, because these call sites cannot be instantiated on the JVM without an Android
 * context, and a silently unwired fallback is exactly the defect being fixed.
 */
class ImageStatusSurfaceGuardTest {

    @Test
    fun unrecognisedAdmissionRejectionUsesTheVisibleComposerStatusSurface() {
        // Real messages published by ImageGenerationOwner.submit(), none of which userNotice()
        // recognises; before the fix GuidanceNotice returned without drawing anything.
        assertEquals(
            ImageStatusSurface.COMPOSER,
            imageStatusSurfaceFor("已有图片生成任务正在运行，请先停止当前任务")
        )
        assertEquals(
            ImageStatusSurface.COMPOSER,
            imageStatusSurfaceFor("已有 UI 或 Local API 图片任务正在运行，请等待完成或先停止当前任务")
        )
        assertEquals(
            ImageStatusSurface.COMPOSER,
            imageStatusSurfaceFor(
                "图片生成尚未启动：本地图像生成模型主文件不存在，请重新下载或重新导入。"
            )
        )
        assertEquals(
            ImageStatusSurface.COMPOSER,
            imageStatusSurfaceFor("本地聊天正在生成，请等本轮结束后再启动本地生图，避免设备内存冲突。")
        )
        assertEquals(
            ImageStatusSurface.COMPOSER,
            imageStatusSurfaceFor("Textual Inversion 库仍在加载，请稍后重试。")
        )
    }

    @Test
    fun recognisedGuidanceStillUsesTheGuidanceCard() {
        assertEquals(
            ImageStatusSurface.GUIDANCE,
            imageStatusSurfaceFor("请先在模型管理的本地页导入并选择图像生成引擎。")
        )
    }

    @Test
    fun rejectedSubmitPublishesTheFallbackButNeverOverwritesASpecificReason() {
        assertEquals(
            "无法开始图片生成，请检查已选模型与生成设置。",
            imageGenerationRejectionFallback(null)
        )
        assertNull(imageGenerationRejectionFallback("图片采样器不可用：x"))
    }

    @Test
    fun bothRejectedSubmitCallSitesPublishTheValidationError() {
        val enqueue = source("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatImagesWorkspaceEnqueue.kt")
        val overlay = source("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatImagesWorkspaceOverlay.kt")
        for (file in listOf("ChatImagesWorkspaceEnqueue.kt" to enqueue, "ChatImagesWorkspaceOverlay.kt" to overlay)) {
            val name = file.first
            val text = file.second
            assertTrue(
                "$name must route a rejected onGenerateImagePrompt through the fallback",
                text.contains("imageGenerationRejectionFallback(imageValidationError)")
            )
            assertTrue(
                "$name must still bump the start signal only on admission",
                text.contains("if (admitted)") && text.contains("imageGenerationStartSignal++")
            )
        }
    }

    @Test
    fun imagePageRendersGuidanceAndComposerSurfacesThroughTheSharedStatusRow() {
        val screen = source("feature/chat/src/main/java/com/muyuchat/feature/chat/ImageWorkspaceScreens.kt")
        assertTrue(
            "recognised guidance keeps the guidance card",
            screen.contains("ImageStatusSurface.GUIDANCE -> GuidanceNotice(")
        )
        assertTrue(
            "unrecognised text must reach the composer's status row",
            screen.contains("ImageStatusSurface.COMPOSER -> ImageStatusFallbackNotice(")
        )
        assertTrue(
            "the fallback reuses ComposerStatusMessage instead of a new design",
            screen.contains("ComposerStatusMessage(message = message, onDismiss = onDismiss)")
        )
    }

    /** Resolve a repository-relative path from the Gradle unit-test working directory. */
    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("source not found from ${System.getProperty("user.dir")}: $relative")
    }
}
