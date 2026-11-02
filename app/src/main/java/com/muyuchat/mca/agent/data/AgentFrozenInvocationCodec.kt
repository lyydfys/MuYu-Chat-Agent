package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import com.muyuchat.core.agent.contract.FrozenToolCommand
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.journal.AgentFrozenInvocation
import com.muyuchat.core.agent.runtime.journal.AgentToolScope
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import java.nio.charset.StandardCharsets

/**
 * Durable encoding for [AgentFrozenInvocation].
 *
 * Layout: `[4-byte big-endian header length][canonical header JSON][normalized argument bytes]`.
 * The header is small and strict-JSON-bounded; the argument bytes are kept out of JSON so a bounded
 * 64 KiB argument document is never re-escaped or truncated. `argsSha256`/`argsBytes` bind the
 * argument bytes to the header, and the command hashes are re-derived by the security layer before
 * authorization, so a tampered payload can never be authorized.
 */
internal object AgentFrozenInvocationCodec {
    private const val SCHEMA_VERSION = 1
    private const val MAX_HEADER_BYTES = 60 * 1024
    private const val MAX_ARGS_BYTES = 64 * 1024
    private const val HEADER_LENGTH_BYTES = 4

    private val codec: StrictJsonCodec = StrictAgentJsonCodec()

    sealed interface Encoded {
        class Success(val bytes: ByteArray) : Encoded

        data class Rejected(val errorCode: String, val safeMessage: String) : Encoded
    }

    fun encode(frozen: AgentFrozenInvocation): Encoded {
        val argsBytes = frozen.command.normalizedArgumentsJson.toByteArray(StandardCharsets.UTF_8)
        if (argsBytes.size > MAX_ARGS_BYTES) {
            return Encoded.Rejected(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "The frozen arguments exceed the persisted bound."
            )
        }
        val header = AgentJsonValues.canonical(
            AgentJsonValues.obj(
                "schemaVersion" to AgentJsonValues.int(SCHEMA_VERSION),
                "argsSha256" to AgentJsonValues.str(AgentHashes.sha256Hex(argsBytes)),
                "argsBytes" to AgentJsonValues.int(argsBytes.size),
                "descriptor" to descriptorJson(frozen.descriptor),
                "scope" to scopeJson(frozen.scope),
                "command" to commandJson(frozen.command)
            )
        )
        val headerBytes = codec.write(header).toByteArray(StandardCharsets.UTF_8)
        if (headerBytes.size > MAX_HEADER_BYTES) {
            return Encoded.Rejected(
                AgentDataErrorCodes.COMMAND_INVALID_STATE,
                "The frozen command header exceeds the persisted bound."
            )
        }
        val out = ByteArray(HEADER_LENGTH_BYTES + headerBytes.size + argsBytes.size)
        writeInt(out, 0, headerBytes.size)
        System.arraycopy(headerBytes, 0, out, HEADER_LENGTH_BYTES, headerBytes.size)
        System.arraycopy(argsBytes, 0, out, HEADER_LENGTH_BYTES + headerBytes.size, argsBytes.size)
        return Encoded.Success(out)
    }

