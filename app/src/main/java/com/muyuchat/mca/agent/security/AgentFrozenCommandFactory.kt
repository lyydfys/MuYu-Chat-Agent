package com.muyuchat.mca.agent.security

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentScopePrecondition
import com.muyuchat.core.agent.contract.AgentScopePreconditionCodec
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.FrozenToolCommand
import java.io.ByteArrayOutputStream

/**
 * Exact resource scope that was frozen together with [FrozenToolCommand].
 *
 * [taskRoot] is a logical identity (`workspaceId/tasks/taskId`), never a device path. A scope has
 * exactly one shape: either V1 workspace [logicalPaths], or a V2 [typedPreconditionJson] (N02c)
 * whose resource identity is the typed scope and whose [logicalPaths] are empty.
 * [expectedOldHash] is null for read tools and for create-only writes.
 */
data class AgentResolvedToolScope(
    val taskRoot: String,
    val logicalPaths: List<String>,
    val expectedOldHash: String?,
    val networkDestinations: List<String>,
    val typedPreconditionJson: String? = null
) {
    init {
        require(taskRoot.isNotBlank()) { "taskRoot must not be blank" }
        if (typedPreconditionJson == null) {
            require(logicalPaths.isNotEmpty()) { "a frozen tool command must name at least one resource path" }
        } else {
            require(logicalPaths.isEmpty()) { "a typed scope must not also name workspace logical paths" }
            require(typedPreconditionJson.startsWith("{")) {
                "a typed scope precondition must be a JSON object"
            }
        }
        require(logicalPaths == logicalPaths.distinct().sorted()) { "logical paths must be sorted and unique" }
        require(expectedOldHash == null || expectedOldHash.matches(Regex("^[0-9a-f]{64}$"))) {
            "expectedOldHash must be lowercase SHA-256 or null"
        }
        require(networkDestinations == networkDestinations.distinct().sorted()) {
            "network destinations must be sorted and unique"
        }
    }

    /** Canonical precondition JSON bound by this scope; the single encoding used by freeze and verify. */
    val preconditionJson: String = typedPreconditionJson
        ?: AgentCanonicalJson.preconditionJson(taskRoot, logicalPaths, expectedOldHash)

    /** Fingerprint stored in [FrozenToolCommand.resourceFingerprint] and bound by approvals. */
    val resourceFingerprint: String = typedPreconditionJson
        ?.let(AgentScopePreconditionCodec::fingerprintJson)
        ?: AgentFrozenHashing.resourceFingerprint(preconditionJson)
}

/**
 * All trusted inputs needed to freeze one validated tool call.
 *
 * The raw arguments are the only model-authored field. The descriptor, ids, epoch, policy version,
 * clock and network destinations are host facts supplied by the run owner; no model text, MCP
 * annotation, Skill allowed-tools entry or tool-declared hint participates in this decision.
 */
data class AgentFreezeRequest(
    val descriptor: AgentToolDescriptor,
    val rawArgumentsJson: String,
    val invocationId: String,
    val runId: String,
    val stepId: String,
    val callOrdinal: Int,
    val workspaceId: String,
    val taskId: String,
    val runEpoch: Long,
    val policyVersion: Long,
    val nowMillis: Long,
    val approvalTtlMillis: Long = AgentContract.APPROVAL_TTL_MILLIS,
    val networkDestinations: List<String> = emptyList(),
    /**
     * Host-derived typed resource scope (N02c). Only tools whose resource identity is not a workspace
     * path (for example `memory.search`) supply one; the value is never derived from model text.
     */
    val typedScope: AgentScopePrecondition? = null,
    /** Native provider call id (N05c); frozen so a resumed approval keeps its call pairing. */
    val providerCallId: String? = null
) {
    init {
        AgentContract.requireId(invocationId, "invocationId")
        AgentContract.requireId(runId, "runId")
        AgentContract.requireId(stepId, "stepId")
        AgentContract.requireId(workspaceId, "workspaceId")
        AgentContract.requireId(taskId, "taskId")
        require(callOrdinal >= 0) { "callOrdinal must not be negative" }
        require(runEpoch > 0L) { "runEpoch must be positive" }
        require(policyVersion > 0L) { "policyVersion must be positive" }
        require(nowMillis > 0L) { "nowMillis must be positive" }
        require(approvalTtlMillis > 0L) { "approvalTtlMillis must be positive" }
    }

    /** Redacted: raw model arguments/content must never be logged. */
    override fun toString(): String =
        "AgentFreezeRequest(toolKey=${descriptor.toolKey}, invocationId=$invocationId, " +
            "runId=$runId, runEpoch=$runEpoch, policyVersion=$policyVersion)"
}

