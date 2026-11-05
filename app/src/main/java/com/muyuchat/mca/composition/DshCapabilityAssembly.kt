package com.muyuchat.mca.composition

import android.content.Context
import android.provider.Settings
import android.content.Intent
import android.app.Activity
import android.util.Log
import com.muyuchat.core.dsh.a11y.android.DshA11yController
import com.muyuchat.core.dsh.android.AndroidDshActionsFactory
import com.muyuchat.core.dsh.android.DshAndroidActionErrorCode
import com.muyuchat.core.dsh.android.DshAndroidActionFailure
import com.muyuchat.core.dsh.android.DshAndroidActions
import com.muyuchat.core.dsh.android.DshPrivilegedChannelProvider
import com.muyuchat.core.dsh.android.DshShellRunResult
import com.muyuchat.core.dsh.android.DshShizukuShellTransport
import com.muyuchat.core.dsh.browser.DshBrowserBinderTransport
import com.muyuchat.core.dsh.browser.DshBrowserRouteAdapter
import com.muyuchat.core.dsh.contract.capability.DshCapability
import com.muyuchat.core.dsh.contract.capability.DshCapabilityAuthority
import com.muyuchat.core.dsh.contract.capability.DshCapabilityBridgeEndpoint
import com.muyuchat.core.dsh.contract.capability.DshCapabilityBridgeEndpointProvider
import com.muyuchat.core.dsh.contract.capability.DshCapabilityConsentDecision
import com.muyuchat.core.dsh.contract.capability.DshCapabilityConsentPort
import com.muyuchat.core.dsh.contract.capability.DshCapabilityConsentPrompt
import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteTable
import com.muyuchat.core.dsh.contract.capability.DshCapabilityState
import com.muyuchat.core.dsh.contract.console.DshConsoleCapabilityProjection
import com.muyuchat.core.dsh.platform.DshCapabilityLedger
import com.muyuchat.core.dsh.platform.DshCapabilityLoopbackServer
import com.muyuchat.core.dsh.platform.DshCapabilityRouteRegistry
import com.muyuchat.core.dsh.platform.DshCapabilityServerOptions
import com.muyuchat.core.dsh.platform.DshCapabilityThrottle
import com.muyuchat.core.dsh.platform.DshWorkspaceTreeGrantConsole
import com.muyuchat.core.dsh.platform.DshWorkspaceTreeGrantHost
import com.muyuchat.core.dsh.platform.DshWorkspaceTreeGrantOwner
import com.muyuchat.core.dsh.platform.DshWorkspaceTreePickerPort
import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import com.muyuchat.core.dsh.vscreen.AndroidDshVscreenDisplayPort
import com.muyuchat.core.dsh.vscreen.AndroidDshVscreenFrameSink
import com.muyuchat.core.dsh.vscreen.AndroidDshVscreenPreviewWindow
import com.muyuchat.core.dsh.vscreen.AndroidDshVscreenPrivilegedPort
import com.muyuchat.core.dsh.vscreen.DshPrivilegedChannelState
import com.muyuchat.core.dsh.vscreen.DshPrivilegedProcessRunner
import com.muyuchat.core.dsh.vscreen.DshShizukuDexProvisioner
import com.muyuchat.core.dsh.vscreen.DshShizukuShellChannel
import com.muyuchat.core.dsh.vscreen.DshVscreenCaptureService
import com.muyuchat.feature.dsh.workspace.device.DshDeviceChannelHost
import com.muyuchat.feature.dsh.workspace.device.DshDeviceChannelStatus
import com.muyuchat.feature.dsh.workspace.device.DshDeviceCommandFailure
import com.muyuchat.feature.dsh.workspace.device.DshDeviceCommandResult
import com.muyuchat.feature.dsh.workspace.device.DshShellChannelChoice
import com.muyuchat.feature.dsh.workspace.device.dshShellChannelChoice
import com.muyuchat.feature.dsh.workspace.device.dshShellUnavailableDetail
import com.muyuchat.core.dsh.vscreen.DshVscreenLimits
import com.muyuchat.core.dsh.vscreen.DshVscreenPreviewRelay
import com.muyuchat.core.dsh.vscreen.DshVscreenProjectionGrantResult
import com.muyuchat.core.dsh.vscreen.DshVscreenProjectionHolder
import com.muyuchat.core.dsh.vscreen.DshVscreenProof
import com.muyuchat.core.dsh.vscreen.DshVscreenRoutes
import com.muyuchat.core.dsh.vscreen.VirtualScreenOwner
import com.muyuchat.mca.agent.device.AgentDeviceAccessStore
import com.muyuchat.mca.agent.device.AgentDeviceCapabilityRoutes
import com.muyuchat.mca.agent.device.AgentDevicePermissionGate
import com.muyuchat.mca.agent.device.AgentSmsConfirmationPort
import com.muyuchat.mca.agent.device.sensors.AndroidAgentSensorReader
import com.muyuchat.mca.agent.device.sms.AndroidAgentSmsReader
import com.muyuchat.mca.dsh.overlay.DshOverlayController
import com.muyuchat.mca.dsh.overlay.DshOverlayHost
import com.muyuchat.mca.dsh.overlay.DshOverlayStatusProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-process owner of the authenticated DSH capability bridge (D15).
 *
 * The official mobile tool packages (`dsh-tool-android`, `-shizuku`, `-accessibility`, `-browser`)
 * call a loopback HTTP surface of the App. This assembly creates exactly one such surface per DSH
 * host generation: a fresh grant (`dshc_` token) per generation, the fixed route table built from
 * the typed per-domain handlers, and a loopback-only server bound to 127.0.0.1.
 *
 * Lifecycle contract:
 * - the runtime client asks [endpointProvider] before starting the host, so the bridge exists
 *   before Node starts and the token enters the child environment only;
 * - [stop] is called when the host generation is confirmed stopped (D01), revoking the token,
 *   closing live sockets and releasing the virtual display, preview window and capture grant;
 * - a failed route build yields a null endpoint instead of a half-wired bridge. The host still
 *   starts (the official frontend and desktop tools must work), and the mobile families report
 *   NOT_ENABLED rather than pretending to be available.
 *
 * Privileged lanes: `POST /shell` and the virtual-screen input/launch actions share one
 * [DshShizukuShellChannel] (real `rish` execution, uid 2000) with a root fallback for the shell
 * route. Virtual-display creation is not a shell capability: it needs the app's screen-capture
 * consent ([grantScreenCapture]), which the Activity owns. Neither lane reports green without a
 * real execution, and the capture grant is never logged or written to disk. The terminal lane is
 * not privileged either: its PTY children run as the App uid under
 * [DshCapability.WORKSPACE_TERMINAL], and it reports GRANTED only for a real PTY.
 *
 * Nothing here persists a token, cookie or user content. The one durable fact is the app-private
 * workspace-folder grant record (SAF URI, modes, grant/release times); a host stop keeps it.
 */
