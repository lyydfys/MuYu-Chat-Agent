package com.muyuchat.core.dsh.runtime.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshGatewayEndpoint
import com.muyuchat.core.dsh.contract.DshGatewayEndpointProvider
import com.muyuchat.core.dsh.contract.DshRuntimeClient
import com.muyuchat.core.dsh.contract.DshRuntimeFailure
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import com.muyuchat.core.dsh.contract.DshTrialHealthSnapshot
import com.muyuchat.core.dsh.contract.DshTrialHostClient
import com.muyuchat.core.dsh.contract.DshTrialPhase
import com.muyuchat.core.dsh.contract.DshTrialSnapshot
import com.muyuchat.core.dsh.contract.DshWebEntry
import com.muyuchat.core.dsh.contract.capability.DshCapabilityBridgeEndpointProvider
import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import com.muyuchat.core.dsh.runtime.state.DshApplyRequest
import com.muyuchat.core.dsh.runtime.state.DshApplyResult
import com.muyuchat.core.dsh.runtime.state.DshApplyStatus
import com.muyuchat.core.dsh.runtime.state.DshDisconnectFailurePolicy
import com.muyuchat.core.dsh.runtime.state.DshHostConfig
import com.muyuchat.core.dsh.runtime.state.DshInFlightObservation
import com.muyuchat.core.dsh.runtime.state.DshStartFailureProjection
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * App-process client of the single `:dsh` runtime owner.
 *
 * Responsibilities:
 * - obtain the loopback MCA model-gateway endpoint BEFORE starting the host, so DSH never boots
 *   without a routable provider;
 * - bind the dedicated service, register for snapshots, and forward ensure/restart/stop/entry
 *   requests over Messenger;
 * - reconcile the live binding: [ensureApplied] sends the desired gateway/capability/composition
 *   identity plus the caller's in-flight observation, and accepts an APPLIED reply only when the
 *   host echoes exactly the requested binding id;
 * - expose the frozen [DshRuntimeClient] contract to the workspace page and app composition.
 *
 * The authenticated launch entry travels only over Binder in memory. It is never persisted,
 * logged or put in an Intent. A service-process death is reported as PROCESS_EXITED (unless an
 * intentional stop was requested), never as a fake READY/STOPPED success.
 *
 * D24: the same client is the app-process [DshTrialHostClient]. The trial is a non-publishing
 * generation of the same owner, so those calls never read or write the frozen [snapshot] and every
 * failure (unavailable service, timeout, Binder loss, malformed reply) maps to
 * [DshTrialPhase.UNKNOWN] — never to STOPPED, which would retire a trial journal that still
 * describes a real process.
 */
