package com.muyuchat.mca.agent.toolpack

/**
 * Normalises the supported HJSON subset of a third-party `manifest.hjson` to strict JSON text.
 *
 * The output is fed to the existing strict JSON reader ([com.muyuchat.mca.agent.extensions.toolpkg.ToolPackageJson]),
 * so duplicate keys, lone surrogates and canonical ordering keep exactly one implementation. This
 * normaliser only performs the syntax translation:
 *
 * - line comments (`#`, `//`) and block comments are removed outside quoted strings;
 * - object keys may be unquoted, and commas between members/elements are optional;
 * - quoted strings use JSON escapes; unquoted strings are single-line tokens;
 * - YAML anchors/aliases and HJSON multiline strings (`'''`) are rejected, not guessed;
 * - numbers are only emitted as JSON numbers when they are integers (the manifest schema has no
 *   floating point); everything else is emitted as a JSON string and validated by field type.
 *
 * Every failure is a stable [ThirdPartyImportErrorCode] plus the exact manifest field path.
 */
internal object ThirdPartyHjsonManifest {

    fun toStrictJson(text: String): String {
        if (text.startsWith('\uFEFF')) {
            thirdpartyFailure(ThirdPartyImportErrorCode.MANIFEST_MALFORMED, "manifest", "HJSON does not accept a UTF-8 byte-order mark.")
        }
        val reader = HjsonReader(text)
        return reader.readDocument()
    }

    private class HjsonReader(private val text: String) {
        private var index = 0

        fun readDocument(): String {
            skipTrivia("manifest")
            if (!consume('{')) {
                thirdpartyFailure(
                    ThirdPartyImportErrorCode.MANIFEST_MALFORMED,
                    "manifest",
                    "the HJSON manifest root must be an object."
                )
            }
            val body = readObjectBody("manifest", depth = 0)
            skipTrivia("manifest")
            if (index != text.length) {
                thirdpartyFailure(
                    ThirdPartyImportErrorCode.MANIFEST_MALFORMED,
                    "manifest",
                    "the HJSON manifest has trailing content after the root object."
                )
            }
            return body
        }

        private fun readValue(path: String, depth: Int): String {
            if (depth > MAX_DEPTH) {
                thirdpartyFailure(ThirdPartyImportErrorCode.MANIFEST_MALFORMED, path, "manifest nesting exceeds the depth budget.")
            }
            if (index >= text.length) {
                thirdpartyFailure(ThirdPartyImportErrorCode.MANIFEST_MALFORMED, path, "the manifest ends before a value.")
            }
            return when (text[index]) {
                '{' -> {
                    index += 1
                    readObjectBody(path, depth + 1)
                }
                '[' -> {
                    index += 1
                    readArrayBody(path, depth + 1)
                }
                '"' -> quote(readQuotedString(path))
                '\'' -> {
                    if (isMultilineStart()) {
                        thirdpartyFailure(
                            ThirdPartyImportErrorCode.MANIFEST_MALFORMED,
                            path,
                            "HJSON multiline strings are outside the supported subset."
                        )
                    }
                    quote(readSingleQuotedString(path))
                }
                else -> readScalar(path)
            }
        }

        private fun readScalar(path: String): String {
            val token = readUnquotedToken(path)
            if (token.isEmpty()) {
                thirdpartyFailure(ThirdPartyImportErrorCode.MANIFEST_MALFORMED, path, "the manifest is missing a field value.")
            }
            if (token.startsWith("&") || token.startsWith("*") || token == "<<") {
                thirdpartyFailure(
                    ThirdPartyImportErrorCode.MANIFEST_MALFORMED,
                    path,
                    "HJSON anchors/aliases are outside the supported subset."
                )
            }
            return when {
                token == "true" || token == "false" || token == "null" -> token
                INTEGER.matches(token) -> token
                else -> quote(token)
            }
        }

