package com.muyuchat.core.dsh.vscreen

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Android implementation of the privileged port: one real root probe, one real Shizuku probe and
 * one real execution path per transport.
 *
 * Root: `su -c 'id -u'`. Shizuku: the `rish` loader ([DshShizukuShellChannel]) — both execute and
 * are judged by their real uid, never by an environment boolean. Input injection and app launching
 * replay the same typed command the pure factory built; the adapter only chooses the channel.
 *
 * The display half is not a shell capability (a shell uid cannot call `createVirtualDisplay`), so
 * the probe report carries separate capture evidence from the projection owner.
 */
class AndroidDshVscreenPrivilegedPort(
    private val applicationId: String,
    private val runner: DshPrivilegedProcessRunner,
    private val shizuku: DshShizukuShellChannel,
    private val dexPath: () -> String?,
    private val captureProbe: () -> DshVscreenCaptureEvidence,
    private val managerInstalled: () -> Boolean
) : DshVscreenPrivilegedPort {

    constructor(
        context: Context,
        runner: DshPrivilegedProcessRunner,
        shizuku: DshShizukuShellChannel,
        dexPath: () -> String?,
        captureProbe: () -> DshVscreenCaptureEvidence
    ) : this(
        applicationId = context.packageName,
        runner = runner,
        shizuku = shizuku,
        dexPath = dexPath,
        captureProbe = captureProbe,
        managerInstalled = { isShizukuManagerInstalled(context) }
    )

    override fun targets(): DshVscreenPrivilegedTargets = DshVscreenPrivilegedTargets(
        privilegedDexPath = dexPath(),
        shizukuAppId = applicationId
    )

    /**
     * Probes both shell channels concurrently and reads the display-source evidence.
     *
     * The two probes are independent child processes, so running them in parallel keeps one dead
     * channel from adding its full deadline to `android_vscreen_create` (the Shizuku loader waits up
     * to 5 s for its binder when the server is not running).
     */
    override suspend fun probe(): DshVscreenProbeReport = withContext(Dispatchers.IO) {
        coroutineScope {
            val root = async { probeRoot() }
            val shizukuEvidence = async { probeShizuku() }
            DshVscreenProbeReport(
                root = root.await(),
                shizuku = shizukuEvidence.await(),
                capture = captureProbe()
            )
        }
    }

    override suspend fun execute(command: DshVscreenPrivilegedCommand): DshVscreenCommandResult =
        withContext(Dispatchers.IO) {
            when (command.transport) {
                DshVscreenTransport.ROOT -> {
                    val outcome = runner.run(
                        argv = command.argv,
                        stdin = command.stdin,
                        timeoutMillis = command.timeoutMillis,
                        stdoutLimit = DshVscreenLimits.MAX_COMMAND_STDOUT_CHARS,
                        stderrLimit = DshVscreenLimits.MAX_COMMAND_STDERR_CHARS
                    )
                    DshVscreenCommandResult(
                        exitCode = outcome.exitCode,
                        stdout = outcome.stdout,
                        stderr = outcome.stderr,
                        timedOut = outcome.timedOut,
                        channelState = when {
                            !outcome.started -> DshPrivilegedChannelState.LOST
                            outcome.timedOut -> DshPrivilegedChannelState.LOST
                            else -> DshPrivilegedChannelState.READY
                        }
                    )
                }

                DshVscreenTransport.SHIZUKU -> {
                    val execution = shizuku.executeInvocation(
                        argv = command.argv,
                        environment = command.environment,
                        stdin = command.stdin,
                        timeoutMillis = command.timeoutMillis
                    )
                    DshVscreenCommandResult(
                        exitCode = execution.exitCode,
                        stdout = execution.stdout,
                        stderr = execution.stderr,
                        timedOut = execution.timedOut,
                        channelState = execution.state
                    )
                }

                DshVscreenTransport.NONE -> DshVscreenCommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "",
                    channelState = DshPrivilegedChannelState.UNKNOWN
                )
            }
        }

    private suspend fun probeRoot(): DshVscreenTransportEvidence {
        val outcome = runner.run(
            argv = listOf(SU_BINARY, "-c", ROOT_PROBE_COMMAND),
            timeoutMillis = ROOT_PROBE_TIMEOUT_MILLIS,
            stdoutLimit = PROBE_STDOUT_CHARS,
            stderrLimit = PROBE_STDERR_CHARS
        )
        val proven = outcome.started && !outcome.timedOut && outcome.exitCode == 0 &&
            outcome.stdout.trim() == ROOT_UID
        return DshVscreenTransportEvidence(
            transport = DshVscreenTransport.ROOT,
            shellProven = proven,
            shellDetail = if (proven) {
                "root shell probe returned uid $ROOT_UID"
            } else {
                "root shell probe did not return uid $ROOT_UID"
            }
        )
    }

    private suspend fun probeShizuku(): DshVscreenTransportEvidence {
        if (!managerInstalled()) {
            return DshVscreenTransportEvidence(
                transport = DshVscreenTransport.SHIZUKU,
                shellProven = false,
                shellDetail = "the Shizuku manager package is not installed"
            )
        }
        val probe = shizuku.probe()
        return DshVscreenTransportEvidence(
            transport = DshVscreenTransport.SHIZUKU,
            shellProven = probe.available,
            shellDetail = probe.detail
        )
    }

    private companion object {
        const val SU_BINARY = "su"
        const val ROOT_PROBE_COMMAND = "id -u"
        const val ROOT_UID = "0"
        const val ROOT_PROBE_TIMEOUT_MILLIS = 4_000L
        const val PROBE_STDOUT_CHARS = 64
        const val PROBE_STDERR_CHARS = 800
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        fun isShizukuManagerInstalled(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (failure: Exception) {
            false
        }
    }
}
