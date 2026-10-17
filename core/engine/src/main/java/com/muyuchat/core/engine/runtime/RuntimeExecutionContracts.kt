package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.RequestIdentity

/**
 * A live execution attempt started by [ExecutableSessionPort.beginRequest].
 *
 * The handle binds one native request to its [identity]; the execution lease the session granted
 * for that identity is released by [close] and by nothing else. A cleanup that arrives late for a
 * retired attempt therefore cannot release the lease of the successor that reused the session.
 *
 * Universal Hardware Admission Notice: this contract reports real resource state only. No path in
 * it may be gated on a device profile, chipset or allowlist.
 */
interface RequestExecutionHandle : AutoCloseable {

    /** Attempt identity bound by the session's execution lease (carries the session generation). */
    val identity: RequestIdentity

    /** Raw return code of the native begin call; `0` means the executor accepted the request. */
    val beginRc: Int

    val isAccepted: Boolean get() = beginRc == 0

    /** True once [close] released the execution lease; idempotent. */
    val isReleased: Boolean

    /**
     * Pulls the next streamed chunk from the native executor.
     * `null` keeps the platform's end-of-stream meaning; an empty string stays a real frame.
     */
    fun nextChunk(): String?

    /** Unconditional cooperative stop signal (native `requestStop`). */
    fun requestStop()

    /** Stops only while a generation is actually running (native `requestStopIfActive`). */
    fun requestStopIfActive(): Boolean

    /**
     * Drain acknowledgement: true while the native executor is still producing output for this
     * request. False is the physical-stop evidence that lets the lease/session binding be retired;
     * it must come from the native runtime, never from "our map is empty".
     */
    fun isNativeRunning(): Boolean

    /**
     * Releases this attempt's execution lease. Idempotent, never stops the native generation, and
     * never touches another attempt's lease.
     */
    override fun close()
}

/**
 * A [SessionPort] that can actually execute a request under its single execution lease.
 *
 * Implementations keep the lease/generation bookkeeping in [EngineSessionOwner] (one live lease
 * per session) and delegate the native work to their backend's leaf; sessions that cannot execute
 * (e.g. a backend with no wired loader) simply do not implement this interface.
 */
interface ExecutableSessionPort : SessionPort {

    /** The live attempt of this session, or null while the session is idle. */
    val currentExecution: RequestExecutionHandle?

    /**
     * Starts one request on this session.
     *
     * A previous attempt of this session is retired first (bookkeeping only - the native side
     * keeps its own reset semantics), then a fresh lease is taken for a new attempt identity and
     * the native begin is invoked exactly once.
     *
     * Returns null **without calling the executor** when the session is closed or a foreign lease
     * is active. When the native begin ran, a handle is always returned - even for a non-zero
     * [RequestExecutionHandle.beginRc] - so the caller keeps the exact executor return code.
     */
    fun beginRequest(messagesJson: String, paramsJson: String): RequestExecutionHandle?
}