        private fun readObjectBody(path: String, depth: Int): String {
            val builder = StringBuilder("{")
            val seen = HashSet<String>()
            var first = true
            while (true) {
                skipTrivia(path)
                if (index >= text.length) {
                    thirdpartyFailure(ThirdPartyImportErrorCode.MANIFEST_MALFORMED, path, "the HJSON object is not closed.")
                }
                if (text[index] == '}') {
                    index += 1
                    break
                }
                if (!first) builder.append(',')
                first = false
                val key = readKey(path)
                if (!seen.add(key)) {
                    thirdpartyFailure(
                        ThirdPartyImportErrorCode.MANIFEST_DUPLICATE_KEY,
                        "$path.${safeKey(key)}",
                        "the HJSON object repeats a field."
                    )
                }
                skipTrivia(path)
                if (!consume(':')) {
                    thirdpartyFailure(
                        ThirdPartyImportErrorCode.MANIFEST_MALFORMED,
                        "$path.${safeKey(key)}",
                        "a HJSON object field is missing ':'."
                    )
                }
                skipTrivia(path)
                builder.append(quote(key)).append(':')
                builder.append(readValue("$path.${safeKey(key)}", depth))
                skipTrivia(path)
                if (index < text.length && text[index] == ',') {
                    index += 1
                }
            }
            return builder.append('}').toString()
        }

        private fun readArrayBody(path: String, depth: Int): String {
            val builder = StringBuilder("[")
            var first = true
            var item = 0
            while (true) {
                skipTrivia(path)
                if (index >= text.length) {
                    thirdpartyFailure(ThirdPartyImportErrorCode.MANIFEST_MALFORMED, path, "the HJSON array is not closed.")
                }
                if (text[index] == ']') {
                    index += 1
                    break
                }
                if (!first) builder.append(',')
                first = false
                builder.append(readValue("$path[$item]", depth))
                item += 1
                skipTrivia(path)
                if (index < text.length && text[index] == ',') {
                    index += 1
                }
            }
            return builder.append(']').toString()
        }

        private fun readKey(path: String): String {
            if (index < text.length && text[index] == '"') {
                return readQuotedString(path)
            }
            if (index < text.length && text[index] == '\'') {
                return readSingleQuotedString(path)
            }
            val start = index
            while (index < text.length) {
                val current = text[index]
                if (current == ':' || current == '\n' || current == '\r' || current == ',' ||
                    current == '}' || current == '{' || current == '[' || current == ']' ||
                    current == '#' || current.isISOControl()
                ) {
                    break
                }
                index += 1
            }
            val key = text.substring(start, index).trim()
            if (key.isEmpty() || key.any { it.isWhitespace() } || !KEY.matches(key)) {
                thirdpartyFailure(
                    ThirdPartyImportErrorCode.MANIFEST_MALFORMED,
                    path,
                    "the HJSON field name is invalid; quote names with spaces or special characters."
                )
            }
            return key
        }

        private fun readQuotedString(path: String): String {
            if (!consume('"')) {
                thirdpartyFailure(ThirdPartyImportErrorCode.MANIFEST_MALFORMED, path, "a string is missing its opening quote.")
            }
            val builder = StringBuilder()
            while (index < text.length) {
                val current = text[index]
                index += 1
                when {
                    current == '"' -> return builder.toString()
                    current == '\\' -> builder.append(readEscape(path))
                    current < ' ' -> thirdpartyFailure(
                        ThirdPartyImportErrorCode.MANIFEST_MALFORMED,
                        path,
                        "a string contains an unescaped control character."
                    )
                    else -> builder.append(current)
                }
            }
            thirdpartyFailure(ThirdPartyImportErrorCode.MANIFEST_MALFORMED, path, "a string is not closed.")
        }

        private fun readSingleQuotedString(path: String): String {
            if (!consume('\'')) {
                thirdpartyFailure(ThirdPartyImportErrorCode.MANIFEST_MALFORMED, path, "a string is missing its opening quote.")
            }
            val builder = StringBuilder()
            while (index < text.length) {
                val current = text[index]
                index += 1
                when {
                    current == '\'' -> return builder.toString()
                    current == '\\' -> builder.append(readEscape(path))
                    current < ' ' -> thirdpartyFailure(
                        ThirdPartyImportErrorCode.MANIFEST_MALFORMED,
                        path,
                        "a string contains an unescaped control character."
                    )
                    else -> builder.append(current)
                }
            }
            thirdpartyFailure(ThirdPartyImportErrorCode.MANIFEST_MALFORMED, path, "a string is not closed.")
        }

