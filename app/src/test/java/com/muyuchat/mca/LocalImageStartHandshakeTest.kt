package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageStartHandshakeTest {
    @Test
    fun `cancel before start completes locally and prevents remote start`() {
        val handshake = LocalImageStartHandshake()

        assertEquals(
            LocalImageStartHandshake.CancelAction.COMPLETE_LOCALLY,
            handshake.requestCancel()
        )
        assertFalse(handshake.tryBeginRemoteStart())
    }

    @Test
    fun `cancel during binder start is deferred then retried after registration`() {
        val handshake = LocalImageStartHandshake()

        assertTrue(handshake.tryBeginRemoteStart())
        assertEquals(
            LocalImageStartHandshake.CancelAction.DEFER_UNTIL_REGISTERED,
            handshake.requestCancel()
        )
        assertTrue(handshake.completeRemoteStart(accepted = true))
    }

    @Test
    fun `cancel after registration goes directly to remote`() {
        val handshake = LocalImageStartHandshake()

        assertTrue(handshake.tryBeginRemoteStart())
        assertFalse(handshake.completeRemoteStart(accepted = true))
        assertEquals(
            LocalImageStartHandshake.CancelAction.CANCEL_REMOTE,
            handshake.requestCancel()
        )
    }
}
