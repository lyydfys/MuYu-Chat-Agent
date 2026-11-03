package com.muyuchat.feature.dsh.workspace.ui

import androidx.lifecycle.viewModelScope
import com.muyuchat.feature.dsh.workspace.DshConsoleTone
import com.muyuchat.feature.dsh.workspace.device.AdbTransport
import com.muyuchat.feature.dsh.workspace.device.DshAdbAutoConnectPolicy
import com.muyuchat.feature.dsh.workspace.device.DshAdbChannel
import com.muyuchat.feature.dsh.workspace.device.DshAdbEndpoint
import com.muyuchat.feature.dsh.workspace.device.DshAdbEndpointStore
import com.muyuchat.feature.dsh.workspace.device.DshAdbProvisioner
import com.muyuchat.feature.dsh.workspace.device.DshAdbShellResult
import com.muyuchat.feature.dsh.workspace.device.DshAdbTransportState
import com.muyuchat.feature.dsh.workspace.device.DshDeviceChannelKind
import com.muyuchat.feature.dsh.workspace.device.DshDeviceChannelStatus
import com.muyuchat.feature.dsh.workspace.device.DshDeviceCommandFailure
import com.muyuchat.feature.dsh.workspace.device.DshDeviceCommandResult
import com.muyuchat.feature.dsh.workspace.device.DshProcessLauncher
import com.muyuchat.feature.dsh.workspace.device.DshRootChannel
import com.muyuchat.feature.dsh.workspace.device.FakeDshProcess
import com.muyuchat.feature.dsh.workspace.device.RecordingLauncher
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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
 * Projection and page-state behaviour of the console device block.
 *
 * The projection is pure, so labels/tones/summaries are asserted directly. The view-model cases run
 * on a test scheduler with a fake launcher: no `su` is spawned and no real IO happens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DshDeviceAccessViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val viewModels = mutableListOf<DshDeviceAccessViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    // region pure projection

    @Test
    fun `status labels are pinned`() {
        assertEquals("不可用", dshDeviceStatusLabel(DshDeviceChannelStatus.UNAVAILABLE))
        assertEquals("待授权", dshDeviceStatusLabel(DshDeviceChannelStatus.NEEDS_GRANT))
        assertEquals("待配对", dshDeviceStatusLabel(DshDeviceChannelStatus.NEEDS_PAIRING))
        assertEquals("已就绪", dshDeviceStatusLabel(DshDeviceChannelStatus.READY))
        assertEquals("错误", dshDeviceStatusLabel(DshDeviceChannelStatus.ERROR))
    }

    @Test
    fun `only ready is positive and only error is negative`() {
        assertEquals(DshConsoleTone.NEUTRAL, dshDeviceStatusTone(DshDeviceChannelStatus.UNAVAILABLE))
        assertEquals(DshConsoleTone.WARNING, dshDeviceStatusTone(DshDeviceChannelStatus.NEEDS_GRANT))
        assertEquals(DshConsoleTone.WARNING, dshDeviceStatusTone(DshDeviceChannelStatus.NEEDS_PAIRING))
        assertEquals(DshConsoleTone.POSITIVE, dshDeviceStatusTone(DshDeviceChannelStatus.READY))
        assertEquals(DshConsoleTone.NEGATIVE, dshDeviceStatusTone(DshDeviceChannelStatus.ERROR))
    }

    @Test
    fun `the adb endpoint text parses with a default port`() {
        assertEquals(DshAdbEndpoint("127.0.0.1", 5555), dshAdbEndpointFrom("127.0.0.1"))
        assertEquals(DshAdbEndpoint("127.0.0.1", 5555), dshAdbEndpointFrom(" 127.0.0.1:5555 "))
        assertEquals(DshAdbEndpoint("192.168.1.7", 5037), dshAdbEndpointFrom("192.168.1.7:5037"))
        assertEquals(DSH_ADB_DEFAULT_ENDPOINT, "127.0.0.1:5555")
        assertNull(dshAdbEndpointFrom(""))
        assertNull(dshAdbEndpointFrom("   "))
        assertNull(dshAdbEndpointFrom(":5555"))
        assertNull(dshAdbEndpointFrom("127.0.0.1:0"))
        assertNull(dshAdbEndpointFrom("127.0.0.1:70000"))
        assertNull(dshAdbEndpointFrom("127.0.0.1:abc"))
        assertNull(dshAdbEndpointFrom("127.0 .0.1:5555"))
    }

    @Test
    fun `an unprobed channel is neutral and labelled unprobed`() {
        val view = dshDeviceChannelView(
            kind = DshDeviceChannelKind.ROOT,
            status = DshDeviceChannelStatus.UNAVAILABLE,
            result = null,
            probed = false
        )

        assertFalse(view.probed)
        assertEquals(DSH_DEVICE_UNPROBED_LABEL, view.statusLabel)
        assertEquals(DshConsoleTone.NEUTRAL, view.tone)
        assertEquals("尚未探测", view.resultSummary)
    }

    @Test
    fun `a refused result shows the reason code and never the command`() {
        val refused = DshDeviceCommandResult.policyBlocked(
            policyDetail = "denied_command: destructive or privileged command"
        )

        val summary = dshDeviceResultSummary(refused)

        assertEquals("已拒绝 · policy_blocked", summary)
        assertFalse(summary.contains("rm"))
        assertFalse(summary.contains("-rf"))
    }

    @Test
    fun `a normal result shows exit code and byte counts`() {
        val result = DshDeviceCommandResult(exitCode = 0, stdout = "abc", stderr = "")

        assertEquals("exit=0 · stdout 3 B · stderr 0 B", dshDeviceResultSummary(result))
    }

    @Test
    fun `grant hints name the real grant path and the missing pairing`() {
        val root = dshDeviceGrantHint(DshDeviceChannelKind.ROOT)
        val adb = dshDeviceGrantHint(DshDeviceChannelKind.ADB)

        assertTrue(root.contains("Magisk"))
        assertTrue(root.contains("KernelSU"))
        assertTrue(adb.contains("配对"))
        assertTrue(adb.contains("未接入"))
    }

    // endregion

    // region page state

    @Test
    fun `a fresh state reports both channels as unprobed`() {
        val state = DshDeviceAccessUiState()

        assertFalse(state.root.probed)
        assertFalse(state.adb.probed)
        assertEquals(DSH_DEVICE_UNPROBED_LABEL, state.root.statusLabel)
        assertEquals(DSH_DEVICE_UNPROBED_LABEL, state.adb.statusLabel)
        assertNull(state.busy)
    }

    @Test
    fun `entering the block probes adb but never runs su`() = runTest(dispatcher) {
        val launcher = RecordingLauncher { _, _ -> FakeDshProcess(stdoutText = "uid=0(root)\n") }
        val viewModel = viewModel(launcher)

        viewModel.start()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.adb.probed)
        assertFalse(viewModel.state.value.root.probed)
        assertTrue("su must not run just by opening the console", launcher.invocations.isEmpty())
        assertEquals("已拒绝 · not_integrated", viewModel.state.value.adb.resultSummary)
        assertNull(viewModel.state.value.busy)
    }

    @Test
    fun `start twice keeps a single adb probe and never touches root`() = runTest(dispatcher) {
        val launcher = RecordingLauncher { _, _ -> FakeDshProcess() }
        val viewModel = viewModel(launcher)

        viewModel.start()
        viewModel.start()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.adb.probed)
        assertFalse(viewModel.state.value.root.probed)
        assertEquals(0, launcher.invocations.size)
    }

    @Test
    fun `probing root maps uid zero to ready`() = runTest(dispatcher) {
        val launcher = RecordingLauncher { _, _ ->
            FakeDshProcess(stdoutText = "uid=0(root) gid=0(root)\n")
        }
        val viewModel = viewModel(launcher)

        viewModel.probeRoot()
        advanceUntilIdle()

        val root = viewModel.state.value.root
        assertTrue(root.probed)
        assertEquals(DshDeviceChannelStatus.READY, root.status)
        assertEquals("已就绪", root.statusLabel)
        assertTrue(root.resultSummary.startsWith("exit=0"))
        assertNull(viewModel.state.value.busy)
        assertEquals(listOf("su", "-c", "id"), launcher.invocations.single())
    }

    @Test
    fun `probing root without su stays unavailable and does not crash`() = runTest(dispatcher) {
        val launcher = RecordingLauncher { _, _ -> throw IOException("su: not found") }
        val viewModel = viewModel(launcher)

        viewModel.probeRoot()
        advanceUntilIdle()

        val root = viewModel.state.value.root
        assertTrue(root.probed)
        assertEquals(DshDeviceChannelStatus.UNAVAILABLE, root.status)
        assertEquals("不可用", root.statusLabel)
        assertNull(viewModel.state.value.busy)
    }

    @Test
    fun `a second root tap while probing is ignored`() = runTest(dispatcher) {
        val launcher = RecordingLauncher { _, _ -> FakeDshProcess(stdoutText = "uid=0(root)\n") }
        val viewModel = viewModel(launcher)

        viewModel.probeRoot()
        viewModel.probeRoot()
        advanceUntilIdle()

        assertEquals(1, launcher.invocations.size)
    }

    @Test
    fun `a malformed endpoint shows an inline error and opens no transport`() = runTest(dispatcher) {
        val transport = FakeAdbTransport()
        val viewModel = viewModel(transport = transport)

        viewModel.updateAdbEndpoint("nope:port")
        viewModel.connectAdb()
        advanceUntilIdle()

        assertNotNull(viewModel.state.value.adbEndpointError)
        assertTrue(transport.endpoints.isEmpty())
        assertFalse(viewModel.state.value.adb.probed)
        assertNull(viewModel.state.value.busy)
    }

    @Test
    fun `connecting adb uses the typed endpoint and projects the status`() = runTest(dispatcher) {
        val transport = FakeAdbTransport(
            connectState = DshAdbTransportState.CONNECTED,
            stdout = "sdk_gphone64_arm64\n"
        )
        val viewModel = viewModel(transport = transport)

        viewModel.updateAdbEndpoint("127.0.0.1:5555")
        viewModel.connectAdb()
        advanceUntilIdle()

        assertEquals(listOf(DshAdbEndpoint("127.0.0.1", 5555)), transport.endpoints)
        val adb = viewModel.state.value.adb
        assertTrue(adb.probed)
        assertEquals(DshDeviceChannelStatus.READY, adb.status)
        assertEquals("已就绪", adb.statusLabel)
        assertTrue(adb.resultSummary.startsWith("exit=0"))
        assertNull(viewModel.state.value.adbEndpointError)
        assertNull(viewModel.state.value.busy)
    }

    @Test
    fun `a saved endpoint is restored when the console opens`() = runTest(dispatcher) {
        val store = MemoryEndpointStore("192.168.1.7:5037")
        val viewModel = viewModel(endpointStore = store)

        viewModel.start()
        advanceUntilIdle()

        assertEquals("192.168.1.7:5037", viewModel.state.value.adbEndpoint)
        assertTrue(viewModel.state.value.adb.probed)
    }

    @Test
    fun `auto reconnect walks the backoff and gives up after five retries`() = runTest(dispatcher) {
        val transport = FlakyTransport()
        val viewModel = viewModel(transport = transport)

        viewModel.setAutoReconnect(true)
        advanceUntilIdle()

        assertEquals("one initial attempt plus five retries", 6, transport.attempts)
        assertEquals(
            DshAdbAutoConnectPolicy.MAX_ATTEMPTS,
            viewModel.state.value.autoConnect.attempts
        )
        assertNull(viewModel.state.value.retryNote)
        assertEquals(DshDeviceChannelStatus.ERROR, viewModel.state.value.adb.status)
        assertNull(viewModel.state.value.busy)
    }

    @Test
    fun `a manual disconnect blocks further automatic retries`() = runTest(dispatcher) {
        val transport = FlakyTransport()
        val viewModel = viewModel(transport = transport)
        viewModel.setAutoReconnect(true)
        advanceUntilIdle()
        val attemptsBeforeDisconnect = transport.attempts

        viewModel.disconnectAdb()
        assertTrue(viewModel.state.value.autoConnect.manualDisconnect)
        assertNull(viewModel.state.value.retryNote)

        viewModel.connectAdb()
        advanceUntilIdle()

        assertEquals(
            "a manual disconnect allows exactly one attempt and no retry",
            attemptsBeforeDisconnect + 1,
            transport.attempts
        )
        assertTrue(viewModel.state.value.autoConnect.manualDisconnect)
    }

    @Test
    fun `re-enabling auto reconnect clears the manual disconnect`() = runTest(dispatcher) {
        val transport = FlakyTransport()
        val viewModel = viewModel(transport = transport)
        viewModel.setAutoReconnect(true)
        advanceUntilIdle()
        viewModel.disconnectAdb()

        viewModel.setAutoReconnect(true)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.autoConnect.manualDisconnect)
        assertTrue(transport.attempts > 1)
    }

    @Test
    fun `a successful connect resets the retry budget and saves the endpoint`() = runTest(dispatcher) {
        val transport = FlakyTransport(succeedAfter = 2)
        val store = MemoryEndpointStore()
        val viewModel = viewModel(transport = transport, endpointStore = store)

        viewModel.setAutoReconnect(true)
        advanceUntilIdle()

        assertEquals(DshDeviceChannelStatus.READY, viewModel.state.value.adb.status)
        assertEquals(0, viewModel.state.value.autoConnect.attempts)
        assertEquals("127.0.0.1:5555", store.saved)
    }

    @Test
    fun `provisioning maps the run into step results`() = runTest(dispatcher) {
        val provisioner = DshAdbProvisioner(
            root = DshRootChannel(
                RecordingLauncher { _, _ -> FakeDshProcess() },
                probeTimeoutMs = 50L,
                io = dispatcher
            ),
            identity = { error("no identity in this test") },
            transport = { FlakyTransport() },
            portReady = { true },
            io = dispatcher
        )
        val viewModel = viewModel(provisioner = provisioner)

        viewModel.provisionAdb()
        advanceUntilIdle()

        val provisioning = viewModel.state.value.provisioning
        assertNotNull(provisioning)
        assertFalse(provisioning!!.running)
        assertEquals(1, provisioning.steps.size)
        assertEquals(DshDeviceCommandFailure.POLICY_BLOCKED, provisioning.failure)
        assertNull(viewModel.state.value.busy)
    }

    // endregion

    private fun viewModel(
        launcher: DshProcessLauncher = RecordingLauncher { _, _ -> FakeDshProcess() },
        transport: AdbTransport? = null,
        provisioner: DshAdbProvisioner? = null,
        endpointStore: DshAdbEndpointStore? = null
    ): DshDeviceAccessViewModel {
        val created = DshDeviceAccessViewModel(
            root = DshRootChannel(launcher, probeTimeoutMs = 50L, io = dispatcher),
            adb = if (transport == null) DshAdbChannel() else DshAdbChannel(transport),
            provisioner = provisioner,
            endpointStore = endpointStore
        )
        viewModels += created
        return created
    }
}

