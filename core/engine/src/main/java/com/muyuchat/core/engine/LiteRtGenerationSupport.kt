package com.muyuchat.core.engine

import com.google.ai.edge.litertlm.Backend as LiteRtBackend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message as LiteRtMessage
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.SamplerConfig as LiteRtSamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import org.json.JSONObject
import com.muyuchat.core.engine.LiteRtLmChatRunner.Companion.CANCEL_JOIN_TIMEOUT_MS
import com.muyuchat.core.engine.LiteRtLmChatRunner.LiteRtLoadConfig


internal fun liteRtEngineConfig(
    modelPath: String,
    backend: LiteRtBackend,
    maxNumTokens: Int,
    cacheDir: String?,
    hasVisionComponents: Boolean
): EngineConfig = EngineConfig(
    modelPath = modelPath,
    backend = backend,
    // LiteRT-LM 0.16.1 creates no vision executor when this is null, even if
    // the package contains vision sections and an ImageFile is sent later.
    visionBackend = backend.takeIf { hasVisionComponents },
    maxNumTokens = maxNumTokens,
    cacheDir = cacheDir
)

/** Constructor selections are submission evidence; null delegates stay SDK/model defaults. */
internal fun liteRtComponentBackends(engineConfig: EngineConfig?): JSONObject {
    fun component(backend: LiteRtBackend?): JSONObject {
        val submitted = backend?.name?.lowercase()
        return JSONObject()
            .put("requested", submitted ?: JSONObject.NULL)
            .put("selected", submitted ?: JSONObject.NULL)
            .put("selectionSource", when {
                engineConfig == null -> "not_submitted"
                backend == null -> "sdk_model_default"
                else -> "engine_config_submission"
            })
            .put("actual", "unknown")
    }
    return JSONObject()
        .put("llm", component(engineConfig?.backend))
        .put("vision", component(engineConfig?.visionBackend))
        .put("audio", component(engineConfig?.audioBackend))
}

/**
 * Partial output does not prove a successful turn: sendMessageAsync/await can
 * throw without invoking onError. Only a successful, uncancelled turn with
 * visible content may commit history for KV reuse.
 */
internal fun liteRtConversationReusableAfterTurn(
    generationResult: Result<*>,
    stopRequested: Boolean,
    output: CharSequence
): Boolean = generationResult.isSuccess && !stopRequested && output.isNotBlank()

internal fun liteRtConversationScopeMatches(
    storedOwnerId: String?,
    storedContextRevision: String?,
    requestedOwnerId: String?,
    requestedContextRevision: String?
): Boolean = !requestedOwnerId.isNullOrBlank() && storedOwnerId == requestedOwnerId &&
    storedContextRevision == requestedContextRevision

/**
 * Returns the role spelling used by LiteRT-LM for a Local API chat role.
 * `assistant` and `model` represent the same turn to LiteRT-LM and therefore
 * must share one spelling in both the Conversation seed and cache comparison.
 */
internal fun canonicalLiteRtMessageRole(raw: String): String? = when (raw.trim().lowercase()) {
    "system" -> "system"
    "user" -> "user"
    "assistant", "model" -> "model"
    "tool" -> "tool"
    else -> null
}

/**
 * Converts the shared generation settings to LiteRT-LM's immutable sampler
 * configuration. Qualcomm/Google Tensor backends intentionally use the
 * engine's sampler defaults because those delegates do not expose the
 * conversation sampler controls in the current LiteRT-LM release.
 */
internal data class LiteRtSamplerValues(
    val topK: Int,
    val topP: Double,
    val temperature: Double,
    val seed: Int
) {
    fun toSdkConfig(): LiteRtSamplerConfig = LiteRtSamplerConfig(
        topK = topK,
        topP = topP,
        temperature = temperature,
        seed = seed
    )
}

internal fun liteRtSamplerValuesFor(
    params: GenerationParams,
    backend: String
): LiteRtSamplerValues? {
    params.requireFiniteSamplerValues()
    val normalizedBackend = backend.trim().lowercase().replace('-', '_')
    if (normalizedBackend == "npu" || normalizedBackend == "google_tensor") return null

    return LiteRtSamplerValues(
        topK = params.topK.coerceAtLeast(1),
        topP = params.topP.toDouble().coerceIn(0.0, 1.0),
        temperature = params.temperature.toDouble().coerceAtLeast(0.0),
        seed = params.seed ?: 0
    )
}

