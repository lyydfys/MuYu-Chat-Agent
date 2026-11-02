package com.muyuchat.core.dsh.contract

import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle phase of the Android DSH runtime host (the Node Web profile process).
 *
 * The phase describes the host only. Official DSH session, task, approval and plugin
 * state stay inside DSH and are never mirrored here.
 */
enum class DshRuntimePhase {
    STOPPED,
    STARTING,
    READY,
    STOPPING,
    FAILED
}

/**
 * Concrete failure families of the Android host. A failure is reported only for a real
 * condition; "unknown chipset" or "never started" is not a failure reason.
 */
enum class DshFailureCode {
    /** The packaged DSH payload is absent from the APK. */
    PAYLOAD_MISSING,
    /** The packaged payload failed its manifest/hash verification. */
    PAYLOAD_INTEGRITY,
    /** The device ABI has no packaged Node executable. */
    ABI_UNSUPPORTED,
    /** The Node executable exists but the app cannot execute it (e.g. link/exec denial). */
    NODE_NOT_EXECUTABLE,
    /** Node started but exited before the official Web profile became ready. */
    NODE_START_FAILED,
    /** The official Loader tree never reached a stable ready state. */
    LOADER_NOT_READY,
    /** The authenticated launch entry printed by `dsh --profile web` could not be found. */
    AUTH_ENTRY_MISSING,
    /** The internal MCA model gateway is not reachable, so DSH could not start. */
    GATEWAY_UNAVAILABLE,
    /** A previously ready host process exited unexpectedly. */
    PROCESS_EXITED,
    /** Stop was requested but the process did not settle in time; the stop fence stays up. */
    STOP_TIMEOUT,
    /**
     * Startup recovery could not prove that a new generation is safe (an interrupted trial or
     * maintenance transaction still owns the host slot). No host is started for this attempt.
     */
    STARTUP_RECOVERY_REQUIRED,
    /** A defect in the host implementation. */
    INTERNAL
}

/** A prompt-free, log-safe failure description. Never carries a launch token or model key. */
data class DshRuntimeFailure(
    val code: DshFailureCode,
    val message: String,
    val retryable: Boolean = true,
    /** Bounded engineering detail suitable for a local log; must stay secret-free. */
    val diagnostics: String? = null
)

/**
 * Immutable, observable host snapshot.
 *
 * [origin] is the loopback origin only (`http://127.0.0.1:<port>`). It never contains the
 * launch token; the authenticated entry is delivered separately through [DshRuntimeClient.webEntry].
 */
data class DshRuntimeSnapshot(
    val phase: DshRuntimePhase = DshRuntimePhase.STOPPED,
    val epoch: Long = 0L,
    val origin: String? = null,
    val failure: DshRuntimeFailure? = null,
    val changedAtMillis: Long = 0L
) {
    val isReady: Boolean
        get() = phase == DshRuntimePhase.READY && origin != null
}

/**
 * One authenticated Web entry. The URL carries the single-use launch token that the official
 * launch-token -> HttpOnly cookie flow consumes. Callers must load it once and never persist,
 * log, share or put it in an Intent.
 */
data class DshWebEntry(
    val epoch: Long,
    val authenticatedUrl: String
)

/**
 * App-facing client of the single DSH runtime owner.
 *
 * Contract: repeated [ensureStarted] calls attach the same host generation; they never start a
 * second Node process. The runtime is started on demand when the DSH workspace is opened and is
 * not preloaded by chat, image or Kotlin Agent entry.
 */
interface DshRuntimeClient {
    val snapshot: StateFlow<DshRuntimeSnapshot>

    /** Starts the host if stopped, waits until READY or terminal failure, returns the snapshot. */
    suspend fun ensureStarted(): DshRuntimeSnapshot

    /**
     * Starts the production host from a committed closure payload tree instead of the APK's
     * installed payload.
     *
     * [payloadRoot] is the `payload` directory of a closure the maintenance owner already
     * verified, tried in isolation and committed. `null` selects the installed payload again
     * (the pre-upgrade state). The runtime owner re-admits a non-null root (closure containment,
     * its own descriptor and critical-file hashes) before any process starts; it never trusts the
     * path alone. The default implementation reports a typed failure so a client that has no
     * closure support can never look like it started one.
     */
    suspend fun ensureStartedFrom(payloadRoot: java.io.File?): DshRuntimeSnapshot =
        DshRuntimeSnapshot(
            phase = DshRuntimePhase.FAILED,
            failure = DshRuntimeFailure(
                code = DshFailureCode.INTERNAL,
                message = "this runtime client cannot start from a committed closure payload",
                retryable = false
            )
        )

    /** Restarts a failed host with a new epoch; a ready host is returned unchanged. */
    suspend fun restart(): DshRuntimeSnapshot

    /** Stops the host and waits for the stop fence. Other MCA modes are never touched. */
    suspend fun stop()

    /**
     * Returns the current authenticated entry once the host is READY, or null when the
     * current generation has already consumed/failed its entry. Safe to call from the UI:
     * the value is in-memory only and is cleared when the host generation changes.
     */
    suspend fun webEntry(): DshWebEntry?
}

/**
 * Startup admission decided by the maintenance owner before any host generation starts.
 *
 * The gate runs on every start attempt, after the journal/safe-mode/upgrade recovery sweep. A
 * refusal never starts a host; it carries the exact typed failure so the workspace can show the
 * reason and offer a retry. A retry re-runs the sweep, so a transient obstruction is recoverable
 * without restarting the process.
 */
sealed interface DshStartupAdmission {
    data object Admitted : DshStartupAdmission

    data class Refused(val failure: DshRuntimeFailure) : DshStartupAdmission
}

/** Narrow, typed provider for the internal MCA model gateway endpoint DSH should use. */
fun interface DshGatewayEndpointProvider {
    /**
     * Returns the loopback gateway endpoint for the current host generation, starting the
     * gateway on demand. An empty model catalog is valid and does not block the DSH frontend;
     * returns null when the gateway or its catalog is unavailable.
     */
    suspend fun endpoint(): DshGatewayEndpoint?
}

/** Loopback-only model gateway coordinates. Never holds a cloud provider's raw credential. */
data class DshGatewayEndpoint(
    val baseUrl: String,
    val bearerToken: String,
    val catalogRevision: String
)