sealed interface AgentFreezeResult {
    /**
     * The immutable command plus the exact bytes that were hashed. [hashPreimage] is retained only
     * for verification/audit; nothing may re-serialize arguments from it or from the model string.
     */
    class Frozen internal constructor(
        val command: FrozenToolCommand,
        val hashPreimage: ByteArray,
        val scope: AgentResolvedToolScope
    ) : AgentFreezeResult {
        val hashPreimageHex: String = AgentCanonicalJson.toHex(hashPreimage)

        fun preimageCopy(): ByteArray = hashPreimage.copyOf()

        /** Redacted: command arguments/content must never appear in logs or error reports. */
        override fun toString(): String =
            "AgentFreezeResult.Frozen(toolKey=${command.toolKey}, " +
                "argumentsHash=${command.argumentsHash}, resourceFingerprint=${command.resourceFingerprint})"
    }

    data class Rejected(val error: AgentSecurityError) : AgentFreezeResult
}

/**
 * Builds contract [FrozenToolCommand]s for K08 executors.
 *
 * Mandatory order (architecture 8.1): strict schema validation happens in K02, then this factory
 * resolves defaults and logical paths, then the broker decides/asks, then the executor receives
 * only the frozen command. Because defaults and paths are already inside [FrozenToolCommand] before
 * approval, changing the raw model string or the catalog later cannot change what was approved.
 *
 * Hash rule (architecture 8.2): SHA-256 over length-prefixed UTF-8 fields, in this fixed order:
 * 1. domain `mca.frozen-command.v1`
 * 2. canonical normalized argument bytes (`normalizedArgumentsJson` UTF-8)
 * 3. tool identity: length-prefixed `toolKey`, `version`, `schemaHash` nested as one field
 * 4. resource precondition: the canonical `resourcePreconditionsJson` that is stored in the command
 * 5. decimal `policyVersion`
 * Each field is prefixed by an 8-byte big-endian unsigned byte length. The result is stored as
 * [FrozenToolCommand.argumentsHash] (lowercase hex) and the exact preimage is returned once.
 */
class AgentFrozenCommandFactory(
    private val normalizer: AgentToolArgumentNormalizer = AgentBuiltInToolArgumentNormalizer()
) {
    fun freeze(request: AgentFreezeRequest): AgentFreezeResult {
        val normalization = when (val result = normalizer.normalize(request.descriptor, request.rawArgumentsJson)) {
            is AgentArgumentNormalization.Rejected -> return AgentFreezeResult.Rejected(result.error)
            is AgentArgumentNormalization.Normalized -> result
        }
        val destinations = when (val result = canonicalDestinations(request.networkDestinations)) {
            is DestinationsResult.Valid -> result.destinations
            is DestinationsResult.Invalid -> return AgentFreezeResult.Rejected(result.error)
        }
        val expiresAtMillis = request.nowMillis + request.approvalTtlMillis
        if (expiresAtMillis <= request.nowMillis) {
            return AgentFreezeResult.Rejected(
                AgentSecurityErrors.invalidArguments("Approval validity interval is out of range.")
            )
        }

        val taskRoot = "${request.workspaceId}/tasks/${request.taskId}"
        val typedJson = request.typedScope?.let(AgentScopePreconditionCodec::canonicalJson)
        val logicalPaths = if (typedJson == null) normalization.logicalPaths.distinct().sorted() else emptyList()
        val scope = try {
            AgentResolvedToolScope(
                taskRoot = taskRoot,
                logicalPaths = logicalPaths,
                expectedOldHash = normalization.expectedOldHash,
                networkDestinations = destinations,
                typedPreconditionJson = typedJson
            )
        } catch (_: IllegalArgumentException) {
            return AgentFreezeResult.Rejected(
                AgentSecurityErrors.invalidArguments("The resolved resource scope is not canonical.")
            )
        }
        val resourcePreconditionsJson = scope.preconditionJson
        val normalizedArgumentsUtf8 = normalization.normalizedArgumentsJson.toByteArray(Charsets.UTF_8)
        val preimage = AgentFrozenHashing.commandPreimage(
            normalizedArgumentsUtf8 = normalizedArgumentsUtf8,
            descriptor = request.descriptor,
            resourcePreconditionsJson = resourcePreconditionsJson,
            policyVersion = request.policyVersion
        )
        val argumentsHash = AgentCanonicalJson.sha256Hex(preimage)
        val resourceFingerprint = scope.resourceFingerprint
        val command = FrozenToolCommand(
            invocationId = request.invocationId,
            runId = request.runId,
            stepId = request.stepId,
            callOrdinal = request.callOrdinal,
            toolKey = request.descriptor.toolKey,
            toolVersion = request.descriptor.version,
            schemaHash = request.descriptor.schemaHash,
            normalizedArgumentsJson = normalization.normalizedArgumentsJson,
            argumentsHash = argumentsHash,
            resourceFingerprint = resourceFingerprint,
            runEpoch = request.runEpoch,
            policyVersion = request.policyVersion,
            expiresAtMillis = expiresAtMillis,
            networkDestinations = destinations,
            resourcePreconditionsJson = resourcePreconditionsJson,
            providerCallId = request.providerCallId
        )
        return AgentFreezeResult.Frozen(command, preimage, scope)
    }

    private sealed interface DestinationsResult {
        data class Valid(val destinations: List<String>) : DestinationsResult

        data class Invalid(val error: AgentSecurityError) : DestinationsResult
    }

    private fun canonicalDestinations(raw: List<String>): DestinationsResult {
        val canonical = mutableSetOf<String>()
        raw.forEach { destination ->
            when (val result = AgentEgressOrigins.canonical(destination, allowCleartext = false)) {
                is AgentOriginResult.Valid -> canonical.add(result.origin)
                is AgentOriginResult.Invalid -> return DestinationsResult.Invalid(
                    AgentSecurityErrors.egressDenied("A declared network destination is not a valid HTTPS origin.")
                )
            }
        }
        return DestinationsResult.Valid(canonical.sorted())
    }
}

