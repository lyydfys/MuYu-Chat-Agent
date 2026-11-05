package com.muyuchat.core.engine

import org.json.JSONArray
import org.json.JSONObject

enum class LocalBackendFallbackPolicy(val wireName: String) {
    ALLOW_CPU("allow_cpu"),
    REQUIRE_REQUESTED_BACKEND("require_requested_backend")
}

data class LoadParams(
    val nCtx: Int = 8192,
    val nThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2) - 1,
    val mmap: Boolean = true,
    val mlock: Boolean = false,
    val visionProjectorPath: String? = null,
    val geniexComputeUnit: String? = null,
    val advancedJson: String = "{}",
    val fallbackPolicy: LocalBackendFallbackPolicy? = null
) {
    companion object {
        fun fromJson(json: String, defaults: LoadParams = LoadParams()): LoadParams {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return defaults
            return LoadParams(
                nCtx = root.optInt("n_ctx", defaults.nCtx),
                nThreads = root.optInt("n_threads", defaults.nThreads),
                mmap = root.optBoolean("mmap", defaults.mmap),
                mlock = root.optBoolean("mlock", defaults.mlock),
                fallbackPolicy = root.optString("fallback_policy").takeIf { it.isNotBlank() }?.let { value ->
                    requireNotNull(LocalBackendFallbackPolicy.entries.firstOrNull { it.wireName == value }) {
                        "Unsupported fallback_policy: $value"
                    }
                } ?: defaults.fallbackPolicy,
                visionProjectorPath = root.optString("mmproj_path", defaults.visionProjectorPath.orEmpty())
                    .takeIf { it.isNotBlank() },
                geniexComputeUnit = root.optString(
                    "geniex_compute_unit",
                    root.optString("compute_unit", defaults.geniexComputeUnit.orEmpty())
                ).trim().takeIf { it.isNotBlank() },
                advancedJson = LlamaAdvancedParams.collectFromRoot(root, defaults.advancedJson)
            )
        }
    }

    fun advancedValidationErrors(): List<String> =
        LlamaAdvancedParams.parse(advancedJson).errorMessages

    fun toJson(): String {
        val advanced = LlamaAdvancedParams.parse(advancedJson)
        return JSONObject()
            .put("n_ctx", nCtx)
            .put("n_threads", nThreads)
            .put("mmap", mmap)
            .put("mlock", mlock)
            .put("fallback_policy", fallbackPolicy?.wireName)
            .apply {
                // Valid advanced canonical values override the LoadParams defaults.
                advanced.putCanonicalFields(this)
                put("advanced_json", advanced.advancedJsonValue())
            if (!visionProjectorPath.isNullOrBlank()) {
                put("mmproj_path", visionProjectorPath)
            }
            if (!geniexComputeUnit.isNullOrBlank()) {
                put("geniex_compute_unit", geniexComputeUnit)
            }
        }
        .toString()
    }
}

/** One third of the window, floored at 512 and capped at the profile ceiling. */
fun defaultOutputBudget(nCtx: Int, ceiling: Int = MAX_OUTPUT_TOKENS): Int {
    val window = if (nCtx > 0) nCtx else 8192
    return (window / 2).coerceIn(MIN_OUTPUT_TOKENS, ceiling)
}

const val MIN_OUTPUT_TOKENS: Int = 512
const val MAX_OUTPUT_TOKENS: Int = 65_536

