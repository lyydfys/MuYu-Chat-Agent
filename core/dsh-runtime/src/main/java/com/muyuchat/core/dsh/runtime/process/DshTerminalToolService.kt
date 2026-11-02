package com.muyuchat.core.dsh.runtime.process

import com.muyuchat.core.dsh.contract.capability.DshTerminalSessionAdmission
import com.muyuchat.core.dsh.contract.runtime.DshTerminalCapability
import com.muyuchat.core.dsh.contract.runtime.DshTerminalCloseOutcome
import com.muyuchat.core.dsh.contract.runtime.DshTerminalCloseReason
import com.muyuchat.core.dsh.contract.runtime.DshTerminalSize
import com.muyuchat.core.dsh.runtime.terminal.DshTerminalFactory
import com.muyuchat.core.dsh.runtime.terminal.DshTerminalSession

/**
 * Production facade between the official DSH terminal tools and one [DshTerminalHost].
 *
 * The official `terminal_*` tools are executed by the official Node kernel, which reaches the
 * Android execution world through the authenticated App-local capability bridge. This service is
 * the single object those bridge routes call; it owns:
 *
 *  - one [DshTerminalHost] (real PTY sessions, attach/reconnect, resize, Ctrl+C, background
 *    sessions and process-group stop) for the current host generation;
 *  - the generation owner identity (`dsh`), so a route can never address a session created by a
 *    different owner or generation;
 *  - the real command/install-chain verification ([DshToolchainVerifier]) with a short cache, so
 *    the seam can prove `node/npm/npx/pnpm/pip/python/git/curl/rg/bash` are executable *by
 *    execution*, not by the existence of a file or a `--version` guess (the verifier itself runs
 *    the real argv).
 *
 * It is deliberately Android-free and blocking: the HTTP route layer dispatches it on IO. The
 * class performs no logging of user terminal input or output.
 */
