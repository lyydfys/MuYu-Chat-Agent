package com.muyuchat.feature.dsh.workspace.device

import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The console-facing pairing gate.
 *
 * Everything here is pure JVM: the TLS transport is replaced by an in-memory device that runs the real
 * SPAKE2 + AES-GCM exchange, so the only thing under test is the gate, the validation, the endpoint
 * recording and the ordering guarantee that `connect()` is only reachable after a proven pairing.
 */
class DshAdbPairingPortTest {

    @Test
    fun `switch off rejects without touching the transport`() {
        var transportCreated = 0
        val port = DshAdbPairingPort(
            transportFactory = {
                transportCreated++
                error("must not build a transport while the switch is off")
            }
        )

        val result = port.pair("10.0.0.2", 43_521, CODE)

        assertFalse(result.succeeded)
        assertEquals("unsupported", result.reasonCode)
        assertEquals(listOf(DshAdbPairingPort.NOT_ENABLED_ASSUMPTION), result.unverifiedAssumptions)
        assertEquals(0, transportCreated)
    }

    @Test
    fun `blank host and bad ports are invalid input`() {
        val port = verifiedPort(FakeDevice(CODE))
        port.setExperimentalEnabled(true)

        assertEquals("invalid_input", port.pair("   ", 43_521, CODE).reasonCode)
        assertEquals("invalid_input", port.pair("10.0.0.2", 0, CODE).reasonCode)
        assertEquals("invalid_input", port.pair("10.0.0.2", 65_536, CODE).reasonCode)
        assertEquals("invalid_input", port.pair("10.0.0.2", -1, CODE).reasonCode)
    }

    @Test
    fun `pairing code must be six digits`() {
        val port = verifiedPort(FakeDevice(CODE))
        port.setExperimentalEnabled(true)

        assertEquals("invalid_input", port.pair("10.0.0.2", 43_521, "12345").reasonCode)
        assertEquals("invalid_input", port.pair("10.0.0.2", 43_521, "1234567").reasonCode)
        assertEquals("invalid_input", port.pair("10.0.0.2", 43_521, "12a456").reasonCode)
        assertEquals("invalid_input", port.pair("10.0.0.2", 43_521, "").reasonCode)
    }

    @Test
    fun `success records the endpoint and reports the canonical host port`() {
        val saved = mutableListOf<String>()
        val device = FakeDevice(CODE)
        val port = verifiedPort(device, saveEndpoint = saved::add)
        port.setExperimentalEnabled(true)

        val result = port.pair(" 10.0.0.2 ", 43_521, " $CODE ")

        assertTrue(result.succeeded)
        assertEquals("配对成功，已可用 ADB 通道（无需 root）", result.message)
        assertEquals("10.0.0.2:43521", result.endpoint)
        assertEquals(listOf("10.0.0.2:43521"), saved)
        assertTrue(result.unverifiedAssumptions.isEmpty())
        assertTrue(device.closed)
    }

    @Test
    fun `failure leaves the endpoint memory untouched`() {
        val saved = mutableListOf<String>()
        val port = verifiedPort(FakeDevice(CODE, FakeDevice.Mode.BAD_REPLY), saveEndpoint = saved::add)
        port.setExperimentalEnabled(true)

        val result = port.pair("10.0.0.2", 43_521, CODE)

        assertFalse(result.succeeded)
        assertEquals("peer_info_rejected", result.reasonCode)
        assertTrue(saved.isEmpty())
        assertNull(result.endpoint)
    }

