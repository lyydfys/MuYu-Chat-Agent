package com.muyuchat.mca.composition

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import com.muyuchat.api.dsh.gateway.DshGatewayBackend
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.auth.DshAccountLoginPort
import com.muyuchat.core.dsh.contract.console.DshAccountAuthUrlMatcher
import com.muyuchat.core.dsh.contract.console.DshConsolePort
import com.muyuchat.core.dsh.contract.console.DshExternalBrowserPort
import com.muyuchat.core.dsh.contract.console.DshMaintenanceConsolePort
import com.muyuchat.core.dsh.maintenance.DshTrialLaunchPort
import com.muyuchat.core.dsh.runtime.auth.DshAccountLoginOwner
import com.muyuchat.core.dsh.contract.console.DshPluginAdminPort
import com.muyuchat.core.dsh.runtime.config.DshMcpDeclarationStore
import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import com.muyuchat.core.dsh.runtime.plugin.DshPluginAdmin
import com.muyuchat.core.dsh.runtime.config.DshShellSandboxStatusStore
import com.muyuchat.core.dsh.runtime.state.DshInFlightChannel
import com.muyuchat.core.dsh.runtime.state.DshInFlightEvidence
import com.muyuchat.mca.account.createDshAccountLoginOwner
import com.muyuchat.mca.dsh.overlay.DshOverlayHost
import com.muyuchat.mca.dsh.overlay.DshOverlayStatus
import com.muyuchat.mca.dsh.overlay.DshOverlayStatusProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope

/** Same app-private DSH root used by the runtime owner and capability assembly. */
private const val DSH_DATA_ROOT_NAME = "dsh"

/**
 * Composition boundary of the independent DSH host mode (D00-D27).
 *
 * It connects the app-process half of the official DSH runtime for one application process:
 * the loopback model gateway, the authenticated capability bridge, the maintenance owner and
 * console ports, the DeepSeek account lane, the WebView first-frame seam and the `:dsh` runtime
 * client. MainRuntimeAssembly only creates this object; it no longer knows how the DSH parts are
 * wired to each other.
 *
 * Ownership rules preserved from the previous inline wiring:
 * - exactly one runtime owner: the `:dsh` service client is owned here and nowhere else;
 * - every costly dependency is lazy, so a user who never opens DSH pays no binder, server or
 *   service cost — except [screenCaptureConsent], which is a tiny Android-free state object the
 *   shell must be able to observe before any runtime exists;
 * - a failed start/stop/teardown releases the same generation's gateway and bridge (D01/D07), and
 *   a stop that is not confirmed is never treated as a release;
 * - the account lane owns correlation only; the official module keeps the grant and the exchange.
 *
 * [gatewayBackend] is a provider, not an instance: the model adapter behind it is only built when
 * the gateway first starts, preserving the previous lazy cost profile.
 */
