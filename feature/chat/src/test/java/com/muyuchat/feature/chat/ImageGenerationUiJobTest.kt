package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageGenerationUiJobTest {
    @Test
    fun `cancelled terminal job never renders the creating placeholder`() {
        val job = ImageGenerationUiJob(
            id = "cancelled",
            prompt = "prompt",
            statusLabel = "已取消",
            terminal = true,
            message = "已取消图片生成"
        )

        assertEquals(ImageAssistantCardKind.TERMINAL, imageAssistantCardKind(job, image = null))
    }

    @Test
    fun `active image job continues to render the creating placeholder`() {
        val job = ImageGenerationUiJob(
            id = "active",
            prompt = "prompt",
            statusLabel = "生成中",
            terminal = false
        )

        assertEquals(ImageAssistantCardKind.CREATING, imageAssistantCardKind(job, image = null))
    }
}
