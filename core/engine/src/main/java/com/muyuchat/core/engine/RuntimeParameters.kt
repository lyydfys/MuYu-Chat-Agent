package com.muyuchat.core.engine

import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class ParameterOwner {
    MODEL_EXECUTION,
    ASSISTANT_GENERATION,
    SESSION_DIAGNOSTIC,
    INTERNAL_CANARY
}

enum class ParameterMutability {
    LOAD_BOUND,
    HOT_EXECUTION,
    GENERATION_ONLY,
    UNSUPPORTED
}

enum class ParameterApiOverridePolicy {
    TRUSTED_PROFILE_ONLY,
    TRUSTED_RUNTIME_OVERRIDE,
    REQUEST_ALLOWED,
    NEVER
}

enum class ParameterExecutionSupport {
    NATIVE,
    HOST_ENFORCED,
    UNSUPPORTED,
    UNKNOWN
}

data class ParameterFieldPolicy(
    val field: String,
    val owner: ParameterOwner,
    val mutability: ParameterMutability,
    val apiOverridePolicy: ParameterApiOverridePolicy,
    val affectsSemantics: Boolean = false,
    val requiredGate: String? = null,
    val evidence: String = "product-contract",
    val executionSupport: ParameterExecutionSupport = ParameterExecutionSupport.UNKNOWN
)

data class VersionedParameterPolicySet(
    val runtime: LocalChatRuntime,
    val runtimeVersionPrefix: String? = null,
    val nativeLibrarySha256Prefix: String? = null,
    val policyVersion: String,
    val policies: Map<String, ParameterFieldPolicy>
) {
    internal fun matches(identity: ModelRuntimeIdentity): Boolean =
        runtime == identity.runtime &&
            (runtimeVersionPrefix == null || identity.runtimeVersion.startsWith(runtimeVersionPrefix)) &&
            (nativeLibrarySha256Prefix == null ||
                identity.nativeLibrarySha256.startsWith(nativeLibrarySha256Prefix, ignoreCase = true))

    internal val specificity: Int
        get() = (if (runtimeVersionPrefix != null) 1 else 0) +
            (if (nativeLibrarySha256Prefix != null) 2 else 0)
}

data class ModelRuntimeIdentity(
    val modelId: String,
    val artifactFingerprint: String,
    val runtime: LocalChatRuntime,
    val runtimeVersion: String = "unknown",
    val nativeLibrarySha256: String = "unknown",
    val abi: String = "arm64-v8a",
    val backendFingerprint: String = "",
    val projectorFingerprint: String = "",
    val bundleFingerprint: String = "",
    val tokenizerFingerprint: String = "",
    val templateFingerprint: String = "",
    val deviceCapabilityFingerprint: String = "",
    val installationScopeId: String = "local-installation",
    val ruleSetFingerprint: String = "",
    val evaluatorFingerprint: String = "",
    val engineContractVersion: String = "1",
    val schemaFingerprint: String = "runtime-parameters-schema-v1",
    val parameterPolicyVersion: String = ParameterFieldPolicyRegistry.BUILTIN_POLICY_VERSION,
    val capabilities: Set<String> = emptySet()
) {
    init {
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        require(artifactFingerprint.isNotBlank()) { "artifactFingerprint must not be blank" }
    }

    val identityHash: String = sha256(
        listOf(
            modelId,
            artifactFingerprint,
            runtime.name,
            runtimeVersion,
            nativeLibrarySha256.lowercase(),
            abi,
            backendFingerprint,
            projectorFingerprint,
            bundleFingerprint,
            tokenizerFingerprint,
            templateFingerprint,
            deviceCapabilityFingerprint,
            installationScopeId,
            ruleSetFingerprint,
            evaluatorFingerprint,
            engineContractVersion,
            schemaFingerprint,
            parameterPolicyVersion,
            capabilities.toSortedSet().joinToString(",")
        ).joinToString("\n", transform = ::escapeCanonical)
    )
}

