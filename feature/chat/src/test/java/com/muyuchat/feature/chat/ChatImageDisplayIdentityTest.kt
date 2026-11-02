package com.muyuchat.feature.chat

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R06: request identity, first-frame projection and cancellation isolation of the image display
 * state machine. These tests drive the real [ImageDisplaySlot] / [driveImageDisplay] /
 * [loadCachedImageOrFallback] types with suspended fakes, so every claim is about behaviour, not
 * source text. The decoded-bitmap cache internals (budget, LRU, dedupe, source revision) are already
 * proven by [ChatImageCacheContractTest] and are not duplicated here.
 */
class ChatImageDisplayIdentityTest {
    @Test
    fun `a ready result from another request is never projected on the next first frame`() {
        val recording = RecordingSlot()
        val requestA = request(uri = "content://images/a")
        val requestB = request(uri = "content://images/b")
        recording.slot.commit(
            next = ImageDisplayProjection.ready(requestA, "A"),
            currentRequest = requestA,
            ownerActive = true
        )

        // No effect has had a chance to run for B: the projection itself must already be Loading,
        // so A's bitmap cannot survive until the new producer executes.
        val projected = recording.slot.project(requestB)

        assertEquals(ImageDisplayPhase.LOADING, projected.phase)
        assertNull(projected.readyValue)
        assertEquals(requestB, projected.request)
        assertEquals("A", recording.stored?.readyValue)
    }

    @Test
    fun `slow request A cannot overwrite a faster request B`() = runTest {
        val recording = RecordingSlot()
        val requestA = request(uri = "content://images/a")
        val requestB = request(uri = "content://images/b")
        val current = AtomicReference(requestB)
        val releaseA = CompletableDeferred<Unit>()
        val slowA = async {
            driveImageDisplay(
                slot = recording.slot,
                request = requestA,
                currentRequest = { current.get() },
                decode = { releaseA.await(); "A" }
            )
        }
        val fastB = async {
            driveImageDisplay(
                slot = recording.slot,
                request = requestB,
                currentRequest = { current.get() },
                decode = { "B" }
            )
        }

        assertEquals("B", fastB.await().readyValue)
        releaseA.complete(Unit)
        // A finishes after B: its late commit is refused, so B stays on screen.
        assertEquals("B", slowA.await().readyValue)
        assertEquals("B", recording.stored?.readyValue)
        assertEquals(requestB, recording.stored?.request)
    }

    @Test
    fun `the same uri with a new revision projects loading instead of the old frame`() {
        val recording = RecordingSlot()
        val firstRevision = request(uri = "content://images/preview", revision = 1L)
        val nextRevision = request(uri = "content://images/preview", revision = 2L)
        recording.slot.commit(
            next = ImageDisplayProjection.ready(firstRevision, "frame-1"),
            currentRequest = firstRevision,
            ownerActive = true
        )

        val projected = recording.slot.project(nextRevision)

        assertEquals(ImageDisplayPhase.LOADING, projected.phase)
        assertNull(projected.readyValue)
    }

    @Test
    fun `a different target size is a different request`() {
        val recording = RecordingSlot()
        val fullSize = request(size = 384)
        val thumbnail = request(size = 192)
        recording.slot.commit(
            next = ImageDisplayProjection.ready(fullSize, "full"),
            currentRequest = fullSize,
            ownerActive = true
        )

        assertEquals(ImageDisplayPhase.LOADING, recording.slot.project(thumbnail).phase)
        assertEquals("full", recording.slot.project(fullSize).readyValue)
    }

    @Test
    fun `a new generation task cannot reuse the previous task image with the same uri and revision`() {
        val recording = RecordingSlot()
        val taskA = request(uri = "content://images/preview", revision = 7L, task = "job-a")
        val taskB = request(uri = "content://images/preview", revision = 7L, task = "job-b")
        recording.slot.commit(
            next = ImageDisplayProjection.ready(taskA, "task-a-frame"),
            currentRequest = taskA,
            ownerActive = true
        )

        val projected = recording.slot.project(taskB)

        assertEquals(
            "same URI and revision but another task must not show the previous task image",
            ImageDisplayPhase.LOADING,
            projected.phase
        )
        assertNull(projected.readyValue)
        // The same task may still keep its own valid live preview until its own revision changes.
        assertEquals("task-a-frame", recording.slot.project(taskA).readyValue)
    }

    @Test
    fun `a failure is retried through a new revision and reaches ready`() {
        val recording = RecordingSlot()
        val failed = request(revision = 0)
        recording.slot.commit(
            next = ImageDisplayProjection.failed(failed),
            currentRequest = failed,
            ownerActive = true
        )
        assertEquals(ImageDisplayPhase.FAILED, recording.slot.project(failed).phase)

        val retried = request(revision = 1)
        assertEquals(ImageDisplayPhase.LOADING, recording.slot.project(retried).phase)

        recording.slot.commit(
            next = ImageDisplayProjection.ready(retried, "recovered"),
            currentRequest = retried,
            ownerActive = true
        )
        assertEquals("recovered", recording.slot.project(retried).readyValue)
    }

