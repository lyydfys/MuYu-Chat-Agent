package com.muyuchat.core.dsh.runtime.terminal

import com.muyuchat.core.dsh.contract.runtime.DshTerminalCapability
import com.muyuchat.core.dsh.contract.runtime.DshTerminalOpenRequest
import com.muyuchat.core.dsh.contract.runtime.DshTerminalSize
import com.muyuchat.core.dsh.contract.runtime.DshTerminalUnavailableReason
import com.muyuchat.core.dsh.runtime.process.DshCommandClosure
import com.muyuchat.core.dsh.runtime.process.DshCommandClosureModel
import com.muyuchat.core.dsh.runtime.process.DshCommandLaunch
import com.muyuchat.core.dsh.runtime.process.DshCommandRole

/** A shell command built from the real closure, or the concrete reason it is unavailable. */
sealed interface DshTerminalShellChoice {
    data class Ready(
        val role: DshCommandRole,
        val launch: DshCommandLaunch,
        val request: DshTerminalOpenRequest,
        /** Always false in MCA: the payload bash wrapper is a `/system/bin/sh` shim. */
        val gnuBash: Boolean = false
    ) : DshTerminalShellChoice

    data class Unavailable(val detail: String) : DshTerminalShellChoice
}

/** Result of opening the shell for a terminal workspace. */
sealed interface DshTerminalSessionOpen {
    data class Opened(
        val choice: DshTerminalShellChoice.Ready,
        val session: DshTerminalSession
    ) : DshTerminalSessionOpen

    data class Unavailable(val detail: String) : DshTerminalSessionOpen
}

/**
 * Single entry point for opening a DSH terminal.
 *
 * Selection order is explicit and honest:
 *  1. real PTY (`libdshpty.so`) -> [com.muyuchat.core.dsh.contract.runtime.DshTerminalTransport.REAL_PTY];
 *  2. when the caller allows it and the native primitive is absent or failed -> pipe fallback,
 *     reported as PIPE_FALLBACK together with the exact missing condition;
 *  3. otherwise a typed failure. There is no invented "terminal".
 *
 * The shell comes from [DshCommandClosure] over real payload/nativeLibraryDir facts, so `bash` is
 * only ever offered as a sh-compatible shell (`gnuBash = false`).
 */
class DshTerminalFactory(
    private val ptyBridge: DshPtyBridge = DshNativePtyBridge(),
    private val allowPipeFallback: Boolean = true,
    private val pipeOpener: (DshTerminalOpenRequest) -> DshTerminalOpenResult = { request ->
        DshPipeTerminalPort.open(request)
    }
) {

    /** What terminals can do in this build, without opening one. */
    fun capability(): DshTerminalCapability = when (val availability = ptyBridge.availability()) {
        is DshPtyAvailability.Loaded -> DshTerminalCapability.RealPty(availability.nativeLibrary)
        is DshPtyAvailability.Unavailable -> if (allowPipeFallback) {
            DshTerminalCapability.PipeFallback(availability.reason, availability.detail)
        } else {
            DshTerminalCapability.Unavailable(availability.reason, availability.detail)
        }
    }

    /** Opens an exact argv on the best available transport. */
    fun open(request: DshTerminalOpenRequest): DshTerminalOpenResult =
        when (val availability = ptyBridge.availability()) {
            is DshPtyAvailability.Loaded ->
                when (val opened = ptyBridge.open(
                    request.command,
                    request.environment,
                    request.workingDirectory,
                    request.size
                )) {
                    is DshTerminalOpenResult.Opened -> opened
                    is DshTerminalOpenResult.Failed -> pipeFallback(request, opened.reason, opened.detail)
                }

            is DshPtyAvailability.Unavailable -> pipeFallback(request, availability.reason, availability.detail)
        }

    /**
     * Builds the shell command from the closure, opens it, and returns the owned session.
     * The caller owns the returned [DshTerminalSession] and must close or abandon it.
     */
    fun openShell(
        model: DshCommandClosureModel,
        workingDirectory: String,
        environment: Map<String, String>,
        size: DshTerminalSize = DshTerminalSize.DEFAULT,
        arguments: List<String> = listOf("-i")
    ): DshTerminalSessionOpen {
        val choice = chooseShell(model, workingDirectory, environment, size, arguments)
        if (choice is DshTerminalShellChoice.Unavailable) {
            return DshTerminalSessionOpen.Unavailable(choice.detail)
        }
        val ready = choice as DshTerminalShellChoice.Ready
        return when (val opened = open(ready.request)) {
            is DshTerminalOpenResult.Opened -> DshTerminalSessionOpen.Opened(
                choice = ready,
                session = DshTerminalSession(opened.port, initialSize = ready.request.size)
            )

            is DshTerminalOpenResult.Failed ->
                DshTerminalSessionOpen.Unavailable("${opened.reason.name}: ${opened.detail}")
        }
    }

    /** Shell selection only; no process is started. */
    fun chooseShell(
        model: DshCommandClosureModel,
        workingDirectory: String,
        environment: Map<String, String>,
        size: DshTerminalSize = DshTerminalSize.DEFAULT,
        arguments: List<String> = listOf("-i")
    ): DshTerminalShellChoice {
        val resolved = DshCommandClosure.resolve(model)
        val candidate = resolved[DshCommandRole.BASH]?.takeIf { it.executable }
            ?: resolved[DshCommandRole.SHELL]
            ?: return DshTerminalShellChoice.Unavailable("no shell role resolved from the payload closure")
        val launch = candidate.launch
        if (launch is DshCommandLaunch.Blocked) {
            return DshTerminalShellChoice.Unavailable(
                "shell unavailable: ${launch.reason} (${launch.detail})"
            )
        }
        val argv = try {
            DshCommandClosure.argv(launch, arguments)
        } catch (t: IllegalStateException) {
            return DshTerminalShellChoice.Unavailable(t.message ?: "shell argv cannot be built")
        }
        return DshTerminalShellChoice.Ready(
            role = candidate.role,
            launch = launch,
            request = DshTerminalOpenRequest(
                command = argv,
                workingDirectory = workingDirectory,
                environment = environment,
                size = size
            )
        )
    }

    private fun pipeFallback(
        request: DshTerminalOpenRequest,
        reason: DshTerminalUnavailableReason,
        detail: String
    ): DshTerminalOpenResult {
        if (!allowPipeFallback) {
            return DshTerminalOpenResult.Failed(reason, detail)
        }
        return when (val opened = pipeOpener(request)) {
            is DshTerminalOpenResult.Opened -> opened
            is DshTerminalOpenResult.Failed -> DshTerminalOpenResult.Failed(
                reason,
                "real pty unavailable (${reason.name}: $detail); pipe fallback failed too: ${opened.detail}"
            )
        }
    }
}
