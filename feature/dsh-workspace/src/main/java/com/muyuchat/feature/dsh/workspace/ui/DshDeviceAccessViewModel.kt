package com.muyuchat.feature.dsh.workspace.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.muyuchat.feature.dsh.workspace.DshConsoleTone
import com.muyuchat.feature.dsh.workspace.device.DshAdbAutoConnectPolicy
import com.muyuchat.feature.dsh.workspace.device.DshAdbAutoConnectState
import com.muyuchat.feature.dsh.workspace.device.DshAdbChannel
import com.muyuchat.feature.dsh.workspace.device.DshAdbEndpoint
import com.muyuchat.feature.dsh.workspace.device.DshAdbEndpointStore
import com.muyuchat.feature.dsh.workspace.device.DshAdbProvisioner
import com.muyuchat.feature.dsh.workspace.device.DshAdbProvisioningStepResult
import com.muyuchat.feature.dsh.workspace.device.DshAdbRetryDecision
import com.muyuchat.feature.dsh.workspace.device.DshDeviceChannelKind
import com.muyuchat.feature.dsh.workspace.device.DshDeviceChannelStatus
import com.muyuchat.feature.dsh.workspace.device.DshDeviceCommandFailure
import com.muyuchat.feature.dsh.workspace.device.DshDeviceCommandResult
import com.muyuchat.feature.dsh.workspace.device.DshDeviceProbe
import com.muyuchat.feature.dsh.workspace.device.DshRootChannel
import com.muyuchat.feature.dsh.workspace.device.FileDshAdbEndpointStore
import com.muyuchat.feature.dsh.workspace.device.SocketAdbTransport
import com.muyuchat.feature.dsh.workspace.device.dshAdbAfterEnable
import com.muyuchat.feature.dsh.workspace.device.dshAdbAfterFailure
import com.muyuchat.feature.dsh.workspace.device.dshAdbAfterManualDisconnect
import com.muyuchat.feature.dsh.workspace.device.dshAdbAfterSuccess
import com.muyuchat.feature.dsh.workspace.device.dshAdbLoopbackEndpoint
import com.muyuchat.feature.dsh.workspace.device.dshAdbRetryDecision
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One channel as the console section renders it. Pure projection, no channel object. */
data class DshDeviceChannelView(
    val kind: DshDeviceChannelKind,
    val status: DshDeviceChannelStatus,
    val statusLabel: String,
    val tone: DshConsoleTone,
    /** Result of the last probe/command: exit code, reason code and byte counts — never the command. */
    val resultSummary: String,
    /** How the user grants this channel. */
    val grantHint: String,
    /** False until a real probe ran, so the UI cannot present "not measured" as "not available". */
    val probed: Boolean
)

/** State of one "用 Root 自动配置 ADB" run, rendered step by step. */
data class DshAdbProvisioningUiState(
    val running: Boolean = false,
    val steps: List<DshAdbProvisioningStepResult> = emptyList(),
    val connected: Boolean = false,
    val failure: DshDeviceCommandFailure? = null
)

/** State of the DSH console "设备权限" block. */
data class DshDeviceAccessUiState(
    val root: DshDeviceChannelView = dshDeviceChannelView(
        kind = DshDeviceChannelKind.ROOT,
        status = DshDeviceChannelStatus.UNAVAILABLE,
        result = null,
        probed = false
    ),
    val adb: DshDeviceChannelView = dshDeviceChannelView(
        kind = DshDeviceChannelKind.ADB,
        status = DshDeviceChannelStatus.UNAVAILABLE,
        result = null,
        probed = false
    ),
    /** Channel whose probe is currently running; both buttons are disabled while non-null. */
    val busy: DshDeviceChannelKind? = null,
    /** Host:port the user is about to connect the ADB channel to. */
    val adbEndpoint: String = DSH_ADB_DEFAULT_ENDPOINT,
    /** Inline validation message for [adbEndpoint]; null when the text parses. */
    val adbEndpointError: String? = null,
    /** Auto-reconnect bookkeeping (switch off by default). */
    val autoConnect: DshAdbAutoConnectState = DshAdbAutoConnectState(),
    /** "waiting N s for retry k/5" note while an automatic attempt is pending. */
    val retryNote: String? = null,
    /** Result of the last one-tap provisioning run, step by step. */
    val provisioning: DshAdbProvisioningUiState? = null
)

