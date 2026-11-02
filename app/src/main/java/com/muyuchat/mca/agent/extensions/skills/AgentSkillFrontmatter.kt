package com.muyuchat.mca.agent.extensions.skills

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Metadata produced by other harnesses; preserved verbatim but never treated as authorization. */
sealed interface AgentSkillMetadataValue {
    data class Text(val value: String) : AgentSkillMetadataValue

    data class ListValue(val values: List<String>) : AgentSkillMetadataValue
}

data class AgentSkillFrontmatter(
    val name: String,
    val description: String,
    val packageVersion: String,
    /** `null` means the document did not declare allowed-tools (no additional restriction). */
    val allowedTools: AgentSkillToolDeclaration?,
    val metadata: Map<String, AgentSkillMetadataValue>
)

data class AgentSkillParsedDocument(
    val frontmatter: AgentSkillFrontmatter,
    val body: String
)

/**
 * Restricted SKILL.md frontmatter parser (architecture 12.1).
 *
 * Supported: single-line plain/quoted strings, string lists (block or inline), and explicit literal
 * (`|`) / folded (`>`) block scalars with optional `-` chomping. Rejected with typed errors: YAML
 * tags, anchors/aliases, flow mappings, nested mappings, duplicate keys, tabs and `|+`/`>+`.
 * Unknown keys are preserved as [AgentSkillFrontmatter.metadata] and never interpreted.
 */
class AgentSkillFrontmatterParser {

    fun parse(skillMarkdown: ByteArray): AgentSkillParsedDocument {
        if (skillMarkdown.size > AgentSkillLimits.MAX_SKILL_MD_BYTES) {
            skillFailure(
                AgentSkillErrorCode.SKILL_MD_TOO_LARGE,
                "SKILL.md exceeds ${AgentSkillLimits.MAX_SKILL_MD_BYTES} bytes"
            )
        }
        val text = decodeUtf8(skillMarkdown).removePrefix("\uFEFF")
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        if (lines.isEmpty() || lines[0].trimEnd() != FRONTMATTER_FENCE) {
            skillFailure(AgentSkillErrorCode.FRONTMATTER_MISSING, "SKILL.md must start with a '---' frontmatter block")
        }
        val closeIndex = (1 until lines.size).firstOrNull { lines[it].trimEnd() == FRONTMATTER_FENCE }
            ?: skillFailure(AgentSkillErrorCode.FRONTMATTER_MISSING, "frontmatter block is not terminated by '---'")

        val accumulator = FrontmatterAccumulator()
        var index = 1
        while (index < closeIndex) {
            val line = lines[index]
            if (line.isBlank()) {
                index += 1
                continue
            }
            if (line.startsWith("#")) {
                index += 1
                continue
            }
            if (line.startsWith(" ") || line.startsWith("\t")) {
                unsupported(index + 1, "unexpected indentation at frontmatter top level")
            }
            if (line == FRONTMATTER_FENCE || line == "...") {
                unsupported(index + 1, "document markers are not supported inside frontmatter")
            }
            if (line.startsWith("%")) {
                unsupported(index + 1, "YAML directives are not supported")
            }
            val colon = line.indexOf(':')
            if (colon <= 0) {
                unsupported(index + 1, "frontmatter line must be 'key: value'")
            }
            val key = line.substring(0, colon).trim()
            if (!KEY_PATTERN.matches(key)) {
                skillFailure(
                    AgentSkillErrorCode.FRONTMATTER_UNSUPPORTED,
                    "unsupported frontmatter key: '$key'",
                    line = index + 1
                )
            }
            val valueText = line.substring(colon + 1).trim()
            index += 1

            val value: AgentSkillMetadataValue = when {
                valueText.startsWith("|") || valueText.startsWith(">") -> {
                    val block = readBlockScalar(lines, closeIndex, index, valueText, index + 1)
                    index = block.nextIndex
                    AgentSkillMetadataValue.Text(block.text)
                }
                valueText.startsWith("[") -> AgentSkillMetadataValue.ListValue(
                    parseInlineList(valueText, index + 1)
                )
                valueText.isEmpty() -> {
                    val blockList = readBlockList(lines, closeIndex, index)
                    index = blockList.nextIndex
                    if (blockList.items != null) {
                        AgentSkillMetadataValue.ListValue(blockList.items)
                    } else {
                        AgentSkillMetadataValue.Text("")
                    }
                }
                else -> AgentSkillMetadataValue.Text(decodeScalar(valueText, index + 1))
            }
            accumulator.put(key, value, index)
        }

        return AgentSkillParsedDocument(
            frontmatter = accumulator.toFrontmatter(),
            body = lines.drop(closeIndex + 1).joinToString("\n")
        )
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (failure: CharacterCodingException) {
            skillFailure(AgentSkillErrorCode.INVALID_UTF8, "SKILL.md is not valid UTF-8")
        }
    }

