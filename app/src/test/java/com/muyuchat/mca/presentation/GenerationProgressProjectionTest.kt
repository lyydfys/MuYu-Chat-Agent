package com.muyuchat.mca.presentation

import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.PersistStage
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.TokenProgress
import com.muyuchat.mca.MainUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pins the behaviour of the generation-progress quartet helper that replaced five hand-written
 * `copy(...)` sites in MainViewModel.
 *
 * The replacement was accepted only because it is an equivalence: for each replaced site the old
 * expression and the new one must produce the same MainUiState. These tests fix the two shapes
 * that actually occur -- the cleared quartet and the DECODE-with-stats quartet -- and, just as
 * importantly, fix the *contrast* with `copy(...)`: a partial copy keeps a field that this helper
 * would overwrite. That contrast is the reason twelve other sites were deliberately left alone.
 */
class GenerationProgressProjectionTest {

    private val tokenProgress = TokenProgress(completedTokens = 7, totalTokens = 20)
    private val persistProgress = PersistProgress(
        stage = PersistStage.WRITING,
        writtenBytes = 3L,
        totalBytes = 7L
    )
    private val stats = RuntimeStats(loaded = true, completionTokens = 42)

    @Test
    fun clearedFormSetsAllFourFieldsToNull() {
        val before = MainUiState(
            generationPhase = GenerationPhase.DECODE,
            generationTokenProgress = tokenProgress,
            generationPersistProgress = persistProgress,
            generationStats = stats
        )

        val after = before.withGenerationProgressCleared()

        assertEquals(null, after.generationPhase)
        assertEquals(null, after.generationTokenProgress)
        assertEquals(null, after.generationPersistProgress)
        assertEquals(null, after.generationStats)
    }

    @Test
    fun decodeFormCarriesPhaseAndStatsAndClearsTheIntermediateProgress() {
        // This is the shape at the streaming site that enters DECODE: phase and stats advance,
        // while the token/persist progress of the previous step is cleared.
        val before = MainUiState(
            generationPhase = GenerationPhase.PREFILL,
            generationTokenProgress = tokenProgress,
            generationPersistProgress = persistProgress,
            generationStats = null
        )

        val after = before.withGenerationProgress(
            phase = GenerationPhase.DECODE,
            tokenProgress = null,
            persistProgress = null,
            stats = stats
        )

        assertEquals(GenerationPhase.DECODE, after.generationPhase)
        assertEquals(null, after.generationTokenProgress)
        assertEquals(null, after.generationPersistProgress)
        assertEquals(stats, after.generationStats)
    }

    @Test
    fun everyNonQuartetFieldIsCarriedOverUntouched() {
        // The helper must not disturb anything outside the quartet. MainUiState is a large union of
        // app state, so a helper that quietly reset an unrelated field would be a serious defect.
        val before = MainUiState(
            input = "draft text",
            isGenerating = true,
            statusMessage = "正在生成",
            loadedModelId = "model-1",
            loadedModelName = "mnn qwen35 2b fresh",
            generationPhase = GenerationPhase.DECODE,
            generationStats = stats
        )

        val after = before.withGenerationProgressCleared()

        assertEquals("draft text", after.input)
        assertEquals(true, after.isGenerating)
        assertEquals("正在生成", after.statusMessage)
        assertEquals("model-1", after.loadedModelId)
        assertEquals("mnn qwen35 2b fresh", after.loadedModelName)
    }

    @Test
    fun theClearedFormIsExactlyTheFourNullAssignmentsItReplaced() {
        // The equivalence claim, stated directly: the helper must equal the hand-written copy()
        // that all four cleared sites used.
        val before = MainUiState(
            generationPhase = GenerationPhase.DECODE,
            generationTokenProgress = tokenProgress,
            generationPersistProgress = persistProgress,
            generationStats = stats
        )

        val viaHelper = before.withGenerationProgressCleared()
        val viaCopy = before.copy(
            generationPhase = null,
            generationTokenProgress = null,
            generationPersistProgress = null,
            generationStats = null
        )

        assertEquals(viaCopy, viaHelper)
    }

    @Test
    fun theDecodeFormIsExactlyTheCopyWithStatsItReplaced() {
        val before = MainUiState(
            generationPhase = GenerationPhase.PREFILL,
            generationTokenProgress = tokenProgress,
            generationPersistProgress = persistProgress,
            generationStats = null
        )

        val viaHelper = before.withGenerationProgress(
            phase = GenerationPhase.DECODE,
            tokenProgress = null,
            persistProgress = null,
            stats = stats
        )
        val viaCopy = before.copy(
            generationPhase = GenerationPhase.DECODE,
            generationTokenProgress = null,
            generationPersistProgress = null,
            generationStats = stats
        )

        assertEquals(viaCopy, viaHelper)
    }

    @Test
    fun aPartialCopyKeepsAFieldThatThisHelperWouldOverwrite() {
        // This is why twelve other sites were not migrated. A copy() that names only three of the
        // four fields leaves the fourth at its previous value; this helper would set it to null.
        // The two are different states, and the difference is invisible to the compiler.
        val before = MainUiState(
            generationPhase = GenerationPhase.DECODE,
            generationTokenProgress = tokenProgress,
            generationPersistProgress = persistProgress,
            generationStats = stats
        )

        val partialCopy = before.copy(
            generationPhase = null,
            generationTokenProgress = null,
            generationPersistProgress = null
            // generationStats deliberately omitted -- copy() keeps the old value.
        )
        val viaHelper = before.withGenerationProgressCleared()

        assertEquals(stats, partialCopy.generationStats)
        assertEquals(null, viaHelper.generationStats)
        // The two forms must NOT be equal. If this ever starts passing as equal, one of the two
        // paths has changed meaning and the "leave it alone" sites need re-examining.
        assertNotSame(partialCopy, viaHelper)
    }

    @Test
    fun helperReturnsANewInstanceAndLeavesTheOriginalUntouched() {
        val before = MainUiState(
            generationPhase = GenerationPhase.DECODE,
            generationStats = stats
        )

        val after = before.withGenerationProgressCleared()

        assertNotSame(before, after)
        assertEquals(GenerationPhase.DECODE, before.generationPhase)
        assertEquals(stats, before.generationStats)
    }

    @Test
    fun clearedFormOnAnAlreadyClearedStateIsStable() {
        // The cleared sites can run more than once on the same state (for example a stop path and
        // a termination path both firing). Applying it twice must not drift.
        val empty = MainUiState()

        val once = empty.withGenerationProgressCleared()
        val twice = once.withGenerationProgressCleared()

        assertEquals(once, twice)
        assertSame(empty.generationPhase, once.generationPhase)
    }
}