class DshRuntimeServiceClient(
    context: Context,
    private val gatewayEndpoints: DshGatewayEndpointProvider,
    private val capabilityEndpoints: DshCapabilityBridgeEndpointProvider? = null,
    private val config: DshRuntimeConfig = DshRuntimeConfig(
        File(context.filesDir, "dsh"),
        sandboxRunnerFile = File(context.applicationInfo.nativeLibraryDir, "libdshsandbox.so")
    ).validated(),
    /**
     * Observation of the app-process work the current generation is still running (for MCA this
     * is the loopback gateway's admitted model calls). The `:dsh` owner cannot see that process,
     * so the observation travels with each apply request. The default is honest: unobservable.
     */
    private val inFlight: DshInFlightObservation = DshInFlightObservation.Unavailable,
    /**
     * App-composition identity for the desired binding (for example an engine generation). A
     * main-process rebuild that keeps the same gateway endpoint and catalog still reloads the
     * host because this value changes. Token-free; empty means "not supplied".
     */
    private val hostBuild: () -> String = { "" }
) : DshRuntimeClient, DshTrialHostClient {

    private val appContext = context.applicationContext
    private val _snapshot = MutableStateFlow(DshRuntimeSnapshot())
    override val snapshot: StateFlow<DshRuntimeSnapshot> = _snapshot.asStateFlow()

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Bundle>>()
    private val requestIds = AtomicLong(0L)

    @Volatile
    private var serviceMessenger: Messenger? = null

    @Volatile
    private var bound = false

    @Volatile
    private var intentionalStop = false


    /**
     * Single-flight bind state. The workspace can legitimately request `/models` and the
     * authenticated entry concurrently while a restart is in flight; a single per-call waiter
     * field would let the second caller overwrite the first one's waiter and time out even though
     * the binding succeeded. All in-flight callers therefore share one deferred.
     */
    private val bindLock = Any()

    @Volatile
    private var bindInFlight = false

    private var bindWaiter: CompletableDeferred<Messenger>? = null

    private val replyMessenger = Messenger(
        Handler(Looper.getMainLooper()) { message ->
            onReply(message)
            true
        }
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                failBind(IllegalStateException("the DSH host service returned a null binder"))
                return
            }
            val messenger = Messenger(binder)
            serviceMessenger = messenger
            register(messenger)
            bindInFlight = false
            val waiter = synchronized(bindLock) {
                bindWaiter.also { bindWaiter = null }
            }
            if (waiter != null && !waiter.isCompleted) waiter.complete(messenger)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
            bound = false
            failBind(IllegalStateException("the DSH host process disconnected"))
            if (!intentionalStop) {
                _snapshot.value = DshDisconnectFailurePolicy.resolve(
                    current = _snapshot.value,
                    message = "the DSH host process disconnected unexpectedly"
                )
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            serviceMessenger = null
            bound = false
            failBind(IllegalStateException("the DSH host binding died"))
            if (!intentionalStop) {
                _snapshot.value = DshDisconnectFailurePolicy.resolve(
                    current = _snapshot.value,
                    message = "the DSH host binding died unexpectedly"
                )
            }
        }

        override fun onNullBinding(name: ComponentName?) {
            serviceMessenger = null
            bound = false
            failBind(IllegalStateException("the DSH host service returned a null binding"))
            publishFailure(DshFailureCode.INTERNAL, "the DSH host service returned a null binding")
        }
    }

    /** Completes every in-flight bind waiter with the same failure and resets the bind gate. */
    private fun failBind(reason: Throwable) {
        bindInFlight = false
        val waiter = synchronized(bindLock) {
            bindWaiter.also { bindWaiter = null }
        }
        if (waiter != null && !waiter.isCompleted) waiter.completeExceptionally(reason)
    }

    override suspend fun ensureStarted(): DshRuntimeSnapshot =
        ensureApplied(source = "ensureStarted").snapshot

    /**
     * Starts from a committed closure `payload` tree, or returns to the installed payload when
     * [payloadRoot] is null. The service admits the tree before the owner records it; a refused
     * tree is reported as [DshFailureCode.PAYLOAD_INTEGRITY] and never looks like a start.
     */
    override suspend fun ensureStartedFrom(payloadRoot: java.io.File?): DshRuntimeSnapshot {
        val result = ensureApplied(
            source = if (payloadRoot == null) "ensureInstalledPayload" else "ensureClosurePayload",
            payloadRoot = payloadRoot?.absolutePath ?: ""
        )
        return DshStartFailureProjection.resolve(
            result = result,
            closureRequested = payloadRoot != null
        )
    }

    /**
     * Makes the host run the binding the app process currently advertises, and reports exactly
     * what happened.
     *
     * This is the app-side half of the reload: a repeated call with an unchanged binding is a
     * no-op on the host (same epoch, no process); a changed gateway port/token, catalog revision,
     * capability bridge or composition identity replaces the generation; running work can defer
     * the change unless [force] is set. The reply is only accepted as APPLIED when the host
     * echoes exactly the requested binding id, so a stale or corrupted reply can never fabricate
     * a successful reload.
     */
    suspend fun ensureApplied(
        force: Boolean = false,
        source: String = "apply",
        payloadRoot: String? = null
    ): DshApplyResult {
        intentionalStop = false
        val endpoint = gatewayEndpointOrNull()
        val bridge = capabilityEndpoints?.endpoint()
        val desired = DshHostConfig(endpoint, bridge, hostBuild())
        val observed = runCatching { inFlight.inFlightCalls() }
            .onFailure { Log.w(TAG, "in-flight observation failed (${it.javaClass.simpleName})") }
            .getOrNull()
        val request = DshApplyRequest(
            inFlightCalls = observed,
            force = force,
            // The owner requires a short stable label; a caller must not be able to fail the call
            // with a long or blank one.
            source = source.trim().takeIf { it.isNotEmpty() }?.take(APPLY_SOURCE_MAX_CHARS) ?: "apply",
            payloadRoot = payloadRoot
        )
        val messenger = messengerOrBind()
            ?: return publishApplyFailure(
                desired,
                DshFailureCode.INTERNAL,
                "无法连接 DSH 运行时服务；请稍后重试"
            )
        val reply = request(
            messenger = messenger,
            what = DshRuntimeIpc.MSG_APPLY_CONFIG,
            data = DshRuntimeIpc.applyRequestBundle(endpoint, bridge, desired.hostBuild, request),
            timeoutMillis = applyTimeoutMillis()
        ) ?: return publishApplyFailure(
            desired,
            DshFailureCode.NODE_START_FAILED,
            "DSH 配置应用超时；运行时服务没有在约定时间内完成"
        )
        val result = DshRuntimeIpc.applyResultFrom(reply)
        _snapshot.value = result.snapshot
        if (result.status == DshApplyStatus.APPLIED && result.applied != desired.id) {
            return publishApplyFailure(
                desired,
                DshFailureCode.INTERNAL,
                "DSH 运行时没有确认请求的配置；请重试"
            )
        }
        return result
    }

    override suspend fun restart(): DshRuntimeSnapshot {
        intentionalStop = false
        val endpoint = gatewayEndpointOrNull()
        val messenger = messengerOrBind()
            ?: return publishFailure(
                DshFailureCode.INTERNAL,
                "无法连接 DSH 运行时服务；请稍后重试"
            )
        val reply = request(
            messenger = messenger,
            what = DshRuntimeIpc.MSG_RESTART,
            data = startBundle(endpoint),
            timeoutMillis = startTimeoutMillis()
        ) ?: return publishFailure(
            DshFailureCode.NODE_START_FAILED,
            "DSH 重新启动超时；运行时服务没有在约定时间内完成启动"
        )
        return applySnapshotReply(reply)
    }

    /**
     * Builds the start/restart payload: gateway coordinates plus, when the app process has one,
     * the same generation's capability bridge. The bridge is optional by design: a host without
     * it still serves the official frontend and desktop tools, and the mobile families report
     * NOT_ENABLED instead of pretending to be available.
     */
    private suspend fun startBundle(endpoint: DshGatewayEndpoint?): Bundle {
        val data = if (endpoint != null) {
            DshRuntimeIpc.gatewayBundle(endpoint)
        } else {
            Bundle()
        }
        val bridge = capabilityEndpoints?.endpoint()
        if (bridge != null) data.putAll(DshRuntimeIpc.capabilityBundle(bridge))
        data.putString(DshRuntimeIpc.KEY_HOST_BUILD, hostBuild())
        return data
    }

    override suspend fun stop() {
        intentionalStop = true
        // A stop is confirmed only by a fresh owner reply, so an unbound client must bind
        // instead of skipping the request: a previous stop() unbinds, and without the round
        // trip the policy below would publish FAILED/STOP_TIMEOUT for a host that is already
        // stopped, which makes maintenance refuse the serialized trial on a false "still
        // running". Binding starts the service process only; the host starts on MSG_ENSURE_STARTED.
        val messenger = serviceMessenger ?: messengerOrBind()
        var reply: Bundle? = null
        if (messenger != null) {
            reply = request(
                messenger = messenger,
                what = DshRuntimeIpc.MSG_STOP,
                data = Bundle(),
                timeoutMillis = STOP_TIMEOUT_MILLIS
            )
            runCatching {
                messenger.send(Message.obtain(null, DshRuntimeIpc.MSG_RELEASE))
            }
        }
        unbind()
        val replySnapshot = reply
            ?.takeIf { it.containsKey(DshRuntimeIpc.KEY_PHASE) }
            ?.let { DshRuntimeIpc.snapshotFrom(it) }
        // A null messenger, timeout or Binder death is NOT proof of STOPPED: Node is a child of
        // the service and can outlive it. Only a fresh owner reply can establish STOPPED here.
        _snapshot.value = DshStopSnapshotPolicy.resolve(
            current = _snapshot.value,
            reply = replySnapshot,
            nowMillis = System.currentTimeMillis()
        )
    }

    override suspend fun webEntry(): DshWebEntry? {
        val messenger = serviceMessenger ?: messengerOrBind() ?: return null
        val reply = request(
            messenger = messenger,
            what = DshRuntimeIpc.MSG_GET_ENTRY,
            data = Bundle(),
            timeoutMillis = ENTRY_TIMEOUT_MILLIS
        ) ?: return null
        if (!reply.containsKey(DshRuntimeIpc.KEY_ENTRY_URL)) return null
        val url = reply.getString(DshRuntimeIpc.KEY_ENTRY_URL)?.takeIf { it.isNotBlank() } ?: return null
        val epoch = reply.getLong(DshRuntimeIpc.KEY_EPOCH, _snapshot.value.epoch)
        return DshWebEntry(epoch = epoch, authenticatedUrl = url)
    }

    /**
     * Sends the app-process WebView's first-visible-frame report for [epoch] to the `:dsh` owner.
     *
     * This is a one-way observation, not part of the frozen [DshRuntimeClient] lifecycle: no reply
     * is awaited, no local snapshot is written and the owner may still reject the epoch. Returns
     * true only when the message was handed to Binder; false when the client is not bound or the
     * send failed. Callers must treat it as best-effort observability.
     */
    fun reportFirstFrameRendered(epoch: Long): Boolean {
        if (epoch <= 0L) return false
        val messenger = serviceMessenger ?: return false
        val message = Message.obtain(null, DshRuntimeIpc.MSG_REPORT_FIRST_FRAME).apply {
            data = DshRuntimeIpc.firstFrameBundle(epoch)
        }
        return runCatching { messenger.send(message) }
            .onFailure { Log.w(TAG, "first-frame report failed (${it.javaClass.simpleName})") }
            .isSuccess
    }

    // ------------------------------------------------------------------ isolated trial (D24)

    /**
     * Starts the isolated trial generation of the same owner.
     *
     * Runner-up outcomes are typed, never optimistic: `RUNNING` is the only success; `STOPPED` is
     * the owner's refusal; an unavailable service, a timeout or a malformed reply is `UNKNOWN`.
     * The frozen production [snapshot] is deliberately not written by this call: a trial must never
     * appear as the production lifecycle.
     */
    override suspend fun startTrial(
        candidateRoot: String,
        candidateFingerprint: String,
        homeRoot: String,
        workspaceRoot: String,
        tmpRoot: String
    ): DshTrialSnapshot {
        val messenger = messengerOrBind() ?: return DshTrialClientPolicy.unavailable()
        val reply = request(
            messenger = messenger,
            what = DshRuntimeIpc.MSG_TRIAL_START,
            data = DshRuntimeIpc.trialRequestBundle(
                DshRuntimeIpc.TrialStartRequest(
                    candidateRoot = candidateRoot,
                    candidateFingerprint = candidateFingerprint,
                    homeRoot = homeRoot,
                    workspaceRoot = workspaceRoot,
                    tmpRoot = tmpRoot
                )
            ),
            timeoutMillis = startTimeoutMillis()
        ) ?: return DshTrialClientPolicy.unknown("the isolated trial start did not answer in time")
        return DshTrialClientPolicy.fromReply(reply)
    }

    /**
     * Releases the trial generation and returns the owner's proof.
     *
     * Unlike [stop] this never sets the intentional-stop flag, never unbinds and never releases the
     * service: a trial release says nothing about the production lifecycle or the notification.
     */
    override suspend fun stopTrial(): DshTrialSnapshot {
        val messenger = serviceMessenger ?: messengerOrBind()
            ?: return DshTrialClientPolicy.unavailable()
        val reply = request(
            messenger = messenger,
            what = DshRuntimeIpc.MSG_TRIAL_STOP,
            data = Bundle(),
            timeoutMillis = STOP_TIMEOUT_MILLIS
        ) ?: return DshTrialClientPolicy.unknown("the isolated trial stop did not answer in time")
        return DshTrialClientPolicy.fromReply(reply)
    }

    /**
     * Fresh trial status from the owner.
     *
     * Read-only: it never binds and therefore never starts the `:dsh` foreground service just to
     * answer a probe. Without a live binding the honest answer is UNKNOWN (not STOPPED).
     */
    override suspend fun trialStatus(): DshTrialSnapshot {
        val messenger = serviceMessenger
            ?: return DshTrialClientPolicy.unknown("the DSH runtime service is not bound")
        val reply = request(
            messenger = messenger,
            what = DshRuntimeIpc.MSG_TRIAL_STATUS,
            data = Bundle(),
            timeoutMillis = TRIAL_PROBE_TIMEOUT_MILLIS
        ) ?: return DshTrialClientPolicy.unknown("the isolated trial status did not answer in time")
        return DshTrialClientPolicy.fromReply(reply)
    }

    /**
     * Read-only trial health. Every positive field comes from the owner's positive observation; a
     * missing reply reports all three as false with a reason, never as "healthy". Like
     * [trialStatus] this never binds a new service.
     */
    override suspend fun trialHealth(): DshTrialHealthSnapshot {
        val messenger = serviceMessenger
            ?: return DshTrialHealthSnapshot(
                detail = "the DSH runtime service is not bound"
            )
        val reply = request(
            messenger = messenger,
            what = DshRuntimeIpc.MSG_TRIAL_HEALTH,
            data = Bundle(),
            timeoutMillis = TRIAL_PROBE_TIMEOUT_MILLIS
        ) ?: return DshTrialHealthSnapshot(detail = "the isolated trial health probe did not answer in time")
        return DshRuntimeIpc.trialHealthFrom(reply)
    }

    /** Releases the Binder connection without touching the running host. */
    fun close() {        intentionalStop = true
        serviceMessenger?.let { messenger ->
            runCatching { messenger.send(Message.obtain(null, DshRuntimeIpc.MSG_RELEASE)) }
        }
        unbind()
    }

    // ------------------------------------------------------------------ bind / requests

    private suspend fun gatewayEndpointOrNull(): DshGatewayEndpoint? =
        runCatching { gatewayEndpoints.endpoint() }
            .onFailure { Log.w(TAG, "gateway endpoint unavailable (${it.javaClass.simpleName})") }
            .getOrNull()

    private suspend fun messengerOrBind(): Messenger? {
        serviceMessenger?.let { return it }

        val waiter = synchronized(bindLock) {
            bindWaiter?.takeIf { !it.isCompleted }
                ?: CompletableDeferred<Messenger>().also { bindWaiter = it }
        }

        val startThisCall = synchronized(bindLock) {
            if (bindInFlight) {
                false
            } else {
                bindInFlight = true
                true
            }
        }
        if (startThisCall) {
            val intent = Intent(appContext, DshRuntimeService::class.java)
            val started = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }.isSuccess
            if (!started) {
                failBind(IllegalStateException("cannot start the DSH host service"))
                return null
            }
            if (!bound) {
                bound = runCatching {
                    appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                }.getOrDefault(false)
            }
            if (!bound) {
                failBind(IllegalStateException("cannot bind the DSH host service"))
                return null
            }
        }
        return withTimeoutOrNull(BIND_TIMEOUT_MILLIS) { waiter.await() } ?: serviceMessenger
    }

    private suspend fun request(
        messenger: Messenger,
        what: Int,
        data: Bundle,
        timeoutMillis: Long
    ): Bundle? {
        val requestId = requestIds.incrementAndGet()
        val deferred = CompletableDeferred<Bundle>()
        pending[requestId] = deferred
        val message = Message.obtain(null, what).apply {
            this.data = Bundle(data).apply {
                putLong(DshRuntimeIpc.KEY_REQUEST_ID, requestId)
            }
            replyTo = replyMessenger
        }
        val sent = runCatching { messenger.send(message) }.isSuccess
        if (!sent) {
            pending.remove(requestId)
            return null
        }
        return try {
            withTimeoutOrNull(timeoutMillis) { deferred.await() }
        } finally {
            pending.remove(requestId)
        }
    }

    private fun register(messenger: Messenger) {
        val message = Message.obtain(null, DshRuntimeIpc.MSG_REGISTER).apply {
            replyTo = replyMessenger
        }
        runCatching { messenger.send(message) }
            .onFailure { Log.w(TAG, "register failed (${it.javaClass.simpleName})") }
    }

    private fun unbind() {
        if (bound) {
            bound = false
            runCatching { appContext.unbindService(connection) }
                .onFailure { Log.w(TAG, "unbind failed (${it.javaClass.simpleName})") }
        }
        serviceMessenger = null
    }

    // ------------------------------------------------------------------ replies

    private fun onReply(message: Message) {
        val bundle = message.data
        val requestId = bundle?.takeIf { it.containsKey(DshRuntimeIpc.KEY_REQUEST_ID) }
            ?.getLong(DshRuntimeIpc.KEY_REQUEST_ID)
        if (requestId != null) {
            val deferred = pending.remove(requestId)
            if (deferred != null && !deferred.isCompleted) {
                deferred.complete(bundle)
            }
            return
        }
        if (message.what == DshRuntimeIpc.REPLY_SNAPSHOT && bundle != null) {
            val candidate = DshRuntimeIpc.snapshotFrom(bundle)
            val current = _snapshot.value
            // Unsolicited pushes can arrive out of order after a newer local failure; never
            // regress the frozen snapshot to an older generation.
            if (DshStopSnapshotPolicy.isFresh(candidate, current)) {
                _snapshot.value = candidate
            }
        }
    }

    private fun applySnapshotReply(bundle: Bundle): DshRuntimeSnapshot {
        val snapshot = DshRuntimeIpc.snapshotFrom(bundle)
        _snapshot.value = snapshot
        return snapshot
    }

    private fun publishFailure(code: DshFailureCode, message: String): DshRuntimeSnapshot {
        val snapshot = DshRuntimeSnapshot(
            phase = DshRuntimePhase.FAILED,
            epoch = _snapshot.value.epoch,
            origin = null,
            failure = DshRuntimeFailure(
                code = code,
                message = message,
                retryable = code != DshFailureCode.PAYLOAD_MISSING &&
                    code != DshFailureCode.PAYLOAD_INTEGRITY &&
                    code != DshFailureCode.ABI_UNSUPPORTED &&
                    code != DshFailureCode.NODE_NOT_EXECUTABLE
            ),
            changedAtMillis = System.currentTimeMillis()
        )
        _snapshot.value = snapshot
        return snapshot
    }

    /**
     * A failed apply is never turned into a success claim: the local projection becomes FAILED and
     * the typed result says nothing is applied. [desired] is the binding the caller asked for, so
     * the failure still names it without any token.
     */
    private fun publishApplyFailure(
        desired: DshHostConfig,
        code: DshFailureCode,
        message: String
    ): DshApplyResult {
        val snapshot = publishFailure(code, message)
        return DshApplyResult(
            status = DshApplyStatus.FAILED,
            requested = desired.id,
            applied = null,
            retained = null,
            epoch = snapshot.epoch,
            snapshot = snapshot,
            detail = message
        )
    }

    private fun startTimeoutMillis(): Long =
        config.authLineTimeoutMillis + config.readinessTimeoutMillis +
            config.nodeExecProbeTimeoutMillis + START_SLACK_MILLIS

    /**
     * A binding apply may have to stop a live generation, start the replacement and, when that
     * fails, start the previous binding again. The stop grace/kill budgets and a second start are
     * therefore part of the same bounded wait.
     */
    private fun applyTimeoutMillis(): Long = startTimeoutMillis() * 2

    private companion object {
        const val TAG = "McaDshClient"
        const val BIND_TIMEOUT_MILLIS = 15_000L
        const val ENTRY_TIMEOUT_MILLIS = 10_000L
        const val STOP_TIMEOUT_MILLIS = 30_000L
        const val START_SLACK_MILLIS = 120_000L

        /** Status/health are cheap reads on the owner; they must not hang the console. */
        const val TRIAL_PROBE_TIMEOUT_MILLIS = 10_000L

        /** Mirrors DshApplyRequest's own bound for the free-form caller label. */
        const val APPLY_SOURCE_MAX_CHARS = 64
    }
}

