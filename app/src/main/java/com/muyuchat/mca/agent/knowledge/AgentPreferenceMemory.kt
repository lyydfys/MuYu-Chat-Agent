package com.muyuchat.mca.agent.knowledge

/**
 * One explicit user preference ("以后都用中文回答").
 *
 * Only what the user stated is stored — never an inference. [source] records where it came from
 * (`"user"`, `"settings"`, a task id) so a later cleanup can tell a deliberate statement from an
 * extracted one, and it never contains message text.
 */
data class AgentPreference(
    val id: String,
    val text: String,
    val source: String,
    val createdAt: Long
)

/**
 * Bounded in-memory store of explicit user preferences.
 *
 * Why not persistence: this is the *extraction* slice. A durable store needs an owner, a schema and
 * a user-visible delete path; inventing a second settings file here would create state nobody can
 * inspect or clear. The class is deliberately a plain object so a future owner can wrap it.
 *
 * Rules:
 *  - **deduplicated** by normalized text (case/whitespace/全角 punctuation differences collapse), so
 *    saying the same thing twice does not double the prompt budget;
 *  - **bounded** by [maxPrefs]; when full, the oldest entry is evicted (a preference list is used
 *    newest-first in the prompt, so dropping the oldest is the least surprising rule);
 *  - [add] returns false for empty text, duplicates and evicted-into-oblivion input, so the caller can
 *    report "已记住" honestly.
 */
class AgentPreferenceMemory(private val maxPrefs: Int = DEFAULT_MAX_PREFS) {

    private val lock = Any()
    private val entries = ArrayList<AgentPreference>(maxPrefs.coerceAtLeast(0))
    private var sequence = 0L

    init {
        require(maxPrefs >= 0) { "maxPrefs must not be negative" }
    }

    /** Current preferences, oldest first. */
    fun all(): List<AgentPreference> = synchronized(lock) { entries.toList() }

    val size: Int get() = synchronized(lock) { entries.size }

    fun isEmpty(): Boolean = synchronized(lock) { entries.isEmpty() }

    /**
     * Adds one preference. Returns false when the text is blank, or when an identical preference is
     * already stored (nothing changes in that case — the existing entry keeps its original position).
     */
    fun add(text: String, source: String = DEFAULT_SOURCE, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return false
        if (maxPrefs == 0) return false
        synchronized(lock) {
            if (entries.any { normalize(it.text) == normalized }) return false
            while (entries.size >= maxPrefs) {
                entries.removeAt(0)
            }
            sequence += 1
            entries += AgentPreference(
                id = "pref-$sequence",
                text = text.trim(),
                source = source,
                createdAt = nowMillis
            )
            return true
        }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /**
     * Prompt block for the current preferences; empty string when there is nothing to inject.
     *
     * Newest-first, one bullet per line, hard-capped at [maxChars]. A preference that does not fit is
     * dropped whole (never cut mid-sentence). The "omitted" notice is **reserved before** the lines are
     * fitted, so it is never the thing that gets cut off — the model must not be told it saw the full
     * list when it did not.
     */
    fun asPromptText(maxChars: Int = DEFAULT_PROMPT_CHARS): String {
        if (maxChars <= 0) return ""
        val snapshot = synchronized(lock) { entries.toList() }
        if (snapshot.isEmpty()) return ""
        val newestFirst = snapshot.asReversed().map { entry ->
            "- " + entry.text.replace('\n', ' ').trim()
        }
        if (HEADER.length >= maxChars) return ""
        // Walk from "all fit" down to "only the newest fits", reserving the notice for the rest.
        for (keep in newestFirst.size downTo 1) {
            val omitted = newestFirst.size - keep
            val notice = if (omitted > 0) "\n（另有 $omitted 条偏好因长度限制未显示）" else ""
            val body = newestFirst.take(keep)
            val total = HEADER.length + notice.length + body.sumOf { it.length + 1 }
            if (total <= maxChars) {
                return buildString {
                    append(HEADER)
                    body.forEach { line -> append('\n').append(line) }
                    append(notice)
                }
            }
        }
        return ""
    }

    companion object {
        const val DEFAULT_MAX_PREFS: Int = 50
        const val DEFAULT_PROMPT_CHARS: Int = 1000
        const val DEFAULT_SOURCE: String = "user"
        const val HEADER: String = "用户明确说过的偏好（仅当与当前任务不冲突时遵守）："
    }

    /** Case-, whitespace- and trailing-punctuation-insensitive key used for de-duplication. */
    private fun normalize(value: String): String = value
        .trim()
        .trim('。', '，', ',', '.', '！', '!', '？', '?')
        .trim()
        .replace('\u3000', ' ')
        .replace(Regex("\\s+"), " ")
        .lowercase()
}

/**
 * Rule-based extraction of *explicit* preference statements from one user message.
 *
 * Deliberately not a model and not a heuristic guesser: it only fires on the four phrasings a user
 * uses to state a standing instruction, and returns the sentence as written (trimmed). If a message
 * does not match one of them, nothing is extracted — a missed preference is recoverable, an invented
 * one silently changes every later answer.
 *
 * Privacy: a candidate is dropped when it looks like a secret (password/code/card number/PIN/密钥),
 * even if it also matches a trigger phrase, because "记住我的密码是..." must never be stored.
 */
object AgentPreferenceExtractor {

