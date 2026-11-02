package com.muyuchat.core.dsh.runtime.launch

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.runtime.auth.DshLaunchEntry
import com.muyuchat.core.dsh.runtime.payload.DshPayloadInstallResult
import com.muyuchat.core.dsh.runtime.payload.DshPayloadProvisioner
import com.muyuchat.core.dsh.runtime.process.DshNodeCommand
import com.muyuchat.core.dsh.runtime.process.DshNodeExecStrategy
import com.muyuchat.core.dsh.runtime.process.DshNodeExecutable
import com.muyuchat.core.dsh.runtime.process.DshNodeHost
import com.muyuchat.core.dsh.runtime.readiness.DshReadinessGate
import com.muyuchat.core.dsh.runtime.startup.DshStartupStage
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** What one launch attempt produced. The host is already created and owned by the caller. */
internal sealed interface DshLaunchOutcome {
    /** The official surface reached its stable authenticated readiness for this generation. */
    data class Launched(
        val host: DshNodeHost,
        val entry: DshLaunchEntry,
        val ready: DshReadinessGate.Outcome.Ready
    ) : DshLaunchOutcome

    /**
     * The generation never became ready. [leftHost] is a process this attempt started and did not
     * stop: the owner must run its normal failure cleanup (stop + survivor scan + stop fence)
     * before publishing, because only the owner can raise the fence.
     */
    data class Failed(
        val code: DshFailureCode,
        val message: String,
        val retryable: Boolean,
        val diagnostics: String?,
        val leftHost: DshNodeHost? = null
    ) : DshLaunchOutcome

    /** A stop was requested while launching; the owner settles the tree on its stop path. */
    data object AbortedForStop : DshLaunchOutcome
}

/**
 * The one launch sequence of the single DSH owner, parameterised by [DshLaunchProfile].
 *
 * Responsibilities: prove the ABI/payload/Node executable, resolve the Node exec strategy once,
 * start the official Web profile inside the profile's workspace, capture the authenticated entry
 * and wait for the official readiness gate. It owns **no** lifecycle state: the caller holds the
 * operation mutex, the state machine, the host slot and the stop fence, and decides what a
 * successful launch means (READY for production, RUNNING-but-not-published for a trial).
 *
 * [onHostCreated] is invoked before `start`, so the caller's host slot always sees the process the
 * moment it can exist; a failure/abort path can therefore stop it like any other generation.
 */
