package com.muyuchat.mca.dsh

import com.muyuchat.core.engine.ChatExecutionBody
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.runtime.RuntimeSupervisor
import com.muyuchat.mca.facade.ChatExecutionFacade
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The production bridge from a DSH gateway cancel to the exact MCA engine stop.
 *
 * Before this was wired, `FacadeDshLocalChatPort.cancel` only called `facade.cancelChat(requestId)`
 * (a supervisor bookkeeping flag). That cannot break a native prefill blocked in the
 * `beginCompletion` binder call, which is why the worker kept burning 700 % CPU for ~70 s after a
 * cancel on device (2026-10-26). These tests fail without the
 * `facade.stopGenerationIfActive(requestId)` dispatch: the exact engine port below is only reached
 * through it.
 */
class FacadeDshLocalChatPortTest {

    @Test
    fun `cancel dispatches the exact engine stop for the request id`() = runBlocking {
        val engine = RecordingEngineBody()
        val port = FacadeDshLocalChatPort(ChatExecutionFacade(RuntimeSupervisor(), engine))

        assertTrue(port.cancel("dsh:7:3"))

        assertEquals(listOf("dsh:7:3"), engine.stopRequests)
        assertEquals(listOf("dsh:7:3"), engine.dispatchedStops)
        assertEquals("a gateway cancel must never use the global stop", 0, engine.globalStops.get())
    }

    @Test
    fun `cancel from an already cancelled collector still dispatches the stop`() = runBlocking {
        val engine = RecordingEngineBody()
        val port = FacadeDshLocalChatPort(ChatExecutionFacade(RuntimeSupervisor(), engine))

        val cancelledCollector = launch(Dispatchers.Default) {
            // This is the adapter's structural-cancellation unwind: the collector is already
            // cancelled when the port is asked to stop the request it owns. A dropped stop here
            // would mean the engine keeps prefilling after the gateway cancelled the call.
            currentCoroutineContext()[Job]?.cancel()
            port.cancel("dsh:7:4")
        }
        assertTrue(
            "the cancelled collector must unwind",
            withTimeoutOrNull(10_000L) { cancelledCollector.join() } != null
        )

        assertEquals(listOf("dsh:7:4"), engine.dispatchedStops)
    }

    @Test
    fun `structural cancellation of the collecting flow still stops that exact engine request`() =
        runBlocking {
            val engine = RecordingEngineBody(emitChunkFirst = true)
            val facade = ChatExecutionFacade(RuntimeSupervisor(), engine)
            val backend = McaDshGatewayBackend(
                localModels = DshLocalModelAccess { localModel() },
                localChat = FacadeDshLocalChatPort(facade),
                cloudModels = null,
                cloudChat = null
            )

            val started = CompletableDeferred<Unit>()
            val collector = launch(Dispatchers.Default) {
                runCatching {
                    backend.stream(dshRequest(callId = "dsh:8:1")).collect {
                        started.complete(Unit)
                    }
                }
            }
            assertTrue(
                "the stream must start",
                withTimeoutOrNull(10_000L) { started.await() } != null
            )

            // Structural cancellation only: no out-of-band gateway cancel. The adapter's unwind
            // must still dispatch the exact engine stop for this request id.
            collector.cancel()
            assertTrue(
                "the cancelled call must unwind",
                withTimeoutOrNull(10_000L) { collector.join() } != null
            )

            assertEquals(listOf("dsh:8:1"), engine.dispatchedStops)
            assertEquals(0, engine.globalStops.get())
        }

    /**
     * Stands in for the engine's `ChatExecutionBody`, mirroring the two properties the real
     * `chatExecutionBodyFor` provides: the request id is checked before a stop is dispatched, and a
     * repeated stop of the same request is acknowledged without a second dispatch.
     */
    private class RecordingEngineBody(
        private val emitChunkFirst: Boolean = false
    ) : ChatExecutionBody {
        val stopRequests = CopyOnWriteArrayList<String>()
        val dispatchedStops = CopyOnWriteArrayList<String>()
        val globalStops = AtomicInteger()
        private val everStopped = ConcurrentHashMap<String, Boolean>()

        override fun stream(
            request: ChatRequest,
            context: LocalChatExecutionContext
        ): Flow<GenerateEvent> = flow {
            if (emitChunkFirst) {
                emit(GenerateEvent.Chunk(text = "partial", stats = RuntimeStats(loaded = true)))
            }
            awaitCancellation()
        }

        override suspend fun stopGeneration() {
            globalStops.incrementAndGet()
        }

        override suspend fun stopGenerationIfActive(requestId: String): Boolean {
            stopRequests += requestId
            if (everStopped.putIfAbsent(requestId, true) != null) return true
            dispatchedStops += requestId
            return true
        }
    }
}
