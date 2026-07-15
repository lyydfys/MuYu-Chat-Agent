package com.muyuchat.mca

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.withTransaction
import com.muyuchat.core.engine.CanonicalParameterSet
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.QuarantinedOverride
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal enum class PersistedProfileRecordState { STAGED, COMMITTED, REJECTED }
internal enum class PersistedProfileVerificationLevel { SAFE, COMPATIBLE, DEVICE_VERIFIED }
internal enum class PersistedTuningJobState {
    QUEUED, RUNNING, PAUSED, CANCELING, VALIDATING, SUCCEEDED, FAILED, RECOVERING
}
internal enum class TuningJournalState {
    STAGED, APPLYING, VALIDATING, COMMITTED, REJECTED, RECOVERING, ROLLED_BACK, FAILED
}

@Entity(tableName = "runtime_installation_scope")
internal data class RuntimeInstallationScopeEntity(
    @PrimaryKey val scopeKey: String = INSTALLATION_SCOPE_KEY,
    val installationScopeId: String,
    val createdAt: Long
) {
    companion object {
        const val INSTALLATION_SCOPE_KEY = "device_installation"
    }
}

@Entity(
    tableName = "runtime_identity",
    indices = [Index("modelId"), Index("runtime"), Index("updatedAt")]
)
internal data class RuntimeIdentityEntity(
    @PrimaryKey val identityKey: String,
    val modelId: String,
    val runtime: String,
    val artifactFingerprint: String,
    val deviceCapabilityFingerprint: String,
    val installationScopeId: String,
    val runtimeFingerprint: String,
    val fieldPolicyFingerprint: String,
    val ruleFingerprint: String,
    val evaluatorFingerprint: String,
    val schemaVersion: Int,
    /** Canonical, complete identity snapshot used to reject partial/stale reconstruction. */
    val identityJson: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "execution_profile",
    indices = [
        Index("identityKey"),
        Index(value = ["identityKey", "revision"], unique = true),
        Index("recordState"),
        Index("updatedAt")
    ]
)
internal data class ExecutionProfileEntity(
    @PrimaryKey val profileId: String,
    val identityKey: String,
    val revision: Long,
    val parentCommittedProfileId: String?,
    val recordState: String,
    val verificationLevel: String,
    val desiredProfileSignature: String,
    val resolvedLoadSignature: String,
    val activeLoadedSignature: String,
    val committedExecutionSignature: String,
    val runtimeOverrideSignature: String,
    val effectiveExecutionSignature: String,
    val resolvedLoadJson: String,
    val committedExecutionJson: String,
    val quarantinedOverridesJson: String,
    val sourceSummaryJson: String,
    /** Canonical execution profile snapshot. Resource paths are binding references, never raw paths. */
    val profileJson: String,
    val failureStage: String?,
    val failureCode: String?,
    val failureSummary: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Installation-local indirection for SAF URIs and native file paths.
 *
 * This table lives in the same database that is excluded from cloud backup/device transfer. Exported
 * profile evidence contains only [bindingId] and [resourceFingerprint], never [resourceValue].
 */
@Entity(
    tableName = "runtime_resource_binding",
    indices = [
        Index("identityKey"),
        Index("profileId"),
        Index(value = ["profileId", "field"], unique = true)
    ]
)
internal data class RuntimeResourceBindingEntity(
    @PrimaryKey val bindingId: String,
    val identityKey: String,
    val profileId: String,
    val field: String,
    val installationScopeId: String,
    val resourceFingerprint: String,
    val resourceValue: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "profile_pointers")
internal data class ProfilePointersEntity(
    @PrimaryKey val identityKey: String,
    val activeProfileId: String?,
    val pendingProfileId: String?,
    val lastKnownGoodProfileId: String?,
    val rollbackTargetProfileId: String?,
    val updatedAt: Long
)

@Entity(
    tableName = "tuning_job",
    indices = [Index("identityKey"), Index("state"), Index("updatedAt")]
)
internal data class TuningJobEntity(
    @PrimaryKey val jobId: String,
    val identityKey: String,
    val state: String,
    val candidateProfileId: String?,
    val phase: String,
    val autoApplyLoadChanges: Boolean,
    val cancellationRequested: Boolean,
    val failureCode: String?,
    val failureSummary: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastHeartbeatAt: Long
)

@Entity(
    tableName = "tuning_journal",
    indices = [Index("identityKey"), Index("jobId"), Index("state"), Index("updatedAt")]
)
internal data class TuningJournalEntity(
    @PrimaryKey val transactionId: String,
    val identityKey: String,
    val jobId: String?,
    val pendingProfileId: String,
    val rollbackTargetProfileId: String?,
    val resolvedLoadSignature: String,
    val state: String,
    val stage: String,
    val recoveryAttempts: Int,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "candidate_measurement",
    indices = [Index("profileId"), Index("jobId"), Index("createdAt")]
)
internal data class CandidateMeasurementEntity(
    @PrimaryKey val measurementId: String,
    val profileId: String,
    val jobId: String?,
    val correctnessPassed: Boolean,
    val safetyPassed: Boolean,
    val effectiveSignatureMatched: Boolean,
    val accepted: Boolean,
    val metricsJson: String,
    val failureCode: String?,
    val createdAt: Long
)

internal data class RuntimeRecoveryPlan(
    val transactionId: String,
    val identityKey: String,
    val rejectedProfileId: String,
    val rollbackProfileId: String?,
    val recoveryAttempt: Int
)

internal data class PersistedExecutionProfileSnapshot(
    val profile: ExecutionProfileEntity,
    val resourceBindings: List<RuntimeResourceBindingEntity>
)

internal data class RecoveryAttemptDecision(
    val canRecover: Boolean,
    val nextAttempts: Int,
    val terminalStage: String
)

/** Transaction-consistent read model used by MainViewModel and the Local API control plane. */
internal data class CurrentRuntimeProfileState(
    val identity: RuntimeIdentityEntity,
    val pointers: ProfilePointersEntity?,
    val activeProfile: ExecutionProfileEntity?,
    val activeExecutionProfile: ModelExecutionProfile?,
    val activeJob: TuningJobEntity?
)

/** Persisted, per-model summary used by the redacted Local API model catalog. */
internal data class PersistedModelRuntimeSummary(
    val modelId: String,
    val identityKey: String,
    val pointers: ProfilePointersEntity?,
    val activeProfile: ExecutionProfileEntity?,
    val pendingProfile: ExecutionProfileEntity?,
    val activeJob: TuningJobEntity?
)

/** The only unfinished transaction authorized to operate on an identity's pending profile. */
internal data class PendingRuntimeProfileTransaction(
    val pointers: ProfilePointersEntity,
    val pendingProfile: ExecutionProfileEntity,
    val pendingExecutionProfile: ModelExecutionProfile,
    val journal: TuningJournalEntity
)

/** Pure policy kept outside Room so transition, redaction, and retry bounds are JVM-testable. */
internal object RuntimeProfilePersistencePolicy {
    const val MAX_RECOVERY_ATTEMPTS = 1
    const val MAX_STAGE_CHARS = 96
    const val MAX_CODE_CHARS = 96
    const val MAX_FAILURE_SUMMARY_CHARS = 512
    const val MAX_DIAGNOSTIC_JSON_CHARS = 32 * 1024
    const val MAX_PROFILE_JSON_CHARS = 256 * 1024

    private val localPathPattern = Regex(
        "(?i)(?<![A-Za-z0-9:])(?:[A-Z]:[\\\\/]|/(?:data|storage|sdcard|mnt|home|Users|var|tmp|system|vendor|product|apex)(?:/|\\b))[^\\s,;\\\"']*"
    )
    private val bearerPattern = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+")
    private val secretPattern = Regex(
        "(?i)(?:sk-[A-Za-z0-9_-]{12,}|AIza[A-Za-z0-9_-]{20,}|(?:api[_-]?key|access[_-]?token|authorization|secret)\\s*[:=]\\s*[^\\s,;]+)"
    )
    private val sensitiveKeyPattern = Regex(
        "(?i)(?:prompt|messages?|conversation|authorization|api[_-]?key|access[_-]?token|secret|password|absolute[_-]?path)"
    )

    fun canUpdateJournalStage(from: TuningJournalState, to: TuningJournalState): Boolean =
        when (from) {
            TuningJournalState.STAGED -> to == TuningJournalState.STAGED ||
                to == TuningJournalState.APPLYING || to == TuningJournalState.VALIDATING
            TuningJournalState.APPLYING -> to == TuningJournalState.APPLYING ||
                to == TuningJournalState.VALIDATING
            TuningJournalState.VALIDATING -> to == TuningJournalState.VALIDATING
            else -> false
        }

    fun canTransitionTuningJob(
        from: PersistedTuningJobState,
        to: PersistedTuningJobState,
        cancellationRequested: Boolean = false
    ): Boolean {
        if (cancellationRequested && to !in CANCELLATION_COMPLETION_JOB_STATES) return false
        if (from == to) return true
        return to in when (from) {
            PersistedTuningJobState.QUEUED -> setOf(
                PersistedTuningJobState.RUNNING,
                PersistedTuningJobState.PAUSED,
                PersistedTuningJobState.CANCELING,
                PersistedTuningJobState.FAILED,
                PersistedTuningJobState.RECOVERING
            )
            PersistedTuningJobState.RUNNING -> setOf(
                PersistedTuningJobState.PAUSED,
                PersistedTuningJobState.CANCELING,
                PersistedTuningJobState.VALIDATING,
                PersistedTuningJobState.FAILED,
                PersistedTuningJobState.RECOVERING
            )
            PersistedTuningJobState.PAUSED -> setOf(
                PersistedTuningJobState.RUNNING,
                PersistedTuningJobState.CANCELING,
                PersistedTuningJobState.FAILED,
                PersistedTuningJobState.RECOVERING
            )
            PersistedTuningJobState.CANCELING -> setOf(
                PersistedTuningJobState.FAILED,
                PersistedTuningJobState.RECOVERING
            )
            PersistedTuningJobState.VALIDATING -> setOf(
                PersistedTuningJobState.CANCELING,
                PersistedTuningJobState.SUCCEEDED,
                PersistedTuningJobState.FAILED,
                PersistedTuningJobState.RECOVERING
            )
            PersistedTuningJobState.RECOVERING -> setOf(PersistedTuningJobState.FAILED)
            PersistedTuningJobState.SUCCEEDED,
            PersistedTuningJobState.FAILED -> emptySet()
        }
    }

    fun pauseTuningJobTarget(from: PersistedTuningJobState): PersistedTuningJobState? = when (from) {
        PersistedTuningJobState.QUEUED,
        PersistedTuningJobState.RUNNING,
        PersistedTuningJobState.PAUSED -> PersistedTuningJobState.PAUSED
        else -> null
    }

    fun resumeTuningJobTarget(from: PersistedTuningJobState): PersistedTuningJobState? = when (from) {
        PersistedTuningJobState.QUEUED,
        PersistedTuningJobState.RUNNING,
        PersistedTuningJobState.PAUSED -> PersistedTuningJobState.RUNNING
        else -> null
    }

    fun cancellationTuningJobTarget(from: PersistedTuningJobState): PersistedTuningJobState = when (from) {
        PersistedTuningJobState.QUEUED,
        PersistedTuningJobState.RUNNING,
        PersistedTuningJobState.PAUSED,
        PersistedTuningJobState.CANCELING,
        PersistedTuningJobState.VALIDATING -> PersistedTuningJobState.CANCELING
        PersistedTuningJobState.RECOVERING,
        PersistedTuningJobState.SUCCEEDED,
        PersistedTuningJobState.FAILED -> from
    }

    fun recoveryDecision(recoveryAttempts: Int, hasRollbackTarget: Boolean): RecoveryAttemptDecision {
        val boundedAttempts = recoveryAttempts.coerceAtLeast(0)
        return when {
            !hasRollbackTarget -> RecoveryAttemptDecision(false, boundedAttempts, "NO_ROLLBACK_TARGET")
            boundedAttempts >= MAX_RECOVERY_ATTEMPTS ->
                RecoveryAttemptDecision(false, boundedAttempts, "RECOVERY_LIMIT_REACHED")
            else -> RecoveryAttemptDecision(true, boundedAttempts + 1, "ROLLBACK_PENDING")
        }
    }

    fun sanitizeStage(value: String): String = sanitizeCode(value, MAX_STAGE_CHARS, "UNSPECIFIED_STAGE")

    fun sanitizeFailureCode(value: String): String = sanitizeCode(value, MAX_CODE_CHARS, "UNKNOWN_FAILURE")

    fun sanitizeFailureSummary(value: String): String = value
        .replace(localPathPattern, "<path>")
        .replace(bearerPattern, "Bearer <redacted>")
        .replace(secretPattern, "<redacted>")
        .take(MAX_FAILURE_SUMMARY_CHARS)

    fun sanitizeDiagnosticJson(value: String): String {
        if (value.isBlank()) return "{}"
        val sanitized = runCatching {
            when (val parsed = if (value.trimStart().startsWith("[")) JSONArray(value) else JSONObject(value)) {
                is JSONObject -> sanitizeObject(parsed, 0)
                is JSONArray -> sanitizeArray(parsed, 0)
                else -> JSONObject().put("summary", sanitizeFailureSummary(value))
            }.toString()
        }.getOrElse {
            JSONObject().put("summary", sanitizeFailureSummary(value)).toString()
        }
        return if (sanitized.length <= MAX_DIAGNOSTIC_JSON_CHARS) sanitized else JSONObject()
            .put("summary", sanitizeFailureSummary(value))
            .put("truncated", true)
            .toString()
    }

    fun requireSafeProfileJson(value: String) {
        require(value.length <= MAX_PROFILE_JSON_CHARS) { "Profile snapshot is too large." }
        require(!localPathPattern.containsMatchIn(value)) { "Profile snapshot contains a raw local path." }
        require(!bearerPattern.containsMatchIn(value) && !secretPattern.containsMatchIn(value)) {
            "Profile snapshot contains credential-like material."
        }
    }

    fun isInstallationLocalResource(field: String, rawValue: String): Boolean {
        val normalizedField = field.lowercase()
        return normalizedField.endsWith("_path") || normalizedField.endsWith("_uri") ||
            rawValue.startsWith("content://", ignoreCase = true) ||
            rawValue.startsWith("file://", ignoreCase = true) ||
            localPathPattern.containsMatchIn(rawValue)
    }

    private fun sanitizeCode(value: String, maxChars: Int, fallback: String): String = value
        .trim()
        .uppercase()
        .replace(Regex("[^A-Z0-9_.-]+"), "_")
        .trim('_')
        .take(maxChars)
        .ifBlank { fallback }

    private fun sanitizeObject(source: JSONObject, depth: Int): JSONObject {
        if (depth >= 8) return JSONObject().put("truncated", true)
        return JSONObject().also { target ->
            source.keys().asSequence().toList().sorted().take(256).forEach { key ->
                val value = source.opt(key)
                target.put(
                    key.take(96),
                    if (sensitiveKeyPattern.containsMatchIn(key)) "<redacted>"
                    else sanitizeJsonValue(value, depth + 1)
                )
            }
        }
    }

    private fun sanitizeArray(source: JSONArray, depth: Int): JSONArray {
        if (depth >= 8) return JSONArray().put("<truncated>")
        return JSONArray().also { target ->
            repeat(minOf(source.length(), 256)) { index ->
                target.put(sanitizeJsonValue(source.opt(index), depth + 1))
            }
        }
    }

    private fun sanitizeJsonValue(value: Any?, depth: Int): Any? = when (value) {
        null, JSONObject.NULL -> JSONObject.NULL
        is JSONObject -> sanitizeObject(value, depth)
        is JSONArray -> sanitizeArray(value, depth)
        is String -> sanitizeFailureSummary(value)
        is Number, is Boolean -> value
        else -> sanitizeFailureSummary(value.toString())
    }

    private val CANCELLATION_COMPLETION_JOB_STATES = setOf(
        PersistedTuningJobState.CANCELING,
        PersistedTuningJobState.RECOVERING,
        PersistedTuningJobState.FAILED
    )
}

private const val IDENTITY_SNAPSHOT_FORMAT_VERSION = 1
private const val PROFILE_SNAPSHOT_FORMAT_VERSION = 1
private const val RESOURCE_BINDING_PREFIX = "@resource-binding:"
private const val MAX_RESOURCE_VALUE_CHARS = 8 * 1024

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
    val behavior = modelBehaviorValues.bindResources("behavior", this, bindings, now)
    val profileJson = canonicalProfileJson(desired, resolved, hot, behavior)
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
        hotExecutionValues = restore("hotExecutionValues", "hot"),
        modelBehaviorValues = restore("modelBehaviorValues", "behavior"),
        profileId = profileId,
        revision = revision,
        userOverrides = root.getJSONArray("userOverrides").let { array ->
            buildSet { repeat(array.length()) { index -> add(array.getString(index)) } }
        },
        quarantinedOverrides = quarantinedOverridesJson.toQuarantinedOverrides(),
        resolvedAt = root.getLong("resolvedAt")
    )
    require(consumedBindings == bindingsById.keys) { "Profile has unreferenced runtime resource bindings." }
    require(profile.desiredSignature.digest == desiredProfileSignature) { "Desired profile signature mismatch." }
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
    behavior: CanonicalParameterSet
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
    put("modelBehaviorValues", behavior.toEncodedJsonObject())
    put("userOverrides", JSONArray().also { array -> userOverrides.toSortedSet().forEach { array.put(it) } })
    put("resolvedAt", resolvedAt)
}.toString()

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

