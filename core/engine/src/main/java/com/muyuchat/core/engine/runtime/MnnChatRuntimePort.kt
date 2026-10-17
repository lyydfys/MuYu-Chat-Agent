package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.RuntimeEpoch
import com.muyuchat.core.engine.contract.SessionGeneration
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The exact native operations the MNN chat lifecycle needs.
 *
 * This is the port's only native leaf: the production implementation forwards to
 * `NativeMnnBridge`, and host tests inject a deterministic fake. Argument lists and return
 * contracts are identical to the bridge calls the production runner made before this port existed,
 * so routing a call through the port never changes the native exchange.
 *
 * Universal Hardware Admission Notice: the leaf is chosen by backend availability (library and
 * linkage probes), never by chipset/model/allowlist admission.
 */
interface MnnChatNativeLeaf {
    fun loadModel(configPath: String, paramsJson: String): Int
    fun unloadModel()
    fun beginCompletion(messagesJson: String, paramsJson: String): Int
    fun generateNextChunk(): String?
    fun requestStop()
    fun requestStopIfActive(): Boolean
    fun isGenerationRunning(): Boolean
    fun getRuntimeStatsJson(): String
    fun shutdown()
}

/**
 * Reads the MNN chat runtime stats JSON (the same payload `getRuntimeStatsJson` has always
 * returned). Returns null when the payload cannot be parsed.
 */
internal fun readMnnChatStatsJson(leaf: MnnChatNativeLeaf): JSONObject? =
    runCatching { JSONObject(leaf.getRuntimeStatsJson()) }.getOrNull()

/**
 * Drain probe used by [RequestExecutionHandle.isNativeRunning].
 *
 * Fail-closed: when the stats payload cannot be read or parsed the answer is `true`
 * ("still running"), because a missing acknowledgement must never be reported as a stopped owner.
 */
internal fun readMnnChatGenerationActive(leaf: MnnChatNativeLeaf): Boolean {
    val stats = readMnnChatStatsJson(leaf) ?: return true
    return stats.optBoolean("generationActive", true)
}

/**
 * Real MNN chat engine behind the Runtime Port.
 *
 * ## What is real here
 *
 * - [load] performs the production `loadModel` call and reports the raw return code; [isReady] is
 *   only true after a load that returned 0.
 * - [unload] performs the production `unloadModel` call and then *verifies* the release against
 *   the native stats payload (`loaded == false && generationActive == false`). A native unload
 *   that returned early (the MNN bridge refuses to join a stalled visual producer) is therefore
 *   reported as [isNativeReleaseConfirmed] == false instead of being presented as a successful
 *   release. In-flight sessions/leases are kept (not force-released) until the release is
 *   confirmed by [retryRelease] or the native owner is confirmed gone.
 * - [createSession] keeps the duplicate-id conflict contract (never silently replaces a live
 *   session).
 *
 * ## What is deliberately not here
 *
 * No retry loop, no model probing, no device admission, and no second execution path: the native
 * leaves stay exactly the production ones, and the port only adds identity/lease/drain bookkeeping.
 */