/**
 * App-process rules for the trial client (D24).
 *
 * The one rule that matters: **no failure may become STOPPED.** An unavailable service, a timeout,
 * a Binder loss or an unparsable reply all mean "the owner did not prove anything", which is
 * [DshTrialPhase.UNKNOWN]. Mapping any of them to STOPPED would let the maintenance journal retire
 * a trial that may still hold a live process.
 */
internal object DshTrialClientPolicy {

    const val UNAVAILABLE_DETAIL = "the DSH runtime service is unavailable"

    fun unavailable(): DshTrialSnapshot =
        DshTrialSnapshot(phase = DshTrialPhase.UNKNOWN, detail = UNAVAILABLE_DETAIL)

    /** Any other reason the owner did not report a phase: timeout, unbound service, Binder loss. */
    fun unknown(reason: String): DshTrialSnapshot =
        DshTrialSnapshot(phase = DshTrialPhase.UNKNOWN, detail = reason)

    /**
     * Maps one reply bundle. A reply without a recognized trial phase (foreign, truncated or from
     * an older service) is UNKNOWN, because the owner never reported a phase.
     */
    fun fromReply(reply: Bundle?): DshTrialSnapshot = reply
        ?.let { DshRuntimeIpc.trialSnapshotFrom(it) }
        ?: unavailable()
}
