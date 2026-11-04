package com.muyuchat.feature.dsh.workspace.device

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing state machine, driven by a fake device that performs the real BoringSSL-style SPAKE2
 * exchange and the real AES-GCM peer-info step over an in-memory channel.
 *
 * No socket, no TLS: the transport is a seam. What these tests prove is that the state machine only
 * reports success when the device's reply decrypts, and that every failure path yields a stable reason
 * code (and never leaks the pairing code).
 */
class AdbPairingControllerTest {

    @Test
    fun `unverified protocol refuses before touching the transport`() {
        var transportCalls = 0
        val controller = AdbPairingController(
            transport = object : AdbPairingTransport {
                override fun connect(host: String, port: Int, connectTimeoutMs: Long): AdbPairingChannel {
                    transportCalls++
                    error("must not connect while unverified")
                }
            }
        )

        val result = controller.pair("127.0.0.1", 37_000, CODE)

        assertTrue(result is AdbPairingResult.Failure)
        val failure = result as AdbPairingResult.Failure
        assertEquals(AdbPairingFailure.UNSUPPORTED, failure.reason)
        assertEquals("unsupported", failure.reason.reasonCode)
        assertEquals(
            listOf("peer_info_layout", "aes_gcm_derivation", "packet_type_values", "tls_exporter_label"),
            failure.detail
        )
        assertEquals(0, transportCalls)
        assertFalse(controller.isPairing())
    }

    @Test
    fun `a real exchange with a compliant device succeeds`() {
        AdbPairingController.installPublicKeyWireFormat(PUBLIC_KEY)
        val device = FakeDevice(CODE, EXPORTER)
        val controller = verifiedController(device)

        val result = controller.pair("10.0.0.2", 43_521, CODE)

        assertTrue("expected success, got $result", result is AdbPairingResult.Success)
        val success = result as AdbPairingResult.Success
        assertTrue(success.peerInfo.isNotEmpty())
        // The device decrypted our peer info: proof that the GCM/key path actually worked.
        val deviceSaw = assertNotNull(device.decryptedClientPeerInfo).let { device.decryptedClientPeerInfo!! }
        assertTrue(deviceSaw.isNotEmpty())
        assertTrue(deviceSaw.toHexString().contains(PUBLIC_KEY.toHexString()))
        assertTrue(device.closed)
        assertFalse(controller.isPairing())
    }

    @Test
    fun `tls handshake failure is reported as tls_failed`() {
        val controller = verifiedController(
            transport = object : AdbPairingTransport {
                override fun connect(host: String, port: Int, connectTimeoutMs: Long): AdbPairingChannel =
                    error("connection refused")
            }
        )
        val failure = assertFailure(controller.pair("127.0.0.1", 37_000, CODE))
        assertEquals(AdbPairingFailure.TLS_FAILED, failure.reason)
        assertEquals("tls_failed", failure.reason.reasonCode)
    }

    @Test
    fun `wrong exporter length is a tls failure`() {
        val device = FakeDevice(CODE, ByteArray(32))
        val failure = assertFailure(verifiedController(device).pair("127.0.0.1", 37_000, CODE))
        assertEquals(AdbPairingFailure.TLS_FAILED, failure.reason)
        assertEquals(listOf("exporter_length"), failure.detail)
        assertTrue(device.closed)
    }

    @Test
    fun `an unusable device share is spake2_failed`() {
        val device = FakeDevice(CODE, EXPORTER, FakeDevice.Mode.BAD_SHARE)
        val failure = assertFailure(verifiedController(device).pair("127.0.0.1", 37_000, CODE))
        assertEquals(AdbPairingFailure.SPAKE2_FAILED, failure.reason)
        assertEquals("spake2_failed", failure.reason.reasonCode)
        assertTrue(device.closed)
    }