private fun persistenceSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

@Dao
internal interface ModelRuntimeProfileDao {
    @Query("SELECT * FROM runtime_installation_scope WHERE scopeKey = :scopeKey LIMIT 1")
    suspend fun installationScope(scopeKey: String): RuntimeInstallationScopeEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInstallationScope(scope: RuntimeInstallationScopeEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIdentity(identity: RuntimeIdentityEntity)

    @Update
    suspend fun updateIdentity(identity: RuntimeIdentityEntity): Int

    @Query("SELECT * FROM runtime_identity WHERE identityKey = :identityKey LIMIT 1")
    suspend fun identity(identityKey: String): RuntimeIdentityEntity?

    @Query("SELECT * FROM runtime_identity WHERE modelId = :modelId ORDER BY updatedAt DESC, identityKey DESC LIMIT 1")
    suspend fun latestIdentityForModel(modelId: String): RuntimeIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProfile(profile: ExecutionProfileEntity)

    @Update
    suspend fun updateProfile(profile: ExecutionProfileEntity): Int

    @Query("SELECT * FROM execution_profile WHERE profileId = :profileId LIMIT 1")
    suspend fun profile(profileId: String): ExecutionProfileEntity?

    @Query("SELECT * FROM execution_profile WHERE identityKey = :identityKey ORDER BY revision DESC")
    suspend fun profiles(identityKey: String): List<ExecutionProfileEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertResourceBindings(bindings: List<RuntimeResourceBindingEntity>)

    @Query("SELECT * FROM runtime_resource_binding WHERE profileId = :profileId ORDER BY field ASC")
    suspend fun resourceBindings(profileId: String): List<RuntimeResourceBindingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPointers(pointers: ProfilePointersEntity)

    @Query("SELECT * FROM profile_pointers WHERE identityKey = :identityKey LIMIT 1")
    suspend fun pointers(identityKey: String): ProfilePointersEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJob(job: TuningJobEntity)

    @Query("SELECT * FROM tuning_job WHERE jobId = :jobId LIMIT 1")
    suspend fun job(jobId: String): TuningJobEntity?

    @Query("SELECT * FROM tuning_job WHERE identityKey = :identityKey AND state IN (:states) ORDER BY updatedAt DESC")
    suspend fun jobsInStates(identityKey: String, states: List<String>): List<TuningJobEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJournal(journal: TuningJournalEntity)

    @Update
    suspend fun updateJournal(journal: TuningJournalEntity): Int

    @Query("SELECT * FROM tuning_journal WHERE transactionId = :transactionId LIMIT 1")
    suspend fun journal(transactionId: String): TuningJournalEntity?

    @Query("SELECT * FROM tuning_journal WHERE state IN (:states) ORDER BY updatedAt ASC")
    suspend fun journalsInStates(states: List<String>): List<TuningJournalEntity>

    @Query("SELECT * FROM tuning_journal WHERE identityKey = :identityKey AND state IN (:states) ORDER BY updatedAt DESC")
    suspend fun journalsInStates(identityKey: String, states: List<String>): List<TuningJournalEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMeasurement(measurement: CandidateMeasurementEntity)

    @Query("DELETE FROM candidate_measurement WHERE profileId = :profileId AND measurementId IN (SELECT measurementId FROM candidate_measurement WHERE profileId = :profileId ORDER BY createdAt DESC, measurementId DESC LIMIT -1 OFFSET :keepCount)")
    suspend fun pruneMeasurements(profileId: String, keepCount: Int)

    @Query("DELETE FROM execution_profile WHERE recordState = 'REJECTED' AND updatedAt < :olderThan AND profileId NOT IN (SELECT activeProfileId FROM profile_pointers WHERE activeProfileId IS NOT NULL) AND profileId NOT IN (SELECT pendingProfileId FROM profile_pointers WHERE pendingProfileId IS NOT NULL) AND profileId NOT IN (SELECT lastKnownGoodProfileId FROM profile_pointers WHERE lastKnownGoodProfileId IS NOT NULL) AND profileId NOT IN (SELECT rollbackTargetProfileId FROM profile_pointers WHERE rollbackTargetProfileId IS NOT NULL) AND profileId NOT IN (SELECT parentCommittedProfileId FROM execution_profile WHERE parentCommittedProfileId IS NOT NULL) AND profileId NOT IN (SELECT pendingProfileId FROM tuning_journal)")
    suspend fun pruneRejectedProfiles(olderThan: Long)

    @Query("DELETE FROM candidate_measurement WHERE profileId NOT IN (SELECT profileId FROM execution_profile)")
    suspend fun pruneOrphanMeasurements()

    @Query("DELETE FROM runtime_resource_binding WHERE profileId NOT IN (SELECT profileId FROM execution_profile)")
    suspend fun pruneOrphanResourceBindings()

    @Query("DELETE FROM tuning_journal WHERE state IN (:terminalStates) AND updatedAt < :olderThan")
    suspend fun pruneTerminalJournalsByAge(terminalStates: List<String>, olderThan: Long)

    @Query("DELETE FROM tuning_journal WHERE transactionId IN (SELECT transactionId FROM tuning_journal WHERE state IN (:terminalStates) ORDER BY updatedAt DESC, transactionId DESC LIMIT -1 OFFSET :keepCount)")
    suspend fun pruneTerminalJournalsByCount(terminalStates: List<String>, keepCount: Int)

    @Query("DELETE FROM tuning_job WHERE state IN (:terminalStates) AND updatedAt < :olderThan")
    suspend fun pruneTerminalJobsByAge(terminalStates: List<String>, olderThan: Long)

    @Query("DELETE FROM tuning_job WHERE jobId IN (SELECT jobId FROM tuning_job WHERE state IN (:terminalStates) ORDER BY updatedAt DESC, jobId DESC LIMIT -1 OFFSET :keepCount)")
    suspend fun pruneTerminalJobsByCount(terminalStates: List<String>, keepCount: Int)
}

@Database(
    entities = [
        RuntimeInstallationScopeEntity::class,
        RuntimeIdentityEntity::class,
        ExecutionProfileEntity::class,
        RuntimeResourceBindingEntity::class,
        ProfilePointersEntity::class,
        TuningJobEntity::class,
        TuningJournalEntity::class,
        CandidateMeasurementEntity::class
    ],
    version = 1,
    exportSchema = false
)
internal abstract class ModelRuntimeProfileDatabase : RoomDatabase() {
    abstract fun dao(): ModelRuntimeProfileDao

