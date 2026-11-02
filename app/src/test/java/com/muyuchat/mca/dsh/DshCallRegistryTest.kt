package com.muyuchat.mca.dsh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cancellation identity: a call id maps deterministically to one MCA request id, a cancel targets
 * exactly one live call once, and late cancels / duplicate calls can never reach a newer call.
 * Suspending hooks are exercised in a [runBlocking] body because the gateway now awaits the exact
 * engine-stop dispatch instead of firing it synchronously.
 */
class DshCallRegistryTest {

    @Test
    fun `structured gateway id maps to the same MCA request id`() = runBlocking {
        val identity = DshCallIdentity.of("dsh:7:42")
        assertTrue(identity.structured)
        assertEquals(7L, identity.epoch)
        assertEquals(42L, identity.ordinal)
        assertEquals("dsh:7:42", identity.mcaRequestId)
        assertEquals(identity, DshCallIdentity.of("dsh:7:42"))
    }

    @Test
    fun `malformed gateway id still maps deterministically and distinctly`() = runBlocking {
        val first = DshCallIdentity.of("gateway:call/1")
        val second = DshCallIdentity.of("gateway:call/1")
        val other = DshCallIdentity.of("gateway:call/2")
        assertFalse(first.structured)
        assertEquals(first, second)
        assertTrue(first.mcaRequestId.startsWith("dsh:call:"))
        assertNotEquals(first.mcaRequestId, other.mcaRequestId)
        assertEquals(first.mcaRequestId, DshCallIdentity.of("gateway:call/1").mcaRequestId)
    }

    @Test
    fun `cancel invokes the exact hook once and only for the live call`() = runBlocking {
        val registry = DshCallRegistry()
        val session = DshCallSession(DshCallIdentity.of("dsh:1:1"))
        var hookCalls = 0
        session.attachCancelHook { hookCalls += 1 }

        assertTrue(registry.begin(session))
        assertTrue(registry.cancel("dsh:1:1"))
        assertEquals(1, hookCalls)
        assertFalse(registry.cancel("dsh:1:1"))
        assertEquals(1, hookCalls)
        assertFalse(registry.cancel("dsh:1:2"))

        registry.end(session)
        assertFalse(registry.isLive("dsh:1:1"))
        assertFalse(registry.cancel("dsh:1:1"))
    }

    @Test
    fun `cancelling one live session leaves another live session untouched`() = runBlocking {
        val registry = DshCallRegistry()
        val first = DshCallSession(DshCallIdentity.of("dsh:9:1"))
        val second = DshCallSession(DshCallIdentity.of("dsh:9:2"))
        var firstHookCalls = 0
        var secondHookCalls = 0
        first.attachCancelHook { firstHookCalls += 1 }
        second.attachCancelHook { secondHookCalls += 1 }

        assertTrue(registry.begin(first))
        assertTrue(registry.begin(second))
        assertTrue(registry.cancel("dsh:9:1"))
        assertEquals(1, firstHookCalls)
        assertEquals(0, secondHookCalls)
        assertTrue(first.isCancelled())
        assertFalse(second.isCancelled())

        assertTrue(registry.cancel("dsh:9:2"))
        assertEquals(1, secondHookCalls)
    }

    @Test
    fun `cancel before hook attachment is applied when the hook arrives`() = runBlocking {
        val registry = DshCallRegistry()
        val session = DshCallSession(DshCallIdentity.of("dsh:1:2"))
        assertTrue(registry.begin(session))
        assertTrue(registry.cancel("dsh:1:2"))
        assertTrue(session.isCancelled())

        var hookCalls = 0
        session.attachCancelHook { hookCalls += 1 }
        assertEquals(1, hookCalls)
    }

    @Test
    fun `a call id is served at most once even after the call finished`() = runBlocking {
        val registry = DshCallRegistry()
        val first = DshCallSession(DshCallIdentity.of("dsh:2:1"))
        assertTrue(registry.begin(first))
        registry.end(first)

        val replay = DshCallSession(DshCallIdentity.of("dsh:2:1"))
        assertFalse(registry.begin(replay))
        assertTrue(registry.hasRemembered("dsh:2:1"))

        val next = DshCallSession(DshCallIdentity.of("dsh:2:2"))
        assertTrue(registry.begin(next))
        registry.end(next)
    }

    @Test
    fun `duplicate live id is rejected and the first binding stays cancelable`() = runBlocking {
        val registry = DshCallRegistry()
        val first = DshCallSession(DshCallIdentity.of("dsh:3:1"))
        val duplicate = DshCallSession(DshCallIdentity.of("dsh:3:1"))
        var firstHook = 0
        var duplicateHook = 0
        first.attachCancelHook { firstHook += 1 }
        duplicate.attachCancelHook { duplicateHook += 1 }

        assertTrue(registry.begin(first))
        assertFalse(registry.begin(duplicate))
        assertTrue(registry.cancel("dsh:3:1"))
        assertEquals(1, firstHook)
        assertEquals(0, duplicateHook)
    }

    @Test
    fun `late cancel after a completed call does not touch a newer call`() = runBlocking {
        val registry = DshCallRegistry()
        val old = DshCallSession(DshCallIdentity.of("dsh:4:1"))
        assertTrue(registry.begin(old))
        registry.end(old)

        val newer = DshCallSession(DshCallIdentity.of("dsh:4:2"))
        var newerHookCalls = 0
        newer.attachCancelHook { newerHookCalls += 1 }
        assertTrue(registry.begin(newer))

        assertFalse(registry.cancel("dsh:4:1"))
        assertEquals(0, newerHookCalls)
        assertFalse(newer.isCancelled())
        assertEquals(1, registry.liveCount())
        registry.end(newer)
    }

    @Test
    fun `bounded id memory evicts only the oldest ids`() = runBlocking {
        val registry = DshCallRegistry(rememberedCallIdCapacity = 2)
        listOf("dsh:5:1", "dsh:5:2", "dsh:5:3").forEach { callId ->
            val session = DshCallSession(DshCallIdentity.of(callId))
            assertTrue(registry.begin(session))
            registry.end(session)
        }
        assertFalse(registry.hasRemembered("dsh:5:1"))
        assertTrue(registry.hasRemembered("dsh:5:2"))
        assertTrue(registry.hasRemembered("dsh:5:3"))
    }
}
