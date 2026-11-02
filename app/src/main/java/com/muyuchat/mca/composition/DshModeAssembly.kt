package com.muyuchat.mca.composition

import android.content.Context
import android.util.Log
import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshGatewayEndpointProvider
import com.muyuchat.core.dsh.contract.DshRuntimeClient
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import com.muyuchat.core.dsh.contract.DshStartupAdmission
import com.muyuchat.core.dsh.contract.DshWebEntry
import com.muyuchat.core.dsh.maintenance.DshTrialLaunchPort
import com.muyuchat.core.dsh.runtime.service.DshGatewayTeardownPolicy
import com.muyuchat.core.dsh.runtime.service.DshRuntimeServiceClient
import com.muyuchat.feature.dsh.workspace.DshFirstFrameReporter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Composition-level binding of the independent DSH mode.
 *
 * The [DshRuntimeServiceClient] talks to the single `:dsh` host process. This assembly adds the
 * app-process half of the lifecycle: the model gateway belongs to the same host generation, so a
 * failed start, an explicit stop or a restart tears the gateway generation down (rotating its
 * bearer token) before the next start. The service itself never learns about the gateway's
 * lifecycle; it only receives the endpoint for the generation it is asked to run.
 *
 * The capability bridge (D15) is the second app-process half: its authenticated loopback server
 * must exist before Node starts, so the same generation hands both coordinates to the service.
 * The bridge is optional; a null provider keeps the official frontend usable while the mobile
 * tool families report NOT_ENABLED.
 *
 * The assembly also owns the app-side D24 seam: [trialLaunchPort] is this same runtime client
 * viewed through the typed `DshTrialHostClient` contract, so a closure trial can only ever be the
 * one owner's non-publishing generation and never a second app-process host.
 */