/** Domain-separated hash helpers; kept internal so only the factory defines the wire rule. */
internal object AgentFrozenHashing {    const val COMMAND_DOMAIN = "mca.frozen-command.v1"
    const val RESOURCE_DOMAIN = "mca.resource-fingerprint.v1"

    fun commandPreimage(
        normalizedArgumentsUtf8: ByteArray,
        descriptor: AgentToolDescriptor,
        resourcePreconditionsJson: String,
        policyVersion: Long
    ): ByteArray {
        val target = ByteArrayOutputStream()
        AgentCanonicalJson.appendLengthPrefixed(target, COMMAND_DOMAIN)
        AgentCanonicalJson.appendLengthPrefixed(target, normalizedArgumentsUtf8)
        AgentCanonicalJson.appendLengthPrefixed(
            target,
            toolIdentity(descriptor.toolKey, descriptor.version, descriptor.schemaHash)
        )
        AgentCanonicalJson.appendLengthPrefixed(target, resourcePreconditionsJson.toByteArray(Charsets.UTF_8))
        AgentCanonicalJson.appendLengthPrefixed(target, policyVersion.toString())
        return target.toByteArray()
    }

    fun resourceFingerprint(resourcePreconditionsJson: String): String {
        val target = ByteArrayOutputStream()
        AgentCanonicalJson.appendLengthPrefixed(target, RESOURCE_DOMAIN)
        AgentCanonicalJson.appendLengthPrefixed(target, resourcePreconditionsJson.toByteArray(Charsets.UTF_8))
        return AgentCanonicalJson.sha256Hex(target.toByteArray())
    }

    private fun toolIdentity(toolKey: String, version: String, schemaHash: String): ByteArray {
        val target = ByteArrayOutputStream()
        AgentCanonicalJson.appendLengthPrefixed(target, toolKey)
        AgentCanonicalJson.appendLengthPrefixed(target, version)
        AgentCanonicalJson.appendLengthPrefixed(target, schemaHash)
        return target.toByteArray()
    }
}

/**
 * Defense-in-depth re-derivation of a [FrozenToolCommand] from its trusted descriptor and scope.
 *
 * The contract record is a public immutable value, so a caller could hand a forged/stale command to
 * the broker. The broker re-derives the precondition JSON, resource fingerprint and argsHash and
 * rejects a mismatch with INTERNAL_INVARIANT instead of trusting the constructor.
 *
 * For a typed scope (N02c) this verifies the persisted canonical document against the command; it
 * does not re-derive the document from current domain state. The executor that owns that resource
 * (for example `memory.search` against the task's read grant) must do that second, independent check
 * before executing — that is where a drifted typed scope becomes `RESOURCE_SCOPE_OUTDATED`.
 */
internal object AgentFrozenIntegrity {
    fun matches(
        command: FrozenToolCommand,
        descriptor: AgentToolDescriptor,
        scope: AgentResolvedToolScope
    ): Boolean {
        if (command.toolKey != descriptor.toolKey ||
            command.toolVersion != descriptor.version ||
            command.schemaHash != descriptor.schemaHash
        ) {
            return false
        }
        val paths = scope.logicalPaths.distinct().sorted()
        if (scope.typedPreconditionJson == null && paths.isEmpty()) return false
        if (scope.typedPreconditionJson != null && paths.isNotEmpty()) return false
        val precondition = scope.preconditionJson
        if (precondition != command.resourcePreconditionsJson) return false
        if (scope.resourceFingerprint != command.resourceFingerprint) return false
        if (scope.networkDestinations != command.networkDestinations) return false
        val preimage = AgentFrozenHashing.commandPreimage(
            normalizedArgumentsUtf8 = command.normalizedArgumentsJson.toByteArray(Charsets.UTF_8),
            descriptor = descriptor,
            resourcePreconditionsJson = precondition,
            policyVersion = command.policyVersion
        )
        return AgentCanonicalJson.sha256Hex(preimage) == command.argumentsHash
    }
}
