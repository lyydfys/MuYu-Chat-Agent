package com.muyuchat.mca

/**
 * The offline translation syntax layer: how a user prompt is split into translatable language slots
 * and opaque control tokens, and how the translated slots are reassembled.
 *
 * ## Where this comes from
 *
 * Moved out of `OfflinePromptTranslation.kt` (a 1,887-line contract file) so that file keeps the
 * bundle/service responsibilities. Every declaration keeps its own name, so the package-level FQNs
 * that other files and tests already use (`parseOfflinePromptTranslationSyntax`,
 * `translateOfflinePromptSyntaxPlan`, `hasMatchingOfflinePromptStructure`,
 * `extractOfflinePromptProtectedTokens`, `OfflinePromptTranslationSyntaxPlan`) are unchanged and no
 * call site moved. Only two file-private predicates were widened to `internal`, and only because the
 * code that stayed behind also calls them.
 *
 * ## What must not change
 *
 * The segmentation boundaries, the nesting/escape/callback budget rules, the opaque-token alphabet,
 * and the predicate character sets are the contract between the lexer and the translation runtime.
 * The two predicates below are data, not implementation detail: widening or narrowing a character
 * set changes which prompts are sent to the model at all.
 */
/** A bounded plan separates user control syntax from language translated by a model. */
internal data class OfflinePromptTranslationSyntaxPart(val text: String, val kind: String?)

internal data class OfflinePromptTranslationSyntaxPlan(
    val parts: List<OfflinePromptTranslationSyntaxPart>,
    val protectedTokens: List<String>
) {
    /** Controls discovered in this source, rather than a catalogue of special namespaces. */
    val bareControlTokens: Set<String>
        get() = parts.filter { it.kind == "bare_control" }.mapTo(linkedSetOf()) { it.text }

    /** Text slots retain their position around controls; numeric literals retain exact spelling. */
    fun signature(): List<Pair<String, String>> = buildList {
        parts.forEach { part ->
            if (part.kind != null) {
                add(part.kind to part.text)
            } else if (part.text.any { it.isLetterOrDigit() }) {
                add("text" to "")
                OFFLINE_PROMPT_NUMBER_PATTERN.findAll(part.text).forEach { add("number" to it.value) }
            }
        }
    }
}

internal class OfflinePromptTranslationSyntaxException(
    val errorCode: String,
    val offset: Int,
    detail: String
) : IllegalArgumentException("$detail (offset=$offset)")

private const val MAX_OFFLINE_PROMPT_SYNTAX_DEPTH = 16
private const val MAX_OFFLINE_PROMPT_SYNTAX_PARTS = 512
private const val MAX_OFFLINE_PROMPT_TRANSLATABLE_PARTS = 32
private const val MAX_OFFLINE_PROMPT_OPAQUE_TOKEN_CHARS = 200
private val OFFLINE_PROMPT_NUMBER_PATTERN =
    Regex("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")
/* Namespaced controls are parsed generically, so new control namespaces remain opaque. */
private val OFFLINE_PROMPT_BARE_TOKEN_PATTERN = Regex(
    "(?i)[A-Za-z_][A-Za-z0-9_.-]{0,31}:[A-Za-z0-9_+./-]{1,128}(?::[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))?"
)

/**
 * This is a syntax lexer, not a catalogue of supported LoRA files or model capabilities. Any
 * bounded angle tag is opaque, and all balanced nesting, schedule separators and escapes are
 * preserved. Unbalanced/over-budget input is returned to the user unchanged by the service.
 */
