package com.muyuchat.core.engine

import java.util.UUID
import org.json.JSONObject

/**
 * In-memory P0 coordinator. Persistence owns profile/journal records; this
 * class owns authorization, immutable signature publication, and retry bounds.
 */
class ParameterCoordinator(
    private val adapters: Map<LocalChatRuntime, RuntimeParameterAdapter>,
    private val nonce: String = UUID.randomUUID().toString()
) {
    private var committedProfile: ModelExecutionProfile? = null
    private var pending: PendingLoadTransaction? = null
    private var active: ActiveLoadedSignature? = null
    private var runtimeOverride: RuntimeOverrideSignature? = null
    private val recoveryAttemptedRequestIds = mutableSetOf<String>()
    private val issuedAuthorizations = mutableMapOf<String, LoadAuthorization>()
    private val consumedTransactionIds = mutableSetOf<String>()
    // HotOverrideAuthorization is a regular class (not a data class), so the
    // set deliberately uses object identity and cannot be matched by a forged
    // value-equivalent instance.
    private val issuedHotAuthorizations = mutableSetOf<HotOverrideAuthorization>()

    constructor(registry: ParameterFieldPolicyRegistry = ParameterFieldPolicyRegistry()) : this(
        mapOf(
            LocalChatRuntime.LLAMA_CPP to LlamaCppRuntimeParameterAdapter(registry),
            LocalChatRuntime.GENIEX_LLAMA_CPP to LlamaCppRuntimeParameterAdapter(registry),
            LocalChatRuntime.MNN_CPU to MnnRuntimeParameterAdapter(registry),
            LocalChatRuntime.GENIEX_QAIRT to QairtRuntimeParameterAdapter(registry),
            LocalChatRuntime.LITERT_LM to LiteRtLmRuntimeParameterAdapter(registry)
        )
    )

    @Synchronized
    fun resolveProfile(
        identity: ModelRuntimeIdentity,
        requestedParamsJson: String,
        profileId: String = UUID.randomUUID().toString(),
        revision: Long = 1
    ): ParameterResolution = adapter(identity.runtime).resolveLoadProfile(
        identity = identity,
        rawJson = requestedParamsJson,
        profileId = profileId,
        revision = revision,
        activeLoadSignature = active
    )

    @Synchronized
    fun nativeLoadJson(profile: ModelExecutionProfile): String =
        adapter(profile.runtimeIdentity.runtime).nativeLoadJson(profile)

    @Synchronized
    fun loadSignatureDiagnostic(
        profile: ModelExecutionProfile,
        nativeStatsJson: String
    ): JSONObject = adapter(profile.runtimeIdentity.runtime).loadSignatureDiagnostic(
        identity = profile.runtimeIdentity,
        nativeStatsJson = nativeStatsJson,
        expected = profile.resolvedLoadSignature
    )

    @Synchronized
    fun isLoadSignatureMismatch(
        identity: ModelRuntimeIdentity,
        beginReturnCode: Int,
        nativeError: String? = null
    ): Boolean = adapter(identity.runtime).isLoadSignatureMismatch(beginReturnCode, nativeError)

    @Synchronized
    fun committedProfile(): ModelExecutionProfile? = committedProfile

    @Synchronized
    fun commit(profile: ModelExecutionProfile) {
        pending?.authorization?.transactionId?.let(consumedTransactionIds::add)
        committedProfile = profile
        runtimeOverride = RuntimeOverrideSignature.none(profile.runtimeIdentity)
        // An ordinary commit is an explicit lifecycle boundary. Never leave an
        // older pending authorization live after a different profile is made active.
        pending = null
    }

    @Synchronized
    fun markUnloaded() {
        active = null
        committedProfile?.let { runtimeOverride = RuntimeOverrideSignature.none(it.runtimeIdentity) }
    }

    @Synchronized
    fun createLoadAuthorization(
        transactionId: String,
        profile: ModelExecutionProfile
    ): LoadAuthorization {
        require(transactionId.isNotBlank()) { "transactionId must not be blank" }
        require(transactionId !in consumedTransactionIds) { "transactionId has already been consumed" }
        issuedAuthorizations[transactionId]?.let { existing ->
            require(matches(profile, existing)) { "transactionId is already bound to another profile" }
            return existing
        }
        return LoadAuthorization(
            transactionId = transactionId,
            modelIdentityHash = profile.runtimeIdentity.identityHash,
            profileId = profile.profileId,
            revision = profile.revision,
            desiredProfileDigest = profile.desiredSignature.digest,
            resolvedLoadDigest = profile.resolvedLoadSignature.digest,
            committedExecutionDigest = profile.committedExecutionSignature.digest,
            coordinatorNonce = nonce
        ).also { issuedAuthorizations[transactionId] = it }
    }

    @Synchronized
    fun createHotOverrideAuthorization(
        profile: ModelExecutionProfile,
        values: CanonicalParameterSet
    ): HotOverrideAuthorization {
        val knownProfile = when {
            sameProfile(committedProfile, profile) -> committedProfile
            sameProfile(pending?.profile, profile) -> pending?.profile
            else -> null
        } ?: error("hot override profile is not committed or pending")
        val view = adapter(knownProfile.runtimeIdentity.runtime).registry.forRuntime(knownProfile.runtimeIdentity)
        require(values.fields.isNotEmpty()) { "hot override authorization must bind at least one field" }
        require(values.fields.all { field ->
            view.policy(field).mutability == ParameterMutability.HOT_EXECUTION &&
                !view.policy(field).affectsSemantics
        }) { "hot override authorization may contain only non-semantic HOT_EXECUTION fields" }
        val signature = RuntimeOverrideSignature.of(knownProfile.runtimeIdentity, values)
        return HotOverrideAuthorization(
            profileId = knownProfile.profileId,
            revision = knownProfile.revision,
            modelIdentityHash = knownProfile.runtimeIdentity.identityHash,
            fields = values.fields.toSortedSet(),
            overrideDigest = signature.digest,
            coordinatorNonce = nonce
        ).also { issuedHotAuthorizations += it }
    }

    @Synchronized
    fun stageAuthorizedPending(
        profile: ModelExecutionProfile,
        authorization: LoadAuthorization,
        rollbackTargetProfileId: String?
    ): PendingLoadTransaction {
        require(matches(profile, authorization)) { "load authorization does not match pending profile" }
        require(issuedAuthorizations[authorization.transactionId] === authorization) {
            "load authorization was not issued by this coordinator"
        }
        require(authorization.transactionId !in consumedTransactionIds) {
            "load transaction has already been consumed"
        }
        val rollbackProfile = committedProfile ?: error("a committed profile is required before staging pending")
        require(rollbackProfile.runtimeIdentity.identityHash == profile.runtimeIdentity.identityHash) {
            "pending profile must target the committed model runtime identity"
        }
        require(rollbackTargetProfileId == null || rollbackTargetProfileId == rollbackProfile.profileId) {
            "rollbackTargetProfileId must identify the current committed profile"
        }
        require(pending == null || pending?.authorization === authorization) {
            "another pending profile transaction is already staged"
        }
        return PendingLoadTransaction(profile, authorization, rollbackTargetProfileId).also { pending = it }
    }

    @Synchronized
    fun authorizedPendingFor(authorization: LoadAuthorization): PendingLoadTransaction? =
        pending?.takeIf { staged ->
            staged.authorization === authorization &&
                authorization.transactionId !in consumedTransactionIds &&
                matches(staged.profile, authorization)
        }

    @Synchronized
    fun isAuthorizedPendingActive(authorization: LoadAuthorization): Boolean =
        authorizedPendingSignatureVerificationLocked(authorization)?.strictlyMatches == true

    /**
     * Returns an atomic candidate-specific six-signature view for the exact
     * coordinator-issued pending authorization. A forged, replaced or consumed
     * authorization receives no view. A genuine pending candidate that has not
     * yet been loaded (or whose native readback drifted) receives a view with
     * [AuthorizedPendingSignatureVerification.strictlyMatches] set to false.
     */
    @Synchronized
    fun authorizedPendingSignatureVerification(
        authorization: LoadAuthorization
    ): AuthorizedPendingSignatureVerification? =
        authorizedPendingSignatureVerificationLocked(authorization)

    @Synchronized
    fun commitAuthorizedPending(authorization: LoadAuthorization): ModelExecutionProfile {
        val staged = pending ?: error("no pending profile")
        require(staged.authorization === authorization && matches(staged.profile, authorization)) {
            "pending profile commit was not authorized"
        }
        val loaded = active ?: error("pending profile is not loaded")
        require(loaded.identityHash == staged.profile.runtimeIdentity.identityHash &&
            loaded.values.differences(staged.profile.resolvedLoadSignature.values).isEmpty()) {
            "active native load does not match the pending resolved signature"
        }
        consumedTransactionIds += authorization.transactionId
        commit(staged.profile)
        return staged.profile
    }

    @Synchronized
    fun publishLoaded(
        profile: ModelExecutionProfile,
        nativeStatsJson: String,
        authorization: LoadAuthorization? = null
    ): ParameterSignatureSnapshot {
        val staged = pending
        if (staged != null) {
            when {
                sameProfile(staged.profile, profile) -> require(
                    authorization != null &&
                        authorization === staged.authorization &&
                        matches(profile, authorization)
                ) { "pending profile load was not authorized" }
                sameProfile(committedProfile, profile) -> require(authorization == null) {
                    "rollback/committed profile publication must not reuse pending authorization"
                }
                else -> error("an unrelated profile cannot be published while a pending transaction exists")
            }
        } else if (authorization != null) {
            error("load authorization has no matching pending transaction")
        }
        val adapter = adapter(profile.runtimeIdentity.runtime)
        val observed = adapter.activeLoadedSignature(
            profile.runtimeIdentity,
            nativeStatsJson,
            profile.resolvedLoadSignature
        ) ?: error("native effective load signature does not match the resolved profile")
        active = observed
        val publicationOverride = if (
            committedProfile?.runtimeIdentity?.identityHash == profile.runtimeIdentity.identityHash
        ) {
            runtimeOverride ?: RuntimeOverrideSignature.none(profile.runtimeIdentity)
        } else {
            // A runtime/identity switch must never inherit a thermal or
            // request-scoped override signed for the previously loaded model.
            RuntimeOverrideSignature.none(profile.runtimeIdentity)
        }
        if (committedProfile == null) commit(profile)
        return snapshotLocked(profile, publicationOverride)
    }

    /**
     * Explicit normal loads cancel stale pending state, but cannot smuggle the
     * exact staged candidate around its transaction authorization.
     */
    @Synchronized
    fun prepareOrdinaryLoad(profile: ModelExecutionProfile) {
        val staged = pending ?: return
        require(!sameProfile(staged.profile, profile)) {
            "the staged pending profile requires its exact load authorization"
        }
        staged.authorization.transactionId.let(consumedTransactionIds::add)
        pending = null
    }

    @Synchronized
    fun rollbackTargetFor(authorization: LoadAuthorization): ModelExecutionProfile {
        val staged = pending ?: error("no pending profile")
        require(staged.authorization === authorization && matches(staged.profile, authorization)) {
            "pending rollback was not authorized"
        }
        val target = committedProfile ?: error("pending transaction has no committed rollback profile")
        require(staged.rollbackTargetProfileId == null || staged.rollbackTargetProfileId == target.profileId) {
            "the in-memory committed profile does not match rollbackTargetProfileId"
        }
        return target
    }

    @Synchronized
    fun abortAuthorizedPending(authorization: LoadAuthorization): Boolean {
        val staged = authorizedPendingFor(authorization) ?: return false
        consumedTransactionIds += staged.authorization.transactionId
        pending = null
        return true
    }

    @Synchronized
    fun setRuntimeOverride(values: CanonicalParameterSet): RuntimeOverrideSignature {
        val profile = committedProfile ?: error("no committed profile")
        val allowed = values.fields.all { field ->
            adapter(profile.runtimeIdentity.runtime).registry.forRuntime(profile.runtimeIdentity)
                .policy(field).let { policy ->
                    policy.mutability == ParameterMutability.HOT_EXECUTION && !policy.affectsSemantics
                }
        }
        require(allowed) {
            "runtime overrides may contain only registered non-semantic HOT_EXECUTION fields"
        }
        return RuntimeOverrideSignature.of(profile.runtimeIdentity, values).also { runtimeOverride = it }
    }

    @Synchronized
    fun clearRuntimeOverride(): RuntimeOverrideSignature {
        val profile = committedProfile ?: error("no committed profile")
        return RuntimeOverrideSignature.none(profile.runtimeIdentity).also { runtimeOverride = it }
    }

    @Synchronized
    fun preflight(
        identity: ModelRuntimeIdentity,
        requestParamsJson: String,
        trustedAuthorization: LoadAuthorization? = null,
        @Suppress("UNUSED_PARAMETER") allowTrustedHotOverride: Boolean = false,
        hotOverrideAuthorization: HotOverrideAuthorization? = null
    ): CompletionPreflight {
        val committed = committedProfile
            ?: return CompletionPreflight.Rejected("model_not_loaded", emptySet(), emptyList(), "No committed model profile is loaded.")
        if (committed.runtimeIdentity.identityHash != identity.identityHash) {
            return CompletionPreflight.Rejected("model_mismatch", emptySet(), emptyList(), "The request model identity is not active.")
        }
        val adapter = adapter(identity.runtime)
        val partition = adapter.partition(identity, requestParamsJson)
        val activeSignature = active
            ?: return CompletionPreflight.Rejected("model_not_loaded", emptySet(), partition.quarantinedOverrides, "No active native load signature is published.")
        val staged = pending
        val authorizedPendingIsActive = trustedAuthorization != null && staged != null &&
            staged.authorization === trustedAuthorization && matches(staged.profile, trustedAuthorization) &&
            activeSignature.identityHash == staged.profile.runtimeIdentity.identityHash &&
            activeSignature.values.differences(staged.profile.resolvedLoadSignature.values).isEmpty()
        val profile = if (authorizedPendingIsActive) staged!!.profile else committed
        val activeDrift = activeSignature.values.differences(profile.resolvedLoadSignature.values)
        if (activeSignature.identityHash != identity.identityHash || activeDrift.isNotEmpty()) {
            if (!authorizedPendingIsActive) {
                return CompletionPreflight.Rejected(
                    "active_profile_drift",
                    activeDrift,
                    partition.quarantinedOverrides,
                    "The native load does not match the committed profile; reload the committed profile before generating."
                )
            }
        }
        val changedLoad = partition.loadBound.differences(profile.resolvedLoadBoundValues)
            .filterTo(linkedSetOf()) { partition.loadBound.fields.contains(it) }
        if (changedLoad.isNotEmpty()) {
            val authorized = trustedAuthorization != null && staged != null &&
                staged.authorization === trustedAuthorization && matches(staged.profile, trustedAuthorization) &&
                staged.profile.runtimeIdentity.identityHash == identity.identityHash &&
                requestedFieldsMatch(partition.loadBound, staged.profile.resolvedLoadBoundValues) &&
                requestedFieldsMatch(partition.hotExecution, staged.profile.hotExecutionValues)
            return CompletionPreflight.Rejected(
                code = if (authorized) "model_reload_required_authorized" else "model_reload_required",
                changedFields = changedLoad,
                quarantinedOverrides = partition.quarantinedOverrides,
                message = if (authorized) "The authorized pending profile must be reloaded before generation."
                else "Load-bound request fields cannot change the active model without an authorized pending transaction."
            )
        }
        val changedHot = partition.hotExecution.differences(profile.hotExecutionValues)
            .filterTo(linkedSetOf()) { partition.hotExecution.fields.contains(it) }
        val requestedHotOverride = partition.hotExecution.only(changedHot)
        val hotOverride = RuntimeOverrideSignature.of(identity, requestedHotOverride)
        val hotOverrideAuthorized = changedHot.isNotEmpty() &&
            hotOverrideAuthorization != null &&
            matches(profile, hotOverride, hotOverrideAuthorization)
        if (changedHot.isNotEmpty() && !hotOverrideAuthorized) {
            return CompletionPreflight.Rejected(
                "execution_override_forbidden",
                changedHot,
                partition.quarantinedOverrides,
                "Ordinary requests may override generation fields only."
            )
        }
        val changedBehavior = partition.modelBehavior.differences(profile.modelBehaviorValues)
            .filterTo(linkedSetOf()) { partition.modelBehavior.fields.contains(it) }
        if (changedBehavior.isNotEmpty()) {
            return CompletionPreflight.Rejected(
                "model_behavior_override_forbidden",
                changedBehavior,
                partition.quarantinedOverrides,
                "Template and model-behavior fields require a trusted profile and correctness gate."
            )
        }
        val override = if (hotOverrideAuthorized) {
            hotOverride
        } else if (authorizedPendingIsActive) {
            // A pending transaction must be evaluated exactly as signed. Do not
            // let a prior thermal/runtime override contaminate candidate proof.
            RuntimeOverrideSignature.none(identity)
        } else {
            runtimeOverride ?: RuntimeOverrideSignature.none(identity)
        }
        val nativeJson = adapter.nativeCompletionJson(partition, profile, override)
        return CompletionPreflight.Ready(
            nativeParamsJson = nativeJson,
            generationJson = partition.generationJson,
            signatures = snapshotLocked(profile, override, activeSignature),
            quarantinedOverrides = partition.quarantinedOverrides
        )
    }

    @Synchronized
    fun decideMismatchRecovery(
        requestId: String,
        identity: ModelRuntimeIdentity,
        trustedAuthorization: LoadAuthorization? = null
    ): MismatchRecoveryDecision {
        if (requestId.isBlank()) return MismatchRecoveryDecision.Fail("invalid_request_id", "requestId must not be blank")
        if (!recoveryAttemptedRequestIds.add(requestId)) {
            return MismatchRecoveryDecision.Fail("recovery_exhausted", "Load signature recovery is limited to one attempt per request.")
        }
        val staged = pending
        if (trustedAuthorization != null && staged != null &&
            staged.authorization === trustedAuthorization && matches(staged.profile, trustedAuthorization) &&
            staged.profile.runtimeIdentity.identityHash == identity.identityHash
        ) {
            return MismatchRecoveryDecision.ReloadAuthorizedPending(staged)
        }
        val committed = committedProfile
        if (committed?.runtimeIdentity?.identityHash == identity.identityHash) {
            return MismatchRecoveryDecision.ReloadCommittedForDrift(committed)
        }
        return MismatchRecoveryDecision.Fail("model_mismatch", "No matching committed profile is available for recovery.")
    }

    @Synchronized
    fun finishRequest(requestId: String) {
        // Keep the bounded-attempt marker until the request has fully exited.
        recoveryAttemptedRequestIds.remove(requestId)
    }

    @Synchronized
    fun snapshot(): ParameterSignatureSnapshot? = committedProfile?.let(::snapshotLocked)

    private fun authorizedPendingSignatureVerificationLocked(
        authorization: LoadAuthorization
    ): AuthorizedPendingSignatureVerification? {
        val staged = pending ?: return null
        if (staged.authorization !== authorization ||
            authorization.transactionId in consumedTransactionIds ||
            !matches(staged.profile, authorization)
        ) {
            return null
        }

        val profile = staged.profile
        val identity = profile.runtimeIdentity
        val noOverride = RuntimeOverrideSignature.none(identity)
        val actualActive = active
        val effective = actualActive
            ?.takeIf { it.identityHash == identity.identityHash }
            ?.let { loaded ->
                EffectiveExecutionSignature.of(
                    identity = identity,
                    active = loaded,
                    committed = profile.committedExecutionSignature,
                    override = noOverride
                )
            }
        val signatures = ParameterSignatureSnapshot(
            desired = profile.desiredSignature,
            resolved = profile.resolvedLoadSignature,
            active = actualActive,
            committed = profile.committedExecutionSignature,
            override = noOverride,
            effective = effective
        )

        val expectedActive = ActiveLoadedSignature.of(identity, profile.resolvedLoadSignature.values)
        val activeMatches = actualActive == expectedActive
        val expectedEffective = if (activeMatches) {
            EffectiveExecutionSignature.of(
                identity = identity,
                active = expectedActive,
                committed = profile.committedExecutionSignature,
                override = noOverride
            )
        } else {
            null
        }
        val authorizationMatchesSignatures =
            signatures.desired.digest == authorization.desiredProfileDigest &&
                signatures.resolved.digest == authorization.resolvedLoadDigest &&
                signatures.committed.digest == authorization.committedExecutionDigest
        val overrideIsStrictlyNone = signatures.override == noOverride &&
            signatures.override.isNone &&
            signatures.override.digest == "NONE" &&
            signatures.override.values.fields.isEmpty()
        val strictlyMatches = authorizationMatchesSignatures &&
            signatures.desired == profile.desiredSignature &&
            signatures.resolved == profile.resolvedLoadSignature &&
            signatures.committed == profile.committedExecutionSignature &&
            activeMatches &&
            overrideIsStrictlyNone &&
            signatures.effective != null &&
            signatures.effective == expectedEffective

        return AuthorizedPendingSignatureVerification(
            transactionId = authorization.transactionId,
            profileId = profile.profileId,
            revision = profile.revision,
            signatures = signatures,
            strictlyMatches = strictlyMatches
        )
    }

    private fun matches(profile: ModelExecutionProfile, authorization: LoadAuthorization): Boolean =
        authorization.coordinatorNonce == nonce &&
            issuedAuthorizations[authorization.transactionId] === authorization &&
            authorization.transactionId.isNotBlank() &&
            authorization.modelIdentityHash == profile.runtimeIdentity.identityHash &&
            authorization.profileId == profile.profileId &&
            authorization.revision == profile.revision &&
            authorization.desiredProfileDigest == profile.desiredSignature.digest &&
            authorization.resolvedLoadDigest == profile.resolvedLoadSignature.digest &&
            authorization.committedExecutionDigest == profile.committedExecutionSignature.digest

    private fun matches(
        profile: ModelExecutionProfile,
        override: RuntimeOverrideSignature,
        authorization: HotOverrideAuthorization
    ): Boolean = authorization.coordinatorNonce == nonce &&
        authorization in issuedHotAuthorizations &&
        override.identityHash == profile.runtimeIdentity.identityHash &&
        authorization.profileId == profile.profileId &&
        authorization.revision == profile.revision &&
        authorization.modelIdentityHash == profile.runtimeIdentity.identityHash &&
        authorization.fields == override.values.fields &&
        authorization.overrideDigest == override.digest

    private fun sameProfile(first: ModelExecutionProfile?, second: ModelExecutionProfile?): Boolean =
        first != null && second != null &&
            first.runtimeIdentity.identityHash == second.runtimeIdentity.identityHash &&
            first.profileId == second.profileId &&
            first.revision == second.revision &&
            first.desiredSignature.digest == second.desiredSignature.digest &&
            first.resolvedLoadSignature.digest == second.resolvedLoadSignature.digest &&
            first.committedExecutionSignature.digest == second.committedExecutionSignature.digest

    private fun requestedFieldsMatch(
        requested: CanonicalParameterSet,
        target: CanonicalParameterSet
    ): Boolean = requested.encodedValues.all { (field, value) -> target.encodedValues[field] == value }

    private fun adapter(runtime: LocalChatRuntime): RuntimeParameterAdapter =
        adapters[runtime] ?: error("No runtime parameter adapter registered for $runtime")

    private fun snapshotLocked(
        profile: ModelExecutionProfile,
        override: RuntimeOverrideSignature = runtimeOverride ?: RuntimeOverrideSignature.none(profile.runtimeIdentity),
        activeSignature: ActiveLoadedSignature? = active
    ): ParameterSignatureSnapshot {
        val effective = activeSignature?.let {
            EffectiveExecutionSignature.of(
                profile.runtimeIdentity,
                it,
                profile.committedExecutionSignature,
                override
            )
        }
        return ParameterSignatureSnapshot(
            desired = profile.desiredSignature,
            resolved = profile.resolvedLoadSignature,
            active = activeSignature,
            committed = profile.committedExecutionSignature,
            override = override,
            effective = effective
        )
    }
}
