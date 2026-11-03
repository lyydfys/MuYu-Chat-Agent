package com.muyuchat.feature.dsh.workspace.device

/**
 * The two device-privilege transports the DSH workspace can drive.
 *
 * Both are opt-in: a channel reports [DshDeviceChannelStatus.UNAVAILABLE] until the user has
 * actually granted the privilege, and nothing here escalates on its own. There is deliberately no
 * "still run it anyway" switch in this package — a blocked command stays blocked.
 */
enum class DshDeviceChannelKind {
    /** `su -c` through the device's root manager (Magisk / KernelSU / APatch). */
    ROOT,

    /** Wireless ADB shell through an [AdbTransport]. Pairing is not implemented in this slice. */
    ADB
}

/**
 * Truthful state of one channel.
 *
 * `UNAVAILABLE` is the initial state of every channel and also the state of a channel that has no
 * implementation wired in at all (the ADB transport in this generation). It must never be reported
 * as `READY` "optimistically": `READY` is only ever set after a real probe proved the privilege.
 */
enum class DshDeviceChannelStatus {
    /** No usable transport: no `su` binary, or no ADB transport/pairing at all. */
    UNAVAILABLE,

    /** The transport exists but the user has not granted it (root prompt denied). */
    NEEDS_GRANT,

    /**
     * The ADB handshake proved that the device does not trust this app's key yet: the public key must
     * be registered (`/data/misc/adb/adb_keys` or the system wireless-debugging pair flow).
     */
    NEEDS_PAIRING,

    /** A probe proved the channel works; commands may run. */
    READY,

    /** The transport exists but is broken (spawn failure while granting, malformed `su` output). */
    ERROR
}

/**
 * Stable, lower_snake_case failure codes carried by [DshDeviceCommandResult.reason].
 *
 * These are engineering codes, not user-facing copy: they are safe to compare in tests, to show in
 * the console and to count, and they never contain a command, a path or a credential.
 */
enum class DshDeviceCommandFailure(val code: String) {
    /** Root not granted / no `su` binary; the command never ran. */
    NOT_GRANTED("not_granted"),

    /** Refused by [DshDeviceCommandPolicy]; the command never reached a shell. */
    POLICY_BLOCKED("policy_blocked"),

    /** The process/transport did not answer inside the caller's timeout and was destroyed. */
    TIMEOUT("timeout"),

    /** Spawning the transport process failed (binary vanished, fork failure, IO error). */
    EXEC_FAILED("exec_failed"),

    /** Output exceeded [DSH_DEVICE_OUTPUT_LIMIT_BYTES]; the result carries the truncated prefix. */
    OUTPUT_LIMIT("output_limit"),

    /** The channel exists as a seam but the transport is not integrated yet. */
    NOT_INTEGRATED("not_integrated"),

    /** The ADB socket could not be opened (refused, unreachable, no route). */
    CONNECT_FAILED("connect_failed"),

    /** The socket opened but the CNXN/AUTH handshake did not complete. */
    HANDSHAKE_FAILED("handshake_failed"),

    /** The peer sent a message that violates the ADB framing (bad magic/length/state). */
    PROTOCOL_ERROR("protocol_error"),

    /** The device refuses this app's ADB key until it is registered in `adb_keys`. */
    PAIRING_REQUIRED("pairing_required")
}

/**
 * One bounded command outcome.
 *
 * [reason] is non-null exactly when the command did **not** produce a normal exit status: a
 * command that ran and exited non-zero has `reason == null` and a meaningful [exitCode]. Use
 * [succeeded] for the "ran and exited 0" question.
 */
data class DshDeviceCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val reason: DshDeviceCommandFailure? = null
) {
    val succeeded: Boolean get() = reason == null && exitCode == 0

    /** Stable code for display/telemetry; `null` when the command exited normally. */
    val reasonCode: String? get() = reason?.code

    companion object {
        /** The command was refused before execution; only a policy code is returned. */
        fun policyBlocked(policyDetail: String): DshDeviceCommandResult =
            DshDeviceCommandResult(
                exitCode = DSH_DEVICE_EXIT_NOT_EXECUTED,
                stdout = "",
                stderr = "[POLICY_BLOCKED] $policyDetail",
                reason = DshDeviceCommandFailure.POLICY_BLOCKED
            )

        /** The command could not run for a transport reason (never contains the command text). */
        fun failure(
            failure: DshDeviceCommandFailure,
            message: String,
            stdout: String = ""
        ): DshDeviceCommandResult =
            DshDeviceCommandResult(
                exitCode = DSH_DEVICE_EXIT_NOT_EXECUTED,
                stdout = stdout,
                stderr = "[${failure.code.uppercase()}] $message",
                reason = failure
            )
    }
}

/** One probe of a channel: its new status plus the bounded result of the probe command. */
data class DshDeviceProbe(
    val kind: DshDeviceChannelKind,
    val status: DshDeviceChannelStatus,
    /** `null` only when no command was run at all (for example a channel with no transport). */
    val result: DshDeviceCommandResult? = null
)

/**
 * One device-privilege transport.
 *
 * Implementations must run their work off the main thread (they are `suspend`), bound their output,
 * honour [timeoutMs] by destroying their process, and keep [status] honest. They must not log or
 * persist the command text; only [DshDeviceCommandResult.reasonCode] and byte counts are surfaced.
 */
interface DshDeviceChannel {
    val kind: DshDeviceChannelKind

    /** Last observed status. Cheap, non-blocking, never performs IO on the caller's thread. */
    fun status(): DshDeviceChannelStatus

    /**
     * Policy-gated command execution.
     *
     * A command rejected by [DshDeviceCommandPolicy] returns
     * [DshDeviceCommandFailure.POLICY_BLOCKED] without touching a shell.
     */
    suspend fun exec(
        command: String,
        timeoutMs: Long = DSH_DEVICE_COMMAND_TIMEOUT_MS
    ): DshDeviceCommandResult
}

/** Default command budget shared by both channels. */
const val DSH_DEVICE_COMMAND_TIMEOUT_MS: Long = 15_000L

/** Root probe budget: a root-manager prompt is short, a hung `su` must not freeze the console. */
const val DSH_DEVICE_PROBE_TIMEOUT_MS: Long = 5_000L

/** Per-stream output ceiling (256 KB) for every channel. */
const val DSH_DEVICE_OUTPUT_LIMIT_BYTES: Int = 256 * 1024

/** Exit code used when a command never ran. Matches the shell convention for "cannot execute". */
const val DSH_DEVICE_EXIT_NOT_EXECUTED: Int = -1