class DshTerminalToolService(
    val environment: DshToolchainEnvironment,
    val host: DshTerminalHost,
    private val verifier: DshToolchainVerifier = DshToolchainVerifier(DefaultDshProcessRunner()),
    private val ownerKey: String = DEFAULT_OWNER_KEY,
    private val requireRealPty: Boolean = true,
    private val toolchainTimeoutMillis: Long = DshToolchainVerifier.DEFAULT_TIMEOUT_MILLIS,
    private val toolchainTtlMillis: Long = DEFAULT_TOOLCHAIN_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val toolchainLock = Any()
    private var cachedToolchain: DshToolchainReport? = null
    private var cachedAtMillis = 0L

    /** What the platform really offers for terminals in this generation. */
    fun capability(): DshTerminalCapability = host.capability()

    /** Default transport policy applied when a caller omits `requirePty`. */
    val requiresRealPty: Boolean get() = requireRealPty

    /** The owner every session of this service belongs to. A route cannot choose another one. */
    fun owner(): String = ownerKey

    fun list(): List<DshTerminalSnapshot> = host.list(ownerKey)

    fun open(
        type: String = DshTerminalHost.SHELL_TYPE,
        name: String? = null,
        cwd: String? = null,
        size: DshTerminalSize = DshTerminalSize.DEFAULT,
        requireRealPty: Boolean = this.requireRealPty
    ): DshTerminalOpenOutcome = host.open(
        ownerKey = ownerKey,
        type = type,
        name = name,
        cwd = cwd,
        size = size,
        requireRealPty = requireRealPty
    )

    fun attach(
        sessionId: String,
        size: DshTerminalSize? = null,
        maxBytes: Int = DshTerminalHost.DEFAULT_READ_BYTES
    ): DshTerminalReadOutcome = host.attach(ownerKey, sessionId, size, maxBytes)

    fun read(
        sessionId: String,
        offset: Long,
        maxBytes: Int = DshTerminalHost.DEFAULT_READ_BYTES
    ): DshTerminalReadOutcome = host.read(ownerKey, sessionId, offset, maxBytes)

    fun write(sessionId: String, text: String, submit: Boolean = true): DshTerminalWriteOutcome =
        host.write(ownerKey, sessionId, text, submit)

    fun control(sessionId: String, control: DshTerminalControl): DshTerminalWriteOutcome =
        host.control(ownerKey, sessionId, control)

    fun resize(sessionId: String, size: DshTerminalSize): DshTerminalResizeOutcome =
        host.resize(ownerKey, sessionId, size)

    fun close(
        sessionId: String,
        reason: DshTerminalCloseReason = DshTerminalCloseReason.CLIENT_CLOSED
    ): DshTerminalCloseOutcome? = host.close(ownerKey, sessionId, reason)

    /**
     * Verifies the complete command closure by executing it. Results are cached for
     * [toolchainTtlMillis]; failures are cached for the same window so a broken install cannot be
     * spammed into the log while it stays broken. Never converts a failure into success.
     */
    fun verifyToolchain(force: Boolean = false): DshToolchainReport {
        synchronized(toolchainLock) {
            val now = clock()
            val cached = cachedToolchain
            if (!force && cached != null && now - cachedAtMillis in 0..toolchainTtlMillis) {
                return cached
            }
            val report = verifier.verify(
                model = environment.closureModel(),
                environment = environment.childEnvironment(),
                workingDir = environment.workspaceDir,
                timeoutMillis = toolchainTimeoutMillis
            )
            cachedToolchain = report
            cachedAtMillis = now
            return report
        }
    }

    /** Last verification without running anything; null when never verified in this generation. */
    fun lastToolchainReport(): DshToolchainReport? = synchronized(toolchainLock) { cachedToolchain }

    /**
     * Generation stop: closes every terminal session and returns the real stop facts. Repeated
     * calls are idempotent because the host's records are removed on close.
     */
    fun stopGeneration(
        graceMillis: Long = DshTerminalSession.DEFAULT_GRACE_MILLIS,
        killMillis: Long = DshTerminalSession.DEFAULT_KILL_MILLIS
    ): DshTerminalHostShutdown =
        host.closeAll(DshTerminalCloseReason.SERVICE_STOPPED, graceMillis, killMillis)

    companion object {
        const val DEFAULT_OWNER_KEY = "dsh"
        const val DEFAULT_TOOLCHAIN_TTL_MILLIS = 60_000L

        /** Production wiring used by the App composition: real PTY, real payload, real probes. */
        fun create(
            environment: DshToolchainEnvironment,
            connection: DshTerminalHostConnection = DshTerminalHostConnection.REQUIRED,
            maxSessions: Int = DshTerminalHost.DEFAULT_MAX_SESSIONS,
            retainedOutputBytes: Int = DshTerminalHost.DEFAULT_RETAINED_BYTES,
            log: (String) -> Unit = {},
            /** Held for each session's lifetime; the app adapts its shared maintenance gate here. */
            sessionAdmission: DshTerminalSessionAdmission? = null
        ): DshTerminalToolService {
            val factory = DshTerminalFactory(
                allowPipeFallback = connection == DshTerminalHostConnection.PIPE_FALLBACK_ALLOWED
            )
            val host = DshTerminalHost(
                factory = factory,
                modelProvider = { environment.closureModel() },
                environmentProvider = { environment.childEnvironment() },
                workingDirectoryProvider = { environment.workingDirectory() },
                maxSessions = maxSessions,
                retainedOutputBytes = retainedOutputBytes,
                log = log,
                sessionAdmission = sessionAdmission
            )
            return DshTerminalToolService(
                environment = environment,
                host = host,
                requireRealPty = connection == DshTerminalHostConnection.REQUIRED
            )
        }
    }
}

/**
 * How strict the bridge is about the transport for the official terminal tools.
 *
 * The default is [REQUIRED]: a missing `libdshpty.so` or a denied PTY open yields a typed
 * refusal, so the official terminal cannot silently run with pipe semantics (no isatty, no
 * resize, no job control). A caller may explicitly opt into [PIPE_FALLBACK_ALLOWED] for a
 * diagnostic surface; the transport is still reported so the UI and the seam can label it.
 */
enum class DshTerminalHostConnection {
    REQUIRED,
    PIPE_FALLBACK_ALLOWED
}
