package com.muyuchat.feature.dsh.workspace.ui

import com.muyuchat.feature.dsh.workspace.device.AdbPairingChannel
import com.muyuchat.feature.dsh.workspace.device.AdbPairingCipher
import com.muyuchat.feature.dsh.workspace.device.AdbPairingController
import com.muyuchat.feature.dsh.workspace.device.AdbPairingKeySchedule
import com.muyuchat.feature.dsh.workspace.device.AdbPairingPacket
import com.muyuchat.feature.dsh.workspace.device.AdbPairingPacketType
import com.muyuchat.feature.dsh.workspace.device.AdbPairingTransport
import com.muyuchat.feature.dsh.workspace.device.AdbPairingWireAssumptions
import com.muyuchat.feature.dsh.workspace.device.AdbPeerInfo
import com.muyuchat.feature.dsh.workspace.device.AdbTransport
import com.muyuchat.feature.dsh.workspace.device.DshAdbChannel
import com.muyuchat.feature.dsh.workspace.device.DshAdbEndpoint
import com.muyuchat.feature.dsh.workspace.device.DshAdbPairingPort
import com.muyuchat.feature.dsh.workspace.device.DshAdbShellResult
import com.muyuchat.feature.dsh.workspace.device.DshAdbTransportState
import com.muyuchat.feature.dsh.workspace.device.DshRootChannel
import com.muyuchat.feature.dsh.workspace.device.Spake2Curve25519
import com.muyuchat.feature.dsh.workspace.device.Spake2Curve25519Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Page-state behaviour of the experimental wireless-pairing form.
 *
 * The ADB transport and the pairing TLS channel are both fakes: the pairing fake runs the real
 * SPAKE2 + AES-GCM exchange over memory, so what is being tested is the view model's gating,
 * single-flight, endpoint write-back and failure text — not a socket.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DshAdbPairingConsoleTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `pairing is off by default and the channel refuses without touching the transport`() = runTest(dispatcher) {
        val device = FakeDevice(CODE)
        val viewModel = viewModel(device)

        assertFalse(viewModel.state.value.pairingEnabled)
        assertFalse(viewModel.state.value.pairingExpanded)

        // Even a programmatic attempt without the switch must not reach the network.
        viewModel.updatePairingEndpoint("10.0.0.2:43521")
        viewModel.updatePairingCode(CODE)
        viewModel.startPairing()
        val state = awaitPairing(viewModel)
        assertTrue(
            "expected an unsupported failure, got ${state.pairingFailure}",
            state.pairingFailure?.contains("unsupported") == true
        )
        assertEquals(0, device.attempts)
        assertNull(state.pairingResult)
    }

    @Test
    fun `an invalid endpoint is rejected locally`() = runTest(dispatcher) {
        val device = FakeDevice(CODE)
        val viewModel = viewModel(device)
        viewModel.setPairingEnabled(true)
        viewModel.updatePairingEndpoint("not an endpoint")
        viewModel.updatePairingCode(CODE)

        viewModel.startPairing()
        advanceUntilIdle()

        assertEquals(
            "配对端点格式应为 host:port，例如 10.0.0.2:43521",
            viewModel.state.value.pairingFailure
        )
        assertEquals(0, device.attempts)
    }

    @Test
    fun `the code field keeps six digits and a short code is rejected`() = runTest(dispatcher) {
        val device = FakeDevice(CODE)
        val viewModel = viewModel(device)
        viewModel.setPairingEnabled(true)

        viewModel.updatePairingCode("12ab34567")
        assertEquals("123456", viewModel.state.value.pairingCode)

        viewModel.updatePairingEndpoint("10.0.0.2:43521")
        viewModel.updatePairingCode("12345")
        viewModel.startPairing()
        advanceUntilIdle()

        assertEquals("配对码是设备上显示的 6 位数字", viewModel.state.value.pairingFailure)
        assertEquals(0, device.attempts)
    }

    @Test
    fun `success writes the proven endpoint back and records it`() = runTest(dispatcher) {
        val device = FakeDevice(CODE)
        val channel = channel(device)
        val viewModel = viewModelFor(channel)
        viewModel.setPairingEnabled(true)
        viewModel.updatePairingEndpoint(" 10.0.0.2:43521 ")
        viewModel.updatePairingCode(CODE)

        viewModel.startPairing()
        val state = awaitPairing(viewModel)
        assertFalse(state.pairingBusy)
        assertEquals("配对成功，已可用 ADB 通道（无需 root）", state.pairingResult)
        assertNull(state.pairingFailure)
        assertEquals("10.0.0.2:43521", state.adbEndpoint)
        assertTrue(channel.isPairedEndpoint(DshAdbEndpoint("10.0.0.2", 43_521)))
        assertEquals(1, device.attempts)
    }

    @Test
    fun `failure keeps the endpoint unchanged and reports the reason code`() = runTest(dispatcher) {
        val device = FakeDevice(CODE, FakeDevice.Mode.BAD_REPLY)
        val viewModel = viewModel(device)
        val originalEndpoint = viewModel.state.value.adbEndpoint
        viewModel.setPairingEnabled(true)
        viewModel.updatePairingEndpoint("10.0.0.2:43521")
        viewModel.updatePairingCode(CODE)

        viewModel.startPairing()
        val state = awaitPairing(viewModel)
        assertNull(state.pairingResult)
        val failure = assertNotNull(state.pairingFailure).let { state.pairingFailure!! }
        assertTrue("expected a peer_info_rejected line, got $failure", failure.contains("peer_info_rejected"))
        assertTrue("failure must list the unverified assumptions", failure.contains("未验证假设"))
        assertTrue("failure must not echo the code", !failure.contains(CODE))
        assertEquals("the endpoint must not be moved by a failure", originalEndpoint, state.adbEndpoint)
    }

    @Test
    fun `double tap sends only one attempt`() = runTest(dispatcher) {
        val device = FakeDevice(CODE)
        val viewModel = viewModel(device)
        viewModel.setPairingEnabled(true)
        viewModel.updatePairingEndpoint("10.0.0.2:43521")
        viewModel.updatePairingCode(CODE)

        viewModel.startPairing()
        viewModel.startPairing()
        awaitPairing(viewModel)

        assertEquals(1, device.attempts)
        assertNotNull(viewModel.state.value.pairingResult)
    }

    @Test
    fun `cancelling stops the attempt before it reaches the transport`() = runTest(dispatcher) {
        val device = FakeDevice(CODE)
        val viewModel = viewModel(device)
        viewModel.setPairingEnabled(true)
        viewModel.updatePairingEndpoint("10.0.0.2:43521")
        viewModel.updatePairingCode(CODE)

        viewModel.startPairing()
        viewModel.cancelPairing()
        advanceUntilIdle()

        assertEquals(0, device.attempts)
        assertFalse(viewModel.state.value.pairingBusy)
        assertNull(viewModel.state.value.pairingResult)
    }

    @Test
    fun `turning the switch off clears and collapses the form`() = runTest(dispatcher) {
        val viewModel = viewModel(FakeDevice(CODE))
        viewModel.setPairingEnabled(true)
        viewModel.togglePairingSection()
        assertTrue(viewModel.state.value.pairingExpanded)

        viewModel.setPairingEnabled(false)

        val state = viewModel.state.value
        assertFalse(state.pairingEnabled)
        assertFalse(state.pairingExpanded)
        assertNull(state.pairingResult)
        assertNull(state.pairingFailure)
    }

