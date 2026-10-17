package com.muyuchat.mca

import com.muyuchat.mca.facade.ImageExecutionGate

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal const val LOCAL_IMAGE_WORKER_REBIND_COOLDOWN_MS = 500L

/**
 * A missing request ID is reserved for legacy lifecycle cancellation. An explicitly blank ID is
 * never allowed to match a live request: callers that own a request must carry its exact ID.
 */
internal fun localImageCancelRequestMatches(
    expectedRequestId: String?,
    activeRequestId: String?
): Boolean {
    if (activeRequestId.isNullOrBlank()) return false
    return expectedRequestId == null ||
        (expectedRequestId.isNotBlank() && expectedRequestId == activeRequestId)
}

class LocalImageWorkerClient(
    context: Context,
    /**
     * E07: the single image execution gate. Every generate/upscale passes through it so one
     * process-wide RuntimeSupervisor owns identity, cancellation and the exactly-once terminal
     * record; the production container installs ImageExecutionOwner, transport-only embeddings
     * keep the passthrough default.
     */
    internal val executionGate: ImageExecutionGate = ImageExecutionGate.PASSTHROUGH,
) : AutoCloseable {
    internal val appContext = context.applicationContext
    internal val inputDispatcher = LocalImageInputDispatcher(appContext)
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val stateLock = Any()

    internal var remote: ILocalImageWorker? = null
    internal var remoteBinder: IBinder? = null
    internal var remoteDeathRecipient: IBinder.DeathRecipient? = null
    internal var bindingSession: BindingSession? = null
    internal val bindingLifecycle = LocalImageWorkerBindingLifecycle()
    internal var closed = false
    internal var preparation: Preparation? = null
    internal var activeRequest: ActiveRequest? = null
    /** The service is disposable and self-terminates after unbind; avoid binding its retiring PID. */
    internal var lastBindingReleaseAtElapsedMs: Long = 0L
    /** Retiring bindings stay tracked until their own Binder death, including across batches. */
    internal val releasedWorkerExits = mutableMapOf<IBinder, CompletableDeferred<Unit>>()

    @Volatile
    var lastWorkerPid: Int = -1
        private set

    internal inner class WorkerServiceConnection(
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
                if (next.cancelRequested || !isCurrentPreparation(next)) {
                    val cancelled = LocalImageWorkerCancelledException()
                    releaseBindingAfterPreparationFailure(next, cancelled)
                    next.ready.completeExceptionally(cancelled)
                } else {
                    next.ready.complete(Unit)
                }
            }.onFailure { error ->
                releaseBindingAfterPreparationFailure(next, error)
                next.ready.completeExceptionally(remoteFailure(error))
            }
        }
    }

    /** Lifecycle fallback for owners that intentionally cancel whichever operation is active. */
    fun cancel(): Boolean {
        // Scope the supervisor bookkeeping to the image request actually being cancelled: the
        // process-wide supervisor is shared with chat, and an unscoped lifecycle cancel must not
        // mark unrelated live chat attempts.
        val activeImageRequestId = synchronized(stateLock) { activeRequest?.requestId }
        val cancelled = cancelInternal(expectedRequestId = null, scoped = false)
        if (cancelled && activeImageRequestId != null) {
            executionGate.cancel(activeImageRequestId)
        }
        return cancelled
    }

    /** Cancel only the request that the caller still owns. A stale ID is a no-op. */
    fun cancel(expectedRequestId: String): Boolean {
        if (expectedRequestId.isBlank()) return false
        val cancelled = cancelInternal(expectedRequestId = expectedRequestId, scoped = true)
        if (cancelled) {
            executionGate.cancel(expectedRequestId)
        }
        return cancelled
    }

    /**
     * Waits for the worker process belonging to the last request to actually die. Unbinding or
     * receiving a successful cancel RPC only relinquishes the client lease and is not a native
     * memory release proof.
     */
    suspend fun awaitOwnedWorkerRelease(timeoutMs: Long = WORKER_EXIT_CONFIRM_TIMEOUT_MS) {
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        val waiters = synchronized(stateLock) { releasedWorkerExits.values.toList() }
        if (withTimeoutOrNull(timeoutMs) { waiters.forEach { it.await() }; true } != true) {
            throw LocalImageWorkerRemoteException(
                code = "image_worker_exit_unconfirmed",
                message = "The local image worker did not exit within ${timeoutMs}ms; native memory release is unconfirmed."
            )
        }
    }

    private fun cancelInternal(expectedRequestId: String?, scoped: Boolean): Boolean {
        val snapshot = synchronized(stateLock) {
            val request = activeRequest?.takeIf {
                localImageCancelRequestMatches(expectedRequestId, it.requestId)
            }
            if (scoped && request == null) return@synchronized null
            preparation?.also { it.cancelRequested = true }
            val cancelAction = request?.handshake?.requestCancel()
            Triple(request, preparation, cancelAction)
        } ?: return false
        val request = snapshot.first
        val pendingPreparation = snapshot.second
        val runtime = request?.runtime ?: pendingPreparation?.runtime
        val supportsNativeCancel = runtime == LocalImageRuntime.QNN_HTP ||
            runtime == LocalImageRuntime.MNN_DIFFUSION ||
            runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP
        if (request == null) {
            pendingPreparation?.ready?.completeExceptionally(LocalImageWorkerCancelledException())
            if (!scoped) currentEndpoint()?.let { endpoint ->
                runRemoteCall(endpoint) {
                    endpoint.service.cancel(LocalImageWorkerProtocol.cancelRequest(null))
                }
            }
            // A preparation has no registered native request whose terminal can release the UI.
            // Report false so the owner cancels prompt preprocessing or other caller-side work.
            return false
        }
        return when (snapshot.third) {
            LocalImageStartHandshake.CancelAction.COMPLETE_LOCALLY -> {
                val cancellation = LocalImageWorkerCancelledException()
                pendingPreparation?.ready?.completeExceptionally(cancellation)
                request.completion.completeExceptionally(cancellation)
                supportsNativeCancel
            }
            LocalImageStartHandshake.CancelAction.DEFER_UNTIL_REGISTERED -> supportsNativeCancel
            LocalImageStartHandshake.CancelAction.CANCEL_REMOTE -> cancelRemote(request)
            LocalImageStartHandshake.CancelAction.NONE -> false
            null -> false
        }
    }

    suspend fun generate(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions = LocalImageGenerationOptions(),
        inputDraft: LocalImageInputDraft = LocalImageInputDraft(),
        requestId: String = UUID.randomUUID().toString(),
        batchLineage: ImageGenerationBatchLineage? = null,
        onProgress: (LocalImageProgress) -> Unit = {}
    ): LocalImageResult {
        return executionGate.run(requestId, "generate") {
        val request = ActiveRequest(
            requestId = requestId,
            runtime = model.runtime,
            requestedSteps = options.steps,
            requestedUseCfg = options.useCfg,
            requestedBackendMode = options.backendMode,
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
            if (request.completion.isCompleted) return@run request.completion.await().consumeResult()

            val endpoint = awaitService { session -> request.bindingSession = session }
            val service = endpoint.service
            if (request.completion.isCompleted) return@run request.completion.await().consumeResult()
            if (!request.handshake.tryBeginRemoteStart()) throw LocalImageWorkerCancelledException()

            val callback = callbackFor(request)
            val accepted = try {
                service.generate(
                    LocalImageWorkerProtocol.generateRequest(
                        request.requestId,
                        model,
                        prompt,
                        dispatchedOptions,
                        batchLineage
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
            return@run result.consumeResult()
        } catch (cancelled: CancellationException) {
            request.completion.cancel(cancelled)
            if (request.handshake.requestCancel() == LocalImageStartHandshake.CancelAction.CANCEL_REMOTE) {
                cancelRemote(request)
            }
            throw cancelled
        } finally {
            request.handshake.markFinished()
            awaitWatchdogTerminationDispatch(request)
            if (!request.watchdogTimedOut) request.watchdogJob?.cancel()
            request.deliveredOutputPaths.forEach(::deleteResultIfSafe)
            releaseBindingAfterRequest(request, model.runtime)
            inputDispatch?.directory?.deleteRecursively()
        }
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

    internal fun callbackFor(request: ActiveRequest): ILocalImageWorkerCallback =
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

    internal fun isCurrentBindingSession(session: BindingSession): Boolean =
        synchronized(stateLock) {
            !closed && bindingSession === session && bindingLifecycle.isCurrent(session.lease)
        }

    internal fun currentPreparation(runtime: LocalImageRuntime): Preparation? = synchronized(stateLock) {
        preparation?.takeIf { it.runtime == runtime }
    }

    private fun isCurrentPreparation(candidate: Preparation): Boolean = synchronized(stateLock) {
        preparation === candidate
    }

    internal fun LocalImageWorkerProtocol.ResultEnvelope.consumeResult(): LocalImageResult {
        if (workerPid > 0) lastWorkerPid = workerPid
        // Do not let a malformed/empty terminal escape as NoSuchElementException
        // from `first()`.  Native image runtimes can terminate without writing
        // an artifact (for example after a delegate abort or a partial bundle),
        // and that exception used to surface as an app crash instead of a
        // recoverable generation error.  Validate the complete envelope before
        // touching any output file and keep the error code stable for UI/API
        // mapping.
        if (outputs.isEmpty()) {
            throw LocalImageWorkerRemoteException(
                code = "empty_result",
                message = "Local image worker returned no image outputs. The model did not produce a readable image; retry or reload the model."
            )
        }
        require(outputs.map { it.index } == outputs.indices.toList()) {
            "Local image worker returned non-contiguous output indices."
        }
        require(outputs.all { it.outputPath.isNotBlank() }) {
            "Local image worker returned a blank output path."
        }
        require(outputs.all { it.mimeType.startsWith("image/", ignoreCase = true) }) {
            "Local image worker returned a non-image MIME type."
        }
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

    internal inner class BindingSession(
        val lease: LocalImageWorkerBindingLifecycle.Session,
        val connection: WorkerServiceConnection,
        val deferred: CompletableDeferred<ILocalImageWorker>
    )

    internal inner class BoundWorker(
        val service: ILocalImageWorker,
        val session: BindingSession
    )

    internal inner class Preparation(val runtime: LocalImageRuntime) {
        val ready = CompletableDeferred<Unit>()

        @Volatile
        var cancelRequested: Boolean = false

        @Volatile
        var bindingSession: BindingSession? = null
    }

    internal inner class ActiveRequest(
        val requestId: String,
        val runtime: LocalImageRuntime,
        val requestedSteps: Int?,
        val requestedUseCfg: Boolean?,
        val requestedBackendMode: String?,
        val onProgress: (LocalImageProgress) -> Unit
    ) {
        val completion = CompletableDeferred<LocalImageWorkerProtocol.ResultEnvelope>()

        val watchdogTerminationDispatched = CompletableDeferred<Unit>()

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
        private const val WORKER_EXIT_CONFIRM_TIMEOUT_MS = 10_000L
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
