package com.muyuchat.mca

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressionRequestStoreTest {
    @Test
    fun manualRequestRemainsBoundToItsConversationUntilThatConversationSends() {
        val requests = ContextCompressionRequestStore()

        assertTrue(requests.request("session-a"))
        assertTrue(requests.isPending("session-a"))
        assertFalse(requests.isPending("session-b"))
        assertFalse(requests.consume("session-b"))
        assertTrue(requests.consume("session-a"))
        assertFalse(requests.isPending("session-a"))
    }

    @Test
    fun deletingOrClearingConversationRequestsDoesNotLeakIntoANewChat() {
        val requests = ContextCompressionRequestStore()
        requests.request("session-a")
        requests.clear("session-a")
        assertFalse(requests.isPending("session-a"))

        requests.request("session-b")
        requests.clearAll()
        assertFalse(requests.isPending("session-b"))
        assertFalse(requests.request(" "))
    }
}
