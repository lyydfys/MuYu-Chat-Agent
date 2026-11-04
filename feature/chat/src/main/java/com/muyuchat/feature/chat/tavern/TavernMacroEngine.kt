package com.muyuchat.feature.chat.tavern

import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

/** Input cap for one macro expansion, in characters (not UTF-8 bytes). */
const val TAVERN_MACRO_MAX_INPUT_CHARS: Int = 200_000

/** Result cap for one macro expansion, in characters. */
const val TAVERN_MACRO_MAX_OUTPUT_CHARS: Int = 400_000

/** Hard round cap prevents `setvar`/`getvar` chains from expanding forever. */
const val TAVERN_MACRO_MAX_ROUNDS: Int = 3

const val TAVERN_MACRO_INPUT_TRUNCATION_MARKER: String =
    "\n\n[MCA 宏引擎：输入超过 $TAVERN_MACRO_MAX_INPUT_CHARS 字符，已截断]"

const val TAVERN_MACRO_OUTPUT_TRUNCATION_MARKER: String =
    "\n\n[MCA 宏引擎：展开结果超过 $TAVERN_MACRO_MAX_OUTPUT_CHARS 字符，已截断]"

/**
 * Per-session variable namespace used by `getvar`/`setvar`/`addvar`/`incvar`/`decvar`.
 *
 * Implementations are expected to be thread-safe. The default [InMemoryTavernVariableStore] is
 * process-local: variables survive later turns in the same process but are **lost on app restart**.
 * A later persistence pass can replace the implementation without changing the macro engine.
 */
interface TavernVariableStore {
    fun get(sessionId: String, name: String): String?
    fun set(sessionId: String, name: String, value: String)
    fun clear(sessionId: String)
    fun snapshot(sessionId: String): Map<String, String>
}

/** Process-local, synchronized default store. Restarting the app clears every session. */
class InMemoryTavernVariableStore : TavernVariableStore {
    private val lock = Any()
    private val sessions = mutableMapOf<String, MutableMap<String, String>>()

    override fun get(sessionId: String, name: String): String? = synchronized(lock) {
        sessions[sessionId]?.get(name)
    }

    override fun set(sessionId: String, name: String, value: String) {
        synchronized(lock) {
            sessions.getOrPut(sessionId) { mutableMapOf() }[name] = value
        }
    }

    override fun clear(sessionId: String) {
        synchronized(lock) { sessions.remove(sessionId) }
    }

    override fun snapshot(sessionId: String): Map<String, String> = synchronized(lock) {
        sessions[sessionId]?.toMap().orEmpty()
    }
}

/**
 * Values available to role macros in one expansion.
 *
 * [userName] and [persona] are supplied by the caller because MCA currently has no user-persona
 * settings; the app-side adapter uses "User" and an empty persona respectively. [charName] and the
 * card fields are normally taken from the active assistant or its retained character card.
 */
data class TavernMacroContext(
    val sessionId: String,
    val userName: String = "User",
    val charName: String = "",
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val persona: String = ""
)

/**
 * Deterministic, bounded implementation of the SillyTavern text macros MCA activates for a preset.
 *
 * Supported syntax (unknown commands are preserved verbatim so a missing feature is visible instead
 * of silently deleting prompt text):
 *
 * | Macro | Result |
 * | --- | --- |
 * | `{{getvar::name}}` | stored value, or the empty string when unset |
 * | `{{setvar::name::value}}` | stores `value`, expands to the empty string |
 * | `{{addvar::name::value}}` | numeric addition when both sides are numbers, otherwise string concatenation; empty output |
 * | `{{incvar::name}}` / `{{decvar::name}}` | adds/subtracts one (missing/non-numeric starts at 0) and returns the new value |
 * | `{{user}}` `{{char}}` `{{description}}` `{{personality}}` `{{scenario}}` `{{persona}}` | values from [TavernMacroContext] |
 * | `{{random::a,b,c}}` / `{{pick::a,b}}` | one uniformly chosen option; a single option is returned unchanged |
 * | `{{roll:1d20}}` | sum of the rolled dice; invalid expressions are preserved verbatim |
 * | `{{time}}` / `{{date}}` | `HH:mm` / `yyyy-MM-dd` from the injected [Clock] |
 * | `{{newline}}` | a newline |
 * | `{{// comment}}` | removed |
 * | `\{{...}}` | the opening braces become literal; the macro is **not** expanded |
 *
 * Differences from SillyTavern: `{{pick}}` is treated as uniform random (Tavern some releases use
 * a seeded/weighted variant); `addvar` is numeric only when both operands parse as numbers; `incvar`
 * and `decvar` return the new value; `{{time}}`/`{{date}}` use the device clock and a fixed ISO-like
 * date format. Variables are per session and process-local.
 *
 * Safety: at most [maxRounds] expansion rounds, [TAVERN_MACRO_MAX_INPUT_CHARS] input characters and
 * [TAVERN_MACRO_MAX_OUTPUT_CHARS] result characters. Truncation appends a visible marker.
 */