internal data class LiteRtGenerationConfiguration(
    val sampler: LiteRtSamplerValues?,
    val repetitionPenalty: RepetitionPenaltyConfig,
    val thinking: ThinkingConfig,
    val maxOutputTokens: Int
)

internal fun liteRtGenerationConfiguration(
    params: GenerationParams,
    backend: String
): LiteRtGenerationConfiguration {
    params.requireFiniteSamplerValues()
    val enableThinking = params.reasoningMode != ReasoningMode.OFF && !params.hideReasoning
    return LiteRtGenerationConfiguration(
        sampler = liteRtSamplerValuesFor(params, backend),
        repetitionPenalty = RepetitionPenaltyConfig(
            repetitionPenalty = params.repeatPenalty.coerceAtLeast(1.0f),
            presencePenalty = params.presencePenalty,
            frequencyPenalty = params.frequencyPenalty
        ),
        thinking = ThinkingConfig(
            enableThinking = enableThinking,
            thinkingTokenBudget = if (enableThinking) params.effectiveThinkingBudget() else 0
        ),
        maxOutputTokens = params.effectiveNPredict()
    )
}

internal fun liteRtGenerationParameterApplication(
    requestedJson: String,
    backend: String,
    config: LiteRtGenerationConfiguration
): JSONObject {
    val ignored = mutableMapOf("min_p" to "litertlm-0.16.1_has_no_min_p_configuration")
    val submitted = JSONObject()
        .put("n_predict", config.maxOutputTokens)
        .put("repeat_penalty", config.repetitionPenalty.repetitionPenalty)
        .put("presence_penalty", config.repetitionPenalty.presencePenalty)
        .put("frequency_penalty", config.repetitionPenalty.frequencyPenalty)
        .put("enable_thinking", config.thinking.enableThinking)
        .put("thinking_budget", config.thinking.thinkingTokenBudget)
    config.sampler?.let { sampler ->
        submitted.put("temperature", sampler.temperature)
            .put("top_k", sampler.topK)
            .put("top_p", sampler.topP)
            .put("seed", sampler.seed)
    } ?: listOf("temperature", "top_k", "top_p", "seed").forEach { field ->
        ignored[field] = "litertlm-0.16.1_${backend}_delegate_uses_engine_sampler_defaults"
    }
    return generationParameterApplication(
        runtime = LocalChatRuntime.LITERT_LM,
        backend = backend,
        requestedJson = requestedJson,
        submitted = submitted,
        ignored = ignored,
        source = "litertlm-0.16.1:ConversationConfig+sendMessageAsync",
        normalizedReasons = mapOf(
            "top_k" to "litertlm_requires_positive_top_k",
            "top_p" to "litertlm_top_p_range_0_to_1",
            "temperature" to "litertlm_requires_nonnegative_temperature",
            "repeat_penalty" to "litertlm_requires_repetition_penalty_at_least_1",
            "seed" to "null_seed_uses_litertlm_default_zero",
            "thinking_budget" to "reasoning_mode_resolves_thinking_budget"
        )
    )
}
internal fun LiteRtLmChatRunner.unloadModelImpl(): Unit = synchronized(lifecycleLock) {
        if (!stopAndJoinGenerationLocked()) return@synchronized
        if (!cancellationGate.whenIdle {
            val oldConversation = conversation
            val oldEngine = engine
            conversation = null
            engine = null
            submittedEngineConfig = null
            loaded = false
            visionModelKnownTextOnly = false
            visionModelVisualComponentsPresent = false
            visionBindingState = VisionBindingState.UNBOUND
            conversationHistory = emptyList()
            conversationOwnerId = null
            conversationSamplerValues = null
            conversationReusable = false
            completedMetrics = null
            queue.cancel()
            val conversationClosed = runCatching { oldConversation?.close() }.isSuccess
            val engineClosed = conversationClosed && runCatching { oldEngine?.close() }.isSuccess
            if (!engineClosed) {
                cancellationRecoveryRequired = true
                lastError = "LiteRT-LM native cleanup failed; reload the isolated worker."
            }
        }) cancellationRecoveryRequired = true
    }

