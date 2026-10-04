package com.muyuchat.mca

import com.muyuchat.core.engine.CanonicalParameterSet
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.QuarantinedOverride
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

private const val IDENTITY_SNAPSHOT_FORMAT_VERSION = 1
private const val PROFILE_SNAPSHOT_FORMAT_VERSION = 1
private const val RESOURCE_BINDING_PREFIX = "@resource-binding:"
internal const val MAX_RESOURCE_VALUE_CHARS = 8 * 1024

internal fun ModelRuntimeIdentity.toRuntimeIdentityEntity(
    now: Long = System.currentTimeMillis(),
    createdAt: Long = now
): RuntimeIdentityEntity {
    requirePersistableInstallationScope(installationScopeId)
    require(!RuntimeProfilePersistencePolicy.isInstallationLocalResource("model_id", modelId)) {
        "modelId must be an opaque model identifier, not a path."
    }
    val identityJson = canonicalIdentityJson()
    RuntimeProfilePersistencePolicy.requireSafeProfileJson(identityJson)
    return RuntimeIdentityEntity(
        identityKey = identityHash,
        modelId = modelId,
        runtime = runtime.name,
        artifactFingerprint = artifactFingerprint,
        deviceCapabilityFingerprint = deviceCapabilityFingerprint,
        installationScopeId = installationScopeId,
        runtimeFingerprint = persistenceSha256(
            listOf(runtime.name, runtimeVersion, nativeLibrarySha256, abi, backendFingerprint)
                .joinToString("\n")
        ),
        fieldPolicyFingerprint = parameterPolicyVersion,
        ruleFingerprint = ruleSetFingerprint,
        evaluatorFingerprint = evaluatorFingerprint,
        schemaVersion = IDENTITY_SNAPSHOT_FORMAT_VERSION,
        identityJson = identityJson,
        createdAt = createdAt,
        updatedAt = now
    )
}

internal fun RuntimeIdentityEntity.toModelRuntimeIdentity(): ModelRuntimeIdentity {
    RuntimeProfilePersistencePolicy.requireSafeProfileJson(identityJson)
    val root = JSONObject(identityJson)
    require(root.getInt("formatVersion") == IDENTITY_SNAPSHOT_FORMAT_VERSION)
    val capabilities = root.getJSONArray("capabilities").let { array ->
        buildSet { repeat(array.length()) { index -> add(array.getString(index)) } }
    }
    val identity = ModelRuntimeIdentity(
        modelId = root.getString("modelId"),
        artifactFingerprint = root.getString("artifactFingerprint"),
        runtime = LocalChatRuntime.valueOf(root.getString("runtime")),
        runtimeVersion = root.getString("runtimeVersion"),
        nativeLibrarySha256 = root.getString("nativeLibrarySha256"),
        abi = root.getString("abi"),
        backendFingerprint = root.getString("backendFingerprint"),
        projectorFingerprint = root.getString("projectorFingerprint"),
        bundleFingerprint = root.getString("bundleFingerprint"),
        tokenizerFingerprint = root.getString("tokenizerFingerprint"),
        templateFingerprint = root.getString("templateFingerprint"),
        deviceCapabilityFingerprint = root.getString("deviceCapabilityFingerprint"),
        installationScopeId = root.getString("installationScopeId"),
        ruleSetFingerprint = root.getString("ruleSetFingerprint"),
        evaluatorFingerprint = root.getString("evaluatorFingerprint"),
        engineContractVersion = root.getString("engineContractVersion"),
        schemaFingerprint = root.getString("schemaFingerprint"),
        parameterPolicyVersion = root.getString("parameterPolicyVersion"),
        capabilities = capabilities
    )
    require(identity.identityHash == identityKey) { "Persisted runtime identity hash does not match its snapshot." }
    require(identity.modelId == modelId && identity.runtime.name == runtime)
    require(identity.artifactFingerprint == artifactFingerprint)
    require(identity.deviceCapabilityFingerprint == deviceCapabilityFingerprint)
    require(identity.installationScopeId == installationScopeId)
    require(identity.ruleSetFingerprint == ruleFingerprint)
    require(identity.evaluatorFingerprint == evaluatorFingerprint)
    require(identity.parameterPolicyVersion == fieldPolicyFingerprint)
    return identity
}

