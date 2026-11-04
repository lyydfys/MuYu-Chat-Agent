package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.feature.chat.tavern.TavernRegexScript
import com.muyuchat.feature.chat.tavern.TavernRegexScriptParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * SillyTavern (Tavern) chat-completion preset adapter.
 *
 * Why a separate adapter instead of the older [com.muyuchat.feature.chat.SillyTavernPresetCodec]:
 * that codec was written for "merge prompt text into an assistant card" and it *throws* on the
 * first surprising value (`require(temperature in 0..2)`, `require(top_k integer ≥ 0)`, …). A real
 * Tavern preset regularly carries `"min_p": null`, `top_k: -1`, `max_tokens: 0` or a value above
 * the MCA range, so one field aborted the whole import — which is exactly the "all presets fail"
 * report. This adapter never throws for a value: it clamps, records the clamp, and continues.
 *
 * Scope (and the honest limits) of the mapping:
 *  - sampler/output fields that a MCA preset can actually store and apply are mapped;
 *  - prompt/template/behaviour fields are listed as **unsupported** with a reason instead of being
 *    silently dropped (MCA presets only hold sampling + output parameters; prompts live on the
 *    assistant card);
 *  - unknown fields are **ignored but recorded**;
 *  - `context_length` / `openai_max_context` are load-bound: the adapter picks a reasonable n_ctx
 *    suggestion for the report, but a preset can never change it (no storage slot, needs a reload).
 *
 * Everything here is pure JVM code (org.json + java.io), so the whole matrix is unit-testable
 * without a device.
 */
internal enum class GenerationPresetDialect { MCA, SILLY_TAVERN, UNKNOWN }

/** One value that was outside the MCA range and had to be clamped; surfaced as a warning. */
internal data class TavernPresetClamp(
    val key: String,
    val requested: String,
    val applied: String,
    val rule: String
) {
    val warning: String get() = "$key=$requested 超出范围（$rule），已按 $applied 使用"
}

/** One known Tavern field that MCA has no entry for; kept explicit instead of dropped silently. */
internal data class TavernPresetUnsupported(val key: String, val reason: String)

/** Result of mapping one Tavern preset onto MCA generation parameters. */
internal data class TavernPresetMapping(
    /** `preset_name`/`name` from the file, for the report only; the record name is the file name. */
    val declaredName: String?,
    val params: GenerationParams,
    /** Tavern keys that produced a value on [params]. */
    val mappedKeys: List<String>,
    val clamped: List<TavernPresetClamp>,
    val unsupported: List<TavernPresetUnsupported>,
    val ignoredKeys: List<String>,
    /**
     * The preset's own prompt bundle, flattened in `prompt_order` order.
     *
     * Tavern presets are mostly *prompts*: the case that made users say "it works in SillyTavern but
     * not here" carries 180-400 enabled system prompts and no sampler difference at all. MCA presets
     * hold sampling values only, so the bundle is kept beside them as text the user can apply to an
     * assistant instead of being thrown away.
     */
    val promptAppendix: String? = null,
    /** Length of [promptAppendix] before the storage cap, for the import report. */
    val promptChars: Int = 0,
    /** Normalized `extensions.regex_scripts`; stored with the preset as `tavern_regex_scripts`. */
    val regexScripts: List<TavernRegexScript> = emptyList(),
    /** True when the source preset carried the regex section, even if it was empty. */
    val hasRegexScriptsKey: Boolean = false,
    /** Parser warnings for malformed regex entries; does not reject the preset. */
    val regexWarnings: List<String> = emptyList()
) {
    /** True when the file carries sampler/output values a preset can store. */
    val hasApplicableValue: Boolean get() = mappedKeys.isNotEmpty()
}

/** Result of parsing a file that was already classified as an MCA preset. */
internal data class McaPresetMapping(
    val params: GenerationParams,
    val mappedKeys: List<String>,
    val unsupported: List<TavernPresetUnsupported>,
    val ignoredKeys: List<String>
) {
    val hasApplicableValue: Boolean get() = mappedKeys.isNotEmpty()
}

