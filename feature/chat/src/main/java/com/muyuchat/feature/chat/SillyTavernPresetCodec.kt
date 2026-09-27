package com.muyuchat.feature.chat

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** The sampler subset of SillyTavern generation presets that MCA can apply per assistant. */
data class SillyTavernGenerationPreset(
    val name: String,
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val minP: Float? = null,
    val repeatPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val frequencyPenalty: Float? = null,
    val maxTokens: Int? = null,
    val stopWords: List<String>? = null,
    /** Ordered, plain-text prompt material that can be merged into an assistant system prompt. */
    val promptAppendix: String? = null,
    val promptIdentifiers: List<String> = emptyList(),
    val ignoredPromptFields: List<String> = emptyList()
)

data class RoleplayStylePreset(
    val id: String,
    val name: String,
    val instruction: String,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val repeatPenalty: Float,
    val maxTokens: Int
)

object RoleplayStylePresets {
    val all = listOf(
        RoleplayStylePreset(
            id = "immersive",
            name = "沉浸角色演绎",
            instruction = "以当前角色的视角和语气进行虚构互动。结合前文保持人物一致，用自然对白与适量动作推进场景；不要替用户决定其行动或台词。",
            temperature = 0.8f,
            topP = 0.95f,
            topK = 40,
            repeatPenalty = 1.08f,
            maxTokens = 512
        ),
        RoleplayStylePreset(
            id = "story",
            name = "剧情共创",
            instruction = "把对话写成连贯的互动故事，先承接用户刚提供的信息，再推进一个清楚的场景。保持角色一致、细节适量，不替用户控制的角色做决定。",
            temperature = 0.85f,
            topP = 0.94f,
            topK = 40,
            repeatPenalty = 1.1f,
            maxTokens = 768
        ),
        RoleplayStylePreset(
            id = "concise",
            name = "简洁对话",
            instruction = "保持角色口吻，优先直接回应当前话题。通常用一至三段短文，不重复背景设定，也不扩写未被要求的细节。",
            temperature = 0.7f,
            topP = 0.9f,
            topK = 30,
            repeatPenalty = 1.08f,
            maxTokens = 256
        )
    )

    private const val PROMPT_SECTION = "【MCA 角色演绎风格："

    fun withPresetPrompt(currentPrompt: String, preset: RoleplayStylePreset?): String {
        val base = currentPrompt.substringBefore(PROMPT_SECTION).trimEnd()
        if (preset == null) return base
        return listOf(base, "$PROMPT_SECTION${preset.name}】\n${preset.instruction}")
            .filter(String::isNotBlank)
            .joinToString("\n\n")
            .take(MAX_PROMPT_CHARS)
    }

    fun selectedPresetId(prompt: String): String? = all.firstOrNull { preset ->
        prompt.trimEnd().endsWith("$PROMPT_SECTION${preset.name}】\n${preset.instruction}")
    }?.id

    private const val MAX_PROMPT_CHARS = 12_000
}

/**
 * Imports common Tavern sampler settings and the plain-text prompt material from a
 * preset. Template macros and executable extensions are never evaluated; the
 * resulting text is merged into the assistant's system prompt while MCA keeps
 * the selected model's native chat template.
 */
object SillyTavernPresetCodec {
    private const val MAX_JSON_BYTES = 1_048_576
    private val promptFields = listOf(
        "prompts",
        "prompt_order",
        "instruct",
        "instruct_template",
        "context_template",
        "system_prompt",
        "jailbreak_prompt",
        "max_context_length"
    )