    companion object {
        const val DATABASE_NAME = "mca-runtime.db"

        @Volatile
        private var instance: ModelRuntimeProfileDatabase? = null

        fun get(context: Context): ModelRuntimeProfileDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ModelRuntimeProfileDatabase::class.java,
                    DATABASE_NAME
                ).build().also { instance = it }
            }
    }
}

/**
 * Transactional persistence for model/runtime/device-specific execution state.
 * It never stores prompts, API keys, absolute model paths, or hardware IDs.
 */
internal class ModelRuntimeProfileStore(context: Context) {
    private val database = ModelRuntimeProfileDatabase.get(context)
    private val dao = database.dao()

    suspend fun installationScopeId(now: Long = System.currentTimeMillis()): String =
        database.withTransaction {
            installationScopeIdLocked(now)
        }

    suspend fun upsertIdentity(identity: RuntimeIdentityEntity) {
        require(identity.identityKey.isNotBlank() && identity.modelId.isNotBlank())
        require(identity.artifactFingerprint.isNotBlank())
        require(identity.deviceCapabilityFingerprint.isNotBlank())
        require(identity.installationScopeId.isNotBlank())
        val reconstructed = identity.toModelRuntimeIdentity()
        require(reconstructed.identityHash == identity.identityKey)
        database.withTransaction {
            val localScope = installationScopeIdLocked(identity.updatedAt)
            require(identity.installationScopeId == localScope) {
                "Runtime identity belongs to another app installation."
            }
            val existing = dao.identity(identity.identityKey)
            if (existing == null) {
                dao.insertIdentity(identity)
            } else {
                val updated = identity.copy(createdAt = existing.createdAt)
                require(existing.copy(updatedAt = updated.updatedAt) == updated) {
                    "Runtime identity is immutable; use a new identityKey after any fingerprint change."
                }
                check(dao.updateIdentity(updated) == 1) { "Runtime identity update was lost." }
            }
        }
    }

    suspend fun currentRuntimeState(identityKey: String): CurrentRuntimeProfileState? =
        database.withTransaction {
            require(identityKey.isNotBlank())
            val identity = dao.identity(identityKey) ?: return@withTransaction null
            val runtimeIdentity = validateLocalIdentity(identity)
            val pointers = dao.pointers(identityKey)
            if (pointers != null) validatePointers(pointers, identityKey)
            val activeProfile = pointers?.activeProfileId?.let { profileId ->
                requireNotNull(dao.profile(profileId)) { "Active profile pointer is dangling." }.also { profile ->
                    validatePersistedProfile(profile)
                    require(profile.identityKey == identityKey) { "Active profile belongs to another identity." }
                    require(profile.recordState == PersistedProfileRecordState.COMMITTED.name) {
                        "Active profile is not committed."
                    }
                }
            }
            CurrentRuntimeProfileState(
                identity = identity,
                pointers = pointers,
                activeProfile = activeProfile,
                activeExecutionProfile = activeProfile?.toModelExecutionProfile(
                    runtimeIdentity,
                    dao.resourceBindings(activeProfile.profileId)
                ),
                activeJob = activeTuningJobLocked(identityKey)
            )
        }

    /**
     * Returns one installation-local runtime summary for each requested model.
     *
     * The query deliberately never returns profile JSON, resource bindings, absolute paths, or
     * device identifiers. Callers may expose only the small state vocabulary represented here.
     */
    suspend fun modelRuntimeSummaries(modelIds: Collection<String>): List<PersistedModelRuntimeSummary> =
        database.withTransaction {
            val summaries = mutableListOf<PersistedModelRuntimeSummary>()
            val distinctModelIds = modelIds.asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .toList()
            for (modelId in distinctModelIds) {
                val identity = dao.latestIdentityForModel(modelId) ?: continue
                validateLocalIdentity(identity)
                val pointers = dao.pointers(identity.identityKey)?.also { value ->
                    validatePointers(value, identity.identityKey)
                }
                val activeProfile = pointers?.activeProfileId?.let { profileId ->
                    requireNotNull(dao.profile(profileId)).also(::validatePersistedProfile)
                }
                val pendingProfile = pointers?.pendingProfileId?.let { profileId ->
                    requireNotNull(dao.profile(profileId)).also(::validatePersistedProfile)
                }
                summaries += PersistedModelRuntimeSummary(
                    modelId = modelId,
                    identityKey = identity.identityKey,
                    pointers = pointers,
                    activeProfile = activeProfile,
                    pendingProfile = pendingProfile,
                    activeJob = activeTuningJobLocked(identity.identityKey)
                )
            }
            summaries
        }

