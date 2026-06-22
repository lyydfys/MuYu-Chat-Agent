package com.muyuchat.core.engine

internal class ReasoningLoopGuard(
    private val minNormalizedChars: Int = 420,
    private val segmentChars: Int = 140,
    private val checkStepChars: Int = 80,
    private val triggerHits: Int = 2,
    private val similarityThreshold: Double = 0.82
) {
    private val normalized = StringBuilder()
    private var lastCheckedLength = 0
    private var consecutiveHits = 0

    fun shouldStop(delta: String): Boolean {
        val compact = delta.normalizeForLoopCheck()
        if (compact.isBlank()) return false
        normalized.append(compact)
        if (normalized.length > MAX_BUFFER_CHARS) {
            normalized.delete(0, normalized.length - MAX_BUFFER_CHARS)
            lastCheckedLength = lastCheckedLength.coerceAtMost(normalized.length)
        }
        if (normalized.length < minNormalizedChars) return false
        if (normalized.length - lastCheckedLength < checkStepChars) return false
        lastCheckedLength = normalized.length

        val text = normalized.toString()
        val tail = text.takeLast(segmentChars)
        val previous = text.dropLast(tail.length).takeLast(segmentChars)
        if (previous.length < segmentChars / 2 || tail.length < segmentChars / 2) return false

        val similar = diceSimilarity(previous, tail) >= similarityThreshold
        consecutiveHits = if (similar) consecutiveHits + 1 else 0
        return consecutiveHits >= triggerHits
    }

    private fun String.normalizeForLoopCheck(): String =
        lowercase()
            .replace(Regex("""[`*_>#\[\](){},.;:：，。！？!?'"“”‘’\-\s]+"""), "")
            .trim()

    private fun diceSimilarity(left: String, right: String): Double {
        if (left.isBlank() || right.isBlank()) return 0.0
        val leftGrams = left.charBigrams()
        val rightGrams = right.charBigrams()
        if (leftGrams.isEmpty() || rightGrams.isEmpty()) return 0.0
        var overlap = 0
        val rightCounts = rightGrams.groupingBy { it }.eachCount().toMutableMap()
        leftGrams.forEach { gram ->
            val count = rightCounts[gram] ?: 0
            if (count > 0) {
                overlap += 1
                if (count == 1) rightCounts.remove(gram) else rightCounts[gram] = count - 1
            }
        }
        return (2.0 * overlap) / (leftGrams.size + rightGrams.size)
    }

    private fun String.charBigrams(): List<String> {
        if (length < 2) return emptyList()
        return windowed(size = 2, step = 1)
    }

    private companion object {
        const val MAX_BUFFER_CHARS = 1600
    }
}
