package com.muyuchat.mca

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LocalImageWorkerClient(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val inputDispatcher = LocalImageInputDispatcher(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()

    private var remote: ILocalImageWorker? = null
    private var remoteBinder: IBinder? = null
    private var remoteDeathRecipient: IBinder.DeathRecipient? = null
    private var bindingSession: BindingSession? = null
    private val bindingLifecycle = LocalImageWorkerBindingLifecycle()
    private var closed = false
    private var preparation: Preparation? = null
    private var activeRequest: ActiveRequest? = null

    @Volatile
    var lastWorkerPid: Int = -1
        private set

    private inner class WorkerServiceConnection(
        val lease: LocalImageWorkerBindingLifecycle.Session
    ) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            handleServiceConnected(this, binder)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            handleConnectionLoss(this, "Local image worker disconnected.")
        }

        override fun onBindingDied(name: ComponentName) {
            handleConnectionLoss(this, "Local image worker binding died.")
        }

        override fun onNullBinding(name: ComponentName) {
            handleConnectionLoss(this, "Local image worker returned a null binding.")
        }
    }

    fun begin(runtime: LocalImageRuntime) {
        val next = Preparation(runtime)
        val previous = synchronized(stateLock) {
            check(!closed) { "Local image worker client is closed." }
            preparation.also { preparation = next }
        }
        previous?.ready?.completeExceptionally(
            LocalImageWorkerException("Local image worker preparation was superseded.")
        )
        scope.launch {
            runCatching {
                val endpoint = awaitService { session -> next.bindingSession = session }
                if (!isCurrentPreparation(next) || next.cancelRequested) {
                    throw LocalImageWorkerCancelledException()
                }
                runRemoteCall(endpoint) {
                    endpoint.service.begin(LocalImageWorkerProtocol.beginRequest(runtime))
                }.getOrThrow()
            }.onSuccess {
                next.ready.complete(Unit)
            }.onFailure { error ->
                releaseBindingAfterPreparationFailure(next, error)
                next.ready.completeExceptionally(remoteFailure(error))
            }
        }
    }

    fun cancel(): Boolean {
        val snapshot = synchronized(stateLock) {
            preparation?.also { it.cancelRequested = true }
            activeRequest to preparation
        }
        val request = snapshot.first
        val pendingPreparation = snapshot.second
        val runtime = request?.runtime ?: pendingPreparation?.runtime
        val supportsNativeCancel = runtime == LocalImageRuntime.QNN_HTP ||
            runtime == LocalImageRuntime.MNN_DIFFUSION ||
            runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP
        if (request == null) {
            pendingPreparation?.ready?.completeExceptionally(LocalImageWorkerCancelledException())
            currentEndpoint()?.let { endpoint ->
                runRemoteCall(endpoint) {
                    endpoint.service.cancel(LocalImageWorkerProtocol.cancelRequest(null))
                }
            }
            return supportsNativeCancel
        }
        return when (request.handshake.requestCancel()) {
            LocalImageStartHandshake.CancelAction.COMPLETE_LOCALLY -> {
                val cancellation = LocalImageWorkerCancelledException()
                pendingPreparation?.ready?.completeExceptionally(cancellation)
                request.completion.completeExceptionally(cancellation)
                supportsNativeCancel
            }
            LocalImageStartHandshake.CancelAction.DEFER_UNTIL_REGISTERED -> supportsNativeCancel
            LocalImageStartHandshake.CancelAction.CANCEL_REMOTE -> cancelRemote(request)
            LocalImageStartHandshake.CancelAction.NONE -> false
        }
    }

    suspend fun generate(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions = LocalImageGenerationOptions(),
        inputDraft: LocalImageInputDraft = LocalImageInputDraft(),
        requestId: String = UUID.randomUUID().toString(),
        onProgress: (LocalImageProgress) -> Unit = {}
    ): LocalImageResult {
        val request = ActiveRequest(
            requestId = requestId,
            runtime = model.runtime,
            requestedSteps = options.steps,
            requestedUseCfg = options.useCfg,
            onProgress = onProgress
        )
        synchronized(stateLock) {
            check(!closed) { "Local image worker client is closed." }
            check(activeRequest == null) { "Another local image generation is already running." }
            activeRequest = request
        }

        var inputDispatch: LocalImageInputDispatch? = null
        try {
            inputDispatch = inputDispatcher.prepare(requestId, inputDraft, options)
            val dispatchedOptions = inputDispatch.options
            val prepared = currentPreparation(model.runtime) ?: run {
                begin(model.runtime)
                requireNotNull(currentPreparation(model.runtime))
            }
            prepared.ready.await()
            request.bindingSession = prepared.bindingSession
            if (prepared.cancelRequested) {
                throw LocalImageWorkerCancelledException()
            }
            if (request.completion.isCompleted) return request.completion.await().consumeResult()

            val endpoint = awaitService { session -> request.bindingSession = session }
            val service = endpoint.service
            if (request.completion.isCompleted) return request.completion.await().consumeResult()
            if (!request.handshake.tryBeginRemoteStart()) throw LocalImageWorkerCancelledException()

            val callback = callbackFor(request)
            val accepted = try {
                service.generate(
                    LocalImageWorkerProtocol.generateRequest(
                        request.requestId,
                        model,
                        prompt,
                        dispatchedOptions
                    ),
                    callback
                )
            } catch (error: RemoteException) {
                request.handshake.markFinished()
                handleConnectionLoss(
                    endpoint.session.connection,
                    "Local image worker call failed: ${error.message.orEmpty()}"
                )
                throw LocalImageWorkerDisconnectedException("Local image worker call failed.", error)
            }
            val cancelAfterRegistration = request.handshake.completeRemoteStart(accepted)
            if (!accepted) {
                request.completion.completeExceptionally(
                    LocalImageWorkerException("Local image worker rejected the generation request.")
                )
            } else if (cancelAfterRegistration) {
                cancelRemote(request)
            } else {
                startWatchdog(request, model)
            }

            val result = request.completion.await()
            return result.consumeResult()
        } catch (cancelled: CancellationException) {
            request.completion.cancel(cancelled)
            if (request.handshake.requestCancel() == LocalImageStartHandshake.CancelAction.CANCEL_REMOTE) {
                cancelRemote(request)
            }
            throw cancelled
        } finally {
            request.handshake.markFinished()
            if (!request.watchdogTimedOut) request.watchdogJob?.cancel()
            request.deliveredOutputPaths.forEach(::deleteResultIfSafe)
            releaseBindingAfterRequest(request, model.runtime)
            inputDispatch?.directory?.deleteRecursively()
        }
    }

    suspend fun upscale(
        inputImageReference: String,
        upscaler: LocalImagePreparedUpscaler,
        targetScale: Int,
        tileSize: Int = 128,
        threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 5),
        requestId: String = UUID.randomUUID().toString(),
        onProgress: (LocalImageProgress) -> Unit = {}
    ): LocalImageResult {
        require(inputImageReference.isNotBlank()) { "Upscale input image reference must not be blank." }
        val request = ActiveRequest(
            requestId = requestId,
            runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP,
            requestedSteps = 1,
            requestedUseCfg = false,
            onProgress = onProgress
        )
        synchronized(stateLock) {
            check(!closed) { "Local image worker client is closed." }
            check(activeRequest == null) { "Another local image operation is already running." }
            activeRequest = request
        }

        var inputDispatch: LocalImageInputDispatch? = null
        try {
            inputDispatch = inputDispatcher.prepare(
                requestId = requestId,
                draft = LocalImageInputDraft(
                    taskMode = LocalImageTaskMode.IMG2IMG,
                    inputImageReference = inputImageReference,
                    strength = 1.0
                ),
                baseOptions = LocalImageGenerationOptions()
            )
            val preparedInput = requireNotNull(inputDispatch.options.inputImage) {
                "Upscale input dispatcher did not publish a prepared image."
            }
            val runtime = LocalImageRuntime.STABLE_DIFFUSION_CPP
            val prepared = currentPreparation(runtime) ?: run {
                begin(runtime)
                requireNotNull(currentPreparation(runtime))
            }
            prepared.ready.await()
            request.bindingSession = prepared.bindingSession
            if (prepared.cancelRequested) throw LocalImageWorkerCancelledException()
            if (request.completion.isCompleted) return request.completion.await().consumeResult()

            val endpoint = awaitService { session -> request.bindingSession = session }
            val service = endpoint.service
            if (request.completion.isCompleted) return request.completion.await().consumeResult()
            if (!request.handshake.tryBeginRemoteStart()) throw LocalImageWorkerCancelledException()
            val accepted = try {
                service.upscale(
                    LocalImageWorkerProtocol.upscaleRequest(
                        requestId = request.requestId,
                        input = preparedInput,
                        upscaler = upscaler,
                        targetScale = targetScale,
                        tileSize = tileSize,
                        threads = threads
                    ),
                    callbackFor(request)
                )
            } catch (error: RemoteException) {
                request.handshake.markFinished()
                handleConnectionLoss(
                    endpoint.session.connection,
                    "Local image worker upscale call failed: ${error.message.orEmpty()}"
                )
                throw LocalImageWorkerDisconnectedException("Local image worker upscale call failed.", error)
            }
            val cancelAfterRegistration = request.handshake.completeRemoteStart(accepted)
            if (!accepted) {
                request.completion.completeExceptionally(
                    LocalImageWorkerException("Local image worker rejected the upscale request.")
                )
            } else if (cancelAfterRegistration) {
                cancelRemote(request)
            } else {
                startUpscaleWatchdog(request)
            }
            return request.completion.await().consumeResult()
        } catch (cancelled: CancellationException) {
            request.completion.cancel(cancelled)
            if (request.handshake.requestCancel() == LocalImageStartHandshake.CancelAction.CANCEL_REMOTE) {
                cancelRemote(request)
            }
            throw cancelled
        } finally {
            request.handshake.markFinished()
            if (!request.watchdogTimedOut) request.watchdogJob?.cancel()
            request.deliveredOutputPaths.forEach(::deleteResultIfSafe)
            releaseBindingAfterRequest(request, LocalImageRuntime.STABLE_DIFFUSION_CPP)
            inputDispatch?.directory?.deleteRecursively()
        }
    }

    override fun close() {
        val service: ILocalImageWorker?
        val request: ActiveRequest?
        val pendingConnection: CompletableDeferred<ILocalImageWorker>?
        val pendingPreparation: Preparation?
        val session: BindingSession?
        val shouldUnbind: Boolean
        synchronized(stateLock) {
            if (closed) return
            closed = true
            service = remote
            request = activeRequest
            session = bindingSession
            pendingConnection = session?.deferred
            pendingPreparation = preparation
            shouldUnbind = session?.let { bindingLifecycle.release(it.lease) } == true
            activeRequest = null
            preparation = null
            bindingSession = null
            unlinkRemoteDeathRecipientLocked()
            remote = null
            remoteBinder = null
        }
        val closeCancelAction = request?.handshake?.requestCancel()
        if (service != null && request != null &&
            closeCancelAction == LocalImageStartHandshake.CancelAction.CANCEL_REMOTE) {
            runCatching {
                service.cancel(LocalImageWorkerProtocol.cancelRequest(request.requestId))
            }
        }
        val closedError = LocalImageWorkerDisconnectedException("Local image worker client was closed.")
        request?.completion?.completeExceptionally(closedError)
        pendingConnection?.completeExceptionally(closedError)
        pendingPreparation?.ready?.completeExceptionally(closedError)
        scope.cancel()
        if (shouldUnbind && session != null) {
            runCatching { appContext.unbindService(session.connection) }
        }
    }

    private fun callbackFor(request: ActiveRequest): ILocalImageWorkerCallback =
        object : ILocalImageWorkerCallback.Stub() {
            override fun onProgress(payloadJson: String) {
                runCatching { LocalImageWorkerProtocol.parseProgress(payloadJson) }
                    .onSuccess { envelope ->
                        if (envelope.workerPid > 0) lastWorkerPid = envelope.workerPid
                        if (envelope.requestId == request.requestId && !request.completion.isCompleted) {
                            if (envelope.workerPid > 0) request.workerPid = envelope.workerPid
                            request.lastWorkerCallbackAtMs = SystemClock.elapsedRealtime()
                            request.lastProgressPhase = envelope.progress.phase
                            request.lastStageTrace = accumulateNativeStageTrace(
                                request.lastStageTrace,
                                envelope.progress.stageTrace
                            )
                            if (request.watchdogStartedAtMs == 0L &&
                                localImageWorkerWatchdogStartsAtPhase(envelope.progress.phase)
                            ) {
                                request.watchdogStartedAtMs = SystemClock.elapsedRealtime()
                            }
                            runCatching {
                                request.onProgress(
                                    envelope.progress.copy(stageTrace = request.lastStageTrace)
                                )
                            }
                        }
                    }
                    .onFailure { error ->
                        request.completion.completeExceptionally(
                            LocalImageWorkerException("Invalid progress from local image worker.", error)
                        )
                    }
            }

            override fun onComplete(payloadJson: String) {
                val envelope = runCatching { LocalImageWorkerProtocol.parseResult(payloadJson) }
                    .getOrElse { error ->
                        request.completion.completeExceptionally(
                            LocalImageWorkerException("Invalid result from local image worker.", error)
                        )
                        return
                }
                if (envelope.workerPid > 0) lastWorkerPid = envelope.workerPid
                if (envelope.workerPid > 0) request.workerPid = envelope.workerPid
                if (envelope.requestId != request.requestId) {
                    envelope.outputs.forEach { output -> deleteResultIfSafe(output.outputPath) }
                    request.completion.completeExceptionally(
                        LocalImageWorkerException("Local image worker returned a mismatched request id.")
                    )
                } else {
                    request.deliveredOutputPaths = envelope.outputs.map { it.outputPath }
                    if (!request.completion.complete(envelope)) {
                        envelope.outputs.forEach { output -> deleteResultIfSafe(output.outputPath) }
                    }
                }
            }

            override fun onError(payloadJson: String) {
                val envelope = runCatching { LocalImageWorkerProtocol.parseError(payloadJson) }
                    .getOrElse { error ->
                        request.completion.completeExceptionally(
                            LocalImageWorkerException("Invalid error from local image worker.", error)
                        )
                        return
                }
                if (envelope.workerPid > 0) lastWorkerPid = envelope.workerPid
                if (envelope.workerPid > 0) request.workerPid = envelope.workerPid
                if (envelope.requestId.isBlank() || envelope.requestId == request.requestId) {
                    val failure = if (envelope.code.equals(
                            LOCAL_IMAGE_GENERATION_CANCELLED_CODE,
                            ignoreCase = true
                        )
                    ) {
                        LocalImageWorkerCancelledException()
                    } else {
                        LocalImageWorkerRemoteException(envelope.code, envelope.message)
                    }
                    request.completion.completeExceptionally(
                        failure
                    )
                } else {
                    request.completion.completeExceptionally(
                        LocalImageWorkerException("Local image worker returned a mismatched request id.")
                    )
                }
            }
        }

    private suspend fun awaitService(
        onSessionSelected: (BindingSession) -> Unit = {}
    ): BoundWorker {
        lateinit var session: BindingSession
        var immediateService: ILocalImageWorker? = null
        var shouldBind = false
        synchronized(stateLock) {
            check(!closed) { "Local image worker client is closed." }
            val current = bindingSession
            val aliveRemote = remote?.takeIf { remoteBinder?.isBinderAlive == true }
            if (current != null) {
                session = current
                immediateService = aliveRemote
            } else {
                val lease = requireNotNull(bindingLifecycle.issueBind()) {
                    "Local image worker binding lifecycle has no active session but rejected a bind."
                }
                val connection = WorkerServiceConnection(lease)
                session = BindingSession(
                    lease = lease,
                    connection = connection,
                    deferred = CompletableDeferred()
                )
                bindingSession = session
                shouldBind = true
            }
            // Publish the exact epoch to its owner while the selected session is
            // still protected by stateLock. A concurrent cancellation can then
            // release this session instead of losing the bind between selection
            // and owner publication.
            onSessionSelected(session)
        }
        if (shouldBind) {
            val bound = runCatching {
                appContext.bindService(
                    Intent(appContext, LocalImageWorkerService::class.java),
                    session.connection,
                    Context.BIND_AUTO_CREATE
                )
            }.getOrElse { error ->
                failBinding(session, error)
                false
            }
            if (!bound) {
                failBinding(
                    session,
                    LocalImageWorkerDisconnectedException("Unable to bind local image worker.")
                )
            } else if (!isCurrentBindingSession(session)) {
                // The owner may finish while bindService is in progress. In that
                // case its earlier unbind can run before Android registers this
                // connection, so the completing bind attempt must retire itself.
                session.deferred.completeExceptionally(
                    LocalImageWorkerDisconnectedException(
                        "Local image worker binding was superseded before connection completed."
                    )
                )
                runCatching { appContext.unbindService(session.connection) }
            }
        }
        return BoundWorker(
            service = immediateService ?: session.deferred.await(),
            session = session
        )
    }

    private fun handleServiceConnected(
        connection: WorkerServiceConnection,
        binder: IBinder
    ) {
        val service = ILocalImageWorker.Stub.asInterface(binder)
        val deathRecipient = IBinder.DeathRecipient {
            handleConnectionLoss(connection, "Local image worker process died.")
        }
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (_: RemoteException) {
            handleConnectionLoss(
                connection,
                "Local image worker process died while connecting."
            )
            return
        }

        val deferred: CompletableDeferred<ILocalImageWorker>?
        val stale: Boolean
        synchronized(stateLock) {
            val current = bindingSession
            stale = closed || current == null || current.connection !== connection ||
                !bindingLifecycle.isCurrent(connection.lease)
            if (stale) {
                deferred = null
            } else {
                unlinkRemoteDeathRecipientLocked()
                remote = service
                remoteBinder = binder
                remoteDeathRecipient = deathRecipient
                deferred = current.deferred
            }
        }
        if (stale) {
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
            runCatching { appContext.unbindService(connection) }
            return
        }
        deferred?.complete(service)
    }

    private fun failBinding(session: BindingSession, error: Throwable) {
        val shouldFail = synchronized(stateLock) {
            if (bindingSession !== session || !bindingLifecycle.isCurrent(session.lease)) {
                false
            } else {
                bindingLifecycle.release(session.lease)
                bindingSession = null
                unlinkRemoteDeathRecipientLocked()
                remote = null
                remoteBinder = null
                true
            }
        }
        if (shouldFail) session.deferred.completeExceptionally(remoteFailure(error))
    }

    private fun handleConnectionLoss(
        connection: WorkerServiceConnection,
        message: String
    ) {
        val failure = LocalImageWorkerDisconnectedException(message)
        val session: BindingSession
        val active: ActiveRequest?
        val pendingPreparation: Preparation?
        synchronized(stateLock) {
            val current = bindingSession
            if (current == null || current.connection !== connection ||
                !bindingLifecycle.isCurrent(connection.lease)
            ) {
                return
            }
            session = current
            unlinkRemoteDeathRecipientLocked()
            remote = null
            remoteBinder = null
            active = activeRequest
            pendingPreparation = preparation
            preparation = null
            check(bindingLifecycle.release(session.lease)) {
                "Current local image worker binding session could not be released."
            }
            bindingSession = null
        }
        session.deferred.completeExceptionally(failure)
        pendingPreparation?.ready?.completeExceptionally(failure)
        active?.completion?.completeExceptionally(failure)
        runCatching { appContext.unbindService(session.connection) }
    }

    /**
     * A preparation can be cancelled and removed before its coroutine is first scheduled. If that
     * late coroutine subsequently selects a binding session, it must retire the exact epoch it
     * selected unless a replacement preparation or active request has already claimed it.
     */
    private fun releaseBindingAfterPreparationFailure(
        candidate: Preparation,
        error: Throwable
    ) {
        val releasedSession = synchronized(stateLock) {
            val expectedSession = candidate.bindingSession ?: return@synchronized null
            val currentPreparation = preparation
            val replacementOwnsSession = currentPreparation
                ?.takeIf { it !== candidate }
                ?.bindingSession === expectedSession
            val activeRequestOwnsSession = activeRequest?.bindingSession === expectedSession
            if (replacementOwnsSession || activeRequestOwnsSession) {
                return@synchronized null
            }
            if (currentPreparation === candidate) preparation = null
            if (bindingSession !== expectedSession ||
                !bindingLifecycle.release(expectedSession.lease)
            ) {
                return@synchronized null
            }
            bindingSession = null
            unlinkRemoteDeathRecipientLocked()
            remote = null
            remoteBinder = null
            expectedSession
        }
        if (releasedSession != null) {
            releasedSession.deferred.completeExceptionally(remoteFailure(error))
            runCatching { appContext.unbindService(releasedSession.connection) }
        }
    }

    /**
     * A bound worker is a lease for exactly one generation. Keep [activeRequest]
     * installed until unbind finishes so a second caller cannot issue a new bind
     * that an older terminal cleanup could accidentally tear down.
     */
    private fun releaseBindingAfterRequest(request: ActiveRequest, runtime: LocalImageRuntime) {
        val pendingConnection: CompletableDeferred<ILocalImageWorker>?
        val releasedSession: BindingSession?
        synchronized(stateLock) {
            if (activeRequest !== request) return
            val prepared = preparation
            val expectedSession = request.bindingSession ?: prepared
                ?.takeIf { it.runtime == runtime }
                ?.bindingSession
            if (prepared?.runtime == runtime &&
                (expectedSession == null || prepared.bindingSession === expectedSession)
            ) {
                preparation = null
            }
            val current = bindingSession
            releasedSession = if (expectedSession != null && current === expectedSession &&
                bindingLifecycle.release(expectedSession.lease)
            ) {
                bindingSession = null
                unlinkRemoteDeathRecipientLocked()
                remote = null
                remoteBinder = null
                expectedSession
            } else {
                null
            }
            pendingConnection = releasedSession?.deferred
        }

        pendingConnection?.completeExceptionally(
            LocalImageWorkerDisconnectedException(
                "Local image worker request finished before its pending connection completed."
            )
        )
        if (releasedSession != null) {
            runCatching { appContext.unbindService(releasedSession.connection) }
        }

        synchronized(stateLock) {
            if (activeRequest === request) activeRequest = null
        }
    }

    private fun cancelRemote(request: ActiveRequest): Boolean {
        val endpoint = currentEndpoint() ?: return false
        val cancelled = runRemoteCall(endpoint) {
            endpoint.service.cancel(LocalImageWorkerProtocol.cancelRequest(request.requestId))
        }.getOrElse { false }
        if (cancelled) {
            // The worker also publishes a cancellation terminal, but a Binder callback is not a
            // reliable prerequisite for releasing the caller. Complete locally as soon as the
            // registered remote request confirms cancellation; the callback remains idempotent.
            request.completion.completeExceptionally(LocalImageWorkerCancelledException())
        }
        return cancelled
    }

    private fun startWatchdog(request: ActiveRequest, model: LocalImageModelRecord) {
        val policy = localImageWorkerWatchdogPolicy(
            runtime = model.runtime,
            family = model.family,
            steps = request.requestedSteps,
            useCfg = request.requestedUseCfg
        ) ?: return
        request.watchdogJob = scope.launch {
            while (!request.completion.isCompleted) {
                val startedAt = request.watchdogStartedAtMs
                if (startedAt <= 0L) {
                    delay(WATCHDOG_POLL_INTERVAL_MS)
                    continue
                }
                val remaining = policy.timeoutMs - (SystemClock.elapsedRealtime() - startedAt)
                if (remaining > 0L) {
                    delay(remaining.coerceAtMost(WATCHDOG_POLL_INTERVAL_MS))
                    continue
                }
                break
            }
            if (request.completion.isCompleted) return@launch

            request.watchdogTimedOut = true
            val timeout = LocalImageWorkerRemoteException(
                code = LOCAL_IMAGE_WORKER_WATCHDOG_TIMEOUT_CODE,
                message = localImageWorkerWatchdogMessage(
                    timeoutMs = policy.timeoutMs,
                    phase = request.lastProgressPhase,
                    stageTrace = request.lastStageTrace
                )
            )
            if (!request.completion.completeExceptionally(timeout)) return@launch

            // Native QNN graphExecute/contextCreate are synchronous vendor calls;
            // cooperative cancellation cannot unwind a stuck call.  This process
            // is declared disposable in the manifest, so terminate it after the
            // stable timeout result has won the completion race.
            cancelRemote(request)
            val workerPid = request.workerPid
            if (workerPid > 0 && workerPid != Process.myPid()) {
                runCatching { Process.killProcess(workerPid) }
            }
        }
    }

    private fun startUpscaleWatchdog(request: ActiveRequest) {
        val acceptedAt = SystemClock.elapsedRealtime()
        request.watchdogStartedAtMs = acceptedAt
        request.lastWorkerCallbackAtMs = acceptedAt
        request.watchdogJob = scope.launch {
            var timeoutReason = "maximum runtime"
            while (!request.completion.isCompleted) {
                val now = SystemClock.elapsedRealtime()
                val runtimeElapsed = now - request.watchdogStartedAtMs
                val heartbeatElapsed = now - request.lastWorkerCallbackAtMs
                val heartbeatTimeout = localImageUpscaleHeartbeatTimeoutMs(
                    request.lastProgressPhase
                )
                if (runtimeElapsed >= LOCAL_IMAGE_UPSCALE_MAX_RUNTIME_MS) {
                    timeoutReason = "maximum runtime"
                    break
                }
                if (heartbeatElapsed >= heartbeatTimeout) {
                    timeoutReason = "worker heartbeat"
                    break
                }
                val remainingRuntime = LOCAL_IMAGE_UPSCALE_MAX_RUNTIME_MS - runtimeElapsed
                val remainingHeartbeat = heartbeatTimeout - heartbeatElapsed
                delay(
                    minOf(
                        WATCHDOG_POLL_INTERVAL_MS,
                        remainingRuntime,
                        remainingHeartbeat
                    ).coerceAtLeast(1L)
                )
            }
            if (request.completion.isCompleted) return@launch

            request.watchdogTimedOut = true
            val timeout = LocalImageWorkerRemoteException(
                code = LOCAL_IMAGE_UPSCALE_WATCHDOG_TIMEOUT_CODE,
                message = "Local ESRGAN worker exceeded its bounded $timeoutReason deadline " +
                    "at phase=${request.lastProgressPhase.ifBlank { "unknown" }}."
            )
            if (!request.completion.completeExceptionally(timeout)) return@launch

            cancelRemote(request)
            val workerPid = request.workerPid
            if (workerPid > 0 && workerPid != Process.myPid()) {
                runCatching { Process.killProcess(workerPid) }
            }
        }
    }

    private fun currentEndpoint(): BoundWorker? = synchronized(stateLock) {
        val session = bindingSession ?: return@synchronized null
        val service = remote?.takeIf { remoteBinder?.isBinderAlive == true }
            ?: return@synchronized null
        BoundWorker(service, session)
    }

    private fun isCurrentBindingSession(session: BindingSession): Boolean =
        synchronized(stateLock) {
            !closed && bindingSession === session && bindingLifecycle.isCurrent(session.lease)
        }

    private fun currentPreparation(runtime: LocalImageRuntime): Preparation? = synchronized(stateLock) {
        preparation?.takeIf { it.runtime == runtime }
    }

    private fun isCurrentPreparation(candidate: Preparation): Boolean = synchronized(stateLock) {
        preparation === candidate
    }

    private fun <T> runRemoteCall(
        endpoint: BoundWorker,
        block: () -> T
    ): Result<T> = runCatching(block).onFailure { error ->
        if (error is RemoteException || error is DeadObjectException) {
            handleConnectionLoss(
                endpoint.session.connection,
                "Local image worker IPC failed."
            )
        }
    }

    private fun LocalImageWorkerProtocol.ResultEnvelope.consumeResult(): LocalImageResult {
        if (workerPid > 0) lastWorkerPid = workerPid
        val files = outputs.map { output -> output to validatedResultFile(output.outputPath) }
        require(files.map { it.second.canonicalPath }.distinct().size == files.size) {
            "Local image worker returned duplicate output paths."
        }
        return try {
            val localOutputs = files.map { (output, file) ->
                require(file.isFile && file.length() > 0L) {
                    "Local image worker returned an empty result at index ${output.index}."
                }
                LocalImageOutput(
                    bytes = file.readBytes(),
                    mimeType = output.mimeType,
                    seed = output.seed,
                    index = output.index
                )
            }
            val first = localOutputs.first()
            LocalImageResult(
                bytes = first.bytes,
                mimeType = first.mimeType,
                executionMetadataJson = executionMetadataJson,
                seed = first.seed,
                outputs = localOutputs
            )
        } finally {
            files.forEach { (_, file) -> runCatching { file.delete() } }
        }
    }

    private fun validatedResultFile(path: String): File {
        val root = resultDirectory().canonicalFile
        val candidate = File(path).canonicalFile
        require(candidate.path.startsWith(root.path + File.separator)) {
            "Local image worker returned a path outside its result directory."
        }
        return candidate
    }

    private fun deleteResultIfSafe(path: String) {
        runCatching { validatedResultFile(path).delete() }
    }

    private fun resultDirectory(): File =
        File(appContext.cacheDir, LocalImageWorkerService.RESULT_DIRECTORY)

    private fun unlinkRemoteDeathRecipientLocked() {
        val binder = remoteBinder
        val recipient = remoteDeathRecipient
        if (binder != null && recipient != null) {
            runCatching { binder.unlinkToDeath(recipient, 0) }
        }
        remoteDeathRecipient = null
    }

    private fun remoteFailure(error: Throwable): Throwable =
        when (error) {
            is LocalImageWorkerException -> error
            is RemoteException -> LocalImageWorkerDisconnectedException(
                "Local image worker IPC failed: ${error.message.orEmpty()}",
                error
            )
            else -> error
        }

    private inner class BindingSession(
        val lease: LocalImageWorkerBindingLifecycle.Session,
        val connection: WorkerServiceConnection,
        val deferred: CompletableDeferred<ILocalImageWorker>
    )

    private inner class BoundWorker(
        val service: ILocalImageWorker,
        val session: BindingSession
    )

    private inner class Preparation(val runtime: LocalImageRuntime) {
        val ready = CompletableDeferred<Unit>()

        @Volatile
        var cancelRequested: Boolean = false

        @Volatile
        var bindingSession: BindingSession? = null
    }

    private inner class ActiveRequest(
        val requestId: String,
        val runtime: LocalImageRuntime,
        val requestedSteps: Int?,
        val requestedUseCfg: Boolean?,
        val onProgress: (LocalImageProgress) -> Unit
    ) {
        val completion = CompletableDeferred<LocalImageWorkerProtocol.ResultEnvelope>()

        val handshake = LocalImageStartHandshake()

        @Volatile
        var deliveredOutputPaths: List<String> = emptyList()

        @Volatile
        var workerPid: Int = -1

        @Volatile
        var lastProgressPhase: String = "worker_starting"

        @Volatile
        var lastStageTrace: List<String> = emptyList()

        @Volatile
        var watchdogTimedOut: Boolean = false

        @Volatile
        var watchdogStartedAtMs: Long = 0L

        @Volatile
        var lastWorkerCallbackAtMs: Long = 0L

        @Volatile
        var watchdogJob: Job? = null

        @Volatile
        var bindingSession: BindingSession? = null
    }

    companion object {
        private const val WATCHDOG_POLL_INTERVAL_MS = 1_000L
    }
}