/** Scalar/JSON values encoded with their type so 1, 1.0, true, and "1" never collide. */
@ConsistentCopyVisibility
data class CanonicalParameterSet private constructor(
    val encodedValues: Map<String, String>
) {
    val fields: Set<String>
        get() = encodedValues.keys

    fun value(field: String): Any? = encodedValues[field]?.let(::decodeValue)

    fun toJsonObject(): JSONObject = JSONObject().also { target ->
        encodedValues.toSortedMap().forEach { (field, value) -> target.put(field, decodeValue(value)) }
    }

    fun plus(other: CanonicalParameterSet): CanonicalParameterSet =
        fromEncoded(encodedValues + other.encodedValues)

    fun only(fields: Set<String>): CanonicalParameterSet =
        fromEncoded(encodedValues.filterKeys(fields::contains))

    fun differences(other: CanonicalParameterSet): Set<String> =
        (fields + other.fields).filterTo(linkedSetOf()) { encodedValues[it] != other.encodedValues[it] }

    companion object {
        val EMPTY = CanonicalParameterSet(emptyMap())

        fun of(values: Map<String, Any?>): CanonicalParameterSet =
            CanonicalParameterSet(values.toSortedMap().mapValues { encodeValue(it.value) })

        fun fromJson(root: JSONObject): CanonicalParameterSet = buildMap<String, Any?> {
            root.keys().asSequence().toList().sorted().forEach { key -> put(key, root.opt(key)) }
        }.let(::of)

        fun fromEncoded(values: Map<String, String>): CanonicalParameterSet =
            CanonicalParameterSet(values.toSortedMap())
    }
}

data class QuarantinedOverride(
    val field: String,
    val rawJson: String,
    val reason: String
)

interface RuntimeParameterSignature {
    val identityHash: String
    val values: CanonicalParameterSet
    val digest: String
}

@ConsistentCopyVisibility
data class DesiredProfileSignature private constructor(
    override val identityHash: String,
    override val values: CanonicalParameterSet,
    override val digest: String
) : RuntimeParameterSignature {
    companion object {
        fun of(identity: ModelRuntimeIdentity, values: CanonicalParameterSet): DesiredProfileSignature {
            val safe = signatureSafeValues(identity, values)
            return DesiredProfileSignature(identity.identityHash, safe, signatureDigest("desired", identity, safe))
        }
    }
}

@ConsistentCopyVisibility
data class ResolvedLoadSignature private constructor(
    override val identityHash: String,
    override val values: CanonicalParameterSet,
    override val digest: String
) : RuntimeParameterSignature {
    companion object {
        fun of(identity: ModelRuntimeIdentity, values: CanonicalParameterSet): ResolvedLoadSignature {
            val safe = signatureSafeValues(identity, values)
            return ResolvedLoadSignature(identity.identityHash, safe, signatureDigest("resolved-load", identity, safe))
        }
    }
}

@ConsistentCopyVisibility
data class ActiveLoadedSignature private constructor(
    override val identityHash: String,
    override val values: CanonicalParameterSet,
    override val digest: String
) : RuntimeParameterSignature {
    companion object {
        fun of(identity: ModelRuntimeIdentity, values: CanonicalParameterSet): ActiveLoadedSignature {
            val safe = signatureSafeValues(identity, values)
            return ActiveLoadedSignature(identity.identityHash, safe, signatureDigest("active-loaded", identity, safe))
        }
    }
}

@ConsistentCopyVisibility
data class CommittedExecutionSignature private constructor(
    override val identityHash: String,
    override val values: CanonicalParameterSet,
    override val digest: String
) : RuntimeParameterSignature {
    companion object {
        fun of(identity: ModelRuntimeIdentity, values: CanonicalParameterSet): CommittedExecutionSignature {
            val safe = signatureSafeValues(identity, values)
            return CommittedExecutionSignature(
                identity.identityHash,
                safe,
                signatureDigest("committed-execution", identity, safe)
            )
        }
    }
}