    /** A preference longer than this is truncated: it is a statement, not a document. */
    const val MAX_PREFERENCE_CHARS: Int = 200

    /** At most this many candidates from one message; longer lists are noise, not preferences. */
    const val MAX_PREFERENCES_PER_MESSAGE: Int = 3

    /** The four explicit phrasings. Order matters: the first match wins per sentence. */
    private val TRIGGERS: List<Regex> = listOf(
        Regex("以后都[^。！？!?\\n]{1,}$"),
        Regex("(?:请)?记住[：:]?\\s*[^。！？!?\\n]{1,}$"),
        Regex("(?:请)?不要再[^。！？!?\\n]{1,}$"),
        Regex("(?:请)?始终[^。！？!?\\n]{1,}$"),
        Regex("(?:以后|下次|今后)[^。！？!?\\n]{1,}$")
    )

    /** Sentences containing these are never stored, whatever else they say. */
    private val SECRET_MARKERS: List<String> = listOf(
        "密码", "口令", "验证码", "短信码", "动态码", "卡号", "银行卡", "身份证", "社保号",
        "密钥", "私钥", "api key", "apikey", "token", "secret", "cvv", "pin码", "password"
    )

    private val CARD_NUMBER = Regex("\\b(?:\\d[ -]?){13,19}\\b")
    private val CJK = Regex("[\\u4e00-\\u9fff]")

    /**
     * Extracts at most [MAX_PREFERENCES_PER_MESSAGE] explicit preferences.
     *
     * The message is split on punctuation first, so "今天天气不错。以后都用中文回答。" yields the second
     * half only; a trigger that starts mid-sentence still matches within its own sentence.
     */
    fun extract(userMessage: String): List<String> {
        if (userMessage.isBlank()) return emptyList()
        val results = LinkedHashSet<String>()
        userMessage
            .split(SENTENCE_BREAKS)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { sentence ->
                if (results.size >= MAX_PREFERENCES_PER_MESSAGE) return@forEach
                val match = TRIGGERS.asSequence()
                    .mapNotNull { trigger -> trigger.find(sentence)?.value }
                    .firstOrNull()
                    ?: return@forEach
                val candidate = clean(match)
                if (candidate.isEmpty()) return@forEach
                if (isSecret(candidate)) return@forEach
                results += candidate
            }
        return results.toList()
    }

    /** True when the sentence looks like a credential; exposed so the rule is testable directly. */
    fun isSecret(text: String): Boolean {
        val lowered = text.lowercase()
        if (SECRET_MARKERS.any { marker -> lowered.contains(marker) }) return true
        // A bare 13-19 digit run is a card/account number, not a preference.
        return CARD_NUMBER.containsMatchIn(text)
    }

    private fun clean(raw: String): String {
        var trimmed = raw
            .trim()
            .trim('，', ',', '。', '.', '！', '!', '？', '?', '；', ';', '、', ' ', '\n', '\t')
            .trim()
        // The command word itself is not part of the preference: "请记住：我不喜欢表格" stores
        // "记住我不喜欢表格", not the colon and not the leading 请.
        trimmed = trimmed.removePrefix("请")
        trimmed = trimmed.replace(Regex("^(记住|请记住)[：:]\\s*"), "$1")
        trimmed = trimmed.trim()
        if (trimmed.isEmpty()) return ""
        // Politeness that trails the statement is not part of it: "以后都用中文回答，谢谢" states one
        // preference. Only trailing fillers are removed, and never the whole candidate.
        trimmed = trimTrailingPoliteness(trimmed)
        if (trimmed.isEmpty()) return ""
        // A pure trigger without content ("记住", "以后都") is not a preference.
        if (trimmed.lowercase() in BARE_TRIGGERS) return ""
        if (trimmed.length <= 2) return ""
        if (!CJK.containsMatchIn(trimmed) && trimmed.length <= 3) return ""
        return if (trimmed.length <= MAX_PREFERENCE_CHARS) trimmed else trimmed.take(MAX_PREFERENCE_CHARS)
    }

    /** Trigger words on their own: a user said "记住" but stated nothing to remember. */
    private val BARE_TRIGGERS: Set<String> = setOf(
        "记住", "请记住", "以后", "以后都", "下次", "今后", "始终", "请始终", "不要再", "不要"
    )

    /** Trailing courtesy words removed from an extracted statement ("…回答，谢谢"). */
    private val TRAILING_POLITENESS: List<Regex> = listOf(
        Regex("[，,]?\\s*(谢谢|谢谢你|多谢|感谢|拜托|麻烦了|辛苦了|好吗|可以吗|行吗|谢谢啦)\\s*$"),
        Regex("[，,]?\\s*(please|thanks|thank you)\\s*$", RegexOption.IGNORE_CASE)
    )

    private fun trimTrailingPoliteness(value: String): String {
        var result = value
        var changed = true
        while (changed) {
            changed = false
            TRAILING_POLITENESS.forEach { pattern ->
                val next = pattern.replace(result, "")
                    .trim()
                    .trim('，', ',', '。', '.', '！', '!', '？', '?', '；', ';', '、')
                    .trim()
                if (next != result) {
                    result = next
                    changed = true
                }
            }
        }
        return result
    }

    private val SENTENCE_BREAKS = Regex("[。！？!?；;\\n\\r]+")
}
