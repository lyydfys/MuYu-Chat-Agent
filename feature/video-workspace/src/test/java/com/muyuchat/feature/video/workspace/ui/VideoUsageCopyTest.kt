package com.muyuchat.feature.video.workspace.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the video-page onboarding copy (usage guide + example prompts).
 *
 * The copy is rendered verbatim by [VideoUsageGuideCard] / [VideoPromptExamplesCard], so these
 * checks keep a future wording change from documenting controls that do not exist, promising a
 * modality the shipped Wan2.2 TI2V-5B template cannot run, or growing the compact rows without
 * bound. Pure JVM: no Compose, no Android, no device.
 */
class VideoUsageCopyTest {

    @Test
    fun `the guide keeps three to five steps that name real page controls`() {
        val steps = VideoUsageCopy.guideSteps
        assertTrue("guide must have 3-5 steps, had ${steps.size}", steps.size in 3..5)
        assertEquals(
            "step titles must be unique",
            steps.size,
            steps.map { it.title }.toSet().size,
        )
        steps.forEach { step ->
            assertTrue("step title must not be blank", step.title.isNotBlank())
            assertTrue("step '${step.title}' needs a detail line", step.detail.isNotBlank())
        }
        val text = steps.joinToString(separator = " ") { "${it.title} ${it.detail}" }
        // Every anchor is a label that actually exists on the page or in the top bar.
        listOf("本地模型组件", "文字输入", "条件构建", "生成", "作品").forEach { anchor ->
            assertTrue("guide must reference the real '$anchor' control", text.contains(anchor))
        }
    }

    @Test
    fun `example prompts stay inside the shipped template capability`() {
        val examples = VideoUsageCopy.promptExamples
        assertTrue("need 3-5 examples, had ${examples.size}", examples.size in 3..5)
        assertEquals(
            "example titles must be unique",
            examples.size,
            examples.map { it.title }.toSet().size,
        )
        val forbidden = listOf(
            "4K",
            "8K",
            "1080p",
            "60fps",
            "120fps",
            "多镜头",
            "字幕",
            "配音",
            "配乐",
        )
        examples.forEach { example ->
            assertTrue("'${example.title}' needs a prompt", example.prompt.isNotBlank())
            assertTrue(
                "'${example.title}' prompt is too long for a two-line row (${example.prompt.length})",
                example.prompt.length <= 60,
            )
            forbidden.forEach { token ->
                assertFalse(
                    "'${example.title}' must not promise '$token' the template cannot deliver",
                    example.prompt.contains(token, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `the prompt hint names the shipped template geometry and both modes`() {
        val hint = VideoUsageCopy.PROMPT_HINT
        assertTrue("hint must name the shipped model", hint.contains("Wan2.2 TI2V-5B"))
        assertTrue("hint must state the template resolution", hint.contains("480×832"))
        assertTrue("hint must mention image-to-video needs a picked image", hint.contains("图生视频"))
    }
}