internal class DshCapabilityAssembly(
    context: Context,
    private val consent: DshCapabilityConsentPort = DshCapabilityConsentBroker.port,
    private val overlayStatus: DshOverlayStatusProvider = DshOverlayStatusProvider.Unavailable,
    /**
     * Typed projection of the one app screen-capture request/consent.
     *
     * The platform `MediaProjection` and its release are owned by this assembly; the Activity owns
     * the system dialog. This narrow dependency lets the real owner report releases and generation
     * teardown, so the console never projects a stale grant and a pending dialog is cancelled when
     * its generation is stopped. It is required: the app composition always constructs both.
     */
    private val screenCaptureConsent: DshScreenCaptureConsentController,
    /** User-presence seam of the persistent folder grant; the broker owns the Activity dialog. */
    private val workspaceTreePicker: DshWorkspaceTreePickerPort = DshWorkspaceTreePickerBroker.port,
    /**
     * The one maintenance admission gate shared with the maintenance owner. Every capability
     * request runs under a MUTATION lease, so an exclusive maintenance barrier and a tool call
     * can never overlap.
     */
    private val maintenanceGate: com.muyuchat.core.dsh.contract.capability.DshMaintenanceAdmissionGate? = null
) {

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val authority = DshCapabilityAuthority()
    private val ledger = DshCapabilityLedger()

    /** In-flight app-local tool executions; a real observation, never a guessed count. */
    fun pendingToolExecutions(): Int = ledger.stats().pending

    private val throttle = DshCapabilityThrottle()

    /**
     * The official web terminal polls `/terminal/read` every 40 ms (1500 requests/min) while a
     * shell is open; the general 120/min budget would answer 429 within the first second and the
     * seam would tear the session down. Terminal I/O therefore has its own bounded budget: still
     * authenticated, generation-scoped, rate- and concurrency-limited, but sized for polling.
     */
    private val terminalThrottle = DshCapabilityThrottle(
        maxRequestsPerWindow = 3_600,
        maxConcurrent = 2,
        cooldownMillis = 200L
    )

    private val terminalBudget: (String) -> DshCapabilityThrottle = { path ->
        if (path.startsWith("/terminal/")) terminalThrottle else throttle
    }
    private val channels: DshPrivilegedChannelProvider = DevicePrivilegeProbe(appContext)

    /**
     * Device-access consent (sensors/location/torch + read-only SMS), read from the shared
     * preferences the Settings card writes. Everything defaults to off; the routes never bypass it.
     */
    private val deviceAccessStore: AgentDeviceAccessStore by lazy { AgentDeviceAccessStore(appContext) }

    /** Runtime-permission probe of the device-access lane; `null` permission means "no grant needed". */
    private val deviceAccessPermissions: AgentDevicePermissionGate = AgentDevicePermissionGate { permission ->
        permission == null ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                appContext,
                permission
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * One confirmation per SMS query, shown through the one capability consent dialog. A denial or
     * a background (no resumed surface) answer is a refusal; the route reports CONFIRMATION_DENIED.
     */
    private val smsQueryConfirmation: AgentSmsConfirmationPort = AgentSmsConfirmationPort { detail ->
        consent.requestConsent(
            DshCapabilityConsentPrompt(
                title = "允许 DSH 只读查询短信？",
                detail = detail,
                capability = DshCapability.ANDROID_SYSTEM
            )
        ) == DshCapabilityConsentDecision.ALLOW
    }

    /**
     * The real privileged shell channels.
     *
     * The runner executes one child process under hard budgets; [shizukuChannel] is the `rish`
     * transport that proves itself with a real `id -u` through Shizuku (uid 2000). Both the vscreen
     * lane and the official `/shell` route share this one channel, so the app never runs two
     * privileged commands at the same time.
     */
    private val privilegedRunner = DshPrivilegedProcessRunner()

    private val shizukuDexProvisioner = DshShizukuDexProvisioner.forContext(appContext)

    private val shizukuChannel = DshShizukuShellChannel(
        runner = privilegedRunner,
        dexPath = { shizukuDexProvisioner.currentDexPath() },
        applicationId = appContext.packageName
    )

    /** App-owned screen-capture consent, created by the in-app consent flow and released on stop. */
    private val projectionHolder = DshVscreenProjectionHolder(appContext)

    private val previewWindow = AndroidDshVscreenPreviewWindow(appContext)

    private val previewRelay = DshVscreenPreviewRelay(window = previewWindow)

    /**
     * App-lifetime scope for the virtual-screen preview pump. The owner starts one child job per
     * RUNNING display and cancels it on every terminal transition; this scope itself holds no
     * long-running work once the owner is closed.
     */
    private val vscreenScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val vscreenOwnerLock = Any()

    private var vscreenOwnerInstance: VirtualScreenOwner? = null

    /**
     * D16 floating-window owner. The official `android_overlay` tool shows/hides the DSH whale
     * mark; the controller starts/stops its own service and reports the real window state. Without
     * the SYSTEM_ALERT_WINDOW grant it reports not running instead of pretending to show a window.
     *
     * The controller is the process-wide one owned by [DshOverlayHost], shared with the workspace
     * "App 外显示" switch: the controller holds the single window-bus slot, so a second instance
     * would silently steal the first one's callbacks. This assembly only lends its live runtime
     * projection (the panel's two lines) to that shared instance.
     */
    private val overlayController: DshOverlayController
        get() = DshOverlayHost.of(appContext).also { it.statusProvider = overlayStatus }.controller

    /**
     * The terminal service of the current generation, or null before one exists.
     *
     * Read through the lane's facts on every call so a generation stop is observed here instead of
     * being cached: the native terminal page must never write into a dead owner.
     */
    internal val terminalService: com.muyuchat.core.dsh.runtime.process.DshTerminalToolService?
        get() = runCatching<com.muyuchat.core.dsh.runtime.process.DshTerminalToolService?> {
            terminalLane.service()
        }.getOrNull()

    /** One terminal lane per capability generation; see [createDshTerminalLane]. */
    private val terminalLane: DshTerminalLane = createDshTerminalLane(
        context = appContext,
        maintenanceGate = maintenanceGate,
        generation = { authority.currentGrant()?.generation ?: 0L },
        log = { message -> Log.i(TAG_TERMINAL, message) },
        dataRootName = DSH_DATA_ROOT_NAME,
        terminalSeamAsset = TERMINAL_SEAM_ASSET,
        shellEnvContributorAsset = SHELL_ENV_CONTRIBUTOR_ASSET
    )

    @Volatile
    private var server: DshCapabilityLoopbackServer? = null

    /**
     * Scope of the one pending folder pick; the system dialog outlives a 15 s HTTP request, so the
     * owner starts it here and the client polls the status. [stop] cancels the pick only.
     */
    private val workspaceTreeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One owner per process, created on first bridge start or console projection. */
    private val workspaceTreeGrant: Lazy<DshWorkspaceTreeGrantOwner> =
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            createDshWorkspaceTreeGrantOwner(appContext, workspaceTreePicker, workspaceTreeScope)
        }

    /** True only while the authenticated loopback bridge is bound. */
    val isRunning: Boolean
        get() = server?.binding() != null

    @Volatile
    private var vscreenServer: DshCapabilityLoopbackServer? = null
    private var generation = 0L

    /**
     * VScreen owner shared by the fixed-port bridge. Created on first use so a device that never
     * asks for a virtual display pays no ImageReader/HandlerThread cost.
     */
    private fun vscreenOwner(): VirtualScreenOwner = synchronized(vscreenOwnerLock) {
        vscreenOwnerInstance ?: VirtualScreenOwner(
            displayPort = AndroidDshVscreenDisplayPort(
                projectionSource = projectionHolder,
                onDisplayCreated = { projectionHolder.recordDisplayCreated() }
            ),
            privilegedPort = AndroidDshVscreenPrivilegedPort(
                context = appContext,
                runner = privilegedRunner,
                shizuku = shizukuChannel,
                dexPath = { shizukuDexProvisioner.currentDexPath() },
                captureProbe = { projectionHolder.evidence() }
            ),
            frameSink = AndroidDshVscreenFrameSink(appContext),
            previewPort = previewRelay,
            previewScope = vscreenScope,
            previewPumpIntervalMillis = DshVscreenLimits.PREVIEW_PUMP_INTERVAL_MILLIS
        ).also { vscreenOwnerInstance = it }
    }

    private fun existingVscreenOwner(): VirtualScreenOwner? =
        synchronized(vscreenOwnerLock) { vscreenOwnerInstance }

    /**
     * The Shizuku half of `POST /shell`.
     *
     * The runner owns consent, timeout, output limits and channel selection; this adapter only maps
     * the typed outcome onto the shell contract. A dead or ungranted channel is an infrastructure
     * failure, never a silent non-zero exit.
     *
     * ADB fallback (additive): when the root/Shizuku channel is not usable and the process-level ADB
     * channel is READY, the same command is executed through [DshDeviceChannelHost] instead. The
     * channel choice is the pure [dshShellChannelChoice] function; a READY (or not-yet-classified)
     * privileged channel always keeps the existing path, so the verified root/Shizuku behaviour is
     * unchanged. The ADB branch runs through the same command policy, timeout and output ceiling.
     */
    private val shizukuShellTransport = DshShizukuShellTransport { invocation ->
        val execution = shizukuChannel.executeShell(
            shellCommand = invocation.command,
            timeoutMillis = invocation.timeoutMillis,
            stdoutLimit = invocation.stdoutLimit,
            stderrLimit = invocation.stderrLimit
        )
        val deviceHost = runCatching { DshDeviceChannelHost.of(appContext) }.getOrNull()
        val adbReady = deviceHost?.let {
            runCatching { it.adbStatus() == DshDeviceChannelStatus.READY }.getOrDefault(false)
        } ?: false
        val choice = dshShellChannelChoice(
            privilegedReady = execution.state == DshPrivilegedChannelState.READY ||
                execution.state == DshPrivilegedChannelState.UNKNOWN,
            adbReady = adbReady
        )
        if (choice == DshShellChannelChoice.ADB && deviceHost != null) {
            return@DshShizukuShellTransport deviceHost
                .adbShell(
                    command = invocation.command,
                    timeoutMs = invocation.timeoutMillis,
                    outputLimitBytes = invocation.stdoutLimit
                )
                .toShellRunResult()
        }
        DshShellRunResult(
            exitCode = execution.exitCode,
            stdout = execution.stdout,
            stderr = execution.stderr,
            stdoutTruncated = execution.stdoutTruncated,
            stderrTruncated = execution.stderrTruncated,
            failure = when {
                execution.state != DshPrivilegedChannelState.READY &&
                    execution.state != DshPrivilegedChannelState.UNKNOWN ->
                    DshAndroidActionFailure.of(
                        DshAndroidActionErrorCode.CAPABILITY_UNAVAILABLE,
                        // Name both missing families when ADB is not usable either.
                        dshShellUnavailableDetail(execution.detail)
                    )

                execution.timedOut ->
                    DshAndroidActionFailure.of(DshAndroidActionErrorCode.TIMEOUT, null)

                else -> null
            }
        )
    }

    /**
     * Maps the feature-side device result onto the privileged shell contract.
     *
     * Only a policy refusal or an SELinux refusal is a permission-shaped failure; transport problems
     * stay `CAPABILITY_UNAVAILABLE` and a truncation keeps the existing `stdoutTruncated` shape.
     */
    private fun DshDeviceCommandResult.toShellRunResult(): DshShellRunResult {
        val reasonCode = reason?.code
        val truncated = reasonCode == DshDeviceCommandFailure.OUTPUT_LIMIT.code
        val failure = when (reasonCode) {
            null -> null

            // Truncation keeps the existing `stdoutTruncated` shape instead of an infrastructure error.
            DshDeviceCommandFailure.OUTPUT_LIMIT.code -> null

            DshDeviceCommandFailure.POLICY_BLOCKED.code,
            DshDeviceCommandFailure.SELINUX_DENIED.code ->
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.PERMISSION_DENIED,
                    reasonCode
                )

            DshDeviceCommandFailure.TIMEOUT.code ->
                DshAndroidActionFailure.of(DshAndroidActionErrorCode.TIMEOUT, null)

            else -> DshAndroidActionFailure.of(
                DshAndroidActionErrorCode.CAPABILITY_UNAVAILABLE,
                reasonCode
            )
        }
        return DshShellRunResult(
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            stdoutTruncated = truncated,
            stderrTruncated = false,
            failure = failure
        )
    }

    /** Provider consumed by the runtime client before each host start. */
    val endpointProvider: DshCapabilityBridgeEndpointProvider =
        DshCapabilityBridgeEndpointProvider { endpointOrNull() }

    private suspend fun endpointOrNull(): DshCapabilityBridgeEndpoint? = mutex.withLock {
        server?.binding()?.let { binding -> return@withLock endpointOf(binding.port, binding.generation, binding.token.value) }

        val nextGeneration = generation + 1
        val routes = runCatching { buildRoutes(nextGeneration) }
            .onFailure { Log.w(TAG, "capability routes unavailable: ${it.javaClass.simpleName}") }
            .getOrNull()
        if (routes == null) {
            // A half-built generation must not leave a terminal owner (and its threads) running.
            runCatching { terminalLane.stop() }
            return@withLock null
        }

        generation = nextGeneration
        // The grant must exist before the server reads it for the new generation.
        val grant = authority.startGeneration(generation, CAPABILITIES)
        val created = DshCapabilityLoopbackServer(
            authority = authority,
            registry = routes,
            ledger = ledger,
            throttle = throttle,
            options = DshCapabilityServerOptions(),
            budgetFor = terminalBudget,
            maintenance = maintenanceGate
        )
        val binding = runCatching { created.start() }.getOrElse { failure ->
            Log.w(TAG, "capability loopback start failed: ${failure.javaClass.simpleName}")
            authority.revoke()
            // The authority advances its generation even when the start fails; the next attempt
            // must use a fresh, strictly larger generation instead of retrying the dead one.
            // The terminal owner of this attempt has no reachable routes, so it is stopped here.
            runCatching { terminalLane.stop() }
            return@withLock null
        }
        server = created
        // The official dsh-tool-vscreen package hardcodes 127.0.0.1:8999 and authenticates with
        // APP_LOCAL_TOKEN, so that family cannot ride the ephemeral App-local port. A second
        // generation-scoped server binds the fixed port with the same grant and the /vscreen
        // routes. If 8999 is occupied the family stays unavailable; the frontend still starts.
        vscreenServer = runCatching { startVscreenServer() }
            .onFailure {
                Log.w(TAG, "vscreen bridge 8999 unavailable: ${it.javaClass.simpleName}")
            }
            .getOrNull()
        Log.i(
            TAG,
            "capability bridge READY generation=${grant.generation} port=${binding.port} " +
                "routes=${routes.paths().size} vscreen=${vscreenServer != null}"
        )
        endpointOf(binding.port, binding.generation, binding.token.value)
    }

    private suspend fun startVscreenServer(): DshCapabilityLoopbackServer {
        // 8999 is a process-wide port, but an assembly instance is per Activity/host build. A
        // second instance would otherwise hit BindException while the previous generation's
        // server kept answering with its dead token (device evidence 2026-10-25: READY vscreen=false
        // with a stale listener holding 8999). The registry makes the latest start own the port.
        stopStaleProcessVscreenServer()
        val table = DshVscreenRoutes.install(DshCapabilityRouteTable.builder(), vscreenOwner()).build()
        val builder = DshCapabilityRouteRegistry.builder()
        mount(
            builder = builder,
            table = table,
            capabilityOf = { DshCapability.VIRTUAL_SCREEN },
            methods = setOf(DshCapabilityHttpMethod.GET)
        )
        val created = DshCapabilityLoopbackServer(
            authority = authority,
            registry = builder.build(),
            ledger = ledger,
            throttle = throttle,
            options = DshCapabilityServerOptions(bindPort = VSCREEN_PORT),
            maintenance = maintenanceGate
        )
        created.start()
        synchronized(VSCREEN_PROCESS_LOCK) { processVscreenServer = created }
        return created
    }

    private suspend fun stopStaleProcessVscreenServer() {
        val stale = synchronized(VSCREEN_PROCESS_LOCK) {
            val current = processVscreenServer
            processVscreenServer = null
            current
        }
        stale?.let { server ->
            runCatching { server.stop() }
                .onFailure { Log.w(TAG, "stale vscreen server stop failed: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * Read-only capability projection for the D20 console (no token, no path).
     * A family is GRANTED only while its handlers are actually mounted in this process.
     */
    fun consoleCapabilities(): List<DshConsoleCapabilityProjection> {
        val bridgeRunning = server != null
        val vscreenRunning = vscreenServer != null
        // Package/binary presence is only a candidate. The console may claim GRANTED only after a
        // real Shizuku `id -u` (or a successful later shell command) proved uid 2000; a root `su`
        // file is reported as unproven until the first privileged command succeeds.
        val shizukuProven = runCatching { shizukuChannel.lastProbeAvailable() }.getOrNull() == true
        val rootCandidate = runCatching { channels.rootAvailable() }.getOrDefault(false)
        // ADB counts only after a real CNXN handshake: `adbStatus()` is the channel's own READY state,
        // never a "configured" guess.
        val adbChannelReady = runCatching {
            DshDeviceChannelHost.of(appContext).adbStatus() == DshDeviceChannelStatus.READY
        }.getOrDefault(false)
        val overlayGranted = runCatching { Settings.canDrawOverlays(appContext) }.getOrDefault(false)
        val captureGranted = isScreenCaptureGranted()
        val a11yRunning = runCatching { DshA11yController.instance.isRunning }.getOrDefault(false)
        val previewState = previewRelay.state()
        return listOf(
            DshConsoleCapabilityProjection(
                capability = DshCapability.ANDROID_SYSTEM,
                state = if (bridgeRunning) DshCapabilityState.GRANTED else DshCapabilityState.NOT_ENABLED,
                toolRegistered = bridgeRunning,
                detail = if (bridgeRunning) {
                    "App-local: clipboard/notification/usage/overlay/setting; " +
                        "package/app/screenshot/input use the payload's own root/Shizuku path; " +
                        AgentDeviceCapabilityRoutes.summaryDetail(deviceAccessStore) +
                        " (GET /sensors /sms, POST /sensors/torch)"
                } else {
                    "App-local bridge is not running"
                }
            ),
            DshConsoleCapabilityProjection(
                capability = DshCapability.PRIVILEGED_SHELL,
                state = when {
                    !bridgeRunning -> DshCapabilityState.NOT_ENABLED
                    shizukuProven -> DshCapabilityState.GRANTED
                    // The ADB fallback really is a working /shell channel, so it is GRANTED too —
                    // but only after its handshake reached READY.
                    adbChannelReady -> DshCapabilityState.GRANTED
                    else -> DshCapabilityState.NOT_ENABLED
                },
                toolRegistered = bridgeRunning,
                detail = when {
                    !bridgeRunning -> "App-local bridge is not running"
                    shizukuProven -> "Shizuku answered uid 2000 in this process"
                    adbChannelReady -> "ADB channel is connected (no root/Shizuku)"
                    rootCandidate -> "su binary present but not probed; the first /shell command proves it"
                    else -> "no proven root or Shizuku channel yet; /shell probes on first use"
                }
            ),
            DshConsoleCapabilityProjection(
                capability = DshCapability.ACCESSIBILITY,
                state = if (a11yRunning) DshCapabilityState.GRANTED else DshCapabilityState.NOT_ENABLED,
                toolRegistered = bridgeRunning,
                detail = if (a11yRunning) null else "accessibility service is not enabled"
            ),
            DshConsoleCapabilityProjection(
                capability = DshCapability.VIRTUAL_SCREEN,
                state = if (vscreenRunning && captureGranted) {
                    DshCapabilityState.GRANTED
                } else {
                    DshCapabilityState.NOT_ENABLED
                },
                toolRegistered = vscreenRunning,
                detail = when {
                    !vscreenRunning -> "virtual-screen bridge (8999) is not running"
                    !captureGranted ->
                        "screen capture needs the in-app consent before android_vscreen_create"
                    previewState.hasFrame -> "preview received a frame from the virtual display"
                    !previewState.windowAvailable -> "preview window needs SYSTEM_ALERT_WINDOW"
                    else -> "waiting for the first virtual-display frame"
                }
            ),
            DshConsoleCapabilityProjection(
                capability = DshCapability.BROWSER,
                state = if (bridgeRunning && overlayGranted) {
                    DshCapabilityState.GRANTED
                } else {
                    DshCapabilityState.NOT_ENABLED
                },
                toolRegistered = bridgeRunning,
                detail = when {
                    !bridgeRunning -> "App-local bridge is not running"
                    !overlayGranted -> "browser window needs SYSTEM_ALERT_WINDOW"
                    else -> null
                }
            ),
            // Terminal: GRANTED only when the routes are mounted AND the transport is a real PTY.
            // A pipe fallback is reported with its concrete reason; it is never shown as a working
            // terminal (no isatty, no resize, no tty ^C).
            terminalRow(bridgeRunning, terminalLane.facts()),
            // Persistent SAF workspace folder. Only a live persisted permission is GRANTED; a
            // file-chooser pick or a download is reported nowhere as tree access.
            DshWorkspaceTreeGrantConsole.project(
                status = workspaceTreeGrant.value.status(),
                toolRegistered = bridgeRunning
            )
        )
    }

    private fun terminalRow(
        bridgeRunning: Boolean,
        facts: DshTerminalLaneFacts
    ): DshConsoleCapabilityProjection = DshConsoleCapabilityProjection(
        capability = DshCapability.WORKSPACE_TERMINAL,
        state = if (bridgeRunning && facts.providesRealTerminal) {
            DshCapabilityState.GRANTED
        } else {
            DshCapabilityState.NOT_ENABLED
        },
        toolRegistered = bridgeRunning,
        detail = if (bridgeRunning) facts.consoleDetail() else "App-local bridge is not running"
    )

    /**
     * Stops the current bridge generation, revokes its token, and releases the virtual-screen
     * resources of this process. Idempotent.
     *
     * Release order matters: live terminal children first (while their routes are still reachable
     * and their stop facts can be read), then the loopback servers, then the virtual display (so
     * no frame pump is reading), then the preview window and the capture notification, and
     * finally the projection grant itself. A failed release is logged and retained in
     * [terminalFacts], not hidden; the next start creates a fresh generation.
     */
    suspend fun stop() {
        // The bounded SIGTERM -> SIGKILL ladder runs before the socket is closed so an unsettled
        // stop is reported with real survivor ids instead of a generic "stop timed out".
        val terminalShutdown = runCatching { terminalLane.stop() }
            .onFailure { Log.w(TAG_TERMINAL, "terminal lane stop raised: ${it.javaClass.simpleName}") }
            .getOrNull()
        if (terminalShutdown != null && !terminalShutdown.settled) {
            Log.w(
                TAG_TERMINAL,
                "terminal stop UNSETTLED survivors=${terminalShutdown.survivors}"
            )
        }
        val current = mutex.withLock {
            val existing = server
            val existingVscreen = vscreenServer
            server = null
            vscreenServer = null
            existing to existingVscreen
        }
        current.first?.let { existing ->
            runCatching { existing.stop() }
                .onFailure { Log.w(TAG, "capability loopback stop failed: ${it.javaClass.simpleName}") }
        }
        current.second?.let { existing ->
            synchronized(VSCREEN_PROCESS_LOCK) {
                if (processVscreenServer === existing) processVscreenServer = null
            }
            runCatching { existing.stop() }
                .onFailure { Log.w(TAG, "vscreen loopback stop failed: ${it.javaClass.simpleName}") }
        }
        // Cancel a picker that belonged to the stopped generation; the grant is released only
        // by the explicit owner.release().
        if (workspaceTreeGrant.isInitialized()) workspaceTreeGrant.value.onHostStopped()
        authority.revoke()
        existingVscreenOwner()?.let { owner ->
            runCatching { owner.close() }
                .onFailure { Log.w(TAG, "virtual-screen close failed: ${it.javaClass.simpleName}") }
        }
        runCatching { previewWindow.close() }
        releaseScreenCapture()
        // A consent dialog that is still open belongs to the generation being torn down: cancel it
        // so a late system result is ignored instead of arming a projection for a dead host.
        screenCaptureConsent.onHostStopped()
    }

    /**
     * Starts the mediaProjection foreground service.
     *
     * Called by [grantScreenCapture] after the system result and before `getMediaProjection`.
     * The caller must be off the main thread: the service's [Service.onStartCommand] callback runs
     * on the main thread and must be allowed to call `startForeground` before the caller waits for
     * [DshVscreenCaptureService.awaitForeground]. Idempotent.
     */
    fun prepareScreenCapture(): Boolean = DshVscreenCaptureService.start(appContext)

    /**
     * Read-only fact for the console consent projection: true only while the platform projection
     * of this app process is still live. It carries no token, Intent or path.
     */
    fun isScreenCaptureGranted(): Boolean = runCatching { projectionHolder.isGranted() }.getOrDefault(false)

    /**
     * Accepts a screen-capture consent result and arms the virtual display.
     *
     * The Activity owns the consent dialog (`MediaProjectionManager.createScreenCaptureIntent()`)
     * and hands its result here. The mediaProjection foreground service must be *foreground*
     * before `getMediaProjection` on Android 14+, so [prepareScreenCapture] is called first and
     * this method waits for the service's `startForeground` callback. **This method must not run on
     * the main thread**: the service callback is delivered there, so a main-thread wait would
     * deadlock and the platform would kill the pending start.
     *
     * A cancelled dialog or a missing result Intent is not a grant: the prepared capture foreground
     * service is stopped here so a cancelled request leaves no orphan notification, and `false` is
     * returned. Callers therefore keep the capability at `consent_required`.
     *
     * Returns true only when a real projection was granted.
     */
    fun grantScreenCapture(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) {
            // Cancelled / no payload: the system will not hand over a projection. Release the
            // foreground service started by prepareScreenCapture() before the dialog.
            releaseScreenCapture()
            return false
        }
        if (projectionHolder.isGranted()) return true
        if (!prepareScreenCapture()) {
            Log.w(TAG, "screen-capture foreground service refused; no projection granted")
            return false
        }
        if (!DshVscreenCaptureService.awaitForeground(5_000)) {
            Log.w(TAG, "screen-capture foreground service did not become foreground; no projection granted")
            releaseScreenCapture()
            return false
        }
        return when (val result = projectionHolder.grant(resultCode, data)) {
            is DshVscreenProjectionGrantResult.Granted -> {
                Log.i(TAG, "screen-capture projection granted generation=${result.generation}")
                true
            }

            is DshVscreenProjectionGrantResult.Rejected -> {
                Log.w(TAG, "screen-capture projection rejected: ${result.code.wire}")
                releaseScreenCapture()
                false
            }
        }
    }

    /** Revokes the capture grant, stops the projection and stops the capture notification. */
    fun releaseScreenCapture() {
        runCatching { projectionHolder.release() }
        DshVscreenCaptureService.stop(appContext)
        // The console projection follows the real owner: the grant is gone even when the reason
        // was a failed start/restart and the runtime never published STOPPED.
        screenCaptureConsent.onCaptureReleased()
    }

    private fun endpointOf(port: Int, generation: Long, token: String): DshCapabilityBridgeEndpoint =
        DshCapabilityBridgeEndpoint(
            appLocalPort = port,
            // The accessibility routes are mounted in the same authenticated server, so the
            // official package's APP_A11Y_PORT is the same loopback port. The service reports
            // NOT_ENABLED while the user has not enabled the accessibility service.
            accessibilityPort = port,
            generation = generation,
            localToken = token,
            rootAvailable = channels.rootAvailable(),
            shizukuAvailable = channels.shizukuAvailable(),
            // The pinned package launches its own rish loader from these two values; MCA now
            // provisions the very same loader, so the Node side gets a real, readable path.
            shizukuAppId = appContext.packageName,
            shizukuDexPath = shizukuDexProvisioner.currentDexPath(),
            approvalAsk = true
        )

    /**
     * Builds the fixed route registry from the installed domain handlers. Only families whose
     * handler table was actually built are granted; a missing module narrows the grant instead of
     * exposing a route that cannot run.
     *
     * [capabilityGeneration] scopes the terminal lane: the App-local bridge generation and the
     * terminal owner are the same lifetime, so a stopped/restarted host never reuses a closed PTY
     * owner.
     */
    private fun buildRoutes(capabilityGeneration: Long): DshCapabilityRouteRegistry {
        val androidActions: DshAndroidActions = AndroidDshActionsFactory.create(
            context = appContext,
            overlayController = overlayController,
            channels = channels,
            consent = consent,
            shizukuShellTransport = shizukuShellTransport
        )
        val builder = DshCapabilityRouteRegistry.builder()
        val androidTable = DshCapabilityRouteTable.builder()
            .also(androidActions::registerInto)
            .build()
        mount(
            builder = builder,
            table = androidTable,
            capabilityOf = { path ->
                if (path == "/shell") DshCapability.PRIVILEGED_SHELL else DshCapability.ANDROID_SYSTEM
            }
        )
        mount(
            builder = builder,
            table = DshA11yController.instance.routes(),
            capabilityOf = { DshCapability.ACCESSIBILITY }
        )
        // The official dsh-tool-browser posts a JSON envelope to /browser. The route forwards to
        // the isolated :browser process through its binder transport; the process holds the real
        // WebView and never shares the DSH WebView cookie jar.
        val browserTable = DshCapabilityRouteTable.builder()
            .route(
                DshBrowserRouteAdapter.ROUTE_PATH,
                DshBrowserRouteAdapter(DshBrowserBinderTransport(appContext))
            )
            .build()
        mount(
            builder = builder,
            table = browserTable,
            capabilityOf = { DshCapability.BROWSER },
            methods = setOf(DshCapabilityHttpMethod.POST)
        )
        // Terminal: the App process owns the only real PTY, so the official `terminal_*` tools
        // reach it through these POST routes. The family is deliberately NOT PRIVILEGED_SHELL:
        // a PTY is an unprivileged child of the App uid, not a root/Shizuku channel.
        val terminalRoutes = terminalLane.open(capabilityGeneration)
        if (terminalRoutes != null) {
            val mounted = mount(
                builder = builder,
                table = terminalRoutes,
                capabilityOf = { DshCapability.WORKSPACE_TERMINAL },
                methods = setOf(DshCapabilityHttpMethod.POST)
            )
            Log.i(TAG_TERMINAL, "terminal family mounted generation=$capabilityGeneration routes=$mounted")
        } else {
            // The bridge still starts for the other families; the console row reports why the
            // terminal family is not available instead of pretending it is mounted.
            Log.w(
                TAG_TERMINAL,
                "terminal family NOT mounted generation=$capabilityGeneration reason=" +
                    terminalLane.facts().unavailableReason
            )
        }
        // One owner, three typed tree routes; no chooser/save/download path is mounted here.
        mountWorkspaceTreeGrantRoutes(builder, workspaceTreeGrant.value)
        // Device-access lane (sensors/location/torch + read-only SMS). The capability enum is a
        // contract file this cut must not change, so the three routes are mounted under
        // ANDROID_SYSTEM and the console detail names them explicitly. Each path keeps its own
        // verb: the two reads are GET, the torch switch is POST.
        val deviceAccessTable = AgentDeviceCapabilityRoutes(
            consent = deviceAccessStore,
            permissions = deviceAccessPermissions,
            sensors = AndroidAgentSensorReader(appContext, deviceAccessStore),
            sms = AndroidAgentSmsReader(appContext),
            smsConfirmation = smsQueryConfirmation
        ).register(DshCapabilityRouteTable.builder()).build()
        AgentDeviceCapabilityRoutes.paths().forEach { path ->
            val handler = deviceAccessTable.handlerFor(path) ?: return@forEach
            builder.route(
                path = path,
                methods = AgentDeviceCapabilityRoutes.methodsFor(path),
                capability = DshCapability.ANDROID_SYSTEM,
                handler = handler
            )
        }
        return builder.build()
    }

    private fun mount(
        builder: DshCapabilityRouteRegistry.Builder,
        table: DshCapabilityRouteTable,
        capabilityOf: (String) -> DshCapability,
        methods: Set<DshCapabilityHttpMethod> = setOf(
            DshCapabilityHttpMethod.GET,
            DshCapabilityHttpMethod.POST
        )
    ): Int = mountCapabilityRoutes(
        builder = builder,
        table = table,
        capabilityOf = capabilityOf,
        methods = methods
    ).size

    /** Terminal lane projection for the console/diagnostics lane. Read-only; never opens anything. */
    fun terminalFacts(): DshTerminalLaneFacts = terminalLane.facts()

    /**
     * Advisory device privilege projection. The tool packages and the shell runner still prove
     * the channel at execution time; these flags only let DSH register a truthful roster.
     */
    private class DevicePrivilegeProbe(private val context: Context) : DshPrivilegedChannelProvider {
        override fun rootAvailable(): Boolean = SU_PATHS.any { File(it).isFile }

        override fun shizukuAvailable(): Boolean = runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrElse { false }
    }

    private companion object {
        const val TAG = "McaDshCapability"

        /** Process-wide owner of the fixed 8999 listener; latest assembly generation wins. */
        val VSCREEN_PROCESS_LOCK = Any()
        var processVscreenServer: DshCapabilityLoopbackServer? = null

        /** Separate log tag: terminal sessions are a distinct owner and are diagnosed separately. */
        const val TAG_TERMINAL = "McaDshTerminal"

        /** Same app-private root `:dsh` uses (`DshRuntimeService`/`DshRuntimeServiceClient`). */
        const val DSH_DATA_ROOT_NAME = "dsh"

        /**
         * The home-level JS seam packaged in `:core:dsh-runtime`. It is copied into the DSH home
         * by [DshTerminalLane]; the payload bytes are never patched.
         */
        const val TERMINAL_SEAM_ASSET = "dsh-seam/terminal-bridge.mjs"

        /** Home-level shell-env contributor; restores DSH_PAYLOAD_DIR for the bash dispatcher. */
        const val SHELL_ENV_CONTRIBUTOR_ASSET = "dsh-seam/shell-env-contributor.mjs"

        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /** Fixed in the official dsh-tool-vscreen package; cannot be made dynamic unilaterally. */
        const val VSCREEN_PORT = 8999

        val SU_PATHS = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su"
        )

        /**
         * Families this build can route. FILE_PICK/FILE_SAVE/NOTIFICATION/DEVICE_ACTION are
         * contract-only in this build (no V1 broker is mounted); the five mobile families are the
         * V2 surface, WORKSPACE_TERMINAL is the App-owned real PTY behind the official terminal
         * tools, and WORKSPACE_TREE is the one persistent SAF folder grant.
         */
        val CAPABILITIES = setOf(
            DshCapability.ANDROID_SYSTEM,
            DshCapability.PRIVILEGED_SHELL,
            DshCapability.ACCESSIBILITY,
            DshCapability.VIRTUAL_SCREEN,
            DshCapability.BROWSER,
            DshCapability.WORKSPACE_TERMINAL,
            DshCapability.WORKSPACE_TREE
        )
    }
}
