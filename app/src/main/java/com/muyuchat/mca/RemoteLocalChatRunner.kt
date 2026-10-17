package com.muyuchat.mca

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import androidx.core.content.ContextCompat
import com.muyuchat.core.engine.LocalChatSessionRecoveryPolicy
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.LocalChatRunner
import com.muyuchat.core.engine.PersistentPrefixCacheRequest
import com.muyuchat.core.engine.TokenProgress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

internal const val WORKER_SESSION_LOST_FIELD = "workerSessionLost"
internal const val WORKER_STAGE_JOURNAL_FIELD = "workerStageJournal"

/** Main-process proxy for [LocalChatWorkerService]. */
internal class RemoteLocalChatRunner(
    context: Context,
    override val runtime: LocalChatRuntime,
    internal val isolatedServiceClass: Class<out LocalChatWorkerService>? = null,
    private val journalScope: String? = null,
    /** Disposable workers may retain Binder death evidence until native process exit is confirmed. */
    internal val retainWorkerDeathObserver: Boolean = false
) : LocalChatRunner, AutoCloseable {
    internal val appContext = context.applicationContext
    internal val stageJournal = LocalChatWorkerStageJournal.forContext(appContext, journalScope)
    internal val stateLock = Any()
    internal var activeBinding: BindingAttempt? = null
    internal var connection: ServiceConnection? = null
    internal var bound = false
    internal var remote: ILocalChatWorker? = null
    /** Retained after detach so a one-shot client can confirm its own process has exited. */
    internal val workerDeathEvidence = ConnectedWorkerDeathEvidence<IBinder>()
    internal var nativeLibDir: String = ""
    internal var modelLoadedInWorker = false
    internal var workerSessionLost = false
    /** Changes whenever a load attempt or Binder identity invalidates an in-flight readback. */
    internal var workerSessionEpoch = 0L
    internal var closed = false

    @Volatile
    internal var failure: Throwable? = null

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

    override fun loadModel(modelPath: String, paramsJson: String): Int {
        return invoke { service ->
            // Bind first so a failed connection cannot leave a notification-only
            // started service behind. A restricted FGS start still falls back to
            // the already-established generic bound-service path.
            startResidentService()
            service.initRuntime(runtime.name, nativeLibDir)
            val loadEpoch = synchronized(stateLock) {
                check(remote === service) { "The isolated local text worker changed before model load." }
                workerSessionEpoch += 1L
                workerSessionEpoch
            }
            val result = service.loadModel(runtime.name, modelPath, paramsJson)
            val disposition = synchronized(stateLock) {
                classifyWorkerLoadResult(
                    nativeResult = result,
                    endpointStillCurrent = remote === service,
                    epochStillCurrent = workerSessionEpoch == loadEpoch
                ).also { outcome ->
                    when (outcome) {
                        WorkerLoadResultDisposition.COMMITTED -> {
                            modelLoadedInWorker = true
                            workerSessionLost = false
                            failure = null
                        }
                        WorkerLoadResultDisposition.FAILED -> {
                            modelLoadedInWorker = false
                            workerSessionLost = false
                        }
                        WorkerLoadResultDisposition.LOST_AFTER_SUCCESS -> {
                            modelLoadedInWorker = false
                            workerSessionLost = true
                        }
                        WorkerLoadResultDisposition.STALE -> Unit
                    }
                }
            }
            when (disposition) {
                WorkerLoadResultDisposition.COMMITTED,
                WorkerLoadResultDisposition.FAILED -> Unit
                WorkerLoadResultDisposition.LOST_AFTER_SUCCESS -> throw workerFailure(
                    "finished loading a model after its Binder connection was already lost."
                ).also { error ->
                    synchronized(stateLock) {
                        if (workerSessionEpoch != loadEpoch || remote !== service) failure = error
                    }
                }
                WorkerLoadResultDisposition.STALE -> throw workerFailure(
                    "returned a stale model-load result after its session epoch changed."
                )
            }
            result
        }
    }

    override fun unloadModel() {
        invoke { service -> service.unloadModel(); Unit }
        synchronized(stateLock) {
            workerSessionEpoch += 1L
            modelLoadedInWorker = false
            workerSessionLost = false
        }
    }

    /**
     * B44: replacement loads run in a fresh worker process. The worker unloads the resident
     * model and then ends its process; this proxy waits for the exact Binder death and
     * clears the lost-session bookkeeping so the following load re-binds a new process.
     */
    override fun recycleWorkerForReplacement(): Boolean = recycleWorkerForReplacementImpl()

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
                    fixedSystemPrompt = prefixCache.fixedSystemPrompt,
                    fullSessionState = prefixCache.fullSessionState
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

    override fun isSessionKnownLost(): Boolean = synchronized(stateLock) {
        workerSessionLost || (modelLoadedInWorker && remote == null)
    }

    override fun canReleasePreparedInputs(): Boolean {
        val snapshot = synchronized(stateLock) { remote?.let { it to workerSessionEpoch } }
            ?: return synchronized(stateLock) { workerDeathEvidence.confirmedDeath }
        val (service, epoch) = snapshot
        return try {
            val releasable = service.canReleasePreparedInputs()
            synchronized(stateLock) { releasable && remote === service && workerSessionEpoch == epoch }
        } catch (error: DeadObjectException) {
            val died = synchronized(stateLock) {
                (remote === service && workerSessionEpoch == epoch).also {
                    if (it) workerDeathEvidence.confirmDeath(service.asBinder())
                }
            }
            handleRemoteFailure(service, disconnectedFailure("crashed or was reclaimed", error))
            died
        } catch (error: Throwable) {
            handleRemoteFailure(service, disconnectedFailure("disconnected", error))
            false
        }
    }

    override fun sessionRecoveryPolicy(): LocalChatSessionRecoveryPolicy = synchronized(stateLock) {
        if (requiresExplicitAcceleratorReload(workerFailureCodeLocked())) {
            LocalChatSessionRecoveryPolicy.EXPLICIT_RELOAD_REQUIRED
        } else {
            LocalChatSessionRecoveryPolicy.AUTOMATIC
        }
    }

    override fun sessionRecoveryMessage(): String? = synchronized(stateLock) {
        explicitAcceleratorReloadMessage(workerFailureCodeLocked())
    }

    override fun getRuntimeStatsJson(): String {
        repeat(MAX_STATS_READ_ATTEMPTS) {
            val snapshot = synchronized(stateLock) {
                if (workerSessionLost) return workerUnavailableStatsLocked()
                remote?.let { RemoteStatsReadSnapshot(it, workerSessionEpoch) }
            } ?: return synchronized(stateLock) {
                if (modelLoadedInWorker) {
                    markWorkerSessionLostLocked(
                        workerFailure("lost its loaded model connection.")
                    )
                }
                workerUnavailableStatsLocked()
            }

            try {
                val stats = snapshot.service.getRuntimeStatsJson()
                val reportsLoaded = runCatching {
                    JSONObject(stats).optBoolean("loaded", false)
                }.getOrDefault(false)
                val expectedLoaded = synchronized(stateLock) {
                    if (remote !== snapshot.service || workerSessionEpoch != snapshot.epoch) {
                        null
                    } else {
                        modelLoadedInWorker
                    }
                }
                if (expectedLoaded == null) return@repeat
                Log.i(
                    REMOTE_LOG_TAG,
                    "worker stats received; expectedLoaded=$expectedLoaded " +
                        "reportsLoaded=$reportsLoaded"
                )
                if (workerStatsConfirmLoadedModelLoss(expectedLoaded, stats)) {
                    val error = workerFailure("no longer has the loaded model.")
                    handleRemoteFailure(snapshot.service, error)
                    return synchronized(stateLock) { workerUnavailableStatsLocked() }
                }
                return runCatching {
                    JSONObject(stats)
                        .put(WORKER_SESSION_LOST_FIELD, false)
                        .toString()
                }.getOrDefault(stats)
            } catch (error: DeadObjectException) {
                handleRemoteFailure(
                    snapshot.service,
                    disconnectedFailure("crashed or was reclaimed", error)
                )
                return synchronized(stateLock) { workerUnavailableStatsLocked() }
            } catch (error: RemoteException) {
                handleRemoteFailure(snapshot.service, disconnectedFailure("disconnected", error))
                return synchronized(stateLock) { workerUnavailableStatsLocked() }
            }
        }
        throw workerFailure("changed session epoch repeatedly while runtime stats were read.")
    }

    override fun shutdown() {
        val endpoint = synchronized(stateLock) { remote }
        if (endpoint != null) {
            try {
                endpoint.shutdown()
            } catch (error: DeadObjectException) {
                handleRemoteFailure(endpoint, disconnectedFailure("crashed during shutdown", error))
            } catch (error: RemoteException) {
                handleRemoteFailure(endpoint, disconnectedFailure("disconnected during shutdown", error))
            }
        }
        detach()
    }

    /** Detaches this client without unloading the process-wide resident model. */
    override fun close() {
        // The detach path releases this owner's binding through releaseBinding; it deliberately
        // never calls the shared worker's shutdown endpoint.
        detach()
    }

    /** Detaches while retaining the exact Binder death observer for disposable workers. */
    internal fun closePreservingWorkerDeathObservation() = detach(preserveWorkerDeathObservation = true)

    /**
     * Read-only evidence for a disposable worker owner. A never-connected runner could not
     * issue a native request; otherwise only death of its exact Binder confirms release.
     * Detach alone is deliberately insufficient. This never binds or reads native stats.
     */
    internal fun isConnectedWorkerReleased(): Boolean = synchronized(stateLock) {
        workerDeathEvidence.isReleased { it.isBinderAlive }
    }

    private fun detach(preserveWorkerDeathObservation: Boolean = false) {
        // Deliberately no Binder shutdown here: ViewModel.onCleared() calls
        // close() on the main thread, and another owner may already be bound.
        val attempt = synchronized(stateLock) {
            if (closed) return
            closed = true
            val currentAttempt = activeBinding
            activeBinding = null
            connection = null
            bound = false
            remote = null
            workerSessionEpoch += 1L
            modelLoadedInWorker = false
            workerSessionLost = false
            currentAttempt?.error?.compareAndSet(
                null,
                RemoteLocalChatRunnerException("The isolated local chat runner is closed.")
            )
            currentAttempt
        }
        attempt?.connected?.countDown()
        attempt?.let { releaseBinding(it, preserveWorkerDeathObservation) }
    }

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

    private fun startResidentService() {
        val serviceIntent = Intent(appContext, workerServiceClass())
        runCatching {
            ContextCompat.startForegroundService(appContext, serviceIntent)
        }.onFailure { error ->
            // Background-start restrictions are advisory here. Binding keeps
            // the generic compatible execution path available.
            Log.w(REMOTE_LOG_TAG, "Could not start the resident text worker; binding only.", error)
        }
    }

    internal class BindingAttempt {
        lateinit var connection: ServiceConnection
        val connected = CountDownLatch(1)
        val error = AtomicReference<Throwable?>(null)
        var bindSucceeded = false
        var unbindIssued = false
        var binder: IBinder? = null
        var deathRecipient: IBinder.DeathRecipient? = null
    }

    private data class RemoteStatsReadSnapshot(
        val service: ILocalChatWorker,
        val epoch: Long
    )

    private companion object {
        private const val REMOTE_LOG_TAG = "McaRemoteLocalChat"
        private const val MAX_STATS_READ_ATTEMPTS = 2
        private const val NO_MODEL_LOADED_MESSAGE = "No local text model is loaded"
    }
}
