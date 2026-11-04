package com.muyuchat.feature.chat.tavern

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Preset JSON key that stores the normalized regex scripts alongside sampler values. */
const val TAVERN_REGEX_SCRIPTS_KEY: String = "tavern_regex_scripts"

const val TAVERN_REGEX_MAX_SCRIPTS: Int = 64
const val TAVERN_REGEX_MAX_INPUT_CHARS: Int = 200_000
const val TAVERN_REGEX_MAX_REPLACEMENTS: Int = 2_000

/** Which side of the conversation a regex script applies to (`placement` 1 / 2 in Tavern). */
enum class TavernRegexPlacement {
    USER_INPUT,
    ASSISTANT_OUTPUT
}

/**
 * One normalized Tavern `regex_script`.
 *
 * `markdownOnly` is parsed for compatibility but deliberately ignored: MCA's chat body is one
 * rendering path, so a script marked markdown-only would otherwise apply to only some messages.
 */
data class TavernRegexScript(
    val name: String = "",
    val findRegex: String,
    val replaceString: String = "",
    val placements: Set<TavernRegexPlacement> = setOf(TavernRegexPlacement.ASSISTANT_OUTPUT),
    val disabled: Boolean = false,
    val markdownOnly: Boolean = false
)

data class TavernRegexParseResult(
    val scripts: List<TavernRegexScript> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** Whether the preset actually carried `extensions.regex_scripts` / `tavern_regex_scripts`. */
    val hasKey: Boolean = false
)

/**
 * Compatibility parser for SillyTavern `extensions.regex_scripts`.
 *
 * Field aliases: `findRegex`/`find_regex`, `replaceString`/`replace_string`, `disabled` or
 * `enabled`, and `placement` as a single integer or an array of integers/strings. A missing
 * `placement` defaults to AI output (`2`), matching the common Tavern export. Malformed entries are
 * skipped with a warning instead of rejecting the whole preset.
 */
object TavernRegexScriptParser {

    fun parsePresetRoot(root: JSONObject): TavernRegexParseResult {
        val extensions = root.optJSONObject("extensions")
        val fromExtensions = extensions?.opt("regex_scripts")
        val fromRoot = root.opt("regex_scripts")
        val value = fromExtensions ?: fromRoot
        val hasKey = fromExtensions != null || root.has("regex_scripts")
        if (value == null || value == JSONObject.NULL) {
            return TavernRegexParseResult(hasKey = hasKey)
        }
        return parseValue(value, hasKey = true)
    }

    fun parseParamsJson(paramsJson: String): TavernRegexParseResult {
        val root = runCatching { JSONObject(paramsJson) }.getOrNull()
            ?: return TavernRegexParseResult(
                warnings = listOf("预设 JSON 不可解析，酒馆正则未启用。"),
                hasKey = false
            )
        if (!root.has(TAVERN_REGEX_SCRIPTS_KEY)) return TavernRegexParseResult(hasKey = false)
        return parseValue(root.opt(TAVERN_REGEX_SCRIPTS_KEY), hasKey = true)
    }

    /** Serializes normalized scripts back into the preset's `tavern_regex_scripts` array. */
    fun toJsonArray(scripts: List<TavernRegexScript>): JSONArray = JSONArray().apply {
        scripts.take(TAVERN_REGEX_MAX_SCRIPTS).forEach { script ->
            put(
                JSONObject()
                    .put("name", script.name.take(128))
                    .put("findRegex", script.findRegex.take(TAVERN_REGEX_MAX_INPUT_CHARS))
                    .put("replaceString", script.replaceString.take(TAVERN_REGEX_MAX_INPUT_CHARS))
                    .put(
                        "placement",
                        JSONArray(script.placements.map { placement ->
                            when (placement) {
                                TavernRegexPlacement.USER_INPUT -> 1
                                TavernRegexPlacement.ASSISTANT_OUTPUT -> 2
                            }
                        }.distinct().sorted())
                    )
                    .put("disabled", script.disabled)
                    .put("markdownOnly", script.markdownOnly)
            )
        }
    }

