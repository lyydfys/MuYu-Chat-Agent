package com.muyuchat.feature.dsh.workspace.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * State of a wireless-ADB transport.
 *
 * `NOT_CONFIGURED` is the initial state (and the state after [AdbTransport.close]). `AWAITING_PAIRING`
 * means the handshake completed far enough to publish this app's public key, but the device has not
 * trusted it yet.
 */
enum class DshAdbTransportState {
    /** No socket session: never connected, or explicitly closed. */
    NOT_CONFIGURED,

    /** The device asked for this app's key and has not authorised it yet (`adb_keys`). */
    AWAITING_PAIRING,

    /** Authenticated (`CNXN` accepted); `shell` may run. */
    CONNECTED,

    /** Connect/handshake/framing failed; `lastFailure` carries the stable reason. */
    FAILED
}

/** One ADB endpoint to pair with / connect to. */
data class DshAdbEndpoint(val host: String, val port: Int) {
    init {
        require(host.isNotBlank()) { "ADB endpoint host must not be blank" }
        require(port in 1..65535) { "ADB endpoint port must be 1..65535" }
    }
}

/** Raw result of one `shell` call on a transport. */
data class DshAdbShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    /** Non-null when the stream did not produce a normal close (framing/transport failure). */
    val failure: DshDeviceCommandFailure? = null,
    /** True when the transport cut the stream at [DSH_DEVICE_OUTPUT_LIMIT_BYTES]. */
    val overflowed: Boolean = false
)

/**
 * Replaceable transport behind [DshAdbChannel].
 *
 * [SocketAdbTransport] is the production implementation: a plain-text adb session on
 * `host:port` with the standard CNXN/AUTH handshake. Wireless **pairing** with a pairing code
 * (SPAKE2 + TLS-PSK on the pairing port) is still not implemented: the app can only authenticate with
 * a key the device already trusts, so a fresh device answers NEEDS_PAIRING until the user registers
 * the app key.
 */
interface AdbTransport {

    fun state(): DshAdbTransportState

    /** Stable reason of the last failed connect/shell, or null when nothing failed. */
    fun lastFailure(): DshDeviceCommandFailure? = null

    /** Performs pairing/connect. Returns true only when the transport reached [DshAdbTransportState.CONNECTED]. */
    suspend fun connect(endpoint: DshAdbEndpoint): Boolean

    suspend fun shell(command: String): DshAdbShellResult

    fun close()
}

/** Pure mapping from transport state to the console-visible channel status. */
fun dshAdbStatusFor(state: DshAdbTransportState): DshDeviceChannelStatus = when (state) {
    DshAdbTransportState.NOT_CONFIGURED -> DshDeviceChannelStatus.UNAVAILABLE
    DshAdbTransportState.AWAITING_PAIRING -> DshDeviceChannelStatus.NEEDS_PAIRING
    DshAdbTransportState.CONNECTED -> DshDeviceChannelStatus.READY
    DshAdbTransportState.FAILED -> DshDeviceChannelStatus.ERROR
}

/**
 * Byte-bounded truncation for transport output.
 *
 * Transport results arrive as `String`, so the ceiling is applied on UTF-8 bytes; a multi-byte
 * character split at the ceiling is acceptable for diagnostics.
 */
internal fun dshBoundOutput(
    text: String,
    limitBytes: Int = DSH_DEVICE_OUTPUT_LIMIT_BYTES
): DshBoundedOutput {
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= limitBytes) return DshBoundedOutput(text, overflowed = false)
    return DshBoundedOutput(bytes.copyOf(limitBytes).toString(Charsets.UTF_8), overflowed = true)
}

/**
 * ADB channel.
 *
 * Every command passes [DshDeviceCommandPolicy] first; the transport never sees a rejected command.
 * The channel also enforces its own timeout and output ceiling, so a transport that blocks or floods
 * can only produce a bounded, typed failure.
 *
 * With no [transport] (tests, or a host that deliberately keeps ADB off) the channel is permanently
 * `UNAVAILABLE` and returns `not_integrated`; it never pretends to be READY.
 */
