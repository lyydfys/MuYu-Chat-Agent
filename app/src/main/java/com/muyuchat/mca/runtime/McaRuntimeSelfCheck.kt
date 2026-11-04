package com.muyuchat.mca.runtime

import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import com.muyuchat.core.dsh.contract.runtime.RuntimeSelfCheckProbe

/**
 * App-side fact collector for the console's "运行状态自检" block.
 *
 * The row model lives in `core:dsh-contract` (`com.muyuchat.core.dsh.contract.runtime`) so the console
 * feature can render it without depending on this module; this class only fills the probe from the live
 * runtime. Every source is injected, so the mapping is unit-testable and `null` honestly means "not
 * probed" instead of a guessed failure.
 */
internal class McaRuntimeSelfCheckSource(
    private val snapshot: () -> DshRuntimeSnapshot? = { null },
    private val configuredPort: () -> Int = { 0 },
    private val portProbe: (Int) -> Boolean = { true },
    private val gatewayReachable: () -> Boolean? = { null },
    private val terminalPtyReady: () -> Boolean? = { null },
    private val configDirectoryReady: () -> Boolean? = { null }
) {
    fun probe(): RuntimeSelfCheckProbe {
        val current = snapshot()
        val port = current?.port() ?: configuredPort().takeIf { it > 0 }
        return RuntimeSelfCheckProbe(
            processRunning = when (current?.phase) {
                null -> null
                DshRuntimePhase.READY -> true
                DshRuntimePhase.FAILED, DshRuntimePhase.STOPPED -> false
                else -> null
            },
            webPort = port,
            webPortAvailable = port?.let(portProbe),
            gatewayHealthy = gatewayReachable(),
            terminalPtyReady = terminalPtyReady(),
            configDirectoryReady = configDirectoryReady()
        )
    }

    /** The port the runtime is actually serving, parsed from its loopback origin when available. */
    private fun DshRuntimeSnapshot.port(): Int? = origin
        ?.substringAfterLast(':')
        ?.substringBefore('/')
        ?.toIntOrNull()
        ?.takeIf { it in RuntimePortPlanner.MIN_PORT..RuntimePortPlanner.MAX_PORT }
}