/** One parsed preset file: the dialect plus what was applied/ignored/refused. */
internal sealed interface GenerationPresetParseResult {
    data class Tavern(val mapping: TavernPresetMapping) : GenerationPresetParseResult
    data class Mca(val mapping: McaPresetMapping) : GenerationPresetParseResult

    /** The file is not a preset, is broken, or has nothing a MCA preset can store. */
    data class Rejected(val reason: String) : GenerationPresetParseResult
}

/**
 * Detection, mapping and clamping for Tavern presets.
 *
 * Detection order matters: MCA's own preset JSON also contains `temperature`/`top_p`/`top_k`, so an
 * MCA marker wins before the Tavern signature is considered. A bare `{"temperature": 1}` is NOT
 * classified as Tavern (it is ambiguous and importing it as a preset would be a guess).
 */
internal object SillyTavernPresetAdapter {

    /** Same ceiling as the assistant-card reader this replaces; a preset is never 8 MiB. */
    const val MAX_JSON_BYTES: Int = 8 * 1024 * 1024

    /** Bounds mirror the MCA generation dialog so an imported value is always a value MCA accepts. */
    const val MAX_TOP_K: Int = 200
    const val MIN_PREDICT: Int = 16
    const val MAX_PREDICT: Int = 65_536

    /**
     * Import ceiling for a Tavern preset's output length.
     *
     * The importer does not invent a ceiling: whatever the author wrote is stored as-is, and the
     * budget is applied per bound model instead (see [PresetOutputBudget]). A preset authored for a
     * cloud model legitimately asks for tens of thousands of output tokens, and silently rewriting
     * that number here would hide the author's intent. The bound model — not the importer — is the
     * only thing that knows what it can honour.
     */
    const val MAX_PREDICT_TAVERN: Int = Int.MAX_VALUE

    /** Storage cap for the flattened prompt bundle; a longer one is truncated with an ellipsis. */
    const val MAX_PROMPT_APPENDIX_CHARS: Int = 32_000
    const val MIN_CONTEXT: Int = 512
    const val MAX_CONTEXT: Int = 131_072
    const val MAX_STOP_WORDS: Int = 32
    const val MAX_STOP_WORD_CHARS: Int = 128

    /** Keys only MCA writes; their presence proves the file is an MCA preset, not a Tavern one. */
    private val MCA_MARKERS = listOf(
        "advanced_json", "chat_template_mode", "reasoning_mode", "n_predict",
        "stop_words", "n_ctx", "n_threads", "hide_reasoning", "enable_thinking"
    )

    /** A Tavern-only key: one is enough to classify the file. */
    private val TAVERN_STRONG_MARKERS = listOf(
        "openai_max_context", "openai_max_tokens", "context_length", "max_context_unused",
        "preset_name", "prompts", "prompt_order", "wrap_in_quotes", "names_behavior",
        "continue_prefill", "squash_system_messages", "stream_openai", "use_sysprompt",
        "assistant_prefill", "wi_format", "scenario_format", "personality_format",
        "impersonation_prompt", "new_chat_prompt", "new_group_chat_prompt",
        "new_example_chat_prompt", "instruct", "jailbreak_prompt"
    )

    /** Shared keys: two of them (or one strong marker) identify a Tavern preset. */
    private val TAVERN_WEAK_MARKERS = listOf(
        "temperature", "top_p", "top_k", "max_tokens", "min_p", "rep_pen",
        "repeat_penalty", "presence_penalty", "frequency_penalty", "stop"
    )

    /** Fields MCA can read from its own preset JSON (see [GenerationParams.fromJson]). */
    private val MCA_MAPPED_KEYS = listOf(
        "n_predict", "max_tokens", "temperature", "top_k", "top_p", "min_p",
        "repeat_penalty", "repetition_penalty", "presence_penalty", "frequency_penalty",
        "stop_words", "stop", "seed", "reasoning_mode", "thinking_mode", "enable_thinking",
        "hide_reasoning", "schema", "override_fields", "max_num_tokens", "maxNumTokens"
    )

