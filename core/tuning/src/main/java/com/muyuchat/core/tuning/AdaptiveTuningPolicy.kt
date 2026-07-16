package com.muyuchat.core.tuning

import com.muyuchat.core.engine.CanonicalParameterSet

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

/** Product tuning depth; kept independent from UI enums and Android classes. */
enum class TuningSearchDepth {
    QUICK,
    STANDARD,
    DEEP,
    POWER_SAVE
}

/** Ordered search phases required by the runtime bug-fix contract. */
enum class TuningSearchStage(val label: String) {
    THREADS("线程"),
    BATCH("batch/ubatch"),
    KV_FLASH("KV/Flash"),
    CONTEXT("上下文"),
    MODEL_SPECIAL("模型专项")
}

/**
 * A typed execution-only patch. Sampling, prompts, output length and assistant
 * state cannot enter this object, so candidate search cannot silently rewrite
 * generation behaviour.
 */
data class TuningCandidatePatch(
    val stage: TuningSearchStage,
    val label: String,
    val nThreads: Int? = null,
    val nThreadsBatch: Int? = null,
    val nCtx: Int? = null,
    val nBatch: Int? = null,
    val nUbatch: Int? = null,
    val cacheTypeK: String? = null,
    val cacheTypeV: String? = null,
    val flashAttention: String? = null,
    val speculativeType: String? = null,
    val speculativeDraftMax: Int? = null
) {
    val changedFields: Set<String>
        get() = buildSet {
            if (nThreads != null) add("n_threads")
            if (nThreadsBatch != null) add("n_threads_batch")
            if (nCtx != null) add("n_ctx")
            if (nBatch != null) add("n_batch")
            if (nUbatch != null) add("n_ubatch")
            if (cacheTypeK != null) add("cache_type_k")
            if (cacheTypeV != null) add("cache_type_v")
            if (flashAttention != null) add("flash_attn")
            if (speculativeType != null) add("spec_type")
            if (speculativeDraftMax != null) add("spec_draft_n_max")
        }

    fun applyTo(
        base: TuningExecutionProfile,
        profileId: String,
        revision: Long
    ): TuningExecutionProfile {
        val loadPatch = CanonicalParameterSet.of(
            buildMap<String, Any?> {
                nCtx?.let { put("n_ctx", it) }
                nBatch?.let { put("n_batch", it) }
                nUbatch?.let { put("n_ubatch", it) }
                cacheTypeK?.let { put("cache_type_k", it) }
                cacheTypeV?.let { put("cache_type_v", it) }
                flashAttention?.let { put("flash_attn", it) }
                speculativeType?.let { put("spec_type", it) }
                speculativeDraftMax?.let { put("spec_draft_n_max", it) }
            }
        )
        val hotPatch = CanonicalParameterSet.of(
            buildMap<String, Any?> {
                nThreads?.let { put("n_threads", it) }
                nThreadsBatch?.let { put("n_threads_batch", it) }
            }
        )
        val updatedLoad = base.loadBound.copy(
            nCtx = nCtx ?: base.loadBound.nCtx,
            nBatch = nBatch ?: base.loadBound.nBatch,
            nUbatch = nUbatch ?: base.loadBound.nUbatch,
            cacheTypeK = cacheTypeK ?: base.loadBound.cacheTypeK,
            cacheTypeV = cacheTypeV ?: base.loadBound.cacheTypeV,
            flashAttention = flashAttention ?: base.loadBound.flashAttention,
            speculativeType = speculativeType ?: base.loadBound.speculativeType,
            speculativeDraftMax = speculativeDraftMax ?: base.loadBound.speculativeDraftMax
        )
        val updatedHot = base.hotExecution.copy(
            nThreads = nThreads ?: base.hotExecution.nThreads,
            nThreadsBatch = nThreadsBatch ?: base.hotExecution.nThreadsBatch
        )
        return base.copy(
            engineProfile = base.engineProfile.copy(
                desiredLoadBoundValues = base.engineProfile.desiredLoadBoundValues.plus(loadPatch),
                resolvedLoadBoundValues = base.engineProfile.resolvedLoadBoundValues.plus(loadPatch),
                hotExecutionValues = base.engineProfile.hotExecutionValues.plus(hotPatch),
                desiredHotExecutionValues = base.engineProfile.desiredHotExecutionValues.plus(hotPatch),
                profileId = profileId,
                revision = revision,
                resolvedAt = System.currentTimeMillis()
            ),
            loadBound = updatedLoad,
            hotExecution = updatedHot,
            verificationLevel = ProfileVerificationLevel.UNVERIFIED,
            reason = listOf(base.reason, "${stage.label}候选：$label")
                .filter(String::isNotBlank)
                .joinToString(" ")
        )
    }
}