class MnnChatEnginePort(
    override val engineId: String,
    override val modelPath: String,
    private val leaf: MnnChatNativeLeaf,
    /** Runtime epoch this engine belongs to (part of request identity, not of EnginePort). */
    val runtimeEpoch: RuntimeEpoch = RuntimeEpoch(1L)
) : EnginePort {

    private val lifecycleLock = Any()
    private val sessions = ConcurrentHashMap<String, MnnChatSessionPort>()
    private val attemptSequence = AtomicLong(0L)

    @Volatile private var ready = false
    @Volatile private var closed = false
    @Volatile private var unloadIssued = false
    @Volatile private var lastLoadRc: Int = NOT_LOADED_RC
    @Volatile private var releaseConfirmed = true
    @Volatile private var releaseMessage: String? = null

    override val isReady: Boolean get() = ready && !closed

    /** Raw return code of the most recent [load]; [NOT_LOADED_RC] before the first load. */
    val lastLoadReturnCode: Int get() = lastLoadRc

    /** True when the last [unload] (or [retryRelease]) verified that the native owner let go. */
    val isNativeReleaseConfirmed: Boolean get() = releaseConfirmed

    /** Prompt-free reason paired with an unconfirmed release; null when confirmed. */
    val nativeReleaseMessage: String? get() = releaseMessage

    /**
     * Monotonic attempt identity source of this engine (attempt ids are never reused).
     * [RequestIdentity.attemptId] carries the same sequence as the request id suffix so settlement,
     * cancellation and callback routing can compare the full identity of an attempt.
     */
    fun newRequestIdentity(tag: String): RequestIdentity {
        val attempt = attemptSequence.incrementAndGet()
        return RequestIdentity(
            requestId = "$engineId:$tag:$attempt",
            attemptId = attempt,
            runtimeEpoch = runtimeEpoch.value
        )
    }

    /**
     * Performs the production load exactly once and returns its raw return code. A failed load
     * never leaves a ready engine behind: `isReady` stays false until a load returns 0.
     */
    fun load(paramsJson: String): Int {
        val rc = leaf.loadModel(modelPath, paramsJson)
        lastLoadRc = rc
        ready = rc == 0
        if (rc == 0) {
            unloadIssued = false
            releaseConfirmed = true
            releaseMessage = null
        }
        return rc
    }

    /**
     * Opens (or re-opens) the session the production runner uses for this engine.
     *
     * Re-opening is what a reload does: the replaced session's *bookkeeping* is retired, and no
     * native call is made here - the native load/unload owns the physical reset semantics.
     */
    fun openSession(sessionId: String): MnnChatSessionPort {
        check(ready) { "Engine $engineId is not loaded; refusing to open the runner session." }
        val session = MnnChatSessionPort(sessionId, this, leaf)
        val replaced = sessions.put(sessionId, session)
        replaced?.close()
        return session
    }

    override fun createSession(sessionId: String): SessionPort {
        check(!closed) { "Engine $engineId is unloaded." }
        check(ready) { "Engine $engineId is not loaded; refusing to create a session." }
        val session = MnnChatSessionPort(sessionId, this, leaf)
        if (sessions.putIfAbsent(sessionId, session) != null) {
            session.close()
            throw IllegalStateException(
                "Engine $engineId already has a live session '$sessionId'; refusing to replace it."
            )
        }
        return session
    }

    /**
     * Performs the production unload and records the *verified* release state.
     * Calling unload twice never calls the native unload twice for the same load cycle.
     */
    override fun unload() {
        val shouldCallNative: Boolean
        synchronized(lifecycleLock) {
            shouldCallNative = !unloadIssued
            unloadIssued = true
            ready = false
        }
        if (!shouldCallNative) return
        leaf.unloadModel()
        applyReleaseAck()
    }

    /**
     * Re-checks a previously unconfirmed release (e.g. after a stalled visual producer finished).
     * Returns true once the native owner is confirmed released; only then are live sessions and
     * their leases retired.
     */
    fun retryRelease(): Boolean {
        if (releaseConfirmed) return true
        applyReleaseAck()
        return releaseConfirmed
    }

    /**
     * Bookkeeping for a load that is superseded by a newer load of the same runner: the old object
     * must never call the native unload afterwards, because that would tear down the new model that
     * the native side just initialized.
     */
    internal fun supersede() {
        synchronized(lifecycleLock) {
            ready = false
            unloadIssued = true
            sessions.values.forEach { it.close() }
            sessions.clear()
        }
    }

    /**
     * Bookkeeping for a native `shutdown` performed by the caller (the MNN bridge frees every
     * owner there): sessions are retired because the native resources are gone.
     */
    internal fun markNativeShutdown() {
        synchronized(lifecycleLock) {
            ready = false
            unloadIssued = true
            closed = true
            releaseConfirmed = true
            releaseMessage = null
            sessions.values.forEach { it.close() }
            sessions.clear()
        }
    }

    override fun close() {
        unload()
        closed = true
    }

    private fun applyReleaseAck() {
        val stats = readMnnChatStatsJson(leaf)
        val loaded = stats?.optBoolean("loaded", true) ?: true
        val active = stats?.optBoolean("generationActive", true) ?: true
        val confirmed = stats != null && !loaded && !active
        releaseConfirmed = confirmed
        releaseMessage = if (confirmed) {
            null
        } else {
            "MNN native release not confirmed (loaded=$loaded, generationActive=$active); " +
                "the native owner may still hold model/generation resources."
        }
        if (confirmed) {
            synchronized(lifecycleLock) {
                sessions.values.forEach { it.close() }
                sessions.clear()
            }
        }
    }

    companion object {
        /** Return code meaning "no load has run yet"; distinct from every native rc. */
        const val NOT_LOADED_RC: Int = Int.MIN_VALUE
    }
}