    /** Returns null for any malformed/inconsistent payload; a corrupt record is never usable. */
    fun decode(bytes: ByteArray): AgentFrozenInvocation? {
        if (bytes.size < HEADER_LENGTH_BYTES + 2) return null
        val headerLength = readInt(bytes, 0)
        if (headerLength <= 0 ||
            headerLength > MAX_HEADER_BYTES ||
            headerLength > bytes.size - HEADER_LENGTH_BYTES
        ) {
            return null
        }
        val headerBytes = bytes.copyOfRange(HEADER_LENGTH_BYTES, HEADER_LENGTH_BYTES + headerLength)
        val argsBytes = bytes.copyOfRange(HEADER_LENGTH_BYTES + headerLength, bytes.size)
        if (argsBytes.size > MAX_ARGS_BYTES) return null
        val parsed = codec.parse(String(headerBytes, StandardCharsets.UTF_8))
        if (parsed !is AgentJsonParseResult.Parsed) return null
        val header = parsed.value as? AgentJsonValue.Obj ?: return null
        val fields = header.fields.toMap()
        val version = fields["schemaVersion"].longOrNull() ?: return null
        if (version != SCHEMA_VERSION.toLong()) return null
        val argsSha256 = fields["argsSha256"].stringOrNull() ?: return null
        val declaredArgsBytes = fields["argsBytes"].intOrNull() ?: return null
        if (declaredArgsBytes != argsBytes.size) return null
        if (AgentHashes.sha256Hex(argsBytes) != argsSha256) return null
        val descriptor = fields["descriptor"]?.let(::decodeDescriptor) ?: return null
        val scope = fields["scope"]?.let(::decodeScope) ?: return null
        val commandFields = fields["command"] as? AgentJsonValue.Obj ?: return null
        val normalizedArgumentsJson = String(argsBytes, StandardCharsets.UTF_8)
        val command = decodeCommand(commandFields, normalizedArgumentsJson) ?: return null
        return try {
            AgentFrozenInvocation(
                command = command,
                descriptor = descriptor,
                scope = scope,
                recordedAtMillis = 0L
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun descriptorJson(descriptor: AgentToolDescriptor): AgentJsonValue = AgentJsonValues.obj(
        "toolKey" to AgentJsonValues.str(descriptor.toolKey),
        "version" to AgentJsonValues.str(descriptor.version),
        "displayName" to AgentJsonValues.str(descriptor.displayName),
        "description" to AgentJsonValues.str(descriptor.description),
        "schemaJson" to AgentJsonValues.str(descriptor.schemaJson),
        "schemaHash" to AgentJsonValues.str(descriptor.schemaHash),
        "sideEffect" to AgentJsonValues.str(descriptor.sideEffect.name),
        "dataClass" to AgentJsonValues.str(descriptor.dataClass.name),
        "timeoutMillis" to AgentJsonValues.long(descriptor.timeoutMillis)
    )

    private fun scopeJson(scope: AgentToolScope): AgentJsonValue = AgentJsonValues.obj(
        "taskRoot" to AgentJsonValues.str(scope.taskRoot),
        "logicalPaths" to AgentJsonValues.arr(scope.logicalPaths.map { AgentJsonValues.str(it) }),
        "expectedOldHash" to (scope.expectedOldHash?.let { AgentJsonValues.str(it) }
            ?: AgentJsonValues.nullValue),
        "networkDestinations" to AgentJsonValues.arr(
            scope.networkDestinations.map { AgentJsonValues.str(it) }
        ),
        // null for V1 workspace scopes; the canonical typed-scope document for N02c scopes.
        "typedPreconditionJson" to (scope.typedPreconditionJson?.let { AgentJsonValues.str(it) }
            ?: AgentJsonValues.nullValue)
    )

    private fun commandJson(command: FrozenToolCommand): AgentJsonValue = AgentJsonValues.obj(
        "invocationId" to AgentJsonValues.str(command.invocationId),
        "runId" to AgentJsonValues.str(command.runId),
        "stepId" to AgentJsonValues.str(command.stepId),
        "callOrdinal" to AgentJsonValues.int(command.callOrdinal),
        "toolKey" to AgentJsonValues.str(command.toolKey),
        "toolVersion" to AgentJsonValues.str(command.toolVersion),
        "schemaHash" to AgentJsonValues.str(command.schemaHash),
        "argumentsHash" to AgentJsonValues.str(command.argumentsHash),
        "resourceFingerprint" to AgentJsonValues.str(command.resourceFingerprint),
        "runEpoch" to AgentJsonValues.long(command.runEpoch),
        "policyVersion" to AgentJsonValues.long(command.policyVersion),
        "expiresAtMillis" to AgentJsonValues.long(command.expiresAtMillis),
        "networkDestinations" to AgentJsonValues.arr(
            command.networkDestinations.map { AgentJsonValues.str(it) }
        ),
        "resourcePreconditionsJson" to AgentJsonValues.str(command.resourcePreconditionsJson),
        "providerCallId" to (command.providerCallId?.let { AgentJsonValues.str(it) }
            ?: AgentJsonValue.Null),
        "schemaVersion" to AgentJsonValues.int(command.schemaVersion)
    )

    private fun decodeDescriptor(value: AgentJsonValue): AgentToolDescriptor? {
        val fields = (value as? AgentJsonValue.Obj)?.fields?.toMap() ?: return null
        val sideEffect = runCatching {
            AgentToolSideEffect.valueOf(fields["sideEffect"].stringOrNull() ?: return null)
        }.getOrNull() ?: return null
        val dataClass = runCatching {
            AgentDataClass.valueOf(fields["dataClass"].stringOrNull() ?: return null)
        }.getOrNull() ?: return null
        return runCatching {
            AgentToolDescriptor(
                toolKey = fields["toolKey"].stringOrNull() ?: return null,
                version = fields["version"].stringOrNull() ?: return null,
                displayName = fields["displayName"].stringOrNull() ?: return null,
                description = fields["description"].stringOrNull() ?: return null,
                schemaJson = fields["schemaJson"].stringOrNull() ?: return null,
                schemaHash = fields["schemaHash"].stringOrNull() ?: return null,
                sideEffect = sideEffect,
                dataClass = dataClass,
                timeoutMillis = fields["timeoutMillis"].longOrNull() ?: return null
            )
        }.getOrNull()
    }

    private fun decodeScope(value: AgentJsonValue): AgentToolScope? {
        val fields = (value as? AgentJsonValue.Obj)?.fields?.toMap() ?: return null
        return runCatching {
            AgentToolScope(
                taskRoot = fields["taskRoot"].stringOrNull() ?: return null,
                logicalPaths = fields["logicalPaths"].stringListOrNull() ?: return null,
                expectedOldHash = fields["expectedOldHash"].stringOrNull(),
                networkDestinations = fields["networkDestinations"].stringListOrNull() ?: return null,
                // Rows written before N02c have no field and stay readable as V1 workspace scopes.
                typedPreconditionJson = fields["typedPreconditionJson"].stringOrNull()
            )
        }.getOrNull()
    }

    private fun decodeCommand(
        fields: AgentJsonValue.Obj,
        normalizedArgumentsJson: String
    ): FrozenToolCommand? {
        val map = fields.fields.toMap()
        return runCatching {
            FrozenToolCommand(
                invocationId = map["invocationId"].stringOrNull() ?: return null,
                runId = map["runId"].stringOrNull() ?: return null,
                stepId = map["stepId"].stringOrNull() ?: return null,
                callOrdinal = map["callOrdinal"].intOrNull() ?: return null,
                toolKey = map["toolKey"].stringOrNull() ?: return null,
                toolVersion = map["toolVersion"].stringOrNull() ?: return null,
                schemaHash = map["schemaHash"].stringOrNull() ?: return null,
                normalizedArgumentsJson = normalizedArgumentsJson,
                argumentsHash = map["argumentsHash"].stringOrNull() ?: return null,
                resourceFingerprint = map["resourceFingerprint"].stringOrNull() ?: return null,
                runEpoch = map["runEpoch"].longOrNull() ?: return null,
                policyVersion = map["policyVersion"].longOrNull() ?: return null,
                expiresAtMillis = map["expiresAtMillis"].longOrNull() ?: return null,
                networkDestinations = map["networkDestinations"].stringListOrNull() ?: return null,
                resourcePreconditionsJson = map["resourcePreconditionsJson"].stringOrNull()
                    ?: return null,
                providerCallId = map["providerCallId"].stringOrNull(),
                schemaVersion = map["schemaVersion"].intOrNull() ?: return null
            )
        }.getOrNull()
    }

    private fun AgentJsonValue?.stringOrNull(): String? = (this as? AgentJsonValue.Str)?.value

    private fun AgentJsonValue?.longOrNull(): Long? =
        (this as? AgentJsonValue.Num)?.literal?.toLongOrNull()

    private fun AgentJsonValue?.intOrNull(): Int? =
        (this as? AgentJsonValue.Num)?.literal?.toIntOrNull()

    private fun AgentJsonValue?.stringListOrNull(): List<String>? {
        val items = (this as? AgentJsonValue.Arr)?.items ?: return null
        return items.map { (it as? AgentJsonValue.Str)?.value ?: return null }
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun readInt(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 24) or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)
}
