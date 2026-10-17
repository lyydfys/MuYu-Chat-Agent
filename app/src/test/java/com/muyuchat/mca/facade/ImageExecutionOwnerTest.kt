package com.muyuchat.mca.facade

import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.runtime.RuntimeSupervisor
import com.muyuchat.mca.LocalImageWorkerCancelledException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageExecutionOwnerTest {

    @Test
    fun successSettlesSucceededAndReleasesTheRegistryEntry() = runBlocking {
        val supervisor = RuntimeSupervisor()
        val owner = ImageExecutionOwner(supervisor)

        val value = owner.run("img-ok", "generate") { 42 }

        assertEquals(42, value)
        assertFalse(supervisor.requestRegistry.isActive("img-ok"))
        assertEquals(
            RequestState.Succeeded,
            supervisor.callbackRouter.terminalRecord("img-ok")?.finalState
        )
    }

    @Test
    fun plainFailureSettlesFailedAndPropagates() = runBlocking {
        val supervisor = RuntimeSupervisor()
        val owner = ImageExecutionOwner(supervisor)

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                owner.run("img-fail", "generate") { throw IllegalStateException("worker exploded") }
            }
        }
        assertEquals("worker exploded", failure.message)
        assertEquals(
            RequestState.Failed,
            supervisor.callbackRouter.terminalRecord("img-fail")?.finalState
        )
        // Note: the bounded terminal record currently carries the state only; the failure string /
        // rich ErrorEnvelope retention is the registered GAP-03 (R8) item, not asserted here.
    }

    @Test
    fun coroutineCancellationSettlesCancelledAndPropagates() = runBlocking {
        val supervisor = RuntimeSupervisor()
        val owner = ImageExecutionOwner(supervisor)

        assertThrows(CancellationException::class.java) {
            runBlocking { owner.run("img-cancel", "generate") { throw CancellationException("stopped") } }
        }
        assertEquals(
            RequestState.Cancelled,
            supervisor.callbackRouter.terminalRecord("img-cancel")?.finalState
        )
    }

    @Test
    fun workerCancellationTokenSettlesCancelled() = runBlocking {
        val supervisor = RuntimeSupervisor()
        val owner = ImageExecutionOwner(supervisor)

        assertThrows(LocalImageWorkerCancelledException::class.java) {
            runBlocking {
                owner.run("img-worker-cancel", "upscale") { throw LocalImageWorkerCancelledException() }
            }
        }
        assertEquals(
            RequestState.Cancelled,
            supervisor.callbackRouter.terminalRecord("img-worker-cancel")?.finalState
        )
    }

    @Test
    fun collectorCancellationSettlesCancelled() = runBlocking {
        val supervisor = RuntimeSupervisor()
        val owner = ImageExecutionOwner(supervisor)
        val started = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val job = scope.launch {
                owner.run("img-collector-cancel", "generate") {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            withTimeout(5_000) { started.await() }
            job.cancel()
            job.join()
        } finally {
            scope.cancel()
        }

        assertEquals(
            RequestState.Cancelled,
            supervisor.callbackRouter.terminalRecord("img-collector-cancel")?.finalState
        )
    }

    @Test
    fun duplicateLiveRequestIdIsAnExplicitConflict() = runBlocking {
        val supervisor = RuntimeSupervisor()
        val owner = ImageExecutionOwner(supervisor)
        val started = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val job = scope.launch {
                owner.run("img-dup", "generate") {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            withTimeout(5_000) { started.await() }

            assertThrows(IllegalStateException::class.java) {
                runBlocking { owner.run("img-dup", "generate") { } }
            }
            job.cancel()
            job.join()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun shutDownSupervisorRejectsNewOperations() {
        val supervisor = RuntimeSupervisor()
        val owner = ImageExecutionOwner(supervisor)
        supervisor.shutdownProcess()

        assertThrows(IllegalStateException::class.java) {
            runBlocking { owner.run("img-shutdown", "generate") { } }
        }
    }

    @Test
    fun cancelEntryPointsMarkTheSupervisorBookkeeping() = runBlocking {
        val supervisor = RuntimeSupervisor()
        val owner = ImageExecutionOwner(supervisor)
        supervisor.admitRequest("img-live")

        owner.cancel("img-live")
        assertTrue(supervisor.cancellationController.isCancellationRequested("img-live"))

        supervisor.admitRequest("img-live-2")
        owner.cancelAll("test cleanup")
        assertTrue(supervisor.cancellationController.isCancellationRequested("img-live-2"))

        owner.cancel("   ")
        assertTrue(ImageExecutionGate.PASSTHROUGH.run("noop", "generate") { "passthrough" } == "passthrough")
    }
}