/** Deterministic, bounded candidate generation for each sequential stage. */
object StagedTuningCandidatePolicy {
    fun stagesFor(
        depth: TuningSearchDepth,
        capabilities: ModelTuningCapabilities,
        userOverrides: Set<String> = emptySet()
    ): List<TuningSearchStage> = buildList {
        val supportsThreadTuning = capabilities.runtime in setOf(
            TuningRuntime.LLAMA_CPP,
            TuningRuntime.MNN
        )
        val supportsKnownLlamaTuning = capabilities.runtime == TuningRuntime.LLAMA_CPP &&
            capabilities.knowledgeLevel == ModelKnowledgeLevel.KNOWN &&
            capabilities.metadataReadable

        if (supportsThreadTuning &&
            setOf("n_threads", "n_threads_batch").none(userOverrides::contains)
        ) {
            add(TuningSearchStage.THREADS)
        }
        if (depth != TuningSearchDepth.QUICK &&
            supportsKnownLlamaTuning &&
            capabilities.supportsBatchTuning &&
            "n_batch" !in userOverrides && "n_ubatch" !in userOverrides
        ) {
            add(TuningSearchStage.BATCH)
        }
        if (depth in setOf(TuningSearchDepth.STANDARD, TuningSearchDepth.DEEP) &&
            supportsKnownLlamaTuning &&
            (capabilities.supportsQuantizedKv || capabilities.supportsFlashAttention) &&
            setOf("cache_type_k", "cache_type_v", "flash_attn").none(userOverrides::contains)
        ) {
            add(TuningSearchStage.KV_FLASH)
        }
        if (depth == TuningSearchDepth.DEEP &&
            supportsKnownLlamaTuning &&
            capabilities.maxContextTokens != null &&
            setOf("n_ctx", "n_batch", "n_ubatch").none(userOverrides::contains)
        ) {
            add(TuningSearchStage.CONTEXT)
        }
        if (depth == TuningSearchDepth.DEEP &&
            supportsKnownLlamaTuning &&
            capabilities.supportsSpeculativeMtp &&
            setOf("spec_type", "spec_draft_n_max", "n_batch", "n_ubatch")
                .none(userOverrides::contains)
        ) {
            add(TuningSearchStage.MODEL_SPECIAL)
        }
    }

    fun candidatesFor(
        stage: TuningSearchStage,
        base: TuningExecutionProfile,
        capabilities: ModelTuningCapabilities,
        cpuCores: Int,
        estimatedBigCores: Int,
        depth: TuningSearchDepth,
        userOverrides: Set<String> = emptySet()
    ): List<TuningCandidatePatch> {
        if (stage !in stagesFor(depth, capabilities, userOverrides)) return emptyList()
        return when (stage) {
            TuningSearchStage.THREADS -> threadCandidates(base, cpuCores, estimatedBigCores, depth)
            TuningSearchStage.BATCH -> batchCandidates(base, depth)
            TuningSearchStage.KV_FLASH -> kvCandidates(base, capabilities, depth)
            TuningSearchStage.CONTEXT -> contextCandidates(base, capabilities)
            TuningSearchStage.MODEL_SPECIAL -> mtpCandidates(base, capabilities)
        }
    }