/** In-memory endpoint memory for the page-state tests. */
private class MemoryEndpointStore(private var stored: String? = null) : DshAdbEndpointStore {

    val saved: String?
        get() = stored

    override fun savedEndpoint(): String? = stored

    override fun saveEndpoint(endpoint: String) {
        stored = endpoint
    }

    override fun clearEndpoint() {
        stored = null
    }
}

/**
 * Transport double that fails until [succeedAfter] connect attempts, then reports CONNECTED.
 * No socket is opened.
 */
private class FlakyTransport(private val succeedAfter: Int = Int.MAX_VALUE) : AdbTransport {

    var attempts: Int = 0
        private set

    private var current: DshAdbTransportState = DshAdbTransportState.NOT_CONFIGURED

    override fun state(): DshAdbTransportState = current

    override suspend fun connect(endpoint: DshAdbEndpoint): Boolean {
        attempts += 1
        current = if (attempts >= succeedAfter) {
            DshAdbTransportState.CONNECTED
        } else {
            DshAdbTransportState.FAILED
        }
        return current == DshAdbTransportState.CONNECTED
    }

    override suspend fun shell(command: String): DshAdbShellResult =
        DshAdbShellResult(exitCode = 0, stdout = "sdk_gphone64_arm64\n", stderr = "")

    override fun close() {
        current = DshAdbTransportState.NOT_CONFIGURED
    }
}

/** Minimal transport double for the page-state tests; no socket is opened. */
private class FakeAdbTransport(
    private var current: DshAdbTransportState = DshAdbTransportState.NOT_CONFIGURED,
    private val connectState: DshAdbTransportState = DshAdbTransportState.CONNECTED,
    private val stdout: String = ""
) : AdbTransport {

    val endpoints: MutableList<DshAdbEndpoint> = mutableListOf()

    override fun state(): DshAdbTransportState = current

    override suspend fun connect(endpoint: DshAdbEndpoint): Boolean {
        endpoints += endpoint
        current = connectState
        return connectState == DshAdbTransportState.CONNECTED
    }

    override suspend fun shell(command: String): DshAdbShellResult =
        DshAdbShellResult(exitCode = 0, stdout = stdout, stderr = "")

    override fun close() {
        current = DshAdbTransportState.NOT_CONFIGURED
    }
}
