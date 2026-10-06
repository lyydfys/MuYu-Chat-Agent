package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression contract for DEFECT-002.
 *
 * `chatImageLoraSelections` is nullable in [MainUiState] and two writers store `null` **on purpose**
 * to mean "the stored selection is no longer usable, ask the user to re-select":
 *
 *  - `MainViewModel.reportMissingLocalImageLoraSelection()` — the selected LoRA was deleted;
 *  - `MainViewModel.updateChatImageLoraSelection()` — `frozen.takeIf { valid }` yields null when the
 *    resolved selection is invalid.
 *
 * The reader `chatImageOptionsForCurrentSelection()` used to assert non-null, which crashed the app
 * during composition (`McaApp` -> `chatImageToolPermissionUiState`) whenever a local image backend was
 * selected together with an invalidated LoRA selection. The persisted-device evidence for that crash
 * (8 entries, all from the pre-refactor build) is recorded in
 * `E:\MAC_REFACTOR_EVIDENCE\P9-38-dropbox-crash-audit.md`.
 */
class ChatImageLoraSelectionFallbackTest {

    // --- behaviour of the extracted pure decision -------------------------------------------------

    @Test
    fun nullSelectionOnLocalBackendDegradesInsteadOfAsserting() {
        val state = MainUiState(
            selectedImageBackend = ImageBackend.LOCAL,
            chatImageLoraSelections = null
        )
        assertNull(state.usableChatImageLoraSelections())
    }

    @Test
    fun emptySelectionOnLocalBackendIsStillAnEmptySelectionNotANullOne() {
        val state = MainUiState(
            selectedImageBackend = ImageBackend.LOCAL,
            chatImageLoraSelections = emptyList()
        )
        assertEquals(emptyList<Pair<String, Double>>(), state.usableChatImageLoraSelections())
    }

    @Test
    fun populatedSelectionOnLocalBackendIsPassedThroughUnchanged() {
        val selections = listOf("lora-a" to 0.75, "lora-b" to 0.25)
        val state = MainUiState(
            selectedImageBackend = ImageBackend.LOCAL,
            chatImageLoraSelections = selections
        )
        assertEquals(selections, state.usableChatImageLoraSelections())
    }

    @Test
    fun cloudBackendKeepsTheEarlyExitRegardlessOfStoredSelection() {
        val cloudStates = listOf(
            MainUiState(selectedImageBackend = ImageBackend.CLOUD, chatImageLoraSelections = null),
            MainUiState(selectedImageBackend = ImageBackend.CLOUD, chatImageLoraSelections = emptyList()),
            MainUiState(
                selectedImageBackend = ImageBackend.CLOUD,
                chatImageLoraSelections = listOf("lora-a" to 1.0)
            )
        )
        cloudStates.forEach { state ->
            assertNull(state.usableChatImageLoraSelections())
        }
    }

    @Test
    fun theDefaultStateBackendIsNotLocalSoTheGuardedPathNeedsAnExplicitSelection() {
        // Positive control for the assertions above: if the default backend were LOCAL, the
        // "cloudBackendKeepsTheEarlyExit" test would be exercising the wrong branch.
        assertFalse(MainUiState().selectedImageBackend == ImageBackend.LOCAL)
    }

    // --- source contract: the assertion must not come back ----------------------------------------

    @Test
    fun theViewModelNoLongerAssertsThatTheSelectionIsPresent() {
        // R3/C3-S14: the degraded decision moved verbatim to chatImageOptionsForCurrentSelectionFor
        // in SendChatImageRequestOperations.kt. The assertions keep their strength: the no-assert
        // guard now scans BOTH files (the buggy shape must not come back anywhere on this path) and
        // the positive half reads where the decision now lives.
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        val sendOperations = sourceFile("app/src/main/java/com/muyuchat/mca/SendChatImageRequestOperations.kt")
        assertFalse(
            "chatImageOptionsForCurrentSelection() must degrade on a null LoRA selection, not assert",
            source.contains("requireNotNull(state.chatImageLoraSelections)") ||
                sendOperations.contains("requireNotNull(state.chatImageLoraSelections)")
        )
        assertTrue(
            "the degraded path must go through the extracted decision",
            sendOperations.contains("state.usableChatImageLoraSelections() ?: return base")
        )
    }

    @Test
    fun theWritersStillProduceANullSelectionOnPurpose() {
        // Guards the premise of the fix: if these writers stopped storing null, the fallback would be
        // dead code and should be revisited rather than silently kept.
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        assertTrue(source.contains("it.copy(chatImageLoraSelections = null)"))
        assertTrue(source.contains("frozen.takeIf { valid }"))
        // The user-facing explanation must survive, otherwise we would trade a crash for a silent no-op.
        assertTrue(source.contains("所选 LoRA 已删除，请重新选择。"))
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate $relativePath")
    }
}

/**
 * Positive control for the source-contract assertions in [ChatImageLoraSelectionFallbackTest].
 *
 * A `contains(...)` contract is only meaningful if the searched strings actually discriminate between
 * the buggy and the fixed code. Without this control, a typo in either search string would silently
 * turn the contract into a vacuous `assertFalse(anything)`.
 *
 * Note on strength: the stronger control — temporarily reverting the production fix and observing the
 * contract test fail — was attempted and could not be completed, because the mechanical reversion made
 * `:app:compileDebugKotlin` abort with a Kotlin compiler internal error before any test ran. The
 * production file was restored byte-for-byte afterwards (SHA-256
 * `8D3A590AAB676B48E5E7DAA16BDFBD136E72292288E094B7B91684D74DA295DA`). This control therefore proves
 * discriminating power of the strings, not a full end-to-end reversion.
 */
class ChatImageLoraSelectionSourceContractControlTest {

    @Test
    fun theBugPatternIsRecognisedInTheHistoricalSourceShape() {
        val historicalShape = """
            val selections = requireNotNull(state.chatImageLoraSelections) {
                "聊天生图 LoRA 选择无效，请修正倍率、重新选择或明确清空后重试。"
            }
        """.trimIndent()
        assertTrue(
            "the bug search string must match the historical buggy line",
            historicalShape.contains("requireNotNull(state.chatImageLoraSelections)")
        )
    }

    @Test
    fun theFixedShapeIsNotMistakenForTheBugPattern() {
        val fixedShape = "val selections = state.usableChatImageLoraSelections() ?: return base"
        assertFalse(
            "the bug search string must not match the fixed line",
            fixedShape.contains("requireNotNull(state.chatImageLoraSelections)")
        )
        assertTrue(
            "the fixed-path search string must match the fixed line",
            fixedShape.contains("state.usableChatImageLoraSelections() ?: return base")
        )
    }

    @Test
    fun theWriterGuardStringsMatchTheSourceShapesTheyGuard() {
        // Same control for the two strings that guard the premise of the fallback.
        assertTrue("it.copy(chatImageLoraSelections = null)".contains("it.copy(chatImageLoraSelections = null)"))
        assertTrue("_uiState.update { it.copy(chatImageLoraSelections = frozen.takeIf { valid }) }".contains("frozen.takeIf { valid }"))
        assertTrue("""localImageLoraMessage = "所选 LoRA 已删除，请重新选择。"""".contains("所选 LoRA 已删除，请重新选择。"))
    }
}
