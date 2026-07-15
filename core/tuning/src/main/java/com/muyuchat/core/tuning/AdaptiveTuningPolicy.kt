package com.muyuchat.core.tuning

/**
 * Pure, deterministic policies shared by UI- and API-triggered tuning jobs.
 *
 * Keeping these rules outside an Android component makes their ordering and
 * hard-gate behaviour independently testable.
 */
object TuningCandidatePolicy {
    const val QUICK_MAX_CANDIDATES: Int = 4

    /**
     * Orders a quick sweep around the recommendation and the device's big-core
     * count before considering ordinary thread counts.
     *
     * Lower neighbours are visited before upper neighbours because they are the
     * safer probe when two otherwise-equivalent candidates are available.
     */
    fun quickThreadCandidates(
        cpuCores: Int,
        estimatedBigCores: Int,
        recommendedThreads: Int?
    ): List<Int> {
        val maximumThreads = cpuCores.coerceAtLeast(1)
        val bigCoreThreads = estimatedBigCores.coerceIn(1, maximumThreads)
        val recommended = (recommendedThreads ?: bigCoreThreads).coerceIn(1, maximumThreads)
        val anchors = listOf(recommended, bigCoreThreads).distinct()

        val ordered = buildList {
            addAll(anchors)
            for (distance in 1 until maximumThreads) {
                anchors.forEach { add(it - distance) }
                anchors.forEach { add(it + distance) }
            }
            addAll(1..maximumThreads)
        }

        return ordered
            .filter { it in 1..maximumThreads }
            .distinct()
            .take(QUICK_MAX_CANDIDATES)
    }
}

/**
 * Selects only candidates that passed every hard gate.
 *
 * Ineligible, missing, NaN and infinite scores are deliberately removed before
 * comparison. This prevents an all-failed sweep from accidentally selecting a
 * candidate merely because callers mapped a rejected score to negative infinity.
 */
object CandidateSelectionPolicy {
    fun <T> selectBestEligible(
        candidates: Iterable<T>,
        scoreOf: (T) -> CandidateScore
    ): T? {
        var bestCandidate: T? = null
        var bestValue: Double? = null

        candidates.forEach { candidate ->
            val score = scoreOf(candidate)
            val value = score.value
            if (score.eligible && value != null && value.isFinite()) {
                if (bestValue == null || value > bestValue!!) {
                    bestCandidate = candidate
                    bestValue = value
                }
            }
        }
        return bestCandidate
    }
}

/**
 * Minimal first-load proof used before an external model is made available.
 *
 * Bootstrap loading must prove that the selected runtime can consume a user
 * turn and return clean deterministic text, but it must not reject a valid
 * small model merely because it cannot follow the richer multi-field tuning
 * benchmark. The stricter [MinimumTextCanaryPolicy] remains the hard gate for
 * committing tuned candidates.
 */
object BootstrapLoadCanaryPolicy {
    const val expectedOutput: String = "MCA_LOAD_OK_17"
    const val prompt: String =
        "Return exactly this token and nothing else: MCA_LOAD_OK_17"

    fun matches(output: String): Boolean {
        if (output.isEmpty() || '\uFFFD' in output) return false
        val normalized = output
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .removeSuffix("\n")
        return normalized == expectedOutput
    }
}

/** Strict output contract for the disposable minimum-text correctness canary. */
object MinimumTextCanaryPolicy {
    val expectedLines: List<String> = listOf(
        "FORMAT=17",
        "ZH=通过",
        "ROLE=USER",
        "CTX=蓝鲸",
        "CLEAN=OK"
    )

    val expectedOutput: String = expectedLines.joinToString("\n")

    /**
     * Some native decoders preserve every field byte-for-byte but suppress newline tokens.
     * Treat that single, exact transport representation as equivalent; field values, order,
     * multiplicity, whitespace and surrounding text remain strict.
     */
    val expectedCompactOutput: String = expectedLines.joinToString("")

    const val prompt: String =
        "请严格输出以下五行，不要添加、删除或解释任何内容：\n" +
            "FORMAT=17\n" +
            "ZH=通过\n" +
            "ROLE=USER\n" +
            "CTX=蓝鲸\n" +
            "CLEAN=OK"

    /**
     * Accepts LF/CRLF line endings, one conventional terminal newline, and the
     * exact newline-suppressed native transport representation. Rejects extra
     * text, whitespace, reordered lines, partial markers and replacement
     * characters.
     */
    fun matches(output: String): Boolean {
        if (output.isEmpty() || '\uFFFD' in output) return false
        val normalized = output
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .removeSuffix("\n")
        return normalized == expectedOutput || normalized == expectedCompactOutput
    }
}
