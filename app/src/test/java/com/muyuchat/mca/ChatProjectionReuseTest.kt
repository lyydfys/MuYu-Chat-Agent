package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.feature.chat.ChatUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * P02 复用契约：未变输入必须复用同一个投影实例。
 *
 * The fixture populates every cached domain; the "unrelated" state edits below are the fields that
 * change on every streaming frame (messages/input/generation stats/status) and are read by cached
 * domains at most for the context-summary session identity -- which they do not change.
 */
class ChatProjectionReuseTest {
    private val fixture = ChatProjectionFixtures.fixture()

    @Test
    fun unchangedDomainInputsReturnTheSameInstancesAcrossStreamingFrameChanges() {
        val cache = ChatUiProjectionCache()
        val first = fixture.project(cache)

        val streamingFrame = fixture.copy(
            state = fixture.state.copy(
                messages = fixture.state.messages + ChatMessage(Role.ASSISTANT, "流式增量"),
                input = "正在输入",
                generationStats = RuntimeStats(decodeTps = 42.0),
                statusMessage = "流式状态"
            )
        )
        val second = streamingFrame.project(cache)

        assertSame("assistants", first.assistants, second.assistants)
        assertSame("localModels", first.localModels, second.localModels)
        assertSame("images", first.images, second.images)
        assertSame("files", first.files, second.files)
        assertSame("imageJobs", first.imageJobs, second.imageJobs)
        assertSame("worldBooks", first.worldBooks, second.worldBooks)
        assertSame("knowledgeBases", first.knowledgeBases, second.knowledgeBases)
        assertSame("imageLoras", first.imageLoras, second.imageLoras)
        assertSame("imageTextualInversions", first.imageTextualInversions, second.imageTextualInversions)
        assertSame("imageUpscalers", first.imageUpscalers, second.imageUpscalers)
        assertSame("contextSummaryModels", first.contextSummaryModels, second.contextSummaryModels)
        assertSame("contextSummaryHistory", first.contextSummaryHistory, second.contextSummaryHistory)
        assertEquals("canUndoContextSummary", first.canUndoContextSummary, second.canUndoContextSummary)
    }

    @Test
    fun unrelatedRuntimeStatsFieldsDoNotInvalidateTheModelCatalog() {
        val cache = ChatUiProjectionCache()
        val first = fixture.project(cache)

        val noisyStats = fixture.state.stats.copy(
            decodeTps = 99.0,
            promptTokens = 123,
            completionTokens = 456,
            nativePssKb = 789L,
            nCtx = 1,
            loadMs = 5L
        )
        val second = fixture.copy(state = fixture.state.copy(stats = noisyStats)).project(cache)

        assertSame(first.localModels, second.localModels)
    }

    @Test
    fun unrelatedImageLibraryBackupProgressDoesNotInvalidateCachedCatalogs() {
        val cache = ChatUiProjectionCache()
        val first = fixture.project(cache)

        val second = fixture.copy(
            state = fixture.state.copy(
                imageLibraryBackup = ImageLibraryBackupState(
                    running = true,
                    done = 3,
                    total = 9,
                    message = "备份中"
                )
            )
        ).project(cache)

        assertSame(first.assistants, second.assistants)
        assertSame(first.localModels, second.localModels)
        assertSame(first.images, second.images)
        assertSame(first.files, second.files)
        assertSame(first.imageJobs, second.imageJobs)
        assertSame(first.worldBooks, second.worldBooks)
        assertSame(first.knowledgeBases, second.knowledgeBases)
        assertSame(first.imageLoras, second.imageLoras)
        assertSame(first.imageTextualInversions, second.imageTextualInversions)
        assertSame(first.imageUpscalers, second.imageUpscalers)
    }

    @Test
    fun freshLambdaIdentityAloneDoesNotInvalidateTheModelCatalog() {
        val cache = ChatUiProjectionCache()
        // project() creates a brand-new generation-params lambda on every call; identity cannot be
        // part of the dependency contract because the owner returns a new callable reference too.
        val first = fixture.project(cache)
        val second = fixture.project(cache)

        assertSame(first.localModels, second.localModels)
    }

    /**
     * Guard the reuse assertions above: the fixture must actually produce non-empty, distinct rows,
     * otherwise `assertSame(emptyList(), emptyList())` would pass even without a cache.
     */
    @Test
    fun fixtureProjectsNonEmptyRowsForEveryCachedDomain() {
        val projected: ChatUiState = fixture.project(ChatUiProjectionCache())

        assertEquals(4, projected.localModels.size)
        assertEquals(2, projected.assistants.size)
        assertEquals(2, projected.images.size)
        assertEquals(1, projected.files.size)
        assertEquals(1, projected.imageJobs.size)
        assertEquals(3, projected.worldBooks.size)
        assertEquals(2, projected.knowledgeBases.size)
        assertEquals(1, projected.imageLoras.size)
        assertEquals(1, projected.imageTextualInversions.size)
        assertEquals(1, projected.imageUpscalers.size)
        // "current" / "deterministic" / "local" + one configured cloud chat model.
        assertEquals(4, projected.contextSummaryModels.size)
        assertEquals(1, projected.contextSummaryHistory.size)
        assertEquals(true, projected.canUndoContextSummary)
    }
}
