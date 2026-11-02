package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.mca.agent.security.AgentArgValue
import com.muyuchat.mca.agent.security.AgentArgumentNormalization
import com.muyuchat.mca.agent.security.AgentArgumentsParse
import com.muyuchat.mca.agent.security.AgentBuiltInToolArgumentNormalizer
import com.muyuchat.mca.agent.security.AgentCanonicalJson
import com.muyuchat.mca.agent.security.AgentLogicalPaths
import com.muyuchat.mca.agent.security.AgentPathResult
import com.muyuchat.mca.agent.security.AgentSecurityError
import com.muyuchat.mca.agent.security.AgentSecurityErrors
import com.muyuchat.mca.agent.security.AgentToolArgumentNormalizer

/**
 * Argument normalizer for the second built-in tool family.
 *
 * The four V1 tools keep their existing normalizer; this class only adds the new keys and delegates
 * everything else, so a single injected normalizer covers the whole built-in surface. Defaults and
 * canonical paths are resolved here, *before* the approval is shown, exactly like the V1 path: a
 * later default or a re-parse can therefore never change what the user approved.
 *
 * Network and process tools still need a typed resource scope at freeze time (a workspace path
 * cannot describe an origin or an argv); [AgentExtendedToolScopes] builds that scope from the same
 * normalized arguments so the approval binds the exact origin/argv that will be used.
 */