    suspend fun activeTuningJob(identityKey: String): TuningJobEntity? = database.withTransaction {
        require(identityKey.isNotBlank())
        val identity = dao.identity(identityKey) ?: return@withTransaction null
        validateLocalIdentity(identity)
        activeTuningJobLocked(identityKey)
    }

    suspend fun tuningJob(jobId: String): TuningJobEntity? = database.withTransaction {
        require(jobId.isNotBlank())
        val job = dao.job(jobId)
        if (job != null) {
            validateTuningJob(job)
            val identity = requireNotNull(dao.identity(job.identityKey)) {
                "Tuning job runtime identity is missing."
            }
            validateLocalIdentity(identity)
        }
        job
    }

    suspend fun pendingTransaction(identityKey: String): PendingRuntimeProfileTransaction? =
        database.withTransaction {
            require(identityKey.isNotBlank())
            val identity = requireNotNull(dao.identity(identityKey)) { "Runtime identity is missing." }
            val runtimeIdentity = validateLocalIdentity(identity)
            val pointers = dao.pointers(identityKey) ?: return@withTransaction null
            validatePointers(pointers, identityKey)
            val pendingProfileId = pointers.pendingProfileId ?: return@withTransaction null
            val pendingProfile = requireNotNull(dao.profile(pendingProfileId)) {
                "Pending profile pointer is dangling."
            }
            validatePersistedProfile(pendingProfile)
            require(pendingProfile.identityKey == identityKey) { "Pending profile belongs to another identity." }
            require(pendingProfile.recordState == PersistedProfileRecordState.STAGED.name) {
                "Pending profile is not staged."
            }
            val journals = dao.journalsInStates(identityKey, RECOVERABLE_JOURNAL_STATE_NAMES)
                .filter { it.pendingProfileId == pendingProfileId }
            require(journals.size == 1) {
                "Pending profile must have exactly one unfinished tuning transaction."
            }
            val journal = journals.single()
            require(journal.resolvedLoadSignature == pendingProfile.resolvedLoadSignature) {
                "Pending profile signature does not match its tuning transaction."
            }
            journal.jobId?.let { jobId ->
                val job = requireNotNull(dao.job(jobId)) { "Pending transaction tuning job is missing." }
                validateTuningJob(job)
                require(job.identityKey == identityKey) { "Pending transaction job belongs to another identity." }
                require(job.state in ACTIVE_JOB_STATE_NAMES) {
                    "Pending transaction points at a terminal tuning job."
                }
                require(job.candidateProfileId == pendingProfileId) {
                    "Pending transaction job points at another candidate."
                }
            }
            PendingRuntimeProfileTransaction(
                pointers = pointers,
                pendingProfile = pendingProfile,
                pendingExecutionProfile = pendingProfile.toModelExecutionProfile(
                    runtimeIdentity,
                    dao.resourceBindings(pendingProfile.profileId)
                ),
                journal = journal
            )
        }

    suspend fun createTuningJob(
        identityKey: String,
        autoApplyLoadChanges: Boolean,
        jobId: String = UUID.randomUUID().toString(),
        phase: String = "QUEUED",
        now: Long = System.currentTimeMillis()
    ): TuningJobEntity = database.withTransaction {
        require(identityKey.isNotBlank() && jobId.isNotBlank())
        val identity = requireNotNull(dao.identity(identityKey)) {
            "Runtime identity must be persisted before creating a tuning job."
        }
        validateLocalIdentity(identity)
        dao.job(jobId)?.let { existing ->
            validateTuningJob(existing)
            require(existing.identityKey == identityKey) { "Tuning job id belongs to another identity." }
            require(existing.autoApplyLoadChanges == autoApplyLoadChanges) {
                "Tuning job id was reused with different auto-apply authorization."
            }
            return@withTransaction existing
        }
        require(activeTuningJobLocked(identityKey) == null) {
            "A tuning job is already active for this runtime identity."
        }
        val created = TuningJobEntity(
            jobId = jobId,
            identityKey = identityKey,
            state = PersistedTuningJobState.QUEUED.name,
            candidateProfileId = null,
            phase = RuntimeProfilePersistencePolicy.sanitizeStage(phase),
            autoApplyLoadChanges = autoApplyLoadChanges,
            cancellationRequested = false,
            failureCode = null,
            failureSummary = null,
            createdAt = now,
            updatedAt = now,
            lastHeartbeatAt = now
        )
        validateTuningJob(created)
        dao.upsertJob(created)
        created
    }

    suspend fun transitionTuningJob(
        jobId: String,
        state: PersistedTuningJobState,
        phase: String,
        failureCode: String? = null,
        failureSummary: String? = null,
        now: Long = System.currentTimeMillis()
    ): TuningJobEntity = database.withTransaction {
        val current = requireTuningJobLocked(jobId)
        updateTuningJobLocked(
            current = current,
            targetState = state,
            phase = phase,
            failureCode = failureCode,
            failureSummary = failureSummary,
            now = now
        )
    }

    suspend fun heartbeatTuningJob(
        jobId: String,
        phase: String? = null,
        now: Long = System.currentTimeMillis()
    ): TuningJobEntity = database.withTransaction {
        val current = requireTuningJobLocked(jobId)
        val state = persistedTuningJobState(current.state)
        if (state in TERMINAL_JOB_STATES) return@withTransaction current
        require(
            RuntimeProfilePersistencePolicy.canTransitionTuningJob(
                from = state,
                to = state,
                cancellationRequested = current.cancellationRequested
            )
        ) { "A canceled tuning job cannot continue reporting active work." }
        persistTuningJob(
            current.copy(
                phase = RuntimeProfilePersistencePolicy.sanitizeStage(phase ?: current.phase),
                updatedAt = monotonicJobTime(current, now),
                lastHeartbeatAt = monotonicJobTime(current, now)
            )
        )
    }

    suspend fun pauseTuningJob(
        jobId: String,
        phase: String = "PAUSED",
        now: Long = System.currentTimeMillis()
    ): TuningJobEntity = database.withTransaction {
        val current = requireTuningJobLocked(jobId)
        val from = persistedTuningJobState(current.state)
        val target = requireNotNull(RuntimeProfilePersistencePolicy.pauseTuningJobTarget(from)) {
            "Tuning job cannot be paused from ${from.name}."
        }
        updateTuningJobLocked(current, target, phase, now = now)
    }

    suspend fun resumeTuningJob(
        jobId: String,
        phase: String = "RUNNING",
        now: Long = System.currentTimeMillis()
    ): TuningJobEntity = database.withTransaction {
        val current = requireTuningJobLocked(jobId)
        val from = persistedTuningJobState(current.state)
        val target = requireNotNull(RuntimeProfilePersistencePolicy.resumeTuningJobTarget(from)) {
            "Tuning job cannot be resumed from ${from.name}."
        }
        updateTuningJobLocked(current, target, phase, now = now)
    }

    suspend fun requestTuningJobCancellation(
        jobId: String,
        phase: String = "CANCEL_REQUESTED",
        now: Long = System.currentTimeMillis()
    ): TuningJobEntity = database.withTransaction {
        val current = requireTuningJobLocked(jobId)
        val from = persistedTuningJobState(current.state)
        val target = RuntimeProfilePersistencePolicy.cancellationTuningJobTarget(from)
        if (target == from && from in NON_INTERRUPTIBLE_OR_TERMINAL_JOB_STATES) {
            return@withTransaction current
        }
        updateTuningJobLocked(
            current = current,
            targetState = target,
            phase = phase,
            cancellationRequested = true,
            now = now
        )
    }

