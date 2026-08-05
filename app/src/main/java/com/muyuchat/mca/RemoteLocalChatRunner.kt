package com.muyuchat.mca

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.LocalChatRunner
import com.muyuchat.core.engine.PersistentPrefixCacheRequest
import com.muyuchat.core.engine.TokenProgress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** Main-process proxy for [LocalChatWorkerService]. */
internal class RemoteLocalChatRunner(
    context: Context,
    override val runtime: LocalChatRuntime
) : LocalChatRunner, AutoCloseable {
    private val appContext = context.applicationContext
    private val stageJournal = LocalChatWorkerStageJournal.forContext(appContext)
    private val stateLock = Any()
    private var activeBinding: BindingAttempt? = null
    private var connection: ServiceConnection? = null
    private var bound = false
    private var remote: ILocalChatWorker? = null
    private var nativeLibDir: String = ""
    private var modelLoadedInWorker = false
    private var workerSessionLost = false
    private var closed = false

    @Volatile
    private var failure: Throwable? = null

    override val isAvailable: Boolean
        get() = synchronized(stateLock) { !closed }

    override val loadError: Throwable?
        get() = failure

    override fun initBackends(nativeLibDir: String) {
        synchronized(stateLock) {
            check(!closed) { "The isolated local chat runner is closed." }
            this.nativeLibDir = nativeLibDir
        }
        // Binding is deferred until the first operation so construction stays
        // non-blocking on the main process.
    }

    override fun loadModel(modelPath: String, paramsJson: String): Int =
        invoke { service ->
            service.initRuntime(runtime.name, nativeLibDir)
            val result = service.loadModel(runtime.name, modelPath, paramsJson)
            synchronized(stateLock) {
                if (remote === service) {
                    modelLoadedInWorker = result == 0
                    workerSessionLost = false
                    if (result == 0) failure = null
                }
            }
            result
        }

    override fun unloadModel() {
        runCatching { invoke { service -> service.unloadModel(); Unit } }
        synchronized(stateLock) {
            modelLoadedInWorker = false
            workerSessionLost = false
        }
    }

    override fun beginCompletion(messagesJson: String, paramsJson: String): Int =
        invoke { service ->
            withRequestPayload(
                LocalChatWorkerRequestTransport.BeginRequest(
                    messagesJson = messagesJson,
                    paramsJson = paramsJson,
                    restoreStatePath = null,
                    writeStatePath = null,
                    fixedSystemPrompt = null
                )
            ) { descriptor ->
                service.beginCompletion(descriptor)
            }
        }

    override fun beginCompletionWithPrefixCache(
        messagesJson: String,
        paramsJson: String,
        prefixCache: PersistentPrefixCacheRequest?
    ): Int {
        if (prefixCache == null) return beginCompletion(messagesJson, paramsJson)
        return invoke { service ->
            withRequestPayload(
                LocalChatWorkerRequestTransport.BeginRequest(
                    messagesJson = messagesJson,
                    paramsJson = paramsJson,
                    restoreStatePath = prefixCache.restoreStatePath,
                    writeStatePath = prefixCache.writeStatePath,
                    fixedSystemPrompt = prefixCache.fixedSystemPrompt
                )
            ) { descriptor ->
                service.beginCompletionWithPrefixCache(descriptor)
            }
        }
    }

    override fun prefillProgress(): TokenProgress? {
        val service = remoteIfBound() ?: return null
        return try {
            val root = JSONObject(service.getPrefillProgressJson())
            val total = root.optInt("totalTokens", 0)
            val completed = root.optInt("completedTokens", -1)
            if (total > 0 && completed in 0..total) {
                TokenProgress(completedTokens = completed, totalTokens = total)
            } else {
                null
            }
        } catch (error: DeadObjectException) {
            handleRemoteFailure(service, disconnectedFailure("crashed or was reclaimed", error))
            null
        } catch (error: RemoteException) {
            handleRemoteFailure(service, disconnectedFailure("disconnected", error))
            null
        } catch (_: Throwable) {
            null
        }
    }

    override fun resetPrefillProgress() {
        // This call must cross the process boundary. A no-op here could expose
        // the previous request's terminal snapshot while the next begin runs.
        invoke { service -> service.resetPrefillProgress(); Unit }
    }

    override fun generateNextChunk(): String? =
        invoke { service -> service.generateNextChunk() }

    override fun invalidateConversationContext() {
        invoke { service -> service.invalidateConversationContext(); Unit }
    }

    override fun requestStop() {
        val service = remoteIfBound() ?: return
        try {
            service.requestStop()
        } catch (error: DeadObjectException) {
            handleRemoteFailure(service, disconnectedFailure("crashed or was reclaimed", error))
        } catch (error: RemoteException) {
            handleRemoteFailure(service, disconnectedFailure("disconnected", error))
        }
    }

    override fun requestStopIfActive(): Boolean {
        val service = remoteIfBound() ?: return false
        return try {
            service.requestStopIfActive()
        } catch (error: DeadObjectException) {
            handleRemoteFailure(service, disconnectedFailure("crashed or was reclaimed", error))
            false
        } catch (error: RemoteException) {
            handleRemoteFailure(service, disconnectedFailure("disconnected", error))
            false
        }
    }

    override fun getRuntimeStatsJson(): String {
        val service = synchronized(stateLock) {
            if (workerSessionLost) return workerUnavailableStatsLocked()
            remote
        } ?: return synchronized(stateLock) {
            if (modelLoadedInWorker) {
                markWorkerSessionLostLocked(
                    workerFailure("lost its loaded model connection.")
                )
            }
            workerUnavailableStatsLocked()
        }

        return try {
            val stats = service.getRuntimeStatsJson()
            val reportsLoaded = runCatching {
                JSONObject(stats).optBoolean("loaded", false)
            }.getOrDefault(false)
            val expectedLoaded = synchronized(stateLock) {
                remote === service && modelLoadedInWorker
            }
            if (expectedLoaded && !reportsLoaded) {
                val error = workerFailure("no longer has the loaded model.")
                handleRemoteFailure(service, error)
                synchronized(stateLock) { workerUnavailableStatsLocked() }
            } else {
                runCatching {
                    JSONObject(stats)
                        .put(WORKER_SESSION_LOST_FIELD, false)
                        .toString()
                }.getOrDefault(stats)
            }
        } catch (error: DeadObjectException) {
            handleRemoteFailure(service, disconnectedFailure("crashed or was reclaimed", error))
            synchronized(stateLock) { workerUnavailableStatsLocked() }
        } catch (error: RemoteException) {
            handleRemoteFailure(service, disconnectedFailure("disconnected", error))
            synchronized(stateLock) { workerUnavailableStatsLocked() }
        }
    }

    override fun shutdown() = close()

    override fun close() {
        val snapshot = synchronized(stateLock) {
            if (closed) return
            closed = true
            val endpoint = remote
            val attempt = activeBinding
            activeBinding = null
            connection = null
            bound = false
            remote = null
            modelLoadedInWorker = false
            workerSessionLost = false
            attempt?.error?.compareAndSet(
                null,
                RemoteLocalChatRunnerException("The isolated local chat runner is closed.")
            )
            endpoint to attempt
        }
        snapshot.second?.connected?.countDown()
        runCatching { snapshot.first?.shutdown() }
        snapshot.second?.let(::releaseBinding)
    }

    private fun remoteIfBound(): ILocalChatWorker? = synchronized(stateLock) { remote }

    private fun <T> invoke(block: (ILocalChatWorker) -> T): T {
        val service = ensureConnected()
        return try {
            block(service)
        } catch (error: DeadObjectException) {
            val failure = disconnectedFailure("crashed or was reclaimed", error)
            handleRemoteFailure(service, failure)
            throw failure
        } catch (error: RemoteException) {
            val failure = disconnectedFailure("disconnected", error)
            handleRemoteFailure(service, failure)
            throw failure
        } catch (error: IllegalStateException) {
            if (!error.message.orEmpty().contains(NO_MODEL_LOADED_MESSAGE, ignoreCase = true)) {
                throw error
            }
            val failure = workerFailure("lost its loaded model state.", error)
            handleRemoteFailure(service, failure)
            throw failure
        }
    }

    private fun ensureConnected(): ILocalChatWorker {
        val (attempt, shouldBind) = synchronized(stateLock) {
            check(!closed) { "The isolated local chat runner is closed." }
            remote?.let { return it }
            activeBinding?.let { it to false } ?: createBindingAttempt().let { candidate ->
                activeBinding = candidate
                connection = candidate.connection
                candidate to true
            }
        }

        if (shouldBind) startBinding(attempt)
        if (!attempt.connected.await(CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            val timeout = workerFailure("timed out while binding.")
            invalidateBinding(attempt, timeout)
            throw timeout
        }
        attempt.error.get()?.let { throw it }
        return synchronized(stateLock) {
            check(!closed) { "The isolated local chat runner is closed." }
            remote?.takeIf { activeBinding === attempt }
                ?: throw workerFailure("did not return a Binder.")
        }
    }

    private fun createBindingAttempt(): BindingAttempt {
        val attempt = BindingAttempt()
        lateinit var serviceConnection: ServiceConnection
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val endpoint = ILocalChatWorker.Stub.asInterface(service)
                val deathRecipient = IBinder.DeathRecipient {
                    invalidateBinding(
                        attempt,
                        workerFailure("process exited unexpectedly.")
                    )
                }
                try {
                    service.linkToDeath(deathRecipient, 0)
                } catch (error: Throwable) {
                    invalidateBinding(
                        attempt,
                        workerFailure("died while connecting.", error)
                    )
                    return
                }
                val accepted = synchronized(stateLock) {
                    if (activeBinding === attempt &&
                        connection === serviceConnection &&
                        !closed
                    ) {
                        attempt.binder = service
                        attempt.deathRecipient = deathRecipient
                        remote = endpoint
                        if (!workerSessionLost) failure = null
                        true
                    } else {
                        false
                    }
                }
                if (!accepted) {
                    runCatching { service.unlinkToDeath(deathRecipient, 0) }
                    invalidateBinding(
                        attempt,
                        workerFailure("returned a stale connection callback.")
                    )
                } else {
                    attempt.connected.countDown()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                invalidateBinding(
                    attempt,
                    workerFailure("service disconnected.")
                )
            }

            override fun onBindingDied(name: ComponentName) {
                invalidateBinding(
                    attempt,
                    workerFailure("binding died.")
                )
            }

            override fun onNullBinding(name: ComponentName) {
                invalidateBinding(
                    attempt,
                    workerFailure("returned a null Binder.")
                )
            }
        }
        attempt.connection = serviceConnection
        return attempt
    }

    private fun startBinding(attempt: BindingAttempt) {
        val didBind = try {
            appContext.bindService(
                Intent(appContext, LocalChatWorkerService::class.java),
                attempt.connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
        } catch (error: Throwable) {
            val failure = workerFailure("could not be bound.", error)
            invalidateBinding(attempt, failure)
            throw failure
        }
        if (!didBind) {
            val failure = workerFailure("could not be bound.")
            invalidateBinding(attempt, failure)
            throw failure
        }

        val unbindStale = synchronized(stateLock) {
            attempt.bindSucceeded = true
            if (activeBinding === attempt && connection === attempt.connection && !closed) {
                bound = true
                false
            } else if (!attempt.unbindIssued) {
                attempt.unbindIssued = true
                true
            } else {
                false
            }
        }
        if (unbindStale) unbindQuietly(attempt.connection)
    }

    private fun handleRemoteFailure(service: ILocalChatWorker, error: Throwable) {
        val attempt = synchronized(stateLock) {
            activeBinding?.takeIf { remote === service }
        } ?: return
        invalidateBinding(attempt, error)
    }

    private fun invalidateBinding(attempt: BindingAttempt, error: Throwable) {
        var shouldUnbind = false
        var binder: IBinder? = null
        var deathRecipient: IBinder.DeathRecipient? = null
        synchronized(stateLock) {
            val isCurrent = activeBinding === attempt && connection === attempt.connection
            if (isCurrent) {
                markWorkerSessionLostLocked(error)
                remote = null
                activeBinding = null
                connection = null
                bound = false
            }
            attempt.error.compareAndSet(null, error)
            binder = attempt.binder
            deathRecipient = attempt.deathRecipient
            attempt.binder = null
            attempt.deathRecipient = null
            if (attempt.bindSucceeded && !attempt.unbindIssued) {
                attempt.unbindIssued = true
                shouldUnbind = true
            }
        }
        attempt.connected.countDown()
        if (binder != null && deathRecipient != null) {
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
        }
        if (shouldUnbind) unbindQuietly(attempt.connection)
    }

    private fun releaseBinding(attempt: BindingAttempt) {
        var shouldUnbind = false
        val binder: IBinder?
        val deathRecipient: IBinder.DeathRecipient?
        synchronized(stateLock) {
            binder = attempt.binder
            deathRecipient = attempt.deathRecipient
            attempt.binder = null
            attempt.deathRecipient = null
            if (attempt.bindSucceeded && !attempt.unbindIssued) {
                attempt.unbindIssued = true
                shouldUnbind = true
            }
        }
        if (binder != null && deathRecipient != null) {
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
        }
        if (shouldUnbind) unbindQuietly(attempt.connection)
    }

    private fun markWorkerSessionLostLocked(error: Throwable) {
        if (modelLoadedInWorker) workerSessionLost = true
        modelLoadedInWorker = false
        failure = error
    }

    private fun workerUnavailableStatsLocked(): String {
        val diagnostic = (failure as? RemoteLocalChatRunnerException)?.workerStageDiagnostic
            ?: runCatching { stageJournal.read() }.getOrNull()
        return JSONObject()
            .put("backend", runtime.backendId)
            .put("loaded", false)
            .put("runnerReady", !closed && !workerSessionLost)
            .put(WORKER_SESSION_LOST_FIELD, workerSessionLost)
            .apply {
                failure?.message?.takeIf { it.isNotBlank() }?.let { put("lastError", it) }
                diagnostic?.let {
                    put(WORKER_STAGE_JOURNAL_FIELD, it.toJson())
                    put("workerLastStage", it.stage)
                    put("workerLastPssKb", it.pssKb)
                }
            }
            .toString()
    }

    private inline fun <T> withRequestPayload(
        request: LocalChatWorkerRequestTransport.BeginRequest,
        block: (ParcelFileDescriptor) -> T
    ): T {
        val file = LocalChatWorkerRequestTransport.write(appContext, request)
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                // The open descriptor remains valid after unlinking the cache
                // entry, so a process death cannot leave prompt text on disk.
                runCatching { file.delete() }
                return block(descriptor)
            }
        } finally {
            runCatching { file.delete() }
        }
    }

    private fun disconnectedFailure(stage: String, cause: Throwable): RemoteLocalChatRunnerException =
        workerFailure(stage, cause)

    /** Reads only the bounded, prompt-free worker sidecar after a Binder failure. */
    private fun workerFailure(summary: String, cause: Throwable? = null): RemoteLocalChatRunnerException {
        val diagnostic = runCatching { stageJournal.read() }.getOrNull()
        val safeCause = LocalDiagnosticRedactor.sanitize(cause?.message)
        val message = buildString {
            append("The isolated local text worker ").append(summary.trim())
            if (safeCause.isNotBlank()) append(": ").append(safeCause)
            diagnostic?.let { append(". Last durable worker diagnostic: ").append(it.compactDescription()) }
        }
        return RemoteLocalChatRunnerException(message, cause, diagnostic)
    }

    private fun unbindQuietly(serviceConnection: ServiceConnection) {
        runCatching { appContext.unbindService(serviceConnection) }
    }

    private class BindingAttempt {
        lateinit var connection: ServiceConnection
        val connected = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(null)
        var bindSucceeded = false
        var unbindIssued = false
        var binder: IBinder? = null
        var deathRecipient: IBinder.DeathRecipient? = null
    }

    private companion object {
        private const val CONNECTION_TIMEOUT_MS = 15_000L
        private const val NO_MODEL_LOADED_MESSAGE = "No local text model is loaded"
        internal const val WORKER_SESSION_LOST_FIELD = "workerSessionLost"
        internal const val WORKER_STAGE_JOURNAL_FIELD = "workerStageJournal"
    }
}

internal class RemoteLocalChatRunnerException(
    message: String,
    cause: Throwable? = null,
    internal val workerStageDiagnostic: LocalChatWorkerStageDiagnostic? = null
) :
    IllegalStateException(message, cause)
