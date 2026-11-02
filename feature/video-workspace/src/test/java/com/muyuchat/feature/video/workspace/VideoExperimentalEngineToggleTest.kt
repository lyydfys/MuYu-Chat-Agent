package com.muyuchat.feature.video.workspace

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The external-engine switch (proposal: engine selection for E2E acceptance).
 *
 * The switch defaults to ON with the built-in MCA engine kept as the opt-out fallback, persists
 * through its injected preference store, and is only ever read when a submit freezes its engine —
 * history and retries keep their recorded snapshot.
 */
class VideoExperimentalEngineToggleTest {

    @Test
    fun `the switch defaults to on when nothing was ever persisted`() {
        val viewModel = source("VideoWorkspaceViewModel.kt")
        assertTrue(
            "an absent preference must select the external engine",
            viewModel.contains("enginePreference?.isExternalEngineEnabled() ?: true"),
        )
        assertTrue(
            "the switch state must exist as a read-only projection",
            viewModel.contains("val externalEngineEnabled: StateFlow<Boolean>"),
        )
    }

    @Test
    fun `the engine is only written into submits made after the switch was enabled`() {
        val viewModel = source("VideoWorkspaceViewModel.kt")
        assertTrue(
            "submit must freeze the selection of the moment, not a global flag",
            viewModel.contains("val engine = selectedEngine()"),
        )
        assertTrue(
            "enabled means the external backend",
            viewModel.contains("VideoLocalEngine.SDCPP_EXTERNAL"),
        )
        assertTrue(
            "disabled means the built-in engine",
            viewModel.contains("VideoLocalEngine.DEFAULT"),
        )
        assertTrue(
            "turning the switch persists it through the injected preference port",
            viewModel.contains("enginePreference?.setExternalEngineEnabled(enabled)"),
        )
    }

    @Test
    fun `the product Turbo LoRA is frozen only for external-engine submits`() {
        val viewModel = source("VideoWorkspaceViewModel.kt")
        assertTrue(
            "a local submit carries the LoRA binding resolved at submit time",
            viewModel.contains("val loraSpecs = turboLoraSpecs(engine)"),
        )
        assertTrue(
            "the built-in engine must never receive a LoRA binding",
            viewModel.contains("if (engine != VideoLocalEngine.SDCPP_EXTERNAL) return emptyList()"),
        )
        assertTrue(
            "a missing asset falls back to no binding instead of inventing a recipe",
            viewModel.contains("takeIf { it.isNotBlank() } ?: return emptyList()"),
        )
    }

    @Test
    fun `the full-VAE switch defaults on and persists through the same preference port`() {
        val viewModel = source("VideoWorkspaceViewModel.kt")
        assertTrue(
            "an absent preference must select the full-VAE decode",
            viewModel.contains("enginePreference?.isFullVaeEnabled() ?: true"),
        )
        assertTrue(
            "the full-VAE state must exist as a read-only projection",
            viewModel.contains("val fullVaeDecode: StateFlow<Boolean>"),
        )
        assertTrue(
            "turning the switch persists it through the injected preference port",
            viewModel.contains("enginePreference?.setFullVaeEnabled(enabled)"),
        )
    }

    @Test
    fun `the page renders an accessible full-VAE switch row`() {
        val screen = source("ui/VideoWorkspaceScreen.kt")
        assertTrue(
            "the row must be addressable for UI tests",
            screen.contains("testTag(\"video.full_vae_toggle\")"),
        )
        assertTrue(
            "the switch must be addressable for UI tests",
            screen.contains("testTag(\"video.full_vae_switch\")"),
        )
    }

    @Test
    fun `the page renders an accessible switch row and says which engine is active`() {
        val screen = source("ui/VideoWorkspaceScreen.kt")
        assertTrue(
            "the row must be addressable for UI tests",
            screen.contains("testTag(\"video.external_engine_toggle\")"),
        )
        assertTrue(
            "the row must expose switch semantics",
            screen.contains("role = Role.Switch"),
        )
        assertTrue(
            "the enabled copy names the external backend",
            screen.contains("sdcpp_external"),
        )
        assertTrue(
            "the off copy states the built-in fallback",
            screen.contains("使用内置 MCA 引擎"),
        )
        assertTrue(
            "the promoted engine must not be labelled experimental any more",
            !screen.contains("实验性视频后端"),
        )
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
