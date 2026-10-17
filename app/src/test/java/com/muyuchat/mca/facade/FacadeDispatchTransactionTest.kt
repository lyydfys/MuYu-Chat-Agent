package com.muyuchat.mca.facade

import com.muyuchat.core.engine.contract.CallbackEvent
import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.runtime.RuntimeSupervisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Admission transaction of the two execution facades: a second dispatch with an already-active
 * requestId must be rejected **without** leaving half-updated state behind.
 *
 * Before the fix, `dispatchChat`/`dispatchImage` ran `cancellation.registerRequest(...)` and
 * `router.subscribe(...)` *before* `registry.register(...)`. `CallbackRouter.subscribe` replaces the
 * route for that id and `RequestCancellationController.registerRequest` replaces the cancel handle,
 * so the duplicate rejection raised by the registry arrived after a live subscription and a pending
 * cancellation had already been overwritten.
 *
 * Every assertion below is an observable: what the router delivers, what the cancellation controller
 * reports, and what the registry tracks after the rejected dispatch.
 */
class FacadeDispatchTransactionTest {

    private fun newSupervisor() = RuntimeSupervisor(initialEpoch = 1L)

    /** Runs [block] and returns the throwable it raised; fails the test when nothing was raised. */
    private fun rejectionOf(block: () -> Unit): Throwable {
        try {
            block()
        } catch (error: Throwable) {
            return error
        }
        fail("expected the duplicate dispatch to be rejected")
        error("unreachable")
    }

    private fun tokenEvent(identity: RequestIdentity): CallbackEvent =
        CallbackEvent.token(
            identity = identity,
            sequence = 0L,
            text = "token",
            tokenIndex = 0L,
            monotonicTimeMs = 1L
        )

    private fun deliver(
        supervisor: RuntimeSupervisor,
        identity: RequestIdentity
    ): Boolean = supervisor.callbackRouter.routeEvent(
        tokenEvent(identity),
        currentRuntimeEpoch = identity.runtimeEpoch,
        currentSessionGeneration = identity.sessionGeneration
    )

    // ── chat facade ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a duplicate chat request id is rejected and the live subscription still receives events`() {
        val supervisor = newSupervisor()
        val facade = ChatExecutionFacade(supervisor)
        val firstConsumerEvents = mutableListOf<String>()
        val duplicateConsumerEvents = mutableListOf<String>()

        val first = facade.dispatchChat(ChatCommand(prompt = "first", requestId = "tx-chat-1")) { event ->
            firstConsumerEvents.add(event.eventType.name)
        }

        val failure = rejectionOf {
            facade.dispatchChat(ChatCommand(prompt = "second", requestId = "tx-chat-1")) { event ->
                duplicateConsumerEvents.add(event.eventType.name)
            }
        }