    fun parse(rawJson: String, fallbackName: String = "Tavern 预设"): SillyTavernGenerationPreset {
        require(rawJson.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) {
            "预设文件超过 1 MiB。"
        }
        val root = JSONObject(rawJson)
        val source = root.optJSONObject("settings")
            ?: root.optJSONObject("sampler")
            ?: root.optJSONObject("preset")
            ?: root

        val temperature = readDouble(source, "temperature", "temp")?.also {
            require(it in 0.0..2.0) { "temperature 必须在 0 到 2 之间。" }
        }?.toFloat()
        val topP = readDouble(source, "top_p", "topP")?.also {
            require(it in 0.0..1.0) { "top_p 必须在 0 到 1 之间。" }
        }?.toFloat()
        val topK = readDouble(source, "top_k", "topK")?.also {
            require(it in 0.0..1000.0 && it % 1.0 == 0.0) { "top_k 必须是 0 到 1000 的整数。" }
        }?.toInt()
        val minP = readDouble(source, "min_p", "minP")?.also {
            require(it in 0.0..1.0) { "min_p 必须在 0 到 1 之间。" }
        }?.toFloat()
        val repeatPenalty = readDouble(
            source,
            "rep_pen",
            "repeat_penalty",
            "repetition_penalty"
        )?.also {
            require(it in 0.5..2.0) { "重复惩罚必须在 0.5 到 2 之间。" }
        }?.toFloat()
        val presencePenalty = readDouble(source, "presence_penalty")?.also {
            require(it in -2.0..2.0) { "presence_penalty 必须在 -2 到 2 之间。" }
        }?.toFloat()
        val frequencyPenalty = readDouble(source, "frequency_penalty")?.also {
            require(it in -2.0..2.0) { "frequency_penalty 必须在 -2 到 2 之间。" }
        }?.toFloat()
        val maxTokens = readDouble(source, "max_length", "max_tokens", "n_predict")?.also {
            require(it in 1.0..65_536.0 && it % 1.0 == 0.0) {
                "max_length/max_tokens 必须是 1 到 65536 的整数。"
            }
        }?.toInt()
        val stopWords = readStopWords(source)
        val promptMaterial = readPromptMaterial(root, source)

        require(
            temperature != null || topP != null || topK != null || minP != null ||
                repeatPenalty != null || presencePenalty != null || frequencyPenalty != null ||
                maxTokens != null || stopWords != null || promptMaterial.text != null
        ) {
            "没有找到可应用的采样参数或纯文本提示内容。"
        }

        val ignored = promptFields.filter { root.has(it) || source.has(it) }
        val name = root.optString("name")
            .ifBlank { source.optString("name") }
            .ifBlank { fallbackName }
            .trim()
            .take(96)

        return SillyTavernGenerationPreset(
            name = name,
            temperature = temperature,
            topP = topP,
            topK = topK,
            minP = minP,
            repeatPenalty = repeatPenalty,
            presencePenalty = presencePenalty,
            frequencyPenalty = frequencyPenalty,
            maxTokens = maxTokens,
            stopWords = stopWords,
            promptAppendix = promptMaterial.text,
            promptIdentifiers = promptMaterial.identifiers,
            ignoredPromptFields = ignored
        )
    }

    /** Merges imported plain text once, without replacing the user's character card. */
    fun mergePromptAppendix(currentPrompt: String, appendix: String): String {
        val marker = "【MCA 酒馆预设提示内容】"
        val base = currentPrompt.substringBefore(marker).trimEnd()
        val clean = appendix.trim().takeIf(String::isNotBlank) ?: return base
        return listOf(base, marker, clean)
            .filter(String::isNotBlank)
            .joinToString("\n\n")
            .take(MAX_PROMPT_CHARS)
    }