internal fun ModelExecutionProfile.toPersistedExecutionProfileSnapshot(
    parentCommittedProfileId: String? = null,
    recordState: PersistedProfileRecordState = PersistedProfileRecordState.STAGED,
    verificationLevel: PersistedProfileVerificationLevel = PersistedProfileVerificationLevel.SAFE,
    quarantinedOverridesJson: String? = null,
    sourceSummaryJson: String = "{}",
    now: Long = System.currentTimeMillis(),
    createdAt: Long = now
): PersistedExecutionProfileSnapshot {
    requirePersistableInstallationScope(runtimeIdentity.installationScopeId)
    val bindings = mutableListOf<RuntimeResourceBindingEntity>()
    val desired = desiredLoadBoundValues.bindResources("desired", this, bindings, now)
    val resolved = resolvedLoadBoundValues.bindResources("resolved", this, bindings, now)
    val hot = hotExecutionValues.bindResources("hot", this, bindings, now)
    val desiredHot = desiredHotExecutionValues.bindResources("desired-hot", this, bindings, now)
    val behavior = modelBehaviorValues.bindResources("behavior", this, bindings, now)
    val desiredBehavior = desiredModelBehaviorValues.bindResources(
        "desired-behavior",
        this,
        bindings,
        now
    )
    val profileJson = canonicalProfileJson(
        desired = desired,
        resolved = resolved,
        hot = hot,
        desiredHot = desiredHot,
        behavior = behavior,
        desiredBehavior = desiredBehavior
    )
    RuntimeProfilePersistencePolicy.requireSafeProfileJson(profileJson)
    val entity = ExecutionProfileEntity(
        profileId = profileId,
        identityKey = runtimeIdentity.identityHash,
        revision = revision,
        parentCommittedProfileId = parentCommittedProfileId,
        recordState = recordState.name,
        verificationLevel = verificationLevel.name,
        desiredProfileSignature = desiredSignature.digest,
        resolvedLoadSignature = resolvedLoadSignature.digest,
        activeLoadedSignature = "",
        committedExecutionSignature = committedExecutionSignature.digest,
        runtimeOverrideSignature = "NONE",
        effectiveExecutionSignature = "",
        resolvedLoadJson = resolved.toJsonObject().toString(),
        committedExecutionJson = hot.plus(behavior).toJsonObject().toString(),
        quarantinedOverridesJson = RuntimeProfilePersistencePolicy.sanitizeDiagnosticJson(
            quarantinedOverridesJson ?: quarantinedOverrides.toPersistenceJson()
        ),
        sourceSummaryJson = RuntimeProfilePersistencePolicy.sanitizeDiagnosticJson(sourceSummaryJson),
        profileJson = profileJson,
        failureStage = null,
        failureCode = null,
        failureSummary = null,
        createdAt = createdAt,
        updatedAt = now
    )
    return PersistedExecutionProfileSnapshot(entity, bindings.sortedBy { it.field })
}

internal fun ExecutionProfileEntity.toModelExecutionProfile(
    identity: ModelRuntimeIdentity,
    resourceBindings: List<RuntimeResourceBindingEntity>
): ModelExecutionProfile {
    require(identity.identityHash == identityKey) { "Profile identity does not match the runtime identity." }
    RuntimeProfilePersistencePolicy.requireSafeProfileJson(profileJson)
    val root = JSONObject(profileJson)
    require(root.getInt("formatVersion") == PROFILE_SNAPSHOT_FORMAT_VERSION)
    require(root.getString("profileId") == profileId)
    require(root.getString("identityKey") == identityKey)
    require(root.getString("modelId") == identity.modelId && root.getString("modelId") == root.getString("profileModelId"))
    require(root.getLong("revision") == revision)

    val bindingsById = resourceBindings.associateBy { binding -> binding.bindingId }
    require(bindingsById.size == resourceBindings.size) { "Duplicate runtime resource binding." }
    val consumedBindings = mutableSetOf<String>()
    fun restore(jsonSlot: String, bindingSlot: String): CanonicalParameterSet = restoreParameterSet(
        root.getJSONObject(jsonSlot), bindingSlot, identity, profileId, bindingsById, consumedBindings
    )

    val hot = restore("hotExecutionValues", "hot")
    val behavior = restore("modelBehaviorValues", "behavior")
    val hasDesiredHot = root.has("desiredHotExecutionValues")
    val hasDesiredBehavior = root.has("desiredModelBehaviorValues")
    require(hasDesiredHot == hasDesiredBehavior) {
        "Profile has an incomplete desired execution snapshot."
    }
    val hasCompleteDesiredExecutionSnapshot = hasDesiredHot
    val profile = ModelExecutionProfile(
        schemaVersion = root.getInt("schemaVersion"),
        modelId = root.getString("modelId"),
        runtimeIdentity = identity,
        // Resource bindings are persisted in the compact slot namespace used
        // by bindResources (desired/resolved/hot/behavior), while the JSON
        // object keeps the descriptive parameter-set names. Keep the two
        // namespaces explicit so restore validates the same field key that
        // was written and remains compatible with existing snapshots.
        desiredLoadBoundValues = restore("desiredLoadBoundValues", "desired"),
        resolvedLoadBoundValues = restore("resolvedLoadBoundValues", "resolved"),
        hotExecutionValues = hot,
        desiredHotExecutionValues = if (hasCompleteDesiredExecutionSnapshot) {
            restore("desiredHotExecutionValues", "desired-hot")
        } else {
            hot
        },
        modelBehaviorValues = behavior,
        desiredModelBehaviorValues = if (hasCompleteDesiredExecutionSnapshot) {
            restore("desiredModelBehaviorValues", "desired-behavior")
        } else {
            behavior
        },
        profileId = profileId,
        revision = revision,
        userOverrides = root.getJSONArray("userOverrides").let { array ->
            buildSet { repeat(array.length()) { index -> add(array.getString(index)) } }
        },
        quarantinedOverrides = quarantinedOverridesJson.toQuarantinedOverrides(),
        resolvedAt = root.getLong("resolvedAt")
    )
    require(consumedBindings == bindingsById.keys) { "Profile has unreferenced runtime resource bindings." }
    if (hasCompleteDesiredExecutionSnapshot) {
        require(profile.desiredSignature.digest == desiredProfileSignature) {
            "Desired profile signature mismatch."
        }
    }
    require(profile.resolvedLoadSignature.digest == resolvedLoadSignature) { "Resolved load signature mismatch." }
    require(profile.committedExecutionSignature.digest == committedExecutionSignature) {
        "Committed execution signature mismatch."
    }
    return profile
}

