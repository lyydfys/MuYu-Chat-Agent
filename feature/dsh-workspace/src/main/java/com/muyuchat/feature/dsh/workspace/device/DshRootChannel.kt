package com.muyuchat.feature.dsh.workspace.device

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** `id` prints `uid=<n>(<name>) …`; only a literal uid 0 counts as positive root proof. */
private val UID_ZERO = Regex("\\buid=0\\b")

/** Root-manager refusal wording; used only to degrade an already-READY channel, never to grant it. */
private val ROOT_DENIED = Regex("(?i)(permission denied|access denied|not allowed|denied by)")

private const val SU_BINARY = "su"
private const val PROBE_COMMAND = "id"

/**
 * What one `su -c id` probe observed. Pure input of [dshRootStatusFor], so the status ladder can be
 * unit-tested without a device.
 */
sealed interface DshRootProbeOutcome {

    /** `su` is not installed / cannot be spawned: the device is not rooted for this app. */
    data object SuMissing : DshRootProbeOutcome

    /** `su` did not answer inside the probe budget: a root prompt that was never answered. */
    data object TimedOut : DshRootProbeOutcome

    /** `su` answered; [stdout]/[stderr] are bounded and [exitCode] is the manager's verdict. */
    data class Exited(val exitCode: Int, val stdout: String, val stderr: String) : DshRootProbeOutcome
}

/**
 * The only place root status is decided.
 *
 * `READY` requires positive proof (`uid=0` in the `id` output), never "the command did not fail
 * loudly". An unanswerable prompt maps to [DshDeviceChannelStatus.NEEDS_GRANT], because the user
 * still has to grant it; a managed-but-broken `su` (exit 0 without uid 0) maps to
 * [DshDeviceChannelStatus.ERROR] instead of pretending the channel works.
 */
fun dshRootStatusFor(outcome: DshRootProbeOutcome): DshDeviceChannelStatus = when (outcome) {
    DshRootProbeOutcome.SuMissing -> DshDeviceChannelStatus.UNAVAILABLE
    DshRootProbeOutcome.TimedOut -> DshDeviceChannelStatus.NEEDS_GRANT
    is DshRootProbeOutcome.Exited -> when {
        outcome.exitCode == 0 && UID_ZERO.containsMatchIn(outcome.stdout) -> DshDeviceChannelStatus.READY
        outcome.exitCode == 0 -> DshDeviceChannelStatus.ERROR
        else -> DshDeviceChannelStatus.NEEDS_GRANT
    }
}

/**
 * Conservative degradation for an already-`READY` channel whose next command was refused.
 *
 * Only ever moves the status towards "needs grant", never towards `READY`: a revoked root grant must
 * stop the console from claiming the channel still works.
 */
fun dshRootDenialFromStderr(stderr: String): Boolean =
    ROOT_DENIED.containsMatchIn(stderr)

/**
 * Root channel: one `su -c` child per command, bounded output, hard timeout, no shell host.
 *
 * Ownership: this object owns no long-lived process. Every command spawns one `su` child that is
 * destroyed when it finishes, times out, trips the output ceiling or the caller is cancelled. The
 * channel keeps only the last observed [status].
 */