    suspend fun stageCandidate(
        profile: ExecutionProfileEntity,
        transactionId: String,
        rollbackTargetProfileId: String?,
        job: TuningJobEntity? = null,
        resourceBindings: List<RuntimeResourceBindingEntity> = emptyList(),
        now: Long = System.currentTimeMillis()
    ): ProfilePointersEntity = database.withTransaction {
        require(profile.recordState == PersistedProfileRecordState.STAGED.name)
        require(profile.profileId.isNotBlank() && profile.identityKey.isNotBlank())
        require(profile.resolvedLoadSignature.isNotBlank())
        require(transactionId.isNotBlank())
        require(profile.runtimeOverrideSignature == "NONE") { "Request-scoped runtime overrides must not be persisted." }
        validatePersistedProfile(profile)
        val identityEntity = requireNotNull(dao.identity(profile.identityKey)) {
            "Runtime identity must be persisted before a profile."
        }
        val identity = identityEntity.toModelRuntimeIdentity()
        require(identity.installationScopeId == installationScopeIdLocked(now)) {
            "Runtime profile belongs to another app installation."
        }
        profile.toModelExecutionProfile(identity, resourceBindings)

        dao.journal(transactionId)?.let { existingJournal ->
            val existingProfile = requireNotNull(dao.profile(existingJournal.pendingProfileId)) {
                "Idempotent transaction lost its pending profile."
            }
            val existingPointers = requireNotNull(dao.pointers(existingJournal.identityKey)) {
                "Idempotent transaction lost its profile pointers."
            }
            require(existingJournal.state == TuningJournalState.STAGED.name)
            require(existingJournal.identityKey == profile.identityKey)
            require(existingJournal.pendingProfileId == profile.profileId)
            require(existingJournal.resolvedLoadSignature == profile.resolvedLoadSignature)
            require(
                existingProfile == profile.copy(
                    parentCommittedProfileId = existingProfile.parentCommittedProfileId,
                    createdAt = existingProfile.createdAt,
                    updatedAt = existingProfile.updatedAt
                ) && existingPointers.pendingProfileId == profile.profileId
            ) {
                "Transaction id was reused with a different candidate."
            }
            val storedBindings = dao.resourceBindings(profile.profileId)
            val replayBindings = resourceBindings.sortedBy { it.field }
            require(
                storedBindings.size == replayBindings.size && storedBindings.zip(replayBindings).all { (stored, replay) ->
                    stored == replay.copy(createdAt = stored.createdAt, updatedAt = stored.updatedAt)
                }
            ) {
                "Transaction id was reused with different resource bindings."
            }
            return@withTransaction existingPointers
        }
        require(dao.profile(profile.profileId) == null) { "Profile id already exists." }
        require(
            dao.journalsInStates(profile.identityKey, RECOVERABLE_JOURNAL_STATE_NAMES).isEmpty()
        ) { "A previous profile transaction still requires recovery." }

        val current = dao.pointers(profile.identityKey) ?: ProfilePointersEntity(
            identityKey = profile.identityKey,
            activeProfileId = null,
            pendingProfileId = null,
            lastKnownGoodProfileId = null,
            rollbackTargetProfileId = null,
            updatedAt = now
        )
        require(current.pendingProfileId == null || current.pendingProfileId == profile.profileId) {
            "A different pending profile already exists for this runtime identity."
        }
        val selectedRollback = selectCommittedPointerTarget(
            identityKey = profile.identityKey,
            explicitProfileId = rollbackTargetProfileId,
            current = current
        )
        val parentProfileId = profile.parentCommittedProfileId ?: selectedRollback?.profileId
        if (parentProfileId != null) {
            val parent = requireNotNull(dao.profile(parentProfileId)) { "Parent committed profile is missing." }
            require(parent.identityKey == profile.identityKey)
            require(parent.recordState == PersistedProfileRecordState.COMMITTED.name)
        }
        if (job != null) {
            val sanitizedInputJob = job.copy(
                phase = RuntimeProfilePersistencePolicy.sanitizeStage(job.phase),
                failureCode = job.failureCode?.let(RuntimeProfilePersistencePolicy::sanitizeFailureCode),
                failureSummary = job.failureSummary?.let(RuntimeProfilePersistencePolicy::sanitizeFailureSummary)
            )
            val persistedJob = dao.job(job.jobId)
            val jobForWrite = persistedJob ?: sanitizedInputJob
            validateTuningJob(jobForWrite)
            require(jobForWrite.identityKey == profile.identityKey) {
                "Tuning job belongs to another runtime identity."
            }
            require(jobForWrite.candidateProfileId == null || jobForWrite.candidateProfileId == profile.profileId)
            require(jobForWrite.state in ACTIVE_JOB_STATE_NAMES) { "Cannot stage a candidate for a terminal tuning job." }
            val jobState = persistedTuningJobState(jobForWrite.state)
            require(
                !jobForWrite.cancellationRequested &&
                    jobState != PersistedTuningJobState.CANCELING &&
                    jobState != PersistedTuningJobState.RECOVERING
            ) {
                "Cannot stage a candidate for a canceled tuning job."
            }
            val otherJobs = dao.jobsInStates(profile.identityKey, ACTIVE_JOB_STATE_NAMES)
                .filterNot { it.jobId == jobForWrite.jobId }
            require(otherJobs.isEmpty()) { "A tuning job is already active for this runtime identity." }
            dao.upsertJob(
                jobForWrite.copy(
                    candidateProfileId = profile.profileId,
                    phase = RuntimeProfilePersistencePolicy.sanitizeStage(jobForWrite.phase),
                    failureCode = jobForWrite.failureCode?.let(RuntimeProfilePersistencePolicy::sanitizeFailureCode),
                    failureSummary = jobForWrite.failureSummary?.let(RuntimeProfilePersistencePolicy::sanitizeFailureSummary),
                    updatedAt = maxOf(now, jobForWrite.createdAt, jobForWrite.updatedAt, jobForWrite.lastHeartbeatAt),
                    lastHeartbeatAt = maxOf(now, jobForWrite.createdAt, jobForWrite.updatedAt, jobForWrite.lastHeartbeatAt)
                )
            )
        }
        dao.insertProfile(
            profile.copy(
                parentCommittedProfileId = parentProfileId,
                updatedAt = now
            )
        )
        if (resourceBindings.isNotEmpty()) {
            validateResourceBindings(profile, identity, resourceBindings)
            dao.insertResourceBindings(resourceBindings.sortedBy { it.field })
        }
        val pointers = current.copy(
            pendingProfileId = profile.profileId,
            rollbackTargetProfileId = selectedRollback?.profileId,
            updatedAt = now
        )
        dao.upsertPointers(pointers)
        dao.insertJournal(
            TuningJournalEntity(
                transactionId = transactionId,
                identityKey = profile.identityKey,
                jobId = job?.jobId,
                pendingProfileId = profile.profileId,
                rollbackTargetProfileId = pointers.rollbackTargetProfileId,
                resolvedLoadSignature = profile.resolvedLoadSignature,
                state = TuningJournalState.STAGED.name,
                stage = "STAGED",
                recoveryAttempts = 0,
                createdAt = now,
                updatedAt = now
            )
        )
        pointers
    }

    suspend fun stageCandidate(
        snapshot: PersistedExecutionProfileSnapshot,
        transactionId: String,
        rollbackTargetProfileId: String?,
        job: TuningJobEntity? = null,
        now: Long = System.currentTimeMillis()
    ): ProfilePointersEntity = stageCandidate(
        profile = snapshot.profile,
        transactionId = transactionId,
        rollbackTargetProfileId = rollbackTargetProfileId,
        job = job,
        resourceBindings = snapshot.resourceBindings,
        now = now
    )

    suspend fun updateJournalStage(
        transactionId: String,
        state: TuningJournalState,
        stage: String,
        now: Long = System.currentTimeMillis()
    ) = database.withTransaction {
        val journal = requireNotNull(dao.journal(transactionId)) { "Unknown tuning transaction." }
        val currentState = persistedJournalState(journal.state)
        require(RuntimeProfilePersistencePolicy.canUpdateJournalStage(currentState, state)) {
            "Illegal tuning journal transition: ${journal.state} -> ${state.name}."
        }
        check(
            dao.updateJournal(
                journal.copy(
                    state = state.name,
                    stage = RuntimeProfilePersistencePolicy.sanitizeStage(stage),
                    updatedAt = now
                )
            ) == 1
        ) { "Tuning journal update was lost." }
    }

    suspend fun commitCandidate(
        transactionId: String,
        verificationLevel: PersistedProfileVerificationLevel,
        activeLoadedSignature: String,
        effectiveExecutionSignature: String,
        now: Long = System.currentTimeMillis()
    ): ProfilePointersEntity = database.withTransaction {
        val journal = requireNotNull(dao.journal(transactionId)) { "Unknown tuning transaction." }
        require(journal.state == TuningJournalState.VALIDATING.name) {
            "A candidate can be committed only after validation."
        }
        val profile = requireNotNull(dao.profile(journal.pendingProfileId)) { "Pending profile is missing." }
        val pointers = requireNotNull(dao.pointers(journal.identityKey)) { "Profile pointers are missing." }
        require(profile.identityKey == journal.identityKey) { "Pending profile belongs to another identity." }
        require(pointers.pendingProfileId == profile.profileId) { "Pending profile pointer does not match the transaction." }
        require(profile.recordState == PersistedProfileRecordState.STAGED.name) { "Only a staged profile can be committed." }
        require(profile.resolvedLoadSignature == journal.resolvedLoadSignature) {
            "Resolved load signature changed after authorization."
        }
        require(activeLoadedSignature.isNotBlank() && effectiveExecutionSignature.isNotBlank())

        check(
            dao.updateProfile(
                profile.copy(
                recordState = PersistedProfileRecordState.COMMITTED.name,
                verificationLevel = verificationLevel.name,
                activeLoadedSignature = activeLoadedSignature,
                effectiveExecutionSignature = effectiveExecutionSignature,
                failureStage = null,
                failureCode = null,
                failureSummary = null,
                    updatedAt = now
                )
            )
            == 1
        ) { "Pending profile commit was lost." }
        val committedPointers = pointers.copy(
            activeProfileId = profile.profileId,
            pendingProfileId = null,
            lastKnownGoodProfileId = profile.profileId,
            rollbackTargetProfileId = null,
            updatedAt = now
        )
        dao.upsertPointers(committedPointers)
        check(
            dao.updateJournal(
                journal.copy(
                state = TuningJournalState.COMMITTED.name,
                stage = "COMMITTED",
                    updatedAt = now
                )
            )
            == 1
        ) { "Tuning journal commit was lost." }
        journal.jobId?.let { jobId ->
            val job = requireNotNull(dao.job(jobId)) { "Tuning job is missing for the candidate transaction." }
            validateTuningJob(job)
            require(persistedTuningJobState(job.state) == PersistedTuningJobState.VALIDATING) {
                "A tuning job can be committed only from VALIDATING."
            }
            require(!job.cancellationRequested) { "A canceled tuning job cannot be committed." }
            require(job.candidateProfileId == profile.profileId) {
                "Tuning job candidate does not match the transaction."
            }
            dao.upsertJob(
                job.copy(
                    state = PersistedTuningJobState.SUCCEEDED.name,
                    phase = "COMPLETED",
                    candidateProfileId = profile.profileId,
                    updatedAt = maxOf(now, job.createdAt, job.updatedAt, job.lastHeartbeatAt),
                    lastHeartbeatAt = maxOf(now, job.createdAt, job.updatedAt, job.lastHeartbeatAt)
                )
            )
        }
        committedPointers
    }

