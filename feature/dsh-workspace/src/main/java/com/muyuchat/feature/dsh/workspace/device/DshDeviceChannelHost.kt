package com.muyuchat.feature.dsh.workspace.device

import android.content.Context
import java.io.File

/**
 * Which transport a privileged `/shell` call should use.
 *
 * Pure decision shared by the app-side fallback: an existing root/Shizuku channel always wins (that is
 * the verified path), ADB only takes over when the privileged channel is not usable, and `NONE` means
 * the caller must report an unavailable capability.
 */
enum class DshShellChannelChoice {
    PRIVILEGED,
    ADB,
    NONE
}

/** Pure channel choice: privileged first, then ADB, otherwise none. */
fun dshShellChannelChoice(privilegedReady: Boolean, adbReady: Boolean): DshShellChannelChoice = when {
    privilegedReady -> DshShellChannelChoice.PRIVILEGED
    adbReady -> DshShellChannelChoice.ADB
    else -> DshShellChannelChoice.NONE
}

/**
 * Detail for the "no channel" case: it must name both missing families so the console/tool result
 * explains the blocker instead of repeating only the root/Shizuku reason.
 */
fun dshShellUnavailableDetail(privilegedDetail: String): String =
    "root/Shizuku 与 ADB 都不可用：$privilegedDetail"

/**
 * Process-level owner of the device channels.
 *
 * Why a singleton: the agent's `/shell` route and the console must drive **one** ADB transport, one
 * RSA identity and one endpoint memory. Creating a second transport per page would open a second
 * socket, re-run the handshake and could show two different statuses for the same device.
 *
 * Ownership:
 *  - the host owns the identity file, the socket transport, the ADB channel, the root channel and the
 *    endpoint store for the process lifetime;
 *  - the console's ViewModel borrows `root`/`adb`/`provisioner` from here and must not close them
 *    (only the explicit "断开" intent closes the socket);
 *  - [adbShell] is the only command entry point for the agent path and it always passes
 *    [DshDeviceCommandPolicy] first;
 *  - [`of`] is idempotent and thread-safe: two calls return the same instance.
 */
class DshDeviceChannelHost internal constructor(
    val root: DshRootChannel,
    val adb: DshAdbChannel,
    val endpointStore: DshAdbEndpointStore,
    provisionerFactory: () -> DshAdbProvisioner
) {

    /** Built once: the provisioner reuses the same root channel (and therefore the same `su` probe). */
    val provisioner: DshAdbProvisioner by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { provisionerFactory() }

    /** Cheap, cached ADB status (no probe, no socket). */
    fun adbStatus(): DshDeviceChannelStatus = adb.status()

    /** Probes ADB; when it is connected this runs one policy-gated read-only command. */
    suspend fun adbSnapshot(): DshDeviceProbe = adb.probe()

    /**
     * Probes root.
     *
     * Suspending on purpose (the requested `rootProbe()` was synchronous): a root probe spawns `su`,
     * so a blocking variant could run on the main thread. The only caller is the suspend `/shell`
     * path, where this is free.
     */
    suspend fun rootProbe(): DshDeviceProbe = root.probe()

    /**
     * The agent-facing command path.
     *
     * Policy, the one-shot floating-window approval and canonical re-rendering all happen inside
     * [DshAdbChannel.exec]; this entry point only applies the caller's byte ceiling. Gating exactly once
     * matters: a second gate after an approval would either ask the user twice or refuse the very command
     * they just allowed.
     */
    suspend fun adbShell(
        command: String,
        timeoutMs: Long = DSH_DEVICE_COMMAND_TIMEOUT_MS,
        outputLimitBytes: Int = DSH_DEVICE_OUTPUT_LIMIT_BYTES
    ): DshDeviceCommandResult {
        val raw = adb.exec(command = command, timeoutMs = timeoutMs)
        return dshBoundDeviceResult(raw, outputLimitBytes)
    }

    /** Closes the shared transport (used by the explicit disconnect and by test teardown). */
    fun close() {
        runCatching { adb.close() }
    }

    companion object {

        private val lock = Any()

        private var instance: DshDeviceChannelHost? = null

        /** The process singleton. Idempotent and thread-safe. */
        fun of(context: Context): DshDeviceChannelHost = synchronized(lock) {
            instance ?: build(File(context.applicationContext.filesDir, KEY_DIRECTORY))
                .also { instance = it }
        }

        /**
         * Storage-scoped accessor, used by JVM tests that cannot construct an Android `Context`.
         * The first call wins, exactly like [of].
         */
        internal fun ofStorage(directory: File): DshDeviceChannelHost = synchronized(lock) {
            instance ?: build(directory).also { instance = it }
        }

        internal fun resetForTesting() {
            synchronized(lock) {
                instance?.close()
                instance = null
            }
        }

        private fun build(directory: File): DshDeviceChannelHost {
            val identityFile = File(directory, "adbkey.pkcs8")
            val root = DshRootChannel()
            val endpointStore = FileDshAdbEndpointStore(File(directory, "adb_endpoint"))
            return DshDeviceChannelHost(
                root = root,
                adb = DshAdbChannel(
                    transport = SocketAdbTransport(identityFile),
                    // Experimental pairing records the endpoint only after the device proves the exchange.
                    pairingPort = DshAdbPairingPort(saveEndpoint = endpointStore::saveEndpoint)
                ),
                endpointStore = endpointStore,
                provisionerFactory = { DshAdbProvisioner(root = root, identityFile = identityFile) }
            )
        }

        /** App-private directory holding the key and the remembered endpoint. */
        const val KEY_DIRECTORY: String = "dsh"
    }
}

/**
 * Applies the caller's byte ceiling to an already executed result.
 *
 * A truncation is only added when no stronger failure already explains the result; `exitCode` is kept
 * so the caller still sees the transport's verdict.
 */
internal fun dshBoundDeviceResult(
    result: DshDeviceCommandResult,
    limitBytes: Int
): DshDeviceCommandResult {
    val limit = if (limitBytes > 0) limitBytes else DSH_DEVICE_OUTPUT_LIMIT_BYTES
    val stdout = dshBoundOutput(result.stdout, limit)
    val stderr = dshBoundOutput(result.stderr, limit)
    if (!stdout.overflowed && !stderr.overflowed) {
        return if (stdout.text == result.stdout && stderr.text == result.stderr) {
            result
        } else {
            result.copy(stdout = stdout.text, stderr = stderr.text)
        }
    }
    return DshDeviceCommandResult(
        exitCode = result.exitCode,
        stdout = stdout.text,
        stderr = stderr.text,
        reason = result.reason ?: DshDeviceCommandFailure.OUTPUT_LIMIT
    )
}
