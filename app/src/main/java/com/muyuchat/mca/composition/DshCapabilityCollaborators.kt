package com.muyuchat.mca.composition

import android.content.Context
import com.muyuchat.core.dsh.contract.capability.DshMaintenanceAdmissionGate
import com.muyuchat.core.dsh.platform.DshCapabilityTerminalSessionAdmission
import com.muyuchat.core.dsh.platform.DshWorkspaceTreeGrantHost
import com.muyuchat.core.dsh.platform.DshWorkspaceTreeGrantOwner
import com.muyuchat.core.dsh.platform.DshWorkspaceTreePickerPort
import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope

/**
 * Construction helpers of the capability assembly.
 *
 * They exist so [DshCapabilityAssembly] stays inside its file budget: each helper builds exactly
 * one collaborator from the same app context, and none of them owns state or policy. Ownership
 * stays with the assembly (one lane, one grant owner), and the terminal lane keeps its single
 * session-lifetime maintenance lease.
 */

/**
 * Terminal lane: the App process owns the only real PTY (`libdshpty.so`), so it also owns the
 * official terminal tool surface. One assembly per capability generation; the generation stop
 * closes every session with a bounded process-group ladder and keeps the real stop facts.
 *
 * The lane mounts nothing by itself: the assembly asks it for the route table and mounts it under
 * `WORKSPACE_TERMINAL`. A route therefore cannot create a second terminal runtime, and a bridge
 * generation without the native PTY reports the typed reason instead of offering a pipe terminal.
 *
 * It also refreshes the home-level JS seam (`seams/terminal-bridge/index.mjs` + one overlay row)
 * so the official `dsh-subprocess-local.spawnTerminal` can reach this PTY. The install is
 * idempotent; on a home that is not seeded yet (first run) it is a typed "pending" fact.
 */
internal fun createDshTerminalLane(
    context: Context,
    maintenanceGate: DshMaintenanceAdmissionGate?,
    generation: () -> Long,
    log: (String) -> Unit,
    dataRootName: String,
    terminalSeamAsset: String,
    shellEnvContributorAsset: String
): DshTerminalLane = DshTerminalLane(
    configProvider = {
        DshRuntimeConfig(
            File(context.filesDir, dataRootName),
            sandboxRunnerFile = File(context.applicationInfo.nativeLibraryDir, "libdshsandbox.so")
        ).validated()
    },
    nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
    seamSource = {
        context.assets.open(terminalSeamAsset)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    },
    shellEnvSource = {
        context.assets.open(shellEnvContributorAsset)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    },
    log = log,
    // A PTY outlives its opening request: hold the shared maintenance lease for the whole
    // session so an exclusive maintenance write can never overlap a live shell.
    sessionAdmission = maintenanceGate?.let { gate ->
        DshCapabilityTerminalSessionAdmission(gate, generation)
    }
)

/** One persistent folder-grant owner per process; created on first bridge start or console read. */
internal fun createDshWorkspaceTreeGrantOwner(
    context: Context,
    picker: DshWorkspaceTreePickerPort,
    scope: CoroutineScope
): DshWorkspaceTreeGrantOwner = DshWorkspaceTreeGrantHost(context, picker, scope).owner