    private fun parseValue(value: Any?, hasKey: Boolean): TavernRegexParseResult {
        val array = when (value) {
            is JSONArray -> value
            is String -> runCatching { JSONArray(value) }.getOrNull()
            else -> null
        } ?: return TavernRegexParseResult(
            warnings = listOf("tavern_regex_scripts 不是数组，已跳过。"),
            hasKey = hasKey
        )

        val warnings = mutableListOf<String>()
        val scripts = mutableListOf<TavernRegexScript>()
        val limit = minOf(array.length(), TAVERN_REGEX_MAX_SCRIPTS)
        if (array.length() > TAVERN_REGEX_MAX_SCRIPTS) {
            warnings += "正则脚本超过 $TAVERN_REGEX_MAX_SCRIPTS 条，仅保留前 $TAVERN_REGEX_MAX_SCRIPTS 条。"
        }
        for (index in 0 until limit) {
            val item = array.optJSONObject(index)
            if (item == null) {
                warnings += "第 ${index + 1} 条正则不是对象，已跳过。"
                continue
            }
            val find = item.stringValue("findRegex", "find_regex", "find").trim()
            if (find.isEmpty()) {
                warnings += "第 ${index + 1} 条正则缺少 findRegex，已跳过。"
                continue
            }
            val replace = item.stringValue("replaceString", "replace_string", "replace")
            val placements = parsePlacements(item.opt("placement"), index + 1, warnings)
            if (placements.isEmpty()) continue
            scripts += TavernRegexScript(
                name = item.stringValue("name", "scriptName", "script_name").take(128),
                findRegex = find,
                replaceString = replace,
                placements = placements,
                disabled = item.optBoolean("disabled", false) || !item.optBoolean("enabled", true),
                markdownOnly = item.optBoolean("markdownOnly", item.optBoolean("markdown_only", false))
            )
        }
        return TavernRegexParseResult(
            scripts = scripts,
            warnings = warnings,
            hasKey = hasKey
        )
    }

    private fun parsePlacements(
        value: Any?,
        index: Int,
        warnings: MutableList<String>
    ): Set<TavernRegexPlacement> {
        if (value == null || value == JSONObject.NULL) {
            return setOf(TavernRegexPlacement.ASSISTANT_OUTPUT)
        }
        val values = when (value) {
            is JSONArray -> (0 until value.length()).map { value.opt(it) }
            else -> listOf(value)
        }
        val placements = values.mapNotNull(::placementOf).toSet()
        if (placements.isEmpty()) {
            warnings += "第 $index 条正则的 placement 无法识别，已跳过。"
        }
        return placements
    }

    private fun placementOf(value: Any?): TavernRegexPlacement? = when (value) {
        is Number -> when (value.toInt()) {
            1 -> TavernRegexPlacement.USER_INPUT
            2 -> TavernRegexPlacement.ASSISTANT_OUTPUT
            else -> null
        }
        is String -> when (value.trim().lowercase(Locale.ROOT)) {
            "1", "user", "user_input", "input" -> TavernRegexPlacement.USER_INPUT
            "2", "ai", "assistant", "ai_output", "output" -> TavernRegexPlacement.ASSISTANT_OUTPUT
            else -> null
        }
        else -> null
    }

    private fun JSONObject.stringValue(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key ->
            optString(key).takeIf(String::isNotBlank)
        }.orEmpty()
}

/** Result of one regex application, including per-run warnings for diagnostics/tests. */
data class TavernRegexApplication(
    val text: String,
    val warnings: List<String> = emptyList()
)

/**
 * Compiles and applies the Tavern regex scripts in array order.
 *
 * Replacement semantics follow Kotlin's `Regex.replace`: `$0` is the whole match, `$1` is a capture
 * group, `${name}` a named group, and `\$` inserts a literal dollar. Difference from JavaScript:
 * JS uses `$&` for the whole match and `$$` for a literal dollar, and its replacement backslash is
 * not an escape; scripts authored against JS `$&`/`$$` are therefore not translated automatically.
 *
 * Safety: at most [TAVERN_REGEX_MAX_SCRIPTS] scripts, [TAVERN_REGEX_MAX_INPUT_CHARS] characters per
 * script input, and [TAVERN_REGEX_MAX_REPLACEMENTS] replacements per script. Invalid patterns and
 * over-limit input skip only that script and append a warning; later scripts still run.
 */