internal class DshHostAssembly(
    private val application: Application,
    private val scope: CoroutineScope,
    private val gatewayBackend: () -> DshGatewayBackend
) {
    /**
     * One loopback model gateway per DSH host generation. It is a protocol adapter over the same
     * engine/facade/cloud store the app already owns; it never creates a second engine, facade,
     * OkHttp client or credential store.
     */
    private val gatewayCreated = java.util.concurrent.atomic.AtomicBoolean(false)
    private val capabilityCreated = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * The one maintenance admission gate of this process. The maintenance owner takes the
     * exclusive barrier; every capability bridge request holds a MUTATION lease on this same
     * instance, so a maintenance write and a live tool call are mutually exclusive.
     */
    private val maintenanceGate = com.muyuchat.core.dsh.platform.DshCapabilityMaintenanceAdmissionGate()

    private val gatewayAssembly: DshGatewayAssembly by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DshGatewayAssembly(
            // The app-outside display observes the replies of this same backend. The decorator only
            // reads the assistant text of a turn boundary and forwards it to the overlay owner; it
            // adds no runtime, no port and no second model path.
            backend = DshOverlayHost.of(application).observeGateway(gatewayBackend()),
            toolEvidence = {
                DshInFlightEvidence.Observed(
                    DshInFlightChannel.TOOL_EXECUTION,
                    capabilityAssembly.pendingToolExecutions()
                )
            }
        ).also { gatewayCreated.set(true) }
    }

    /**
     * App-local authenticated capability bridge for the official mobile tool packages (D15).
     *
     * The bridge starts only when the DSH host asks for its endpoint before a generation start.
     * It shares the host generation lifetime and is revoked on a confirmed stop/restart.
     */
    private val capabilityAssembly: DshCapabilityAssembly by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DshCapabilityAssembly(
            context = application,
            // The floating overlay panel reads the live runtime projection; the provider is only
            // invoked after this assembly exists, so the lazy runtime client has no cycle here.
            overlayStatus = DshOverlayStatusProvider {
                val snapshot = runtimeClient.snapshot.value
                DshOverlayStatus.of(
                    runtimePhase = snapshot.phase.name,
                    generation = snapshot.epoch,
                    engineOnline = snapshot.isReady,
                    capabilitySummary = null
                )
            },
            // The real capture owner reports releases and generation teardown to the consent
            // projection, so the console can never keep a stale grant or a dead pending request.
            screenCaptureConsent = screenCaptureConsent,
            maintenanceGate = maintenanceGate
        ).also { capabilityCreated.set(true) }
    }

    /**
     * App-owned screen-capture consent request projected by the console (D15/D20).
     *
     * Created eagerly: it is a tiny Android-free state object the shell and the Activity observe
     * without constructing any DSH runtime dependency. FAILED/STOPPING are never treated as a
     * release by the controller itself: only the real owner's release and its `stop()` do that.
     */
    internal val screenCaptureConsent: DshScreenCaptureConsentController =
        DshScreenCaptureConsentController(
            // Read-only reconciliation with the real platform grant; the probe is only invoked
            // while the stored phase is GRANTED (an unused grant expires after its TTL).
            isGrantLive = { capabilityAssembly.isScreenCaptureGranted() }
        )

    /** The consent transaction shares the real capability owner and remains cheap to construct. */
    internal val screenCaptureConsentOwner = DshScreenCaptureConsentOwner<Intent>(
        consent = screenCaptureConsent,
        grantCapture = ::grantScreenCapture,
        grantedResultCode = Activity.RESULT_OK,
        cancelledResultCode = Activity.RESULT_CANCELED,
    )

    /**
     * Maintenance owner for DSH data (D21-D24). It runs the startup sweep before the first host
     * start and exposes typed intents to the DSH console; it never starts a second host.
     */
    private val maintenanceAssembly: DshMaintenanceAssembly by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DshMaintenanceAssembly(
            filesDir = application.filesDir,
            runtime = runtimeClient,
            gatewayReady = { gatewayAssembly.isRunning },
            bridgeReady = { capabilityAssembly.isRunning },
            nativeLibrary = { File(application.applicationInfo.nativeLibraryDir, "libdshnode.so") },
            expectedArchiveSha256 = { packagedArchiveSha256() },
            expectedPayloadVersion = { packagedPayloadVersion() },
            expectedFileCount = { packagedPayloadFileCount() },
            installedAbi = {
                Build.SUPPORTED_ABIS.firstOrNull()
            },
            codecHelperSource = {
                runCatching {
                    application.assets.open("dsh-maintenance/session-v4-codec.mjs")
                        .bufferedReader(Charsets.UTF_8).use { it.readText() }
                }.getOrNull()
            },
            trialLaunchPort = { trialLaunchPort },
            // Managed repair inventory + verified source: exactly the payload files this build's
            // manifest declares, re-materialised from the APK's own payload archive.
            payloadManifestJson = { packagedPayloadManifestJson() },
            payloadArchiveOpen = {
                runCatching { application.assets.open("dsh/payload.zip") }.getOrNull()
            },
            gate = maintenanceGate
        )
    }

    /** Shares one exported diagnostic bundle through the app's narrow FileProvider grant. */
    private val diagnosticsSharer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DshDiagnosticsSharer(application)
    }

    /**
     * D23 maintenance console port: metadata-only projections and typed intents delegated to the
     * maintenance owner. Mutating intents require a confirmed-stopped host; nothing writes data in
     * this adapter.
     */
    /**
     * The one MCP declaration file the DSH seeder reads (`<DSH_HOME>/mcp-servers.json`). The console
     * exposes an import action over the same validation-preserving store; it never writes a second
     * format and never touches the official overlay directly.
     */
    private val mcpDeclarations: DshMcpDeclarationStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DshMcpDeclarationStore(File(application.filesDir, "dsh/home"))
    }

    private val maintenanceConsoleAdapter: DshMaintenanceConsoleAdapter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DshMaintenanceConsoleAdapter(
            dataRoot = maintenanceAssembly.dataRoot,
            runtime = runtimeClient,
            maintenance = { maintenanceAssembly.owner },
            appVersion = runCatching {
                application.packageManager.getPackageInfo(application.packageName, 0).versionName
            }.getOrNull() ?: "unknown",
            // The assembly is both the managed-repair source and the transaction owner; the sharer
            // is the only component that exposes the exported bundle outside the app.
            managedRepair = maintenanceAssembly,
            // Read-only durable trial journal so the console can surface a live/unknown trial even
            // when the upgrade state file is absent; only phase + candidate token are projected.
            trialJournal = { maintenanceAssembly.activeTrial() },
            shareDiagnostics = { file -> diagnosticsSharer.share(file) },
            mcpDeclarations = mcpDeclarations
        )
    }

    /** Typed D23 maintenance console entry point. */
    internal val maintenanceConsolePort: DshMaintenanceConsolePort get() = maintenanceConsoleAdapter

    /**
     * Read-only D20 console port. It projects the runtime, the capability bridge and the
     * maintenance admission owner, and forwards typed intents to them.
     */
    /**
     * In-app plugin administration.
     *
     * It runs the *packaged* pnpm CLI inside the official profile, and refuses while a generation is
     * live so `node_modules` is never rewritten under a running loader. Nothing here touches the
     * official plugin manager's files directly.
     */
    private val pluginAdminPort: DshPluginAdminPort by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DshPluginAdmin(
            config = DshRuntimeConfig(dataRoot = File(application.filesDir, DSH_DATA_ROOT_NAME)),
            nativeLibraryDir = File(application.applicationInfo.nativeLibraryDir),
            runtimeIsLive = {
                when (runtimeClient.snapshot.value.phase) {
                    DshRuntimePhase.STARTING,
                    DshRuntimePhase.READY,
                    DshRuntimePhase.STOPPING -> true
                    DshRuntimePhase.STOPPED,
                    DshRuntimePhase.FAILED -> false
                }
            }
        )
    }

    /** Console-facing seam for the plugin block; the owner above stays the only mutator. */
    val pluginAdmin: DshPluginAdminPort get() = pluginAdminPort

    private val consolePort: DshConsolePortAdapter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DshConsolePortAdapter(
            runtime = runtimeClient,
            capabilities = { capabilityAssembly.consoleCapabilities() },
            maintenance = maintenanceAssembly,
            screenCapture = screenCaptureConsent,
            firstFrame = { modeAssembly.firstFrameObserved },
            modelBound = { gatewayAssembly.catalogSize > 0 },
            // D20 account row: bounded attempt observation only (never a login claim).
            accountAttempt = { accountLoginOwner.attemptView() },
            // Seeder-owned fact; absent/unknown must render as "not probed", not "isolated".
            sandboxStatus = {
                DshShellSandboxStatusStore.read(File(application.filesDir, DSH_DATA_ROOT_NAME))
            }
        )
    }

    /** Typed D20 console entry point. */
    internal val console: DshConsolePort get() = consolePort

    /** Ordinary external-link transport; the account lane delegates non-account popups here. */
    private val plainExternalBrowserPort: DshExternalBrowserPort by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidDshExternalBrowserPort(application)
    }

    /**
     * D04 account-login lane: correlates the trusted pre-opened popup, the live official authorize
     * URL, the system-browser handoff, the durable attempt marker and typed terminal states
     * (cancel / timeout / process restart / 401 / runtime stop). With no live attempt nothing is
     * classified as an account popup, so ordinary links keep the existing browser behavior.
     */
    private val accountLoginOwner: DshAccountLoginOwner by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createDshAccountLoginOwner(
            context = application,
            scope = scope,
            runtime = runtimeClient,
            externalFallback = plainExternalBrowserPort
        )
    }

    internal val externalBrowserPort: DshExternalBrowserPort get() = accountLoginOwner
    internal val accountAuthUrlMatcher: DshAccountAuthUrlMatcher get() = accountLoginOwner
    internal val accountLoginPort: DshAccountLoginPort get() = accountLoginOwner

    /**
     * The independent official-DSH mode: one app-side runtime client bound to the dedicated
     * `:dsh` service.
     *
     * The `Lazy` handle is retained so [runtimePhaseOrNull] can answer "is DSH running?" without
     * creating the binder for a user who never opened DSH.
     */
    private val modeAssemblyLazy: Lazy<DshModeAssembly> = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DshModeAssembly(
            context = application,
            gateway = gatewayAssembly,
            endpointProvider = gatewayAssembly.endpointProvider,
            capabilityBridge = capabilityAssembly,
            beforeStart = { maintenanceAssembly.startupAdmission() },
            onHostReady = { maintenanceAssembly.confirmStartedUpgrade() },
            inFlight = gatewayAssembly.inFlightObservation,
            hostBuild = { "mca-app" },
            scope = scope
        ).also { assembly ->
            // A committed closure keeps serving its payload across app restarts; the selector is
            // the maintenance owner's durable record, so the workspace start path reads it here.
            assembly.activeClosurePayload = { maintenanceAssembly.activeClosurePayload() }
        }
    }

    private val modeAssembly: DshModeAssembly by modeAssemblyLazy

    /** Single app-facing runtime client for AppTab.DSH_WORKSPACE. */
    internal val runtimeClient get() = modeAssembly.runtimeClient
    internal val firstFrameReporter get() = modeAssembly.firstFrameReporter

    /** Endpoint provider consumed by the DSH runtime owner before a host generation starts. */
    internal val gatewayEndpointProvider get() = gatewayAssembly.endpointProvider

    /** Stops the current gateway generation; safe when it never started. */
    internal suspend fun stopGateway() = gatewayAssembly.stop()

    /** Typed D24 trial-launch seam of the maintenance assembly. */
    internal val trialLaunchPort: DshTrialLaunchPort get() = modeAssembly.trialLaunchPort

    /**
     * Current DSH runtime phase, or null when the DSH mode was never created in this process.
     *
     * This is the cheap readiness guard of the automatic binding refresh: a model change must not
     * instantiate the `:dsh` binder (let alone start the host) for a user who never opened DSH.
     */
    internal fun runtimePhaseOrNull(): DshRuntimePhase? =
        if (modeAssemblyLazy.isInitialized()) {
            modeAssembly.runtimeClient.snapshot.value.phase
        } else {
            null
        }

    /** Applies a changed model/gateway binding only while DSH is already READY. */
    internal suspend fun refreshActiveBinding(force: Boolean = false) =
        modeAssembly.refreshActiveBinding(force)

    /**
     * Tri-state observation of the work the current host generation is still running.
     *
     * Only valid while the host is READY (the caller checks that first): it reads the live gateway
     * generation and the capability bridge ledger, never starts either one. A null count means
     * "cannot observe" and must never be read as idle.
     */
    internal suspend fun observeInFlight(): com.muyuchat.core.dsh.runtime.state.DshInFlightSnapshot =
        gatewayAssembly.observeActivity()

    /** Hands the user's MediaProjection consent result to the vscreen capture ledger. */
    internal fun grantScreenCapture(resultCode: Int, data: android.content.Intent?): Boolean =
        capabilityAssembly.grantScreenCapture(resultCode, data)

    /** Releases the app-process binder only; the host keeps running (page exit is not a stop). */
    internal fun close() {
        runCatching { modeAssembly.close() }
    }

    /**
     * Process-root settlement: stops the app-local gateway and capability bridge that were actually
     * created, then releases the binder. Bounded so a hung terminal child cannot hold the last
     * Activity finish indefinitely; a timeout is logged, never reported as a clean stop.
     */
    internal fun closeAndStopBlocking(timeoutMillis: Long = 2_000L) {
        val outcome = runCatching {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) { closeAndStop() }
            }
        }
        outcome.onFailure {
            Log.w("DshHostAssembly", "DSH process close failed: ${it.javaClass.simpleName}")
        }
        if (outcome.isSuccess && outcome.getOrNull() == null) {
            Log.w("DshHostAssembly", "DSH process close timed out after ${timeoutMillis}ms")
        }
    }

    private suspend fun closeAndStop() {
        if (capabilityCreated.get()) {
            runCatching { capabilityAssembly.stop() }
                .onFailure {
                    Log.w("DshHostAssembly", "capability bridge stop failed: ${it.javaClass.simpleName}")
                }
        }
        if (gatewayCreated.get()) {
            runCatching { gatewayAssembly.stop() }
                .onFailure {
                    Log.w("DshHostAssembly", "gateway stop failed: ${it.javaClass.simpleName}")
                }
        }
        runCatching { modeAssembly.close() }
    }

    /** File count from the APK's payload manifest; null when unreadable. */
    private fun packagedPayloadFileCount(): Long? = runCatching {
        application.assets.open("dsh/MANIFEST.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            .let { org.json.JSONObject(it).optLong("fileCount", 0L) }
    }.getOrNull()

    /** Hash from the APK's own payload manifest; null when the asset is unreadable. */
    private fun packagedArchiveSha256(): String? = runCatching {
        application.assets.open("dsh/MANIFEST.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            .let { org.json.JSONObject(it).getJSONObject("archive").getString("sha256") }
    }.getOrNull()

    /** Payload version from the APK's own manifest; null when the asset is unreadable. */
    private fun packagedPayloadVersion(): String? = runCatching {
        application.assets.open("dsh/MANIFEST.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            .let { org.json.JSONObject(it).optString("payloadVersion", "") }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Full text of the packaged payload manifest; the managed-repair inventory derives from it. */
    private fun packagedPayloadManifestJson(): String? = runCatching {
        application.assets.open("dsh/MANIFEST.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()
}
