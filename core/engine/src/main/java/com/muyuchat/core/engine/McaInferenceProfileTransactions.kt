package com.muyuchat.core.engine

import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID

    /**
     * Read-only six-signature proof for the exact unconsumed pending candidate.
     * RuntimeOverride is forced to NONE for candidate validation.
     */
internal fun McaInferenceService.authorizedPendingSignatureVerificationImpl(
        authorization: LoadAuthorization
    ): AuthorizedPendingSignatureVerification? =
        parameterCoordinator.authorizedPendingSignatureVerification(authorization)

internal fun McaInferenceService.activeExecutionProfileImpl(): ModelExecutionProfile? =
        parameterCoordinator.committedProfile()

    /**
     * Resolves a user-authored parameter document against the exact runtime
     * identity without mutating the active model. Persistence/UI code uses the
     * returned profile to build a field-level pending transaction.
     */
internal fun McaInferenceService.resolveExecutionProfileImpl(
        identity: ModelRuntimeIdentity,
        requestedParamsJson: String,
        profileId: String = UUID.randomUUID().toString(),
        revision: Long = 1
    ): ParameterResolution = parameterCoordinator.resolveProfile(
        identity = identity,
        requestedParamsJson = requestedParamsJson,
        profileId = profileId,
        revision = revision
    )

    /**
     * Engine-owned candidate/apply boundary. The returned authorization is
     * opaque and binds transactionId + profile identity/revision/all signatures.
     */
internal fun McaInferenceService.stagePendingExecutionProfileImpl(
        transactionId: String,
        profile: ModelExecutionProfile,
        rollbackTargetProfileId: String? = activeExecutionProfile()?.profileId
    ): LoadAuthorization {
        val authorization = parameterCoordinator.createLoadAuthorization(transactionId, profile)
        parameterCoordinator.stageAuthorizedPending(profile, authorization, rollbackTargetProfileId)
        return authorization
    }

    /** Issues a value-bound HOT_EXECUTION lease; a public Boolean cannot replace it. */
internal fun McaInferenceService.authorizeHotExecutionOverrideImpl(
        profile: ModelExecutionProfile,
        values: CanonicalParameterSet
    ): HotOverrideAuthorization = parameterCoordinator.createHotOverrideAuthorization(profile, values)

    /**
     * Waits until the real engine lifecycle is exclusively owned. Formal candidate evaluation may
     * preempt an active stream; deterministic background transforms use the non-preemptive mode so
     * timing out while waiting can never stop another caller's generation.
     */
internal suspend fun McaInferenceService.acquireExclusiveLifecycleLeaseImpl(
        stopActiveGeneration: Boolean = true
    ): EngineLifecycleLease {
        if (stopActiveGeneration) stopGeneration()
        val owner = Any()
        mutex.lock(owner)
        return EngineLifecycleLease(this, owner)
    }

internal suspend fun McaInferenceService.releaseExclusiveLifecycleLease(lease: EngineLifecycleLease) {
        require(lease.service === this) { "lifecycle lease belongs to another engine" }
        val shouldRelease = synchronized(lease) {
            if (!lease.active || lease.releasing) false else {
                lease.releasing = true
                true
            }
        }
        if (!shouldRelease) return
        try {
            val deferred = synchronized(lease) { lease.deferredAuthorization }
            if (deferred != null) {
                runCatching {
                    rollbackDeferredPendingExecutionProfile(deferred, lease)
                }.onFailure { error ->
                    parameterCoordinator.abortAuthorizedPending(deferred)
                    parameterCoordinator.markUnloaded()
                    activeLoadSession = null
                    _stats.value = _stats.value.copy(
                        loaded = false,
                        lastError = "Deferred pending cleanup failed: ${error.message ?: error::class.java.simpleName}"
                    )
                }
            }
        } finally {
            synchronized(lease) {
                lease.deferredAuthorization = null
                lease.active = false
                lease.releasing = false
            }
            mutex.unlock(lease.ownerToken)
        }
    }

internal suspend fun McaInferenceService.commitDeferredPendingExecutionProfileImpl(
        authorization: LoadAuthorization,
        lease: EngineLifecycleLease
    ): ModelExecutionProfile = withLifecycleLock(lease) {
        require(synchronized(lease) { lease.deferredAuthorization === authorization }) {
            "the lifecycle lease does not own this deferred pending transaction"
        }
        val committed = parameterCoordinator.commitAuthorizedPending(authorization)
        adoptCommittedProfileSessionLocked(committed)
        synchronized(lease) { lease.deferredAuthorization = null }
        committed
    }

    /** Returns null on success; a non-null message means the engine was left unloaded. */