@ConsistentCopyVisibility
data class RuntimeOverrideSignature private constructor(
    override val identityHash: String,
    override val values: CanonicalParameterSet,
    override val digest: String,
    val isNone: Boolean
) : RuntimeParameterSignature {
    companion object {
        fun none(identity: ModelRuntimeIdentity) =
            RuntimeOverrideSignature(identity.identityHash, CanonicalParameterSet.EMPTY, "NONE", true)

        fun of(identity: ModelRuntimeIdentity, values: CanonicalParameterSet): RuntimeOverrideSignature {
            if (values.fields.isEmpty()) return none(identity)
            val safe = signatureSafeValues(identity, values)
            return RuntimeOverrideSignature(
                identity.identityHash,
                safe,
                signatureDigest("runtime-override", identity, safe),
                false
            )
        }
    }
}

@ConsistentCopyVisibility
data class EffectiveExecutionSignature private constructor(
    override val identityHash: String,
    override val values: CanonicalParameterSet,
    override val digest: String
) : RuntimeParameterSignature {
    companion object {
        fun of(
            identity: ModelRuntimeIdentity,
            active: ActiveLoadedSignature,
            committed: CommittedExecutionSignature,
            override: RuntimeOverrideSignature
        ): EffectiveExecutionSignature {
            require(listOf(active.identityHash, committed.identityHash, override.identityHash)
                .all { it == identity.identityHash }) {
                "signature identity mismatch: expected=${identity.identityHash}, " +
                    "active=${active.identityHash}, committed=${committed.identityHash}, override=${override.identityHash}"
            }
            val values = active.values.plus(committed.values).plus(override.values)
            return EffectiveExecutionSignature(
                identity.identityHash,
                values,
                signatureDigest("effective-execution", identity, values)
            )
        }
    }
}

data class ParameterSignatureSnapshot(
    val desired: DesiredProfileSignature,
    val resolved: ResolvedLoadSignature,
    val active: ActiveLoadedSignature?,
    val committed: CommittedExecutionSignature,
    val override: RuntimeOverrideSignature,
    val effective: EffectiveExecutionSignature?
)

data class ModelExecutionProfile(
    val schemaVersion: Int = 1,
    val modelId: String,
    val runtimeIdentity: ModelRuntimeIdentity,
    val desiredLoadBoundValues: CanonicalParameterSet,
    val resolvedLoadBoundValues: CanonicalParameterSet,
    val hotExecutionValues: CanonicalParameterSet,
    val desiredHotExecutionValues: CanonicalParameterSet = hotExecutionValues,
    val modelBehaviorValues: CanonicalParameterSet = CanonicalParameterSet.EMPTY,
    val desiredModelBehaviorValues: CanonicalParameterSet = modelBehaviorValues,
    val profileId: String = UUID.randomUUID().toString(),
    val revision: Long = 1,
    val userOverrides: Set<String> = emptySet(),
    /** Unsupported or legacy fields retained for redacted diagnostics, never native dispatch. */
    val quarantinedOverrides: List<QuarantinedOverride> = emptyList(),
    val resolvedAt: Long = System.currentTimeMillis()
) {
    init {
        require(modelId == runtimeIdentity.modelId) { "profile modelId must match runtime identity" }
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        require(revision > 0) { "revision must be positive" }
    }

    val desiredSignature: DesiredProfileSignature = DesiredProfileSignature.of(
        runtimeIdentity,
        desiredLoadBoundValues.plus(desiredHotExecutionValues).plus(desiredModelBehaviorValues)
    )
    val resolvedLoadSignature: ResolvedLoadSignature =
        ResolvedLoadSignature.of(runtimeIdentity, resolvedLoadBoundValues)
    val committedExecutionSignature: CommittedExecutionSignature =
        CommittedExecutionSignature.of(runtimeIdentity, hotExecutionValues.plus(modelBehaviorValues))
}

