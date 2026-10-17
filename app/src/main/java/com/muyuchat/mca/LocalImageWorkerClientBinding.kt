package com.muyuchat.mca

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import com.muyuchat.mca.LocalImageWorkerClient.BindingSession
import com.muyuchat.mca.LocalImageWorkerClient.BoundWorker
import com.muyuchat.mca.LocalImageWorkerClient.Preparation
import com.muyuchat.mca.LocalImageWorkerClient.ActiveRequest
import com.muyuchat.mca.LocalImageWorkerClient.WorkerServiceConnection

internal suspend fun LocalImageWorkerClient.awaitService(
        onSessionSelected: (LocalImageWorkerClient.BindingSession) -> Unit = {}
    ): LocalImageWorkerClient.BoundWorker {
        awaitRebindCooldown()
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

internal fun LocalImageWorkerClient.handleServiceConnected(
        connection: LocalImageWorkerClient.WorkerServiceConnection,
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

    internal fun LocalImageWorkerClient.failBinding(session: LocalImageWorkerClient.BindingSession, error: Throwable) {
        val shouldFail = synchronized(stateLock) {
            if (bindingSession !== session || !bindingLifecycle.isCurrent(session.lease)) {
                false
            } else {
                bindingLifecycle.release(session.lease)
                bindingSession = null
                lastBindingReleaseAtElapsedMs = SystemClock.elapsedRealtime()
                unlinkRemoteDeathRecipientLocked()
                remote = null
                remoteBinder = null
                true
            }
        }
        if (shouldFail) session.deferred.completeExceptionally(remoteFailure(error))
    }

internal fun LocalImageWorkerClient.handleConnectionLoss(
        connection: LocalImageWorkerClient.WorkerServiceConnection,
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
            armReleasedWorkerExitWaiterLocked(remoteBinder)
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
            lastBindingReleaseAtElapsedMs = SystemClock.elapsedRealtime()
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
internal fun LocalImageWorkerClient.releaseBindingAfterPreparationFailure(
        candidate: LocalImageWorkerClient.Preparation,
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
            armReleasedWorkerExitWaiterLocked(remoteBinder)
            bindingSession = null
            lastBindingReleaseAtElapsedMs = SystemClock.elapsedRealtime()
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
    internal fun LocalImageWorkerClient.releaseBindingAfterRequest(request: LocalImageWorkerClient.ActiveRequest, runtime: LocalImageRuntime) {
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
                armReleasedWorkerExitWaiterLocked(remoteBinder)
                bindingSession = null
                lastBindingReleaseAtElapsedMs = SystemClock.elapsedRealtime()
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

    /** Must be called while [stateLock] is held, before the live binder is cleared. */
    internal fun LocalImageWorkerClient.armReleasedWorkerExitWaiterLocked(binder: IBinder?) {
        if (binder == null || !binder.isBinderAlive || binder in releasedWorkerExits) return
        val waiter = CompletableDeferred<Unit>()
        val recipient = IBinder.DeathRecipient {
            waiter.complete(Unit)
            synchronized(stateLock) { releasedWorkerExits.remove(binder) }
        }
        releasedWorkerExits[binder] = waiter
        try {
            binder.linkToDeath(recipient, 0)
        } catch (_: RemoteException) {
            waiter.complete(Unit)
            releasedWorkerExits.remove(binder)
        }
    }
