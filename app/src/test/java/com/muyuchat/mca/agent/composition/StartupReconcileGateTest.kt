package com.muyuchat.mca.agent.composition

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupReconcileGateTest {

    @Test
    fun `concurrent entries run the reconciliation once`() = runBlocking {
        val gate = StartupReconcileGate()
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val jobs = (1..8).map {
            launch(Dispatchers.Default) {
                gate.runOnce {
                    calls.incrementAndGet()
                    entered.complete(Unit)
                    release.await()
                }
            }
        }
        entered.await()
        release.complete(Unit)
        jobs.joinAll()
        assertEquals(1, calls.get())
    }

    @Test
    fun `a failed reconciliation is retried by the next entry`() = runBlocking {
        val gate = StartupReconcileGate()
        val calls = AtomicInteger()
        val failure = runCatching {
            gate.runOnce {
                calls.incrementAndGet()
                error("recovery failed")
            }
        }
        assertTrue(failure.isFailure)
        gate.runOnce { calls.incrementAndGet() }
        assertEquals(2, calls.get())
    }

    @Test
    fun `a later entry does not repeat a completed reconciliation`() = runBlocking {
        val gate = StartupReconcileGate()
        val calls = AtomicInteger()
        gate.runOnce { calls.incrementAndGet() }
        gate.runOnce { calls.incrementAndGet() }
        assertEquals(1, calls.get())
    }
}
