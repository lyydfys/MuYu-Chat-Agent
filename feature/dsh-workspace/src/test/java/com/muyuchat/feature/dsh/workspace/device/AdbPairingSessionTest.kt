package com.muyuchat.feature.dsh.workspace.device

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing state machine: happy path over an in-memory transport, every abort path, and the
 * honest "unsupported" outcome for a build that has no real transport.
 */
class AdbPairingSessionTest {

    @Test
    fun `client and server reach READY_FOR_TLS with the same key`() {
        val client = AdbPairingSession.client(CODE, fastPolicy())
        val server = AdbPairingSession.server(CODE, fastPolicy())
        assertEquals(AdbPairingState.IDLE, client.state)

        val clientShare = requireNotNull(client.ownShare())
        val serverShare = requireNotNull(server.ownShare())
        assertEquals(AdbPairingState.SHARE_SENT, client.state)
        assertEquals(65, clientShare.size)
        assertEquals(0x04, clientShare[0].toInt())
        assertNull("a key must not exist before confirmation", client.sharedKey())

        assertTrue(client.acceptPeerShare(serverShare))
        assertTrue(server.acceptPeerShare(clientShare))
        assertEquals(AdbPairingState.SHARE_RECEIVED, client.state)
        assertNull("a key must not exist before confirmation", client.sharedKey())

        val clientConfirm = requireNotNull(client.ownConfirm())
        val serverConfirm = requireNotNull(server.ownConfirm())
        assertEquals(32, clientConfirm.size)

        assertEquals(PairingOutcome.READY_FOR_TLS, client.acceptPeerConfirm(serverConfirm))
        assertEquals(PairingOutcome.READY_FOR_TLS, server.acceptPeerConfirm(clientConfirm))
        assertEquals(AdbPairingState.CONFIRMED, client.state)
        assertEquals(PairingOutcome.READY_FOR_TLS, client.outcome())
        assertArrayEquals(client.sharedKey(), server.sharedKey())
        assertEquals(16, requireNotNull(client.sharedKey()).size)
    }

    @Test
    fun `runExchange drives both sides through a pluggable transport`() {
        val client = AdbPairingSession.client(CODE, fastPolicy())
        val server = AdbPairingSession.server(CODE, fastPolicy())

        val clientOutcome = client.runExchange(PeerSessionExchange(server))

        assertEquals(PairingOutcome.READY_FOR_TLS, clientOutcome)
        assertEquals(PairingOutcome.READY_FOR_TLS, server.outcome())
        assertEquals(AdbPairingState.CONFIRMED, server.state)
        assertArrayEquals(client.sharedKey(), server.sharedKey())
    }

    @Test
    fun `a wrong pairing code is rejected on both sides`() {
        val client = AdbPairingSession.client("123456", fastPolicy())
        val server = AdbPairingSession.server("123457", fastPolicy())

        val clientShare = requireNotNull(client.ownShare())
        val serverShare = requireNotNull(server.ownShare())
        assertTrue(client.acceptPeerShare(serverShare))
        assertTrue(server.acceptPeerShare(clientShare))

        val clientOutcome = client.acceptPeerConfirm(requireNotNull(server.ownConfirm()))
        val serverOutcome = server.acceptPeerConfirm(requireNotNull(client.ownConfirm()))

        assertEquals(PairingOutcome.REJECTED, clientOutcome)
        assertEquals(PairingOutcome.REJECTED, serverOutcome)
        assertEquals(AdbPairingState.REJECTED, client.state)
        assertNull(client.sharedKey())
    }

    @Test
    fun `unsupported session never pretends to pair`() {
        val session = AdbPairingSession.unsupported()

        assertEquals(AdbPairingState.UNSUPPORTED, session.state)
        assertEquals(PairingOutcome.UNSUPPORTED, session.outcome())
        assertNull(session.ownShare())
        assertNull(session.ownConfirm())
        assertNull(session.sharedKey())
        assertFalse(session.isActive)
        assertFalse(session.acceptPeerShare(byteArrayOf(0x04)))
        assertEquals(PairingOutcome.UNSUPPORTED, session.acceptPeerConfirm(ByteArray(32)))
        assertEquals(PairingOutcome.UNSUPPORTED, session.runExchange(AlwaysFailsExchange))
    }

    @Test
    fun `empty peer share rejects the attempt permanently`() {
        val client = AdbPairingSession.client(CODE, fastPolicy())
        client.ownShare()

        assertFalse(client.acceptPeerShare(ByteArray(0)))
        assertEquals(AdbPairingState.REJECTED, client.state)
        assertEquals(PairingOutcome.REJECTED, client.outcome())
        // Single shot: a later, well-formed share must not revive the attempt.
        val server = AdbPairingSession.server(CODE, fastPolicy())
        assertFalse(client.acceptPeerShare(requireNotNull(server.ownShare())))
        assertEquals(AdbPairingState.REJECTED, client.state)
    }