internal suspend fun McaInferenceService.rollbackDeferredPendingExecutionProfileImpl(
        authorization: LoadAuthorization,
        lease: EngineLifecycleLease
    ): String? = withLifecycleLock(lease) {
        require(synchronized(lease) { lease.deferredAuthorization === authorization }) {
            "the lifecycle lease does not own this deferred pending transaction"
        }
        val session = activeLoadSession ?: error("deferred pending transaction has no active session")
        val rollbackError = rollbackAuthorizedPendingLocked(
            PendingRollbackContext(session, authorization)
        )
        if (rollbackError != null) {
            parameterCoordinator.abortAuthorizedPending(authorization)
            parameterCoordinator.markUnloaded()
            activeLoadSession = null
            _stats.value = _stats.value.copy(
                loaded = false,
                lastError = "Deferred pending rollback failed: $rollbackError"
            )
        }
        synchronized(lease) { lease.deferredAuthorization = null }
        rollbackError
    }

internal suspend fun McaInferenceService.preflightChatImpl(
        request: ChatRequest,
        executionContext: LocalChatExecutionContext = LocalChatExecutionContext()
    ): CompletionPreflight = withLifecycleLock(executionContext.lifecycleLease) lifecycle@ {
        val session = activeLoadSession
            ?: return@lifecycle CompletionPreflight.Rejected(
                code = "model_not_loaded",
                changedFields = emptySet(),
                quarantinedOverrides = emptyList(),
                message = "No local model execution profile is loaded."
            )
        parameterCoordinator.preflight(
            identity = session.runtimeIdentity,
            requestParamsJson = request.params.toJson(),
            trustedAuthorization = executionContext.engineLoadAuthorization,
            allowTrustedHotOverride = executionContext.allowTrustedHotOverride,
            hotOverrideAuthorization = executionContext.engineHotOverrideAuthorization
        )
    }

    /**
     * Records a pending profile disposition while holding the lifecycle lease.
     * This is intentionally a regular (non-suspend) helper: keeping the
     * synchronized section out of [streamChat]'s suspend body avoids a Kotlin
     * compiler coroutine-transformer stack overflow on large builds.
     */
internal fun McaInferenceService.deferPendingAuthorization(
        lease: EngineLifecycleLease,
        authorization: LoadAuthorization
    ) {
        synchronized(lease) {
            require(
                lease.deferredAuthorization == null ||
                    lease.deferredAuthorization === authorization
            ) {
                "lifecycle lease already owns another deferred transaction"
            }
            lease.deferredAuthorization = authorization
        }
    }

internal suspend fun <T> McaInferenceService.withLifecycleLock(
        lease: EngineLifecycleLeaseHandle?,
        block: suspend () -> T
    ): T {
        if (lease == null) return mutex.withLock { block() }
        val engineLease = lease as? EngineLifecycleLease
        require(engineLease != null && engineLease.service === this && engineLease.active &&
            mutex.holdsLock(engineLease.ownerToken)
        ) {
            "lifecycle lease is invalid, released, or belongs to another engine"
        }
        return block()
    }

internal fun McaInferenceService.loadParamsForProfile(
        requested: LoadParams,
        profile: ModelExecutionProfile
    ): LoadParams = requested.copy(
        nCtx = (profile.resolvedLoadBoundValues.value("n_ctx") as? Number)?.toInt()
            ?: requested.nCtx,
        nThreads = (profile.hotExecutionValues.value("n_threads") as? Number)?.toInt()
            ?: requested.nThreads,
        mmap = profile.resolvedLoadBoundValues.value("mmap") as? Boolean ?: requested.mmap,
        mlock = profile.resolvedLoadBoundValues.value("mlock") as? Boolean ?: requested.mlock
    )

    /**
     * LiteRT-LM's GPU delegate compiles sizeable kernels during Engine.initialize().
     * Supplying an app-private cache makes that compilation durable and avoids
     * repeating the multi-gigabyte first-load work after every worker restart.
     * The directory is scoped by the model artifact fingerprint so unrelated
     * models/backends never share compiler output.
     */
internal fun McaInferenceService.nativeLoadParamsJson(
        profile: ModelExecutionProfile,
        visionProjectorPath: String? = null,
        fallbackPolicy: LocalBackendFallbackPolicy? = null
    ): String {
        val json = JSONObject(parameterCoordinator.nativeLoadJson(profile))
        fallbackPolicy?.let { json.put("fallback_policy", it.wireName) }
        // The projector is intentionally hidden from the generation editor and
        // therefore may be absent from a persisted profile created before a
        // user bound mmproj. Never let that stale profile drop a valid binding
        // before the VLM create call.
        visionProjectorPath
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { json.put("mmproj_path", it) }
        if (profile.runtimeIdentity.runtime != LocalChatRuntime.LITERT_LM) return json.toString()
        val backend = json.optString("backend").trim().lowercase()
        if (backend.isBlank()) return json.toString()
        val fingerprint = profile.runtimeIdentity.artifactFingerprint
            .filter { it.isLetterOrDigit() }
            .takeIf { it.isNotBlank() }
            ?: "model"
        val cache = File(appContext.cacheDir, "litertlm/$fingerprint-$backend")
            .apply { mkdirs() }
        json.put("cache_dir", cache.absolutePath)
        return json.toString()
    }

    /** Called only while [mutex] is held. */
