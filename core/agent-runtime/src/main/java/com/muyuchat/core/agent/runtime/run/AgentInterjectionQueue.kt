package com.muyuchat.core.agent.runtime.run

/**
 * Bounded, thread-safe queue of user interjections for a running agent loop.
 *
 * The point is Mika's "reads it before its next step" semantics: text typed while a run is in flight
 * must not be delivered mid-tool-call. It is buffered here and only consumed at a step boundary via
 * [drainAtStepBoundary].
 *
 * Threading: writes come from the UI thread, reads from the loop coroutine. Every operation takes one
 * monitor, so an [enqueue] racing a [drainAtStepBoundary] can neither lose a message that was already
 * accepted nor resurrect one that was drained.
 *
 * Ordering: FIFO by arrival. A full queue evicts the oldest entry so the newest instruction always
 * survives (a stale instruction is worse than a dropped one).
 */
class AgentInterjectionQueue {

    private val lock = Any()

    /** Insertion-ordered; both ends are touched, so a deque rather than a list copy per eviction. */
    private val entries = ArrayDeque<Interjection>()

    /**
     * Appends [text] as an interjection observed at [atMillis].
     *
     * @return true when the text was accepted without evicting anything; false when it was rejected
     *   (blank, or longer than [MAX_CHARS]) **or** accepted only by dropping the oldest entry. Callers
     *   use the false return to show "已丢弃最早的一条插话", never to hide a rejection.
     */
    fun enqueue(text: String, atMillis: Long): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_CHARS) return false
        synchronized(lock) {
            var evicted = false
            while (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
                evicted = true
            }
            entries.addLast(Interjection(trimmed, atMillis))
            return !evicted
        }
    }

    /**
     * Takes every pending interjection in arrival order and clears the queue.
     *
     * Called exactly once per agent step boundary. The returned list is a snapshot: nothing that is
     * enqueued after this call can have been observed by the step that is about to start.
     */
    fun drainAtStepBoundary(): List<String> = synchronized(lock) {
        if (entries.isEmpty()) return emptyList()
        val drained = entries.map { it.text }
        entries.clear()
        drained
    }

    /** Read-only snapshot for the UI ("N 条插话待送达"); does not consume anything. */
    fun pending(): List<String> = synchronized(lock) { entries.map { it.text } }

    /** True when nothing is waiting. */
    fun isEmpty(): Boolean = synchronized(lock) { entries.isEmpty() }

    /** One buffered message plus the monotonic time it arrived. */
    private data class Interjection(val text: String, val atMillis: Long)

    companion object {
        /** Bounded like the rest of the loop's user-supplied inputs. */
        const val MAX_ENTRIES: Int = 5

        /** Longer text is rejected rather than truncated, so the model never sees a half sentence. */
        const val MAX_CHARS: Int = 2_000
    }
}