    suspend fun rejectCandidate(
        transactionId: String,
        failureStage: String,
        failureCode: String,
        failureSummary: String,
        now: Long = System.currentTimeMillis()
    ): RuntimeRecoveryPlan = database.withTransaction {
        val journal = requireNotNull(dao.journal(transactionId)) { "Unknown tuning transaction." }
        require(journal.state in REJECTABLE_JOURNAL_STATE_NAMES) {
            "Only an unfinished candidate transaction can be rejected."
        }
        val profile = requireNotNull(dao.profile(journal.pendingProfileId)) { "Pending profile is missing." }
        require(profile.identityKey == journal.identityKey)
        require(profile.recordState == PersistedProfileRecordState.STAGED.name) {
            "Only a staged candidate can be rejected."
        }
        val pointers = dao.pointers(journal.identityKey) ?: ProfilePointersEntity(
            identityKey = journal.identityKey,
            activeProfileId = null,
            pendingProfileId = profile.profileId,
            lastKnownGoodProfileId = null,
            rollbackTargetProfileId = journal.rollbackTargetProfileId,
            updatedAt = now
        )
        require(pointers.pendingProfileId == profile.profileId) { "Pending profile pointer does not match the transaction." }
        val sanitizedStage = RuntimeProfilePersistencePolicy.sanitizeStage(failureStage)
        val sanitizedCode = RuntimeProfilePersistencePolicy.sanitizeFailureCode(failureCode)
        val sanitizedSummary = RuntimeProfilePersistencePolicy.sanitizeFailureSummary(failureSummary)
        check(
            dao.updateProfile(
                profile.copy(
                recordState = PersistedProfileRecordState.REJECTED.name,
                    failureStage = sanitizedStage,
                    failureCode = sanitizedCode,
                    failureSummary = sanitizedSummary,
                    updatedAt = now
                )
            )
            == 1
        ) { "Rejected profile update was lost." }
        val rollback = resolveRecoveryTarget(journal, profile, pointers)
        val recovery = RuntimeProfilePersistencePolicy.recoveryDecision(
            journal.recoveryAttempts,
            rollback != null
        )
        dao.upsertPointers(
            pointers.copy(
                pendingProfileId = null,
                rollbackTargetProfileId = rollback?.profileId?.takeIf { recovery.canRecover },
                updatedAt = now
            )
        )
        check(
            dao.updateJournal(
                journal.copy(
                    rollbackTargetProfileId = rollback?.profileId?.takeIf { recovery.canRecover },
                    state = if (recovery.canRecover) {
                        TuningJournalState.RECOVERING.name
                    } else {
                        TuningJournalState.FAILED.name
                    },
                    stage = recovery.terminalStage,
                    recoveryAttempts = recovery.nextAttempts,
                    updatedAt = now
                )
            )
            == 1
        ) { "Tuning journal rejection was lost." }
        journal.jobId?.let { jobId ->
            dao.job(jobId)?.let { job ->
                dao.upsertJob(
                    job.copy(
                        state = if (recovery.canRecover) PersistedTuningJobState.RECOVERING.name else PersistedTuningJobState.FAILED.name,
                        phase = if (recovery.canRecover) "RECOVERING" else "FAILED",
                        failureCode = sanitizedCode,
                        failureSummary = sanitizedSummary,
                        updatedAt = now,
                        lastHeartbeatAt = now
                    )
                )
            }
        }
        RuntimeRecoveryPlan(
            transactionId,
            journal.identityKey,
            profile.profileId,
            rollback?.profileId?.takeIf { recovery.canRecover },
            recovery.nextAttempts
        )
    }

    suspend fun recoverInterruptedTransactions(
        now: Long = System.currentTimeMillis()
    ): List<RuntimeRecoveryPlan> = database.withTransaction {
        val recoverable = dao.journalsInStates(RECOVERABLE_JOURNAL_STATE_NAMES)
        val selectedTransactionIds = mutableSetOf<String>()
        recoverable.groupBy { it.identityKey }.forEach { (identityKey, journals) ->
            val pendingProfileId = dao.pointers(identityKey)?.pendingProfileId
            val selected = journals.firstOrNull { it.pendingProfileId == pendingProfileId }
                ?: journals.maxWith(compareBy<TuningJournalEntity> { it.updatedAt }.thenBy { it.transactionId })
            selectedTransactionIds += selected.transactionId
        }

        recoverable.filterNot { it.transactionId in selectedTransactionIds }.forEach { journal ->
            dao.profile(journal.pendingProfileId)
                ?.takeIf { it.recordState == PersistedProfileRecordState.STAGED.name }
                ?.let { profile ->
                    check(
                        dao.updateProfile(
                            profile.copy(
                                recordState = PersistedProfileRecordState.REJECTED.name,
                                failureStage = "PROCESS_RECOVERY",
                                failureCode = "SUPERSEDED_TRANSACTION",
                                failureSummary = "同一模型存在更新的未完成事务；旧事务已隔离。",
                                updatedAt = now
                            )
                        ) == 1
                    )
                }
            check(
                dao.updateJournal(
                    journal.copy(
                        state = TuningJournalState.FAILED.name,
                        stage = "SUPERSEDED_RECOVERY",
                        updatedAt = now
                    )
                ) == 1
            )
        }

        recoverable.filter { it.transactionId in selectedTransactionIds }.mapNotNull { journal ->
            val profile = dao.profile(journal.pendingProfileId)
            val pointers = dao.pointers(journal.identityKey) ?: ProfilePointersEntity(
                identityKey = journal.identityKey,
                activeProfileId = null,
                pendingProfileId = journal.pendingProfileId,
                lastKnownGoodProfileId = null,
                rollbackTargetProfileId = journal.rollbackTargetProfileId,
                updatedAt = now
            )
            if (profile?.recordState == PersistedProfileRecordState.COMMITTED.name) {
                check(
                    dao.updateJournal(
                        journal.copy(
                            state = TuningJournalState.FAILED.name,
                            stage = "INCONSISTENT_COMMITTED_PROFILE",
                            updatedAt = now
                        )
                    ) == 1
                )
                return@mapNotNull null
            }
            if (profile?.recordState == PersistedProfileRecordState.STAGED.name) {
                check(
                    dao.updateProfile(
                        profile.copy(
                        recordState = PersistedProfileRecordState.REJECTED.name,
                        failureStage = "PROCESS_RECOVERY",
                        failureCode = "INTERRUPTED_TRANSACTION",
                        failureSummary = "调优事务在进程退出前未完成。",
                            updatedAt = now
                        )
                    )
                    == 1
                ) { "Interrupted profile update was lost." }
            }
            val rollback = resolveRecoveryTarget(journal, profile, pointers)
            val recovery = RuntimeProfilePersistencePolicy.recoveryDecision(
                journal.recoveryAttempts,
                rollback != null
            )
            dao.upsertPointers(
                pointers.copy(
                    pendingProfileId = null,
                    rollbackTargetProfileId = rollback?.profileId?.takeIf { recovery.canRecover },
                    updatedAt = now
                )
            )
            check(
                dao.updateJournal(
                    journal.copy(
                        rollbackTargetProfileId = rollback?.profileId?.takeIf { recovery.canRecover },
                        state = if (recovery.canRecover) {
                            TuningJournalState.RECOVERING.name
                        } else {
                            TuningJournalState.FAILED.name
                        },
                        stage = if (recovery.canRecover) "PROCESS_RECOVERY" else recovery.terminalStage,
                        recoveryAttempts = recovery.nextAttempts,
                        updatedAt = now
                    )
                )
                == 1
            ) { "Interrupted tuning journal update was lost." }
            journal.jobId?.let { jobId ->
                dao.job(jobId)?.let { job ->
                    dao.upsertJob(
                        job.copy(
                            state = if (recovery.canRecover) PersistedTuningJobState.RECOVERING.name else PersistedTuningJobState.FAILED.name,
                            phase = if (recovery.canRecover) "PROCESS_RECOVERY" else recovery.terminalStage,
                            failureCode = "INTERRUPTED_TRANSACTION",
                            failureSummary = "调优事务在进程退出前未完成。",
                            updatedAt = now,
                            lastHeartbeatAt = now
                        )
                    )
                }
            }
            if (!recovery.canRecover) return@mapNotNull null
            RuntimeRecoveryPlan(
                transactionId = journal.transactionId,
                identityKey = journal.identityKey,
                rejectedProfileId = journal.pendingProfileId,
                rollbackProfileId = requireNotNull(rollback).profileId,
                recoveryAttempt = recovery.nextAttempts
            )
        }
    }

