package com.muyuchat.mca.facade

import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.runtime.RuntimeSupervisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * Terminal-cleanup and shutdown contract at the Facade boundary (R1, Q02/Q04/Q07).
 *
 * The Facades are the production admission boundary for UI / Local API / Worker callers, so the
 * resource contract has to hold here, not only inside the runtime owner:
 *
 * * a stream failure settles exactly once and then releases the attempt's cancel handle, callback
 *   route and live registry entry, while the bounded terminal outcome stays queryable;
 * * shutdown leaves zero cancel handles, zero live entries and zero retained routes/outcomes;
 * * a dispatch that races shutdown never leaves a route or a cancel handle behind.
 */
class FacadeTerminalCleanupTest {

    private fun newSupervisor() = RuntimeSupervisor(initialEpoch = 1L)

    @Test
    fun `a stream failure releases the request resources and keeps a queryable outcome`() {
        val supervisor = newSupervisor()
        val facade = ChatExecutionFacade.admissionOnly(supervisor)
        val identity = facade.dispatchChat(ChatCommand(prompt = "prompt", requestId = "sf-chat-1")) { }
        assertTrue(supervisor.requestRegistry.transitionState(identity, RequestState.Preparing))
        assertTrue(supervisor.requestRegistry.transitionState(identity, RequestState.Running))

        assertTrue("the terminal must settle exactly once", facade.settleStreamFailure(identity, "sse overflow"))
        assertFalse(
            "a second failure report is not a second terminal",
            facade.settleStreamFailure(identity, "sse overflow")
        )

        assertEquals("the cancel handle must be released", 0, supervisor.cancellationController.activeCount())
        assertEquals("the live entry must be evicted", 0, supervisor.requestRegistry.count())
        assertFalse("the request is no longer active", facade.isChatActive("sf-chat-1"))

        val outcome = facade.terminalOutcome("sf-chat-1")
        assertNotNull("the bounded outcome must stay queryable after cleanup", outcome)
        assertEquals(RequestState.Failed, outcome!!.finalState)
        assertFalse("the broken channel did not deliver the terminal", outcome.delivered)

        // The public state query must keep answering after the live entry is evicted: it is served by
        // the retained outcome record instead of the evicted live registry entry.
        val snapshot = facade.snapshot("sf-chat-1")
        assertNotNull("the state query must still answer after terminal cleanup", snapshot)
        assertTrue(snapshot!!.terminal)
        assertEquals(RequestState.Failed, snapshot.state)
    }

    @Test
    fun `shutdown after a dispatch leaves no cancel handle route or live entry`() {
        val supervisor = newSupervisor()
        val chat = ChatExecutionFacade.admissionOnly(supervisor)

        chat.dispatchChat(ChatCommand(prompt = "prompt", requestId = "sd-chat-1")) { }
        assertEquals("admission must have reserved the request", 1, supervisor.cancellationController.activeCount())

        supervisor.shutdownProcess()

        assertEquals("shutdown must release every cancel handle", 0, supervisor.cancellationController.activeCount())
        assertEquals("shutdown must clear every live entry", 0, supervisor.requestRegistry.count())
        assertFalse(chat.isChatActive("sd-chat-1"))
        assertNull("no routed outcome may survive shutdown", chat.terminalOutcome("sd-chat-1"))
    }

    @Test
    fun `a dispatch after shutdown is rejected without leaving any reservation`() {
        val supervisor = newSupervisor()
        val facade = ChatExecutionFacade.admissionOnly(supervisor)
        supervisor.shutdownProcess()

        val failure = runCatching {
            facade.dispatchChat(ChatCommand(prompt = "late", requestId = "late-chat")) { }
        }.exceptionOrNull()

        assertTrue("a closed runtime must reject the dispatch loudly", failure is IllegalStateException)
        assertEquals(0, supervisor.cancellationController.activeCount())
        assertEquals(0, supervisor.requestRegistry.count())
        assertNull(facade.snapshot("late-chat"))
        assertNull(facade.terminalOutcome("late-chat"))
    }

    @Test
    fun `a dispatch that races shutdown never leaves a route or cancel handle behind`() {
        val iterations = 300
        repeat(iterations) { index ->
            val supervisor = newSupervisor()
            val facade = ChatExecutionFacade.admissionOnly(supervisor)
            val barrier = CyclicBarrier(2)
            val requestId = "facade-race-$index"

            val dispatcher = Thread {
                barrier.await(5, TimeUnit.SECONDS)
                runCatching {
                    facade.dispatchChat(ChatCommand(prompt = "prompt", requestId = requestId)) { }
                }
            }
            val closer = Thread {
                barrier.await(5, TimeUnit.SECONDS)
                supervisor.shutdownProcess()
            }
            dispatcher.start()
            closer.start()
            dispatcher.join(5_000)
            closer.join(5_000)

            assertEquals(
                "iteration $index: no cancel handle may survive shutdown",
                0,
                supervisor.cancellationController.activeCount()
            )
            assertEquals(
                "iteration $index: no live entry may survive shutdown",
                0,
                supervisor.requestRegistry.count()
            )
            assertNull(
                "iteration $index: no outcome may be archived after shutdown",
                facade.terminalOutcome(requestId)
            )
        }
    }
}
