package com.muyuchat.core.engine

/** Monotonic elapsed milliseconds with an arbitrary origin, never a Unix timestamp. */
fun interface RuntimeMonotonicClock {
    fun nowMs(): Long
}

object SystemRuntimeMonotonicClock : RuntimeMonotonicClock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000L
}

internal class GenerationElapsedTiming(private val clock: RuntimeMonotonicClock) {
    private var startedAtMs: Long? = null
    private var firstTokenAtMs: Long? = null
    private var completedAtMs: Long? = null

    @Synchronized
    fun start() {
        startedAtMs = clock.nowMs()
        firstTokenAtMs = null
        completedAtMs = null
    }

    @Synchronized
    fun markFirstToken() {
        if (startedAtMs != null && firstTokenAtMs == null) firstTokenAtMs = clock.nowMs()
    }

    @Synchronized
    fun complete() {
        if (startedAtMs != null && completedAtMs == null) completedAtMs = clock.nowMs()
    }

    @Synchronized
    fun resetDecodeAttempt() {
        firstTokenAtMs = null
        completedAtMs = null
    }

    @Synchronized
    fun hasFirstToken(): Boolean = firstTokenAtMs != null

    @Synchronized
    fun snapshot(): GenerationElapsedSnapshot {
        val start = startedAtMs
        val first = firstTokenAtMs
        val completed = completedAtMs
        return GenerationElapsedSnapshot(
            ttftMs = if (start != null && first != null) (first - start).coerceAtLeast(0L) else 0L,
            decodeMs = if (completed != null && first != null) (completed - first).coerceAtLeast(1L) else 0L,
            elapsedMs = start?.let { ((completed ?: clock.nowMs()) - it).coerceAtLeast(0L) },
            completed = completed != null
        )
    }
}

internal data class GenerationElapsedSnapshot(
    val ttftMs: Long,
    val decodeMs: Long,
    val elapsedMs: Long?,
    val completed: Boolean
) {
    fun decodeTokensPerSecond(tokens: Int): Double =
        if (decodeMs > 0L && tokens > 0) tokens * 1000.0 / decodeMs else 0.0

    fun endToEndTokensPerSecond(tokens: Int, requireCompleted: Boolean = false): Double {
        val elapsed = elapsedMs ?: return 0.0
        if (tokens <= 0 || (requireCompleted && !completed)) return 0.0
        return tokens * 1000.0 / elapsed.coerceAtLeast(1L)
    }
}