internal class DshModeAssembly(
    context: Context,
    private val gateway: DshGatewayAssembly,
    endpointProvider: DshGatewayEndpointProvider,
    private val capabilityBridge: DshCapabilityAssembly? = null,
    /** Typed startup admission (journal/safe-mode/upgrade sweep), run before every start attempt. */
    private val beforeStart: (() -> DshStartupAdmission)? = null,
    /** Live model-call observation for reload deferral; unavailable is never treated as zero. */
    private val inFlight: com.muyuchat.core.dsh.runtime.state.DshInFlightObservation =
        com.muyuchat.core.dsh.runtime.state.DshInFlightObservation.Unavailable,
    /** Stable composition identity; empty is acceptable (a process rebuild changes port/token). */
    private val hostBuild: () -> String = { "" },
    /**
     * Post-READY maintenance confirmation (an upgrade interrupted between the selector switch and
     * its health proof). Runs off the main thread and never starts a host.
     */
    private val onHostReady: (suspend () -> Unit)? = null,
    scope: CoroutineScope
) {
    private val client = DshRuntimeServiceClient(
        context.applicationContext,
        endpointProvider,
        capabilityBridge?.endpointProvider,
        inFlight = inFlight,
        hostBuild = hostBuild
    )

    /**
     * Typed D24 seam for the isolated closure trial.
     *
     * The trial must be a non-publishing generation inside the *same* `:dsh` owner, so the port is
     * built from this assembly's one runtime client instead of a second client/host. When the
     * installed `:core:dsh-runtime` client does not implement
     * [com.muyuchat.core.dsh.contract.DshTrialHostClient] the probe yields the honest typed
     * "not connected" failure; no trial state is ever faked and no process is launched here.
     */
    internal val trialLaunchPort: DshTrialLaunchPort =
        DshRuntimeTrialLaunchAdapter.fromRuntimeClient(client)

    /**
     * App-process observation of the WebView's first committed frame for the current generation.
     * The host owns the same fact in its startup timeline; this copy exists so the D20 console can
     * project "首帧渲染" without adding a second IPC round-trip. It is cleared whenever a new
     * generation starts or the current one is confirmed stopped.
     */
    private val firstFrameSeen = AtomicBoolean(false)

    val firstFrameObserved: Boolean
        get() = firstFrameSeen.get()

    /** Stable app-process edge from the WebView's first visible frame to the host generation. */
    val firstFrameReporter: DshFirstFrameReporter = DshFirstFrameReporter { epoch ->
        if (epoch > 0L) firstFrameSeen.set(true)
        client.reportFirstFrameRendered(epoch)
        Unit
    }

    /**
     * Supplies the committed closure `payload` tree, or null when the installed payload is the
     * active one. The host composition wires this to the maintenance selector; absent wiring keeps
     * the installed payload and is honest for builds without a closure store.
     */
    @Volatile
    var activeClosurePayload: (() -> File?)? = null

    /**
     * Observable snapshot of this assembly. A startup-admission refusal happens in this process
     * before the service is asked to start, so it must be published here: the service flow still
     * carries the previous generation's disconnect (`PROCESS_EXITED`), which must not replace the
     * typed refusal the user has to act on.
     */
    private val localSnapshot = MutableStateFlow(client.snapshot.value)
    private val admissionRefusalAtMillis = AtomicLong(0L)

    @Volatile
    private var admissionRefusalActive = false

    val runtimeClient: DshRuntimeClient = object : DshRuntimeClient {
        override val snapshot: StateFlow<DshRuntimeSnapshot> = localSnapshot

        override suspend fun ensureStarted(): DshRuntimeSnapshot =
            startWith { client.ensureStartedFrom(activeClosurePayload?.invoke()) }

        override suspend fun ensureStartedFrom(payloadRoot: File?): DshRuntimeSnapshot =
            startWith { client.ensureStartedFrom(payloadRoot) }

        private suspend fun startWith(
            start: suspend () -> DshRuntimeSnapshot
        ): DshRuntimeSnapshot {
            val before = client.snapshot.value
            if (DshGatewayTeardownPolicy.shouldRotateBeforeStart(before)) {
                // Never hand a stopped/unknown/failed generation's bearer or capability token to
                // a new Node. The owner still enforces the stop fence before starting anything.
                gateway.stop()
                capabilityBridge?.stop()
            }
            refuseBeforeStart(before)?.let { refusal ->
                admissionRefusalAtMillis.set(System.currentTimeMillis())
                admissionRefusalActive = true
                localSnapshot.value = refusal
                return refusal
            }
            // A real attempt is going out: a new service snapshot now wins over an old refusal.
            admissionRefusalActive = false
            if (before.phase != DshRuntimePhase.READY) firstFrameSeen.set(false)
            val snapshot = start()
            if (DshGatewayTeardownPolicy.shouldStopAfterStartAttempt(snapshot)) {
                gateway.stop()
                capabilityBridge?.stop()
            }
            return snapshot
        }

        override suspend fun restart(): DshRuntimeSnapshot =
            // A restart is another production generation, so it carries the same payload choice:
            // the durable committed closure when one exists, the installed payload otherwise.
            // The service is never stopped here: the owner settles the old host and allocates the
            // next epoch itself; this composition only rotates the app-process credentials.
            startWith { client.ensureStartedFrom(activeClosurePayload?.invoke()) }

        override suspend fun stop() {
            // D01: only a confirmed STOPPED reply releases the model channel. A stop timeout,
            // Binder loss or stale reply leaves the owner's fate unknown, and a still-running
            // Node host may need the gateway; tearing it down here would break that host and
            // falsely claim the resources were released. The capability bridge follows the same
            // rule: an unknown stop keeps its token valid so a live host's tools are not cut off.
            try {
                client.stop()
            } finally {
                if (DshGatewayTeardownPolicy.shouldStopAfterStop(client.snapshot.value)) {
                    firstFrameSeen.set(false)
                    gateway.stop()
                    capabilityBridge?.stop()
                }
            }
        }

        override suspend fun webEntry(): DshWebEntry? = client.webEntry()
    }

    init {
        scope.launch {
            client.snapshot.collect { incoming ->
                if (DshAdmissionRefusalOverlayPolicy.clearsRefusal(incoming.phase)) {
                    admissionRefusalActive = false
                }
                if (DshAdmissionRefusalOverlayPolicy.suppresses(admissionRefusalActive, incoming)) {
                    return@collect
                }
                localSnapshot.value = incoming
            }
        }
        scope.launch {
            // The initial StateFlow value is STOPPED. Stopping the gateway on that emission would
            // race the on-demand start: endpoint() opens the gateway, then the first STOPPED
            // snapshot would immediately close it before the :dsh seeder reads the catalog.
            // Only tear down a gateway generation that actually became active (STARTING/READY).
            var activeGeneration = false
            var confirmedReadyEpoch = -1L
            client.snapshot.collect { snapshot ->
                when (snapshot.phase) {
                    DshRuntimePhase.STARTING -> activeGeneration = true

                    DshRuntimePhase.READY -> {
                        activeGeneration = true
                        val hook = onHostReady
                        if (hook != null && snapshot.epoch != confirmedReadyEpoch) {
                            confirmedReadyEpoch = snapshot.epoch
                            Log.i("DshModeAssembly", "host READY epoch=${snapshot.epoch}: post-start confirmation")
                            withContext(Dispatchers.IO) {
                                runCatching { hook() }.onFailure { failure ->
                                    Log.w(
                                        "DshModeAssembly",
                                        "post-start maintenance confirmation failed: ${failure.javaClass.simpleName}"
                                    )
                                }
                            }
                        }
                    }

                    // D01: only a confirmed STOPPED snapshot releases the gateway. A FAILED
                    // snapshot can mean the stop outcome is unknown; the owner may still be
                    // alive, so the model channel stays observable until a real stop/restart
                    // decision settles it.
                    DshRuntimePhase.STOPPED -> if (activeGeneration) {
                        activeGeneration = false
                        gateway.stop()
                        capabilityBridge?.stop()
                    }

                    DshRuntimePhase.FAILED,
                    DshRuntimePhase.STOPPING -> Unit
                }
            }
        }
    }

    /**
     * Reconciles a changed gateway/model binding with the running host. Never starts DSH on its
     * own: only called while the host is READY. On FAILED, the half-created gateway/bridge
     * generation is released; on ROLLED_BACK the old generation keeps serving.
     */
    suspend fun applyBinding(force: Boolean = false): com.muyuchat.core.dsh.runtime.state.DshApplyResult {
        val result = client.ensureApplied(force)
        if (result.status == com.muyuchat.core.dsh.runtime.state.DshApplyStatus.FAILED) {
            gateway.stop()
            capabilityBridge?.stop()
        }
        return result
    }

    /** Applies a changed binding only when a host is already READY; null when there is none. */
    suspend fun refreshActiveBinding(force: Boolean = false): com.muyuchat.core.dsh.runtime.state.DshApplyResult? =
        if (client.snapshot.value.phase == DshRuntimePhase.READY) applyBinding(force) else null

    /**
     * Evaluates the startup admission exactly once per start attempt.
     *
     * A refusal is returned as a FAILED snapshot with the typed failure and **no** host start: the
     * workspace renders [DshFailureCode.STARTUP_RECOVERY_REQUIRED] with a retry action, and the
     * retry re-runs the maintenance sweep (the admission is not latched for a blocked outcome).
     */
    private fun refuseBeforeStart(before: DshRuntimeSnapshot): DshRuntimeSnapshot? {
        val admission = beforeStart?.invoke() ?: return null
        return when (admission) {
            DshStartupAdmission.Admitted -> null
            is DshStartupAdmission.Refused -> DshRuntimeSnapshot(
                phase = DshRuntimePhase.FAILED,
                epoch = before.epoch,
                origin = null,
                failure = admission.failure,
                changedAtMillis = System.currentTimeMillis()
            )
        }
    }

    fun close() {
        client.close()
    }
}