internal class DshHostLauncher(
    private val ioDispatcher: CoroutineDispatcher,
    private val payload: DshPayloadProvisioner,
    private val execStrategy: DshNodeExecStrategy,
    private val readinessGate: DshReadinessGate,
    private val supportedAbis: List<String>,
    private val packagedAbis: List<String>,
    private val nativeLibraryDir: () -> File,
    private val nodeLibraryName: String,
    private val newNodeHost: () -> DshNodeHost,
    private val prepareRuntimeLinks: (DshNodeExecutable, File) -> Unit,
    private val nodeExecProbeTimeoutMillis: Long,
    private val authLineTimeoutMillis: Long,
    private val log: (String) -> Unit
) {

    /** One launch attempt: the profile plus everything that is per-generation. */
    data class Request(
        val profile: DshLaunchProfile,
        /** Complete child environment of this generation (already isolated to the profile roots). */
        val environment: Map<String, String>,
        val seed: suspend () -> DshSeedAttempt,
        val onHostCreated: (DshNodeHost) -> Unit = {},
        val onStage: (DshStartupStage) -> Unit = {},
        val shouldAbort: () -> Boolean = { false }
    )

    /**
     * The proven Node execution strategy. Resolving it runs a real `node --version` probe, so it is
     * cached for the lifetime of this owner exactly like the original inline implementation.
     */
    @Volatile
    private var execChoice: DshNodeExecStrategy.Choice? = null

    suspend fun launch(request: Request): DshLaunchOutcome {
        val profile = request.profile
        val abi = packagedAbis.firstOrNull { abi -> supportedAbis.any { it == abi } }
            ?: return DshLaunchOutcome.Failed(
                DshFailureCode.ABI_UNSUPPORTED,
                "the APK carries no Node runtime for the device ABIs $supportedAbis",
                retryable = false,
                diagnostics = "packaged=$packagedAbis"
            )

        // 1) Payload. Production installs/verifies; a trial executes the already verified tree and
        // must never install (or re-verify into) a second copy below its own data root.
        if (profile.verifyPayload) {
            log("${profile.logPrefix}verifying packaged payload (abi=$abi)")
            when (val installed = payload.ensureInstalled()) {
                is DshPayloadInstallResult.Missing -> return DshLaunchOutcome.Failed(
                    DshFailureCode.PAYLOAD_MISSING,
                    "the packaged DSH payload is missing from this APK build",
                    retryable = false,
                    diagnostics = installed.detail
                )

                is DshPayloadInstallResult.Integrity -> return DshLaunchOutcome.Failed(
                    DshFailureCode.PAYLOAD_INTEGRITY,
                    "the packaged DSH payload failed manifest/hash verification",
                    retryable = false,
                    diagnostics = installed.detail
                )

                is DshPayloadInstallResult.Installed -> log(
                    "${profile.logPrefix}payload ${installed.manifest.payloadVersion} " +
                        (if (installed.reused) "reused" else "installed") +
                        " archive=${installed.archiveSha256.take(12)}"
                )
            }
        } else {
            if (!profile.roots.payload.isDirectory || !profile.kernelEntry.isFile) {
                return DshLaunchOutcome.Failed(
                    DshFailureCode.PAYLOAD_MISSING,
                    "the verified installed payload tree is not available for the ${profile.hostNoun}",
                    retryable = false,
                    diagnostics = null
                )
            }
            log(
                "${profile.logPrefix}using the verified installed payload tree " +
                    "(no install, no link rewrite)"
            )
            if (profile.installPackagedPayload) {
                // Prepare the packaged payload for a future upgrade without ever gating this
                // generation: the closure tree below is what actually executes.
                when (val attempted = payload.ensureInstalled()) {
                    is DshPayloadInstallResult.Installed -> log(
                        "${profile.logPrefix}packaged payload ${attempted.manifest.payloadVersion} " +
                            (if (attempted.reused) "reused" else "installed") +
                            " for a future upgrade archive=${attempted.archiveSha256.take(12)}"
                    )
                    is DshPayloadInstallResult.Missing -> log(
                        "${profile.logPrefix}packaged payload install skipped: ${attempted.detail.take(120)}"
                    )
                    is DshPayloadInstallResult.Integrity -> log(
                        "${profile.logPrefix}packaged payload install refused: ${attempted.detail.take(120)}"
                    )
                }
            }
        }

        // 2) The packaged Node executable must exist before anything can be probed.
        val nodeLibrary = File(nativeLibraryDir(), nodeLibraryName)
        if (!nodeLibrary.isFile) {
            return DshLaunchOutcome.Failed(
                DshFailureCode.PAYLOAD_MISSING,
                "nativeLibraryDir does not contain $nodeLibraryName",
                retryable = false,
                diagnostics = "nativeLibraryDir=${nativeLibraryDir()}"
            )
        }

        // 3) Home seeding for this generation. The owner owns the policy (gateway binding, account
        // only, or the isolated official template) and returns a typed refusal.
        when (val seeded = request.seed()) {
            DshSeedAttempt.Seeded -> Unit
            is DshSeedAttempt.Refused -> return DshLaunchOutcome.Failed(
                DshFailureCode.GATEWAY_UNAVAILABLE,
                seeded.message,
                retryable = true,
                diagnostics = seeded.diagnostics
            )
        }
        if (request.shouldAbort()) return DshLaunchOutcome.AbortedForStop

        // 4) Prove the Node execution strategy on this device/target SDK.
        val choice = execChoice ?: withContext(ioDispatcher) {
            execStrategy.resolve(
                nodeLibrary = nodeLibrary,
                environment = request.environment,
                workingDir = profile.roots.workspace,
                timeoutMillis = nodeExecProbeTimeoutMillis
            )
        }
        if (choice == null) {
            val detail = withContext(ioDispatcher) {
                execStrategy.probeFailureDetail(
                    nodeLibrary,
                    request.environment,
                    profile.roots.workspace,
                    nodeExecProbeTimeoutMillis
                )
            }
            return DshLaunchOutcome.Failed(
                DshFailureCode.NODE_NOT_EXECUTABLE,
                "the packaged Node executable could not be started on this device",
                retryable = false,
                diagnostics = detail
            )
        }
        execChoice = choice
        log("${profile.logPrefix}node exec strategy=${choice.executable.strategy} version=${choice.nodeVersion}")
        if (profile.prepareCommandLinks) {
            runCatching { prepareRuntimeLinks(choice.executable, profile.roots.payload) }
                .onFailure { log("${profile.logPrefix}PATH node link not prepared: ${it.javaClass.simpleName}") }
        }

        for (dir in listOf(
            profile.roots.home,
            profile.roots.workspace,
            profile.roots.tmp,
            File(profile.roots.home, CONFIG_DIR_NAME)
        )) {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                return DshLaunchOutcome.Failed(
                    DshFailureCode.INTERNAL,
                    "cannot create the isolated DSH directory ${dir.name}",
                    retryable = false,
                    diagnostics = dir.absolutePath
                )
            }
        }
        if (request.shouldAbort()) return DshLaunchOutcome.AbortedForStop

        // 5) Launch the official Web profile and wait for its authenticated entry line.
        val createdHost = newNodeHost()
        request.onHostCreated(createdHost)
        val command = DshNodeCommand.arguments(
            choice.executable,
            profile.kernelEntry,
            profile.bindHost,
            profile.port
        )
        log("${profile.logPrefix}launching ${command.joinToString(" ")}")
        try {
            withContext(ioDispatcher) {
                // Run inside the profile's writable workspace, never inside the verified payload
                // tree: official sandbox-policy/fs-sandbox derive their roots from process.cwd(),
                // and a tool writing there could corrupt the hash-verified tree.
                createdHost.start(choice.executable, command, request.environment, profile.roots.workspace)
            }
        } catch (t: Throwable) {
            return DshLaunchOutcome.Failed(
                DshFailureCode.NODE_START_FAILED,
                "the ${profile.hostNoun} process could not be launched",
                retryable = true,
                diagnostics = t.javaClass.simpleName,
                leftHost = createdHost
            )
        }
        // Only a live child process may claim the first startup boundary. A launch that exits
        // immediately keeps the timeline empty and follows the existing NODE_START_FAILED path.
        if (createdHost.isAlive) request.onStage(DshStartupStage.NODE_PROCESS_ALIVE)

        val launch: DshLaunchEntry? = withContext(ioDispatcher) {
            createdHost.awaitLaunchEntry(authLineTimeoutMillis) {
                request.shouldAbort() || !createdHost.isAlive
            }
        }
        if (launch == null) {
            if (request.shouldAbort()) return DshLaunchOutcome.AbortedForStop
            val exited = createdHost.exitCode
            return DshLaunchOutcome.Failed(
                if (exited != null) DshFailureCode.NODE_START_FAILED else DshFailureCode.AUTH_ENTRY_MISSING,
                if (exited != null) {
                    "the ${profile.hostNoun} exited before printing its authenticated entry (code $exited)"
                } else {
                    "the official authenticated launch entry was not printed"
                },
                retryable = true,
                diagnostics = createdHost.tail(20),
                leftHost = createdHost
            )
        }
        log("${profile.logPrefix}authenticated entry captured for ${launch.origin}")
        if (request.shouldAbort()) return DshLaunchOutcome.AbortedForStop

        // 6) Official readiness gate: token exchange, injected index, client plugins, stability.
        val outcome = withContext(ioDispatcher) {
            readinessGate.awaitReady(
                entry = launch,
                onStage = request.onStage,
                shouldAbort = { request.shouldAbort() || !createdHost.isAlive }
            )
        }
        return when (outcome) {
            is DshReadinessGate.Outcome.Failed -> DshLaunchOutcome.Failed(
                outcome.code,
                outcome.message,
                retryable = outcome.code != DshFailureCode.LOADER_NOT_READY,
                diagnostics = outcome.diagnostics ?: createdHost.tail(20),
                leftHost = createdHost
            )

            is DshReadinessGate.Outcome.Ready -> DshLaunchOutcome.Launched(createdHost, launch, outcome)
        }
    }

    private companion object {
        /** `<DSH_HOME>/config`, the official config directory below the profile home. */
        const val CONFIG_DIR_NAME = "config"
    }
}
