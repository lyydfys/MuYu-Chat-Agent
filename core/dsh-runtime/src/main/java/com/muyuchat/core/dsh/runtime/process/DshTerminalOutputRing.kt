package com.muyuchat.core.dsh.runtime.process

import java.util.ArrayDeque

/**
 * Bounded byte scrollback for one terminal session.
 *
 * It keeps the newest [capacityBytes] of output and exposes a monotonic absolute cursor, so a
 * client that reconnects with its last offset either receives exactly the missing bytes or a
 * `truncated` page when the window has already dropped them. Dropped bytes never renumber
 * surviving bytes.
 *
 * The pump appends while a reconnect may read, so every operation is synchronized on the ring.
 */
internal class DshTerminalOutputRing(capacityBytes: Int) {

    private val capacity = capacityBytes.coerceAtLeast(MIN_CAPACITY_BYTES)
    private val chunks = ArrayDeque<ByteArray>()
    private var retained = 0L
    private var end = 0L

    @Synchronized
    fun startOffset(): Long = end - retained

    @Synchronized
    fun endOffset(): Long = end

    /** Consistent `(firstRetainedOffset, nextOffset)` pair for one snapshot publication. */
    @Synchronized
    fun bounds(): Bounds = Bounds(end - retained, end)

    @Synchronized
    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        // Keep the newest window: drop whole chunks while the next one is not needed, then trim
        // a partial head chunk. A single oversized chunk keeps only its tail.
        val kept = if (bytes.size <= capacity) bytes else bytes.copyOfRange(bytes.size - capacity, bytes.size)
        chunks.addLast(kept)
        retained += kept.size
        end += bytes.size
        while (chunks.size > 1) {
            val head = chunks.peekFirst() ?: break
            if (retained - head.size < capacity) break
            chunks.removeFirst()
            retained -= head.size
        }
        if (retained > capacity) {
            val only = chunks.peekFirst() ?: return
            val overflow = (retained - capacity).toInt()
            chunks.removeFirst()
            chunks.addFirst(only.copyOfRange(overflow, only.size))
            retained -= overflow
        }
    }

    @Synchronized
    fun read(fromOffset: Long, maxBytes: Int): Page {
        val start = startOffset()
        val first = fromOffset.coerceAtLeast(start)
        val truncated = fromOffset < start || fromOffset > end
        val available = (end - first).coerceAtLeast(0L)
        val take = minOf(available, maxBytes.toLong()).toInt()
        if (take <= 0) return Page(ByteArray(0), end, truncated)
        val out = ByteArray(take)
        var absolute = start
        var written = 0
        for (chunk in chunks) {
            val chunkStart = absolute
            val chunkEnd = absolute + chunk.size
            absolute = chunkEnd
            if (chunkEnd <= first || written >= take) continue
            val localStart = (first - chunkStart).coerceAtLeast(0L).toInt()
            val localEnd = minOf(chunk.size, localStart + (take - written))
            if (localEnd <= localStart) continue
            System.arraycopy(chunk, localStart, out, written, localEnd - localStart)
            written += localEnd - localStart
        }
        return Page(
            data = if (written == out.size) out else out.copyOf(written),
            nextOffset = first + written,
            truncated = truncated || written < take
        )
    }

    class Page(val data: ByteArray, val nextOffset: Long, val truncated: Boolean)

    class Bounds(val firstRetainedOffset: Long, val nextOffset: Long)

    private companion object {
        const val MIN_CAPACITY_BYTES = 4 * 1024
    }
}
