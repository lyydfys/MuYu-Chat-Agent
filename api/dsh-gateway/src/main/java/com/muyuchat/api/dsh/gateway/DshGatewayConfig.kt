package com.muyuchat.api.dsh.gateway

import com.muyuchat.core.dsh.contract.console.DSH_MAX_MODEL_VISIBLE_TOOLS

/**
 * Bounded resource limits for one internal DSH gateway run.
 *
 * The gateway serves exactly one trusted local caller (the DSH Node runtime) over loopback, so
 * every limit is defensive rather than a user-facing quota: a misbehaving client or backend must
 * not be able to exhaust the app process.
 */
data class DshGatewayLimits(
    /** Request line + headers ceiling. */
    val maxHeaderBytes: Int = DEFAULT_MAX_HEADER_BYTES,
    /** JSON request body ceiling. DSH tool schemas and transcripts are large but bounded. */
    val maxRequestBytes: Int = DEFAULT_MAX_REQUEST_BYTES,
    /** Total visible text, reasoning text and tool argument characters emitted for one call. */
    val maxResponseChars: Int = DEFAULT_MAX_RESPONSE_CHARS,
    /** Simultaneously executing model calls; further calls receive HTTP 429. */
    val maxConcurrentCalls: Int = DEFAULT_MAX_CONCURRENT_CALLS,
    /** Simultaneously open TCP connections; further connections receive HTTP 503. */
    val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    /** Request read timeout; cleared once the body is fully read (SSE responses stay open). */
    val clientReadTimeoutMillis: Int = DEFAULT_CLIENT_READ_TIMEOUT_MILLIS,
    /** SSE comment heartbeat interval, used to observe a vanished peer. */
    val streamHeartbeatMillis: Long = DEFAULT_STREAM_HEARTBEAT_MILLIS,
    /**
     * Upper bound for one backend cancel dispatch (the exact native stop). The adapter's engine
     * stop is itself bounded at 500 ms; this outer window protects the SSE/response path from an
     * adapter that never returns. On timeout the stream is still cancelled structurally and a
     * warning is logged; the gateway never waits for native settle evidence.
     */
    val cancelDispatchTimeoutMillis: Long = DEFAULT_CANCEL_DISPATCH_TIMEOUT_MILLIS,
    /**
     * Upper bound for waiting for a cancelled backend collector to unwind.
     *
     * A local model prefill can be blocked inside the worker's `beginCompletion` Binder call and
     * does not observe coroutine cancellation until that call returns (device evidence: 206–245 s,
     * 2026-10-26). This drain window is therefore the maximum time the gateway waits for
     * cooperative collector cancellation before it settles the call, closes the response and
     * releases the call lease. The backend (native) work still needs the out-of-band engine stop
     * dispatched through [cancelDispatchTimeoutMillis]; this window only keeps `/healthz
     * active_calls`, the registry entry and the client response from lingering.
     */
    val collectorDrainTimeoutMillis: Long = DEFAULT_COLLECTOR_DRAIN_TIMEOUT_MILLIS,
    /** Tool definitions accepted per request. */
    val maxToolsPerRequest: Int = DEFAULT_MAX_TOOLS_PER_REQUEST,
    /** Tool calls accepted per model response. */
    val maxToolCallsPerResponse: Int = DEFAULT_MAX_TOOL_CALLS_PER_RESPONSE,
    /**
     * Complete JSON argument characters accepted for one tool call. A producer must send the
     * complete object explicitly (at least `{}` for a no-argument tool); the gateway never
     * synthesizes executable arguments from missing fragments.
     */
    val maxToolArgumentChars: Int = DEFAULT_MAX_TOOL_ARGUMENT_CHARS,
) {
    init {
        require(maxHeaderBytes in 1_024..MAX_HARD_HEADER_BYTES) {
            "maxHeaderBytes must be in 1024..$MAX_HARD_HEADER_BYTES."
        }
        require(maxRequestBytes in 1_024..MAX_HARD_REQUEST_BYTES) {
            "maxRequestBytes must be in 1024..$MAX_HARD_REQUEST_BYTES."
        }
        require(maxResponseChars >= 1_024) { "maxResponseChars must be at least 1024." }
        require(maxConcurrentCalls in 1..64) { "maxConcurrentCalls must be in 1..64." }
        require(maxConnections in 1..256) { "maxConnections must be in 1..256." }
        require(clientReadTimeoutMillis in 1_000..120_000) {
            "clientReadTimeoutMillis must be in 1000..120000."
        }
        require(streamHeartbeatMillis in 1_000..120_000) {
            "streamHeartbeatMillis must be in 1000..120000."
        }
        require(cancelDispatchTimeoutMillis in 100L..30_000L) {
            "cancelDispatchTimeoutMillis must be in 100..30000."
        }
        require(collectorDrainTimeoutMillis in 100L..10_000L) {
            "collectorDrainTimeoutMillis must be in 100..10000."
        }
        require(maxToolsPerRequest in 1..512) { "maxToolsPerRequest must be in 1..512." }
        require(maxToolCallsPerResponse in 1..128) {
            "maxToolCallsPerResponse must be in 1..128."
        }
        require(maxToolArgumentChars in 256..MAX_HARD_TOOL_ARGUMENT_CHARS) {
            "maxToolArgumentChars must be in 256..$MAX_HARD_TOOL_ARGUMENT_CHARS."
        }
    }

    companion object {
        const val DEFAULT_MAX_HEADER_BYTES = 32 * 1024
        const val DEFAULT_MAX_REQUEST_BYTES = 8 * 1024 * 1024
        const val DEFAULT_MAX_RESPONSE_CHARS = 8 * 1024 * 1024
        const val DEFAULT_MAX_CONCURRENT_CALLS = 4
        const val DEFAULT_MAX_CONNECTIONS = 16
        const val DEFAULT_CLIENT_READ_TIMEOUT_MILLIS = 15_000
        const val DEFAULT_STREAM_HEARTBEAT_MILLIS = 15_000L
        const val DEFAULT_CANCEL_DISPATCH_TIMEOUT_MILLIS = 2_000L
        const val DEFAULT_COLLECTOR_DRAIN_TIMEOUT_MILLIS = 1_000L
        const val DEFAULT_MAX_TOOLS_PER_REQUEST = DSH_MAX_MODEL_VISIBLE_TOOLS
        const val DEFAULT_MAX_TOOL_CALLS_PER_RESPONSE = 32
        const val DEFAULT_MAX_TOOL_ARGUMENT_CHARS = 1024 * 1024

        private const val MAX_HARD_HEADER_BYTES = 256 * 1024
        private const val MAX_HARD_REQUEST_BYTES = 64 * 1024 * 1024
        private const val MAX_HARD_TOOL_ARGUMENT_CHARS = 8 * 1024 * 1024

        val Default: DshGatewayLimits = DshGatewayLimits()
    }
}

/** Coarse log levels for the injected [DshGatewayLogger]. */
enum class DshGatewayLogLevel {
    DEBUG,
    INFO,
    WARN
}

/**
 * Diagnostics sink for the gateway. Implementations may write to Android logcat, but this module
 * intentionally depends on no Android logging API so its tests are plain JVM tests.
 *
 * The gateway only ever passes lifecycle lines, route names and safe error codes here. Request
 * bodies, model output, the bearer token and the Authorization header are never logged.
 */
fun interface DshGatewayLogger {
    fun log(level: DshGatewayLogLevel, message: String)

    companion object {
        /** Silent logger used by tests and by hosts that have not wired diagnostics yet. */
        val None: DshGatewayLogger = DshGatewayLogger { _, _ -> }
    }
}
