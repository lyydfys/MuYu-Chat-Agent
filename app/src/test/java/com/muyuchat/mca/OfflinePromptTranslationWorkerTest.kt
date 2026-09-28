package com.muyuchat.mca

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePromptTranslationWorkerTest {
    @Test
    fun failedCleanupSlotRecoversOnlyAfterClosedWorkerProcessExits() {
        val slot = OfflinePromptTranslationWorkerSlot<WorkerState> { it.closed.get() && it.exited.get() }
        val previous = WorkerState()
        val next = WorkerState()
        assertTrue(slot.tryAcquire(previous))

        previous.closed.set(true)
        slot.release(previous)
        assertFalse(slot.tryAcquire(next))

        // Cleanup may already have timed out; the next request can discover later process death.
        previous.exited.set(true)
        assertTrue(slot.tryAcquire(next))
    }

    @Test
    fun lateReleaseCallbackCannotClearANewerWorkerOwner() {
        val slot = OfflinePromptTranslationWorkerSlot<WorkerState> { it.closed.get() && it.exited.get() }
        val previous = WorkerState()
        val next = WorkerState()
        assertTrue(slot.tryAcquire(previous))
        previous.closed.set(true)
        previous.exited.set(true)
        assertTrue(slot.tryAcquire(next))

        slot.release(previous)
        assertFalse(slot.tryAcquire(WorkerState()))
        next.closed.set(true)
        next.exited.set(true)
        slot.release(next)
        assertTrue(slot.tryAcquire(WorkerState()))
    }

    @Test
    fun successfulTranslationWaitsForNativeRelease() = runBlocking {
        val releasing = CompletableDeferred<Unit>()
        val releaseGate = CountDownLatch(1)
        val released = AtomicBoolean(false)
        val result = async {
            runOfflinePromptTranslationWorker(
                requestStop = {},
                release = {
                    releasing.complete(Unit)
                    check(releaseGate.await(5, TimeUnit.SECONDS))
                },
                onReleased = { released.set(true) }
            ) { "translated prompt" }
        }
        try {
            withTimeout(2_000) { releasing.await() }
            assertFalse(result.isCompleted)
            assertFalse(released.get())
            releaseGate.countDown()
            assertEquals("translated prompt", withTimeout(2_000) { result.await() })
            assertTrue(released.get())
        } finally {
            releaseGate.countDown()
        }
    }

    @Test
    fun userCancellationWaitsForNativeExecutionAndRelease() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val releasing = CompletableDeferred<Unit>()
        val nativeGate = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val released = AtomicBoolean(false)
        val result = launch {
            runOfflinePromptTranslationWorker(
                requestStop = { nativeGate.countDown() },
                release = {
                    releasing.complete(Unit)
                    check(releaseGate.await(5, TimeUnit.SECONDS))
                },
                onReleased = { released.set(true) }
            ) { cancelled ->
                started.complete(Unit)
                check(nativeGate.await(5, TimeUnit.SECONDS))
                check(cancelled.get())
            }
        }
        try {
            withTimeout(2_000) { started.await() }
            result.cancel()
            withTimeout(2_000) { releasing.await() }
            assertFalse(result.isCompleted)
            assertFalse(released.get())
            releaseGate.countDown()
            withTimeout(2_000) { result.join() }
            assertTrue(result.isCancelled)
            assertTrue(released.get())
        } finally {
            nativeGate.countDown()
            releaseGate.countDown()
        }
    }

    @Test
    fun stopIsRetriedWhenCancellationRacesWorkerBinding() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val nativeGate = CountDownLatch(1)
        val stopCalls = AtomicInteger()
        val released = AtomicBoolean(false)
        val result = launch {
            runOfflinePromptTranslationWorker(
                requestStop = { if (stopCalls.incrementAndGet() >= 2) nativeGate.countDown() },
                release = {},
                onReleased = { released.set(true) }
            ) {
                started.complete(Unit)
                check(nativeGate.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            withTimeout(2_000) { started.await() }
            result.cancel()
            withTimeout(2_000) { result.join() }
            assertTrue(stopCalls.get() >= 2)
            assertTrue(released.get())
        } finally {
            nativeGate.countDown()
        }
    }

    @Test
    fun cancellationDoesNotFinishWithAnOutstandingStopCall() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val nativeGate = CountDownLatch(1)
        val stopGate = CountDownLatch(1)
        val result = launch {
            runOfflinePromptTranslationWorker(
                requestStop = {
                    nativeGate.countDown()
                    check(stopGate.await(5, TimeUnit.SECONDS))
                },
                release = {},
                onReleased = { released.complete(Unit) }
            ) {
                started.complete(Unit)
                check(nativeGate.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            withTimeout(2_000) { started.await() }
            result.cancel()
            withTimeout(2_000) { released.await() }
            assertFalse(result.isCompleted)
            stopGate.countDown()
            withTimeout(2_000) { result.join() }
        } finally {
            nativeGate.countDown()
            stopGate.countDown()
        }
    }

    @Test
    fun failedReleaseCannotReturnACompletedTranslation() = runBlocking {
        val releaseFailure = IllegalStateException("native unload failed")
        val released = AtomicBoolean(false)
        val failure = runCatching {
            runOfflinePromptTranslationWorker(
                requestStop = {},
                release = { throw releaseFailure },
                onReleased = { released.set(true) }
            ) { "translated prompt" }
        }.exceptionOrNull()
        assertTrue(failure is OfflinePromptTranslationCleanupException)
        assertSame(releaseFailure, failure?.cause)
        assertFalse(released.get())
    }

    @Test
    fun unconfirmedReleaseHasABoundedWaitAndOverridesTranslationTimeout() = runBlocking {
        val nativeGate = CountDownLatch(1)
        val released = CompletableDeferred<Unit>()
        try {
            val failure = runCatching {
                withTimeout(50) {
                    runOfflinePromptTranslationWorker(
                        cleanupTimeoutMs = 150,
                        requestStop = {},
                        release = {},
                        onReleased = { released.complete(Unit) }
                    ) {
                        check(nativeGate.await(5, TimeUnit.SECONDS))
                        "translated prompt"
                    }
                }
            }.exceptionOrNull()
            assertTrue(failure is OfflinePromptTranslationCleanupException)
            assertFalse(released.isCompleted)
        } finally {
            nativeGate.countDown()
            withTimeout(2_000) { released.await() }
        }
    }

    private class WorkerState {
        val closed = AtomicBoolean(false)
        val exited = AtomicBoolean(false)
    }
}