data class ParameterResolution(
    val requested: DesiredProfileSignature,
    val resolved: ResolvedLoadSignature,
    val profile: ModelExecutionProfile,
    val sourceByField: Map<String, String>,
    val warnings: List<String>,
    val reloadRequired: Boolean,
    val rejectedOverrides: List<String>,
    val quarantinedOverrides: List<QuarantinedOverride>
)

data class RuntimeParameterPartition(
    val loadBound: CanonicalParameterSet,
    val hotExecution: CanonicalParameterSet,
    val modelBehavior: CanonicalParameterSet,
    val generationJson: String,
    val quarantinedOverrides: List<QuarantinedOverride>,
    val warnings: List<String>
)

interface RuntimeParameterAdapter {
    val runtime: LocalChatRuntime
    val registry: ParameterFieldPolicyRegistry

    fun partition(identity: ModelRuntimeIdentity, rawJson: String): RuntimeParameterPartition

    fun resolveLoadProfile(
        identity: ModelRuntimeIdentity,
        rawJson: String,
        profileId: String = UUID.randomUUID().toString(),
        revision: Long = 1,
        activeLoadSignature: ActiveLoadedSignature? = null
    ): ParameterResolution

    fun activeLoadedSignature(
        identity: ModelRuntimeIdentity,
        nativeStatsJson: String,
        expected: ResolvedLoadSignature
    ): ActiveLoadedSignature?

    /**
     * A path-redacted comparison used only to explain a rejected native
     * readback. It does not alter admission or relax the active signature.
     */
    fun loadSignatureDiagnostic(
        identity: ModelRuntimeIdentity,
        nativeStatsJson: String,
        expected: ResolvedLoadSignature
    ): JSONObject

    fun nativeCompletionJson(
        partition: RuntimeParameterPartition,
        profile: ModelExecutionProfile,
        override: RuntimeOverrideSignature
    ): String

    fun nativeLoadJson(profile: ModelExecutionProfile): String

    fun isLoadSignatureMismatch(beginReturnCode: Int, nativeError: String? = null): Boolean
}

object NativeRuntimeErrorCodes {
    const val LOAD_SIGNATURE_MISMATCH = -11
}

class LoadAuthorization internal constructor(
    val transactionId: String,
    val modelIdentityHash: String,
    val profileId: String,
    val revision: Long,
    val desiredProfileDigest: String,
    val resolvedLoadDigest: String,
    val committedExecutionDigest: String,
    internal val coordinatorNonce: String
)

/**
 * Opaque, coordinator-issued authorization for one exact HOT_EXECUTION value set.
 * The public Boolean compatibility flag is deliberately insufficient to create it.
 */
class HotOverrideAuthorization internal constructor(
    val profileId: String,
    val revision: Long,
    val modelIdentityHash: String,
    val fields: Set<String>,
    val overrideDigest: String,
    internal val coordinatorNonce: String
)