data class GenerationParams(
    val nCtx: Int = 8192,
    /**
     * Output budget when nobody specified one.
     *
     * It follows the context window instead of a fixed 8192: a long-context model should not be
     * limited to a short answer, and a small window must never ask for more output than it can
     * hold. An explicit `n_predict`/`max_tokens` always wins (see [fromJson]).
     */
    val nPredict: Int = defaultOutputBudget(nCtx),
    val nThreads: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(2) - 1,
    val temperature: Float = 0.6f,
    val topK: Int = 20,
    val topP: Float = 0.95f,
    val minP: Float = 0.0f,
    val repeatPenalty: Float = 1.08f,
    val presencePenalty: Float = 0.0f,
    val frequencyPenalty: Float = 0.2f,
    val seed: Int? = null,
    val systemPrompt: String = "你是霂榆Chat Agent（MCA），一名运行在本机的离线助手。默认使用中文回答，回答要清晰、直接、自然。用户要求代码时，必须使用 Markdown 代码块并标注语言，代码块内保留正确换行和缩进。",
    val stopWords: List<String> = emptyList(),
    val chatTemplateMode: String = "auto",
    val advancedJson: String = "{}",
    val reasoningMode: ReasoningMode = ReasoningMode.OFF,
    val hideReasoning: Boolean = false
) {
    companion object {
        fun fromJson(json: String, defaults: GenerationParams = GenerationParams()): GenerationParams {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return defaults
            return fromJson(root, defaults)
        }

        fun fromJson(root: JSONObject, defaults: GenerationParams = GenerationParams()): GenerationParams =
            GenerationParams(
                // LiteRT-LM profiles call the context-length constructor field
                // `max_num_tokens`; it is the same value MCA exposes as
                // `n_ctx`.  Keep this alias in the generation parser because
                // profile merging passes the canonical load-bound map here.
                nCtx = root.optInt(
                    "n_ctx",
                    root.optInt("max_num_tokens", root.optInt("maxNumTokens", defaults.nCtx))
                ),
                // The output budget always follows the local model window. A preset's
                // `n_predict`/`max_tokens` is deliberately ignored: it used to pin every model to
                // whatever the preset author wrote (or 8192), which is exactly the one-size-fits-all
                // budget this project decided against.
                nPredict = defaultOutputBudget(
                    root.optInt(
                        "n_ctx",
                        root.optInt("max_num_tokens", root.optInt("maxNumTokens", defaults.nCtx))
                    )
                ),
                nThreads = root.optInt("n_threads", defaults.nThreads),
                temperature = root.optDouble("temperature", defaults.temperature.toDouble()).toFloat(),
                topK = root.optInt("top_k", defaults.topK),
                topP = root.optDouble("top_p", defaults.topP.toDouble()).toFloat(),
                minP = root.optDouble("min_p", defaults.minP.toDouble()).toFloat(),
                repeatPenalty = root.optDouble(
                    "repeat_penalty",
                    root.optDouble("repetition_penalty", defaults.repeatPenalty.toDouble())
                ).toFloat(),
                presencePenalty = root.optDouble("presence_penalty", defaults.presencePenalty.toDouble()).toFloat(),
                frequencyPenalty = root.optDouble("frequency_penalty", defaults.frequencyPenalty.toDouble()).toFloat(),
                seed = if (root.has("seed") && !root.isNull("seed")) root.optInt("seed") else defaults.seed,
                systemPrompt = root.optString("system_prompt", defaults.systemPrompt),
                stopWords = root.optJSONArray("stop_words")?.toStringList()
                    ?: root.optJSONArray("stop")?.toStringList()
                    ?: defaults.stopWords,
                chatTemplateMode = root.optString("chat_template_mode", defaults.chatTemplateMode),
                advancedJson = LlamaAdvancedParams.collectFromRoot(root, defaults.advancedJson),
                reasoningMode = root.optReasoningMode(defaults.reasoningMode),
                hideReasoning = root.optBoolean("hide_reasoning", defaults.hideReasoning)
            )

        private fun JSONObject.optReasoningMode(default: ReasoningMode): ReasoningMode {
            val raw = optString("reasoning_mode", optString("thinking_mode", "")).trim()
            if (raw.isBlank()) {
                return if (has("enable_thinking")) {
                    if (optBoolean("enable_thinking", default != ReasoningMode.OFF)) ReasoningMode.STANDARD else ReasoningMode.OFF
                } else {
                    default
                }
            }
            return when (raw.lowercase()) {
                "off", "none", "disable", "disabled", "false", "关闭" -> ReasoningMode.OFF
                "advanced", "deep", "high", "进阶", "深度" -> ReasoningMode.ADVANCED
                "standard", "normal", "default", "true", "标准" -> ReasoningMode.STANDARD
                else -> ReasoningMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: default
            }
        }

    }

    fun effectiveNPredict(): Int {
        val reasoningFloor = when (reasoningMode) {
            ReasoningMode.OFF -> nPredict
            ReasoningMode.STANDARD -> 2048
            ReasoningMode.ADVANCED -> 8192
        }
        return nPredict
            .coerceAtLeast(reasoningFloor)
            .coerceAtLeast(1)
    }

    fun effectiveThinkingBudget(): Int = when (reasoningMode) {
        ReasoningMode.OFF -> 0
        ReasoningMode.STANDARD -> 192
        ReasoningMode.ADVANCED -> 1536
    }

    fun advancedValidationErrors(): List<String> =
        LlamaAdvancedParams.parse(advancedJson).errorMessages

    fun toJson(): String {
        val advanced = LlamaAdvancedParams.parse(advancedJson)
        return JSONObject()
            .put("n_ctx", nCtx)
            .put("n_predict", effectiveNPredict())
            .put("n_threads", nThreads)
            .put("temperature", temperature)
            .put("top_k", topK)
            .put("top_p", topP)
            .put("min_p", minP)
            .put("repeat_penalty", repeatPenalty)
            .put("repetition_penalty", repeatPenalty)
            .put("presence_penalty", presencePenalty)
            .put("frequency_penalty", frequencyPenalty)
            .put("seed", seed)
            .put("system_prompt", systemPrompt)
            .put("stop_words", JSONArray(stopWords))
            .put("chat_template_mode", chatTemplateMode)
            .put("reasoning_mode", reasoningMode.name.lowercase())
            .put("enable_thinking", reasoningMode != ReasoningMode.OFF && !hideReasoning)
            .put("thinking_budget", effectiveThinkingBudget())
            .put("hide_reasoning", hideReasoning || reasoningMode == ReasoningMode.OFF)
            .apply {
                advanced.putCanonicalFields(this)
                put("advanced_json", advanced.advancedJsonValue())
            }
            .toString()
    }
}
