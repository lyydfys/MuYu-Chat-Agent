package com.muyuchat.mca

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