/** Default ADB endpoint: the loopback port `adb tcpip 5555` opens. */
const val DSH_ADB_DEFAULT_ENDPOINT: String = "127.0.0.1:5555"

/** Port used when the user types only a host. */
const val DSH_ADB_DEFAULT_PORT: Int = 5555

/**
 * Parses `host:port` (port optional, defaults to 5555).
 *
 * Pure: returns null for a blank host, a non-numeric port or an out-of-range port, so the UI can show
 * an inline error instead of attempting a connect with garbage.
 */
fun dshAdbEndpointFrom(raw: String): DshAdbEndpoint? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val separator = text.lastIndexOf(':')
    val host = if (separator < 0) text else text.substring(0, separator).trim()
    val portText = if (separator < 0) "" else text.substring(separator + 1).trim()
    if (host.isEmpty() || host.any { it.isWhitespace() }) return null
    val port = if (portText.isEmpty()) {
        DSH_ADB_DEFAULT_PORT
    } else {
        portText.toIntOrNull() ?: return null
    }
    if (port !in 1..65535) return null
    return runCatching { DshAdbEndpoint(host, port) }.getOrNull()
}

/** Chinese label of a channel status; pure and unit-tested. */
fun dshDeviceStatusLabel(status: DshDeviceChannelStatus): String = when (status) {
    DshDeviceChannelStatus.UNAVAILABLE -> "不可用"
    DshDeviceChannelStatus.NEEDS_GRANT -> "待授权"
    DshDeviceChannelStatus.NEEDS_PAIRING -> "待配对"
    DshDeviceChannelStatus.READY -> "已就绪"
    DshDeviceChannelStatus.ERROR -> "错误"
}

/** Existing console tone for a status: only READY is positive, only ERROR is negative. */
fun dshDeviceStatusTone(status: DshDeviceChannelStatus): DshConsoleTone = when (status) {
    DshDeviceChannelStatus.UNAVAILABLE -> DshConsoleTone.NEUTRAL
    DshDeviceChannelStatus.NEEDS_GRANT -> DshConsoleTone.WARNING
    DshDeviceChannelStatus.NEEDS_PAIRING -> DshConsoleTone.WARNING
    DshDeviceChannelStatus.READY -> DshConsoleTone.POSITIVE
    DshDeviceChannelStatus.ERROR -> DshConsoleTone.NEGATIVE
}

/** Grant path shown under each channel. Never claims a capability that is not implemented. */
fun dshDeviceGrantHint(kind: DshDeviceChannelKind): String = when (kind) {
    DshDeviceChannelKind.ROOT ->
        "在 Magisk / KernelSU / APatch 中为 MCA 授予 Root 后点「探测 Root」；未授权时命令不会执行。"

    DshDeviceChannelKind.ADB ->
        "先在设备上开启「无线调试」，或在有 root 时执行 adb tcpip 5555（本版本未提供一键开启）。" +
            "无 root 请用系统的无线调试配对；配对后仍需把本 App 的公钥登记到 /data/misc/adb/adb_keys" +
            "（本版本未提供一键写入，需要手动或用你自己的 adb）。SPAKE2 配对流程本版本尚未接入。"
}

/**
 * One-line summary of the last command result.
 *
 * Includes the exit code, the stable reason code and the byte counts only: the command text and the
 * output body are never repeated here, so nothing sensitive ends up in the console or a log.
 */