        private fun isMultilineStart(): Boolean = index + 2 < text.length &&
            text[index] == '\'' && text[index + 1] == '\'' && text[index + 2] == '\''

        private fun readEscape(path: String): Char {
            if (index >= text.length) {
                thirdpartyFailure(ThirdPartyImportErrorCode.MANIFEST_MALFORMED, path, "a string escape is incomplete.")
            }
            val escaped = text[index]
            index += 1
            return when (escaped) {
                '"' -> '"'
                '\'' -> '\''
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> readUnicodeEscape(path)
                else -> thirdpartyFailure(
                    ThirdPartyImportErrorCode.MANIFEST_MALFORMED,
                    path,
                    "a string contains an unsupported escape."
                )
            }
        }

        private fun readUnicodeEscape(path: String): Char {
            if (index + 4 > text.length) {
                thirdpartyFailure(ThirdPartyImportErrorCode.MANIFEST_MALFORMED, path, "the Unicode escape is incomplete.")
            }
            val digits = text.substring(index, index + 4)
            if (!HEX.matches(digits)) {
                thirdpartyFailure(ThirdPartyImportErrorCode.MANIFEST_MALFORMED, path, "the Unicode escape is not hexadecimal.")
            }
            index += 4
            return digits.toInt(16).toChar()
        }

        private fun readUnquotedToken(path: String): String {
            val start = index
            while (index < text.length) {
                val current = text[index]
                if (current == ',' || current == '}' || current == ']' ||
                    current == '\n' || current == '\r' || current == '#' ||
                    current == '/' && index + 1 < text.length &&
                    (text[index + 1] == '/' || text[index + 1] == '*')
                ) {
                    break
                }
                if (current.isISOControl()) {
                    thirdpartyFailure(
                        ThirdPartyImportErrorCode.MANIFEST_MALFORMED,
                        path,
                        "an unquoted string contains a control character."
                    )
                }
                index += 1
            }
            return text.substring(start, index).trim()
        }

        private fun skipTrivia(path: String) {
            while (index < text.length) {
                val current = text[index]
                when {
                    current == ' ' || current == '\t' || current == '\n' || current == '\r' -> index += 1
                    current == '#' -> skipToLineEnd()
                    current == '/' && index + 1 < text.length && text[index + 1] == '/' -> skipToLineEnd()
                    current == '/' && index + 1 < text.length && text[index + 1] == '*' -> {
                        index += 2
                        var closed = false
                        while (index + 1 < text.length) {
                            if (text[index] == '*' && text[index + 1] == '/') {
                                index += 2
                                closed = true
                                break
                            }
                            index += 1
                        }
                        if (!closed) {
                            thirdpartyFailure(
                                ThirdPartyImportErrorCode.MANIFEST_MALFORMED,
                                path,
                                "a block comment is not closed."
                            )
                        }
                    }
                    else -> return
                }
            }
        }

        private fun skipToLineEnd() {
            while (index < text.length && text[index] != '\n' && text[index] != '\r') {
                index += 1
            }
        }

        private fun consume(expected: Char): Boolean {
            if (index >= text.length || text[index] != expected) return false
            index += 1
            return true
        }

        private fun quote(value: String): String = buildString(value.length + 2) {
            append('"')
            value.forEach { character ->
                when {
                    character == '"' -> append("\\\"")
                    character == '\\' -> append("\\\\")
                    character == '\b' -> append("\\b")
                    character == '\u000C' -> append("\\f")
                    character == '\n' -> append("\\n")
                    character == '\r' -> append("\\r")
                    character == '\t' -> append("\\t")
                    character < ' ' || character in '\u007F'..'\u009F' ->
                        append("\\u").append(character.code.toString(16).padStart(4, '0'))
                    else -> append(character)
                }
            }
            append('"')
        }

        private fun safeKey(key: String): String = buildString {
            for (character in key.take(64)) {
                append(if (character.isISOControl() || character == '\'' || character == '"') '?' else character)
            }
        }
    }

    private const val MAX_DEPTH = 32
    private val INTEGER = Regex("^-?(0|[1-9][0-9]{0,18})$")
    private val HEX = Regex("^[0-9A-Fa-f]{4}$")
    private val KEY = Regex("^[A-Za-z0-9_][A-Za-z0-9_.-]*$")
}
