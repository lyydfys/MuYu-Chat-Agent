package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生图提交结算规则：成功清空 / 失败保留 / 译文不串场 / 失效输入清理。
 *
 * 这些断言直接对应两个用户投诉：生成完成后输入框还留着 prompt；失败后还带着一条失效的图片引用，
 * 重试永远失败。规则本身是纯函数，Composable 只负责在 job 进入终态时调用。
 */
class ImagePromptSettlementTest {

    @Test
    fun `only a terminal successful job with produced images clears the prompt`() {
        assertFalse(shouldClearImagePromptAfterJob(terminal = false, failed = false, imageAssetCount = 1))
        assertFalse(shouldClearImagePromptAfterJob(terminal = true, failed = true, imageAssetCount = 0))
        assertFalse(shouldClearImagePromptAfterJob(terminal = true, failed = false, imageAssetCount = 0))
        assertTrue(shouldClearImagePromptAfterJob(terminal = true, failed = false, imageAssetCount = 2))
    }

    @Test
    fun `successful settlement clears only the submitted prompt`() {
        assertEquals(
            "",
            imagePromptAfterJobSettlement(
                currentPrompt = "一只猫",
                submittedPrompt = "一只猫",
                clearPrompt = true,
            ),
        )
        // 用户在生成期间改了提示词：保留新内容，不把正在编辑的文本冲掉。
        assertEquals(
            "一只更亮的猫",
            imagePromptAfterJobSettlement(
                currentPrompt = "一只更亮的猫",
                submittedPrompt = "一只猫",
                clearPrompt = true,
            ),
        )
    }

    @Test
    fun `failure and cancellation keep the prompt for retry`() {
        assertEquals(
            "一只猫",
            imagePromptAfterJobSettlement(
                currentPrompt = "一只猫",
                submittedPrompt = "一只猫",
                clearPrompt = false,
            ),
        )
    }

    @Test
    fun `stale translation drafts are hidden once the source changes`() {
        assertTrue(imagePromptTranslationVisibleFor("一只猫", "一只猫"))
        assertFalse(imagePromptTranslationVisibleFor("一只猫", "一只狗"))
        assertFalse(imagePromptTranslationVisibleFor("一只猫", ""))
    }

    @Test
    fun `stale input roles are collected and blank roles ignored`() {
        val stale = staleGenerationInputRoles(
            references = mapOf(
                ImageInputRole.INPUT to "file:///images/gone.png",
                ImageInputRole.MASK to null,
                ImageInputRole.CONTROL to "content://provider/control",
            ),
            reasonOf = { reference ->
                when (reference) {
                    "file:///images/gone.png" -> ImageAssetPreflightReason.MISSING
                    "content://provider/control" -> ImageAssetPreflightReason.NOT_READABLE
                    else -> ImageAssetPreflightReason.OK
                }
            },
        )
        assertEquals(listOf(ImageInputRole.INPUT, ImageInputRole.CONTROL), stale)
        assertNull(staleGenerationInputWarning(emptyList()))
        val message = staleGenerationInputWarning(listOf(ImageInputRole.INPUT))!!
        assertTrue(message.contains("输入图片"))
        assertTrue(message.contains("请重新选择"))
    }
}