fun dshDeviceResultSummary(result: DshDeviceCommandResult?): String {
    if (result == null) return "尚未探测"
    val reason = result.reason
    if (reason != null) return "已拒绝 · ${reason.code}"
    val stdoutBytes = result.stdout.toByteArray(Charsets.UTF_8).size
    val stderrBytes = result.stderr.toByteArray(Charsets.UTF_8).size
    return "exit=${result.exitCode} · stdout ${stdoutBytes} B · stderr ${stderrBytes} B"
}

/**
 * Builds the rendered view of one channel.
 *
 * An unprobed channel is labelled "未探测" with a neutral tone: MCA must not present "never measured"
 * as a measured "unavailable".
 */
fun dshDeviceChannelView(
    kind: DshDeviceChannelKind,
    status: DshDeviceChannelStatus,
    result: DshDeviceCommandResult?,
    probed: Boolean
): DshDeviceChannelView = DshDeviceChannelView(
    kind = kind,
    status = status,
    statusLabel = if (probed) dshDeviceStatusLabel(status) else DSH_DEVICE_UNPROBED_LABEL,
    tone = if (probed) dshDeviceStatusTone(status) else DshConsoleTone.NEUTRAL,
    resultSummary = dshDeviceResultSummary(result),
    grantHint = dshDeviceGrantHint(kind),
    probed = probed
)

/** Label of a channel that has not been probed yet. */
const val DSH_DEVICE_UNPROBED_LABEL: String = "未探测"

/**
 * Page state holder of the console device block.
 *
 * It owns the two channel objects for the page lifetime and is the only place a probe, a connect,
 * an automatic retry or a provisioning run can be started. `su` is spawned only by an explicit
 * [probeRoot] / [provisionAdb] tap; entering the console merely reads the free ADB status. Every job
 * is a `viewModelScope` child, so leaving the page cancels them and the root channel destroys its
 * child process.
 */
