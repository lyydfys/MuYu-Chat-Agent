package com.muyuchat.mca

import com.muyuchat.core.engine.CanonicalParameterSet
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.QuarantinedOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class ModelRuntimeProfileStorePolicyTest {
    @Test
    fun nextRevisionSkipsRejectedAndSupersededHistory() {
        assertEquals(6L, nextExecutionProfileRevision(2L, listOf(1L, 2L, 3L, 4L, 5L)))
        assertEquals(8L, nextExecutionProfileRevision(7L, listOf(1L, 2L, 3L)))
        assertEquals(1L, nextExecutionProfileRevision(0L, emptyList()))
    }

    @Test
    fun identitySnapshotRoundTripsEveryIdentityDimension() {
        val identity = identity()

        val restored = identity.toRuntimeIdentityEntity(now = 20L, createdAt = 10L).toModelRuntimeIdentity()

        assertEquals(identity, restored)
        assertEquals(identity.identityHash, restored.identityHash)
    }

    @Test
    fun profileSnapshotUsesInstallationLocalBindingsAndRestoresExecutionValues() {
        val identity = identity()
        val load = CanonicalParameterSet.of(mapOf("n_ctx" to 4096, "mmproj_path" to "D:\\models\\mmproj.gguf"))
        val profile = ModelExecutionProfile(
            modelId = identity.modelId,
            runtimeIdentity = identity,
            desiredLoadBoundValues = load,
            resolvedLoadBoundValues = load,
            hotExecutionValues = CanonicalParameterSet.of(mapOf("n_threads" to 6)),
            modelBehaviorValues = CanonicalParameterSet.of(mapOf("template_policy_ref" to "gemma-v1")),
            profileId = "profile-1",
            revision = 3,
            userOverrides = setOf("n_ctx"),
            resolvedAt = 1234L
        )

        val snapshot = profile.toPersistedExecutionProfileSnapshot(now = 200L, createdAt = 100L)
        val restored = snapshot.profile.toModelExecutionProfile(identity, snapshot.resourceBindings)

        assertFalse(snapshot.profile.profileJson.contains("D:\\models"))
        assertTrue(snapshot.profile.profileJson.contains("@resource-binding:"))
        assertEquals(profile, restored)
        assertEquals(2, snapshot.resourceBindings.size)
    }

    @Test
    fun profileSnapshotPreservesDesiredExecutionValuesAfterRuntimeNormalization() {
        val identity = identity()
        val load = CanonicalParameterSet.of(mapOf("n_ctx" to 4096))
        val desiredHot = CanonicalParameterSet.of(
            mapOf("n_threads" to 8, "n_threads_batch" to 1)
        )
        val resolvedHot = CanonicalParameterSet.of(
            mapOf("n_threads" to 6, "n_threads_batch" to 6)
        )
        val desiredBehavior = CanonicalParameterSet.of(
            mapOf("template_policy_ref" to "requested-template")
        )
        val resolvedBehavior = CanonicalParameterSet.of(
            mapOf("template_policy_ref" to "resolved-template")
        )
        val profile = ModelExecutionProfile(
            modelId = identity.modelId,
            runtimeIdentity = identity,
            desiredLoadBoundValues = load,
            resolvedLoadBoundValues = load,
            hotExecutionValues = resolvedHot,
            desiredHotExecutionValues = desiredHot,
            modelBehaviorValues = resolvedBehavior,
            desiredModelBehaviorValues = desiredBehavior,
            profileId = "profile-normalized",
            revision = 4
        )

        val snapshot = profile.toPersistedExecutionProfileSnapshot(now = 200L, createdAt = 100L)
        val restored = snapshot.profile.toModelExecutionProfile(identity, snapshot.resourceBindings)

        assertEquals(profile, restored)
        assertEquals(desiredHot, restored.desiredHotExecutionValues)
        assertEquals(desiredBehavior, restored.desiredModelBehaviorValues)
        assertFalse(snapshot.profile.requiresDesiredExecutionSnapshotMigration())
    }

    @Test
    fun legacyProfileWithoutDesiredExecutionValuesUsesEffectiveValuesUntilUpgraded() {
        val identity = identity()
        val load = CanonicalParameterSet.of(mapOf("n_ctx" to 4096))
        val profile = ModelExecutionProfile(
            modelId = identity.modelId,
            runtimeIdentity = identity,
            desiredLoadBoundValues = load,
            resolvedLoadBoundValues = load,
            hotExecutionValues = CanonicalParameterSet.of(mapOf("n_threads" to 6)),
            desiredHotExecutionValues = CanonicalParameterSet.of(mapOf("n_threads" to 8)),
            modelBehaviorValues = CanonicalParameterSet.of(
                mapOf("template_policy_ref" to "resolved-template")
            ),
            desiredModelBehaviorValues = CanonicalParameterSet.of(
                mapOf("template_policy_ref" to "requested-template")
            ),
            profileId = "profile-legacy",
            revision = 5
        )
        val currentSnapshot = profile.toPersistedExecutionProfileSnapshot(now = 200L, createdAt = 100L)
        val legacySnapshot = currentSnapshot.profile.copy(
            profileJson = JSONObject(currentSnapshot.profile.profileJson).apply {
                remove("desiredHotExecutionValues")
                remove("desiredModelBehaviorValues")
            }.toString()
        )

        val restored = legacySnapshot.toModelExecutionProfile(identity, currentSnapshot.resourceBindings)

        assertTrue(legacySnapshot.requiresDesiredExecutionSnapshotMigration())
        assertEquals(profile.hotExecutionValues, restored.desiredHotExecutionValues)
        assertEquals(profile.modelBehaviorValues, restored.desiredModelBehaviorValues)
        assertEquals(profile.resolvedLoadSignature, restored.resolvedLoadSignature)
        assertEquals(profile.committedExecutionSignature, restored.committedExecutionSignature)
    }

    @Test
    fun quarantinedLegacyOverridesRoundTripAsRedactedDiagnostics() {
        val identity = identity()
        val load = CanonicalParameterSet.of(mapOf("n_ctx" to 4096))
        val profile = ModelExecutionProfile(
            modelId = identity.modelId,
            runtimeIdentity = identity,
            desiredLoadBoundValues = load,
            resolvedLoadBoundValues = load,
            hotExecutionValues = CanonicalParameterSet.of(mapOf("n_threads" to 6)),
            profileId = "profile-quarantine",
            revision = 1,
            quarantinedOverrides = listOf(
                QuarantinedOverride(
                    field = "future_native",
                    rawJson = "D:\\models\\private.gguf api_key=super-secret-token",
                    reason = "unsupported by runtime"
                )
            )
        )

        val snapshot = profile.toPersistedExecutionProfileSnapshot(now = 200L, createdAt = 100L)
        val restored = snapshot.profile.toModelExecutionProfile(identity, snapshot.resourceBindings)

        assertEquals(listOf("future_native"), restored.quarantinedOverrides.map { it.field })
        assertFalse(snapshot.profile.quarantinedOverridesJson.contains("D:\\models"))
        assertFalse(snapshot.profile.quarantinedOverridesJson.contains("super-secret-token"))
        assertTrue(snapshot.profile.quarantinedOverridesJson.contains("<path>"))
        assertTrue(snapshot.profile.quarantinedOverridesJson.contains("<redacted>"))
    }

    @Test
    fun recoveryIsAuthorizedAtMostOnce() {
        val first = RuntimeProfilePersistencePolicy.recoveryDecision(0, hasRollbackTarget = true)
        val second = RuntimeProfilePersistencePolicy.recoveryDecision(first.nextAttempts, hasRollbackTarget = true)
        val missing = RuntimeProfilePersistencePolicy.recoveryDecision(0, hasRollbackTarget = false)

        assertTrue(first.canRecover)
        assertEquals(1, first.nextAttempts)
        assertFalse(second.canRecover)
        assertEquals("RECOVERY_LIMIT_REACHED", second.terminalStage)
        assertFalse(missing.canRecover)
        assertEquals(0, missing.nextAttempts)
    }

    @Test
    fun diagnosticsRedactPathsAndCredentials() {
        val sanitized = RuntimeProfilePersistencePolicy.sanitizeFailureSummary(
            "load D:\\models\\secret.gguf Authorization=token Bearer abc.def"
        )

        assertFalse(sanitized.contains("secret.gguf"))
        assertFalse(sanitized.contains("abc.def"))
        assertTrue(sanitized.contains("<redacted>"))
    }

    @Test
    fun tuningJobStateMachineAllowsOnlyBoundedLifecycleTransitions() {
        assertTrue(
            RuntimeProfilePersistencePolicy.canTransitionTuningJob(
                PersistedTuningJobState.QUEUED,
                PersistedTuningJobState.RUNNING
            )
        )
        assertTrue(
            RuntimeProfilePersistencePolicy.canTransitionTuningJob(
                PersistedTuningJobState.RUNNING,
                PersistedTuningJobState.VALIDATING
            )
        )
        assertTrue(
            RuntimeProfilePersistencePolicy.canTransitionTuningJob(
                PersistedTuningJobState.QUEUED,
                PersistedTuningJobState.RECOVERING
            )
        )
        assertTrue(
            RuntimeProfilePersistencePolicy.canTransitionTuningJob(
                PersistedTuningJobState.VALIDATING,
                PersistedTuningJobState.SUCCEEDED
            )
        )
        assertFalse(
            RuntimeProfilePersistencePolicy.canTransitionTuningJob(
                PersistedTuningJobState.QUEUED,
                PersistedTuningJobState.SUCCEEDED
            )
        )
        assertFalse(
            RuntimeProfilePersistencePolicy.canTransitionTuningJob(
                PersistedTuningJobState.PAUSED,
                PersistedTuningJobState.VALIDATING
            )
        )
        assertFalse(
            RuntimeProfilePersistencePolicy.canTransitionTuningJob(
                PersistedTuningJobState.SUCCEEDED,
                PersistedTuningJobState.RUNNING
            )
        )
        assertFalse(
            RuntimeProfilePersistencePolicy.canTransitionTuningJob(
                PersistedTuningJobState.FAILED,
                PersistedTuningJobState.QUEUED
            )
        )
    }

    @Test
    fun cancellationRequestPreventsAJobFromReturningToActiveWork() {
        assertTrue(
            RuntimeProfilePersistencePolicy.canTransitionTuningJob(
                PersistedTuningJobState.CANCELING,
                PersistedTuningJobState.RECOVERING,
                cancellationRequested = true
            )
        )
        assertTrue(
            RuntimeProfilePersistencePolicy.canTransitionTuningJob(
                PersistedTuningJobState.CANCELING,
                PersistedTuningJobState.FAILED,
                cancellationRequested = true
            )
        )
        assertFalse(
            RuntimeProfilePersistencePolicy.canTransitionTuningJob(
                PersistedTuningJobState.RUNNING,
                PersistedTuningJobState.RUNNING,
                cancellationRequested = true
            )
        )
        assertFalse(
            RuntimeProfilePersistencePolicy.canTransitionTuningJob(
                PersistedTuningJobState.CANCELING,
                PersistedTuningJobState.PAUSED,
                cancellationRequested = true
            )
        )
    }

    @Test
    fun pauseResumeAndCancelControlsAreIdempotentAtTheirLegalBoundaries() {
        assertEquals(
            PersistedTuningJobState.PAUSED,
            RuntimeProfilePersistencePolicy.pauseTuningJobTarget(PersistedTuningJobState.QUEUED)
        )
        assertEquals(
            PersistedTuningJobState.PAUSED,
            RuntimeProfilePersistencePolicy.pauseTuningJobTarget(PersistedTuningJobState.PAUSED)
        )
        assertNull(RuntimeProfilePersistencePolicy.pauseTuningJobTarget(PersistedTuningJobState.VALIDATING))

        assertEquals(
            PersistedTuningJobState.RUNNING,
            RuntimeProfilePersistencePolicy.resumeTuningJobTarget(PersistedTuningJobState.PAUSED)
        )
        assertEquals(
            PersistedTuningJobState.RUNNING,
            RuntimeProfilePersistencePolicy.resumeTuningJobTarget(PersistedTuningJobState.RUNNING)
        )
        assertNull(RuntimeProfilePersistencePolicy.resumeTuningJobTarget(PersistedTuningJobState.CANCELING))

        assertEquals(
            PersistedTuningJobState.CANCELING,
            RuntimeProfilePersistencePolicy.cancellationTuningJobTarget(PersistedTuningJobState.RUNNING)
        )
        assertEquals(
            PersistedTuningJobState.CANCELING,
            RuntimeProfilePersistencePolicy.cancellationTuningJobTarget(PersistedTuningJobState.CANCELING)
        )
        assertEquals(
            PersistedTuningJobState.RECOVERING,
            RuntimeProfilePersistencePolicy.cancellationTuningJobTarget(PersistedTuningJobState.RECOVERING)
        )
        assertEquals(
            PersistedTuningJobState.SUCCEEDED,
            RuntimeProfilePersistencePolicy.cancellationTuningJobTarget(PersistedTuningJobState.SUCCEEDED)
        )
    }

    @Test
    fun onlyJoblessProbeJournalsCanUseTheDisposableProbeTerminalPath() {
        val base = TuningJournalEntity(
            transactionId = "probe-transaction-1",
            identityKey = "identity-1",
            jobId = null,
            pendingProfileId = "profile-1",
            rollbackTargetProfileId = "committed-1",
            resolvedLoadSignature = "resolved-1",
            state = TuningJournalState.VALIDATING.name,
            stage = "ISOLATED_NATIVE_PROBE",
            recoveryAttempts = 0,
            createdAt = 1L,
            updatedAt = 2L
        )

        assertTrue(RuntimeProfilePersistencePolicy.canCompleteIsolatedProbe(base))
        assertFalse(
            RuntimeProfilePersistencePolicy.canCompleteIsolatedProbe(
                base.copy(transactionId = "tuning-production-1")
            )
        )
        assertFalse(
            RuntimeProfilePersistencePolicy.canCompleteIsolatedProbe(
                base.copy(jobId = "job-1")
            )
        )
        assertFalse(
            RuntimeProfilePersistencePolicy.canCompleteIsolatedProbe(
                base.copy(state = TuningJournalState.COMMITTED.name)
            )
        )
    }

    private fun identity() = ModelRuntimeIdentity(
        modelId = "gemma-vision",
        artifactFingerprint = "artifact-sha",
        runtime = LocalChatRuntime.LLAMA_CPP,
        runtimeVersion = "runtime-1",
        nativeLibrarySha256 = "native-sha",
        abi = "arm64-v8a",
        backendFingerprint = "backend-sha",
        projectorFingerprint = "projector-sha",
        bundleFingerprint = "bundle-sha",
        tokenizerFingerprint = "tokenizer-sha",
        templateFingerprint = "template-sha",
        deviceCapabilityFingerprint = "device-capability-sha",
        installationScopeId = "81012bd3-c920-440d-9cc1-bc3b5a598667",
        ruleSetFingerprint = "rules-v1",
        evaluatorFingerprint = "evaluator-v1",
        engineContractVersion = "engine-contract-v2",
        schemaFingerprint = "schema-v2",
        parameterPolicyVersion = "policy-v2",
        capabilities = setOf("vision", "tool-use")
    )
}