private fun List<QuarantinedOverride>.toPersistenceJson(): String = JSONArray().also { array ->
    sortedWith(compareBy(QuarantinedOverride::field, QuarantinedOverride::reason)).forEach { value ->
        array.put(
            JSONObject()
                .put("field", value.field)
                .put("rawJson", value.rawJson)
                .put("reason", value.reason)
        )
    }
}.toString()

private fun String.toQuarantinedOverrides(): List<QuarantinedOverride> = runCatching {
    val array = JSONArray(RuntimeProfilePersistencePolicy.sanitizeDiagnosticJson(this))
    buildList {
        repeat(array.length()) { index ->
            val value = array.optJSONObject(index) ?: return@repeat
            val field = value.optString("field").trim()
            val reason = value.optString("reason").trim()
            if (field.isNotBlank() && reason.isNotBlank()) {
                add(
                    QuarantinedOverride(
                        field = field,
                        rawJson = value.optString("rawJson"),
                        reason = reason
                    )
                )
            }
        }
    }.sortedWith(compareBy(QuarantinedOverride::field, QuarantinedOverride::reason))
}.getOrDefault(emptyList())

private fun ModelRuntimeIdentity.canonicalIdentityJson(): String = JSONObject().apply {
    put("formatVersion", IDENTITY_SNAPSHOT_FORMAT_VERSION)
    put("modelId", modelId)
    put("artifactFingerprint", artifactFingerprint)
    put("runtime", runtime.name)
    put("runtimeVersion", runtimeVersion)
    put("nativeLibrarySha256", nativeLibrarySha256)
    put("abi", abi)
    put("backendFingerprint", backendFingerprint)
    put("projectorFingerprint", projectorFingerprint)
    put("bundleFingerprint", bundleFingerprint)
    put("tokenizerFingerprint", tokenizerFingerprint)
    put("templateFingerprint", templateFingerprint)
    put("deviceCapabilityFingerprint", deviceCapabilityFingerprint)
    put("installationScopeId", installationScopeId)
    put("ruleSetFingerprint", ruleSetFingerprint)
    put("evaluatorFingerprint", evaluatorFingerprint)
    put("engineContractVersion", engineContractVersion)
    put("schemaFingerprint", schemaFingerprint)
    put("parameterPolicyVersion", parameterPolicyVersion)
    put("capabilities", JSONArray().also { array -> capabilities.toSortedSet().forEach { array.put(it) } })
}.toString()