        assertTrue(
            "the registry's duplicate rejection must be the failure surfaced",
            failure is IllegalStateException
        )
        assertTrue("the first subscription must still be live", deliver(supervisor, first))
        assertEquals(listOf("Token"), firstConsumerEvents)
        assertTrue(
            "the rejected dispatch must not have taken over the callback route",
            duplicateConsumerEvents.isEmpty()
        )
        assertTrue("the accepted request must still be tracked", facade.isChatActive("tx-chat-1"))
        assertEquals("the rejected dispatch must not add a second entry", 1, supervisor.requestRegistry.count())
    }

    @Test
    fun `a duplicate chat request id does not discard a pending cancellation`() {
        val supervisor = newSupervisor()
        val facade = ChatExecutionFacade(supervisor)

        val first = facade.dispatchChat(ChatCommand(prompt = "first", requestId = "tx-chat-2")) { }
        assertTrue("cancellation must be recorded before the duplicate arrives", facade.cancelChat(first.requestId))

        rejectionOf { facade.dispatchChat(ChatCommand(prompt = "second", requestId = "tx-chat-2")) { } }

        assertTrue(
            "the pending cancellation must survive the rejected dispatch",
            supervisor.cancellationController.isCancellationRequested("tx-chat-2")
        )
        assertEquals("no extra cancel record may be left behind", 1, supervisor.cancellationController.activeCount())
        assertEquals(1, supervisor.requestRegistry.count())
    }

    @Test
    fun `a successful chat dispatch keeps subscription registration and identity consistent`() {
        val supervisor = newSupervisor()
        val facade = ChatExecutionFacade(supervisor)
        val events = mutableListOf<String>()

        val identity = facade.dispatchChat(ChatCommand(prompt = "hello", requestId = "tx-chat-ok")) { event ->
            events.add(event.eventType.name)
        }

        assertEquals("tx-chat-ok", identity.requestId)
        assertEquals(supervisor.currentEpoch.value, identity.runtimeEpoch)
        assertEquals(
            "the registry must hold the accepted identity",
            identity,
            supervisor.requestRegistry.getEntry("tx-chat-ok")?.identity
        )
        assertEquals(
            RequestState.Accepted,
            supervisor.requestRegistry.getEntry("tx-chat-ok")?.stateMachine?.currentState
        )
        assertEquals(1, supervisor.cancellationController.activeCount())
        assertTrue(facade.isChatActive("tx-chat-ok"))
        assertTrue("the subscription must be live", deliver(supervisor, identity))
        assertEquals(listOf("Token"), events)
        assertTrue(facade.cancelChat("tx-chat-ok"))
        assertTrue(supervisor.cancellationController.isCancellationRequested("tx-chat-ok"))
    }

    // ── image facade ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a duplicate image request id is rejected and the live subscription still receives events`() {
        val supervisor = newSupervisor()
        val facade = ImageGenerationFacade(supervisor)
        val firstConsumerEvents = mutableListOf<String>()
        val duplicateConsumerEvents = mutableListOf<String>()

        val first = facade.dispatchImage(
            ImageCommand(prompt = "first", targetOutputPath = "/tmp/tx-1.png", requestId = "tx-img-1")
        ) { event -> firstConsumerEvents.add(event.eventType.name) }

        val failure = rejectionOf {
            facade.dispatchImage(
                ImageCommand(prompt = "second", targetOutputPath = "/tmp/tx-2.png", requestId = "tx-img-1")
            ) { event -> duplicateConsumerEvents.add(event.eventType.name) }
        }

        assertTrue(failure is IllegalStateException)
        assertTrue("the first subscription must still be live", deliver(supervisor, first))
        assertEquals(listOf("Token"), firstConsumerEvents)
        assertTrue(duplicateConsumerEvents.isEmpty())
        assertTrue(facade.isImageActive("tx-img-1"))
        assertEquals(1, supervisor.requestRegistry.count())
    }

    @Test
    fun `a duplicate image request id does not discard a pending cancellation`() {
        val supervisor = newSupervisor()
        val facade = ImageGenerationFacade(supervisor)

        val first = facade.dispatchImage(
            ImageCommand(prompt = "first", targetOutputPath = "/tmp/tx-3.png", requestId = "tx-img-2")
        ) { }
        assertTrue(facade.cancelImage(first.requestId))

        rejectionOf {
            facade.dispatchImage(
                ImageCommand(prompt = "second", targetOutputPath = "/tmp/tx-4.png", requestId = "tx-img-2")
            ) { }
        }

        assertTrue(
            "the pending cancellation must survive the rejected dispatch",
            supervisor.cancellationController.isCancellationRequested("tx-img-2")
        )
        assertEquals(1, supervisor.cancellationController.activeCount())
        assertEquals(1, supervisor.requestRegistry.count())
    }

    @Test
    fun `a successful image dispatch keeps subscription registration and identity consistent`() {
        val supervisor = newSupervisor()
        val facade = ImageGenerationFacade(supervisor)
        val events = mutableListOf<String>()

        val identity = facade.dispatchImage(
            ImageCommand(prompt = "neon control room", targetOutputPath = "/tmp/tx-ok.png", requestId = "tx-img-ok")
        ) { event -> events.add(event.eventType.name) }

        assertEquals("tx-img-ok", identity.requestId)
        assertEquals(supervisor.currentEpoch.value, identity.runtimeEpoch)
        assertEquals(identity, supervisor.requestRegistry.getEntry("tx-img-ok")?.identity)
        assertEquals(
            RequestState.Accepted,
            supervisor.requestRegistry.getEntry("tx-img-ok")?.stateMachine?.currentState
        )
        assertEquals(1, supervisor.cancellationController.activeCount())
        assertTrue(facade.isImageActive("tx-img-ok"))
        assertTrue("the subscription must be live", deliver(supervisor, identity))
        assertEquals(listOf("Token"), events)
        assertNotNull(supervisor.requestRegistry.getEntry("tx-img-ok"))
    }
}
