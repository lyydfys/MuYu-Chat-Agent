package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.mca.agent.security.AgentArgValue
import com.muyuchat.mca.agent.security.AgentArgumentNormalization
import com.muyuchat.mca.agent.security.AgentArgumentsParse
import com.muyuchat.mca.agent.security.AgentCanonicalJson
import com.muyuchat.mca.agent.security.AgentSecurityErrors
import com.muyuchat.mca.agent.security.AgentToolArgumentNormalizer

/**
 * Freeze-time normalizer for `device.screen`.
 *
 * Without this route the built-in fallback would answer "tool not registered", so the tool could be
 * advertised but never frozen into a command. Validation is deliberately strict and mirrors the
 * schema: exactly one `action`, and only that action's own fields. The canonical document it emits
 * is what [AgentScreenTool] parses, so a request can never reach the accessibility engine with an
 * unvalidated coordinate or an over-long text.
 */
class AgentScreenArgumentNormalizer : AgentToolArgumentNormalizer {

    override fun normalize(
        descriptor: AgentToolDescriptor,
        rawArgumentsJson: String
    ): AgentArgumentNormalization {
        if (descriptor.toolKey != AgentScreenTool.TOOL_KEY) {
            return AgentArgumentNormalization.Rejected(AgentSecurityErrors.toolNotRegistered())
        }
        val fields = when (val parsed = AgentCanonicalJson.parseArguments(rawArgumentsJson)) {
            is AgentArgumentsParse.Parsed -> parsed.fields
            is AgentArgumentsParse.Rejected -> return AgentArgumentNormalization.Rejected(parsed.error)
        }
        val action = (fields["action"] as? AgentArgValue.Text)?.value?.trim()?.lowercase()
            ?.takeIf { it in ACTIONS }
            ?: return reject("device.screen needs one of: ${ACTIONS.joinToString(", ")}.")
        val normalized = linkedMapOf<String, AgentArgValue>("action" to AgentArgValue.Text(action))
        try {
            when (action) {
                "read", "back", "home" -> Unit
                "tap" -> {
                    val text = optionalText(fields, "text", AgentScreenTool.MAX_TEXT_CHARS)
                    val x = optionalInt(fields, "x", AGENT_MAX_COORDINATE)
                    val y = optionalInt(fields, "y", AGENT_MAX_COORDINATE)
                    if (text == null && (x == null || y == null)) {
                        return reject("device.screen tap needs x and y, or the text of a control.")
                    }
                    text?.let { normalized["text"] = AgentArgValue.Text(it) }
                    if (x != null && y != null) {
                        normalized["x"] = AgentArgValue.Integer(x)
                        normalized["y"] = AgentArgValue.Integer(y)
                    }
                }

                "input" -> {
                    val text = optionalText(fields, "text", AgentScreenTool.MAX_TEXT_CHARS)
                        ?: return reject("device.screen input needs the text to type.")
                    normalized["text"] = AgentArgValue.Text(text)
                }

                "scroll" -> {
                    val forward = when (val value = fields["forward"]) {
                        null -> true
                        is AgentArgValue.Bool -> value.value
                        else -> return reject("device.screen scroll forward must be true or false.")
                    }
                    val distance = optionalInt(fields, "distance", AGENT_MAX_COORDINATE) ?: 0L
                    normalized["forward"] = AgentArgValue.Bool(forward)
                    normalized["distance"] = AgentArgValue.Integer(distance)
                }
            }
        } catch (rejected: RejectedField) {
            return reject(rejected.message ?: "device.screen arguments are invalid.")
        }
        val allowed = when (action) {
            "tap" -> setOf("action", "x", "y", "text")
            "input" -> setOf("action", "text")
            "scroll" -> setOf("action", "forward", "distance")
            else -> setOf("action")
        }
        if (!allowed.containsAll(fields.keys)) {
            return reject("device.screen ${action} does not accept ${fields.keys - allowed}.")
        }
        return AgentArgumentNormalization.Normalized(
            normalizedArgumentsJson = AgentCanonicalJson.writeObject(normalized),
            logicalPaths = emptyList(),
            expectedOldHash = null
        )
    }

    private fun optionalText(
        fields: Map<String, AgentArgValue>,
        key: String,
        maximum: Int
    ): String? = when (val value = fields[key]) {
        null -> null
        is AgentArgValue.Text -> value.value.takeIf { it.isNotBlank() && it.length <= maximum }
            ?: throw RejectedField("$key must be 1..$maximum characters.")
        else -> throw RejectedField("$key must be a string.")
    }

    private fun optionalInt(
        fields: Map<String, AgentArgValue>,
        key: String,
        maximum: Long
    ): Long? = when (val value = fields[key]) {
        null -> null
        is AgentArgValue.Integer -> value.value.takeIf { it in 0..maximum }
            ?: throw RejectedField("$key must be between 0 and $maximum.")
        else -> throw RejectedField("$key must be an integer.")
    }

    private fun reject(message: String): AgentArgumentNormalization =
        AgentArgumentNormalization.Rejected(AgentSecurityErrors.invalidArguments(message))

    /** Thrown by the field helpers and converted by [normalize]'s caller-visible rejection. */
    private class RejectedField(message: String) : RuntimeException(message)

    private companion object {
        val ACTIONS: Set<String> = setOf("read", "tap", "input", "scroll", "back", "home")
        const val AGENT_MAX_COORDINATE: Long = 100_000L
    }
}