private fun ModelExecutionProfile.canonicalProfileJson(
    desired: CanonicalParameterSet,
    resolved: CanonicalParameterSet,
    hot: CanonicalParameterSet,
    desiredHot: CanonicalParameterSet,
    behavior: CanonicalParameterSet,
    desiredBehavior: CanonicalParameterSet
): String = JSONObject().apply {
    put("formatVersion", PROFILE_SNAPSHOT_FORMAT_VERSION)
    put("schemaVersion", schemaVersion)
    put("modelId", modelId)
    put("profileModelId", runtimeIdentity.modelId)
    put("identityKey", runtimeIdentity.identityHash)
    put("profileId", profileId)
    put("revision", revision)
    put("desiredLoadBoundValues", desired.toEncodedJsonObject())
    put("resolvedLoadBoundValues", resolved.toEncodedJsonObject())
    put("hotExecutionValues", hot.toEncodedJsonObject())
    put("desiredHotExecutionValues", desiredHot.toEncodedJsonObject())
    put("modelBehaviorValues", behavior.toEncodedJsonObject())
    put("desiredModelBehaviorValues", desiredBehavior.toEncodedJsonObject())
    put("userOverrides", JSONArray().also { array -> userOverrides.toSortedSet().forEach { array.put(it) } })
    put("resolvedAt", resolvedAt)
}.toString()

/**
 * Older snapshots did not preserve desired hot/behavior values. Their original
 * desired digest cannot be reconstructed after a runtime normalization, so the
 * next formal load must write a complete snapshot under a fresh profile id.
 */
internal fun ExecutionProfileEntity.requiresDesiredExecutionSnapshotMigration(): Boolean {
    RuntimeProfilePersistencePolicy.requireSafeProfileJson(profileJson)
    val root = JSONObject(profileJson)
    val hasDesiredHot = root.has("desiredHotExecutionValues")
    val hasDesiredBehavior = root.has("desiredModelBehaviorValues")
    require(hasDesiredHot == hasDesiredBehavior) {
        "Profile has an incomplete desired execution snapshot."
    }
    return !hasDesiredHot
}

private fun CanonicalParameterSet.bindResources(
    slot: String,
    profile: ModelExecutionProfile,
    bindings: MutableList<RuntimeResourceBindingEntity>,
    now: Long
): CanonicalParameterSet = CanonicalParameterSet.fromEncoded(
    encodedValues.toSortedMap().mapValues { (field, encoded) ->
        val raw = encoded.takeIf { it.startsWith("s:") }?.substring(2)
        if (raw == null || !RuntimeProfilePersistencePolicy.isInstallationLocalResource(field, raw)) {
            encoded
        } else {
            require(raw.length <= MAX_RESOURCE_VALUE_CHARS) { "Runtime resource reference is too large." }
            val bindingField = "$slot:$field"
            val bindingId = "rb_" + persistenceSha256(
                "${profile.runtimeIdentity.identityHash}\n${profile.profileId}\n$bindingField"
            ).take(40)
            bindings += RuntimeResourceBindingEntity(
                bindingId = bindingId,
                identityKey = profile.runtimeIdentity.identityHash,
                profileId = profile.profileId,
                field = bindingField,
                installationScopeId = profile.runtimeIdentity.installationScopeId,
                resourceFingerprint = persistenceSha256(raw),
                resourceValue = raw,
                createdAt = now,
                updatedAt = now
            )
            "s:$RESOURCE_BINDING_PREFIX$bindingId"
        }
    }
)

private fun restoreParameterSet(
    encodedRoot: JSONObject,
    slot: String,
    identity: ModelRuntimeIdentity,
    profileId: String,
    bindingsById: Map<String, RuntimeResourceBindingEntity>,
    consumedBindings: MutableSet<String>
): CanonicalParameterSet = CanonicalParameterSet.fromEncoded(
    buildMap {
        encodedRoot.keys().asSequence().toList().sorted().forEach { field ->
            val encoded = encodedRoot.getString(field)
            val reference = encoded.takeIf { it.startsWith("s:$RESOURCE_BINDING_PREFIX") }
                ?.removePrefix("s:$RESOURCE_BINDING_PREFIX")
            if (reference == null) {
                put(field, encoded)
            } else {
                val binding = requireNotNull(bindingsById[reference]) { "Runtime resource binding is missing." }
                require(binding.identityKey == identity.identityHash && binding.profileId == profileId)
                require(binding.installationScopeId == identity.installationScopeId) {
                    "Runtime resource binding belongs to another installation."
                }
                require(binding.field == "$slot:$field") { "Runtime resource binding field mismatch." }
                require(binding.resourceFingerprint == persistenceSha256(binding.resourceValue)) {
                    "Runtime resource binding fingerprint mismatch."
                }
                consumedBindings += reference
                put(field, "s:${binding.resourceValue}")
            }
        }
    }
)

private fun CanonicalParameterSet.toEncodedJsonObject(): JSONObject = JSONObject().also { target ->
    encodedValues.toSortedMap().forEach { (field, encoded) -> target.put(field, encoded) }
}

private fun requirePersistableInstallationScope(value: String) {
    require(value.isNotBlank() && value != "local-installation" && !value.startsWith("process-local:")) {
        "A persistent installationScopeId is required before saving runtime profiles."
    }
}

internal fun persistenceSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