    private fun readBlockScalar(
        lines: List<String>,
        closeIndex: Int,
        startIndex: Int,
        indicator: String,
        lineNumber: Int
    ): BlockScalar {
        if (!BLOCK_INDICATOR.matches(indicator)) {
            unsupported(lineNumber, "unsupported block scalar indicator '$indicator'")
        }
        val folded = indicator.startsWith(">")
        val strip = indicator.endsWith("-")
        var index = startIndex
        val rawLines = ArrayList<String>()
        while (index < closeIndex) {
            val line = lines[index]
            if (line.isBlank()) {
                rawLines += ""
                index += 1
                continue
            }
            if (!line.startsWith(" ")) break
            rawLines += line
            index += 1
        }
        val nonBlank = rawLines.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) {
            return BlockScalar(if (strip) "" else "\n", index)
        }
        val indent = nonBlank.minOf { it.indexOfFirst { ch -> ch != ' ' } }
        if (indent <= 0) {
            unsupported(lineNumber, "block scalar content must be indented")
        }
        val stripped = rawLines.map { line ->
            if (line.isBlank()) "" else {
                if (line.length < indent || line.take(indent).any { it != ' ' }) {
                    unsupported(lineNumber, "block scalar indentation is inconsistent")
                }
                line.substring(indent)
            }
        }
        val joined = if (folded) fold(stripped) else stripped.joinToString("\n")
        val text = if (strip) joined.trimEnd('\n') else joined.trimEnd('\n') + "\n"
        return BlockScalar(text, index)
    }

    private fun fold(lines: List<String>): String {
        val result = StringBuilder()
        var previousBlank = true
        for (line in lines) {
            if (line.isBlank()) {
                result.append('\n')
                previousBlank = true
            } else {
                if (!previousBlank) result.append(' ')
                result.append(line)
                previousBlank = false
            }
        }
        return result.toString()
    }

    private fun readBlockList(lines: List<String>, closeIndex: Int, startIndex: Int): BlockList {
        var index = startIndex
        var sawIndent = false
        while (index < closeIndex && lines[index].isBlank()) {
            index += 1
        }
        val items = ArrayList<String>()
        while (index < closeIndex) {
            val line = lines[index]
            if (line.isBlank()) {
                index += 1
                continue
            }
            if (!line.startsWith(" ")) break
            val trimmed = line.trimStart(' ')
            if (!trimmed.startsWith("-")) {
                unsupported(index + 1, "nested mappings are not supported in frontmatter")
            }
            if (trimmed == "-" || trimmed.startsWith("- ").not()) {
                unsupported(index + 1, "list items must be '- value'")
            }
            val itemText = trimmed.removePrefix("- ").trim()
            if (itemText.isEmpty()) {
                unsupported(index + 1, "empty list items are not supported")
            }
            if (itemText.startsWith("-") || itemText.startsWith("[") || itemText.startsWith("{")) {
                unsupported(index + 1, "nested list/object items are not supported")
            }
            items += decodeScalar(itemText, index + 1)
            sawIndent = true
            index += 1
        }
        if (!sawIndent && index < closeIndex && lines[index].startsWith(" ")) {
            unsupported(startIndex + 1, "nested mappings are not supported in frontmatter")
        }
        return BlockList(items.ifEmpty { null }, index)
    }

    private fun parseInlineList(raw: String, lineNumber: Int): List<String> {
        if (!raw.endsWith("]")) {
            unsupported(lineNumber, "inline list is not terminated by ']'")
        }
        val inner = raw.substring(1, raw.length - 1).trim()
        if (inner.isEmpty()) return emptyList()
        val items = ArrayList<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (ch in inner) {
            when {
                quote != null -> {
                    current.append(ch)
                    if (ch == quote) quote = null
                }
                ch == '\'' || ch == '"' -> {
                    quote = ch
                    current.append(ch)
                }
                ch == ',' -> {
                    items += decodeScalar(current.toString(), lineNumber)
                    current.setLength(0)
                }
                ch == '[' || ch == ']' || ch == '{' || ch == '}' -> {
                    unsupported(lineNumber, "nested flow collections are not supported")
                }
                else -> current.append(ch)
            }
        }
        if (quote != null) unsupported(lineNumber, "unterminated quoted string")
        if (current.isNotBlank() || inner.endsWith(",")) {
            items += decodeScalar(current.toString(), lineNumber)
        }
        if (items.any { it.isEmpty() }) unsupported(lineNumber, "inline list contains an empty item")
        return items
    }

    private fun decodeScalar(raw: String, lineNumber: Int): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        if (text.startsWith("'")) {
            if (text.length < 2 || !text.endsWith("'")) {
                unsupported(lineNumber, "unterminated single-quoted string")
            }
            val inner = text.substring(1, text.length - 1)
            if (inner.replace("''", "").contains('\'')) {
                unsupported(lineNumber, "unescaped single quote in string")
            }
            return inner.replace("''", "'")
        }
        if (text.startsWith("\"")) {
            if (text.length < 2 || !text.endsWith("\"") || text.endsWith("\\\"")) {
                unsupported(lineNumber, "unterminated double-quoted string")
            }
            return unescapeDoubleQuoted(text.substring(1, text.length - 1), lineNumber)
        }
        if (FORBIDDEN_SCALAR_START.any { text.startsWith(it) }) {
            unsupported(lineNumber, "YAML tags, anchors, aliases and flow objects are not supported")
        }
        if (text.contains(": ") || text.endsWith(":")) {
            unsupported(lineNumber, "nested mappings are not supported")
        }
        if (text.contains(" &") || text.contains(" *") || text.contains(" !")) {
            unsupported(lineNumber, "YAML anchors, aliases and tags are not supported")
        }
        if (text.any { it == '\t' || it == '\u0000' }) {
            skillFailure(AgentSkillErrorCode.FRONTMATTER_INVALID_VALUE, "value contains a control character", line = lineNumber)
        }
        return text
    }

    private fun unescapeDoubleQuoted(raw: String, lineNumber: Int): String {
        val result = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            if (ch != '\\') {
                result.append(ch)
                index += 1
                continue
            }
            if (index + 1 >= raw.length) unsupported(lineNumber, "dangling escape in double-quoted string")
            when (val next = raw[index + 1]) {
                '\\' -> result.append('\\')
                '"' -> result.append('"')
                'n' -> result.append('\n')
                't' -> result.append('\t')
                else -> unsupported(lineNumber, "unsupported escape '\\$next' in double-quoted string")
            }
            index += 2
        }
        return result.toString()
    }

    private fun unsupported(lineNumber: Int, message: String): Nothing =
        skillFailure(AgentSkillErrorCode.FRONTMATTER_UNSUPPORTED, message, line = lineNumber)

    private class FrontmatterAccumulator {
        private val values = LinkedHashMap<String, AgentSkillMetadataValue>()
        private val metadata = LinkedHashMap<String, AgentSkillMetadataValue>()

        fun put(key: String, value: AgentSkillMetadataValue, lineNumber: Int) {
            if (values.containsKey(key)) {
                skillFailure(
                    AgentSkillErrorCode.FRONTMATTER_DUPLICATE_KEY,
                    "duplicate frontmatter key: '$key'",
                    line = lineNumber
                )
            }
            values[key] = value
        }

        fun toFrontmatter(): AgentSkillFrontmatter {
            val name = scalarOf("name") ?: skillFailure(
                AgentSkillErrorCode.FRONTMATTER_INVALID_VALUE,
                "frontmatter must declare 'name'"
            )
            if (name.isBlank() || name.any { it == '\n' || it == '\r' || it == '\u0000' || it.isISOControl() }) {
                skillFailure(AgentSkillErrorCode.NAME_INVALID, "skill name must be a single non-blank line")
            }
            if (name.codePointCount(0, name.length) > AgentSkillLimits.MAX_NAME_CODE_POINTS) {
                skillFailure(
                    AgentSkillErrorCode.NAME_INVALID,
                    "skill name exceeds ${AgentSkillLimits.MAX_NAME_CODE_POINTS} characters"
                )
            }
            if (name != name.trim()) {
                skillFailure(AgentSkillErrorCode.NAME_INVALID, "skill name must not start or end with whitespace")
            }
            val description = scalarOf("description") ?: skillFailure(
                AgentSkillErrorCode.FRONTMATTER_INVALID_VALUE,
                "frontmatter must declare 'description'"
            )
            if (description.isBlank() || description.contains('\u0000')) {
                skillFailure(AgentSkillErrorCode.FRONTMATTER_INVALID_VALUE, "skill description must not be blank")
            }
            if (description.toByteArray(Charsets.UTF_8).size > AgentSkillLimits.MAX_DESCRIPTION_BYTES) {
                skillFailure(
                    AgentSkillErrorCode.DESCRIPTION_TOO_LARGE,
                    "skill description exceeds ${AgentSkillLimits.MAX_DESCRIPTION_BYTES} bytes"
                )
            }
            val packageVersion = scalarOf("version")?.takeIf { it.isNotBlank() } ?: "1"
            if (packageVersion.length > 64) {
                skillFailure(AgentSkillErrorCode.FRONTMATTER_INVALID_VALUE, "'version' is too long")
            }

            val allowedTools = when (val declaration = values["allowed-tools"]) {
                null -> null
                is AgentSkillMetadataValue.Text -> toolDeclaration(splitCommaList(declaration.value, "allowed-tools"))
                is AgentSkillMetadataValue.ListValue -> toolDeclaration(declaration.values)
            }
            val declaredKeys = setOf("name", "description", "allowed-tools", "version")
            for ((key, value) in values) {
                if (key in declaredKeys) continue
                val bytes = when (value) {
                    is AgentSkillMetadataValue.Text -> value.value.toByteArray(Charsets.UTF_8).size
                    is AgentSkillMetadataValue.ListValue -> value.values.sumOf { it.toByteArray(Charsets.UTF_8).size }
                }
                if (bytes > AgentSkillLimits.MAX_METADATA_VALUE_BYTES) {
                    skillFailure(AgentSkillErrorCode.FRONTMATTER_INVALID_VALUE, "metadata '$key' is too large")
                }
                metadata[key] = value
            }
            if (metadata.size > AgentSkillLimits.MAX_METADATA_KEYS) {
                skillFailure(
                    AgentSkillErrorCode.FRONTMATTER_INVALID_VALUE,
                    "frontmatter carries more than ${AgentSkillLimits.MAX_METADATA_KEYS} metadata keys"
                )
            }
            return AgentSkillFrontmatter(
                name = name,
                description = description,
                packageVersion = packageVersion,
                allowedTools = allowedTools,
                metadata = metadata.toMap()
            )
        }

        private fun scalarOf(key: String): String? = when (val value = values[key]) {
            null -> null
            // Block scalars (`|`, `>`) must keep their YAML trailing newline; plain scalars were
            // already trimmed when decoded, so a second trim here would only corrupt block text.
            is AgentSkillMetadataValue.Text -> value.value
            is AgentSkillMetadataValue.ListValue -> skillFailure(
                AgentSkillErrorCode.FRONTMATTER_INVALID_VALUE,
                "'$key' must be a single-line string"
            )
        }

        private fun toolDeclaration(values: List<String>): AgentSkillToolDeclaration {
            if (values.isEmpty()) {
                skillFailure(
                    AgentSkillErrorCode.ALLOWED_TOOLS_INVALID,
                    "allowed-tools must not be empty; omit it to leave the skill unrestricted"
                )
            }
            return AgentSkillToolDeclaration(values)
        }

        private fun splitCommaList(raw: String, key: String): List<String> {
            val items = raw.split(',').map { it.trim() }
            if (items.any { it.isEmpty() }) {
                skillFailure(AgentSkillErrorCode.ALLOWED_TOOLS_INVALID, "'$key' contains an empty entry")
            }
            return items
        }
    }

    private data class BlockScalar(val text: String, val nextIndex: Int)

    private data class BlockList(val items: List<String>?, val nextIndex: Int)

    private companion object {
        const val FRONTMATTER_FENCE = "---"
        val KEY_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
        val BLOCK_INDICATOR = Regex("^[|>]-?$")
        val FORBIDDEN_SCALAR_START = listOf("!", "&", "*", "{", "}", "?", "@", "`")
    }
}