class AgentExtendedToolArgumentNormalizer(
    private val builtIn: AgentToolArgumentNormalizer = AgentBuiltInToolArgumentNormalizer(),
    private val terminalWhitelist: Set<String> = AgentTerminalWhitelist.DEFAULT
) : AgentToolArgumentNormalizer {

    override fun normalize(
        descriptor: AgentToolDescriptor,
        rawArgumentsJson: String
    ): AgentArgumentNormalization {
        if (descriptor.toolKey !in KEYS) return builtIn.normalize(descriptor, rawArgumentsJson)
        val fields = when (val parsed = AgentCanonicalJson.parseArguments(rawArgumentsJson)) {
            is AgentArgumentsParse.Parsed -> parsed.fields
            is AgentArgumentsParse.Rejected -> return AgentArgumentNormalization.Rejected(parsed.error)
        }
        return try {
            when (descriptor.toolKey) {
                WORKSPACE_WRITE_TEXT -> normalizeWriteText(fields)
                WORKSPACE_EDIT_TEXT -> normalizeEditText(fields)
                HTTP_GET -> normalizeHttpGet(fields)
                TERMINAL_EXEC -> normalizeTerminalExec(fields)
                else -> AgentArgumentNormalization.Rejected(AgentSecurityErrors.toolNotRegistered())
            }
        } catch (failure: FieldFailure) {
            AgentArgumentNormalization.Rejected(failure.error)
        }
    }

    private fun normalizeWriteText(fields: Map<String, AgentArgValue>): AgentArgumentNormalization {
        requireOnly(fields, setOf(FIELD_PATH, FIELD_CONTENT, FIELD_EXPECTED_OLD_HASH))
        val path = writePath(requiredText(fields, FIELD_PATH))
        val content = requiredText(fields, FIELD_CONTENT)
        if (content.toByteArray(Charsets.UTF_8).size > AgentTextWriteSupport.MAX_CONTENT_BYTES) {
            fail("Artifact content exceeds the 32 KiB UTF-8 limit.")
        }
        if (!fields.containsKey(FIELD_EXPECTED_OLD_HASH)) {
            fail("Workspace writes must state expectedOldHash explicitly (null means create-only).")
        }
        val expectedOldHash = optionalHash(fields, FIELD_EXPECTED_OLD_HASH)
        return normalized(
            linkedMapOf(
                FIELD_PATH to AgentArgValue.Text(path),
                FIELD_CONTENT to AgentArgValue.Text(content),
                FIELD_EXPECTED_OLD_HASH to (expectedOldHash?.let(AgentArgValue::Text) ?: AgentArgValue.Null)
            ),
            logicalPaths = listOf(path),
            expectedOldHash = expectedOldHash
        )
    }

    private fun normalizeEditText(fields: Map<String, AgentArgValue>): AgentArgumentNormalization {
        requireOnly(fields, setOf(FIELD_PATH, FIELD_OLD_TEXT, FIELD_NEW_TEXT, FIELD_EXPECTED_OLD_HASH))
        val path = writePath(requiredText(fields, FIELD_PATH))
        val oldText = requiredText(fields, FIELD_OLD_TEXT)
        if (oldText.isEmpty() || oldText.length > MAX_EDIT_TEXT_CHARS) {
            fail("oldText must contain between 1 and $MAX_EDIT_TEXT_CHARS characters.")
        }
        val newText = requiredText(fields, FIELD_NEW_TEXT)
        if (newText.length > MAX_EDIT_TEXT_CHARS) {
            fail("newText exceeds $MAX_EDIT_TEXT_CHARS characters.")
        }
        val expectedOldHash = requiredHash(fields, FIELD_EXPECTED_OLD_HASH)
        return normalized(
            linkedMapOf(
                FIELD_PATH to AgentArgValue.Text(path),
                FIELD_OLD_TEXT to AgentArgValue.Text(oldText),
                FIELD_NEW_TEXT to AgentArgValue.Text(newText),
                FIELD_EXPECTED_OLD_HASH to AgentArgValue.Text(expectedOldHash)
            ),
            logicalPaths = listOf(path),
            expectedOldHash = expectedOldHash
        )
    }

    private fun normalizeHttpGet(fields: Map<String, AgentArgValue>): AgentArgumentNormalization {
        requireOnly(fields, setOf(FIELD_URL, FIELD_MAX_BYTES, FIELD_TIMEOUT_MILLIS))
        val rawUrl = requiredText(fields, FIELD_URL)
        val destination = when (val checked = AgentHttpDestinationRules.check(rawUrl)) {
            is AgentHttpUrlResult.Valid -> checked
            is AgentHttpUrlResult.Invalid -> {
                fail(checked.safeMessage)
            }
        }
        val maxBytes = optionalInteger(fields, FIELD_MAX_BYTES, DEFAULT_HTTP_MAX_BYTES, 1L, MAX_HTTP_MAX_BYTES)
        val timeoutMillis = optionalInteger(
            fields,
            FIELD_TIMEOUT_MILLIS,
            DEFAULT_HTTP_TIMEOUT_MILLIS,
            MIN_HTTP_TIMEOUT_MILLIS,
            MAX_HTTP_TIMEOUT_MILLIS
        )
        return normalized(
            linkedMapOf(
                FIELD_URL to AgentArgValue.Text(destination.url),
                FIELD_MAX_BYTES to AgentArgValue.Integer(maxBytes),
                FIELD_TIMEOUT_MILLIS to AgentArgValue.Integer(timeoutMillis)
            ),
            logicalPaths = emptyList(),
            expectedOldHash = null
        )
    }

    private fun normalizeTerminalExec(fields: Map<String, AgentArgValue>): AgentArgumentNormalization {
        requireOnly(fields, setOf(FIELD_COMMAND, FIELD_ARGS, FIELD_TIMEOUT_MILLIS))
        val command = requiredText(fields, FIELD_COMMAND).trim()
        if (!AgentTerminalWhitelist.isValidCommandName(command)) {
            fail("The command name contains unsupported characters.")
        }
        if (command !in terminalWhitelist) {
            fail("The command is not on the terminal whitelist.")
        }
        val args = optionalText(fields, FIELD_ARGS, "", MAX_TERMINAL_ARGS_CHARS)
        if (AgentTerminalWhitelist.containsShellSyntax(args)) {
            fail("Shell syntax is not supported; pass plain space-separated tokens.")
        }
        val timeoutMillis = optionalInteger(
            fields,
            FIELD_TIMEOUT_MILLIS,
            DEFAULT_TERMINAL_TIMEOUT_MILLIS,
            MIN_TERMINAL_TIMEOUT_MILLIS,
            MAX_TERMINAL_TIMEOUT_MILLIS
        )
        return normalized(
            linkedMapOf(
                FIELD_COMMAND to AgentArgValue.Text(command),
                FIELD_ARGS to AgentArgValue.Text(args),
                FIELD_TIMEOUT_MILLIS to AgentArgValue.Integer(timeoutMillis)
            ),
            logicalPaths = emptyList(),
            expectedOldHash = null
        )
    }

    private fun normalized(
        fields: Map<String, AgentArgValue>,
        logicalPaths: List<String>,
        expectedOldHash: String?
    ): AgentArgumentNormalization = AgentArgumentNormalization.Normalized(
        normalizedArgumentsJson = AgentCanonicalJson.writeObject(fields),
        logicalPaths = logicalPaths,
        expectedOldHash = expectedOldHash
    )

    private fun requireOnly(fields: Map<String, AgentArgValue>, allowed: Set<String>) {
        fields.keys.forEach { key ->
            if (key !in allowed) fail("Tool arguments contain a field that is not part of the tool schema.")
        }
    }

    private fun requiredText(fields: Map<String, AgentArgValue>, key: String): String {
        val value = fields[key] ?: fail("A required tool argument is missing.")
        return (value as? AgentArgValue.Text)?.value ?: fail("A required tool argument has the wrong JSON type.")
    }

    private fun optionalText(fields: Map<String, AgentArgValue>, key: String, default: String, maxChars: Int): String {
        val value = fields[key] ?: return default
        val text = (value as? AgentArgValue.Text)?.value ?: fail("A tool argument has the wrong JSON type.")
        if (text.length > maxChars) fail("A tool argument string is out of range.")
        return text
    }

    private fun optionalInteger(
        fields: Map<String, AgentArgValue>,
        key: String,
        default: Long,
        minimum: Long,
        maximum: Long
    ): Long {
        val value = fields[key] ?: return default
        val integer = (value as? AgentArgValue.Integer)?.value ?: fail("A tool integer argument has the wrong JSON type.")
        if (integer < minimum || integer > maximum) fail("A tool integer argument is out of range.")
        return integer
    }

    private fun optionalHash(fields: Map<String, AgentArgValue>, key: String): String? {
        val value = fields[key] ?: return null
        if (value == AgentArgValue.Null) return null
        val text = (value as? AgentArgValue.Text)?.value
            ?: fail("expectedOldHash must be a lowercase SHA-256 string or null.")
        if (!SHA256_HEX.matches(text)) fail("expectedOldHash must be a lowercase SHA-256 string or null.")
        return text
    }

    private fun requiredHash(fields: Map<String, AgentArgValue>, key: String): String {
        val text = requiredText(fields, key)
        if (!SHA256_HEX.matches(text)) fail("expectedOldHash must be a lowercase SHA-256 string.")
        return text
    }

    private fun writePath(raw: String): String = when (val result = AgentLogicalPaths.normalizeWritePath(raw)) {
        is AgentPathResult.Valid -> result.path
        is AgentPathResult.Invalid -> throw FieldFailure(result.error)
    }

    private fun fail(detail: String): Nothing = throw FieldFailure(AgentSecurityErrors.invalidArguments(detail))

    private class FieldFailure(val error: AgentSecurityError) : Exception()

    companion object {
        const val WORKSPACE_WRITE_TEXT: String = "workspace.write_text"
        const val WORKSPACE_EDIT_TEXT: String = "workspace.edit_text"
        const val HTTP_GET: String = "http.get"
        const val TERMINAL_EXEC: String = "terminal.exec"

        private val KEYS = setOf(WORKSPACE_WRITE_TEXT, WORKSPACE_EDIT_TEXT, HTTP_GET, TERMINAL_EXEC)

        private const val FIELD_PATH = "path"
        private const val FIELD_CONTENT = "content"
        private const val FIELD_OLD_TEXT = "oldText"
        private const val FIELD_NEW_TEXT = "newText"
        private const val FIELD_EXPECTED_OLD_HASH = "expectedOldHash"
        private const val FIELD_URL = "url"
        private const val FIELD_MAX_BYTES = "maxBytes"
        private const val FIELD_COMMAND = "command"
        private const val FIELD_ARGS = "args"
        private const val FIELD_TIMEOUT_MILLIS = "timeoutMillis"

        private const val MAX_EDIT_TEXT_CHARS = 8_192
        private const val MAX_TERMINAL_ARGS_CHARS = 1_024

        private const val DEFAULT_HTTP_MAX_BYTES = 32L * 1024
        private const val MAX_HTTP_MAX_BYTES = 128L * 1024
        private const val DEFAULT_HTTP_TIMEOUT_MILLIS = 10_000L
        private const val MIN_HTTP_TIMEOUT_MILLIS = 1_000L
        private const val MAX_HTTP_TIMEOUT_MILLIS = 15_000L

        private const val DEFAULT_TERMINAL_TIMEOUT_MILLIS = 15_000L
        private const val MIN_TERMINAL_TIMEOUT_MILLIS = 1_000L
        private const val MAX_TERMINAL_TIMEOUT_MILLIS = 30_000L

        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}
