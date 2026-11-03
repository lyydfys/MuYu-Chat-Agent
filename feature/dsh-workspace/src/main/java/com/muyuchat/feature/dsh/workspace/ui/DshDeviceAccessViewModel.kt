package com.muyuchat.feature.dsh.workspace.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.muyuchat.feature.dsh.workspace.DshConsoleTone
import com.muyuchat.feature.dsh.workspace.device.DshAdbChannel
import com.muyuchat.feature.dsh.workspace.device.DshDeviceChannelKind
import com.muyuchat.feature.dsh.workspace.device.DshDeviceChannelStatus
import com.muyuchat.feature.dsh.workspace.device.DshDeviceCommandResult
import com.muyuchat.feature.dsh.workspace.device.DshDeviceProbe
import com.muyuchat.feature.dsh.workspace.device.DshRootChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    val busy: DshDeviceChannelKind? = null
)

/** Chinese label of a channel status; pure and unit-tested. */
fun dshDeviceStatusLabel(status: DshDeviceChannelStatus): String = when (status) {
    DshDeviceChannelStatus.UNAVAILABLE -> "不可用"
    DshDeviceChannelStatus.NEEDS_GRANT -> "待授权"
    DshDeviceChannelStatus.READY -> "已就绪"
    DshDeviceChannelStatus.ERROR -> "错误"
}

/** Existing console tone for a status: only READY is positive, only ERROR is negative. */
fun dshDeviceStatusTone(status: DshDeviceChannelStatus): DshConsoleTone = when (status) {
    DshDeviceChannelStatus.UNAVAILABLE -> DshConsoleTone.NEUTRAL
    DshDeviceChannelStatus.NEEDS_GRANT -> DshConsoleTone.WARNING
    DshDeviceChannelStatus.READY -> DshConsoleTone.POSITIVE
    DshDeviceChannelStatus.ERROR -> DshConsoleTone.NEGATIVE
}

/** Grant path shown under each channel. Never claims a capability that is not implemented. */
fun dshDeviceGrantHint(kind: DshDeviceChannelKind): String = when (kind) {
    DshDeviceChannelKind.ROOT ->
        "在 Magisk / KernelSU / APatch 中为 MCA 授予 Root 后点「探测 Root」；未授权时命令不会执行。"

    DshDeviceChannelKind.ADB ->
        "需要系统「无线调试」的配对码。本版本尚未接入配对流程（SPAKE2 / TLS-PSK），通道保持未接入。"
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
 * It owns the two channel objects for the page lifetime and is the only place a probe can be
 * started. `su` is spawned only by an explicit [probeRoot] tap; entering the console merely reads
 * the free ADB status. Both jobs are `viewModelScope` children, so leaving the page cancels them and
 * the root channel destroys its child process.
 */
class DshDeviceAccessViewModel internal constructor(
    private val root: DshRootChannel,
    private val adb: DshAdbChannel
) : ViewModel() {

    private val _state = MutableStateFlow(DshDeviceAccessUiState())
    val state: StateFlow<DshDeviceAccessUiState> = _state.asStateFlow()

    private var rootJob: Job? = null
    private var adbJob: Job? = null
    private var started = false

    /** First entry into the console block. Idempotent and privilege-free. */
    fun start() {
        if (started) return
        started = true
        probeAdb()
    }

    /** Explicit, user-initiated root probe: the only path that spawns `su`. */
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

    /** ADB status probe; needs no privilege and never opens a socket in this generation. */
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

    /** Releases the ADB transport (no-op while none is wired). */
    override fun onCleared() {
        runCatching { adb.close() }
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
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer { DshDeviceAccessViewModel(DshRootChannel(), DshAdbChannel()) }
        }
    }
}