internal fun LiteRtLmChatRunner.invalidateConversationContextImpl() {
        synchronized(lifecycleLock) {
            if (!stopAndJoinGenerationLocked()) {
                throw IllegalStateException(lastError.ifBlank { "LiteRT-LM generation could not stop for context invalidation." })
            }
            conversation?.let {
                if (!closeConversationSafely(it)) {
                    throw IllegalStateException(lastError.ifBlank { "LiteRT-LM conversation could not close for context invalidation." })
                }
            }
            conversationHistory = emptyList()
            conversationOwnerId = null
            conversationSamplerValues = null
            conversationReusable = false
            completedMetrics = null
        }
    }

internal fun LiteRtLmChatRunner.liteRtStatsJson(): String {
        val benchmark = completedMetrics
        val conversationTokenCount = benchmark?.tokenCount ?: 0
        val now = wallClockMs()
        val timing = generationTiming.snapshot()
        val decodeMs = timing.decodeMs
        val ttftMs = timing.ttftMs
        val effectiveDecodeTps = benchmark?.decodeTps ?:
            timing.decodeTokensPerSecond(completionTokens)
        val effectivePrefillTps = benchmark?.prefillTps ?: 0.0
        return JSONObject()
            .put("backend", runtime.backendId)
            // The load configuration selects a delegate, but the current
            // LiteRT-LM SDK does not expose a verified per-inference backend
            // readback.  Never present the requested delegate as execution
            // fact; callers must treat actualBackend=unknown until native
            // telemetry is available.
            .put("requestedBackend", activeConfig.backend)
            .put("selectedBackend", activeConfig.backend)
            .put("actualBackend", "unknown")
            .put("componentBackends", liteRtComponentBackends(submittedEngineConfig))
            .put("executionEvidence", JSONObject()
                .put("available", false)
                .put("source", "sdk_backend_telemetry_unavailable")
                .put("delegateInitialized", loaded)
                .put("decodeObserved", benchmark != null && (benchmark.decodeTokens > 0 || completionTokens > 0)))
            .put("fallbackReason", if (activeConfig.backend == "cpu") {
                "none"
            } else {
                "backend_execution_telemetry_unavailable"
            })
            .put("backendMode", activeConfig.backend)
            .put("backendDevices", "LiteRT-LM ${activeConfig.backend.uppercase()}")
            .put("loaded", loaded)
            .put("runnerReady", isAvailable)
            // ImageFile transport only proves that MCA can pass image content.
            // A model is marked vision-ready only after a real image turn succeeds.
            .put("visionReady", liteRtVisionReady(loaded, successfulImageTurn))
            .put("supportsVision", liteRtVisionReady(loaded, successfulImageTurn))
            .put("visionVerified", successfulImageTurn)
            .put("visionBindingState", visionBindingState.wireName)
            .put("visionModelKnownTextOnly", visionModelKnownTextOnly)
            .put("visionModelVisualComponentsPresent", visionModelVisualComponentsPresent)
            .put("visionInputTransportReady", loaded && classesPresent)
            .put("capabilities", JSONObject().put("supportsVision", liteRtVisionReady(loaded, successfulImageTurn)))
            .put("modelPath", modelPath)
            .put("loadMs", loadMs)
            .put("nCtx", activeConfig.maxNumTokens)
            .put("maxAllTokens", activeConfig.maxNumTokens)
            .put("maxNewTokens", 0)
            .put("promptTokens", benchmark?.let { (it.tokenCount - it.decodeTokens).coerceAtLeast(it.prefillTokens) } ?: promptTokens)
            .put("promptTokensEstimated", benchmark == null)
            .put("prefillTokens", benchmark?.prefillTokens ?: 0)
            .put("completionTokens", benchmark?.decodeTokens ?: completionTokens)
            .put("completionTokensEstimated", benchmark == null)
            .put("conversationTokenCount", conversationTokenCount)
            .put("kvCacheTokens", conversationTokenCount)
            .put("ttftMs", benchmark?.ttftMs ?: ttftMs)
            .put("prefillTps", effectivePrefillTps)
            .put("decodeTps", effectiveDecodeTps)
            .put("prefillMs", if (effectivePrefillTps > 0.0 && benchmark != null && benchmark.prefillTokens > 0) {
                benchmark.prefillTokens * 1000.0 / effectivePrefillTps
            } else 0.0)
            .put("decodeMs", decodeMs)
            .put("e2eTps", timing.endToEndTokensPerSecond(completionTokens, requireCompleted = true))
            .put("effectiveConfig", JSONObject()
                .put("backend", activeConfig.backend)
                .put("max_num_tokens", activeConfig.maxNumTokens)
                .put("n_threads", activeConfig.nThreads)
                .apply { activeConfig.cacheDir?.let { put("cache_dir", it) } })
            .put("parameterApplication", JSONObject(parameterApplicationJson))
            .put("benchmarkEnabled", benchmark != null)
            .put("runtimeStatsDeferred", generationRunning)
            .put("runtimeStatsSource", if (benchmark == null) "java_estimate" else "completed_native_snapshot")
            .put("statsAt", now)
            .put("lastError", lastError)
            .toString()
    }

