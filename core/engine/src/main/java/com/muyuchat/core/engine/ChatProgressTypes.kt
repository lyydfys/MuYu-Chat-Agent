package com.muyuchat.core.engine

data class RuntimeStats(
    val loaded: Boolean = false,
    val modelPath: String? = null,
    val backend: String = "cpu",
    val loadMs: Long = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val ttftMs: Long = 0,
    val prefillMs: Long = 0,
    /** Tokens actually evaluated by the native prefill pass per second. */
    val prefillTps: Double = 0.0,
    /** Tokens/s if the full logical prompt (including reused KV) were counted. */
    val effectivePromptTps: Double = 0.0,
    /** Number of prompt tokens that were actually evaluated this request. */
    val prefillTokens: Int = 0,
    val decodeMs: Long = 0,
    val decodeTps: Double = 0.0,
    val e2eTps: Double = 0.0,
    val nativePssKb: Long = 0,
    val processRssKb: Long = 0,
    val nativeHeapKb: Long = 0,
    val nativeHeapSizeKb: Long = 0,
    val javaHeapKb: Long = 0,
    val availMemKb: Long = 0,
    val totalMemKb: Long = 0,
    val advertisedMemKb: Long = 0,
    val memoryThresholdKb: Long = 0,
    val isLowMemory: Boolean = false,
    val procMemAvailableKb: Long = 0,
    val procMemFreeKb: Long = 0,
    val cachedKb: Long = 0,
    val reclaimableKb: Long = 0,
    val modelMemoryBudgetKb: Long = 0,
    val nThreads: Int = 0,
    val nThreadsBatch: Int = 0,
    val nBatch: Int = 0,
    val nUbatch: Int = 0,
    val nCtx: Int = 0,
    val maxAllTokens: Int = 0,
    val maxNewTokens: Int = 0,
    val backendDevices: String = "[]",
    /** Native llama.cpp reported a usable non-CPU backend for this loaded runtime. */
    val gpuOffloadSupported: Boolean? = null,
    /** True only after native allocation and successful decode evidence agree. */
    val gpuOffloadActive: Boolean = false,
    /** Native read-back saw non-CPU model and context/compute allocations. */
    val gpuOffloadAllocationObserved: Boolean = false,
    /** At least one llama_decode completed after the allocation evidence. */
    val gpuOffloadExecutionObserved: Boolean = false,
    val gpuOffloadBytes: Long = 0,
    /** -1 means the backend did not expose an exact actual layer count. */
    val gpuOffloadLayers: Int = 0,
    val gpuOffloadLayersKnown: Boolean = false,
    /** `n_gpu_layers=auto` retried the current load using CPU. */
    val gpuAutoFallbackApplied: Boolean = false,
    val gpuAutoFallbackReason: String? = null,
    /**
     * Backend selected by the request/profile.  This is kept separate from
     * [backend] (the runtime family id) and [actualBackend] because a model
     * may be loaded with a requested delegate that later falls back.
     */
    val requestedBackend: String? = null,
    /**
     * Native read-back of the backend that executed the current request.
     * `null`/`unknown` means the runtime did not expose an execution read-back;
     * it must never be inferred from a configured backend string.
     */
    val actualBackend: String? = null,
    /** True only when the native runtime supplied backend execution evidence. */
    val backendExecutionObserved: Boolean = false,
    /** Optional source/reason for [backendExecutionObserved] or its absence. */
    val backendExecutionEvidence: String? = null,
    /** Explicit NPU execution proof; configuration or delegate initialization is insufficient. */
    val npuExecutionObserved: Boolean = false,
    /** Native capability probe for an NPU path, when the runtime exposes one. */
    val npuSupported: Boolean? = null,
    /** In-memory longest-common-prefix KV reuse for the current request. */
    val cacheReuseHit: Boolean = false,
    val cacheReusedTokens: Int = 0,
    val cacheReuseReason: String? = null,
    val cacheReuseHits: Long = 0,
    val cacheReuseMisses: Long = 0,
    /** Adapter submission evidence; native acknowledgement remains explicitly unknown when unavailable. */
    val generationParameterApplicationJson: String = "{}",
    /** Disk-backed fixed-system-prefix cache remains separately attributable. */
    val persistentPrefixCacheHit: Boolean = false,
    val persistentPrefixCacheTokens: Int = 0,
    val persistentPrefixCacheReason: String? = null,
    val lastError: String? = null,
    /** True when no tokenizer/provider usage was available for this count. */
    val promptTokensEstimated: Boolean = false,
    val completionTokensEstimated: Boolean = false
) {
    /** A single predicate shared by UI and API projections of runtime stats. */
    val hasVerifiedGpuExecution: Boolean
        get() = gpuOffloadActive &&
            gpuOffloadAllocationObserved &&
            gpuOffloadExecutionObserved
}

