package com.muyuchat.core.dsh.runtime.log

/** Secret-free log formatting shared by the host process streams and host diagnostics. */
object DshLogRedactor {

    private data class Rule(val pattern: Regex, val replacement: String)

    private val RULES = listOf(
        // Official launch token in URLs, e.g. `?token=...`.
        Rule(Regex("([?&]token=)[^&\\s\"']+", RegexOption.IGNORE_CASE), "$1<redacted>"),
        // Bearer credentials echoed by tools/providers.
        Rule(Regex("(Bearer\\s+)[A-Za-z0-9._~+/=-]+", RegexOption.IGNORE_CASE), "$1<redacted>"),
        // key=value / key: value secret shapes (api key, token, password, secret, authorization).
        Rule(
            Regex(
                "((?:api[_-]?key|access[_-]?token|secret|password|authorization|token)[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"',}]+",
                RegexOption.IGNORE_CASE
            ),
            "$1<redacted>"
        )
    )

    /** Redacts secret shapes and bounds length; safe to keep in a ring buffer or local log. */
    fun redact(text: String, maxChars: Int = 4_096): String {
        var out = text
        for (rule in RULES) out = rule.pattern.replace(out, rule.replacement)
        out = out.filter { it == '\t' || it.code >= 0x20 }
        return if (out.length > maxChars) out.substring(0, maxChars) + "…" else out
    }

    /** One redacted, single-line form of a raw process output chunk/line. */
    fun redactLine(line: String, maxChars: Int = 4_096): String =
        redact(line.trim(), maxChars).replace('\n', ' ').replace('\r', ' ')
}