internal fun parseOfflinePromptTranslationSyntax(
    prompt: String,
    bareControlTokens: Set<String>? = null
): OfflinePromptTranslationSyntaxPlan {
    if (prompt.containsUnsafeTranslationCharacters()) {
        throw OfflinePromptTranslationSyntaxException(
            "prompt_syntax_unsafe_character", 0, "Prompt contains hidden control or format characters."
        )
    }
    if (prompt.length > OfflinePromptTranslationContract.MAX_OUTPUT_TEXT_CHARS) {
        throw OfflinePromptTranslationSyntaxException("prompt_syntax_size_limit", 0, "Prompt exceeds the syntax parsing limit.")
    }
    val parts = mutableListOf<OfflinePromptTranslationSyntaxPart>()
    val protectedRanges = mutableListOf<IntRange>()
    val stack = java.util.ArrayDeque<Pair<Char, Int>>()
    val text = StringBuilder()
    var index = 0

    fun addPart(value: String, kind: String?) {
        if (value.isEmpty()) return
        if (parts.size >= MAX_OFFLINE_PROMPT_SYNTAX_PARTS) {
            throw OfflinePromptTranslationSyntaxException("prompt_syntax_part_limit", index, "Prompt has too many structural fragments.")
        }
        parts.add(OfflinePromptTranslationSyntaxPart(value, kind))
    }
    fun flushText() {
        addPart(text.toString(), null)
        text.setLength(0)
    }
    fun protect(value: String, kind: String) {
        flushText()
        addPart(value, kind)
    }
    fun fail(code: String, detail: String): Nothing =
        throw OfflinePromptTranslationSyntaxException(code, index, detail)

    while (index < prompt.length) {
        val character = prompt[index]
        when {
            character == '\\' && index + 1 < prompt.length && prompt[index + 1] in "\\()[]{}<>:|" -> {
                protect(prompt.substring(index, index + 2), "escape")
                index += 2
            }
            character == '<' -> {
                val end = prompt.indexOf('>', index + 1)
                if (end < 0 || end - index + 1 > MAX_OFFLINE_PROMPT_OPAQUE_TOKEN_CHARS ||
                    prompt.substring(index + 1, end).any { it == '<' || it == '\n' || it == '\r' }
                ) fail("prompt_syntax_invalid_tag", "Prompt contains an incomplete or oversized control tag.")
                protect(prompt.substring(index, end + 1), "opaque")
                protectedRanges.add(index..end)
                index = end + 1
            }
            character == '>' -> fail("prompt_syntax_unbalanced", "Prompt contains an unmatched control delimiter.")
            character in "([{" -> {
                if (stack.size >= MAX_OFFLINE_PROMPT_SYNTAX_DEPTH) {
                    fail("prompt_syntax_depth_limit", "Prompt nesting exceeds the supported parsing depth.")
                }
                protect(character.toString(), "delimiter")
                stack.addLast(character to index)
                index++
            }
            character in ")]}" -> {
                val opened = stack.peekLast() ?: fail("prompt_syntax_unbalanced", "Prompt contains an unmatched closing delimiter.")
                val expected = when (opened.first) { '(' -> ')'; '[' -> ']'; else -> '}' }
                if (character != expected) fail("prompt_syntax_unbalanced", "Prompt contains mismatched nested delimiters.")
                protect(character.toString(), "delimiter")
                stack.removeLast()
                if (stack.isEmpty()) protectedRanges.add(opened.second..index)
                index++
            }
            character in ":|=" || stack.isNotEmpty() && character in "@#%&*+/_-" -> {
                protect(character.toString(), "separator")
                index++
                if (character == ':') {
                    val number = OFFLINE_PROMPT_NUMBER_PATTERN.find(prompt, index)?.takeIf { it.range.first == index }
                    if (number != null) {
                        protect(number.value, "control_number")
                        index = number.range.last + 1
                    }
                }
            }
            (index == 0 || !prompt[index - 1].isLetterOrDigit() && prompt[index - 1] != '_') &&
                OFFLINE_PROMPT_BARE_TOKEN_PATTERN.matchAt(prompt, index)?.let {
                    bareControlTokens == null || it.value in bareControlTokens
                } == true -> {
                val token = requireNotNull(OFFLINE_PROMPT_BARE_TOKEN_PATTERN.matchAt(prompt, index))
                if (token.value.length > MAX_OFFLINE_PROMPT_OPAQUE_TOKEN_CHARS) {
                    fail("prompt_syntax_invalid_tag", "Prompt contains an oversized named control token.")
                }
                protect(token.value, "bare_control")
                protectedRanges.add(token.range)
                index = token.range.last + 1
            }
            else -> {
                if (character.code < 128 && !character.toString().isSafeAsciiDiffusionPrompt()) {
                    fail("prompt_syntax_unsupported_character", "Prompt contains syntax that cannot be safely preserved by this runtime.")
                }
                text.append(character)
                index++
            }
        }
    }
    if (stack.isNotEmpty()) fail("prompt_syntax_unbalanced", "Prompt contains an unclosed structural delimiter.")
    flushText()
    if (parts.count { it.kind == null && it.text.containsHanForOfflineTranslation() } > MAX_OFFLINE_PROMPT_TRANSLATABLE_PARTS) {
        fail("prompt_translation_fragment_limit", "Prompt has too many independent translation fragments.")
    }
    // Outermost groups are display metadata only. Validation uses the complete token signature,
    // so a translated phrase inside a group is permitted without relaxing its numeric/control data.
    val ordered = protectedRanges.sortedBy { it.first }
    val metadata = ordered.filterNot { range ->
        ordered.any { other -> other != range && other.first <= range.first && other.last >= range.last }
    }.map { prompt.substring(it.first, it.last + 1) }
    return OfflinePromptTranslationSyntaxPlan(parts.toList(), metadata)
}