    suspend fun completeRecovery(
        transactionId: String,
        restoredProfileId: String?,
        now: Long = System.currentTimeMillis()
    ): ProfilePointersEntity = database.withTransaction {
        val journal = requireNotNull(dao.journal(transactionId)) { "Unknown tuning transaction." }
        require(journal.state == TuningJournalState.RECOVERING.name) {
            "Only a recovering transaction can be completed."
        }
        require(journal.recoveryAttempts == RuntimeProfilePersistencePolicy.MAX_RECOVERY_ATTEMPTS) {
            "Recovery was not authorized exactly once."
        }
        val pointers = requireNotNull(dao.pointers(journal.identityKey)) { "Profile pointers are missing." }
        if (restoredProfileId != null) {
            val restored = requireNotNull(dao.profile(restoredProfileId)) { "Rollback profile is missing." }
            require(restored.recordState == PersistedProfileRecordState.COMMITTED.name) { "Rollback profile is not committed." }
            require(restored.identityKey == journal.identityKey) { "Rollback profile belongs to another identity." }
            require(restoredProfileId == journal.rollbackTargetProfileId) { "Rollback profile was not authorized by the journal." }
            require(restoredProfileId == pointers.rollbackTargetProfileId) { "Rollback profile pointer changed during recovery." }
        }
        val completed = pointers.copy(
            activeProfileId = restoredProfileId ?: pointers.activeProfileId,
            pendingProfileId = null,
            lastKnownGoodProfileId = restoredProfileId ?: pointers.lastKnownGoodProfileId,
            rollbackTargetProfileId = null,
            updatedAt = now
        )
        dao.upsertPointers(completed)
        check(
            dao.updateJournal(
                journal.copy(
                    state = if (restoredProfileId == null) TuningJournalState.FAILED.name else TuningJournalState.ROLLED_BACK.name,
                    stage = if (restoredProfileId == null) "ROLLBACK_FAILED" else "ROLLED_BACK",
                    updatedAt = now
                )
            )
            == 1
        ) { "Recovery completion was lost." }
        journal.jobId?.let { jobId ->
            dao.job(jobId)?.let { job ->
                dao.upsertJob(
                    job.copy(
                        state = PersistedTuningJobState.FAILED.name,
                        phase = if (restoredProfileId == null) "ROLLBACK_FAILED" else "ROLLED_BACK",
                        updatedAt = now,
                        lastHeartbeatAt = now
                    )
                )
            }
        }
        completed
    }

    suspend fun recordMeasurement(measurement: CandidateMeasurementEntity, keepCount: Int = 256) {
        database.withTransaction {
            require(measurement.measurementId.isNotBlank())
            require(dao.profile(measurement.profileId) != null) { "Measurement profile is missing." }
            val sanitized = measurement.copy(
                metricsJson = RuntimeProfilePersistencePolicy.sanitizeDiagnosticJson(measurement.metricsJson),
                failureCode = measurement.failureCode?.let(RuntimeProfilePersistencePolicy::sanitizeFailureCode)
            )
            dao.insertMeasurement(sanitized)
            dao.pruneMeasurements(measurement.profileId, keepCount.coerceIn(32, 2048))
        }
    }

    suspend fun pruneRejectedProfiles(
        olderThan: Long = System.currentTimeMillis() - REJECTED_PROFILE_RETENTION_MS
    ) {
        database.withTransaction {
            dao.pruneRejectedProfiles(olderThan)
            dao.pruneOrphanMeasurements()
            dao.pruneOrphanResourceBindings()
        }
    }

    suspend fun pruneTerminalHistory(
        olderThan: Long = System.currentTimeMillis() - TERMINAL_HISTORY_RETENTION_MS,
        keepCount: Int = 512
    ) {
        database.withTransaction {
            val boundedCount = keepCount.coerceIn(64, 4096)
            dao.pruneTerminalJournalsByAge(TERMINAL_JOURNAL_STATE_NAMES, olderThan)
            dao.pruneTerminalJournalsByCount(TERMINAL_JOURNAL_STATE_NAMES, boundedCount)
            dao.pruneTerminalJobsByAge(TERMINAL_JOB_STATE_NAMES, olderThan)
            dao.pruneTerminalJobsByCount(TERMINAL_JOB_STATE_NAMES, boundedCount)
        }
    }

    suspend fun pointers(identityKey: String): ProfilePointersEntity? = dao.pointers(identityKey)
    suspend fun profile(profileId: String): ExecutionProfileEntity? = dao.profile(profileId)
    suspend fun profiles(identityKey: String): List<ExecutionProfileEntity> = dao.profiles(identityKey)

    suspend fun reconstructedProfile(profileId: String): ModelExecutionProfile? = database.withTransaction {
        val entity = dao.profile(profileId) ?: return@withTransaction null
        val identityEntity = requireNotNull(dao.identity(entity.identityKey)) { "Runtime identity is missing." }
        val identity = identityEntity.toModelRuntimeIdentity()
        require(identity.installationScopeId == installationScopeIdLocked(System.currentTimeMillis())) {
            "Persisted profile belongs to another app installation."
        }
        entity.toModelExecutionProfile(identity, dao.resourceBindings(profileId))
    }

    private suspend fun activeTuningJobLocked(identityKey: String): TuningJobEntity? {
        val jobs = dao.jobsInStates(identityKey, ACTIVE_JOB_STATE_NAMES)
        jobs.forEach { job ->
            validateTuningJob(job)
            job.candidateProfileId?.let { profileId ->
                val candidate = requireNotNull(dao.profile(profileId)) {
                    "Active tuning job candidate profile is missing."
                }
                require(candidate.identityKey == identityKey) {
                    "Active tuning job candidate belongs to another identity."
                }
            }
        }
        require(jobs.size <= 1) { "Multiple active tuning jobs exist for this runtime identity." }
        return jobs.singleOrNull()
    }

    private suspend fun requireTuningJobLocked(jobId: String): TuningJobEntity {
        require(jobId.isNotBlank())
        val job = requireNotNull(dao.job(jobId)) { "Unknown tuning job." }
        validateTuningJob(job)
        val identity = requireNotNull(dao.identity(job.identityKey)) {
            "Tuning job runtime identity is missing."
        }
        validateLocalIdentity(identity)
        return job
    }

    private suspend fun updateTuningJobLocked(
        current: TuningJobEntity,
        targetState: PersistedTuningJobState,
        phase: String,
        failureCode: String? = null,
        failureSummary: String? = null,
        cancellationRequested: Boolean = current.cancellationRequested ||
            targetState == PersistedTuningJobState.CANCELING,
        now: Long
    ): TuningJobEntity {
        val from = persistedTuningJobState(current.state)
        if (from == targetState && from in TERMINAL_JOB_STATES) return current
        require(
            RuntimeProfilePersistencePolicy.canTransitionTuningJob(
                from = from,
                to = targetState,
                cancellationRequested = current.cancellationRequested
            )
        ) { "Illegal tuning job transition: ${from.name} -> ${targetState.name}." }
        require(!current.cancellationRequested || cancellationRequested) {
            "A tuning job cancellation request is monotonic."
        }
        if (targetState.name in ACTIVE_JOB_STATE_NAMES) {
            val otherJobs = dao.jobsInStates(current.identityKey, ACTIVE_JOB_STATE_NAMES)
                .filterNot { it.jobId == current.jobId }
            require(otherJobs.isEmpty()) { "Another tuning job is active for this runtime identity." }
        }
        if (targetState == PersistedTuningJobState.SUCCEEDED && from != PersistedTuningJobState.SUCCEEDED) {
            val candidateProfileId = requireNotNull(current.candidateProfileId) {
                "A tuning job cannot succeed without a candidate profile."
            }
            val candidate = requireNotNull(dao.profile(candidateProfileId)) {
                "Successful tuning job candidate is missing."
            }
            val pointers = requireNotNull(dao.pointers(current.identityKey)) {
                "Successful tuning job profile pointers are missing."
            }
            require(candidate.recordState == PersistedProfileRecordState.COMMITTED.name) {
                "A tuning job cannot succeed before its candidate is committed."
            }
            require(pointers.activeProfileId == candidateProfileId && pointers.pendingProfileId == null) {
                "A tuning job cannot succeed before its candidate becomes active."
            }
        }
        val retainFailure = targetState == PersistedTuningJobState.FAILED ||
            targetState == PersistedTuningJobState.RECOVERING
        val timestamp = monotonicJobTime(current, now)
        return persistTuningJob(
            current.copy(
                state = targetState.name,
                phase = RuntimeProfilePersistencePolicy.sanitizeStage(phase),
                cancellationRequested = cancellationRequested,
                failureCode = if (retainFailure) {
                    failureCode?.let(RuntimeProfilePersistencePolicy::sanitizeFailureCode) ?: current.failureCode
                } else {
                    null
                },
                failureSummary = if (retainFailure) {
                    failureSummary?.let(RuntimeProfilePersistencePolicy::sanitizeFailureSummary) ?:
                        current.failureSummary
                } else {
                    null
                },
                updatedAt = timestamp,
                lastHeartbeatAt = timestamp
            )
        )
    }

    private suspend fun persistTuningJob(job: TuningJobEntity): TuningJobEntity {
        validateTuningJob(job)
        dao.upsertJob(job)
        return job
    }

