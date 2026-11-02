package com.muyuchat.core.dsh.runtime.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimeFailure
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import com.muyuchat.core.dsh.contract.DshTrialHealthSnapshot
import com.muyuchat.core.dsh.contract.DshTrialPhase
import com.muyuchat.core.dsh.contract.DshTrialSnapshot
import com.muyuchat.core.dsh.runtime.DshRuntimeOwner
import com.muyuchat.core.dsh.runtime.config.DshRuntimeConfig
import com.muyuchat.core.dsh.runtime.payload.DshClosurePayloadAdmission
import com.muyuchat.core.dsh.runtime.state.DshApplyResult
import com.muyuchat.core.dsh.runtime.state.DshApplyStatus
import com.muyuchat.core.dsh.runtime.state.DshBindingId
import com.muyuchat.core.dsh.runtime.state.DshEpochStore
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Dedicated `:dsh` host process. It owns exactly one [DshRuntimeOwner] and exposes it to the app
 * process through [DshRuntimeIpc]'s Messenger protocol.
 *
 * Isolation scope: a separate Android process isolates DSH crashes and part of its lifecycle from
 * the MCA UI process. It is NOT an OS security sandbox: both processes share the app UID, so DSH
 * and its npm plugins can read the same UID-visible files. The V1 proposal/ADR state this
 * explicitly; no isolation claim is made here.
 *
 * The service is started on demand by the workspace page and stops itself only after the owner
 * publishes a confirmed STOPPED snapshot. A FAILED/STOP_TIMEOUT stop keeps the service and its
 * foreground notification alive so the user can retry. Exiting the page never stops it
 * (long-running DSH work must continue); the client explicitly stops it through the Runtime Owner.
 */
class DshRuntimeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = CopyOnWriteArrayList<Messenger>()

    private lateinit var config: DshRuntimeConfig
    private lateinit var endpointHolder: DshGatewayEndpointHolder
    private lateinit var capabilityHolder: DshCapabilityEndpointHolder
    private lateinit var hostBuildHolder: DshHostBuildHolder
    private lateinit var owner: DshRuntimeOwner

    @Volatile
    private var lastSnapshot: DshRuntimeSnapshot = DshRuntimeSnapshot()

    @Volatile
    private var foregroundStarted = false

    private val handler = Handler(Looper.getMainLooper()) { message ->
        onClientMessage(message)
        true
    }
    private val messenger = Messenger(handler)

    override fun onCreate() {
        super.onCreate()
        config = DshRuntimeConfig(
            File(filesDir, "dsh"),
            sandboxRunnerFile = File(applicationInfo.nativeLibraryDir, "libdshsandbox.so")
        ).validated()
        endpointHolder = DshGatewayEndpointHolder()
        capabilityHolder = DshCapabilityEndpointHolder()
        hostBuildHolder = DshHostBuildHolder()
        owner = DshRuntimeOwner(
            config = config,
            deps = DshAndroidRuntimeAssembly(this, config, endpointHolder, capabilityHolder, hostBuildHolder),
            epochStore = DshEpochStore(File(config.dataRoot, "runtime-epoch.json")),
            // Background-job ledger of this installation: one file per host generation. The
            // owner runs recovery from it before a start and polls the READY tree from it.
            jobLedgerDir = File(config.dataRoot, "jobs")
        )
        DshRuntimeNotifications.ensureChannel(this, config)
        startForegroundCompat(DshRuntimeNotifications.build(this, config, lastSnapshot))
        scope.launch {
            owner.snapshot.collect { snapshot ->
                lastSnapshot = snapshot
                pushSnapshot(snapshot)
                updateNotification(snapshot)
            }
        }
        Log.i(TAG, "dsh service created (isolated process, same UID; not a security sandbox)")
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DshRuntimeIpc.ACTION_STOP) {
            scope.launch {
                val snapshot = runCatching {
                    owner.stop()
                    owner.snapshot.value
                }.getOrElse { failure ->
                    Log.w(TAG, "stop from notification failed (${failure.javaClass.simpleName})")
                    failureSnapshot(
                        DshFailureCode.INTERNAL,
                        "the DSH host stop raised an internal error"
                    )
                }
                stopHostAndSelfIfConfirmed(snapshot, "stop from notification")
            }
            return START_NOT_STICKY
        }
        // Ensures the foreground contract for a started service. The actual host start waits for
        // MSG_ENSURE_STARTED, which carries the generation's model-gateway coordinates.
        startForegroundCompat(DshRuntimeNotifications.build(this, config, lastSnapshot))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // The explicit stop path already settled the tree before stopSelf(). This is a last-chance
        // teardown for an OS-initiated destroy; it runs on the service scope so it cannot block
        // the main thread. Lease/native settlement is owned by MCA's engine, not by DSH.
        scope.launch {
            runCatching { owner.shutdown() }
                .onFailure { Log.w(TAG, "shutdown on destroy failed (${it.javaClass.simpleName})") }
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ IPC

    /**
     * Applies a caller's payload instruction before the generation starts.
     *
     * `null` carries no instruction; an empty string selects the installed payload again; any
     * other value must name a closure `payload` tree that passes
     * [DshClosurePayloadAdmission]. Returns a refusal detail, or null once the owner holds the
     * requested choice.
     */
    private fun admitPayloadInstruction(instruction: String?): String? {
        if (instruction == null) return null
        if (instruction.isEmpty()) {
            owner.setActiveClosurePayload(null)
            return null
        }
        val payloadRoot = File(instruction)
        if (!payloadRoot.isAbsolute) return "the closure payload root is not an absolute path"
        return when (
            val admitted = DshClosurePayloadAdmission.admit(
                dataRoot = config.dataRoot,
                payloadRoot = payloadRoot,
                kernelEntryRelativePath = config.kernelEntryRelativePath
            )
        ) {
            is DshClosurePayloadAdmission.Result.Admitted -> {
                owner.setActiveClosurePayload(admitted.payloadRoot)
                null
            }

            is DshClosurePayloadAdmission.Result.Refused -> admitted.detail
        }
    }

    private fun onClientMessage(message: Message) {
        when (message.what) {
            DshRuntimeIpc.MSG_REGISTER -> {
                message.replyTo?.let { replyTo ->
                    if (!clients.contains(replyTo)) clients.add(replyTo)
                }
                replySnapshot(message.replyTo, lastSnapshot, null)
            }

            DshRuntimeIpc.MSG_ENSURE_STARTED,
            DshRuntimeIpc.MSG_RESTART -> {
                // Looper recycles the inbound Message after this handler returns. Capture every
                // field the asynchronous reply path needs NOW; reading message.replyTo / data
                // inside the coroutine could deliver the reply to a recycled/null target and
                // leave the workspace stuck on its Starting shell.
                val restart = message.what == DshRuntimeIpc.MSG_RESTART
                val replyTo = message.replyTo
                val requestId = message.requestIdOrNull()
                // A missing gateway endpoint is valid for D02 account-only mode; the owner then
                // seeds an overlay without an MCA provider instead of refusing the launch.
                adoptCoordinates(message.data)
                scope.launch {
                    val snapshot = runCatching {
                        if (restart) owner.restart() else owner.ensureStarted()
                    }.getOrElse { failure ->
                        Log.w(TAG, "host start failed (${failure.javaClass.simpleName})")
                        failureSnapshot(
                            DshFailureCode.INTERNAL,
                            "the DSH host start raised an internal error"
                        )
                    }
                    replySnapshot(replyTo, snapshot, requestId)
                }
            }

            DshRuntimeIpc.MSG_APPLY_CONFIG -> {
                // Binding reconciliation: the same coordinates as a start, plus the caller's
                // in-flight observation and force flag. Everything is captured before the
                // coroutine because the Message is recycled when this handler returns.
                val replyTo = message.replyTo
                val requestId = message.requestIdOrNull()
                val request = DshRuntimeIpc.applyRequestFrom(message.data)
                val requestedId = DshBindingId.fromWire(
                    message.data?.getString(DshRuntimeIpc.KEY_APPLY_REQUESTED)
                )
                adoptCoordinates(message.data)
                // null = no payload instruction, "" = back to the installed payload, else a
                // committed closure tree that must pass the runtime's own admission first.
                val payloadInstruction = request.payloadRoot
                scope.launch {
                    val admission = admitPayloadInstruction(payloadInstruction)
                    if (admission != null) {
                        val snapshot = owner.snapshot.value
                        replyApply(
                            replyTo,
                            DshApplyResult(
                                status = DshApplyStatus.FAILED,
                                requested = requestedId,
                                applied = null,
                                retained = null,
                                epoch = snapshot.epoch,
                                snapshot = snapshot,
                                inFlightCalls = request.inFlightCalls,
                                detail = admission
                            ),
                            requestId
                        )
                        return@launch
                    }
                    val result = try {
                        owner.ensureApplied(request)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Log.w(TAG, "binding apply failed (${failure.javaClass.simpleName})")
                        val snapshot = owner.snapshot.value
                        DshApplyResult(
                            status = DshApplyStatus.FAILED,
                            requested = requestedId,
                            applied = null,
                            retained = null,
                            epoch = snapshot.epoch,
                            snapshot = snapshot,
                            inFlightCalls = request.inFlightCalls,
                            detail = "the binding apply raised an internal error"
                        )
                    }
                    replyApply(replyTo, result, requestId)
                }
            }

            DshRuntimeIpc.MSG_STOP -> {
                val replyTo = message.replyTo
                val requestId = message.requestIdOrNull()
                scope.launch {
                    val snapshot = runCatching {
                        owner.stop()
                        owner.snapshot.value
                    }.getOrElse { failure ->
                        Log.w(TAG, "host stop failed (${failure.javaClass.simpleName})")
                        failureSnapshot(
                            DshFailureCode.INTERNAL,
                            "the DSH host stop raised an internal error"
                        )
                    }
                    replySnapshot(replyTo, snapshot, requestId)
                    stopHostAndSelfIfConfirmed(snapshot, "client stop")
                }
            }

            // ---------------------------------------------------------- isolated trial (D24)
            // A trial is a non-publishing generation of the same owner: it never carries gateway
            // coordinates, never publishes REPLY_SNAPSHOT and never clears the foreground
            // notification. Each of these messages answers exactly one typed trial reply.
            DshRuntimeIpc.MSG_TRIAL_START -> {
                val replyTo = message.replyTo
                val requestId = message.requestIdOrNull()
                val request = DshRuntimeIpc.trialRequestFrom(message.data)
                scope.launch {
                    val snapshot = if (request == null) {
                        Log.w(TAG, "ignored malformed trial start request")
                        DshTrialSnapshot(
                            phase = DshTrialPhase.FAILED,
                            detail = "the isolated trial request is malformed"
                        )
                    } else {
                        try {
                            owner.startTrial(
                                candidateRoot = File(request.candidateRoot),
                                candidateFingerprint = request.candidateFingerprint,
                                homeRoot = File(request.homeRoot),
                                workspaceRoot = File(request.workspaceRoot),
                                tmpRoot = File(request.tmpRoot)
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            Log.w(TAG, "trial start failed (${failure.javaClass.simpleName})")
                            DshTrialSnapshot(
                                phase = DshTrialPhase.FAILED,
                                detail = "the isolated trial start raised an internal error"
                            )
                        }
                    }
                    replyTrial(replyTo, snapshot, requestId)
                }
            }

            DshRuntimeIpc.MSG_TRIAL_STOP -> {
                val replyTo = message.replyTo
                val requestId = message.requestIdOrNull()
                scope.launch {
                    val snapshot = try {
                        owner.stopTrial()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Log.w(TAG, "trial stop failed (${failure.javaClass.simpleName})")
                        DshTrialSnapshot(
                            phase = DshTrialPhase.UNKNOWN,
                            detail = "the isolated trial stop raised an internal error"
                        )
                    }
                    replyTrial(replyTo, snapshot, requestId)
                    // Deliberately no stopHostAndSelfIfConfirmed(): releasing a trial says nothing
                    // about the production lifecycle, and the service must stay observable.
                }
            }

            DshRuntimeIpc.MSG_TRIAL_STATUS -> {
                val replyTo = message.replyTo
                val requestId = message.requestIdOrNull()
                scope.launch {
                    val snapshot = try {
                        owner.trialStatus()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Log.w(TAG, "trial status failed (${failure.javaClass.simpleName})")
                        DshTrialSnapshot(
                            phase = DshTrialPhase.UNKNOWN,
                            detail = "the isolated trial status could not be read"
                        )
                    }
                    replyTrial(replyTo, snapshot, requestId)
                }
            }

            DshRuntimeIpc.MSG_TRIAL_HEALTH -> {
                val replyTo = message.replyTo
                val requestId = message.requestIdOrNull()
                scope.launch {
                    val health = try {
                        owner.trialHealth()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        Log.w(TAG, "trial health failed (${failure.javaClass.simpleName})")
                        DshTrialHealthSnapshot(detail = "the isolated trial health could not be read")
                    }
                    replyTrialHealth(replyTo, health, requestId)
                }
            }

            DshRuntimeIpc.MSG_GET_ENTRY -> {
                val entry = owner.webEntry()
                val target = message.replyTo
                if (target == null) return
                val reply = if (entry == null) {
                    Message.obtain(null, DshRuntimeIpc.REPLY_ENTRY_NONE).apply {
                        data = Bundle()
                    }
                } else {
                    Message.obtain(null, DshRuntimeIpc.REPLY_ENTRY).apply {
                        data = DshRuntimeIpc.entryBundle(entry.epoch, entry.authenticatedUrl)
                    }
                }
                message.requestIdOrNull()?.let { reply.data.putLong(DshRuntimeIpc.KEY_REQUEST_ID, it) }
                runCatching { target.send(reply) }
            }

            DshRuntimeIpc.MSG_REPORT_FIRST_FRAME -> {
                // Observation only. The owner validates the phase/epoch and never lets this
                // message start, stop or promote a generation; a rejected report is simply
                // ignored so a stale page can not corrupt the current startup timeline.
                val epoch = DshRuntimeIpc.firstFrameEpochFrom(message.data)
                if (epoch == null) {
                    Log.w(TAG, "ignored malformed first-frame report")
                } else if (!owner.reportFirstFrameRendered(epoch)) {
                    Log.i(TAG, "ignored first-frame report for a non-current READY generation")
                }
            }

            DshRuntimeIpc.MSG_RELEASE -> {
                message.replyTo?.let { clients.remove(it) }
                if (clients.isEmpty() && DshServiceStopPolicy.canStopService(owner.snapshot.value)) {
                    stopHostAndSelf()
                }
            }

            else -> Log.w(TAG, "unknown client message ${message.what}")
        }
    }

    private fun replySnapshot(
        target: Messenger?,
        snapshot: DshRuntimeSnapshot,
        requestId: Long?
    ) {
        if (target == null) return
        val reply = Message.obtain(null, DshRuntimeIpc.REPLY_SNAPSHOT).apply {
            data = DshRuntimeIpc.snapshotBundle(snapshot)
            requestId?.let { data.putLong(DshRuntimeIpc.KEY_REQUEST_ID, it) }
        }
        runCatching { target.send(reply) }
            .onFailure { Log.w(TAG, "snapshot reply failed (${it.javaClass.simpleName})") }
    }

    /**
     * Replies with the typed apply result. The snapshot fields are included so an unchanged,
     * deferred or rolled-back call still refreshes the client's frozen projection.
     */
    private fun replyApply(
        target: Messenger?,
        result: DshApplyResult,
        requestId: Long?
    ) {
        if (target == null) return
        val reply = Message.obtain(null, DshRuntimeIpc.REPLY_SNAPSHOT).apply {
            data = DshRuntimeIpc.applyResultBundle(result)
            requestId?.let { data.putLong(DshRuntimeIpc.KEY_REQUEST_ID, it) }
        }
        runCatching { target.send(reply) }
            .onFailure { Log.w(TAG, "apply reply failed (${it.javaClass.simpleName})") }
    }

    /**
     * Replies with one typed trial snapshot. [DshRuntimeIpc.REPLY_TRIAL] is a dedicated id: a
     * trial reply can never be consumed as a production snapshot push, and the client's
     * request-id correlation is unchanged.
     */
    private fun replyTrial(
        target: Messenger?,
        snapshot: DshTrialSnapshot,
        requestId: Long?
    ) {
        if (target == null) return
        val reply = Message.obtain(null, DshRuntimeIpc.REPLY_TRIAL).apply {
            data = DshRuntimeIpc.trialSnapshotBundle(snapshot)
            requestId?.let { data.putLong(DshRuntimeIpc.KEY_REQUEST_ID, it) }
        }
        runCatching { target.send(reply) }
            .onFailure { Log.w(TAG, "trial reply failed (${it.javaClass.simpleName})") }
    }

    /** Replies with one typed, positive-observation-only trial health snapshot. */
    private fun replyTrialHealth(
        target: Messenger?,
        health: DshTrialHealthSnapshot,
        requestId: Long?
    ) {
        if (target == null) return
        val reply = Message.obtain(null, DshRuntimeIpc.REPLY_TRIAL).apply {
            data = DshRuntimeIpc.trialHealthBundle(health)
            requestId?.let { data.putLong(DshRuntimeIpc.KEY_REQUEST_ID, it) }
        }
        runCatching { target.send(reply) }
            .onFailure { Log.w(TAG, "trial health reply failed (${it.javaClass.simpleName})") }
    }

    /**
     * Adopts the per-generation coordinates carried by one client message.
     *
     * A missing gateway endpoint is valid for D02 account-only mode; a missing capability bridge
     * is valid too (the mobile tool families then report NOT_ENABLED). A malformed bridge is
     * dropped so the host never starts half-wired.
     */
    private fun adoptCoordinates(data: Bundle?) {
        val endpoint = DshRuntimeIpc.gatewayFrom(data)
        if (endpoint != null) {
            endpointHolder.set(endpoint)
        } else {
            endpointHolder.set(null)
            Log.i(TAG, "dsh start without an MCA model gateway (account-only generation)")
        }
        capabilityHolder.set(DshRuntimeIpc.capabilityFrom(data))
        hostBuildHolder.set(DshRuntimeIpc.hostBuildFrom(data))
    }

    private fun pushSnapshot(snapshot: DshRuntimeSnapshot) {
        for (client in clients) {
            val message = Message.obtain(null, DshRuntimeIpc.REPLY_SNAPSHOT).apply {
                data = DshRuntimeIpc.snapshotBundle(snapshot)
            }
            runCatching { client.send(message) }
                .onFailure { clients.remove(client) }
        }
    }

    private fun Message.requestIdOrNull(): Long? =
        data?.let { bundle ->
            if (bundle.containsKey(DshRuntimeIpc.KEY_REQUEST_ID)) {
                bundle.getLong(DshRuntimeIpc.KEY_REQUEST_ID)
            } else {
                null
            }
        }

    private fun failureSnapshot(code: DshFailureCode, message: String): DshRuntimeSnapshot =
        DshRuntimeSnapshot(
            phase = DshRuntimePhase.FAILED,
            epoch = lastSnapshot.epoch,
            origin = null,
            failure = DshRuntimeFailure(code = code, message = message, retryable = true),
            changedAtMillis = System.currentTimeMillis()
        )

    // ------------------------------------------------------------------ notification

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                config.notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(config.notificationId, notification)
        }
        foregroundStarted = true
    }

    private fun updateNotification(snapshot: DshRuntimeSnapshot) {
        if (!foregroundStarted) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.notify(
                config.notificationId,
                DshRuntimeNotifications.build(this, config, snapshot)
            )
        }
    }

    /**
     * Ends the foreground service only after the owner has published a confirmed STOPPED
     * snapshot. FAILED (including STOP_TIMEOUT) is not proof of exit: keep the service, the
     * foreground notification and the stop fence observable so the user can retry the stop.
     */
    private fun stopHostAndSelfIfConfirmed(snapshot: DshRuntimeSnapshot, source: String) {
        if (DshServiceStopPolicy.canStopService(snapshot)) {
            stopHostAndSelf()
        } else {
            val code = snapshot.failure?.code?.name
            Log.i(
                TAG,
                "$source not confirmed (phase=${snapshot.phase}, failure=${code ?: "none"}); " +
                    "keeping the service and notification for retry"
            )
        }
    }

    /**
     * Only call after [DshServiceStopPolicy.canStopService] confirmed the owner published
     * STOPPED. This is not a stop request and must never be used for FAILED/STOP_TIMEOUT.
     */
    private fun stopHostAndSelf() {
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private companion object {
        const val TAG = "McaDshService"
    }
}