internal fun McaInferenceService.validateExecutionProfilePathLocked(
        modelPath: String,
        profile: ModelExecutionProfile
    ) {
        activeLoadSession
            ?.takeIf { it.runtimeIdentity.identityHash == profile.runtimeIdentity.identityHash }
            ?.let { session ->
                require(sameModelLocation(session.modelPath, modelPath)) {
                    "the same model runtime identity cannot be rebound to a different model path"
                }
            }
    }

    /** Rebind persisted profile metadata only when the native execution is identical. */
internal fun McaInferenceService.adoptEquivalentLoadedProfileLocked(profile: ModelExecutionProfile) {
        val session = activeLoadSession ?: error("no loaded session is available for profile adoption")
        require(session.runtimeIdentity.identityHash == profile.runtimeIdentity.identityHash &&
            session.executionProfile.resolvedLoadSignature.digest == profile.resolvedLoadSignature.digest &&
            session.executionProfile.committedExecutionSignature.digest == profile.committedExecutionSignature.digest
        ) { "persisted profile is not equivalent to the active native session" }
        parameterCoordinator.commit(profile)
        activeLoadSession = session.copy(
            runtimeIdentity = profile.runtimeIdentity,
            executionProfile = profile,
            nativeLoadParamsJson = nativeLoadParamsJson(
                profile = profile,
                visionProjectorPath = session.params.visionProjectorPath,
                fallbackPolicy = session.params.fallbackPolicy
            ),
            params = loadParamsForProfile(session.params, profile)
        )
    }

    /** Called after ParameterCoordinator has atomically committed [profile]. */
internal fun McaInferenceService.adoptCommittedProfileSessionLocked(profile: ModelExecutionProfile) {
        val session = activeLoadSession ?: error("committed profile has no active native session")
        require(session.runtimeIdentity.identityHash == profile.runtimeIdentity.identityHash) {
            "committed profile identity does not match the active native session"
        }
        activeLoadSession = session.copy(
            params = loadParamsForProfile(session.params, profile),
            runtimeIdentity = profile.runtimeIdentity,
            executionProfile = profile,
            nativeLoadParamsJson = nativeLoadParamsJson(
                profile = profile,
                visionProjectorPath = session.params.visionProjectorPath,
                fallbackPolicy = session.params.fallbackPolicy
            )
        )
    }
    /** Called only while [mutex] is held. A failed transaction is restored once. */
internal suspend fun McaInferenceService.rollbackAuthorizedPendingLocked(
        rollback: PendingRollbackContext
    ): String? {
        val target = runCatching {
            parameterCoordinator.rollbackTargetFor(rollback.authorization)
        }.getOrElse { error ->
            return error.message ?: "pending rollback target is unavailable"
        }
        val session = activeLoadSession ?: rollback.baseSession
        return runCatching {
            reloadCoordinatedProfileLocked(
                session = session,
                target = target,
                authorization = null,
                commitAfterLoad = true
            )
        }.getOrElse { error ->
            error.message ?: error::class.java.simpleName
        }
    }

internal fun CompletionPreflight.Rejected.userFacingPresentation(): PreflightRejectionPresentation =
        when (code) {
            "model_not_loaded" -> PreflightRejectionPresentation(
                message = "模型运行参数尚未就绪，请重新加载当前模型后再试。",
                action = "load_model"
            )
            "model_mismatch" -> PreflightRejectionPresentation(
                message = "请求的模型与当前已加载模型不一致，请切换到正确的模型后再试。",
                action = "select_model"
            )
            "active_profile_drift" -> PreflightRejectionPresentation(
                message = "当前模型的实际运行参数与已保存配置不一致，请重新加载当前模型后再试。",
                action = "reload_model"
            )
            "model_reload_required" -> PreflightRejectionPresentation(
                message = "本次请求修改了需要重新加载模型的参数，请先应用参数并重新加载模型。",
                action = "apply_and_reload"
            )
            "model_reload_required_authorized" -> PreflightRejectionPresentation(
                message = "新模型参数已获授权，但模型尚未完成重新加载，请重新加载后再试。",
                action = "reload_model"
            )
            "execution_override_forbidden" -> PreflightRejectionPresentation(
                message = "本次请求包含不允许临时修改的运行参数，请在模型参数中应用后再试。",
                action = "review_parameters"
            )
            "model_behavior_override_forbidden" -> PreflightRejectionPresentation(
                message = "本次请求包含模板或模型行为参数，请通过受信任的模型配置应用并完成校验。",
                action = "review_parameters"
            )
            else -> PreflightRejectionPresentation(
                message = "模型参数校验未通过，请检查模型参数后重试。",
                action = "review_parameters"
            )
        }

internal data class PreflightRejectionPresentation(
        val message: String,
        val action: String
    )

internal data class PendingRollbackContext(
        val baseSession: LoadedModelSession,
        val authorization: LoadAuthorization
    )