/** Execution boundaries reported by [GenerateEvent.Phase]. */
enum class GenerationPhase {
    LOAD,
    /** Local image files are staged/decoded before native vision prefill. */
    PREPROCESS,
    TOKENIZE,
    PREFILL,
    DECODE,
    PERSIST
}

/** Lifecycle of KV cache serialization/write reported by the native runtime. */
enum class PersistStage {
    IDLE,
    ENCODING,
    WRITING,
    DONE
}

/**
 * Byte-level serialization progress for the KV state file written while
 * beginCompletion runs. A missing [PersistProgress] means no write is in flight.
 */
data class PersistProgress(
    val stage: PersistStage,
    val writtenBytes: Long,
    val totalBytes: Long
) {
    val isActive: Boolean
        get() = stage == PersistStage.ENCODING || stage == PersistStage.WRITING
}

/**
 * Exact token progress reported by a native runtime.
 *
 * A missing [TokenProgress] means the phase is indeterminate. Callers must not
 * derive a percentage from estimates or configured token limits.
 */
data class TokenProgress(
    val completedTokens: Int,
    val totalTokens: Int
) {
    init {
        require(completedTokens >= 0) { "completedTokens must not be negative" }
        require(totalTokens > 0) { "totalTokens must be positive" }
        require(completedTokens <= totalTokens) { "completedTokens must not exceed totalTokens" }
    }
}

/** Original message position and deterministic retention decision for one prompt. */
data class PromptMessageRetention(
    val originalIndex: Int,
    val role: Role,
    val retained: Boolean
) {
    init {
        require(originalIndex >= 0) { "originalIndex must not be negative" }
    }
}

/** Deterministic prompt accounting exposed to the chat UI and diagnostics. */
data class PromptContextUsage(
    val retainedMessageCount: Int,
    val trimmedMessageCount: Int,
    val roleTokens: Int,
    val worldBookTokens: Int,
    val knowledgeTokens: Int,
    val totalEstimatedTokens: Long,
    val messageRetention: List<PromptMessageRetention> = emptyList(),
    val selectedWorldBookEntryIds: List<String> = emptyList(),
    val skippedWorldBookEntryIds: List<String> = emptyList(),
    val selectedKnowledgeChunkIds: List<String> = emptyList(),
    val skippedKnowledgeChunkIds: List<String> = emptyList()
) {
    init {
        require(retainedMessageCount >= 0) { "retainedMessageCount must not be negative" }
        require(trimmedMessageCount >= 0) { "trimmedMessageCount must not be negative" }
        require(roleTokens >= 0) { "roleTokens must not be negative" }
        require(worldBookTokens >= 0) { "worldBookTokens must not be negative" }
        require(knowledgeTokens >= 0) { "knowledgeTokens must not be negative" }
        require(totalEstimatedTokens >= 0L) { "totalEstimatedTokens must not be negative" }
        require(messageRetention.map { it.originalIndex }.distinct().size == messageRetention.size) {
            "messageRetention must contain each original index at most once"
        }
        if (messageRetention.isNotEmpty()) {
            require(retainedMessageCount == messageRetention.count { it.retained }) {
                "retainedMessageCount must match messageRetention"
            }
            require(trimmedMessageCount == messageRetention.count { !it.retained }) {
                "trimmedMessageCount must match messageRetention"
            }
        }
    }
}

sealed interface GenerateEvent {
    /**
     * A non-token lifecycle transition. In particular, prefill stays
     * indeterminate until native code supplies an exact token total.
     */
    data class Phase(
        val phase: GenerationPhase,
        val stats: RuntimeStats,
        val tokenProgress: TokenProgress? = null
    ) : GenerateEvent

    data class Chunk(
        val text: String,
        val stats: RuntimeStats,
        val reasoning: String = "",
        val reasoningDurationMs: Long = 0L,
        val hiddenReasoning: Boolean = false
    ) : GenerateEvent

    /** Byte-level KV serialization progress while native persists a state file. */
    data class Persist(val progress: PersistProgress) : GenerateEvent
    data class Done(
        val stats: RuntimeStats,
        val toolCalls: List<ChatToolCall> = emptyList()
    ) : GenerateEvent
    data class Error(
        val message: String,
        val stats: RuntimeStats,
        val code: String? = null,
        val changedFields: Set<String> = emptySet(),
        val action: String? = null
    ) : GenerateEvent
}