    @Test
    fun `an unauthenticated peer info reply is peer_info_rejected`() {
        val device = FakeDevice(CODE, EXPORTER, FakeDevice.Mode.BAD_REPLY)
        val failure = assertFailure(verifiedController(device).pair("127.0.0.1", 37_000, CODE))
        assertEquals(AdbPairingFailure.PEER_INFO_REJECTED, failure.reason)
        assertEquals("peer_info_rejected", failure.reason.reasonCode)
        assertTrue(device.closed)
    }

    @Test
    fun `a device that never answers produces timeout`() {
        var now = 0L
        val device = FakeDevice(CODE, EXPORTER)
        val controller = AdbPairingController(
            transport = FixedTransport(device) { now += AdbPairingController.OVERALL_TIMEOUT_MS + 1 },
            assumptions = AdbPairingWireAssumptions.ALL_VERIFIED,
            nowMillis = { now }
        )
        val failure = assertFailure(controller.pair("127.0.0.1", 37_000, CODE))
        assertEquals(AdbPairingFailure.TIMEOUT, failure.reason)
        assertTrue(device.closed)
    }

    @Test
    fun `cancellation between steps is reported as cancelled`() {
        lateinit var controller: AdbPairingController
        val device = FakeDevice(CODE, EXPORTER, onClientShare = { controller.cancel() })
        controller = verifiedController(device)

        val failure = assertFailure(controller.pair("127.0.0.1", 37_000, CODE))
        assertEquals(AdbPairingFailure.CANCELLED, failure.reason)
        assertTrue(device.closed)
    }

    @Test
    fun `a second attempt while one is running is rejected`() {
        lateinit var controller: AdbPairingController
        var nested: AdbPairingResult? = null
        val device = FakeDevice(CODE, EXPORTER)
        controller = AdbPairingController(
            transport = FixedTransport(device) { nested = controller.pair("127.0.0.1", 37_000, CODE) },
            assumptions = AdbPairingWireAssumptions.ALL_VERIFIED
        )

        val first = controller.pair("127.0.0.1", 37_000, CODE)

        assertTrue(first is AdbPairingResult.Success)
        assertEquals(AdbPairingFailure.CONCURRENT_PAIRING, assertFailure(requireNotNull(nested)).reason)
        assertFalse(controller.isPairing())
    }

    @Test
    fun `bad input is rejected without a reason that echoes the code`() {
        val controller = verifiedController(FakeDevice(CODE, EXPORTER))
        for (candidate in listOf("12345", "1234567", "12345x", "", " 12345")) {
            val failure = assertFailure(controller.pair("127.0.0.1", 37_000, candidate))
            assertEquals(AdbPairingFailure.INVALID_INPUT, failure.reason)
            assertTrue(failure.detail.none { it.contains(candidate) })
        }
        assertEquals(AdbPairingFailure.INVALID_INPUT, assertFailure(controller.pair("", 37_000, CODE)).reason)
        assertEquals(AdbPairingFailure.INVALID_INPUT, assertFailure(controller.pair("127.0.0.1", 0, CODE)).reason)
        assertEquals(
            AdbPairingFailure.INVALID_INPUT,
            assertFailure(controller.pair("127.0.0.1", 65_536, CODE)).reason
        )
    }

    @Test
    fun `failures never contain the pairing code or key material`() {
        val device = FakeDevice(CODE, EXPORTER, FakeDevice.Mode.BAD_REPLY)
        val failure = assertFailure(verifiedController(device).pair("127.0.0.1", 37_000, CODE))
        val serialized = failure.reason.reasonCode + "|" + failure.detail.joinToString("|")
        assertFalse("reason must not echo the code", serialized.contains(CODE))
        assertFalse("reason must not contain a hex blob", serialized.length > 200)
    }

    private fun verifiedController(
        device: FakeDevice,
        transport: AdbPairingTransport = FixedTransport(device)
    ) = AdbPairingController(
        transport = transport,
        assumptions = AdbPairingWireAssumptions.ALL_VERIFIED
    )

    private fun verifiedController(transport: AdbPairingTransport) = AdbPairingController(
        transport = transport,
        assumptions = AdbPairingWireAssumptions.ALL_VERIFIED
    )

