package com.muyuchat.core.dsh.runtime.process

import com.muyuchat.core.dsh.contract.capability.DshTerminalSessionAdmission
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteTable
import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import java.io.File

/**
 * Production composition seam for the DSH terminal surface.
 *
 * The App process owns the capability bridge, so it also owns this assembly: it derives every
 * path from the same [DshRuntimeConfig] the host generation uses (payload, home, workspace, tmp),
 * receives the real `nativeLibraryDir`, builds exactly one [DshTerminalToolService], and exposes
 * the fixed route table plus the generation-stop call.
 *
 * This is deliberately the only place that knows how the terminal domain is wired. The App
 * composition needs three calls:
 *
 * ```kotlin
 * val terminals = DshTerminalAssembly(config, File(appInfo.nativeLibraryDir), nodeNeedsLinker)
 * // in DshCapabilityAssembly.buildRoutes(): mount(terminals.routes(), capability)
 * // in generation stop:                            terminals.stop()
 * ```
 *
 * No second terminal runtime can be created by a route: the service, host, verifier and route
 * table are one object graph.
 */
class DshTerminalAssembly(
    private val config: DshRuntimeConfig,
    nativeLibraryDir: File,
    nodeNeedsLinker: Boolean = false,
    connection: DshTerminalHostConnection = DshTerminalHostConnection.REQUIRED,
    log: (String) -> Unit = {},
    /** Held per session; the app adapts its shared maintenance gate through this seam. */
    sessionAdmission: DshTerminalSessionAdmission? = null
) {

    /** The real execution world the probes and the terminal child share with the Node host. */
    val environment: DshToolchainEnvironment = DshToolchainEnvironment(
        payloadDir = config.payloadDir,
        nativeLibraryDir = nativeLibraryDir,
        runtimeLibraryDir = config.runtimeLibraryDir(),
        payloadBinDir = config.payloadBinDir(),
        homeDir = config.homeDir,
        workspaceDir = config.workspaceDir,
        configDir = config.configDir,
        tmpDir = config.tmpDir,
        nodeNeedsLinker = nodeNeedsLinker
    )

    /** Single owner of this generation's terminal sessions and toolchain evidence. */
    val service: DshTerminalToolService = DshTerminalToolService.create(
        environment = environment,
        connection = connection,
        log = log,
        sessionAdmission = sessionAdmission
    )

    /** App-local route table for the official terminal tool bridge. */
    fun routes(): DshCapabilityRouteTable = DshTerminalToolRoutes.table(service)

    /** Verifies the command/install closure by execution; safe to call repeatedly (cached). */
    fun verifyToolchain(force: Boolean = false): DshToolchainReport = service.verifyToolchain(force)

    /**
     * Closes every session with a bounded process-group stop and returns the real stop facts.
     * Must be called when the capability bridge generation is stopped, before the next generation
     * may start; an unsettled result must not be reported as a clean stop.
     */
    fun stop(
        graceMillis: Long = config.stopGraceMillis,
        killMillis: Long = config.stopKillMillis
    ): DshTerminalHostShutdown = service.stopGeneration(graceMillis, killMillis)

    /** Bounded, secret-free diagnostics for the console. */
    fun diagnostics(): String = buildString {
        append("transport=").append(service.capability().transport.name)
        append(" sessions=").append(service.list().size)
        service.lastToolchainReport()?.let { append(" ").append(it.summary()) }
    }
}