    /**
     * Known Tavern fields with no MCA preset entry. The reason is user-facing and says *where* the
     * setting lives in MCA instead, so "unsupported" never reads like a silent loss.
     */
    private val UNSUPPORTED_FIELDS: Map<String, String> = linkedMapOf(
        "system_prompt" to "预设只存采样/输出参数；系统提示词由角色卡/助手提供",
        "instruct" to "指令模板没有预设槽位；请在角色卡中导入",
        "instruct_template" to "指令模板没有预设槽位；请在角色卡中导入",
        "context_template" to "上下文模板没有预设槽位；请在角色卡中导入",
        "jailbreak_prompt" to "越狱/系统提示没有预设槽位；请在角色卡中导入",
        "prompts" to "提示词条目没有预设槽位；请在角色卡中导入",
        "prompt_order" to "提示词顺序没有预设槽位；请在角色卡中导入",
        "wi_format" to "世界书格式没有预设槽位",
        "scenario_format" to "场景模板没有预设槽位；请在角色卡中导入",
        "personality_format" to "性格模板没有预设槽位；请在角色卡中导入",
        "impersonation_prompt" to "用户扮演提示没有预设槽位",
        "new_chat_prompt" to "新对话提示没有预设槽位",
        "new_group_chat_prompt" to "群聊开场提示没有预设槽位",
        "new_example_chat_prompt" to "示例对话提示没有预设槽位",
        "assistant_prefill" to "MCA 没有助手预填（prefill）入口",
        "wrap_in_quotes" to "MCA 没有引号包裹开关",
        "names_behavior" to "MCA 没有角色名行为开关",
        "continue_prefill" to "MCA 没有 prefill 续写开关",
        "squash_system_messages" to "MCA 没有系统消息合并开关",
        "stream_openai" to "流式由 MCA 后端决定，预设不切换传输方式",
        "use_sysprompt" to "系统提示词开关没有预设槽位",
        "openai_model" to "云端模型由 MCA 选择，预设不改模型",
        "chat_completion_source" to "云端提供方由 MCA 选择，预设不改提供方",
        "reverse_proxy" to "反向代理属于 MCA 云端配置，预设不覆盖",
        "proxy_password" to "凭据不进入预设",
        "proxy_preset" to "代理预设属于 MCA 云端配置，预设不覆盖",
        "stop_sequence" to "停止词请使用 stop/stop_sequences"
    )

    /** Load-bound fields: parsed for the report, never applied by a preset. */
    private val LOAD_BOUND_FIELDS = listOf("context_length", "openai_max_context", "n_ctx")

    fun detect(root: JSONObject): GenerationPresetDialect {
        if (MCA_MARKERS.any(root::has)) return GenerationPresetDialect.MCA
        if (root.optString("schema").startsWith("mca.")) return GenerationPresetDialect.MCA
        // Older Tavern exports nest the sampler under `settings`/`sampler`; look one level down so
        // the nested form is not misread as "unknown".
        val source = samplerSource(root)
        fun seen(key: String): Boolean = root.has(key) || source.has(key)
        val strong = TAVERN_STRONG_MARKERS.count(::seen)
        val weak = TAVERN_WEAK_MARKERS.count(::seen)
        return when {
            strong >= 1 || weak >= 2 -> GenerationPresetDialect.SILLY_TAVERN
            else -> GenerationPresetDialect.UNKNOWN
        }
    }

    /** Parses [rawJson]. Never throws for a value; only structural problems become [Rejected]. */
    fun adapt(rawJson: String, defaults: GenerationParams = GenerationParams()): GenerationPresetParseResult {
        if (rawJson.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) {
            return GenerationPresetParseResult.Rejected("预设文件超过 8 MiB。")
        }
        val root = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: return GenerationPresetParseResult.Rejected("不是有效的 JSON 对象。")
        return when (detect(root)) {
            GenerationPresetDialect.MCA -> adaptMca(root, defaults)
            GenerationPresetDialect.SILLY_TAVERN -> adaptTavern(root, defaults)
            GenerationPresetDialect.UNKNOWN -> GenerationPresetParseResult.Rejected(
                "既不是酒馆预设也不是 MCA 预设：缺少 temperature/top_p/max_tokens 等采样键。"
            )
        }
    }

