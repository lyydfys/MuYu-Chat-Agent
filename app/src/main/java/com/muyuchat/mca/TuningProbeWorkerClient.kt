package com.muyuchat.mca

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** Main-process client for the disposable load-bound tuning process. */
internal class TuningProbeWorkerClient(context: Context) {
    private val appContext = context.applicationContext

    suspend fun probe(
        transactionId: String,
        identityKey: String,
        modelId: String,
        profileId: String,
        resolvedLoadSignature: String,
        committedExecutionSignature: String,
        onProgress: (TuningProbeWorkerProtocol.Progress) -> Unit = {}
    ): TuningProbeWorkerProtocol.Result {
        val request = TuningProbeWorkerProtocol.Request(
            requestId = UUID.randomUUID().toString(),
            transactionId = transactionId,
            identityKey = identityKey,
            modelId = modelId,
            profileId = profileId,
            resolvedLoadSignature = resolvedLoadSignature,
            committedExecutionSignature = committedExecutionSignature
        )
        val connected = CompletableDeferred<ITuningProbeWorker>()
        val completed = CompletableDeferred<TuningProbeWorkerProtocol.Result>()
        var bound = false
        var remote: ITuningProbeWorker? = null
        var binder: IBinder? = null
        var deathRecipient: IBinder.DeathRecipient? = null

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val worker = ITuningProbeWorker.Stub.asInterface(service)
                val recipient = IBinder.DeathRecipient {
                    completed.completeExceptionally(
                        TuningProbeWorkerException(
                            "The disposable tuning process exited before returning complete evidence."
                        )
                    )
                }
                try {
                    service.linkToDeath(recipient, 0)
                } catch (error: RemoteException) {
                    completed.completeExceptionally(
                        TuningProbeWorkerException("The tuning process exited while connecting.", error)
                    )
                    return
                }
                remote = worker
                binder = service
                deathRecipient = recipient
                connected.complete(worker)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                completed.completeExceptionally(TuningProbeWorkerException("The tuning service disconnected."))
            }

            override fun onBindingDied(name: ComponentName) {
                completed.completeExceptionally(TuningProbeWorkerException("The tuning service binding died."))
            }

            override fun onNullBinding(name: ComponentName) {
                completed.completeExceptionally(TuningProbeWorkerException("The tuning service returned no binder."))
            }
        }

        val callback = object : ITuningProbeWorkerCallback.Stub() {
            override fun onProgress(payloadJson: String) {
                runCatching { TuningProbeWorkerProtocol.parseProgress(payloadJson) }
                    .onSuccess { progress ->
                        if (progress.requestId == request.requestId && !completed.isCompleted) {
                            runCatching { onProgress(progress) }
                        }
                    }
                    .onFailure { error ->
                        completed.completeExceptionally(
                            TuningProbeWorkerException("The tuning worker returned invalid progress.", error)
                        )
                    }
            }

            override fun onComplete(payloadJson: String) {
                val result = runCatching { TuningProbeWorkerProtocol.parseComplete(payloadJson) }
                    .getOrElse { error ->
                        completed.completeExceptionally(
                            TuningProbeWorkerException("The tuning worker returned invalid evidence.", error)
                        )
                        return
                    }
                val exactIdentity = result.requestId == request.requestId &&
                    result.transactionId == request.transactionId &&
                    result.identityKey == request.identityKey &&
                    result.modelId == request.modelId &&
                    result.profileId == request.profileId &&
                    result.resolvedLoadSignature == request.resolvedLoadSignature &&
                    result.committedExecutionSignature == request.committedExecutionSignature
                if (exactIdentity) {
                    completed.complete(result)
                } else {
                    completed.completeExceptionally(
                        TuningProbeWorkerException(
                            "The tuning worker returned evidence for a different model/profile transaction."
                        )
                    )
                }
            }

            override fun onError(payloadJson: String) {
                val error = runCatching { TuningProbeWorkerProtocol.parseError(payloadJson) }
                    .getOrElse { parseError ->
                        completed.completeExceptionally(
                            TuningProbeWorkerException("The tuning worker returned an invalid error.", parseError)
                        )
                        return
                    }
                if (error.requestId.isBlank() || error.requestId == request.requestId) {
                    completed.completeExceptionally(
                        TuningProbeWorkerRemoteException(error.code, error.message)
                    )
                }
            }
        }

        try {
            bound = appContext.bindService(
                Intent(appContext, TuningProbeWorkerService::class.java),
                connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
            check(bound) { "Unable to bind the disposable tuning service." }
            val worker = withTimeout(CONNECTION_TIMEOUT_MS) { connected.await() }
            val accepted = try {
                worker.start(TuningProbeWorkerProtocol.start(request), callback)
            } catch (error: RemoteException) {
                throw TuningProbeWorkerException("The tuning Binder call failed.", error)
            }
            check(accepted) { "The disposable tuning service is busy." }
            return withTimeout(TuningProbeWorkerProtocol.CLIENT_RUN_TIMEOUT_MS) { completed.await() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: DeadObjectException) {
            throw TuningProbeWorkerException(
                "The disposable tuning process crashed or was killed by its watchdog.",
                error
            )
        } finally {
            runCatching { remote?.cancel(TuningProbeWorkerProtocol.cancel(request.requestId)) }
            deathRecipient?.let { recipient -> runCatching { binder?.unlinkToDeath(recipient, 0) } }
            if (bound) runCatching { appContext.unbindService(connection) }
        }
    }

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 15_000L
    }
}

internal open class TuningProbeWorkerException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal class TuningProbeWorkerRemoteException(val code: String, message: String) :
    TuningProbeWorkerException(message)
