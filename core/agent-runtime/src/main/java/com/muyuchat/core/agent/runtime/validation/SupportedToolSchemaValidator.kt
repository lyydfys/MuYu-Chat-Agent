package com.muyuchat.core.agent.runtime.validation

import com.muyuchat.core.agent.runtime.AgentArgumentValidation
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.runtime.AgentSchemaValidation
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.runtime.AgentToolSchemaValidator
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec

/**
 * Strict implementation of [AgentToolSchemaValidator].
 *
 * - [validateToolSchema] compiles only the exact supported subset, rejects unknown keywords,
 *   remote/local `$ref` and recursive schemas, and returns the canonical schema JSON plus SHA-256.
 * - [validateArguments] applies the four V1 code-level defaults, rejects missing required fields,
 *   extra fields, wrong JSON types, bounds and over-budget UTF-8 strings, then returns canonical
 *   normalized arguments and their SHA-256 for the frozen-command factory.
 *
 * No lenient fallback exists: any unsupported or invalid input returns a typed error and the
 * caller must not execute the tool.
 */
class SupportedToolSchemaValidator(
    private val codec: StrictJsonCodec = StrictAgentJsonCodec()
) : AgentToolSchemaValidator {

    private val compiler = SupportedSchemaCompiler(codec)
    private val checker = AgentArgumentChecker(codec)

    override fun validateToolSchema(schemaJson: String): AgentSchemaValidation {
        return when (val result = compiler.compile(schemaJson)) {
            is AgentSchemaCompileResult.Supported -> AgentSchemaValidation.Supported(
                canonicalSchemaJson = result.canonicalSchemaJson,
                schemaHash = result.schemaHash
            )
            is AgentSchemaCompileResult.Unsupported -> AgentSchemaValidation.Unsupported(
                errorCode = result.errorCode,
                safeMessage = result.safeMessage
            )
        }
    }

    override fun validateArguments(
        descriptor: AgentToolDescriptor,
        argumentsJson: String
    ): AgentArgumentValidation {
        val parsed = codec.parse(argumentsJson)
        if (parsed is AgentJsonParseResult.Failed) {
            return invalid("Arguments are not strict JSON (${parsed.errorCode}).")
        }
        val root = (parsed as AgentJsonParseResult.Parsed).value as? AgentJsonValue.Obj
            ?: return invalid("Tool arguments must be a single JSON object.")
        val schema = when (val compiled = compiler.compile(descriptor.schemaJson)) {
            is AgentSchemaCompileResult.Supported -> compiled
            is AgentSchemaCompileResult.Unsupported -> return AgentArgumentValidation.Invalid(
                errorCode = compiled.errorCode,
                safeMessage = compiled.safeMessage
            )
        }
        if (schema.schemaHash != descriptor.schemaHash) {
            return AgentArgumentValidation.Invalid(
                errorCode = AgentErrorCodes.POLICY_SCHEMA_UNSUPPORTED,
                safeMessage = "The descriptor schema hash does not match its canonical schema hash."
            )
        }
        val normalized = LinkedHashMap<String, AgentJsonValue>()
        for ((name, value) in root.fields) normalized[name] = value
        AgentV1ToolRules.applyDefaults(descriptor.toolKey, normalized)
        checker.check(schema.root, AgentJsonValue.Obj(normalized.toList()), "$")?.let {
            return invalid(it)
        }
        AgentV1ToolRules.validate(descriptor.toolKey, normalized)?.let {
            return invalid(it)
        }
        val normalizedJson = codec.write(AgentJsonValues.canonical(AgentJsonValue.Obj(normalized.toList())))
        return AgentArgumentValidation.Valid(
            normalizedArgumentsJson = normalizedJson,
            argumentsHash = AgentHashes.sha256HexUtf8(normalizedJson)
        )
    }

    private fun invalid(message: String): AgentArgumentValidation.Invalid =
        AgentArgumentValidation.Invalid(
            errorCode = AgentErrorCodes.TOOL_INPUT_INVALID,
            safeMessage = message
        )
}