internal fun LiteRtLmChatRunner.closeConversationSafely(current: Conversation): Boolean {
        val closed = runCatching {
            cancellationGate.await(CANCEL_JOIN_TIMEOUT_MS) && cancellationGate.whenIdle {
                if (conversation === current) conversation = null
                current.close()
            }
        }.getOrDefault(false)
        if (!closed) {
            cancellationRecoveryRequired = true
            lastError = "LiteRT-LM conversation is still owned by native cancellation; reload the worker."
        }
        return closed
    }

internal fun LiteRtLmChatRunner.parseLoadConfig(paramsJson: String): LiteRtLoadConfig {
        val root = JSONObject(paramsJson.ifBlank { "{}" })
        val advanced = root.opt("advanced_json")
            ?.let { raw ->
                when (raw) {
                    is JSONObject -> raw
                    is String -> runCatching { JSONObject(raw) }.getOrNull()
                    else -> null
                }
            }
        fun value(vararg names: String): Any? = names.asSequence().mapNotNull { name ->
            root.opt(name).takeIf { it != JSONObject.NULL }
                ?: advanced?.opt(name)?.takeIf { it != JSONObject.NULL }
        }.firstOrNull()
        val backend = canonicalBackend(value("backend", "backend_type", "backendType")?.toString()) ?: "cpu"
        val maxTokens = integerValue(value("max_num_tokens", "maxNumTokens", "n_ctx"))
            ?: 4096
        val threads = integerValue(value("n_threads", "thread_count", "threadCount"))?.coerceAtLeast(1) ?: 4
        val cache = value("cache_dir", "cacheDir", "cache_directory")?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
        return LiteRtLoadConfig(
            backend = backend,
            maxNumTokens = maxTokens.coerceAtLeast(1),
            cacheDir = cache,
            nThreads = threads
        )
    }

internal fun LiteRtLmChatRunner.backendFor(config: LiteRtLoadConfig): LiteRtBackend = when (config.backend) {
        "gpu" -> LiteRtBackend.GPU()
        "npu" -> LiteRtBackend.NPU(nativeLibDir)
        "google_tensor" -> LiteRtBackend.GOOGLE_TENSOR()
        else -> LiteRtBackend.CPU(threadCount = config.nThreads)
    }

internal fun LiteRtLmChatRunner.toLiteRtMessage(message: LiteRtMessageSpec): LiteRtMessage {
        val contents = message.toLiteRtContents()
        return when (message.role) {
            "system" -> LiteRtMessage.system(contents)
            "assistant", "model" -> LiteRtMessage.model(contents)
            "tool" -> LiteRtMessage.tool(contents)
            else -> LiteRtMessage.user(contents)
        }
    }

internal fun LiteRtLmChatRunner.canonicalBackend(raw: String?): String? = when (
        raw?.trim()?.lowercase()?.replace('-', '_')
    ) {
        "cpu", "host" -> "cpu"
        "gpu", "opencl", "open_cl" -> "gpu"
        "npu", "qnn", "qualcomm" -> "npu"
        "google_tensor", "googletensor", "tpu", "google_tensor_processor" -> "google_tensor"
        else -> null
    }

internal fun LiteRtLmChatRunner.estimateTokens(text: String): Int =
        (text.length / 3).coerceAtLeast(if (text.isBlank()) 0 else 1)

internal fun LiteRtLmChatRunner.describe(error: Throwable): String {
        val root = error.cause ?: error
        return root.message?.takeIf { it.isNotBlank() }
            ?: root::class.java.simpleName
    }