    private fun assertFailure(result: AdbPairingResult?): AdbPairingResult.Failure {
        assertTrue("expected a failure, got $result", result is AdbPairingResult.Failure)
        return result as AdbPairingResult.Failure
    }

    private class FixedTransport(
        private val device: FakeDevice,
        private val onConnect: () -> Unit = {}
    ) : AdbPairingTransport {
        override fun connect(host: String, port: Int, connectTimeoutMs: Long): AdbPairingChannel {
            assertEquals(AdbPairingController.CONNECT_TIMEOUT_MS, connectTimeoutMs)
            onConnect()
            return device
        }
    }

    /**
     * A device (Bob) that runs the real SPAKE2 + AES-GCM steps.
     *
     * `onClientShare` fires after the host's SPAKE2 packet arrives (used by the cancellation test) and
     * `onConnect` fires when the transport is asked to connect (used by the concurrency test).
     */
    private class FakeDevice(
        code: String,
        private val exporter: ByteArray,
        private val mode: Mode = Mode.HAPPY,
        private val onClientShare: () -> Unit = {}
    ) : AdbPairingChannel {

        enum class Mode { HAPPY, BAD_SHARE, BAD_REPLY }

        private val password = code.toByteArray(Charsets.US_ASCII) + exporter
        private val bob = Spake2Curve25519.startForAdb(Spake2Curve25519Role.BOB, password)
        private var clientShare: ByteArray? = null
        private var cipher: AdbPairingCipher? = null
        private var exchangedShare = false
        private var repliedPeerInfo = false

        var decryptedClientPeerInfo: ByteArray? = null
            private set
        var closed: Boolean = false
            private set

        override val exporterMaterial: ByteArray get() = exporter

        override fun writePacket(packet: AdbPairingPacket) {
            when (packet.header.type) {
                AdbPairingPacketType.SPAKE2_MSG -> {
                    clientShare = packet.payload
                    onClientShare()
                }
                AdbPairingPacketType.PEER_INFO -> {
                    val active = cipher ?: error("peer info before key exchange")
                    decryptedClientPeerInfo = active.decrypt(packet.payload)
                    repliedPeerInfo = true
                }
            }
        }

        override fun readPacket(): AdbPairingPacket {
            if (!exchangedShare) {
                val share = requireNotNull(clientShare) { "host has not sent its share" }
                val keyMaterial = requireNotNull(bob.keyMaterial(share)) { "bad host share" }
                val (key, nonce) = AdbPairingKeySchedule.UnverifiedDefault.derive(keyMaterial)
                cipher = AdbPairingCipher(key, nonce)
                exchangedShare = true
                val reply = if (mode == Mode.BAD_SHARE) ByteArray(32) { 0xFF.toByte() } else bob.share
                return AdbPairingPacket.of(AdbPairingPacketType.SPAKE2_MSG, reply)
            }
            require(repliedPeerInfo) { "host has not sent peer info" }
            val active = requireNotNull(cipher)
            val ourPeerInfo = AdbPeerInfo(
                type = AdbPeerInfo.TYPE_ADB,
                publicKey = DEVICE_PUBLIC_KEY,
                systemIdentity = "fake-adbd"
            ).toProtobufBytes()
            val encrypted = if (mode == Mode.BAD_REPLY) {
                ByteArray(ourPeerInfo.size + AdbPairingCipher.TAG_BYTES) { 0x7F.toByte() }
            } else {
                active.encrypt(ourPeerInfo)
            }
            return AdbPairingPacket.of(AdbPairingPacketType.PEER_INFO, encrypted)
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val CODE = "246813"

        val EXPORTER: ByteArray = ByteArray(AdbPairingController.EXPORTER_BYTES) { (it * 7).toByte() }

        val PUBLIC_KEY: ByteArray = ByteArray(64) { (it + 1).toByte() }

        val DEVICE_PUBLIC_KEY: ByteArray = ByteArray(64) { (it + 101).toByte() }

        fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
    }
}
