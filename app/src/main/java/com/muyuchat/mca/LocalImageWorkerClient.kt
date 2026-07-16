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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()

    private var remote: ILocalImageWorker? = null
    private var remoteBinder: IBinder? = null
    private var remoteDeathRecipient: IBinder.DeathRecipient? = null
    private var connectionDeferred: CompletableDeferred<ILocalImageWorker>? = null
    private val bindingLifecycle = LocalImageWorkerBindingLifecycle()
    private var closed = false
    private var preparation: Preparation? = null
    private var activeRequest: ActiveRequest? = null

    @Volatile
    var lastWorkerPid: Int = -1
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = ILocalImageWorker.Stub.asInterface(binder)
            val deathRecipient = IBinder.DeathRecipient {
                handleConnectionLoss("Local image worker process died.", bindingDied = false)
            }
            try {
                binder.linkToDeath(deathRecipient, 0)
            } catch (_: RemoteException) {
                handleConnectionLoss("Local image worker process died while connecting.", bindingDied = false)
                return
            }

            val deferred: CompletableDeferred<ILocalImageWorker>?
            val shouldUnbind: Boolean
            synchronized(stateLock) {
                shouldUnbind = closed || !bindingLifecycle.bindIssued
                if (shouldUnbind) {
                    deferred = null
                } else {
                    unlinkRemoteDeathRecipientLocked()
                    remote = service
                    remoteBinder = binder
                    remoteDeathRecipient = deathRecipient
                    deferred = connectionDeferred
                    connectionDeferred = null
                }
            }
            if (shouldUnbind) {
                runCatching { binder.unlinkToDeath(deathRecipient, 0) }
                runCatching { appContext.unbindService(this) }
                return
            }
            deferred?.complete(service)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            handleConnectionLoss("Local image worker disconnected.", bindingDied = false)
        }

        override fun onBindingDied(name: ComponentName) {
            handleConnectionLoss("Local image worker binding died.", bindingDied = true)
        }

        override fun onNullBinding(name: ComponentName) {
            handleConnectionLoss("Local image worker returned a null binding.", bindingDied = true)
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
                val service = awaitService()
                if (!isCurrentPreparation(next) || next.cancelRequested) {
                    throw LocalImageWorkerCancelledException()
                }
                service.begin(LocalImageWorkerProtocol.beginRequest(runtime))
            }.onSuccess {
                next.ready.complete(Unit)
            }.onFailure { error ->
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
            currentRemote()?.let { service ->
                runRemoteCall { service.cancel(LocalImageWorkerProtocol.cancelRequest(null)) }
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

        try {
            val prepared = currentPreparation(model.runtime) ?: run {
                begin(model.runtime)
                requireNotNull(currentPreparation(model.runtime))
            }
            prepared.ready.await()
            if (prepared.cancelRequested) {
                throw LocalImageWorkerCancelledException()
            }
            if (request.completion.isCompleted) return request.completion.await().consumeResult()

            val service = awaitService()
            if (request.completion.isCompleted) return request.completion.await().consumeResult()
            if (!request.handshake.tryBeginRemoteStart()) throw LocalImageWorkerCancelledException()

            val callback = callbackFor(request)
            val accepted = try {
                service.generate(
                    LocalImageWorkerProtocol.generateRequest(request.requestId, model, prompt, options),
                    callback
                )
            } catch (error: RemoteException) {
                request.handshake.markFinished()
                handleConnectionLoss("Local image worker call failed: ${error.message.orEmpty()}", bindingDied = false)
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
            request.deliveredOutputPath?.let(::deleteResultIfSafe)
            releaseBindingAfterRequest(request, model.runtime)
        }
    }

    override fun close() {
        val service: ILocalImageWorker?
        val request: ActiveRequest?
        val pendingConnection: CompletableDeferred<ILocalImageWorker>?
        val pendingPreparation: Preparation?
        val shouldUnbind: Boolean
        synchronized(stateLock) {
            if (closed) return
            closed = true
            service = remote
            request = activeRequest
            pendingConnection = connectionDeferred
            pendingPreparation = preparation
            shouldUnbind = bindingLifecycle.release()
            activeRequest = null
            preparation = null
            connectionDeferred = null
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
        if (shouldUnbind) runCatching { appContext.unbindService(connection) }
        cleanupWorkerResults()
    }

    private fun callbackFor(request: ActiveRequest): ILocalImageWorkerCallback =
        object : ILocalImageWorkerCallback.Stub() {
            override fun onProgress(payloadJson: String) {
                runCatching { LocalImageWorkerProtocol.parseProgress(payloadJson) }
                    .onSuccess { envelope ->
                        if (envelope.workerPid > 0) lastWorkerPid = envelope.workerPid
                        if (envelope.requestId == request.requestId && !request.completion.isCompleted) {
                            if (envelope.workerPid > 0) request.workerPid = envelope.workerPid
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
                    deleteResultIfSafe(envelope.outputPath)
                    request.completion.completeExceptionally(
                        LocalImageWorkerException("Local image worker returned a mismatched request id.")
                    )
                } else {
                    request.deliveredOutputPath = envelope.outputPath
                    if (!request.completion.complete(envelope)) {
                        deleteResultIfSafe(envelope.outputPath)
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
                    request.completion.completeExceptionally(
                        LocalImageWorkerRemoteException(envelope.code, envelope.message)
                    )
                } else {
                    request.completion.completeExceptionally(
                        LocalImageWorkerException("Local image worker returned a mismatched request id.")
                    )
                }
            }
        }

    private suspend fun awaitService(): ILocalImageWorker {
        synchronized(stateLock) {
            check(!closed) { "Local image worker client is closed." }
            remote?.takeIf { remoteBinder?.isBinderAlive == true }?.let { return it }
        }

        val deferred: CompletableDeferred<ILocalImageWorker>
        val shouldBind: Boolean
        synchronized(stateLock) {
            check(!closed) { "Local image worker client is closed." }
            remote?.takeIf { remoteBinder?.isBinderAlive == true }?.let { return it }
            deferred = connectionDeferred ?: CompletableDeferred<ILocalImageWorker>().also {
                connectionDeferred = it
            }
            shouldBind = bindingLifecycle.issueBind()
        }
        if (shouldBind) {
            val bound = runCatching {
                appContext.bindService(
                    Intent(appContext, LocalImageWorkerService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            }.getOrElse { error ->
                failBinding(error)
                false
            }
            if (!bound) {
                failBinding(LocalImageWorkerDisconnectedException("Unable to bind local image worker."))
            }
        }
        return deferred.await()
    }

    private fun failBinding(error: Throwable) {
        val deferred = synchronized(stateLock) {
            bindingLifecycle.release()
            connectionDeferred.also { connectionDeferred = null }
        }
        deferred?.completeExceptionally(remoteFailure(error))
    }

    private fun handleConnectionLoss(message: String, bindingDied: Boolean) {
        val failure = LocalImageWorkerDisconnectedException(message)
        val pendingConnection: CompletableDeferred<ILocalImageWorker>?
        val active: ActiveRequest?
        val pendingPreparation: Preparation?
        val shouldUnbind: Boolean
        synchronized(stateLock) {
            unlinkRemoteDeathRecipientLocked()
            remote = null
            remoteBinder = null
            pendingConnection = connectionDeferred
            connectionDeferred = null
            active = activeRequest
            pendingPreparation = preparation
            preparation = null
            shouldUnbind = bindingDied && bindingLifecycle.release()
        }
        pendingConnection?.completeExceptionally(failure)
        pendingPreparation?.ready?.completeExceptionally(failure)
        active?.completion?.completeExceptionally(failure)
        if (shouldUnbind) runCatching { appContext.unbindService(connection) }
    }

    /**
     * A bound worker is a lease for exactly one generation. Keep [activeRequest]
     * installed until unbind finishes so a second caller cannot issue a new bind
     * that an older terminal cleanup could accidentally tear down.
     */
    private fun releaseBindingAfterRequest(request: ActiveRequest, runtime: LocalImageRuntime) {
        val pendingConnection: CompletableDeferred<ILocalImageWorker>?
        val shouldUnbind: Boolean
        synchronized(stateLock) {
            if (activeRequest !== request) return
            if (preparation?.runtime == runtime) preparation = null
            pendingConnection = connectionDeferred
            connectionDeferred = null
            shouldUnbind = bindingLifecycle.release()
            unlinkRemoteDeathRecipientLocked()
            remote = null
            remoteBinder = null
        }

        pendingConnection?.completeExceptionally(
            LocalImageWorkerDisconnectedException(
                "Local image worker request finished before its pending connection completed."
            )
        )
        if (shouldUnbind) runCatching { appContext.unbindService(connection) }

        synchronized(stateLock) {
            if (activeRequest === request) activeRequest = null
        }
    }

    private fun cancelRemote(request: ActiveRequest): Boolean {
        val service = currentRemote() ?: return false
        return runRemoteCall {
            service.cancel(LocalImageWorkerProtocol.cancelRequest(request.requestId))
        }.getOrElse { false }
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

    private fun currentRemote(): ILocalImageWorker? = synchronized(stateLock) {
        remote?.takeIf { remoteBinder?.isBinderAlive == true }
    }

    private fun currentPreparation(runtime: LocalImageRuntime): Preparation? = synchronized(stateLock) {
        preparation?.takeIf { it.runtime == runtime }
    }

    private fun isCurrentPreparation(candidate: Preparation): Boolean = synchronized(stateLock) {
        preparation === candidate
    }

    private fun <T> runRemoteCall(block: () -> T): Result<T> = runCatching(block).onFailure { error ->
        if (error is RemoteException || error is DeadObjectException) {
            handleConnectionLoss("Local image worker IPC failed.", bindingDied = false)
        }
    }

    private fun LocalImageWorkerProtocol.ResultEnvelope.consumeResult(): LocalImageResult {
        if (workerPid > 0) lastWorkerPid = workerPid
        val file = validatedResultFile(outputPath)
        return try {
            require(file.isFile && file.length() > 0L) { "Local image worker returned an empty result." }
            LocalImageResult(
                bytes = file.readBytes(),
                mimeType = mimeType,
                executionMetadataJson = executionMetadataJson
            )
        } finally {
            runCatching { file.delete() }
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

    private fun cleanupWorkerResults() {
        resultDirectory().listFiles()?.forEach { file ->
            if (file.isFile) runCatching { file.delete() }
        }
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

    private class Preparation(val runtime: LocalImageRuntime) {
        val ready = CompletableDeferred<Unit>()

        @Volatile
        var cancelRequested: Boolean = false
    }

    private class ActiveRequest(
        val requestId: String,
        val runtime: LocalImageRuntime,
        val requestedSteps: Int?,
        val requestedUseCfg: Boolean?,
        val onProgress: (LocalImageProgress) -> Unit
    ) {
        val completion = CompletableDeferred<LocalImageWorkerProtocol.ResultEnvelope>()

        val handshake = LocalImageStartHandshake()

        @Volatile
        var deliveredOutputPath: String? = null

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
        var watchdogJob: Job? = null
    }

    companion object {
        private const val WATCHDOG_POLL_INTERVAL_MS = 1_000L
    }
}

/**
 * Binding admission state kept free of Android dependencies so terminal-release
 * and rebind behavior can be covered by local JVM tests. Callers serialize access
 * with their own connection-state lock.
 */
internal class LocalImageWorkerBindingLifecycle {
    var bindIssued: Boolean = false
        private set

    fun issueBind(): Boolean {
        if (bindIssued) return false
        bindIssued = true
        return true
    }

    fun release(): Boolean {
        val shouldUnbind = bindIssued
        bindIssued = false
        return shouldUnbind
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
