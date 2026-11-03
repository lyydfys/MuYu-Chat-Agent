package com.muyuchat.feature.dsh.workspace.device

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * State of a wireless-ADB transport.
 *
 * `NOT_CONFIGURED` is the only state this generation can reach: no pairing implementation exists yet.
 */
enum class DshAdbTransportState {
    /** No transport/pairing at all. */
    NOT_CONFIGURED,

    /** The pairing flow started (pairing code / TLS-PSK handshake) but did not complete. */
    AWAITING_PAIRING,

    /** Paired and authenticated; `shell` may run. */
    CONNECTED,

    /** The transport exists but broke (handshake failure, socket error, key rejected). */
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
    val stderr: String
)

/**
 * Replaceable transport behind [DshAdbChannel].
 *
 * **This slice ships no implementation.** Wireless debugging is not a socket wrapper: it needs the
 * adb pairing protocol (SPAKE2 over the pairing port), the TLS 1.3 PSK handshake against the
 * `_adb-tls-connect` port, key/certificate storage and connection keep-alive. That is a separate
 * slice; until it lands, [DshAdbChannel] is constructed without a transport, reports
 * [DshDeviceChannelStatus.UNAVAILABLE] and returns `not_integrated` for every command. It must
 * never be reported as READY to the user or to an agent.
 */
interface AdbTransport {

    fun state(): DshAdbTransportState

    /** Performs pairing/connect. Returns true only when the transport reached [DshAdbTransportState.CONNECTED]. */
    suspend fun connect(endpoint: DshAdbEndpoint): Boolean

    suspend fun shell(command: String): DshAdbShellResult

    fun close()
}

/** Pure mapping from transport state to the console-visible channel status. */
fun dshAdbStatusFor(state: DshAdbTransportState): DshDeviceChannelStatus = when (state) {
    DshAdbTransportState.NOT_CONFIGURED -> DshDeviceChannelStatus.UNAVAILABLE
    DshAdbTransportState.AWAITING_PAIRING -> DshDeviceChannelStatus.NEEDS_GRANT
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
 * ADB channel skeleton.
 *
 * What is real today: the channel contract, the shared policy gate, byte-bounded output, timeout
 * enforcement and truthful status projection. What is **not** implemented: pairing, the TLS-PSK
 * handshake, discovery and key storage. With no [transport] the channel is permanently
 * `UNAVAILABLE`/`not_integrated`, which is the honest state, not a placeholder success.
 */
class DshAdbChannel internal constructor(
    private val transport: AdbTransport?
) : DshDeviceChannel {

    /** This generation: no transport, therefore no pairing and no READY. */
    constructor() : this(null)

    override val kind: DshDeviceChannelKind = DshDeviceChannelKind.ADB

    override fun status(): DshDeviceChannelStatus =
        transport?.let { dshAdbStatusFor(it.state()) } ?: DshDeviceChannelStatus.UNAVAILABLE

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
            notPairedResult()
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
            return notPairedResult()
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
        val stdout = dshBoundOutput(shellResult.stdout)
        val stderr = dshBoundOutput(shellResult.stderr)
        if (stdout.overflowed || stderr.overflowed) {
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

    /** Releases the transport (no-op while no transport is wired). */
    fun close() {
        runCatching { transport?.close() }
    }

    private fun notIntegratedResult(): DshDeviceCommandResult = DshDeviceCommandResult.failure(
        DshDeviceCommandFailure.NOT_INTEGRATED,
        message = "wireless ADB pairing is not integrated in this generation"
    )

    private fun notPairedResult(): DshDeviceCommandResult = DshDeviceCommandResult.failure(
        DshDeviceCommandFailure.NOT_GRANTED,
        message = "adb transport is not paired"
    )

    private companion object {
        /**
         * Read-only probe for a future paired transport. It passes the same policy gate as any other
         * command, which also demonstrates that the channel does not bypass its own guard.
         */
        const val ADB_PROBE_COMMAND = "getprop ro.product.model"
    }
}
