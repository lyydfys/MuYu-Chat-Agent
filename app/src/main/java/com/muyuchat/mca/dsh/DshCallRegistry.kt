package com.muyuchat.mca.dsh

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException

/**
 * Deterministic binding between one gateway call and one MCA engine request.
 *
 * The frozen gateway identity is `dsh:<runtimeEpoch>:<requestOrdinal>` (see
 * `core/dsh-contract/DshModelGatewayContract.kt`). A well-formed id maps 1:1 onto a stable MCA
 * request id of the same shape, so the same logical call always produces the same request id and
 * two different calls can never collide. A malformed id still gets a deterministic, collision
 * resistant fallback instead of a random id (a random id would make the mapping untestable and
 * could hide a gateway bug).
 */
internal data class DshCallIdentity(
    val epoch: Long,
    val ordinal: Long,
    val callId: String,
    val mcaRequestId: String
) {
    val structured: Boolean
        get() = epoch >= 0L && ordinal > 0L && mcaRequestId == "dsh:$epoch:$ordinal"

    companion object {
        private val PATTERN = Regex("^dsh:(\\d{1,18}):(\\d{1,18})$")

        fun of(callId: String): DshCallIdentity {
            val match = PATTERN.matchEntire(callId)
            if (match != null) {
                val epoch = match.groupValues[1].toLongOrNull()
                val ordinal = match.groupValues[2].toLongOrNull()
                if (epoch != null && ordinal != null && ordinal > 0L) {
                    return DshCallIdentity(
                        epoch = epoch,
                        ordinal = ordinal,
                        callId = callId,
                        mcaRequestId = "dsh:$epoch:$ordinal"
                    )
                }
            }
            return DshCallIdentity(
                epoch = 0L,
                ordinal = 0L,
                callId = callId,
                mcaRequestId = "dsh:call:" + sha256Hex(callId).take(16)
            )
        }
    }
}

/**
 * Exact-call cancellation binding for one [DshCallIdentity].
 *
 * The gateway invokes `cancel(callId)` out-of-band while the collecting flow is structurally
 * cancelled. The hook is installed by the local runner before dispatch and invoked at most once; a
 * cancel that arrives before the hook is installed is remembered and applied immediately when the
 * hook attaches. The hook is synchronously awaited by the gateway cancel path so the exact engine
 * stop (`runner.requestStop()`) has been dispatched before the cancel is acknowledged, and the
 * gateway bounds that wait. For cloud calls the structural cancellation is sufficient, so no hook
 * is installed.
 */
internal class DshCallSession(
    val identity: DshCallIdentity,
    val callId: String = identity.callId,
    val epoch: Long = identity.epoch,
    val requestId: String = identity.mcaRequestId
) {
    private val cancelled = AtomicBoolean(false)
    private val hookInvoked = AtomicBoolean(false)

    @Volatile
    private var cancelHook: (suspend () -> Unit)? = null

    suspend fun attachCancelHook(hook: suspend () -> Unit) {
        cancelHook = hook
        if (cancelled.get()) invokeHook()
    }

    /** Returns true only for the transition that actually requested cancellation. */
    suspend fun requestCancel(): Boolean {
        if (!cancelled.compareAndSet(false, true)) return false
        invokeHook()
        return true
    }

    fun isCancelled(): Boolean = cancelled.get()

    private suspend fun invokeHook() {
        // Never mark the hook as invoked when no hook exists yet: a cancel-before-attach must
        // still dispatch the exact engine stop when the runner attaches its hook later.
        val hook = cancelHook ?: return
        if (!hookInvoked.compareAndSet(false, true)) return
        try {
            hook()
        } catch (cancelled: CancellationException) {
            // Structured cancellation must keep propagating; only adapter failures are contained.
            throw cancelled
        } catch (_: Throwable) {
            // A failing adapter stop must not turn the cancel transition into an error; the
            // collecting flow is still cancelled structurally by the gateway.
        }
    }
}

/**
 * Bounded registry of live DSH call identities plus a bounded memory of already-used ids.
 *
 * A call id is accepted at most once for the lifetime of this adapter (bounded FIFO memory), so a
 * late `cancel(callId)` or a duplicated gateway request can never be applied to a newer, unrelated
 * call. [cancel] targets exactly the live binding and returns false for unknown or already
 * terminal calls.
 */
internal class DshCallRegistry(
    private val rememberedCallIdCapacity: Int = 8_192
) {
    init {
        require(rememberedCallIdCapacity in 1..1_000_000) {
            "rememberedCallIdCapacity must be in 1..1000000."
        }
    }

    private val lock = Any()
    private val live = LinkedHashMap<String, DshCallSession>()
    private val remembered = LinkedHashSet<String>()

    /** Returns false for a duplicated live id or an id this adapter already served. */
    fun begin(session: DshCallSession): Boolean = synchronized(lock) {
        if (live.containsKey(session.callId)) return false
        if (session.callId in remembered) return false
        live[session.callId] = session
        remembered.add(session.callId)
        while (remembered.size > rememberedCallIdCapacity) {
            val oldest = remembered.first()
            remembered.remove(oldest)
        }
        true
    }

    fun end(session: DshCallSession) {
        synchronized(lock) {
            if (live[session.callId] === session) live.remove(session.callId)
        }
    }

    suspend fun cancel(callId: String): Boolean {
        val session = synchronized(lock) { live[callId] } ?: return false
        return session.requestCancel()
    }

    fun isLive(callId: String): Boolean = synchronized(lock) { live.containsKey(callId) }

    fun liveCount(): Int = synchronized(lock) { live.size }

    /** Test/diagnostic view of the bounded id memory. */
    fun hasRemembered(callId: String): Boolean = synchronized(lock) { callId in remembered }
}

/** Stable, dependency-free digest for request ids and catalog revisions (never used on secrets). */
internal fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
    }
}

private const val HEX = "0123456789abcdef"