'    /**
     * Waits for the attempt to finish.
     *
     * `DshAdbChannel.pair` hops to `Dispatchers.IO`, so a test-dispatcher `advanceUntilIdle` alone can
     * return before the attempt completed. Polling on the real clock keeps this deterministic.
     */
    private suspend fun awaitPairing(viewModel: DshDeviceAccessViewModel): DshDeviceAccessUiState {
        repeat(500) {
            val state = viewModel.state.value
            if (state.pairingResult != null || state.pairingFailure != null || !state.pairingBusy) {
                if (state.pairingResult != null || state.pairingFailure != null) return state
            }
            withContext(Dispatchers.Default) { delay(10) }
        }
        error("pairing did not finish: " + viewModel.state.value.pairingFailure)
    }

    private fun channel(device: FakeDevice): DshAdbChannel = DshAdbChannel('
        transport = FakeAdbTransport(),
        pairingPort = DshAdbPairingPort(
            assumptions = AdbPairingWireAssumptions.EXPERIMENTAL,
            transportFactory = { device }
        )
    )

    private fun viewModel(device: FakeDevice): DshDeviceAccessViewModel = viewModelFor(channel(device))

    private fun viewModelFor(channel: DshAdbChannel): DshDeviceAccessViewModel =
        DshDeviceAccessViewModel(
            root = DshRootChannel(),
            adb = channel
        )

    /** In-memory ADB transport; only used so the channel has a real (non-null) transport. */
    private class FakeAdbTransport : AdbTransport {
        private var current = DshAdbTransportState.NOT_CONFIGURED
        override fun state(): DshAdbTransportState = current
        override suspend fun connect(endpoint: DshAdbEndpoint): Boolean {
            current = DshAdbTransportState.CONNECTED
            return true
        }

        override suspend fun shell(command: String): DshAdbShellResult =
            DshAdbShellResult(exitCode = 0, stdout = "sdk=34", stderr = "")

        override fun close() {
            current = DshAdbTransportState.NOT_CONFIGURED
        }
    }

    /** Device (Bob) side of the pairing exchange, in memory. */
    private class FakeDevice(
        private val code: String,
        private val mode: Mode = Mode.HAPPY
    ) : AdbPairingTransport, AdbPairingChannel {

        enum class Mode { HAPPY, BAD_REPLY }

        private val exporter = ByteArray(AdbPairingController.EXPORTER_BYTES) { (it * 5).toByte() }
        private val bob = Spake2Curve25519.startForAdb(Spake2Curve25519Role.BOB, code.toByteArray() + exporter)
        private var clientShare: ByteArray? = null
        private var cipher: AdbPairingCipher? = null
        private var exchangedShare = false
        private var replied = false

        var attempts: Int = 0
            private set

        override fun connect(host: String, port: Int, connectTimeoutMs: Long): AdbPairingChannel {
            attempts++
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

        override fun close() = Unit
    }

    private companion object {
        const val CODE = "135790"
    }
}