    /**
     * Extracts only bounded plain text from common SillyTavern preset shapes.
     * `prompt_order` is honored when it references entries in `prompts`; otherwise
     * the source array order is used. No macro, script, or extension is executed.
     */
    private fun readPromptMaterial(root: JSONObject, source: JSONObject): PromptMaterial {
        val entries = linkedMapOf<String, String>()
        val prompts = root.opt("prompts").takeUnless { it == null || it == JSONObject.NULL }
            ?: source.opt("prompts").takeUnless { it == null || it == JSONObject.NULL }
        fun addPrompt(item: Any?, fallbackId: String) {
            when (item) {
                is JSONObject -> {
                    val id = item.optString("identifier")
                        .ifBlank { item.optString("id") }
                        .ifBlank { fallbackId }
                    val text = item.optString("content")
                        .ifBlank { item.optString("prompt") }
                        .ifBlank { item.optString("text") }
                    sanitizePromptText(text)?.let { entries[id] = it }
                }
                is String -> sanitizePromptText(item)?.let { entries[fallbackId] = it }
            }
        }
        when (prompts) {
            is JSONArray -> for (index in 0 until prompts.length()) {
                addPrompt(prompts.opt(index), "prompt_$index")
            }
            // Some Tavern exports serialize prompts as an object keyed by
            // identifier instead of the newer array form.
            is JSONObject -> {
                // org.json stores object members in a HashMap, so `keys()` does
                // not retain the order from the exported JSON.  Use a stable
                // semantic order for the common Tavern sections and a lexical
                // fallback for custom sections.  An explicit prompt_order still
                // takes precedence below.
                val semanticOrder = mapOf(
                    "main" to 0,
                    "system" to 1,
                    "context" to 2,
                    "jailbreak" to 3,
                    "prefill" to 4
                )
                prompts.keys()
                    .asSequence()
                    .toList()
                    .sortedWith(compareBy<String>({ semanticOrder[it] ?: 100 }, { it }))
                    .forEach { key ->
                        addPrompt(prompts.opt(key), key.ifBlank { "prompt_${entries.size}" })
                    }
            }
        }

        val orderedIds = mutableListOf<String>()
        val promptOrder = root.optJSONArray("prompt_order") ?: source.optJSONArray("prompt_order")
        if (promptOrder != null) {
            for (index in 0 until promptOrder.length()) {
                val item = promptOrder.opt(index)
                when (item) {
                    is JSONObject -> {
                        val nested = item.optJSONArray("order")
                        if (nested != null) {
                            for (nestedIndex in 0 until nested.length()) {
                                val nestedItem = nested.optJSONObject(nestedIndex) ?: continue
                                if (!nestedItem.has("enabled") || nestedItem.optBoolean("enabled", true)) {
                                    nestedItem.optString("identifier")
                                        .ifBlank { nestedItem.optString("id") }
                                        .takeIf(String::isNotBlank)
                                        ?.let(orderedIds::add)
                                }
                            }
                        } else {
                            val enabled = !item.has("enabled") || item.optBoolean("enabled", true)
                            if (enabled) {
                                item.optString("identifier")
                                    .ifBlank { item.optString("id") }
                                    .takeIf(String::isNotBlank)
                                    ?.let(orderedIds::add)
                            }
                        }
                    }
                    is String -> item.trim().takeIf(String::isNotBlank)?.let(orderedIds::add)
                }
            }
        }
        val ids = buildList {
            orderedIds.forEach { if (it in entries && it !in this) add(it) }
            entries.keys.forEach { if (it !in this) add(it) }
        }

        val legacyFields = listOf(
            "system_prompt", "jailbreak_prompt", "context_template", "instruct", "instruct_template"
        )
        val legacyText = legacyFields.mapNotNull { key ->
            val value = root.optString(key).ifBlank { source.optString(key) }
            sanitizePromptText(value)
        }
        val all = (legacyText + ids.mapNotNull(entries::get))
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        val text = all.joinToString("\n\n").take(MAX_PROMPT_CHARS).ifBlank { null }
        return PromptMaterial(text, ids)
    }

    private fun sanitizePromptText(raw: String): String? {
        val normalized = raw
            .replace("\\u0000", "")
            .replace(Regex("\\r\\n?"), "\\n")
            .replace(Regex("\\{\\{[^}]{1,160}}"), "")
            .trim()
        return normalized.takeIf { it.isNotBlank() && it.length <= MAX_PROMPT_ENTRY_CHARS }
    }

    private data class PromptMaterial(val text: String?, val identifiers: List<String>)

    private const val MAX_PROMPT_ENTRY_CHARS = 8_000
    private const val MAX_PROMPT_CHARS = 24_000

    private fun readDouble(source: JSONObject, vararg keys: String): Double? {
        val key = keys.firstOrNull(source::has) ?: return null
        val raw = source.opt(key)
        val parsed = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }
        require(parsed != null && parsed.isFinite()) { "$key 不是有效数值。" }
        return parsed
    }

    private fun readStopWords(source: JSONObject): List<String>? {
        val key = listOf("stop", "stop_sequence", "stop_sequences", "stop_words")
            .firstOrNull(source::has) ?: return null
        val raw = source.opt(key)
        val values = when (raw) {
            is JSONArray -> buildList {
                for (index in 0 until raw.length()) {
                    raw.optString(index).takeIf(String::isNotEmpty)?.let(::add)
                }
            }
            is String -> listOf(raw).filter(String::isNotBlank)
            else -> error("$key 必须是字符串或字符串数组。")
        }
        require(values.size <= 32 && values.all { it.length <= 128 }) {
            "$key 最多允许 32 个停止词，每个不超过 128 个字符。"
        }
        return values.distinct()
    }
}

fun readSillyTavernPresetJson(input: InputStream, maxBytes: Int = 1_048_576): String {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        require(read <= maxBytes - total) { "酒馆预设文件超过 1 MiB。" }
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray().toString(Charsets.UTF_8)
}