class DshDeviceAccessViewModel internal constructor(
    private val root: DshRootChannel,
    private val adb: DshAdbChannel,
    /** Root-based one-tap provisioning; null when the console was built without a key file. */
    private val provisioner: DshAdbProvisioner? = null,
    /** Remembers the last endpoint that connected; null keeps everything in memory only. */
    private val endpointStore: DshAdbEndpointStore? = null
) : ViewModel() {

    private val _state = MutableStateFlow(DshDeviceAccessUiState())
    val state: StateFlow<DshDeviceAccessUiState> = _state.asStateFlow()

    private var rootJob: Job? = null
    private var adbJob: Job? = null
    private var provisionJob: Job? = null
    private var started = false

    /** First entry into the console block. Idempotent and privilege-free. */
    fun start() {
        if (started) return
        started = true
        val saved = endpointStore?.savedEndpoint()
        if (saved != null && dshAdbEndpointFrom(saved) != null) {
            _state.update { it.copy(adbEndpoint = saved) }
        }
        probeAdb()
    }

    /** Explicit, user-initiated root probe: the only path that spawns `su` (besides provisioning). */
    fun probeRoot() {
        if (rootJob?.isActive == true) return
        rootJob = viewModelScope.launch {
            _state.update { it.copy(busy = DshDeviceChannelKind.ROOT) }
            val result = probeChannel { root.probe() }
            _state.update { current ->
                current.copy(
                    busy = null,
                    root = dshDeviceChannelView(
                        kind = DshDeviceChannelKind.ROOT,
                        status = result?.status ?: DshDeviceChannelStatus.ERROR,
                        result = result?.result,
                        probed = true
                    )
                )
            }
        }
    }

    /** ADB status probe; needs no privilege and never opens a socket by itself. */
    fun probeAdb() {
        if (adbJob?.isActive == true) return
        adbJob = viewModelScope.launch {
            _state.update { it.copy(busy = DshDeviceChannelKind.ADB) }
            val result = probeChannel { adb.probe() }
            _state.update { current ->
                current.copy(
                    busy = null,
                    adb = dshDeviceChannelView(
                        kind = DshDeviceChannelKind.ADB,
                        status = result?.status ?: DshDeviceChannelStatus.ERROR,
                        result = result?.result,
                        probed = true
                    )
                )
            }
        }
    }

    /** Updates the endpoint text and clears the previous validation message. */
    fun updateAdbEndpoint(text: String) {
        _state.update { it.copy(adbEndpoint = text, adbEndpointError = null) }
    }

    /**
     * Connects the ADB transport to the endpoint in [DshDeviceAccessUiState.adbEndpoint].
     *
     * A malformed endpoint is reported inline and never reaches the transport. When auto-reconnect is
     * on, the failure path follows [DshAdbAutoConnectPolicy] and reports each wait in [retryNote].
     */
    fun connectAdb() {
        if (adbJob?.isActive == true) return
        val endpoint = dshAdbEndpointFrom(_state.value.adbEndpoint)
        if (endpoint == null) {
            _state.update { it.copy(adbEndpointError = "格式应为 host:port，例如 127.0.0.1:5555") }
            return
        }
        adbJob = viewModelScope.launch { runConnectLoop(endpoint) }
    }

    /**
     * Explicit user disconnect: closes the socket, stops any retry loop and blocks automatic
     * reconnects until the switch is toggled again.
     */
    fun disconnectAdb() {
        adbJob?.cancel()
        adbJob = null
        runCatching { adb.close() }
        _state.update { current ->
            current.copy(
                adb = current.adb.copy(
                    status = adb.status(),
                    statusLabel = dshDeviceStatusLabel(adb.status()),
                    tone = dshDeviceStatusTone(adb.status())
                ),
                autoConnect = dshAdbAfterManualDisconnect(current.autoConnect),
                retryNote = null,
                busy = null
            )
        }
    }

    /** Turns the automatic reconnect loop on/off; enabling also makes one immediate attempt. */
    fun setAutoReconnect(enabled: Boolean) {
        _state.update {
            it.copy(autoConnect = dshAdbAfterEnable(it.autoConnect, enabled), retryNote = null)
        }
        if (enabled && adb.status() != DshDeviceChannelStatus.READY) {
            connectAdb()
        }
    }

    /**
     * One-tap provisioning through root.
     *
     * The caller (console UI) must show the confirmation dialog first: the fixed script appends this
     * app's public key to the system adb authorisation list and enables wireless adb, which is a real
     * capability grant. The steps are reported one by one; nothing is hidden behind a single boolean.
     */
    fun provisionAdb() {
        val provisioner = provisioner ?: return
        if (provisionJob?.isActive == true || rootJob?.isActive == true) return
        provisionJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = DshDeviceChannelKind.ROOT,
                    provisioning = DshAdbProvisioningUiState(running = true)
                )
            }
            val outcome = try {
                provisioner.provision()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                null
            }
            _state.update { current ->
                current.copy(
                    busy = null,
                    provisioning = DshAdbProvisioningUiState(
                        running = false,
                        steps = outcome?.steps.orEmpty(),
                        connected = outcome?.connected == true,
                        failure = outcome?.failure ?: if (outcome == null) {
                            DshDeviceCommandFailure.EXEC_FAILED
                        } else {
                            null
                        }
                    )
                )
            }
            if (outcome?.connected != true) return@launch
            // The provisioning verified its own socket; bring the console's own channel up as well.
            val endpoint = dshAdbLoopbackEndpoint()
            _state.update { it.copy(adbEndpoint = "${endpoint.host}:${endpoint.port}") }
            val probe = probeChannel { adb.connect(endpoint) }
            val status = probe?.status ?: DshDeviceChannelStatus.ERROR
            _state.update { current ->
                current.copy(
                    adb = dshDeviceChannelView(
                        kind = DshDeviceChannelKind.ADB,
                        status = status,
                        result = probe?.result,
                        probed = true
                    ),
                    autoConnect = if (status == DshDeviceChannelStatus.READY) {
                        dshAdbAfterSuccess(current.autoConnect)
                    } else {
                        current.autoConnect
                    }
                )
            }
            if (status == DshDeviceChannelStatus.READY) {
                endpointStore?.saveEndpoint("${endpoint.host}:${endpoint.port}")
            }
        }
    }

    /** Releases the ADB transport (no-op while none is wired). */
    override fun onCleared() {
        runCatching { adb.close() }
    }

    /** One connect attempt plus the bounded auto-reconnect loop described by the policy. */
    private suspend fun runConnectLoop(endpoint: DshAdbEndpoint) {
        val endpointText = _state.value.adbEndpoint
        while (true) {
            _state.update {
                it.copy(
                    busy = DshDeviceChannelKind.ADB,
                    adbEndpointError = null,
                    retryNote = null
                )
            }
            val result = probeChannel { adb.connect(endpoint) }
            val status = result?.status ?: DshDeviceChannelStatus.ERROR
            _state.update { current ->
                current.copy(
                    busy = null,
                    adb = dshDeviceChannelView(
                        kind = DshDeviceChannelKind.ADB,
                        status = status,
                        result = result?.result,
                        probed = true
                    ),
                    autoConnect = if (status == DshDeviceChannelStatus.READY) {
                        dshAdbAfterSuccess(current.autoConnect)
                    } else {
                        current.autoConnect
                    }
                )
            }
            if (status == DshDeviceChannelStatus.READY) {
                endpointStore?.saveEndpoint(endpointText)
                return
            }
            val decision = dshAdbRetryDecision(
                state = _state.value.autoConnect,
                endpoint = endpointText,
                awaitingPairing = status == DshDeviceChannelStatus.NEEDS_PAIRING
            )
            _state.update {
                it.copy(autoConnect = dshAdbAfterFailure(it.autoConnect, result?.result?.reason))
            }
            if (decision !is DshAdbRetryDecision.Retry) return
            _state.update {
                it.copy(
                    retryNote = "第 ${decision.attempt + 1}/${DshAdbAutoConnectPolicy.MAX_ATTEMPTS} 次重连将在 " +
                        "${decision.delayMillis / 1_000}s 后进行"
                )
            }
            delay(decision.delayMillis)
            if (!_state.value.autoConnect.enabled || _state.value.autoConnect.manualDisconnect) {
                _state.update { it.copy(retryNote = null) }
                return
            }
        }
    }

    /**
     * Runs one probe and maps a thrown error to "no result" (the UI then reports ERROR). Cancellation
     * is never swallowed: leaving the page must actually stop the probe.
     */
    private suspend fun probeChannel(probe: suspend () -> DshDeviceProbe): DshDeviceProbe? =
        try {
            probe()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            null
        }

    companion object {
        /**
         * Builds the page state holder.
         *
         * [adbIdentityFile] is the app-private PKCS#8 key file (the console passes
         * `filesDir/dsh/adbkey.pkcs8`); it also provides the directory for the remembered endpoint and
         * the root provisioner. When null the ADB channel stays an honest "not wired" skeleton and
         * provisioning is unavailable.
         */
        fun factory(
            adbIdentityFile: File? = null,
            endpointStore: DshAdbEndpointStore? = null
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val root = DshRootChannel()
                val transport = adbIdentityFile?.let { SocketAdbTransport(it) }
                val provisioner = adbIdentityFile?.let { DshAdbProvisioner(root = root, identityFile = it) }
                val store = endpointStore ?: adbIdentityFile?.parentFile?.let {
                    FileDshAdbEndpointStore(File(it, "adb_endpoint"))
                }
                DshDeviceAccessViewModel(
                    root = root,
                    adb = DshAdbChannel(transport),
                    provisioner = provisioner,
                    endpointStore = store
                )
            }
        }
    }
}