/**
 * Reassembly owns controls and English-only fragments; the model sees only language slots. This
 * avoids relying on model output to reconstruct nested attention/schedules or LoRA positions.
 * The callback is bounded by the remaining branch budget and receives no opaque control tokens.
 */
internal fun translateOfflinePromptSyntaxPlan(
    plan: OfflinePromptTranslationSyntaxPlan,
    maxChars: Int,
    translateText: (String, Int) -> String
): String {
    require(maxChars in 1..OfflinePromptTranslationContract.MAX_OUTPUT_TEXT_CHARS)
    val output = StringBuilder()
    plan.parts.forEach { part ->
        val value = if (part.kind != null || !part.text.containsHanForOfflineTranslation()) part.text else {
            val first = part.text.indexOfFirst { !it.isWhitespace() }
            val last = part.text.indexOfLast { !it.isWhitespace() }
            val source = part.text.substring(first, last + 1)
            val leading = part.text.substring(0, first)
            val trailing = part.text.substring(last + 1)
            val available = maxChars - output.length - leading.length - trailing.length
            if (available < 1) throw OfflinePromptTranslationSyntaxException(
                "translation_output_size_limit", 0, "Translation exceeded the requested branch limit."
            )
            val translated = translateText(source, available).trim()
            if (translated.isBlank() || translated.length > available ||
                translated.containsHanForOfflineTranslation() || translated.containsUnsafeTranslationCharacters() ||
                !translated.isSafeAsciiDiffusionPrompt()
            ) throw OfflinePromptTranslationSyntaxException(
                "translation_text_invalid", 0, "A translated text fragment is empty, incomplete, or outside safe prompt syntax."
            )
            if (!hasMatchingOfflinePromptStructure(source, translated)) {
                throw OfflinePromptTranslationSyntaxException(
                    "translation_fragment_syntax_changed", 0, "A translated text fragment changed numeric values or introduced control syntax."
                )
            }
            leading + translated + trailing
        }
        if (output.length.toLong() + value.length > maxChars) throw OfflinePromptTranslationSyntaxException(
            "translation_output_size_limit", 0, "Translation exceeded the requested branch limit."
        )
        output.append(value)
    }
    val result = output.toString()
    if (plan.signature() != parseOfflinePromptTranslationSyntax(result, plan.bareControlTokens).signature()) {
        throw OfflinePromptTranslationSyntaxException(
            "translation_structure_changed", 0, "Translation changed control structure, fragment order, or numeric values."
        )
    }
    return result
}

internal fun hasMatchingOfflinePromptStructure(source: String, translated: String): Boolean =
    runCatching {
        val sourcePlan = parseOfflinePromptTranslationSyntax(source)
        sourcePlan.signature() ==
            parseOfflinePromptTranslationSyntax(translated, sourcePlan.bareControlTokens).signature()
    }.getOrDefault(false)

/** Metadata never throws; the service separately reports malformed syntax and retains the source. */
internal fun extractOfflinePromptProtectedTokens(prompt: String): List<String> =
    runCatching { parseOfflinePromptTranslationSyntax(prompt).protectedTokens }.getOrDefault(emptyList())

internal fun String.containsHanForOfflineTranslation(): Boolean =
    codePoints().anyMatch { codePoint ->
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
    }

internal fun String.containsUnsafeTranslationCharacters(): Boolean =
    codePoints().anyMatch { codePoint ->
        // Visible line breaks are valid prompt separators; hidden C0/C1 and format controls are not.
        val isLineBreak = codePoint == '\n'.code || codePoint == '\r'.code
        val type = Character.getType(codePoint)
        !isLineBreak && (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt())
    }

