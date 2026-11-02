package com.muyuchat.feature.video.workspace

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The draft must survive a cold start, not only a system-restored process death. These are
 * source-contract checks (the VM needs an Android SavedStateHandle to instantiate), pinned to the
 * two call sites that make the durable store authoritative.
 */
class VideoDraftDurabilityContractTest {

    @Test
    fun `restore prefers saved state and falls back to the durable store`() {
        val viewModel = source("VideoWorkspaceViewModel.kt")
        assertTrue(viewModel.contains("val saved = savedState.get<String>(KEY_DRAFT)"))
        assertTrue(viewModel.contains("val durable = runCatching { draftStore?.load() }.getOrNull() ?: return null"))
    }

    @Test
    fun `every persist writes both saved state and the durable store`() {
        val viewModel = source("VideoWorkspaceViewModel.kt")
        assertTrue(viewModel.contains("savedState[KEY_DRAFT] = encoded"))
        assertTrue(viewModel.contains("runCatching { draftStore?.save(encoded) }"))
    }

    @Test
    fun `default recipe uses the Wan-aligned smoke length`() {
        val templates = source("VideoWorkflowTemplates.kt")
        assertTrue(
            "default frames must be 5 (4k+1 smoke length)",
            templates.contains("const val DEFAULT_FRAMES = 5L"),
        )
        assertTrue(templates.contains("const val DEFAULT_FPS = 24L"))
        assertTrue(templates.contains("const val DEFAULT_STEPS = 4L"))
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/muyuchat/feature/video/workspace/$relative"),
            File("feature/video-workspace/src/main/java/com/muyuchat/feature/video/workspace/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
        checkNotNull(file) { "source file not found: $relative" }
        return file.readText()
    }
}