class DshAdbChannel internal constructor(
    private val transport: AdbTransport?
) : DshDeviceChannel {

    /** No transport: the honest "ADB not wired in this host" state. */
    constructor() : this(null)

    override val kind: DshDeviceChannelKind = DshDeviceChannelKind.ADB

    override fun status(): DshDeviceChannelStatus =
        transport?.let { dshAdbStatusFor(it.state()) } ?: DshDeviceChannelStatus.UNAVAILABLE

    /**
     * Connects to [endpoint] and reports the resulting channel status.
     *
     * A successful handshake is not enough to claim the channel works: the probe then runs one
     * policy-gated read-only command, so READY always has a real result behind it.
     */
    suspend fun connect(endpoint: DshAdbEndpoint): DshDeviceProbe {
        val active = transport ?: return DshDeviceProbe(
            kind = kind,
            status = DshDeviceChannelStatus.UNAVAILABLE,
            result = notIntegratedResult()
        )
        val connected = try {
            active.connect(endpoint)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            false
        }
        val status = dshAdbStatusFor(active.state())
        if (connected && status == DshDeviceChannelStatus.READY) {
            return probe()
        }
        val failure = active.lastFailure() ?: when (status) {
            DshDeviceChannelStatus.NEEDS_PAIRING -> DshDeviceCommandFailure.PAIRING_REQUIRED
            DshDeviceChannelStatus.UNAVAILABLE -> DshDeviceCommandFailure.NOT_INTEGRATED
            else -> DshDeviceCommandFailure.HANDSHAKE_FAILED
        }
        return DshDeviceProbe(
            kind = kind,
            status = status,
            result = DshDeviceCommandResult.failure(
                failure = failure,
                message = "adb connect did not complete"
            )
        )
    }

    /**
     * Status probe. With no transport this returns `UNAVAILABLE` plus a `not_integrated` result, so
     * the console can explain the exact missing piece instead of showing an empty row.
     */
    suspend fun probe(): DshDeviceProbe {
        val active = transport
        if (active == null) {
            return DshDeviceProbe(
                kind = kind,
                status = DshDeviceChannelStatus.UNAVAILABLE,
                result = notIntegratedResult()
            )
        }
        val status = dshAdbStatusFor(active.state())
        val result = if (status == DshDeviceChannelStatus.READY) {
            exec(ADB_PROBE_COMMAND, DSH_DEVICE_PROBE_TIMEOUT_MS)
        } else {
            notReadyResult(active.lastFailure())
        }
        return DshDeviceProbe(kind = kind, status = status, result = result)
    }

    override suspend fun exec(
        command: String,
        timeoutMs: Long
    ): DshDeviceCommandResult {
        val decision = DshDeviceCommandPolicy.evaluate(command)
        if (decision is DshDeviceCommandDecision.Rejected) {
            return DshDeviceCommandResult.policyBlocked(
                policyDetail = "${decision.rejection.code}: ${decision.rule}"
            )
        }
        val allowed = decision as DshDeviceCommandDecision.Allowed
        val active = transport ?: return notIntegratedResult()
        if (active.state() != DshAdbTransportState.CONNECTED) {
            return notReadyResult(active.lastFailure())
        }
        val effectiveTimeout = if (timeoutMs > 0) timeoutMs else DSH_DEVICE_COMMAND_TIMEOUT_MS
        val shellResult = try {
            withTimeout(effectiveTimeout) {
                active.shell(DshDeviceCommandPolicy.renderSafe(allowed.segments))
            }
        } catch (timeout: TimeoutCancellationException) {
            // Only this scope's own timeout is converted; an outer cancellation keeps propagating.
            return DshDeviceCommandResult.failure(
                DshDeviceCommandFailure.TIMEOUT,
                message = "adb shell did not finish inside ${effectiveTimeout}ms"
            )
        }
        val transportFailure = shellResult.failure
        if (transportFailure != null) {
            return DshDeviceCommandResult.failure(
                failure = transportFailure,
                message = "adb shell failed",
                stdout = shellResult.stdout
            )
        }
        val stdout = dshBoundOutput(shellResult.stdout)
        val stderr = dshBoundOutput(shellResult.stderr)
        if (shellResult.overflowed || stdout.overflowed || stderr.overflowed) {
            return DshDeviceCommandResult.failure(
                DshDeviceCommandFailure.OUTPUT_LIMIT,
                message = "output exceeded $DSH_DEVICE_OUTPUT_LIMIT_BYTES bytes and was truncated",
                stdout = stdout.text
            )
        }
        return DshDeviceCommandResult(
            exitCode = shellResult.exitCode,
            stdout = stdout.text,
            stderr = stderr.text,
            reason = null
        )
    }

    /** Releases the transport; the transport itself makes `close` idempotent. */
    fun close() {
        runCatching { transport?.close() }
    }

    private fun notIntegratedResult(): DshDeviceCommandResult = DshDeviceCommandResult.failure(
        DshDeviceCommandFailure.NOT_INTEGRATED,
        message = "wireless ADB transport is not wired in this host"
    )

    private fun notReadyResult(transportFailure: DshDeviceCommandFailure?): DshDeviceCommandResult =
        DshDeviceCommandResult.failure(
            failure = transportFailure ?: DshDeviceCommandFailure.NOT_GRANTED,
            message = "adb transport is not authenticated"
        )

    private companion object {
        /**
         * Read-only probe for a connected transport. It passes the same policy gate as any other
         * command, which also demonstrates that the channel does not bypass its own guard.
         */
        const val ADB_PROBE_COMMAND = "getprop ro.product.model"
    }
}
