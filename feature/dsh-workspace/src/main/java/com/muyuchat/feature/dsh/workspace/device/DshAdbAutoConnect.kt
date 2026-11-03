package com.muyuchat.feature.dsh.workspace.device

import java.io.File

/**
 * Remembers the last endpoint that connected successfully, so "配一次就不用管" survives page
 * re-entry and process restarts. Injected as a seam: tests use an in-memory implementation.
 */
interface DshAdbEndpointStore {

    fun savedEndpoint(): String?

    fun saveEndpoint(endpoint: String)

    fun clearEndpoint()
}

/** File-backed store: a single line in app-private storage (same `dsh/` directory as the ADB key). */
class FileDshAdbEndpointStore internal constructor(private val file: File) : DshAdbEndpointStore {

    override fun savedEndpoint(): String? = runCatching {
        if (!file.isFile) return null
        file.readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    override fun saveEndpoint(endpoint: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(endpoint.trim())
        }
    }

    override fun clearEndpoint() {
        runCatching { if (file.isFile) file.writeText("") }
    }
}

/**
 * Auto-reconnect policy.
 *
 * Delays are 1 s, 2 s, 4 s, 8 s, 8 s for at most five attempts: long enough to survive the adbd
 * restart the provisioning triggers, short enough that a device which never comes back stops
 * hammering within ~23 s. A successful connection resets the attempt counter.
 */
object DshAdbAutoConnectPolicy {

    /** Delay before each retry; index = attempts already made. */
    val BACKOFF_MILLIS: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L)

    /** Upper bound of automatic attempts for one outage. */
    const val MAX_ATTEMPTS: Int = 5

    /** Delay for [attempt] (0-based); values beyond the table keep the last delay. */
    fun backoffMillis(attempt: Int): Long =
        BACKOFF_MILLIS[attempt.coerceIn(0, BACKOFF_MILLIS.lastIndex)]
}

/** Pure state of the auto-reconnect loop. */
data class DshAdbAutoConnectState(
    val enabled: Boolean = false,
    /** Automatic attempts already spent for the current outage. */
    val attempts: Int = 0,
    /** True after an explicit user disconnect: automatic reconnects stay off until re-enabled. */
    val manualDisconnect: Boolean = false,
    val lastFailure: DshDeviceCommandFailure? = null
) {
    val exhausted: Boolean get() = attempts >= DshAdbAutoConnectPolicy.MAX_ATTEMPTS
}

/** Why no retry will happen; the console shows the matching hint. */
enum class DshAdbRetryBlock(val code: String) {
    DISABLED("auto_connect_disabled"),
    MANUAL_DISCONNECT("manual_disconnect"),
    NO_ENDPOINT("no_endpoint"),
    AWAITING_PAIRING("awaiting_pairing"),
    EXHAUSTED("attempts_exhausted"),
    ALREADY_CONNECTED("already_connected")
}

/** Pure retry decision: either "retry after N ms" or "stop for this reason". */
sealed interface DshAdbRetryDecision {
    data class Retry(val attempt: Int, val delayMillis: Long) : DshAdbRetryDecision

    data class Stop(val block: DshAdbRetryBlock) : DshAdbRetryDecision
}

/**
 * Decides whether another automatic attempt should run.
 *
 * Never retries when: the user turned the switch off, the user disconnected manually, there is no
 * remembered endpoint, the device is waiting for key authorisation (a retry cannot fix that), the
 * link is already up, or the attempt budget is spent.
 */
fun dshAdbRetryDecision(
    state: DshAdbAutoConnectState,
    endpoint: String?,
    awaitingPairing: Boolean,
    connected: Boolean = false
): DshAdbRetryDecision = when {
    connected -> DshAdbRetryDecision.Stop(DshAdbRetryBlock.ALREADY_CONNECTED)
    !state.enabled -> DshAdbRetryDecision.Stop(DshAdbRetryBlock.DISABLED)
    state.manualDisconnect -> DshAdbRetryDecision.Stop(DshAdbRetryBlock.MANUAL_DISCONNECT)
    endpoint.isNullOrBlank() -> DshAdbRetryDecision.Stop(DshAdbRetryBlock.NO_ENDPOINT)
    awaitingPairing -> DshAdbRetryDecision.Stop(DshAdbRetryBlock.AWAITING_PAIRING)
    state.exhausted -> DshAdbRetryDecision.Stop(DshAdbRetryBlock.EXHAUSTED)
    else -> DshAdbRetryDecision.Retry(
        attempt = state.attempts,
        delayMillis = DshAdbAutoConnectPolicy.backoffMillis(state.attempts)
    )
}

/** Enables/disables auto-reconnect; enabling always clears an earlier manual disconnect. */
fun dshAdbAfterEnable(state: DshAdbAutoConnectState, enabled: Boolean): DshAdbAutoConnectState =
    state.copy(enabled = enabled, manualDisconnect = false, attempts = 0, lastFailure = null)

/** A successful connection resets the outage bookkeeping. */
fun dshAdbAfterSuccess(state: DshAdbAutoConnectState): DshAdbAutoConnectState =
    state.copy(attempts = 0, manualDisconnect = false, lastFailure = null)

/** One failed attempt; the attempt counter drives the backoff table. */
fun dshAdbAfterFailure(
    state: DshAdbAutoConnectState,
    failure: DshDeviceCommandFailure?
): DshAdbAutoConnectState = state.copy(
    // Clamp at the ceiling: the counter is both a progress display ("第 k/5 次") and the
    // exhaustion predicate, so letting it reach MAX+1 would show "6/5" and hide the real cap.
    attempts = (state.attempts + 1).coerceAtMost(DshAdbAutoConnectPolicy.MAX_ATTEMPTS),
    lastFailure = failure
)

/** The user asked to stop: no automatic reconnect until the switch is toggled again. */
fun dshAdbAfterManualDisconnect(state: DshAdbAutoConnectState): DshAdbAutoConnectState =
    state.copy(manualDisconnect = true, attempts = 0)