class TavernRegexPostProcessor private constructor(
    private val compiled: List<CompiledScript>,
    /** Parse/compile warnings; per-application warnings are returned by [applyDetailed]. */
    val warnings: List<String>,
    val hasPresetKey: Boolean
) {
    val hasScripts: Boolean get() = compiled.isNotEmpty()

    fun applyToUserInput(text: String): String =
        applyDetailed(text, TavernRegexPlacement.USER_INPUT).text

    fun applyToAssistantOutput(text: String): String =
        applyDetailed(text, TavernRegexPlacement.ASSISTANT_OUTPUT).text

    internal fun applyDetailed(
        text: String,
        placement: TavernRegexPlacement
    ): TavernRegexApplication {
        val active = compiled.filter { placement in it.script.placements }
        if (active.isEmpty()) return TavernRegexApplication(text)
        var output = text
        val applicationWarnings = mutableListOf<String>()
        for (script in active) {
            if (output.length > TAVERN_REGEX_MAX_INPUT_CHARS) {
                output = output.take(TAVERN_REGEX_MAX_INPUT_CHARS)
                applicationWarnings += "脚本 ${script.script.name.ifBlank { "未命名" }} 的输入超过 " +
                    "$TAVERN_REGEX_MAX_INPUT_CHARS 字符，已截断。"
            }
            val applied = applyOne(script, output)
            output = applied.text
            applicationWarnings += applied.warnings
        }
        return TavernRegexApplication(output, applicationWarnings)
    }

    private fun applyOne(script: CompiledScript, text: String): TavernRegexApplication {
        val matches = script.regex.findAll(text)
        val builder = StringBuilder(text.length)
        var lastEnd = 0
        var count = 0
        var hitLimit = false
        for (match in matches) {
            if (count >= TAVERN_REGEX_MAX_REPLACEMENTS) {
                hitLimit = true
                break
            }
            builder.append(text, lastEnd, match.range.first)
            builder.append(expandReplacement(match, script.script.replaceString))
            lastEnd = match.range.last + 1
            count += 1
        }
        if (count == 0) return TavernRegexApplication(text)
        builder.append(text, lastEnd, text.length)
        val warnings = if (hitLimit) {
            listOf(
                "脚本 ${script.script.name.ifBlank { "未命名" }} 达到 " +
                    "$TAVERN_REGEX_MAX_REPLACEMENTS 次替换上限，剩余匹配保持原样。"
            )
        } else {
            emptyList()
        }
        return TavernRegexApplication(builder.toString(), warnings)
    }

    /**
     * Kotlin-compatible replacement expansion. Unknown groups expand to empty text rather than
     * throwing, so one malformed script cannot abort the turn.
     */
    private fun expandReplacement(match: MatchResult, replacement: String): String {
        val out = StringBuilder(replacement.length)
        var index = 0
        while (index < replacement.length) {
            val character = replacement[index]
            when {
                character == '\\' && index + 1 < replacement.length -> {
                    out.append(replacement[index + 1])
                    index += 2
                }

                character == '$' && index + 1 < replacement.length -> {
                    val next = replacement[index + 1]
                    if (next == '{') {
                        val close = replacement.indexOf('}', index + 2)
                        if (close < 0) {
                            out.append('$')
                            index += 1
                        } else {
                            val name = replacement.substring(index + 2, close)
                            out.append(runCatching { match.groups[name]?.value }.getOrNull().orEmpty())
                            index = close + 1
                        }
                    } else if (next.isDigit()) {
                        var end = index + 1
                        while (end < replacement.length && replacement[end].isDigit()) end += 1
                        val groupIndex = replacement.substring(index + 1, end).toIntOrNull()
                        out.append(
                            groupIndex
                                ?.takeIf { it in 0 until match.groupValues.size }
                                ?.let { match.groupValues[it] }
                                .orEmpty()
                        )
                        index = end
                    } else {
                        out.append('$')
                        index += 1
                    }
                }

                else -> {
                    out.append(character)
                    index += 1
                }
            }
        }
        return out.toString()
    }

    private data class CompiledScript(val script: TavernRegexScript, val regex: Regex)

    companion object {
        /**
         * Compiles [scripts]. Disabled scripts are dropped; invalid regexes and the over-64 script
         * cap are reported in [warnings] and do not affect other scripts.
         */
        fun fromScripts(
            scripts: List<TavernRegexScript>,
            warnings: List<String> = emptyList(),
            hasPresetKey: Boolean = true
        ): TavernRegexPostProcessor {
            val compileWarnings = warnings.toMutableList()
            val compiled = mutableListOf<CompiledScript>()
            if (scripts.size > TAVERN_REGEX_MAX_SCRIPTS) {
                compileWarnings += "正则脚本超过 $TAVERN_REGEX_MAX_SCRIPTS 条，仅编译前 $TAVERN_REGEX_MAX_SCRIPTS 条。"
            }
            scripts.take(TAVERN_REGEX_MAX_SCRIPTS).forEachIndexed { index, script ->
                if (script.disabled) return@forEachIndexed
                val regex = runCatching { Regex(script.findRegex) }.getOrNull()
                if (regex == null) {
                    compileWarnings += "第 ${index + 1} 条正则无法编译，已跳过：${script.findRegex.take(80)}"
                } else {
                    compiled += CompiledScript(script, regex)
                }
            }
            return TavernRegexPostProcessor(
                compiled = compiled,
                warnings = compileWarnings,
                hasPresetKey = hasPresetKey
            )
        }

        /** Reads the normalized scripts saved with the preset; absent key means "do nothing". */
        fun fromParamsJson(paramsJson: String): TavernRegexPostProcessor {
            val parsed = TavernRegexScriptParser.parseParamsJson(paramsJson)
            return fromScripts(
                scripts = parsed.scripts,
                warnings = parsed.warnings,
                hasPresetKey = parsed.hasKey
            )
        }

        /** Alias used by the prompt pipeline. */
        fun fromPresetParams(paramsJson: String): TavernRegexPostProcessor = fromParamsJson(paramsJson)
    }
}