    private fun threadCandidates(
        base: TuningExecutionProfile,
        cpuCores: Int,
        estimatedBigCores: Int,
        depth: TuningSearchDepth
    ): List<TuningCandidatePatch> {
        val cores = cpuCores.coerceAtLeast(1)
        val recommended = base.hotExecution.nThreads.coerceIn(1, cores)
        val quick = TuningCandidatePolicy.quickThreadCandidates(cores, estimatedBigCores, recommended)
        val values = when (depth) {
            TuningSearchDepth.QUICK -> quick
            TuningSearchDepth.STANDARD -> quick + listOf(recommended - 2, recommended + 2) + (1..cores)
            TuningSearchDepth.DEEP -> listOf(recommended, estimatedBigCores, recommended - 1, recommended + 1) + (1..cores)
            TuningSearchDepth.POWER_SAVE -> listOf(recommended.coerceAtMost(4), 2, 1, 3, 4)
        }
        val limit = when (depth) {
            TuningSearchDepth.QUICK -> 4
            TuningSearchDepth.STANDARD -> 6
            TuningSearchDepth.DEEP -> 8
            TuningSearchDepth.POWER_SAVE -> 3
        }
        return values
            .map { it.coerceIn(1, cores) }
            .filter { depth != TuningSearchDepth.POWER_SAVE || it <= 4 }
            .distinct()
            .take(limit)
            .map { threads ->
                TuningCandidatePatch(
                    stage = TuningSearchStage.THREADS,
                    label = "n_threads=$threads",
                    nThreads = threads,
                    nThreadsBatch = base.hotExecution.nThreadsBatch?.coerceAtMost(threads.coerceAtLeast(1))
                )
            }
    }

    private fun batchCandidates(
        base: TuningExecutionProfile,
        depth: TuningSearchDepth
    ): List<TuningCandidatePatch> {
        val maxBatch = when (depth) {
            TuningSearchDepth.DEEP -> 2048
            TuningSearchDepth.POWER_SAVE -> 512
            else -> 1024
        }.coerceAtMost(base.loadBound.nCtx.coerceAtLeast(128))
        val pairs = buildList {
            val currentBatch = base.loadBound.nBatch
            val currentUbatch = base.loadBound.nUbatch
            if (currentBatch != null && currentUbatch != null) add(currentBatch to currentUbatch)
            addAll(
                listOf(
                    256 to 128,
                    512 to 128,
                    512 to 256,
                    1024 to 256,
                    1024 to 512,
                    2048 to 256
                )
            )
        }
        val limit = when (depth) {
            TuningSearchDepth.STANDARD -> 4
            TuningSearchDepth.DEEP -> 6
            TuningSearchDepth.POWER_SAVE -> 2
            TuningSearchDepth.QUICK -> 0
        }
        return pairs
            .filter { (batch, ubatch) -> batch <= maxBatch && ubatch <= batch }
            .distinct()
            .take(limit)
            .map { (batch, ubatch) ->
                TuningCandidatePatch(
                    stage = TuningSearchStage.BATCH,
                    label = "n_batch=$batch, n_ubatch=$ubatch",
                    nBatch = batch,
                    nUbatch = ubatch
                )
            }
    }