internal const val LOCAL_IMAGE_UPSCALE_WATCHDOG_TIMEOUT_CODE = "esrgan_worker_timeout"
internal const val LOCAL_IMAGE_UPSCALE_MAX_RUNTIME_MS = 2L * 60L * 60L * 1_000L
private const val LOCAL_IMAGE_UPSCALE_EXECUTION_HEARTBEAT_MS = 2L * 60L * 1_000L
private const val LOCAL_IMAGE_UPSCALE_PREPARATION_HEARTBEAT_MS = 15L * 60L * 1_000L
private const val LOCAL_IMAGE_UPSCALE_LEASE_WAIT_HEARTBEAT_MS = 45L * 60L * 1_000L

internal fun localImageUpscaleHeartbeatTimeoutMs(phase: String): Long = when (phase) {
    "worker_starting", "worker_preparing", "worker_started" ->
        LOCAL_IMAGE_UPSCALE_PREPARATION_HEARTBEAT_MS
    "waiting_for_native_lease" -> LOCAL_IMAGE_UPSCALE_LEASE_WAIT_HEARTBEAT_MS
    else -> LOCAL_IMAGE_UPSCALE_EXECUTION_HEARTBEAT_MS
}

/**
 * Binding admission state kept free of Android dependencies so terminal-release
 * and rebind behavior can be covered by local JVM tests. Callers serialize access
 * with their own connection-state lock.
 */
internal class LocalImageWorkerBindingLifecycle {
    class Session internal constructor(val epoch: Long)

    private var nextEpoch: Long = 0L
    private var activeSession: Session? = null

    val bindIssued: Boolean
        get() = activeSession != null

    fun issueBind(): Session? {
        if (activeSession != null) return null
        check(nextEpoch < Long.MAX_VALUE) { "Local image worker binding epoch exhausted." }
        val session = Session(++nextEpoch)
        activeSession = session
        return session
    }

    fun isCurrent(session: Session): Boolean = activeSession === session

    fun release(session: Session): Boolean {
        if (activeSession !== session) return false
        activeSession = null
        return true
    }
}

open class LocalImageWorkerException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

class LocalImageWorkerDisconnectedException(
    message: String,
    cause: Throwable? = null
) : LocalImageWorkerException(message, cause)

class LocalImageWorkerRemoteException(
    val code: String,
    message: String
) : LocalImageWorkerException(message)

class LocalImageWorkerCancelledException :
    LocalImageWorkerException("Local image generation was cancelled.")