class TavernMacroEngine(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val randomSeedProvider: () -> Long = { System.nanoTime() },
    private val variables: TavernVariableStore = InMemoryTavernVariableStore(),
    private val maxRounds: Int = TAVERN_MACRO_MAX_ROUNDS
) {
    /**
     * Expands [text] for one turn. The same store instance must be reused across turns if variables
     * are expected to survive; the engine itself is stateless apart from that injected store.
     */
    fun expand(text: String, context: TavernMacroContext): String {
        if (text.isEmpty()) return text
        var input = text
        if (input.length > TAVERN_MACRO_MAX_INPUT_CHARS) {
            input = input.take(TAVERN_MACRO_MAX_INPUT_CHARS) + TAVERN_MACRO_INPUT_TRUNCATION_MARKER
        }
        // Hide escaped openings before the macro regex sees them. The sentinel cannot contain
        // "{{", so an escaped macro can never be expanded by any round.
        var current = input.replace("\\{{", ESCAPED_OPEN)
        val random = Random(randomSeedProvider())
        var round = 0
        while (round < maxRounds) {
            val next = expandOnce(current, context, random)
            round += 1
            if (next == current) break
            current = next
        }
        var result = current.replace(ESCAPED_OPEN, "{{")
        if (result.length > TAVERN_MACRO_MAX_OUTPUT_CHARS) {
            result = result.take(TAVERN_MACRO_MAX_OUTPUT_CHARS) + TAVERN_MACRO_OUTPUT_TRUNCATION_MARKER
        }
        return result
    }

    private fun expandOnce(
        text: String,
        context: TavernMacroContext,
        random: Random
    ): String {
        val output = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (text[index] == '{' && index + 1 < text.length && text[index + 1] == '{') {
                val end = findMacroEnd(text, index + 2)
                if (end >= 0) {
                    val raw = text.substring(index + 2, end)
                    output.append(resolve(raw, context, random))
                    index = end + 2
                    continue
                }
            }
            output.append(text[index])
            index += 1
        }
        return output.toString()
    }

    /**
     * Finds the `}}` that closes a macro, counting nested `{{...}}` pairs so a value such as
     * `{{setvar::x::{{getvar::y}}}}` is passed to [resolve] as one macro instead of being cut at
     * the inner close. Returns the index of the first `}` of the closing pair, or -1 when unclosed.
     */
    private fun findMacroEnd(text: String, start: Int): Int {
        var depth = 1
        var cursor = start
        while (cursor < text.length - 1) {
            when {
                text[cursor] == '{' && text[cursor + 1] == '{' -> {
                    depth += 1
                    cursor += 2
                }

                text[cursor] == '}' && text[cursor + 1] == '}' -> {
                    depth -= 1
                    if (depth == 0) return cursor
                    cursor += 2
                }

                else -> cursor += 1
            }
        }
        return -1
    }

    private fun resolve(raw: String, context: TavernMacroContext, random: Random): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("//")) return ""

        // Tavern's roll syntax uses one colon: {{roll:1d20}}.
        if (trimmed.startsWith("roll:", ignoreCase = true)) {
            val rolled = roll(trimmed.substringAfter(':').trim(), random)
            return rolled ?: "{{$raw}}"
        }

        val delimiter = trimmed.indexOf("::")
        val command = if (delimiter < 0) trimmed else trimmed.substring(0, delimiter).trim()
        val rest = if (delimiter < 0) "" else trimmed.substring(delimiter + 2)
        return when (command.lowercase(Locale.ROOT)) {
            "getvar" -> {
                val name = rest.trim()
                if (name.isEmpty()) "{{$raw}}" else variables.get(context.sessionId, name).orEmpty()
            }

            "setvar" -> {
                val pair = splitNameValue(rest)
                if (pair == null || pair.first.isEmpty()) {
                    "{{$raw}}"
                } else {
                    variables.set(context.sessionId, pair.first, pair.second)
                    ""
                }
            }

            "addvar" -> {
                val pair = splitNameValue(rest)
                if (pair == null || pair.first.isEmpty()) {
                    "{{$raw}}"
                } else {
                    val currentValue = variables.get(context.sessionId, pair.first).orEmpty()
                    variables.set(context.sessionId, pair.first, addValues(currentValue, pair.second))
                    ""
                }
            }

            "incvar" -> {
                val name = rest.trim()
                if (name.isEmpty()) {
                    "{{$raw}}"
                } else {
                    val currentValue = variables.get(context.sessionId, name)
                    val next = stepNumber(currentValue, 1.0)
                    variables.set(context.sessionId, name, next)
                    next
                }
            }

            "decvar" -> {
                val name = rest.trim()
                if (name.isEmpty()) {
                    "{{$raw}}"
                } else {
                    val currentValue = variables.get(context.sessionId, name)
                    val next = stepNumber(currentValue, -1.0)
                    variables.set(context.sessionId, name, next)
                    next
                }
            }

            "random", "pick" -> pick(rest, random) ?: "{{$raw}}"

            "user" -> context.userName
            "char" -> context.charName
            "description" -> context.description
            "personality" -> context.personality
            "scenario" -> context.scenario
            "persona" -> context.persona
            "newline" -> "\n"
            "time" -> ZonedDateTime.now(clock).format(TIME_FORMAT)
            "date" -> ZonedDateTime.now(clock).format(DATE_FORMAT)

            else -> "{{$raw}}"
        }
    }

    private fun splitNameValue(rest: String): Pair<String, String>? {
        val delimiter = rest.indexOf("::")
        if (delimiter < 0) {
            return rest.trim().takeIf(String::isNotEmpty)?.let { it to "" }
        }
        val name = rest.substring(0, delimiter).trim()
        if (name.isEmpty()) return null
        return name to rest.substring(delimiter + 2)
    }

    private fun addValues(current: String, value: String): String {
        val left = current.trim().toDoubleOrNull()
        val right = value.trim().toDoubleOrNull()
        return if (left != null && right != null) formatNumber(left + right) else current + value
    }

    private fun stepNumber(current: String?, delta: Double): String =
        formatNumber((current?.trim()?.toDoubleOrNull() ?: 0.0) + delta)

    private fun formatNumber(value: Double): String {
        if (!value.isFinite()) return value.toString()
        if (value == value.toLong().toDouble()) return value.toLong().toString()
        return value.toString()
    }

    private fun pick(rest: String, random: Random): String? {
        if (rest.isEmpty()) return null
        val options = rest.split(',').map(String::trim)
        if (options.isEmpty() || options.all(String::isEmpty)) return null
        return options[random.nextInt(options.size)]
    }

    private fun roll(expression: String, random: Random): String? {
        val match = ROLL_PATTERN.matchEntire(expression) ?: return null
        val count = match.groupValues[1].toIntOrNull() ?: return null
        val sides = match.groupValues[2].toIntOrNull() ?: return null
        if (count !in 1..100 || sides !in 2..1_000) return null
        var total = 0
        repeat(count) { total += random.nextInt(sides) + 1 }
        return total.toString()
    }

    private companion object {
        const val ESCAPED_OPEN = "\uE000"
        val ROLL_PATTERN = Regex("""^(\d{1,3})d(\d{1,4})$""", RegexOption.IGNORE_CASE)
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    }
}