    @Test
    fun `confirmation before a share is rejected`() {
        val client = AdbPairingSession.client(CODE, fastPolicy())

        assertEquals(PairingOutcome.REJECTED, client.acceptPeerConfirm(ByteArray(32)))
        assertEquals(AdbPairingState.REJECTED, client.state)
        assertNull(client.sharedKey())
    }

    @Test
    fun `duplicate confirmation cannot resurrect a finished attempt`() {
        val client = AdbPairingSession.client(CODE, fastPolicy())
        val server = AdbPairingSession.server(CODE, fastPolicy())
        val clientShare = requireNotNull(client.ownShare())
        val serverShare = requireNotNull(server.ownShare())
        assertTrue(client.acceptPeerShare(serverShare))
        assertTrue(server.acceptPeerShare(clientShare))
        val serverConfirm = requireNotNull(server.ownConfirm())
        assertEquals(PairingOutcome.READY_FOR_TLS, client.acceptPeerConfirm(serverConfirm))
        assertNotNull(client.sharedKey())

        assertEquals(PairingOutcome.REJECTED, client.acceptPeerConfirm(serverConfirm))
        assertEquals(AdbPairingState.REJECTED, client.state)
        assertNull("a replayed confirmation must drop the key", client.sharedKey())
    }

    @Test
    fun `a throwing transport becomes a typed rejection`() {
        val client = AdbPairingSession.client(CODE, fastPolicy())

        assertEquals(PairingOutcome.REJECTED, client.runExchange(AlwaysFailsExchange))
        assertEquals(AdbPairingState.REJECTED, client.state)
    }

    @Test
    fun `runExchange is single shot`() {
        val client = AdbPairingSession.client(CODE, fastPolicy())
        val server = AdbPairingSession.server(CODE, fastPolicy())
        assertEquals(PairingOutcome.READY_FOR_TLS, client.runExchange(PeerSessionExchange(server)))

        val secondServer = AdbPairingSession.server(CODE, fastPolicy())
        assertEquals(
            PairingOutcome.REJECTED,
            client.runExchange(PeerSessionExchange(secondServer))
        )
        assertEquals(AdbPairingState.REJECTED, client.state)
    }

    @Test
    fun `a long pairing code is accepted`() {
        val longCode = "6".repeat(512)
        val client = AdbPairingSession.client(longCode, fastPolicy())
        val server = AdbPairingSession.server(longCode, fastPolicy())

        assertEquals(PairingOutcome.READY_FOR_TLS, client.runExchange(PeerSessionExchange(server)))
        assertArrayEquals(client.sharedKey(), server.sharedKey())
    }

    @Test
    fun `own share is stable and the session starts inactive until used`() {
        val client = AdbPairingSession.client(CODE, fastPolicy())
        val first = requireNotNull(client.ownShare())
        val second = requireNotNull(client.ownShare())
        assertArrayEquals(first, second)
        assertTrue(client.isActive)
        assertNull(client.ownConfirm())
    }

    /**
     * Drives the real peer session as the "transport": each `exchange*` call feeds the peer the
     * message and returns the peer's. No network, no TLS — exactly the pluggable seam.
     */
    private class PeerSessionExchange(private val peer: AdbPairingSession) : AdbPairingExchange {
        override fun exchangeShare(ownShare: ByteArray): ByteArray {
            val peerShare = requireNotNull(peer.ownShare()) { "peer produced no share" }
            check(peer.acceptPeerShare(ownShare)) { "peer rejected the share" }
            return peerShare
        }

        override fun exchangeConfirm(ownConfirm: ByteArray): ByteArray {
            val peerConfirm = requireNotNull(peer.ownConfirm()) { "peer produced no confirmation" }
            check(peer.acceptPeerConfirm(ownConfirm) == PairingOutcome.READY_FOR_TLS) {
                "peer rejected the confirmation"
            }
            return peerConfirm
        }
    }

    private object AlwaysFailsExchange : AdbPairingExchange {
        override fun exchangeShare(ownShare: ByteArray): ByteArray = error("no transport")
        override fun exchangeConfirm(ownConfirm: ByteArray): ByteArray = error("no transport")
    }

    private fun fastPolicy() = Spake2PasswordPolicy(salt = "mca-session-salt".toByteArray(), iterations = 100)

    private companion object {
        const val CODE = "314159"
    }
}
