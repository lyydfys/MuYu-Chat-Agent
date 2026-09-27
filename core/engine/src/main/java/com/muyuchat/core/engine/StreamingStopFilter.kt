package com.muyuchat.core.engine

/** Retains only suffixes that could become a stop sequence in a later delta. */
class StreamingStopFilter(stops: List<String>) {
    private val markers = stops.filter(String::isNotEmpty).distinct()
    private var pending = ""
    var stopped = false
        private set

    fun accept(delta: String): String {
        if (stopped) return ""
        pending += delta
        val match = markers.map { pending.indexOf(it) }.filter { it >= 0 }.minOrNull()
        if (match != null) {
            stopped = true
            return pending.substring(0, match).also { pending = "" }
        }
        val retained = markers.maxOfOrNull { marker ->
            (1..minOf(marker.length - 1, pending.length))
                .lastOrNull { pending.endsWith(marker.take(it)) } ?: 0
        } ?: 0
        return pending.dropLast(retained).also { pending = pending.takeLast(retained) }
    }

    fun finish(): String = pending.also { pending = "" }
}