data class PendingLoadTransaction(
    val profile: ModelExecutionProfile,
    val authorization: LoadAuthorization,
    val rollbackTargetProfileId: String?,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Atomic, read-only proof for one exact authorized pending candidate.
 *
 * [strictlyMatches] is true only when the authorization is still the same
 * unconsumed pending transaction and all six signatures describe the candidate
 * that native code actually loaded. Candidate proof always uses
 * RuntimeOverride=NONE so an unrelated request-scoped throttle cannot leak into
 * tuning validation.
 */
data class AuthorizedPendingSignatureVerification(
    val transactionId: String,
    val profileId: String,
    val revision: Long,
    val signatures: ParameterSignatureSnapshot,
    val strictlyMatches: Boolean
)

sealed interface CompletionPreflight {
    data class Ready(
        val nativeParamsJson: String,
        val generationJson: String,
        val signatures: ParameterSignatureSnapshot,
        val quarantinedOverrides: List<QuarantinedOverride>
    ) : CompletionPreflight

    data class Rejected(
        val code: String,
        val changedFields: Set<String>,
        val quarantinedOverrides: List<QuarantinedOverride>,
        val message: String
    ) : CompletionPreflight
}

sealed interface MismatchRecoveryDecision {
    data class ReloadAuthorizedPending(
        val transaction: PendingLoadTransaction
    ) : MismatchRecoveryDecision

    data class ReloadCommittedForDrift(
        val profile: ModelExecutionProfile
    ) : MismatchRecoveryDecision

    data class Fail(val code: String, val message: String) : MismatchRecoveryDecision
}

internal fun JSONObject.mergeJson(source: JSONObject) {
    source.keys().forEach { key -> put(key, source.opt(key)) }
}

internal fun signatureSafeValues(
    identity: ModelRuntimeIdentity,
    values: CanonicalParameterSet
): CanonicalParameterSet = CanonicalParameterSet.fromEncoded(
    values.encodedValues.mapValues { (field, encoded) ->
        when {
            field == "mmproj_path" || field == "projector_path" -> encodeValue(
                "projector:${identity.projectorFingerprint.ifBlank { "bound" }}"
            )
            field.endsWith("_path") || field.endsWith("_uri") ->
                encodeValue("resource:${sha256(decodeValue(encoded).toString())}")
            else -> encoded
        }
    }
)

private fun signatureDigest(
    kind: String,
    identity: ModelRuntimeIdentity,
    values: CanonicalParameterSet
): String = sha256(
    buildString {
        append(kind).append('\n')
        append(identity.identityHash).append('\n')
        values.encodedValues.toSortedMap().forEach { (field, value) ->
            append(escapeCanonical(field)).append('=').append(escapeCanonical(value)).append('\n')
        }
    }
)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun escapeCanonical(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\n", "\\n")
    .replace("=", "\\=")

private fun encodeValue(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "n:"
    is Boolean -> if (value) "b:1" else "b:0"
    is Byte, is Short, is Int, is Long -> "i:${(value as Number).toLong()}"
    is Float, is Double -> "d:${BigDecimal(value.toString()).stripTrailingZeros().toPlainString()}"
    is Number -> "d:${BigDecimal(value.toString()).stripTrailingZeros().toPlainString()}"
    is String -> "s:$value"
    is JSONObject -> "j:${canonicalJsonObject(value)}"
    is JSONArray -> "j:${canonicalJsonArray(value)}"
    else -> "s:${value}"
}

internal fun decodeValue(encoded: String): Any = when {
    encoded == "n:" -> JSONObject.NULL
    encoded == "b:1" -> true
    encoded == "b:0" -> false
    encoded.startsWith("i:") -> encoded.substring(2).toLong().let { value ->
        if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else value
    }
    encoded.startsWith("d:") -> encoded.substring(2).toDouble()
    encoded.startsWith("s:") -> encoded.substring(2)
    encoded.startsWith("j:") -> {
        val json = encoded.substring(2)
        if (json.startsWith("[")) JSONArray(json) else JSONObject(json)
    }
    else -> encoded
}

private fun canonicalJsonObject(root: JSONObject): String = JSONObject().also { out ->
    root.keys().asSequence().toList().sorted().forEach { key ->
        out.put(key, canonicalJsonValue(root.opt(key)))
    }
}.toString()

private fun canonicalJsonArray(array: JSONArray): String = JSONArray().also { out ->
    for (index in 0 until array.length()) out.put(canonicalJsonValue(array.opt(index)))
}.toString()

private fun canonicalJsonValue(value: Any?): Any? = when (value) {
    is JSONObject -> JSONObject(canonicalJsonObject(value))
    is JSONArray -> JSONArray(canonicalJsonArray(value))
    else -> value
}

internal fun jsonValueString(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject, is JSONArray -> value.toString()
    is Number, is Boolean -> value.toString()
    is String -> JSONObject.quote(value)
    else -> JSONObject.quote(value.toString())
}
