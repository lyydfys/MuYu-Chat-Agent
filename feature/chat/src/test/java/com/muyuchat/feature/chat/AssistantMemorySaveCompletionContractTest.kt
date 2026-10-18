package com.muyuchat.feature.chat

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AssistantMemorySaveCompletionContractTest {
    @Test
    fun successfulDeferredClearsEditingStateOnlyAfterCompletion() = runBlocking {
        val persisted = CompletableDeferred<Boolean>()
        val original = editor()
        var state = original.beginSave()

        val completion = async {
            val result = awaitAssistantMemorySave { persisted.await() }
            state = state.applySaveResult(result)
        }
        yield()

        assertEquals(original.editingId, state.editingId)
        assertEquals(original.scope, state.scope)
        assertEquals(original.content, state.content)
        assertTrue(state.isSaving)

        persisted.complete(true)
        completion.await()

        assertNull(state.editingId)
        assertEquals(original.scope, state.scope)
        assertEquals("", state.content)
        assertFalse(state.isSaving)
        assertNull(state.saveError)
    }

    @Test
    fun failedSaveRetainsTheDraftAndShowsARecoverableError() = runBlocking {
        val original = editor()
        val result = awaitAssistantMemorySave { false }

        val state = original.beginSave().applySaveResult(result)

        assertEquals(original.editingId, state.editingId)
        assertEquals(original.scope, state.scope)
        assertEquals(original.content, state.content)
        assertFalse(state.isSaving)
        assertEquals(ASSISTANT_MEMORY_SAVE_FAILED_MESSAGE, state.saveError)
    }

    @Test
    fun retryKeepsTheSameDraftAndClearsOnlyAfterALaterSuccess() = runBlocking {
        val original = editor()

        val failed = original.beginSave().applySaveResult(
            awaitAssistantMemorySave { false }
        )
        assertEquals(original.editingId, failed.editingId)
        assertEquals(original.scope, failed.scope)
        assertEquals(original.content, failed.content)

        val retried = failed.beginSave()
        assertNull(retried.saveError)
        val succeeded = retried.applySaveResult(
            awaitAssistantMemorySave { true }
        )

        assertNull(succeeded.editingId)
        assertEquals("", succeeded.content)
        assertEquals(original.scope, succeeded.scope)
        assertNull(succeeded.saveError)
    }

    @Test
    fun cancellationRetainsTheDraftAndDoesNotBecomeASuccess() = runBlocking {
        val original = editor()
        val persisted = CompletableDeferred<Boolean>()
        var state = original.beginSave()
        val completion = async {
            try {
                awaitAssistantMemorySave { persisted.await() }
                fail("Cancelled save must not produce a successful result")
            } catch (cancelled: CancellationException) {
                state = state.cancelSave()
                throw cancelled
            }
        }
        yield()

        persisted.cancel()
        try {
            completion.await()
            fail("Cancellation must propagate to the caller")
        } catch (_: CancellationException) {
            Unit
        }

        assertEquals(original.editingId, state.editingId)
        assertEquals(original.scope, state.scope)
        assertEquals(original.content, state.content)
        assertFalse(state.isSaving)
        assertEquals(ASSISTANT_MEMORY_SAVE_CANCELLED_MESSAGE, state.saveError)
    }

    @Test
    fun exceptionalCompletionRetainsTheDraftAndSurfacesTheCauseInline() = runBlocking {
        val original = editor()
        val persisted = CompletableDeferred<Boolean>()
        persisted.completeExceptionally(IOException("disk full"))

        val state = original.beginSave().applySaveResult(
            awaitAssistantMemorySave { persisted.await() }
        )

        assertEquals(original.editingId, state.editingId)
        assertEquals(original.scope, state.scope)
        assertEquals(original.content, state.content)
        assertFalse(state.isSaving)
        assertTrue(state.saveError.orEmpty().contains("disk full"))
        assertTrue(state.saveError.orEmpty().contains("草稿已保留"))
    }

    private fun editor() = AssistantMemoryEditorState(
        editingId = "memory-1",
        scope = "role_progress",
        content = "保留这段尚未确认写入的记忆"
    )
}
