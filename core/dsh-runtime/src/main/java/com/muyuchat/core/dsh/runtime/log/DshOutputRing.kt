package com.muyuchat.core.dsh.runtime.log

import java.util.ArrayDeque

/**
 * Bounded, redacted ring buffer of the DSH Node process output.
 *
 * The buffer is the only retention point for host stdout/stderr: it is capacity-bounded,
 * replaces the oldest lines, and redacts secret shapes before storing. It never persists to
 * disk and is not exposed through IPC in full; diagnostics snapshots use [tail].
 */
class DshOutputRing(
    private val capacity: Int = 400,
    private val maxLineChars: Int = 4_096,
    private val redactor: (String) -> String = { DshLogRedactor.redactLine(it, maxLineChars) }
) {
    init {
        require(capacity in 2..100_000) { "dsh: output ring capacity out of range: $capacity" }
    }

    private val lines = ArrayDeque<String>(capacity)
    private var dropped = 0L
    private var total = 0L

    @Synchronized
    fun add(rawLine: String) {
        val safe = redactor(rawLine)
        total++
        if (lines.size == capacity) {
            lines.removeFirst()
            dropped++
        }
        lines.addLast(safe)
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun tail(count: Int): String {
        if (count <= 0 || lines.isEmpty()) return ""
        val from = (lines.size - count).coerceAtLeast(0)
        return lines.toList().subList(from, lines.size).joinToString("\n")
    }

    @Synchronized
    fun totalLines(): Long = total

    @Synchronized
    fun droppedLines(): Long = dropped

    @Synchronized
    fun clear() {
        lines.clear()
        dropped = 0
        total = 0
    }
}
