package com.muyuchat.mca.agent.tools

import com.muyuchat.mca.agent.security.AgentArgValue
import com.muyuchat.mca.agent.security.AgentArgumentsParse
import com.muyuchat.mca.agent.security.AgentCanonicalJson
import com.muyuchat.mca.agent.security.AgentLogicalPaths
import com.muyuchat.mca.agent.security.AgentPathResult
import com.muyuchat.core.agent.contract.FrozenToolCommand

/** Typed, content-free failure of executor-side argument validation. */
internal class AgentToolInputException(message: String) : Exception(message)

/**
 * Typed access to a frozen command's canonical arguments.
 *
 * The K07 normalizer already applied defaults and path canonicalisation before the approval was
 * shown, so this reader never rewrites an approved value. Defaults are still applied when a field
 * is absent (defence for a command constructed outside the factory) using exactly the architecture
 * 9.1 values; a present explicit `null` is never treated as "use the default".
 */
internal object AgentToolArguments {
    fun parse(command: FrozenToolCommand): Map<String, AgentArgValue> =
        when (val parsed = AgentCanonicalJson.parseArguments(command.normalizedArgumentsJson)) {
            is AgentArgumentsParse.Parsed -> parsed.fields
            is AgentArgumentsParse.Rejected -> throw AgentToolInputException(parsed.error.safeMessage)
        }

    fun rejectUnknown(fields: Map<String, AgentArgValue>, allowed: Set<String>) {
        val unknown = fields.keys.firstOrNull { it !in allowed }
        if (unknown != null) throw AgentToolInputException("Tool arguments contain an unsupported field: $unknown")
    }

    fun requiredText(fields: Map<String, AgentArgValue>, key: String, maxChars: Int): String {
        val value = fields[key] ?: throw AgentToolInputException("A required tool argument is missing: $key")
        val text = (value as? AgentArgValue.Text)?.value
            ?: throw AgentToolInputException("Tool argument '$key' must be a string.")
        if (text.length > maxChars) throw AgentToolInputException("Tool argument '$key' exceeds its length limit.")
        return text
    }

    fun optionalText(
        fields: Map<String, AgentArgValue>,
        key: String,
        default: String,
        maxChars: Int
    ): String {
        val value = fields[key] ?: return default
        if (value == AgentArgValue.Null) throw AgentToolInputException("Tool argument '$key' must be a string, not null.")
        val text = (value as? AgentArgValue.Text)?.value
            ?: throw AgentToolInputException("Tool argument '$key' must be a string.")
        if (text.length > maxChars) throw AgentToolInputException("Tool argument '$key' exceeds its length limit.")
        return text
    }

    fun optionalNullableText(
        fields: Map<String, AgentArgValue>,
        key: String,
        maxChars: Int
    ): String? {
        val value = fields[key] ?: return null
        if (value == AgentArgValue.Null) return null
        val text = (value as? AgentArgValue.Text)?.value
            ?: throw AgentToolInputException("Tool argument '$key' must be a string or null.")
        if (text.isEmpty() || text.length > maxChars) {
            throw AgentToolInputException("Tool argument '$key' is outside its length limits.")
        }
        return text
    }

    fun requiredNullableText(fields: Map<String, AgentArgValue>, key: String, maxChars: Int): String? {
        if (!fields.containsKey(key)) {
            throw AgentToolInputException("Tool argument '$key' must be stated explicitly (null means create-only).")
        }
        val value = fields.getValue(key)
        if (value == AgentArgValue.Null) return null
        val text = (value as? AgentArgValue.Text)?.value
            ?: throw AgentToolInputException("Tool argument '$key' must be a string or null.")
        if (text.length > maxChars) throw AgentToolInputException("Tool argument '$key' exceeds its length limit.")
        return text
    }

    fun optionalInteger(
        fields: Map<String, AgentArgValue>,
        key: String,
        default: Long,
        minimum: Long,
        maximum: Long
    ): Long {
        val value = fields[key] ?: return default
        val integer = (value as? AgentArgValue.Integer)?.value
            ?: throw AgentToolInputException("Tool argument '$key' must be an integer.")
        if (integer < minimum || integer > maximum) {
            throw AgentToolInputException("Tool argument '$key' is outside its allowed range.")
        }
        return integer
    }

    fun readPath(raw: String): String = when (val result = AgentLogicalPaths.normalizeReadPath(raw)) {
        is AgentPathResult.Valid -> result.path
        is AgentPathResult.Invalid -> throw AgentToolInputException(result.error.safeMessage)
    }

    fun writePath(raw: String): String = when (val result = AgentLogicalPaths.normalizeWritePath(raw)) {
        is AgentPathResult.Valid -> result.path
        is AgentPathResult.Invalid -> throw AgentToolInputException(result.error.safeMessage)
    }
}