class DshRootChannel internal constructor(
    private val launcher: DshProcessLauncher,
    private val probeTimeoutMs: Long,
    private val io: CoroutineDispatcher = Dispatchers.IO
) : DshDeviceChannel {

    /** Production wiring: real `su` and the shared probe budget. */
    constructor() : this(
        launcher = DeviceSuProcessLauncher,
        probeTimeoutMs = DSH_DEVICE_PROBE_TIMEOUT_MS
    )

    override val kind: DshDeviceChannelKind = DshDeviceChannelKind.ROOT

    @Volatile
    private var cachedStatus: DshDeviceChannelStatus = DshDeviceChannelStatus.UNAVAILABLE

    override fun status(): DshDeviceChannelStatus = cachedStatus

    /**
     * Probes root with one bounded `su -c id` (the probe command is a constant of this class, not
     * user input, so it is not routed through the command policy).
     */
    suspend fun probe(): DshDeviceProbe {
        val run = runDshProcess(
            launcher = launcher,
            argv = listOf(SU_BINARY, "-c", PROBE_COMMAND),
            timeoutMs = probeTimeoutMs,
            dispatcher = io
        )
        val status = dshRootStatusFor(run.toProbeOutcome())
        cachedStatus = status
        return DshDeviceProbe(kind = kind, status = status, result = run.toProbeResult())
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
        if (cachedStatus != DshDeviceChannelStatus.READY) {
            val probed = probe()
            if (probed.status != DshDeviceChannelStatus.READY) {
                return notGranted(probed.status)
            }
        }
        val effectiveTimeout = if (timeoutMs > 0) timeoutMs else DSH_DEVICE_COMMAND_TIMEOUT_MS
        val run = runDshProcess(
            launcher = launcher,
            argv = listOf(SU_BINARY, "-c", DshDeviceCommandPolicy.renderSafe(allowed.segments)),
            timeoutMs = effectiveTimeout,
            dispatcher = io
        )
        if (run.spawnFailed) {
            cachedStatus = DshDeviceChannelStatus.UNAVAILABLE
            return DshDeviceCommandResult.failure(
                DshDeviceCommandFailure.EXEC_FAILED,
                message = "su could not be spawned"
            )
        }
        if (run.timedOut) {
            return DshDeviceCommandResult.failure(
                DshDeviceCommandFailure.TIMEOUT,
                message = "command did not finish inside ${effectiveTimeout}ms",
                stdout = run.stdout.text
            )
        }
        if (run.stdout.overflowed || run.stderr.overflowed) {
            return DshDeviceCommandResult.failure(
                DshDeviceCommandFailure.OUTPUT_LIMIT,
                message = "output exceeded $DSH_DEVICE_OUTPUT_LIMIT_BYTES bytes and was truncated",
                stdout = run.stdout.text
            )
        }
        if (run.exitCode != 0 && dshRootDenialFromStderr(run.stderr.text)) {
            // The grant was revoked between the probe and this command: report the truth and let the
            // next probe re-confirm it.
            cachedStatus = DshDeviceChannelStatus.NEEDS_GRANT
        }
        return DshDeviceCommandResult(
            exitCode = run.exitCode,
            stdout = run.stdout.text,
            stderr = run.stderr.text,
            reason = null
        )
    }

    private fun DshProcessRun.toProbeOutcome(): DshRootProbeOutcome = when {
        spawnFailed -> DshRootProbeOutcome.SuMissing
        timedOut -> DshRootProbeOutcome.TimedOut
        else -> DshRootProbeOutcome.Exited(exitCode, stdout.text, stderr.text)
    }

    private fun DshProcessRun.toProbeResult(): DshDeviceCommandResult = when {
        spawnFailed -> DshDeviceCommandResult.failure(
            DshDeviceCommandFailure.NOT_GRANTED,
            message = "su binary is not available"
        )

        timedOut -> DshDeviceCommandResult.failure(
            DshDeviceCommandFailure.TIMEOUT,
            message = "su did not answer inside ${probeTimeoutMs}ms"
        )

        else -> DshDeviceCommandResult(
            exitCode = exitCode,
            stdout = stdout.text,
            stderr = stderr.text,
            reason = null
        )
    }

    private fun notGranted(status: DshDeviceChannelStatus): DshDeviceCommandResult = when (status) {
        DshDeviceChannelStatus.UNAVAILABLE -> DshDeviceCommandResult.failure(
            DshDeviceCommandFailure.NOT_GRANTED,
            message = "root channel is unavailable (no su binary)"
        )

        DshDeviceChannelStatus.NEEDS_GRANT -> DshDeviceCommandResult.failure(
            DshDeviceCommandFailure.NOT_GRANTED,
            message = "root access has not been granted"
        )

        DshDeviceChannelStatus.ERROR -> DshDeviceCommandResult.failure(
            DshDeviceCommandFailure.EXEC_FAILED,
            message = "root probe returned an unusable su state"
        )

        DshDeviceChannelStatus.READY -> DshDeviceCommandResult.failure(
            DshDeviceCommandFailure.EXEC_FAILED,
            message = "root channel reported READY but was not"
        )
    }
}
