package com.muyuchat.feature.video.workspace

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the production retry entry that V1 requires (cancel / retry / re-run from the page).
 *
 * The owner has always exposed `VideoWorkflowPort.retry`, but before this guard no screen called
 * it, so a terminal run could only be re-run by editing and re-submitting the whole draft. These
 * assertions bind the visible action to the owner call and keep the action limited to terminal
 * states (the owner itself refuses non-terminal retries).
 */
class VideoRetryActionContractTest {

    @Test
    fun `run card exposes retry and rerun actions backed by the owner`() {
        val section = source("ui/VideoRunStatusSection.kt")
        assertTrue(
            "terminal run card must render a retry action",
            section.contains("testTag(\"video.run_retry_action\")"),
        )
        assertTrue(
            "completed runs are re-run with the same frozen snapshot",
            section.contains("\"重新运行\""),
        )
        assertTrue("failed/cancelled/interrupted runs say retry", section.contains("\"重试\""))
        assertTrue(
            "retry stays limited to terminal states",
            section.contains("VideoRunState.COMPLETED") &&
                section.contains("VideoRunState.FAILED") &&
                section.contains("VideoRunState.CANCELLED") &&
                section.contains("VideoRunState.INTERRUPTED"),
        )

        val viewModel = source("VideoWorkspaceViewModel.kt")
        assertTrue(
            "the page must forward retry to the process owner instead of faking a new draft",
            viewModel.contains("target.retry(runId)"),
        )
        assertTrue(
            "retry rejection is surfaced verbatim",
            viewModel.contains("_retryNotice") && viewModel.contains("重试被拒绝"),
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