    /** The sampler object: Tavern chat presets are flat, older exports nest one level. */
    private fun samplerSource(root: JSONObject): JSONObject =
        root.optJSONObject("settings") ?: root.optJSONObject("sampler") ?: root

    private fun adaptTavern(root: JSONObject, defaults: GenerationParams): GenerationPresetParseResult {
        val source = samplerSource(root)
        val mapped = linkedSetOf<String>()
        val clamped = mutableListOf<TavernPresetClamp>()
        var params = defaults
        var applicable = false

        fun number(vararg keys: String): Pair<String, Double>? {
            for (key in keys) {
                if (!root.has(key) && !source.has(key)) continue
                val holder = if (source.has(key)) source else root
                val raw = holder.opt(key)
                if (raw == null || raw == JSONObject.NULL) continue
                val parsed = when (raw) {
                    is Number -> raw.toDouble()
                    is String -> raw.trim().toDoubleOrNull()
                    else -> null
                } ?: continue
                if (!parsed.isFinite()) continue
                return key to parsed
            }
            return null
        }

        number("temperature", "temp")?.let { (key, raw) ->
            applicable = true
            val value = raw.coerceIn(0.0, 2.0)
            params = params.copy(temperature = value.toFloat())
            mapped += key
            if (value != raw) clamped += clamp(key, raw, value, "0–2")
        }
        number("top_p")?.let { (key, raw) ->
            applicable = true
            val value = raw.coerceIn(0.0, 1.0)
            params = params.copy(topP = value.toFloat())
            mapped += key
            if (value != raw) clamped += clamp(key, raw, value, "0–1")
        }
        number("top_k")?.let { (key, raw) ->
            applicable = true
            // Tavern uses 0 (and sometimes -1) for "disabled"; MCA stores 0 as the neutral value.
            val value = if (raw < 0) 0 else raw.toInt().coerceIn(0, MAX_TOP_K)
            params = params.copy(topK = value)
            mapped += key
            if (value.toDouble() != raw) clamped += clamp(key, raw, value, "0–$MAX_TOP_K")
        }
        number("min_p")?.let { (key, raw) ->
            applicable = true
            val value = raw.coerceIn(0.0, 1.0)
            params = params.copy(minP = value.toFloat())
            mapped += key
            if (value != raw) clamped += clamp(key, raw, value, "0–1")
        }
        number("repeat_penalty", "repetition_penalty", "rep_pen")?.let { (key, raw) ->
            applicable = true
            val value = raw.coerceIn(0.5, 2.0)
            params = params.copy(repeatPenalty = value.toFloat())
            mapped += key
            if (value != raw) clamped += clamp(key, raw, value, "0.5–2")
        }
        number("presence_penalty")?.let { (key, raw) ->
            applicable = true
            val value = raw.coerceIn(-2.0, 2.0)
            params = params.copy(presencePenalty = value.toFloat())
            mapped += key
            if (value != raw) clamped += clamp(key, raw, value, "-2–2")
        }
        number("frequency_penalty")?.let { (key, raw) ->
            applicable = true
            val value = raw.coerceIn(-2.0, 2.0)
            params = params.copy(frequencyPenalty = value.toFloat())
            mapped += key
            if (value != raw) clamped += clamp(key, raw, value, "-2–2")
        }
        number("max_tokens", "openai_max_tokens", "max_length")?.let { (key, raw) ->
            applicable = true
            // A Tavern preset routinely asks for 30k-60k output tokens because a web model streams a
            // whole chapter; a phone-side preset at that size overruns every context budget on the
            // first request, so the import ceiling is lower than the MCA sampler ceiling.
            // Keep the declared value. Only a non-positive number is replaced (SillyTavern uses 0 to
            // mean "let the server decide"), because MCA has no zero-output request.
            val declared = raw.coerceAtLeast(0.0).toInt()
            val value = if (declared <= 0) params.nPredict else declared
            params = params.copy(nPredict = value)
            mapped += key
            if (declared <= 0 && value != declared) {
                clamped += clamp(key, raw, value, "大于 0")
            }
        }
        number("seed")?.let { (key, raw) ->
            val value = raw.toInt()
            params = params.copy(seed = value)
            mapped += key
        }
        readStopWords(root, source)?.let { (list, dropped) ->
            applicable = true
            params = params.copy(stopWords = list)
            mapped += "stop"
            if (dropped > 0) {
                clamped += TavernPresetClamp(
                    key = "stop",
                    requested = dropped.toString(),
                    applied = list.size.toString(),
                    rule = "最多 $MAX_STOP_WORDS 个、每个不超过 $MAX_STOP_WORD_CHARS 字符"
                )
            }
        }

        val unsupported = linkedMapOf<String, TavernPresetUnsupported>()
        LOAD_BOUND_FIELDS.forEach { key ->
            val value = number(key) ?: return@forEach
            val suggestion = value.second.toInt().coerceIn(MIN_CONTEXT, MAX_CONTEXT)
            unsupported[key] = TavernPresetUnsupported(
                key = key,
                reason = "上下文长度是加载期参数：建议 n_ctx=$suggestion，但预设应用/存储没有该槽位，需要重新加载模型才生效"
            )
        }
        val handled = mapped.toSet() +
            listOf("temperature", "temp", "top_p", "top_k", "min_p", "repeat_penalty",
                "repetition_penalty", "rep_pen", "presence_penalty", "frequency_penalty",
                "max_tokens", "openai_max_tokens", "max_length", "stop", "stop_sequences",
                "stop_words", "seed", "name", "preset_name",
                "settings", "sampler", "preset", "extensions", "regex_scripts") +
            LOAD_BOUND_FIELDS
        val ignored = mutableListOf<String>()
        root.keys().forEach { key ->
            if (key in handled) return@forEach
            val known = UNSUPPORTED_FIELDS[key]
            if (known != null) {
                unsupported[key] = TavernPresetUnsupported(key, known)
            } else {
                ignored += key
            }
        }
        val promptMaterial = extractPromptAppendix(root)
        val promptText = promptMaterial?.first
        val promptChars = promptMaterial?.second ?: 0
        val regex = TavernRegexScriptParser.parsePresetRoot(root)
        if (regex.scripts.any { !it.disabled }) {
            // A regex-only preset (for example an output formatter) is applicable even when it
            // carries no sampler difference; it must not be rejected as "no applicable values".
            applicable = true
            mapped += "extensions.regex_scripts"
        }
        if (!applicable && promptText == null) {
            return GenerationPresetParseResult.Rejected(
                "该预设没有可应用的采样参数（只有提示词/模板或行为开关）；提示词请在角色卡中导入。"
            )
        }
        return GenerationPresetParseResult.Tavern(
            TavernPresetMapping(
                declaredName = root.optString("preset_name").ifBlank { root.optString("name") }
                    .trim()
                    .takeIf(String::isNotBlank),
                params = params,
                mappedKeys = mapped.toList(),
                clamped = clamped,
                unsupported = unsupported.values.toList(),
                ignoredKeys = ignored.distinct(),
                promptAppendix = promptText?.takeIf(String::isNotBlank),
                promptChars = promptChars,
                regexScripts = regex.scripts,
                hasRegexScriptsKey = regex.hasKey,
                regexWarnings = regex.warnings
            )
        )
    }

