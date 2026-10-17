package com.muyuchat.mca

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import com.muyuchat.core.engine.LocalChatRuntime
import java.util.concurrent.TimeUnit

/** Binder binding, connection and release for [RemoteLocalChatRunner] (T2B22-T3 split). */

private const val CONNECTION_TIMEOUT_MS = 15_000L

    /** Unloads through the Binder this owner already holds; cleanup must never start a replacement. */
internal fun RemoteLocalChatRunner.unloadModelIfConnected() {
        val service = remoteIfBound() ?: return
        try {
            service.unloadModel()
        } catch (error: DeadObjectException) {
            synchronized(stateLock) {
                if (remote === service && workerDeathEvidence.lastEndpoint === service.asBinder()) {
                    workerDeathEvidence.confirmDeath(service.asBinder())
                }
            }
            handleRemoteFailure(service, disconnectedFailure("crashed or was reclaimed", error))
            return
        } catch (error: RemoteException) {
            handleRemoteFailure(service, disconnectedFailure("disconnected", error))
            return
        }
        synchronized(stateLock) {
            if (remote === service) {
                workerSessionEpoch += 1L
                modelLoadedInWorker = false
                workerSessionLost = false
            }
        }
    }

internal fun RemoteLocalChatRunner.remoteIfBound(): ILocalChatWorker? = synchronized(stateLock) { remote }

internal fun RemoteLocalChatRunner.ensureConnected(): ILocalChatWorker {
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

internal fun RemoteLocalChatRunner.createBindingAttempt(): RemoteLocalChatRunner.BindingAttempt {
        val attempt = RemoteLocalChatRunner.BindingAttempt()
        lateinit var serviceConnection: ServiceConnection
        serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val endpoint = ILocalChatWorker.Stub.asInterface(service)
                val deathRecipient = IBinder.DeathRecipient {
                    val invalidateCurrentAttempt = synchronized(stateLock) {
                        workerDeathEvidence.confirmDeath(service)
                        // The retained observer can outlive unbind. Ignore a retired Binder's
                        // callback after this connection has accepted a replacement endpoint.
                        activeBinding === attempt && (attempt.binder == null || attempt.binder === service)
                    }
                    if (invalidateCurrentAttempt) {
                        invalidateBinding(
                            attempt,
                            workerFailure("process exited unexpectedly."),
                            expectedBinder = service
                        )
                    }
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
                        workerDeathEvidence.connected(service)
                        remote = endpoint
                        workerSessionEpoch += 1L
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

internal fun RemoteLocalChatRunner.startBinding(attempt: RemoteLocalChatRunner.BindingAttempt) {
        val serviceIntent = Intent(appContext, workerServiceClass())
        val didBind = try {
            appContext.bindService(
                serviceIntent,
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

    /**
     * Keep LiteRT-LM in its dedicated worker process/service so its Qualcomm dispatch
     * preloading and native lifecycle cannot collide with the generic local-chat worker.
     * All other runtimes continue to use the shared worker implementation.
     */
internal fun RemoteLocalChatRunner.workerServiceClass(): Class<out LocalChatWorkerService> =
        isolatedServiceClass ?: if (runtime == LocalChatRuntime.LITERT_LM) {
            LiteRtChatWorkerService::class.java
        } else {
            LocalChatWorkerService::class.java
        }

internal fun RemoteLocalChatRunner.handleRemoteFailure(service: ILocalChatWorker, error: Throwable) {
        val attempt = synchronized(stateLock) {
            if (remote === service && workerDeathEvidence.lastEndpoint === service.asBinder() &&
                error.causeChainContainsDeadObject()
            ) {
                workerDeathEvidence.confirmDeath(service.asBinder())
            }
            activeBinding?.takeIf { remote === service }
        } ?: return
        invalidateBinding(attempt, error, expectedBinder = service.asBinder())
    }

internal fun Throwable.causeChainContainsDeadObject(): Boolean =
        generateSequence(this) { it.cause }.any { it is DeadObjectException }

internal fun RemoteLocalChatRunner.invalidateBinding(
        attempt: RemoteLocalChatRunner.BindingAttempt,
        error: Throwable,
        expectedBinder: IBinder? = null
    ) {
        var shouldUnbind = false
        var binder: IBinder? = null
        var deathRecipient: IBinder.DeathRecipient? = null
        synchronized(stateLock) {
            if (expectedBinder != null && attempt.binder != null && attempt.binder !== expectedBinder) return
            val isCurrent = activeBinding === attempt && connection === attempt.connection
            if (isCurrent) {
                markWorkerSessionLostLocked(error)
                remote = null
                workerSessionEpoch += 1L
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
        val keepDeathObserver = synchronized(stateLock) {
            retainWorkerDeathObserver && binder != null && binder === workerDeathEvidence.lastEndpoint &&
                !workerDeathEvidence.confirmedDeath
        }
        if (!keepDeathObserver && binder != null && deathRecipient != null) {
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
        }
        if (shouldUnbind) unbindQuietly(attempt.connection)
    }

internal fun RemoteLocalChatRunner.releaseBinding(
        attempt: RemoteLocalChatRunner.BindingAttempt,
        preserveDeathObservation: Boolean = false
    ) {
        var shouldUnbind = false
        val binder: IBinder?
        val deathRecipient: IBinder.DeathRecipient?
        synchronized(stateLock) {
            binder = attempt.binder
            deathRecipient = attempt.deathRecipient
            if (!preserveDeathObservation) {
                attempt.binder = null
                attempt.deathRecipient = null
            }
            if (attempt.bindSucceeded && !attempt.unbindIssued) {
                attempt.unbindIssued = true
                shouldUnbind = true
            }
        }
        if (!preserveDeathObservation && binder != null && deathRecipient != null) {
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
        }
        if (shouldUnbind) unbindQuietly(attempt.connection)
    }

internal fun RemoteLocalChatRunner.unbindQuietly(serviceConnection: ServiceConnection) {
        runCatching { appContext.unbindService(serviceConnection) }
    }

private const val REPLACEMENT_RECYCLE_TIMEOUT_MS = 6_000L

/**
 * B44: replacement loads must start in a fresh worker process. The worker unloads the
 * resident model and kills its own process; this waits for the exact Binder death, clears
 * the lost-session bookkeeping and leaves the runner usable for the following load (which
 * re-binds a new process). Unlike [RemoteLocalChatRunner.close] this never marks the runner
 * closed.
 */
internal fun RemoteLocalChatRunner.recycleWorkerForReplacementImpl(
    timeoutMs: Long = REPLACEMENT_RECYCLE_TIMEOUT_MS
): Boolean {
    val endpoint = synchronized(stateLock) { remote }
    val attempt = synchronized(stateLock) { activeBinding }
    val binder = attempt?.binder
    if (endpoint == null) {
        resetReplacementRecycleStateLocked()
        return true
    }
    runCatching { endpoint.recycleForReplacement() }
    val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
    while (android.os.SystemClock.uptimeMillis() < deadline) {
        if (binder == null || !binder.isBinderAlive) break
        Thread.sleep(50L)
    }
    val died = binder == null || !binder.isBinderAlive
    val stale = synchronized(stateLock) { activeBinding }
    if (died && stale != null) {
        invalidateBinding(stale, workerFailure("recycled for a replacement load."), expectedBinder = binder)
    }
    resetReplacementRecycleStateLocked()
    return died
}

internal fun RemoteLocalChatRunner.resetReplacementRecycleStateLocked() {
    synchronized(stateLock) {
        workerSessionEpoch += 1L
        modelLoadedInWorker = false
        workerSessionLost = false
        failure = null
    }
}