/**
 * The production MNN chat session: [EngineSessionOwner] keeps the single execution lease and the
 * session generation, this class binds it to the native begin/generate/stop exchange.
 */
class MnnChatSessionPort internal constructor(
    override val sessionId: String,
    private val engine: MnnChatEnginePort,
    private val leaf: MnnChatNativeLeaf
) : ExecutableSessionPort {

    private val owner = EngineSessionOwner(sessionId)
    private val lifecycleLock = Any()

    @Volatile private var execution: MnnChatRequestHandle? = null

    override val currentGeneration: SessionGeneration get() = owner.currentGeneration
    override val isLeaseActive: Boolean get() = owner.isLeaseActive
    override val currentExecution: RequestExecutionHandle? get() = execution

    override fun tryAcquireExecutionLease(identity: RequestIdentity): ExecutionLease? =
        owner.tryAcquireExecutionLease(identity)

    override fun beginRequest(messagesJson: String, paramsJson: String): RequestExecutionHandle? {
        val lease: ExecutionLease
        synchronized(lifecycleLock) {
            // Retire the previous attempt's bookkeeping first; the native side keeps its own
            // reuse/reset semantics, so no native call is added here.
            execution?.close()
            execution = null
            lease = owner.tryAcquireExecutionLease(engine.newRequestIdentity(sessionId))
                ?: return null
        }
        // The native begin can block (prefill); it must run outside the session lock.
        val rc = leaf.beginCompletion(messagesJson, paramsJson)
        val handle = MnnChatRequestHandle(lease.identity, rc, lease, leaf)
        synchronized(lifecycleLock) { execution = handle }
        return handle
    }

    /** Unconditional stop; identical to the pre-port runner call. */
    fun requestStop() {
        execution?.requestStop() ?: leaf.requestStop()
    }

    /** Conditional stop; identical to the pre-port runner call. */
    fun requestStopIfActive(): Boolean =
        execution?.requestStopIfActive() ?: leaf.requestStopIfActive()

    /** Visual-producer running probe kept for the runner's `canReleasePreparedInputs` semantics. */
    fun isGenerationRunning(): Boolean = leaf.isGenerationRunning()

    /** True while no native owner may still read request-scoped media files. */
    fun canReleasePreparedInputs(): Boolean = !leaf.isGenerationRunning()


    override fun advanceGeneration(): SessionGeneration {
        synchronized(lifecycleLock) {
            execution?.close()
            execution = null
        }
        return owner.advanceGeneration()
    }

    override fun releaseAllResources() {
        owner.releaseAllResources()
    }

    /**
     * Session close retires the lease bookkeeping. Callers must only close after the native owner
     * stopped (or is confirmed gone); the engine's unload path enforces that ordering.
     */
    override fun close() {
        synchronized(lifecycleLock) {
            execution?.close()
            execution = null
        }
        owner.close()
    }
}

/**
 * One in-flight MNN chat request. Chunk pulling and stop signals are the production bridge calls;
 * [close] releases exactly this attempt's lease and is idempotent.
 */
class MnnChatRequestHandle internal constructor(
    override val identity: RequestIdentity,
    override val beginRc: Int,
    private val lease: ExecutionLease,
    private val leaf: MnnChatNativeLeaf
) : RequestExecutionHandle {

    private val released = AtomicBoolean(false)

    override val isReleased: Boolean get() = released.get()

    override fun nextChunk(): String? = leaf.generateNextChunk()

    override fun requestStop() {
        leaf.requestStop()
    }

    override fun requestStopIfActive(): Boolean = leaf.requestStopIfActive()

    override fun isNativeRunning(): Boolean = readMnnChatGenerationActive(leaf)

    override fun close() {
        if (released.compareAndSet(false, true)) {
            lease.close()
        }
    }
}