    /**
     * Flattens the preset's prompt bundle in `prompt_order` order.
     *
     * Returns the (possibly truncated) text plus the original length so the import report can say
     * how much prompt the file really carried. Marker rows are structural (`<char>`, `<scenario>`
     * …), not text, and disabled rows are excluded — exactly what SillyTavern would send.
     */
    private fun extractPromptAppendix(root: JSONObject): Pair<String, Int>? {
        val prompts = root.optJSONArray("prompts") ?: return null
        val byId = HashMap<String, JSONObject>()
        for (index in 0 until prompts.length()) {
            val item = prompts.optJSONObject(index) ?: continue
            val id = item.optString("identifier").ifBlank { item.optString("id") }
            if (id.isNotBlank()) byId[id] = item
        }
        val builder = StringBuilder()
        fun append(item: JSONObject) {
            if (!item.optBoolean("enabled", true)) return
            if (item.optBoolean("marker", false)) return
            val content = item.optString("content").trim()
            if (content.isEmpty()) return
            if (builder.isNotEmpty()) builder.append("\n\n")
            builder.append(content)
        }
        var ordered = false
        val order = root.optJSONArray("prompt_order")
        if (order != null) {
            for (groupIndex in 0 until order.length()) {
                val entries = order.optJSONObject(groupIndex)?.optJSONArray("order") ?: continue
                for (entryIndex in 0 until entries.length()) {
                    val identifier = entries.optJSONObject(entryIndex)?.optString("identifier").orEmpty()
                    val item = byId[identifier] ?: continue
                    append(item)
                    ordered = true
                }
            }
        }
        if (!ordered) {
            for (index in 0 until prompts.length()) {
                prompts.optJSONObject(index)?.let(::append)
            }
        }
        val text = builder.toString()
        if (text.isBlank()) return null
        val truncated = if (text.length > MAX_PROMPT_APPENDIX_CHARS) {
            text.take(MAX_PROMPT_APPENDIX_CHARS) + "\n…"
        } else {
            text
        }
        return truncated to text.length
    }