    private fun kvCandidates(
        base: TuningExecutionProfile,
        capabilities: ModelTuningCapabilities,
        depth: TuningSearchDepth
    ): List<TuningCandidatePatch> {
        val current = Triple(
            base.loadBound.cacheTypeK ?: "f16",
            base.loadBound.cacheTypeV ?: "f16",
            base.loadBound.flashAttention ?: "off"
        )
        val values = buildList {
            add(current)
            add(Triple("f16", "f16", if (capabilities.supportsFlashAttention) "on" else "off"))
            val quantizedFlash = if (capabilities.supportsFlashAttention) "on" else "off"
            if (capabilities.supportsQuantizedKv) add(Triple("q8_0", "q8_0", quantizedFlash))
            if (capabilities.supportsQuantizedKv && depth == TuningSearchDepth.DEEP) {
                add(Triple("q4_0", "q4_0", quantizedFlash))
            }
        }
        return values.distinct().map { (k, v, flash) ->
            TuningCandidatePatch(
                stage = TuningSearchStage.KV_FLASH,
                label = "K=$k, V=$v, Flash=$flash",
                cacheTypeK = k,
                cacheTypeV = v,
                flashAttention = if (capabilities.supportsFlashAttention) flash else base.loadBound.flashAttention
            )
        }
    }

    private fun contextCandidates(
        base: TuningExecutionProfile,
        capabilities: ModelTuningCapabilities
    ): List<TuningCandidatePatch> {
        val maximum = capabilities.maxContextTokens?.coerceAtLeast(128) ?: return emptyList()
        val current = base.loadBound.nCtx.coerceIn(128, maximum)
        val values = listOf(current, 2048, 4096, 8192, 16_384, 32_768)
            .filter { it <= maximum }
            .distinct()
            .sorted()
        return values.map { context ->
            TuningCandidatePatch(
                stage = TuningSearchStage.CONTEXT,
                label = "n_ctx=$context",
                nCtx = context,
                nBatch = base.loadBound.nBatch?.coerceAtMost(context),
                nUbatch = base.loadBound.nUbatch?.coerceAtMost(
                    base.loadBound.nBatch?.coerceAtMost(context) ?: context
                )
            )
        }
    }

    private fun mtpCandidates(
        base: TuningExecutionProfile,
        capabilities: ModelTuningCapabilities
    ): List<TuningCandidatePatch> {
        if (!capabilities.supportsSpeculativeMtp) return emptyList()
        val currentType = base.loadBound.speculativeType ?: "none"
        val currentMax = base.loadBound.speculativeDraftMax ?: 0
        return listOf(
            currentType to currentMax,
            "none" to 0,
            "draft-mtp" to 1,
            "draft-mtp" to 2
        ).distinct().map { (type, draftMax) ->
            val batch = if (type == "draft-mtp") {
                maxOf(
                    base.loadBound.nBatch ?: 0,
                    base.loadBound.nUbatch ?: 0,
                    draftMax + 1,
                    256
                )
            } else {
                base.loadBound.nBatch
            }
            TuningCandidatePatch(
                stage = TuningSearchStage.MODEL_SPECIAL,
                label = "spec_type=$type, draft=$draftMax",
                speculativeType = type,
                speculativeDraftMax = draftMax,
                nBatch = batch,
                nUbatch = base.loadBound.nUbatch?.let { ubatch ->
                    batch?.let { ubatch.coerceAtMost(it) } ?: ubatch
                }
            )
        }
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
 * Stage-aware winner selection. Context search is a capacity proof rather than
 * a throughput contest: once candidates pass every hard gate, Deep mode should
 * retain the largest proven context and use performance only as a tie-breaker.
 * Other stages continue to maximize the ordinary candidate score.
 */
object StagedCandidateSelectionPolicy {
    fun <T> selectBestEligible(
        stage: TuningSearchStage,
        candidates: Iterable<T>,
        scoreOf: (T) -> CandidateScore,
        contextTokensOf: (T) -> Int
    ): T? {
        if (stage != TuningSearchStage.CONTEXT) {
            return CandidateSelectionPolicy.selectBestEligible(candidates, scoreOf)
        }
        return candidates
            .mapNotNull { candidate ->
                val score = scoreOf(candidate)
                val value = score.value
                if (score.eligible && value != null && value.isFinite()) {
                    Triple(candidate, contextTokensOf(candidate), value)
                } else {
                    null
                }
            }
            .maxWithOrNull(compareBy<Triple<T, Int, Double>> { it.second }.thenBy { it.third })
            ?.first
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