    @Test
    fun `an unverified protocol surfaces the assumption names and nothing else`() {
        val port = DshAdbPairingPort(transportFactory = { FakeDevice(CODE) })
        port.setExperimentalEnabled(true)

        val result = port.pair("10.0.0.2", 43_521, CODE)

        assertFalse(result.succeeded)
        assertEquals("unsupported", result.reasonCode)
        assertEquals(
            listOf("peer_info_layout", "aes_gcm_derivation", "packet_type_values", "tls_exporter_label"),
            result.unverifiedAssumptions
        )
        // The detail is assumption names only: no code, no hex.
        assertTrue(result.unverifiedAssumptions.none { it.contains(CODE) })
        assertTrue(result.unverifiedAssumptions.all { it.length < 40 })
        assertEquals(
            "exporter label / GCM 派生 / PeerInfo 布局 / packet type",
            DshAdbPairingResult.formatAssumptions(
                listOf("tls_exporter_label", "aes_gcm_derivation", "peer_info_layout", "packet_type_values")
            )
        )
    }

    @Test
    fun `a second attempt while one is running reports concurrent pairing`() {
        lateinit var port: DshAdbPairingPort
        var nested: DshAdbPairingResult? = null
        val device = FakeDevice(CODE, onConnect = { nested = port.pair("10.0.0.2", 43_521, CODE) })
        port = verifiedPort(device)
        port.setExperimentalEnabled(true)

        val first = port.pair("10.0.0.2", 43_521, CODE)

        assertTrue(first.succeeded)
        assertEquals("concurrent_pairing", requireNotNull(nested).reasonCode)
    }

    @Test
    fun `every stable reason code maps to its documented string`() {
        val expected = mapOf(
            AdbPairingFailure.INVALID_INPUT to "invalid_input",
            AdbPairingFailure.UNSUPPORTED to "unsupported",
            AdbPairingFailure.TLS_FAILED to "tls_failed",
            AdbPairingFailure.SPAKE2_FAILED to "spake2_failed",
            AdbPairingFailure.PEER_INFO_REJECTED to "peer_info_rejected",
            AdbPairingFailure.TIMEOUT to "timeout",
            AdbPairingFailure.CANCELLED to "cancelled",
            AdbPairingFailure.CONCURRENT_PAIRING to "concurrent_pairing",
            AdbPairingFailure.TRANSPORT_FAILED to "transport_failed"
        )
        for ((reason, code) in expected) {
            assertEquals(code, DshAdbPairingResult.failure(reason, listOf("x")).reasonCode)
        }
    }

    @Test
    fun `connect is only reachable after a successful pairing`() = runBlocking {
        val device = FakeDevice(CODE)
        val pairingPort = verifiedPort(device)
        pairingPort.setExperimentalEnabled(true)
        val transport = FakeTransport()
        val channel = DshAdbChannel(transport = transport, pairingPort = pairingPort)
        val endpoint = DshAdbEndpoint("10.0.0.2", 43_521)

        assertFalse(channel.isPairedEndpoint(endpoint))
        assertNull("unpaired endpoint must not reach the transport", channel.connectAfterPairing(endpoint))
        assertEquals(0, transport.connectCalls)

        channelledPair(channel)

        assertTrue(channel.isPairedEndpoint(endpoint))
        val probe = channel.connectAfterPairing(endpoint)
        assertEquals(1, transport.connectCalls)
        assertEquals(DshDeviceChannelStatus.READY, probe?.status)
    }

    @Test
    fun `a failed pairing never authorises connect`() = runBlocking {
        val pairingPort = verifiedPort(FakeDevice(CODE, FakeDevice.Mode.BAD_REPLY))
        pairingPort.setExperimentalEnabled(true)
        val transport = FakeTransport()
        val channel = DshAdbChannel(transport = transport, pairingPort = pairingPort)
        val endpoint = DshAdbEndpoint("10.0.0.2", 43_521)

        val result = channel.pair(endpoint.host, endpoint.port, CODE)

        assertFalse(result.succeeded)
        assertFalse(channel.isPairedEndpoint(endpoint))
        assertNull(channel.connectAfterPairing(endpoint))
        assertEquals(0, transport.connectCalls)
    }

    @Test
    fun `a channel without a pairing port reports unsupported`() = runBlocking {
        val channel = DshAdbChannel(transport = FakeTransport())
        val result = channel.pair("10.0.0.2", 43_521, CODE)
        assertFalse(result.succeeded)
        assertEquals("unsupported", result.reasonCode)
    }