    private fun adaptMca(root: JSONObject, defaults: GenerationParams): GenerationPresetParseResult {
        val mapped = MCA_MAPPED_KEYS.filter(root::has)
        val unsupported = mutableListOf<TavernPresetUnsupported>()
        LOAD_BOUND_FIELDS.forEach { key ->
            if (!root.has(key)) return@forEach
            val suggestion = runCatching { root.optDouble(key, defaults.nCtx.toDouble()) }
                .getOrDefault(defaults.nCtx.toDouble())
                .toInt()
                .coerceIn(MIN_CONTEXT, MAX_CONTEXT)
            unsupported += TavernPresetUnsupported(
                key = key,
                reason = "上下文长度是加载期参数：建议 n_ctx=$suggestion，但预设应用没有该槽位，需要重新加载模型才生效"
            )
        }
        val storedWithoutEffect = listOf("system_prompt", "chat_template_mode", "advanced_json",
            "n_threads", "thinking_budget")
        storedWithoutEffect.filter(root::has).forEach { key ->
            unsupported += TavernPresetUnsupported(
                key = key,
                reason = "MCA 预设应用不会覆盖该字段（$key 由模型配置/角色卡拥有）"
            )
        }
        val handled = mapped.toSet() + unsupported.map { it.key }
        val ignored = root.keys().asSequence().filterNot { it in handled }.toList()
        val params = GenerationParams.fromJson(root, defaults)
        if (mapped.isEmpty()) {
            return GenerationPresetParseResult.Rejected("MCA 预设里没有可应用的采样参数。")
        }
        return GenerationPresetParseResult.Mca(
            McaPresetMapping(
                params = params,
                mappedKeys = mapped,
                unsupported = unsupported,
                ignoredKeys = ignored
            )
        )
    }

    /**
     * `stop` may be a string, a string array, or an array of arrays (grouped presets). Returns the
     * normalized list plus how many entries were dropped by the caps; null when the key is absent.
     */
    private fun readStopWords(root: JSONObject, source: JSONObject): Pair<List<String>, Int>? {
        val key = listOf("stop", "stop_sequences", "stop_words").firstOrNull { root.has(it) || source.has(it) }
            ?: return null
        val holder = if (source.has(key)) source else root
        val raw = holder.opt(key) ?: return null
        if (raw == JSONObject.NULL) return null
        val values = mutableListOf<String>()
        fun add(value: Any?) {
            when (value) {
                // Stop sequences are matched literally; a leading newline is meaningful, so the
                // value is kept verbatim (only empty strings are dropped).
                is String -> value.takeIf(String::isNotEmpty)?.let(values::add)
                is JSONArray -> for (index in 0 until value.length()) add(value.opt(index))
            }
        }
        add(raw)
        val normalized = values
            .map { it.take(MAX_STOP_WORD_CHARS) }
            .distinct()
            .take(MAX_STOP_WORDS)
        val dropped = values.distinct().size - normalized.size
        return normalized to dropped
    }

