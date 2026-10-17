package com.muyuchat.mca

import androidx.room.withTransaction
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import java.util.UUID

internal suspend fun ModelRuntimeProfileStore.installationScopeIdImpl(now: Long = System.currentTimeMillis()): String =
        database.withTransaction {
            installationScopeIdLocked(now)
        }

internal suspend fun ModelRuntimeProfileStore.upsertIdentityImpl(identity: RuntimeIdentityEntity) {
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

internal suspend fun ModelRuntimeProfileStore.currentRuntimeStateImpl(identityKey: String): CurrentRuntimeProfileState? =
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

internal suspend fun ModelRuntimeProfileStore.modelRuntimeSummariesImpl(modelIds: Collection<String>): List<PersistedModelRuntimeSummary> =
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

internal suspend fun ModelRuntimeProfileStore.pointersImpl(identityKey: String): ProfilePointersEntity? = dao.pointers(identityKey)

internal suspend fun ModelRuntimeProfileStore.profileImpl(profileId: String): ExecutionProfileEntity? = dao.profile(profileId)

internal suspend fun ModelRuntimeProfileStore.profilesImpl(identityKey: String): List<ExecutionProfileEntity> = dao.profiles(identityKey)

internal suspend fun ModelRuntimeProfileStore.reconstructedProfileImpl(profileId: String): ModelExecutionProfile? = database.withTransaction {
        val entity = dao.profile(profileId) ?: return@withTransaction null
        val identityEntity = requireNotNull(dao.identity(entity.identityKey)) { "Runtime identity is missing." }
        val identity = identityEntity.toModelRuntimeIdentity()
        require(identity.installationScopeId == installationScopeIdLocked(System.currentTimeMillis())) {
            "Persisted profile belongs to another app installation."
        }
        entity.toModelExecutionProfile(identity, dao.resourceBindings(profileId))
    }

internal suspend fun ModelRuntimeProfileStore.validateLocalIdentity(identity: RuntimeIdentityEntity): ModelRuntimeIdentity {
        val runtimeIdentity = identity.toModelRuntimeIdentity()
        val localScope = requireNotNull(
            dao.installationScope(RuntimeInstallationScopeEntity.INSTALLATION_SCOPE_KEY)
        ) { "Runtime installation scope is missing." }
        require(identity.installationScopeId == localScope.installationScopeId) {
            "Runtime identity belongs to another app installation."
        }
        return runtimeIdentity
    }

internal suspend fun ModelRuntimeProfileStore.validatePointers(pointers: ProfilePointersEntity, identityKey: String) {
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

internal suspend fun ModelRuntimeProfileStore.installationScopeIdLocked(now: Long): String =
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

internal fun ModelRuntimeProfileStore.validatePersistedProfile(profile: ExecutionProfileEntity) {
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