    private suspend fun channelledPair(channel: DshAdbChannel) {
        val result = channel.pair("10.0.0.2", 43_521, CODE)
        assertTrue("pairing should succeed: $result", result.succeeded)
    }

    private fun verifiedPort(
        device: FakeDevice,
        saveEndpoint: (String) -> Unit = {}
    ) = DshAdbPairingPort(
        saveEndpoint = saveEndpoint,
        assumptions = AdbPairingWireAssumptions.ALL_VERIFIED,
        transportFactory = { device }
    )

    /** In-memory ADB transport: connect always succeeds, `shell` answers the probe command. */
    private class FakeTransport : AdbTransport {
        var connectCalls: Int = 0
            private set
        private var current = DshAdbTransportState.NOT_CONFIGURED

        override fun state(): DshAdbTransportState = current

        override suspend fun connect(endpoint: DshAdbEndpoint): Boolean {
            connectCalls++
            current = DshAdbTransportState.CONNECTED
            return true
        }

        override suspend fun shell(command: String): DshAdbShellResult =
            DshAdbShellResult(exitCode = 0, stdout = "sdk=34", stderr = "")

        override fun close() {
            current = DshAdbTransportState.NOT_CONFIGURED
        }
    }

    /**
     * A device (Bob) that runs the real SPAKE2 + AES-GCM exchange over an in-memory channel.
     *
     * `onConnect` fires when the controller asks the transport to connect (used by the concurrency
     * test to re-enter `pair` from inside the first attempt).
     */
    private class FakeDevice(
        private val code: String,
        private val mode: Mode = Mode.HAPPY,
        private val onConnect: (() -> Unit)? = null
    ) : AdbPairingTransport, AdbPairingChannel {

        enum class Mode { HAPPY, BAD_REPLY }

        private val exporter = ByteArray(AdbPairingController.EXPORTER_BYTES) { (it * 5).toByte() }
        private val bob = Spake2Curve25519.startForAdb(Spake2Curve25519Role.BOB, code.toByteArray() + exporter)
        private var clientShare: ByteArray? = null
        private var cipher: AdbPairingCipher? = null
        private var exchangedShare = false
        private var replied = false

        var closed: Boolean = false
            private set

        override fun connect(host: String, port: Int, connectTimeoutMs: Long): AdbPairingChannel {
            onConnect?.invoke()
            return this
        }

        override val exporterMaterial: ByteArray get() = exporter

        override fun writePacket(packet: AdbPairingPacket) {
            when (packet.header.type) {
                AdbPairingPacketType.SPAKE2_MSG -> clientShare = packet.payload
                AdbPairingPacketType.PEER_INFO -> {
                    cipher?.decrypt(packet.payload)
                    replied = true
                }
            }
        }

        override fun readPacket(): AdbPairingPacket {
            if (!exchangedShare) {
                val share = requireNotNull(clientShare)
                val keyMaterial = requireNotNull(bob.keyMaterial(share))
                val (key, nonce) = AdbPairingKeySchedule.UnverifiedDefault.derive(keyMaterial)
                cipher = AdbPairingCipher(key, nonce)
                exchangedShare = true
                return AdbPairingPacket.of(AdbPairingPacketType.SPAKE2_MSG, bob.share)
            }
            require(replied)
            val peerInfo = AdbPeerInfo(
                type = AdbPeerInfo.TYPE_ADB,
                publicKey = byteArrayOf(1, 2, 3),
                systemIdentity = "fake-adbd"
            ).toProtobufBytes()
            val payload = if (mode == Mode.BAD_REPLY) {
                ByteArray(peerInfo.size + AdbPairingCipher.TAG_BYTES) { 0x5A }
            } else {
                requireNotNull(cipher).encrypt(peerInfo)
            }
            return AdbPairingPacket.of(AdbPairingPacketType.PEER_INFO, payload)
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val CODE = "135790"
    }
}