    private fun clamp(key: String, requested: Double, applied: Number, rule: String): TavernPresetClamp =
        TavernPresetClamp(
            key = key,
            requested = requested.cleanNumber(),
            applied = applied.toString(),
            rule = rule
        )

    private fun Double.cleanNumber(): String =
        if (this == toLong().toDouble()) toLong().toString() else toString()
}

/** One imported preset as the import path stores it, with its own field report. */
internal data class ImportedGenerationPreset(
    val name: String,
    val paramsJson: String,
    val appliedFields: Int,
    val clampedFields: Int,
    val ignoredFields: List<String>,
    val unsupportedFields: List<String>,
    /** Prompt characters the file carried; shown so a prompt-only preset is not mistaken for empty. */
    val promptChars: Int = 0
)

/**
 * Aggregated result of one batch import, rendered into the one status line the console shows.
 *
 * The counts are the answer to "已导入 N 项 / 忽略 N 项 / 不支持 N 项"; the key lists are truncated
 * on purpose (a Tavern preset can carry dozens of prompt fields) but never omitted entirely.
 */
internal data class GenerationPresetImportReport(
    val names: List<String> = emptyList(),
    val appliedFields: Int = 0,
    val clampedFields: Int = 0,
    val ignoredFields: List<String> = emptyList(),
    val unsupportedFields: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    /** Prompt characters carried by the imported files, so a prompt-only preset is not "empty". */
    val promptChars: Int = 0
) {
    fun plus(preset: ImportedGenerationPreset): GenerationPresetImportReport = copy(
        names = names + preset.name,
        appliedFields = appliedFields + preset.appliedFields,
        clampedFields = clampedFields + preset.clampedFields,
        ignoredFields = (ignoredFields + preset.ignoredFields).distinct(),
        unsupportedFields = (unsupportedFields + preset.unsupportedFields).distinct(),
        promptChars = promptChars + preset.promptChars
    )

    fun plusFailure(failure: String): GenerationPresetImportReport =
        copy(failures = failures + failure)

    /**
     * The counts clause appended to the import status line, so the result answers
     * "已导入 N 项 / 忽略 N 项 / 不支持 N 项" for every field of every selected file.
     */
    fun detailSuffix(): String = buildString {
        append(" · 应用 ").append(appliedFields).append(" 项")
        if (clampedFields > 0) append(" / 钳制 ").append(clampedFields).append(" 项")
        append(" / 忽略 ").append(ignoredFields.size).append(" 项")
        appendPresetKeys(ignoredFields)
        append(" / 不支持 ").append(unsupportedFields.size).append(" 项")
        appendPresetKeys(unsupportedFields)
        if (promptChars > 0) {
            append(" / 提示词 ").append(promptChars).append(" 字（可应用到助手）")
        }
    }
}

/** Bounded key list; a Tavern preset can carry dozens of prompt keys, so the tail is elided. */
private fun StringBuilder.appendPresetKeys(keys: List<String>) {
    if (keys.isEmpty()) return
    append("（").append(keys.take(6).joinToString("、"))
    if (keys.size > 6) append("…")
    append("）")
}

/** Reads a picked preset document with a hard byte ceiling; UTF-8, no partial decode. */
internal fun readGenerationPresetJson(input: InputStream, maxBytes: Int = SillyTavernPresetAdapter.MAX_JSON_BYTES): String {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        require(read <= maxBytes - total) { "预设文件超过 ${maxBytes / (1024 * 1024)} MiB。" }
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray().toString(Charsets.UTF_8)
}