    @Test
    fun `an absent source is not loading and not a failure`() {
        val recording = RecordingSlot()
        val blank = request(uri = "")

        val projected = recording.slot.project(blank)

        assertEquals(ImageDisplayPhase.ABSENT, projected.phase)
        assertNull(projected.request)
        assertNull(projected.readyValue)
    }

    @Test
    fun `cancelled decode rethrows and never commits a failure`() = runTest {
        val recording = RecordingSlot()
        val request = request()
        val decodeStarted = CompletableDeferred<Unit>()
        val owner = launch {
            driveImageDisplay(
                slot = recording.slot,
                request = request,
                currentRequest = { request },
                decode = {
                    decodeStarted.complete(Unit)
                    awaitCancellation()
                }
            )
        }
        decodeStarted.await()

        owner.cancelAndJoin()

        assertTrue(owner.isCancelled)
        assertNull("cancellation must not write any terminal state", recording.stored)
        assertEquals(ImageDisplayPhase.LOADING, recording.slot.project(request).phase)
    }

    @Test
    fun `a late result is dropped when the owner already left`() {
        val recording = RecordingSlot()
        val request = request()

        val committed = recording.slot.commit(
            next = ImageDisplayProjection.ready(request, "late"),
            currentRequest = request,
            ownerActive = false
        )

        assertNull(recording.stored)
        assertEquals(ImageDisplayPhase.LOADING, committed.phase)
    }

    @Test
    fun `a result that arrives after the asset was removed is dropped`() {
        val recording = RecordingSlot()
        val removed = request(uri = "")
        val deleted = request(uri = "content://images/deleted")

        val committed = recording.slot.commit(
            next = ImageDisplayProjection.ready(deleted, "deleted"),
            currentRequest = removed,
            ownerActive = true
        )

        assertNull(recording.stored)
        assertEquals(ImageDisplayPhase.ABSENT, committed.phase)
    }

    @Test
    fun `a cancelled subscription leaves no failure and the next request decodes again`() = runTest {
        val recording = RecordingSlot()
        val request = request()
        val attempts = AtomicInteger()
        val decodeStarted = CompletableDeferred<Unit>()
        val releaseStaleDecode = CompletableDeferred<Unit>()
        val first = launch {
            driveImageDisplay(
                slot = recording.slot,
                request = request,
                currentRequest = { request },
                decode = {
                    attempts.incrementAndGet()
                    decodeStarted.complete(Unit)
                    releaseStaleDecode.await()
                    "stale"
                }
            )
        }
        decodeStarted.await()

        first.cancelAndJoin()
        assertNull("the last cancelled subscriber must not poison the slot", recording.stored)

        // A fresh request decodes again and is allowed to commit.
        val fresh = async {
            driveImageDisplay(
                slot = recording.slot,
                request = request,
                currentRequest = { request },
                decode = {
                    attempts.incrementAndGet()
                    "fresh"
                }
            )
        }

        assertEquals("fresh", fresh.await().readyValue)
        assertEquals(2, attempts.get())
    }

    @Test
    fun `cancellation is rethrown before the fallback decode starts`() = runTest {
        val fallbackCalls = AtomicInteger()
        var thrown: Throwable? = null
        try {
            loadCachedImageOrFallback<String>(
                cached = { throw CancellationException("cancelled") },
                fallback = {
                    fallbackCalls.incrementAndGet()
                    "fallback"
                }
            )
        } catch (error: Throwable) {
            thrown = error
        }

        assertTrue(thrown is CancellationException)
        assertEquals("no fallback decode may start after cancellation", 0, fallbackCalls.get())
    }

    @Test
    fun `an ordinary cache failure still uses the one fallback decode`() = runTest {
        val fallbackCalls = AtomicInteger()

        val value = loadCachedImageOrFallback<String>(
            cached = { throw IllegalStateException("cache metadata unavailable") },
            fallback = {
                fallbackCalls.incrementAndGet()
                "decoded"
            }
        )

        assertEquals("decoded", value)
        assertEquals(1, fallbackCalls.get())
    }

    @Test
    fun `request identity covers uri size revision and task`() {
        val base = request()
        assertTrue(base.matches(request()))
        assertFalse(base.matches(request(uri = "content://images/other")))
        assertFalse(base.matches(request(size = 192)))
        assertFalse(base.matches(request(revision = 9)))
        assertFalse(base.matches(request(task = "job-2")))
        assertFalse(base.matches(null))
    }

    private class RecordingSlot {
        var stored: ImageDisplayProjection<String>? = null
        val slot: ImageDisplaySlot<String> = ImageDisplaySlot(
            read = { stored },
            write = { next -> stored = next }
        )
    }

    private fun request(
        uri: String = "content://images/a",
        size: Int? = 384,
        revision: Any? = 0,
        task: Any? = "job-1"
    ): ImageDisplayRequest = ImageDisplayRequest(
        uriString = uri,
        maxDimensionPx = size,
        revisionKey = revision,
        taskIdentity = task
    )
}