    private fun validateTuningJob(job: TuningJobEntity) {
        require(job.jobId.isNotBlank() && job.identityKey.isNotBlank())
        val state = persistedTuningJobState(job.state)
        require(job.phase == RuntimeProfilePersistencePolicy.sanitizeStage(job.phase)) {
            "Tuning job phase must be canonical."
        }
        require(
            job.failureCode == null ||
                job.failureCode == RuntimeProfilePersistencePolicy.sanitizeFailureCode(job.failureCode)
        ) { "Tuning job failure code must be canonical." }
        require(
            job.failureSummary == null ||
                job.failureSummary == RuntimeProfilePersistencePolicy.sanitizeFailureSummary(job.failureSummary)
        ) { "Tuning job failure summary must be redacted." }
        require(job.updatedAt >= job.createdAt && job.lastHeartbeatAt >= job.createdAt) {
            "Tuning job timestamps are not monotonic."
        }
        require(state != PersistedTuningJobState.CANCELING || job.cancellationRequested) {
            "A canceling tuning job must retain its cancellation request."
        }
    }

    private suspend fun validateLocalIdentity(identity: RuntimeIdentityEntity): ModelRuntimeIdentity {
        val runtimeIdentity = identity.toModelRuntimeIdentity()
        val localScope = requireNotNull(
            dao.installationScope(RuntimeInstallationScopeEntity.INSTALLATION_SCOPE_KEY)
        ) { "Runtime installation scope is missing." }
        require(identity.installationScopeId == localScope.installationScopeId) {
            "Runtime identity belongs to another app installation."
        }
        return runtimeIdentity
    }

    private suspend fun validatePointers(pointers: ProfilePointersEntity, identityKey: String) {
        require(pointers.identityKey == identityKey) { "Profile pointers belong to another identity." }
        require(
            pointers.pendingProfileId == null || pointers.pendingProfileId !in listOfNotNull(
                pointers.activeProfileId,
                pointers.lastKnownGoodProfileId,
                pointers.rollbackTargetProfileId
            )
        ) { "Pending profile pointer must be distinct from all committed-profile pointers." }
        listOfNotNull(
            pointers.activeProfileId,
            pointers.pendingProfileId,
            pointers.lastKnownGoodProfileId,
            pointers.rollbackTargetProfileId
        ).distinct().forEach { profileId ->
            val profile = requireNotNull(dao.profile(profileId)) { "Profile pointer is dangling." }
            require(profile.identityKey == identityKey) { "Profile pointer belongs to another identity." }
            val expectedState = when (profileId) {
                pointers.pendingProfileId -> PersistedProfileRecordState.STAGED.name
                else -> PersistedProfileRecordState.COMMITTED.name
            }
            require(profile.recordState == expectedState) {
                "Profile pointer references a ${profile.recordState} profile where $expectedState is required."
            }
        }
    }

    private fun monotonicJobTime(job: TuningJobEntity, now: Long): Long = maxOf(
        now,
        job.createdAt,
        job.updatedAt,
        job.lastHeartbeatAt
    )

    private suspend fun resolveRecoveryTarget(
        journal: TuningJournalEntity,
        rejected: ExecutionProfileEntity?,
        pointers: ProfilePointersEntity
    ): ExecutionProfileEntity? {
        val preferred = listOfNotNull(
            journal.rollbackTargetProfileId,
            pointers.rollbackTargetProfileId,
            pointers.activeProfileId,
            pointers.lastKnownGoodProfileId,
            rejected?.parentCommittedProfileId
        ).distinct()
        preferred.forEach { profileId ->
            committedRecoveryProfile(profileId, journal.identityKey, rejected?.profileId)?.let { return it }
        }

        var parentId = rejected?.parentCommittedProfileId
        var depth = 0
        while (parentId != null && depth < MAX_PARENT_DEPTH) {
            val parent = dao.profile(parentId) ?: break
            if (parent.identityKey != journal.identityKey) break
            if (parent.recordState == PersistedProfileRecordState.COMMITTED.name) return parent
            parentId = parent.parentCommittedProfileId
            depth += 1
        }
        return null
    }

    private suspend fun committedRecoveryProfile(
        profileId: String,
        identityKey: String,
        rejectedProfileId: String?
    ): ExecutionProfileEntity? = dao.profile(profileId)?.takeIf { candidate ->
        candidate.profileId != rejectedProfileId &&
            candidate.identityKey == identityKey &&
            candidate.recordState == PersistedProfileRecordState.COMMITTED.name
    }

    private suspend fun installationScopeIdLocked(now: Long): String =
        dao.installationScope(RuntimeInstallationScopeEntity.INSTALLATION_SCOPE_KEY)
            ?.installationScopeId
            ?: UUID.randomUUID().toString().also { generated ->
                dao.insertInstallationScope(
                    RuntimeInstallationScopeEntity(
                        installationScopeId = generated,
                        createdAt = now
                    )
                )
            }

    private suspend fun selectCommittedPointerTarget(
        identityKey: String,
        explicitProfileId: String?,
        current: ProfilePointersEntity
    ): ExecutionProfileEntity? {
        val profileId = explicitProfileId ?: current.activeProfileId ?: current.lastKnownGoodProfileId ?: return null
        return requireNotNull(dao.profile(profileId)) { "Rollback profile is missing." }.also { target ->
            require(target.identityKey == identityKey) { "Rollback profile belongs to another identity." }
            require(target.recordState == PersistedProfileRecordState.COMMITTED.name) {
                "Rollback profile must be committed."
            }
        }
    }

    private fun validatePersistedProfile(profile: ExecutionProfileEntity) {
        require(profile.revision > 0)
        PersistedProfileRecordState.valueOf(profile.recordState)
        PersistedProfileVerificationLevel.valueOf(profile.verificationLevel)
        require(profile.desiredProfileSignature.isNotBlank())
        require(profile.committedExecutionSignature.isNotBlank())
        RuntimeProfilePersistencePolicy.requireSafeProfileJson(profile.profileJson)
        RuntimeProfilePersistencePolicy.requireSafeProfileJson(profile.resolvedLoadJson)
        RuntimeProfilePersistencePolicy.requireSafeProfileJson(profile.committedExecutionJson)
        require(
            profile.quarantinedOverridesJson ==
                RuntimeProfilePersistencePolicy.sanitizeDiagnosticJson(profile.quarantinedOverridesJson)
        ) { "Quarantined override evidence must be canonical and redacted." }
        require(
            profile.sourceSummaryJson == RuntimeProfilePersistencePolicy.sanitizeDiagnosticJson(profile.sourceSummaryJson)
        ) { "Profile source evidence must be canonical and redacted." }
    }

    private fun validateResourceBindings(
        profile: ExecutionProfileEntity,
        identity: ModelRuntimeIdentity,
        bindings: List<RuntimeResourceBindingEntity>
    ) {
        require(bindings.map { it.bindingId }.distinct().size == bindings.size)
        require(bindings.map { it.field }.distinct().size == bindings.size)
        bindings.forEach { binding ->
            require(binding.identityKey == profile.identityKey && binding.identityKey == identity.identityHash)
            require(binding.profileId == profile.profileId)
            require(binding.installationScopeId == identity.installationScopeId)
            require(binding.resourceValue.length <= MAX_RESOURCE_VALUE_CHARS)
            require(binding.resourceFingerprint == persistenceSha256(binding.resourceValue))
        }
    }

    private fun persistedJournalState(value: String): TuningJournalState =
        runCatching { TuningJournalState.valueOf(value) }
            .getOrElse { throw IllegalStateException("Unknown persisted journal state: $value") }

    private fun persistedTuningJobState(value: String): PersistedTuningJobState =
        runCatching { PersistedTuningJobState.valueOf(value) }
            .getOrElse { throw IllegalStateException("Unknown persisted tuning job state: $value") }

    private companion object {
        private const val MAX_PARENT_DEPTH = 16
        private const val REJECTED_PROFILE_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
        private const val TERMINAL_HISTORY_RETENTION_MS = 90L * 24L * 60L * 60L * 1000L
        private val ACTIVE_JOB_STATE_NAMES = setOf(
            PersistedTuningJobState.QUEUED,
            PersistedTuningJobState.RUNNING,
            PersistedTuningJobState.PAUSED,
            PersistedTuningJobState.CANCELING,
            PersistedTuningJobState.VALIDATING,
            PersistedTuningJobState.RECOVERING
        ).map { it.name }
        private val REJECTABLE_JOURNAL_STATE_NAMES = setOf(
            TuningJournalState.STAGED,
            TuningJournalState.APPLYING,
            TuningJournalState.VALIDATING
        ).map { it.name }
        private val RECOVERABLE_JOURNAL_STATE_NAMES = setOf(
            TuningJournalState.STAGED,
            TuningJournalState.APPLYING,
            TuningJournalState.VALIDATING,
            TuningJournalState.RECOVERING
        ).map { it.name }
        private val TERMINAL_JOURNAL_STATE_NAMES = setOf(
            TuningJournalState.COMMITTED,
            TuningJournalState.REJECTED,
            TuningJournalState.ROLLED_BACK,
            TuningJournalState.FAILED
        ).map { it.name }
        private val TERMINAL_JOB_STATE_NAMES = setOf(
            PersistedTuningJobState.SUCCEEDED,
            PersistedTuningJobState.FAILED
        ).map { it.name }
        private val TERMINAL_JOB_STATES = setOf(
            PersistedTuningJobState.SUCCEEDED,
            PersistedTuningJobState.FAILED
        )
        private val NON_INTERRUPTIBLE_OR_TERMINAL_JOB_STATES = setOf(
            PersistedTuningJobState.RECOVERING,
            PersistedTuningJobState.SUCCEEDED,
            PersistedTuningJobState.FAILED
        )
    }
}
