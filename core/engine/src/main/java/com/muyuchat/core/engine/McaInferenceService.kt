package com.muyuchat.core.engine

import android.content.Context
import android.util.Log
import com.muyuchat.core.modelstore.QairtExecutionAdmission
import com.muyuchat.core.modelstore.QairtBundleRiskAnalyzer
import com.muyuchat.core.modelstore.QairtBundleRuntimeIdentity
import com.muyuchat.core.telemetry.MemorySnapshot
import com.muyuchat.core.telemetry.RuntimeMetrics
import com.muyuchat.core.telemetry.SocDetector
import com.muyuchat.core.telemetry.TelemetryLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max

data class LocalChatExecutionContext(
    val requestId: String = UUID.randomUUID().toString(),
    val loadAuthorization: LoadAuthorization? = null,
    /** Kept for source compatibility; a Boolean alone no longer grants trust. */
    val allowTrustedHotOverride: Boolean = false,
    val hotOverrideAuthorization: HotOverrideAuthorization? = null,
    val pendingProfileDisposition: PendingProfileDisposition = PendingProfileDisposition.COMMIT_ON_VISIBLE_SUCCESS,
    val lifecycleLease: EngineLifecycleLease? = null,
    /** Deterministic internal transforms such as prompt translation must not receive wall-clock text. */
    val includeDeviceClockContext: Boolean = true,
    /**
     * Optional request-scoped observer for privacy-reviewed local-vision preparation diagnostics.
     * The engine isolates observer failures from inference and gives this sink its own JSON copy so
     * callers cannot mutate the existing debug diagnostic or another request's evidence.
     */
    val visionDiagnosticSink: ((String, JSONObject) -> Unit)? = null
)

data class RuntimeHealthSnapshot(
    val runtimeStats: RuntimeStats,
    val workerSessionLost: Boolean
)

/** Opaque identity for exactly one request while it owns the native runner. */
class GenerationStopToken internal constructor(
    val epoch: Long,
    val requestId: String
)

private data class ActiveGenerationStopTarget(
    val token: GenerationStopToken,
    val runner: LocalChatRunner
)

private data class NativeGpuOffloadEvidence(
    val active: Boolean,
    val allocationObserved: Boolean,
    val executionObserved: Boolean,
    val bytes: Long,
    val layers: Int,
    val layersKnown: Boolean,
    val autoFallbackApplied: Boolean,
    val autoFallbackReason: String?
)

/**
 * Native allocation alone must never promote a runtime to GPU execution. This
 * is the shared projection used by load, streaming, UI, and Local API stats.
 */
private fun nativeGpuOffloadEvidence(
    nativeStats: JSONObject?,
    fallback: RuntimeStats
): NativeGpuOffloadEvidence {
    if (nativeStats == null) {
        return NativeGpuOffloadEvidence(
            active = fallback.hasVerifiedGpuExecution,
            allocationObserved = fallback.gpuOffloadAllocationObserved,
            executionObserved = fallback.gpuOffloadExecutionObserved,
            bytes = fallback.gpuOffloadBytes,
            layers = fallback.gpuOffloadLayers,
            layersKnown = fallback.gpuOffloadLayersKnown,
            autoFallbackApplied = fallback.gpuAutoFallbackApplied,
            autoFallbackReason = fallback.gpuAutoFallbackReason
        )
    }
    val allocationObserved = nativeStats.optBoolean("gpuOffloadAllocationObserved", false)
    val executionObserved = nativeStats.optBoolean("gpuOffloadExecutionObserved", false)
    val active = nativeStats.optBoolean("gpuOffloadActive", false) &&
        allocationObserved && executionObserved
    return NativeGpuOffloadEvidence(
        active = active,
        allocationObserved = allocationObserved,
        executionObserved = executionObserved,
        bytes = nativeStats.optLong("gpuOffloadBytes", 0L).coerceAtLeast(0L),
        layers = nativeStats.optInt("gpuOffloadLayers", 0),
        layersKnown = nativeStats.optBoolean("gpuOffloadLayersKnown", false),
        autoFallbackApplied = nativeStats.optBoolean("gpuAutoFallbackApplied", false),
        autoFallbackReason = nativeStats.optString("gpuAutoFallbackReason")
            .takeIf { it.isNotBlank() }
    )
}

/** Opaque, engine-owned exclusive lifecycle lease used by formal candidate evaluation. */
class EngineLifecycleLease internal constructor(
    internal val service: McaInferenceService,
    internal val ownerToken: Any,
    val leaseId: String = UUID.randomUUID().toString()
) {
    @Volatile
    internal var active: Boolean = true
    internal var deferredAuthorization: LoadAuthorization? = null
    internal var releasing: Boolean = false

    /** Idempotent; release is safe from candidate cleanup/finally blocks. */
    suspend fun release() {
        service.releaseExclusiveLifecycleLease(this)
    }
}

enum class PendingProfileDisposition {
    /** Formal user/apply transaction: commit the exact active candidate before Done. */
    COMMIT_ON_VISIBLE_SUCCESS,

    /** Keep the exact candidate active under an exclusive lease for an external correctness gate. */
    DEFER_TO_LEASE_HOLDER,

    /** Formal candidate evaluation: run under the candidate, then restore the locked rollback target. */
    ROLLBACK_AFTER_REQUEST
}

class McaInferenceService(
    context: Context,
    runners: Map<LocalChatRuntime, LocalChatRunner>? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val memorySnapshotProvider: (() -> MemorySnapshot)? = null,
    qairtVerificationStoreOverride: QairtExecutionVerificationStore? = null,
    qairtIdentityProviderOverride: ((String?) -> QairtBundleRuntimeIdentity?)? = null,
    qairtDryRunProcessVerifierOverride: (() -> Boolean)? = null,
    private val parameterCoordinator: ParameterCoordinator = ParameterCoordinator(),
    installationScopeId: String? = null,
    private val deviceClockContextProvider: DeviceClockContextProvider = DeviceClockContextProvider(),
    persistentPrefixCacheStoreOverride: PersistentPrefixCacheStore? = null,
    private val prefillProgressPollIntervalMs: Long = PREFILL_PROGRESS_POLL_INTERVAL_MS
) {
    init {
        require(prefillProgressPollIntervalMs > 0L) {
            "prefillProgressPollIntervalMs must be positive."
        }
    }
    private val runners: Map<LocalChatRuntime, LocalChatRunner> =
        runners ?: defaultLocalChatRunners(context.applicationContext)
    private val mutex = Mutex()
    private val generationStopGate = Any()
    private var generationStopEpoch = 0L
    private var activeGenerationStopTarget: ActiveGenerationStopTarget? = null
    private val telemetry = TelemetryLogger(context.applicationContext)
    private val socInfo = SocDetector.detect()
    private val appContext = context.applicationContext
    private val parameterInstallationScopeId = installationScopeId
        ?.takeIf { it.isNotBlank() }
        ?: "process-local:${UUID.randomUUID()}"
    private val persistentPrefixCacheStore: PersistentPrefixCacheStore by lazy {
        persistentPrefixCacheStoreOverride ?: PersistentPrefixCacheStore(
            rootDirectory = runCatching { appContext.noBackupFilesDir }
                .getOrNull()
                ?.let { File(it, "mca_llama_prefix_cache/v1") }
                // Unit-test Context wrappers may not implement noBackupFilesDir;
                // production Android always takes the no-backup branch above.
                ?: File(appContext.filesDir, "mca_llama_prefix_cache/v1")
        )
    }
    private val qairtVerificationStore = qairtVerificationStoreOverride
        ?: QairtExecutionVerificationStore.forContext(appContext)
    private val qairtIdentityProvider: (String?) -> QairtBundleRuntimeIdentity? =
        qairtIdentityProviderOverride ?: { bundleSha256 ->
            qairtRuntimeIdentityFor(appContext, bundleSha256)
        }
    private val qairtDryRunProcessVerifier: () -> Boolean =
        qairtDryRunProcessVerifierOverride ?: { isQairtIsolatedDryRunProcess(appContext) }
    private val _stats = MutableStateFlow(RuntimeStats(backend = "cpu", loaded = false))
    private var activeRuntime: LocalChatRuntime = LocalChatRuntime.MNN_CPU
    private var activeLoadSession: LoadedModelSession? = null
    @Volatile
    private var lastQairtExecutionAdmission: QairtExecutionAdmission? = null
    private var qairtDryRunWitness: QairtDryRunWitness? = null
    @Volatile
    private var persistentPrefixCacheEnabled = true
    // A failed or cancelled MNN turn can leave native state unsafe to reuse.
    // Most successful text turns are reset in native beginCompletion(); the
    // narrow Gemma 4 text-isolation exception is selected by
    // MnnSessionLifecyclePolicy after a successful turn.
    private var mnnSessionNeedsReloadBeforeNextRequest = false
    private var isolatedWorkerSessionNeedsReload = false

    val stats = _stats.asStateFlow()

    /**
     * Latest QAIRT diagnostic recommendation. Unknown bundle/device/runtime
     * combinations remain runnable; this value never admits or rejects a load.
     */
    val qairtExecutionAdmission: QairtExecutionAdmission?
        get() = lastQairtExecutionAdmission

    /**
     * Lists only aggregate usage of the app-private llama.cpp prefix cache.
     * Cache keys and persisted KV content intentionally stay private to the engine.
     */
    fun persistentPrefixCacheSummary(): PersistentPrefixCacheSummary =
        runCatching { persistentPrefixCacheStore.summary() }
            .getOrDefault(PersistentPrefixCacheSummary(entryCount = 0, totalBytes = 0L))

    /**
     * Enables or disables future persisted llama.cpp prefix state. Disabling
     * also prevents an already-running prefill from publishing its staging file.
     */
    fun setPersistentPrefixCacheEnabled(enabled: Boolean) {
        persistentPrefixCacheEnabled = enabled
    }

    /** Clears persisted fixed-prefix state. The caller owns any live-context invalidation. */
    suspend fun clearPersistentPrefixCache(): Boolean = withContext(io) {
        runCatching { persistentPrefixCacheStore.clear() }.getOrDefault(false)
    }

    /**
     * Debug/validation tooling calls this only after an isolated QAIRT smoke has
     * reached create, generated a visible response, and cleanly destroyed the
     * native handle. A normal app load never self-certifies an unverified bundle.
     */
    fun recordVerifiedQairtDryRun(bundleSha256: String?): Boolean {
        val identity = qairtIdentityProvider(bundleSha256) ?: return false
        val witness = qairtDryRunWitness ?: return false
        if (!qairtDryRunProcessVerifier() ||
            witness.identity != identity ||
            !witness.sawNpuExecution ||
            !witness.sawVisibleCompletion ||
            !witness.destroyedCleanly
        ) {
            return false
        }
        qairtVerificationStore.recordVerified(identity)
        qairtDryRunWitness = null
        return true
    }

    fun isRuntimeAvailable(runtime: LocalChatRuntime): Boolean =
        this.runners[runtime]?.isAvailable == true

    fun parameterSignatureSnapshot(): ParameterSignatureSnapshot? =
        parameterCoordinator.snapshot()

    /**
     * Read-only six-signature proof for the exact unconsumed pending candidate.
     * RuntimeOverride is forced to NONE for candidate validation.
     */
    fun authorizedPendingSignatureVerification(
        authorization: LoadAuthorization
    ): AuthorizedPendingSignatureVerification? =
        parameterCoordinator.authorizedPendingSignatureVerification(authorization)

    fun activeExecutionProfile(): ModelExecutionProfile? =
        parameterCoordinator.committedProfile()

    /**
     * Resolves a user-authored parameter document against the exact runtime
     * identity without mutating the active model. Persistence/UI code uses the
     * returned profile to build a field-level pending transaction.
     */
    fun resolveExecutionProfile(
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
    fun stagePendingExecutionProfile(
        transactionId: String,
        profile: ModelExecutionProfile,
        rollbackTargetProfileId: String? = activeExecutionProfile()?.profileId
    ): LoadAuthorization {
        val authorization = parameterCoordinator.createLoadAuthorization(transactionId, profile)
        parameterCoordinator.stageAuthorizedPending(profile, authorization, rollbackTargetProfileId)
        return authorization
    }

    /** Issues a value-bound HOT_EXECUTION lease; a public Boolean cannot replace it. */
    fun authorizeHotExecutionOverride(
        profile: ModelExecutionProfile,
        values: CanonicalParameterSet
    ): HotOverrideAuthorization = parameterCoordinator.createHotOverrideAuthorization(profile, values)

    /**
     * Waits until the real engine lifecycle is exclusively owned. Formal candidate evaluation may
     * preempt an active stream; deterministic background transforms use the non-preemptive mode so
     * timing out while waiting can never stop another caller's generation.
     */
    suspend fun acquireExclusiveLifecycleLease(
        stopActiveGeneration: Boolean = true
    ): EngineLifecycleLease {
        if (stopActiveGeneration) stopGeneration()
        val owner = Any()
        mutex.lock(owner)
        return EngineLifecycleLease(this, owner)
    }

    internal suspend fun releaseExclusiveLifecycleLease(lease: EngineLifecycleLease) {
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

    suspend fun commitDeferredPendingExecutionProfile(
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
    suspend fun rollbackDeferredPendingExecutionProfile(
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

    suspend fun preflightChat(
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
            trustedAuthorization = executionContext.loadAuthorization,
            allowTrustedHotOverride = executionContext.allowTrustedHotOverride,
            hotOverrideAuthorization = executionContext.hotOverrideAuthorization
        )
    }

    init {
        this.runners.values.forEach { runner ->
            if (runner.isAvailable) {
                runCatching { runner.initBackends(appContext.applicationInfo.nativeLibraryDir) }
            }
        }
        val defaultRunner = this.runners[activeRuntime]
        if (defaultRunner != null && !defaultRunner.isAvailable) {
            _stats.value = _stats.value.copy(
                backend = activeRuntime.backendId,
                lastError = defaultRunner.loadError?.message
            )
        }
    }

    suspend fun loadModel(
        modelPath: String,
        runtime: LocalChatRuntime = LocalChatRuntime.MNN_CPU,
        params: LoadParams = LoadParams(),
        qairtBundleSha256: String? = null,
        qairtExecutionPurpose: QairtExecutionPurpose = QairtExecutionPurpose.NORMAL,
        runtimeIdentity: ModelRuntimeIdentity? = null,
        executionProfile: ModelExecutionProfile? = null
    ): Result<RuntimeStats> = withContext(io) {
        runCatching {
            require(modelPath.isNotBlank()) { "modelPath must not be blank" }
            val resolvedIdentity = executionProfile?.runtimeIdentity
                ?: runtimeIdentity
                ?: defaultRuntimeIdentity(
                    modelPath = modelPath,
                    runtime = runtime,
                    params = params,
                    artifactFingerprintOverride = qairtBundleSha256
                        ?.trim()
                        ?.takeIf { runtime == LocalChatRuntime.GENIEX_QAIRT && it.isNotBlank() }
                )
            executionProfile?.let { persisted ->
                require(persisted.runtimeIdentity.runtime == runtime) {
                    "execution profile runtime does not match requested runtime"
                }
                require(runtimeIdentity == null || runtimeIdentity.identityHash == persisted.runtimeIdentity.identityHash) {
                    "execution profile identity does not match requested runtime identity"
                }
            }
            val resolvedExecutionProfile = executionProfile ?: parameterCoordinator.resolveProfile(
                identity = resolvedIdentity,
                requestedParamsJson = params.toJson(),
                profileId = UUID.randomUUID().toString()
            ).profile
            require(resolvedExecutionProfile.runtimeIdentity.identityHash == resolvedIdentity.identityHash) {
                "execution profile identity changed during load resolution"
            }
            val effectiveLoadParams = loadParamsForProfile(params, resolvedExecutionProfile)
            val nativeLoadParamsJson = parameterCoordinator.nativeLoadJson(resolvedExecutionProfile)
            val admissionMemory = memorySnapshotProvider?.invoke() ?: telemetry.memorySnapshotDetailed()
            val admission = qairtExecutionAdmissionForLoad(
                modelPath = modelPath,
                runtime = runtime,
                memory = admissionMemory,
                bundleSha256 = qairtBundleSha256
            )
            lastQairtExecutionAdmission = admission
            requireQairtExecutionPurpose(
                runtime = runtime,
                purpose = qairtExecutionPurpose,
            )
            // Selecting an already healthy MNN model is an idempotent UI action.
            // Avoiding a pointless native unload/load is essential for Gemma,
            // whose upstream session can hang after repeated cold construction.
            // Failed/cancelled MNN turns deliberately bypass this fast path and
            // are refreshed below before their next request.
            mutex.withLock {
                validateExecutionProfilePathLocked(modelPath, resolvedExecutionProfile)
                parameterCoordinator.prepareOrdinaryLoad(resolvedExecutionProfile)
                reusableMnnLoadedStats(modelPath, runtime, resolvedExecutionProfile)
            }?.let { reusable ->
                if (executionProfile != null) {
                    mutex.withLock { adoptEquivalentLoadedProfileLocked(resolvedExecutionProfile) }
                }
                return@runCatching reusable
            }
            stopGeneration()
            mutex.withLock {
                // A running request may have completed while stopGeneration()
                // waited outside the lock. Recheck so that concurrent model-page
                // taps still do not tear down a healthy MNN session.
                reusableMnnLoadedStats(modelPath, runtime, resolvedExecutionProfile)?.let { reusable ->
                    if (executionProfile != null) adoptEquivalentLoadedProfileLocked(resolvedExecutionProfile)
                    return@withLock reusable
                }
                runCatching { runnerFor(activeRuntime).unloadModel() }
                parameterCoordinator.markUnloaded()
                _stats.value = RuntimeStats(loaded = false, backend = runtime.backendId)
                activeRuntime = runtime
                activeLoadSession = null
                mnnSessionNeedsReloadBeforeNextRequest = false
                if (runtime == LocalChatRuntime.GENIEX_QAIRT) {
                    // A new QAIRT attempt must earn a new witness. Do not let a
                    // prior create/generate result certify a different handle.
                    qairtDryRunWitness = null
                }
                val runner = runnerFor(runtime)
                if (!runner.isAvailable) {
                    val message = unavailableStats(runtime, runner.loadError).optString("lastError")
                    _stats.value = RuntimeStats(
                        loaded = false,
                        backend = runtime.backendId,
                        modelPath = modelPath,
                        nThreads = effectiveLoadParams.nThreads,
                        nCtx = effectiveLoadParams.nCtx,
                        maxAllTokens = effectiveLoadParams.nCtx,
                        lastError = message
                    )
                    error(message)
                }
                val started = System.currentTimeMillis()
                val rc = runner.loadModel(modelPath, nativeLoadParamsJson)
                if (rc != 0) {
                    val nativeStats = nativeStatsJson()
                    val nativeError = runCatching {
                        JSONObject(nativeStats).optString("lastError").takeIf { it.isNotBlank() }
                    }.getOrNull()
                    error(
                        buildString {
                            append("Native loadModel failed: ").append(rc)
                            if (!nativeError.isNullOrBlank()) append("；").append(nativeError.trim())
                        }.also { message ->
                            runCatching { runner.requestStop() }
                            runCatching { runner.unloadModel() }
                            activeLoadSession = null
                            mnnSessionNeedsReloadBeforeNextRequest = false
                            _stats.value = RuntimeStats(
                                loaded = false,
                                modelPath = modelPath,
                                backend = runtime.backendId,
                                nThreads = effectiveLoadParams.nThreads,
                                nThreadsBatch = effectiveLoadParams.nThreads,
                                nCtx = effectiveLoadParams.nCtx,
                                maxAllTokens = effectiveLoadParams.nCtx,
                                lastError = message
                            )
                        }
                    )
                }
                val loadMs = System.currentTimeMillis() - started
                val stats = loadedStatsFromNative(modelPath, runtime, effectiveLoadParams, loadMs)
                if (runtime == LocalChatRuntime.GENIEX_QAIRT &&
                    qairtExecutionPurpose == QairtExecutionPurpose.ISOLATED_DRY_RUN
                ) {
                    val identity = qairtIdentityProvider(qairtBundleSha256)
                        ?.takeIf(QairtBundleRuntimeIdentity::isComplete)
                    val nativeStats = runCatching { JSONObject(nativeStatsJson()) }.getOrNull()
                    if (nativeStats == null || !hasQairtNpuExecutionEvidence(nativeStats)) {
                        runCatching { runner.requestStop() }
                        runCatching { runner.unloadModel() }
                        parameterCoordinator.markUnloaded()
                        activeLoadSession = null
                        _stats.value = RuntimeStats(
                            loaded = false,
                            modelPath = modelPath,
                            backend = runtime.backendId,
                            nThreads = effectiveLoadParams.nThreads,
                            nCtx = effectiveLoadParams.nCtx,
                            maxAllTokens = effectiveLoadParams.nCtx,
                            lastError = "QAIRT 隔离安全启动未取得 NPU 执行证据。"
                        )
                        error("QAIRT 隔离安全启动未取得 NPU 执行证据。")
                    }
                    // A missing diagnostic identity must not stop a concrete
                    // native smoke. It only means this result cannot be cached
                    // as reusable verification evidence.
                    qairtDryRunWitness = identity?.let {
                        QairtDryRunWitness(
                            identity = it,
                            sawNpuExecution = true,
                            npuEvidence = nativeStats.opt("backendDevices")?.toString().orEmpty()
                        )
                    }
                }
                if (runtime.usesCoordinatedParameters()) {
                    val nativeStatsBeforeSignaturePublish = nativeStatsJson()
                    runCatching {
                        // QAIRT publish/commit is intentionally after both static
                        // admission and native NPU evidence validation above.
                        parameterCoordinator.publishLoaded(
                            resolvedExecutionProfile,
                            nativeStatsBeforeSignaturePublish
                        )
                        parameterCoordinator.commit(resolvedExecutionProfile)
                    }.getOrElse { signatureError ->
                        val diagnostic = parameterCoordinator.loadSignatureDiagnostic(
                            profile = resolvedExecutionProfile,
                            nativeStatsJson = nativeStatsBeforeSignaturePublish
                        ).put("error", signatureError.message.orEmpty())
                        Log.e(
                            "McaInferenceService",
                            "native load signature mismatch: $diagnostic"
                        )
                        LocalChatRunnerDebug.emit(
                            "native_load_signature_mismatch",
                            diagnostic
                        )
                        runCatching { runner.requestStop() }
                        runCatching { runner.unloadModel() }
                        parameterCoordinator.markUnloaded()
                        if (runtime == LocalChatRuntime.GENIEX_QAIRT) qairtDryRunWitness = null
                        activeLoadSession = null
                        mnnSessionNeedsReloadBeforeNextRequest = false
                        error("Native effective parameter verification failed: ${signatureError.message}")
                    }
                }
                activeLoadSession = LoadedModelSession(
                    modelPath = modelPath,
                    runtime = runtime,
                    params = effectiveLoadParams,
                    runtimeIdentity = resolvedIdentity,
                    executionProfile = resolvedExecutionProfile,
                    nativeLoadParamsJson = nativeLoadParamsJson
                )
                mnnSessionNeedsReloadBeforeNextRequest = false
                isolatedWorkerSessionNeedsReload = false
                _stats.value = stats
                stats
            }
        }
    }

    suspend fun unloadModel() = withContext(io) {
        stopGeneration()
        mutex.withLock {
            val unloadedRuntime = activeRuntime
            val dryRunWitness = qairtDryRunWitness
            runnerFor(unloadedRuntime).unloadModel()
            parameterCoordinator.markUnloaded()
            if (unloadedRuntime == LocalChatRuntime.GENIEX_QAIRT && dryRunWitness != null) {
                val nativeStats = runCatching { JSONObject(nativeStatsJson()) }.getOrNull()
                dryRunWitness.destroyedCleanly = nativeStats != null &&
                    !nativeStats.optBoolean("loaded", true) &&
                    nativeStats.optString("lastError").isBlank()
            }
            activeLoadSession = null
            mnnSessionNeedsReloadBeforeNextRequest = false
            isolatedWorkerSessionNeedsReload = false
            lastQairtExecutionAdmission = null
            _stats.value = RuntimeStats(loaded = false, backend = activeRuntime.backendId)
        }
    }

    /**
     * Discards text KV/checkpoint state after a user edits, removes, or switches
     * a conversation. The operation is serialized with generation so a later
     * request can never continue from the removed history.
     */
    suspend fun invalidateConversationContext() = withContext(io) {
        mutex.withLock {
            if (!_stats.value.loaded) return@withLock
            runnerFor(activeRuntime).invalidateConversationContext()
            // Current MNN runtimes clear their live prompt/KV state here and perform an
            // exact token-prefix check on the next begin. Reloading multi-gigabyte weights
            // after a new chat or edit is unnecessary; begin still falls back to a cold
            // prefill whenever the retained prefix does not match.
        }
    }

    fun streamChat(
        request: ChatRequest,
        executionContext: LocalChatExecutionContext = LocalChatExecutionContext()
    ): Flow<GenerateEvent> = flow {
        var pendingRollbackContext: PendingRollbackContext? = null
        var pendingTransactionCommitted = false
        var pendingTransactionDeferred = false
        try {
            withLifecycleLock(executionContext.lifecycleLease) lifecycle@ {
            var current = _stats.value
            if (activeLoadSession != null &&
                (isolatedWorkerSessionNeedsReload || isolatedWorkerSessionLostLocked())
            ) {
                emit(GenerateEvent.Phase(GenerationPhase.LOAD, current))
                val reloadError = reloadActiveSessionForNextRequestLocked(
                    SessionReloadReason.WORKER_SESSION_LOST
                )
                if (reloadError != null) {
                    val errorStats = _stats.value.copy(lastError = reloadError)
                    _stats.value = errorStats
                    emit(GenerateEvent.Error(reloadError, errorStats))
                    return@lifecycle
                }
                current = _stats.value
            }
            if (!current.loaded) {
                val errorStats = current.copy(lastError = "No local chat model is loaded.")
                emit(GenerateEvent.Error("请先在模型页加载一个本地推理模型。", errorStats))
                return@lifecycle
            }
            val memoryBeforeGenerate = telemetry.memorySnapshotDetailed()
            if (memoryBeforeGenerate.availMemKb in 1 until LOW_MEMORY_START_GUARD_KB) {
                val message = "当前可用内存过低（约 ${formatMb(memoryBeforeGenerate.availMemKb)}），已拦截本轮生成。请关闭后台应用、降低上下文或换更小模型。"
                val errorStats = current.copy(
                    lastError = message
                ).withMemory(memoryBeforeGenerate)
                _stats.value = errorStats
                emit(GenerateEvent.Error(message, errorStats))
                return@lifecycle
            }

            val deviceClockContext = if (executionContext.includeDeviceClockContext) {
                deviceClockContextProvider.contextFor(request.messages)
            } else {
                ""
            }
            val requestWithRuntimeContext = request.copy(
                runtimeSystemContext = listOf(request.runtimeSystemContext, deviceClockContext)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
            )

            val contextAdmission = localContextWindowAdmission(requestWithRuntimeContext)
            if (!contextAdmission.isAccepted) {
                val message = contextAdmission.userMessage
                    ?: "\u5f53\u524d\u8bf7\u6c42\u65e0\u6cd5\u653e\u5165\u672c\u5730\u6a21\u578b\u4e0a\u4e0b\u6587\u3002"
                val errorStats = current.copy(lastError = message)
                _stats.value = errorStats
                emit(
                    GenerateEvent.Error(
                        message = message,
                        stats = errorStats,
                        code = CONTEXT_LENGTH_EXCEEDED_ERROR_CODE
                    )
                )
                return@lifecycle
            }
            val contextSafeRequest = contextAdmission.request

            val incomingHasImageAttachments = contextSafeRequest.hasImageAttachments()
            if (activeRuntime == LocalChatRuntime.MNN_CPU && mnnSessionNeedsReloadBeforeNextRequest) {
                emit(GenerateEvent.Phase(GenerationPhase.LOAD, _stats.value))
                val reloadError = reloadActiveSessionForNextRequestLocked(
                    SessionReloadReason.MNN_CONTEXT_RESET
                )
                if (reloadError != null) {
                    val errorStats = _stats.value.copy(lastError = reloadError)
                    _stats.value = errorStats
                    emit(GenerateEvent.Error(reloadError, errorStats))
                    return@lifecycle
                }
                current = _stats.value
            }

            var preparedParameters: CompletionPreflight.Ready? = null
            if (activeRuntime.usesCoordinatedParameters()) {
                val session = activeLoadSession
                if (session == null) {
                    val message = "模型运行参数状态缺失，请重新加载当前模型。"
                    val errorStats = current.copy(lastError = message)
                    _stats.value = errorStats
                    emit(GenerateEvent.Error(message, errorStats))
                    return@lifecycle
                }
                val suppliedLoadAuthorization = executionContext.loadAuthorization
                if (suppliedLoadAuthorization != null &&
                    parameterCoordinator.isAuthorizedPendingActive(suppliedLoadAuthorization)
                ) {
                    pendingRollbackContext = PendingRollbackContext(
                        baseSession = session,
                        authorization = suppliedLoadAuthorization
                    )
                }
                var preflight = parameterCoordinator.preflight(
                    identity = session.runtimeIdentity,
                    requestParamsJson = requestWithRuntimeContext.params.toJson(),
                    trustedAuthorization = executionContext.loadAuthorization,
                    allowTrustedHotOverride = executionContext.allowTrustedHotOverride,
                    hotOverrideAuthorization = executionContext.hotOverrideAuthorization
                )
                if (preflight is CompletionPreflight.Rejected &&
                    preflight.code == "model_reload_required_authorized"
                ) {
                    val decision = parameterCoordinator.decideMismatchRecovery(
                        requestId = executionContext.requestId,
                        identity = session.runtimeIdentity,
                        trustedAuthorization = executionContext.loadAuthorization
                    )
                    val reloadError = when (decision) {
                        is MismatchRecoveryDecision.ReloadAuthorizedPending -> {
                            emit(GenerateEvent.Phase(GenerationPhase.LOAD, _stats.value))
                            pendingRollbackContext = pendingRollbackContext ?: PendingRollbackContext(
                                baseSession = session,
                                authorization = decision.transaction.authorization
                            )
                            reloadCoordinatedProfileLocked(
                                session = session,
                                target = decision.transaction.profile,
                                authorization = decision.transaction.authorization,
                                commitAfterLoad = false
                            )
                        }
                        is MismatchRecoveryDecision.ReloadCommittedForDrift -> {
                            emit(GenerateEvent.Phase(GenerationPhase.LOAD, _stats.value))
                            reloadCoordinatedProfileLocked(session, decision.profile, null, false)
                        }
                        is MismatchRecoveryDecision.Fail -> decision.message
                    }
                    if (reloadError != null) {
                        val errorStats = _stats.value.copy(lastError = reloadError)
                        _stats.value = errorStats
                        emit(GenerateEvent.Error(reloadError, errorStats))
                        return@lifecycle
                    }
                    current = _stats.value
                    val reloadedSession = activeLoadSession ?: session
                    preflight = parameterCoordinator.preflight(
                        identity = reloadedSession.runtimeIdentity,
                        requestParamsJson = requestWithRuntimeContext.params.toJson(),
                        trustedAuthorization = executionContext.loadAuthorization,
                        allowTrustedHotOverride = executionContext.allowTrustedHotOverride,
                        hotOverrideAuthorization = executionContext.hotOverrideAuthorization
                    )
                }
                when (preflight) {
                    is CompletionPreflight.Ready -> {
                        preparedParameters = preflight
                        val staged = executionContext.loadAuthorization
                            ?.let(parameterCoordinator::authorizedPendingFor)
                            ?.takeIf { transaction ->
                                transaction.profile.desiredSignature.digest == preflight.signatures.desired.digest &&
                                    transaction.profile.resolvedLoadSignature.digest == preflight.signatures.resolved.digest &&
                                    transaction.profile.committedExecutionSignature.digest == preflight.signatures.committed.digest
                            }
                        if (staged != null) {
                            pendingRollbackContext = pendingRollbackContext ?: PendingRollbackContext(
                                baseSession = activeLoadSession ?: session,
                                authorization = staged.authorization
                            )
                        }
                    }
                    is CompletionPreflight.Rejected -> {
                        val presentation = preflight.userFacingPresentation()
                        val errorStats = current.copy(lastError = presentation.message)
                        _stats.value = errorStats
                        emit(
                            GenerateEvent.Error(
                                message = presentation.message,
                                stats = errorStats,
                                code = preflight.code,
                                changedFields = preflight.changedFields.toSortedSet(),
                                action = presentation.action
                            )
                        )
                        return@lifecycle
                    }
                }
            }

            val requestWithVisionFiles = if (incomingHasImageAttachments) {
                if (!localVisionReady()) {
                    val message = "当前本地模型未启用识图。请加载 MNN 多模态包，或加载本地多模态 GGUF 并绑定匹配 mmproj 后重新加载模型。"
                    val errorStats = current.copy(lastError = message)
                    _stats.value = errorStats
                    emit(GenerateEvent.Error(message, errorStats))
                    return@lifecycle
                }
                runCatching {
                    withContext(io) {
                        LocalVisionInputPreparer.prepare(
                            request = contextSafeRequest,
                            cacheDir = appContext.cacheDir,
                            diagnosticSink = { stage, details ->
                                dispatchVisionDiagnostic(executionContext, stage, details)
                            }
                        )
                    }
                }.getOrElse { error ->
                    val message = "本地图片预处理失败：${error.message ?: "无法读取图片"}"
                    val errorStats = current.copy(lastError = message)
                    _stats.value = errorStats
                    emit(GenerateEvent.Error(message, errorStats))
                    return@lifecycle
                }
            } else {
                contextSafeRequest
            }
            val activeRequest = requestWithVisionFiles
            val runner = runnerFor(activeRuntime)
            val generationStopToken = activateGenerationStopTarget(executionContext.requestId, runner)
            try {

            val started = System.currentTimeMillis()
            val hasImageAttachments = activeRequest.hasImageAttachments()
            val shouldRefreshMnnAfterRequest = activeRuntime == LocalChatRuntime.MNN_CPU
            var activePersistentPrefix: PreparedPersistentPrefix? = null
            var managedPersistentPrefixFailure: ManagedPersistentPrefixFailure? = null
            var exactPrefillPhaseEmitted = false
            suspend fun emitTokenizePhase() {
                emit(GenerateEvent.Phase(GenerationPhase.TOKENIZE, _stats.value))
            }
            suspend fun beginNative(paramsJson: String): Result<Int> {
                var downstreamPrefillEmissionFailed = false
                return try {
                    activePersistentPrefix?.let { previous ->
                        withContext(NonCancellable + io) {
                            discardPersistentPrefix(previous)
                            if (activePersistentPrefix === previous) activePersistentPrefix = null
                        }
                    }
                    val persistentPrefix = withContext(NonCancellable + io) {
                        preparePersistentPrefix(
                            request = activeRequest,
                            preparedParameters = preparedParameters,
                            paramsJson = paramsJson,
                            hasImageAttachments = hasImageAttachments
                        ).also { activePersistentPrefix = it }
                    }
                    val messagesJson = activeRequest.messagesJson(
                        multimodal = hasImageAttachments,
                        contentEncoding = if (activeRuntime == LocalChatRuntime.MNN_CPU) {
                            MultimodalContentEncoding.MNN_IMAGE_TAGS_FIRST
                        } else {
                            MultimodalContentEncoding.OPENAI_PARTS
                        }
                    )
                    exactPrefillPhaseEmitted = false
                    var lastPrefillProgress: TokenProgress? = null
                    suspend fun emitExactPrefillProgress(progress: TokenProgress) {
                        try {
                            emit(
                                GenerateEvent.Phase(
                                    phase = GenerationPhase.PREFILL,
                                    stats = _stats.value,
                                    tokenProgress = progress
                                )
                            )
                        } catch (error: Throwable) {
                            downstreamPrefillEmissionFailed = true
                            throw error
                        }
                        exactPrefillPhaseEmitted = true
                        lastPrefillProgress = progress
                    }
                    val resultCode =
                        coroutineScope {
                            // A previous request leaves its final 100% snapshot
                            // available for diagnostics. Clear it before this
                            // request starts so polling can never attribute that
                            // completed work to a new prompt.
                            runCatching {
                                withContext(io) { runner.resetPrefillProgress() }
                            }
                            val nativeBegin = async(io) {
                                if (persistentPrefix == null) {
                                    runner.beginCompletion(messagesJson, paramsJson)
                                } else {
                                    runner.beginCompletionWithPrefixCache(
                                        messagesJson = messagesJson,
                                        paramsJson = paramsJson,
                                        prefixCache = persistentPrefix.request
                                    )
                                }
                            }
                            var nativeResult: Int? = null
                            while (nativeResult == null) {
                                // Isolated text runners obtain this snapshot through Binder.
                                // Keep that IPC off the caller/UI dispatcher while
                                // native prefill runs in the worker process.
                                val progress = withContext(io) { runner.prefillProgress() }
                                if (progress != null && progress != lastPrefillProgress) {
                                    emitExactPrefillProgress(progress)
                                }
                                // Do not impose one polling interval of TTFT
                                // latency on short prompts: return immediately
                                // as soon as native begin completes, otherwise
                                // wake only to publish a newer exact batch count.
                                nativeResult = withTimeoutOrNull(prefillProgressPollIntervalMs) {
                                    nativeBegin.await()
                                }
                            }
                            // The final native batch can finish between the last
                            // poll and completion. Publish that exact terminal
                            // snapshot before decode starts.
                            val finalProgress = withContext(io) { runner.prefillProgress() }
                            if (finalProgress != null && finalProgress != lastPrefillProgress) {
                                emitExactPrefillProgress(finalProgress)
                            }
                            checkNotNull(nativeResult)
                        }
                    withContext(NonCancellable + io) {
                        finishPersistentPrefix(persistentPrefix, resultCode, runner)?.let { failure ->
                            managedPersistentPrefixFailure = failure
                            _stats.value = _stats.value.copy(
                                persistentPrefixCacheHit = false,
                                persistentPrefixCacheTokens = failure.tokens,
                                persistentPrefixCacheReason = failure.reason
                            )
                        }
                        if (persistentPrefix != null && activePersistentPrefix === persistentPrefix) {
                            activePersistentPrefix = null
                        }
                    }
                    Result.success(resultCode)
                } catch (error: Throwable) {
                    withContext(NonCancellable + io) {
                        activePersistentPrefix?.let(::discardPersistentPrefix)
                        if (activePersistentPrefix != null) {
                            activePersistentPrefix = null
                        }
                    }
                    if (downstreamPrefillEmissionFailed) throw error
                    Result.failure(error)
                }
            }
            emitTokenizePhase()
            var beginResult = beginNative(
                preparedParameters?.nativeParamsJson ?: activeRequest.params.toJson()
            )
            beginResult.exceptionOrNull()?.let { error ->
                if (shouldRefreshMnnAfterRequest) {
                    mnnSessionNeedsReloadBeforeNextRequest = true
                }
                runCatching { runner.requestStop() }
                if (error is CancellationException) throw error
                val message = "Native beginCompletion failed: ${error.message ?: error::class.java.simpleName}"
                val errorStats = errorStatsForRunnerFailure(runner, message)
                _stats.value = errorStats
                emit(GenerateEvent.Error(message, errorStats))
                return@lifecycle
            }
            var beginRc = beginResult.getOrThrow()
            if (beginRc != 0 && activeRuntime.usesCoordinatedParameters()) {
                val session = activeLoadSession
                val nativeError = runCatching {
                    JSONObject(nativeStatsJson()).optString("lastError").takeIf { it.isNotBlank() }
                }.getOrNull()
                if (session != null && parameterCoordinator.isLoadSignatureMismatch(
                        session.runtimeIdentity,
                        beginRc,
                        nativeError
                    )
                ) {
                    val decision = parameterCoordinator.decideMismatchRecovery(
                        requestId = executionContext.requestId,
                        identity = session.runtimeIdentity,
                        trustedAuthorization = executionContext.loadAuthorization
                    )
                    val reloadError = when (decision) {
                        is MismatchRecoveryDecision.ReloadAuthorizedPending -> {
                            emit(GenerateEvent.Phase(GenerationPhase.LOAD, _stats.value))
                            pendingRollbackContext = pendingRollbackContext ?: PendingRollbackContext(
                                baseSession = session,
                                authorization = decision.transaction.authorization
                            )
                            reloadCoordinatedProfileLocked(
                                session,
                                decision.transaction.profile,
                                decision.transaction.authorization,
                                false
                            )
                        }
                        is MismatchRecoveryDecision.ReloadCommittedForDrift -> {
                            emit(GenerateEvent.Phase(GenerationPhase.LOAD, _stats.value))
                            reloadCoordinatedProfileLocked(session, decision.profile, null, false)
                        }
                        is MismatchRecoveryDecision.Fail -> decision.message
                    }
                    if (reloadError == null) {
                        current = _stats.value
                        val reloadedSession = activeLoadSession
                        val retryPreflight = reloadedSession?.let {
                            parameterCoordinator.preflight(
                                identity = it.runtimeIdentity,
                                requestParamsJson = activeRequest.params.toJson(),
                                trustedAuthorization = executionContext.loadAuthorization,
                                allowTrustedHotOverride = executionContext.allowTrustedHotOverride,
                                hotOverrideAuthorization = executionContext.hotOverrideAuthorization
                            )
                        }
                        if (retryPreflight is CompletionPreflight.Ready) {
                            preparedParameters = retryPreflight
                            emitTokenizePhase()
                            beginResult = beginNative(retryPreflight.nativeParamsJson)
                            beginResult.exceptionOrNull()?.let { error ->
                                if (shouldRefreshMnnAfterRequest) mnnSessionNeedsReloadBeforeNextRequest = true
                                runCatching { runner.requestStop() }
                                if (error is CancellationException) throw error
                                val message = "Native beginCompletion recovery failed: ${error.message ?: error::class.java.simpleName}"
                                val errorStats = errorStatsForRunnerFailure(runner, message)
                                _stats.value = errorStats
                                emit(GenerateEvent.Error(message, errorStats))
                                return@lifecycle
                            }
                            beginRc = beginResult.getOrThrow()
                        } else if (retryPreflight is CompletionPreflight.Rejected) {
                            beginRc = NativeRuntimeErrorCodes.LOAD_SIGNATURE_MISMATCH
                        }
                    }
                }
            }
            if (beginRc != 0) {
                if (shouldRefreshMnnAfterRequest) {
                    mnnSessionNeedsReloadBeforeNextRequest = true
                }
                runCatching { runner.requestStop() }
                val nativeError = runCatching {
                    JSONObject(nativeStatsJson()).optString("lastError").takeIf { it.isNotBlank() }
                }.getOrNull()
                val message = buildString {
                    append("Native beginCompletion failed: ").append(beginRc)
                    if (!nativeError.isNullOrBlank()) append("；").append(nativeError.trim())
                }
                val errorStats = errorStatsForRunnerFailure(runner, message)
                _stats.value = errorStats
                emit(GenerateEvent.Error(message, errorStats))
                return@lifecycle
            }

            // Native beginCompletion finishes the complete prompt prefill before
            // decode starts. Publish that terminal prefill evidence immediately;
            // otherwise the UI cannot show prefillMs/prefillTps until the first
            // generated token arrives, which is especially misleading for large
            // GGUF models with a long first-token latency.
            runCatching { JSONObject(nativeStatsJson()) }.getOrNull()?.let { nativeStats ->
                val prefillStats = mergeNativeStats(
                    base = _stats.value,
                    nativeStats = nativeStats,
                    memory = memoryBeforeGenerate,
                    started = started,
                    lastTokenAt = started,
                    request = activeRequest,
                    managedPrefixFailure = managedPersistentPrefixFailure
                ).copy(
                    completionTokens = 0,
                    ttftMs = 0,
                    decodeMs = 0,
                    decodeTps = 0.0,
                    e2eTps = 0.0
                )
                _stats.value = prefillStats
            }

            // Runtimes that expose exact batch progress already emitted PREFILL
            // while native work was in flight. Others remain intentionally
            // indeterminate, but only after a successful final begin.
            if (!exactPrefillPhaseEmitted) {
                emit(GenerateEvent.Phase(GenerationPhase.PREFILL, _stats.value))
            }

            var firstTokenAt = 0L
            var lastTokenAt = started
            var generatedChunks = 0
            var generatedTokens = 0
            var finalStats = _stats.value
            var latestMemory = memoryBeforeGenerate
            var lastStatsSampleAt = 0L
            var lastMemorySampleAt = started
            var cachedNativeStats: JSONObject? = null
            val reasoningFilter = ReasoningContentFilter()
            val reasoningLoopGuard = ReasoningLoopGuard()
            val hideReasoning = request.params.hideReasoning || request.params.reasoningMode == ReasoningMode.OFF
            var reasoningStartedAt = 0L
            var reasoningDurationMs = 0L
            var visibleOutputSeen = false
            var mnnGenerationCompletedNormally = false
            var mnnGenerationWasInterrupted = false
            // A failure thrown by FlowCollector.emit() belongs to the downstream consumer.
            // It must escape unchanged: attempting to emit a second error event from our
            // catch block violates Flow's exception-transparency contract.
            var downstreamEmissionFailed = false

            suspend fun emitGenerated(event: GenerateEvent) {
                try {
                    emit(event)
                } catch (error: Throwable) {
                    downstreamEmissionFailed = true
                    throw error
                }
            }
            var persistPhaseEmitted = false
            suspend fun emitPersistPhase(stats: RuntimeStats) {
                if (!persistPhaseEmitted) {
                    emitGenerated(GenerateEvent.Phase(GenerationPhase.PERSIST, stats))
                    persistPhaseEmitted = true
                }
            }

            try {
                emitGenerated(GenerateEvent.Phase(GenerationPhase.DECODE, _stats.value))
                while (true) {
                    val chunk = withContext(io) { runner.generateNextChunk() } ?: break
                    if (chunk.isBlank()) continue
                    val now = System.currentTimeMillis()
                    if (firstTokenAt == 0L) firstTokenAt = now
                    lastTokenAt = now
                    generatedChunks += 1
                    generatedTokens += estimateTokens(chunk)
                    val shouldSampleStats = cachedNativeStats == null ||
                        now - lastStatsSampleAt >= STATS_SAMPLE_INTERVAL_MS
                    val nativeStats = if (shouldSampleStats) {
                        lastStatsSampleAt = now
                        runCatching { JSONObject(nativeStatsJson()) }.getOrNull()
                            ?.also { cachedNativeStats = it }
                    } else {
                        cachedNativeStats
                    }
                    val nativeCompletionTokens = if (shouldSampleStats) {
                        nativeStats?.optInt("completionTokens")?.takeIf { it > 0 }
                    } else {
                        null
                    }
                    val nativePromptTokens = if (shouldSampleStats) {
                        nativeStats?.optInt("promptTokens")?.takeIf { it > 0 }
                    } else {
                        null
                    }
                    if (nativeCompletionTokens != null) generatedTokens = nativeCompletionTokens
                    val ttft = firstTokenAt - started
                    val decodeMs = if (shouldSampleStats) {
                        nativeStats?.optLong("decodeMs")?.takeIf { it > 0L }
                    } else {
                        null
                    }
                        ?: max(1L, lastTokenAt - firstTokenAt)
                    val totalMs = max(1L, lastTokenAt - started)
                    if (now - lastMemorySampleAt >= MEMORY_SAMPLE_INTERVAL_MS) {
                        latestMemory = telemetry.memorySnapshotDetailed()
                        lastMemorySampleAt = now
                    }
                    val promptTokens = nativePromptTokens ?: estimatePromptTokens(activeRequest)
                    val prefillMs = if (shouldSampleStats) {
                        nativeStats?.optLong("prefillMs") ?: _stats.value.prefillMs
                    } else {
                        _stats.value.prefillMs
                    }
                    val cacheReuse = nativeStats?.optJSONObject("cacheReuse")
                    val gpuEvidence = nativeGpuOffloadEvidence(nativeStats, _stats.value)
                    val prefillTokens = if (shouldSampleStats) {
                        nativeStats?.optInt("prefillTokens", -1)?.takeIf { it >= 0 }
                    } else {
                        null
                    } ?: _stats.value.prefillTokens.takeIf { it > 0 }
                        ?: promptTokens
                    finalStats = _stats.value.copy(
                        promptTokens = promptTokens,
                        completionTokens = generatedTokens,
                        ttftMs = ttft,
                        prefillMs = prefillMs,
                        prefillTokens = prefillTokens,
                        prefillTps = nativeStats?.optDouble("prefillTps")
                            ?.takeIf { it.isFinite() && it >= 0.0 }
                            ?: if (prefillMs > 0L) {
                                prefillTokens * 1000.0 / prefillMs
                            } else 0.0,
                        effectivePromptTps = nativeStats?.optDouble("effectivePromptTps")
                            ?.takeIf { it.isFinite() && it >= 0.0 }
                            ?: if (prefillMs > 0L) promptTokens * 1000.0 / prefillMs else 0.0,
                        decodeMs = decodeMs,
                        decodeTps = if (shouldSampleStats) {
                            nativeStats?.optDouble("decodeTps")?.takeIf { it > 0.0 }
                        } else {
                            null
                        }
                            ?: (generatedTokens * 1000.0 / decodeMs),
                        e2eTps = generatedTokens * 1000.0 / totalMs,
                        nThreads = nativeStats?.optInt("nThreads") ?: _stats.value.nThreads,
                        nThreadsBatch = nativeStats?.optInt("nThreadsBatch") ?: _stats.value.nThreadsBatch,
                        nBatch = nativeStats?.optInt("nBatch") ?: _stats.value.nBatch,
                        nUbatch = nativeStats?.optInt("nUbatch") ?: _stats.value.nUbatch,
                        nCtx = nativeStats?.optInt("nCtx")?.takeIf { it > 0 } ?: _stats.value.nCtx,
                        maxAllTokens = nativeStats?.optInt("maxAllTokens")?.takeIf { it > 0 } ?: _stats.value.maxAllTokens,
                        maxNewTokens = nativeStats?.optInt("maxNewTokens")?.takeIf { it > 0 } ?: _stats.value.maxNewTokens,
                        backendDevices = nativeStats?.optJSONArray("backendDevices")?.toString() ?: _stats.value.backendDevices,
                        gpuOffloadActive = gpuEvidence.active,
                        gpuOffloadAllocationObserved = gpuEvidence.allocationObserved,
                        gpuOffloadExecutionObserved = gpuEvidence.executionObserved,
                        gpuOffloadBytes = gpuEvidence.bytes,
                        gpuOffloadLayers = gpuEvidence.layers,
                        gpuOffloadLayersKnown = gpuEvidence.layersKnown,
                        gpuAutoFallbackApplied = gpuEvidence.autoFallbackApplied,
                        gpuAutoFallbackReason = gpuEvidence.autoFallbackReason,
                        cacheReuseHit = cacheReuse?.optBoolean("hit", _stats.value.cacheReuseHit)
                            ?: _stats.value.cacheReuseHit,
                        cacheReusedTokens = cacheReuse?.optInt("reusedTokens", _stats.value.cacheReusedTokens)
                            ?: _stats.value.cacheReusedTokens,
                        cacheReuseReason = cacheReuse?.optString("reason")
                            ?.takeIf { it.isNotBlank() }
                            ?: _stats.value.cacheReuseReason,
                        cacheReuseHits = cacheReuse?.optLong("hits", _stats.value.cacheReuseHits)
                            ?: _stats.value.cacheReuseHits,
                        cacheReuseMisses = cacheReuse?.optLong("misses", _stats.value.cacheReuseMisses)
                            ?: _stats.value.cacheReuseMisses,
                        lastError = null
                    ).withMemory(latestMemory)
                    if (shouldSampleStats) {
                        _stats.value = finalStats
                    }
                    if (latestMemory.availMemKb in 1 until LOW_MEMORY_RUNTIME_STOP_KB) {
                        val message = "生成过程中可用内存降到 ${formatMb(latestMemory.availMemKb)}，已停止生成以避免系统回收或崩溃。建议降低 n_ctx / n_predict 或关闭后台应用。"
                        mnnGenerationWasInterrupted = shouldRefreshMnnAfterRequest
                        runner.requestStop()
                        val errorStats = finalStats.copy(lastError = message)
                        _stats.value = errorStats
                        emitPersistPhase(errorStats)
                        writeLog(errorStats, activeRequest.params, error = message)
                        emitGenerated(GenerateEvent.Error(message, errorStats))
                        return@lifecycle
                    }
                    val filtered = reasoningFilter.filter(chunk)
                    if (filtered.visible.isNotBlank()) {
                        visibleOutputSeen = true
                    }
                    var stopForReasoningLoop = false
                    if (filtered.reasoning.isNotBlank() && !hideReasoning) {
                        if (reasoningStartedAt == 0L) reasoningStartedAt = now
                        reasoningDurationMs = now - reasoningStartedAt
                        stopForReasoningLoop = reasoningLoopGuard.shouldStop(filtered.reasoning)
                        if (stopForReasoningLoop) {
                            mnnGenerationWasInterrupted = shouldRefreshMnnAfterRequest
                            runner.requestStop()
                        }
                    }
                    if (filtered.visible.isNotBlank() || (filtered.reasoning.isNotBlank() && !hideReasoning)) {
                        emitGenerated(
                            GenerateEvent.Chunk(
                                text = filtered.visible,
                                stats = finalStats,
                                reasoning = if (hideReasoning) "" else filtered.reasoning,
                                reasoningDurationMs = reasoningDurationMs
                            )
                        )
                    } else if (filtered.reasoning.isNotBlank() && hideReasoning && generatedTokens % HIDDEN_REASONING_PROGRESS_STEP_TOKENS == 0) {
                        emitGenerated(
                            GenerateEvent.Chunk(
                                text = "",
                                stats = finalStats,
                                hiddenReasoning = true
                            )
                        )
                    }
                    if (stopForReasoningLoop) break
                }
                val finalNativeStats = runCatching { JSONObject(nativeStatsJson()) }.getOrNull()
                finalStats = mergeNativeStats(
                    base = finalStats,
                    nativeStats = finalNativeStats,
                    memory = telemetry.memorySnapshotDetailed(),
                    started = started,
                    lastTokenAt = lastTokenAt,
                    request = activeRequest,
                    managedPrefixFailure = managedPersistentPrefixFailure
                )
                _stats.value = finalStats
                if (shouldRefreshMnnAfterRequest) {
                    val nativeStopReason = finalNativeStats
                        ?.optString("generationStopReason")
                        .orEmpty()
                    if (nativeStopReason in MNN_UNSAFE_STOP_REASONS) {
                        mnnGenerationWasInterrupted = true
                    }
                }
                val remaining = reasoningFilter.finish()
                if (remaining.reasoning.isNotBlank() && !hideReasoning) {
                    val now = System.currentTimeMillis()
                    if (reasoningStartedAt == 0L) reasoningStartedAt = now
                    reasoningDurationMs = now - reasoningStartedAt
                }
                if (remaining.visible.isNotBlank()) {
                    visibleOutputSeen = true
                }
                if (remaining.visible.isNotBlank() || (remaining.reasoning.isNotBlank() && !hideReasoning)) {
                    emitGenerated(
                        GenerateEvent.Chunk(
                            text = remaining.visible,
                            stats = finalStats,
                            reasoning = if (hideReasoning) "" else remaining.reasoning,
                            reasoningDurationMs = reasoningDurationMs
                        )
                    )
                }
                if (!visibleOutputSeen) {
                    val message = "本地模型本轮没有生成可见正文。请重试；若持续发生，请降低上下文或更换模型。"
                    val errorStats = finalStats.copy(lastError = message)
                    _stats.value = errorStats
                    emitPersistPhase(errorStats)
                    writeLog(errorStats, activeRequest.params, error = message)
                    emitGenerated(GenerateEvent.Error(message, errorStats))
                    return@lifecycle
                }
                emitPersistPhase(finalStats)
                writeLog(finalStats, activeRequest.params, error = null)
                if (activeRuntime == LocalChatRuntime.GENIEX_QAIRT) {
                    qairtDryRunWitness?.sawVisibleCompletion = true
                }
                pendingRollbackContext?.let { transaction ->
                    if (executionContext.pendingProfileDisposition ==
                        PendingProfileDisposition.COMMIT_ON_VISIBLE_SUCCESS
                    ) {
                        val committed = parameterCoordinator.commitAuthorizedPending(transaction.authorization)
                        adoptCommittedProfileSessionLocked(committed)
                        pendingTransactionCommitted = true
                    } else if (executionContext.pendingProfileDisposition ==
                        PendingProfileDisposition.DEFER_TO_LEASE_HOLDER
                    ) {
                        val lease = executionContext.lifecycleLease
                            ?: error("deferred pending disposition requires an exclusive lifecycle lease")
                        synchronized(lease) {
                            require(lease.deferredAuthorization == null ||
                                lease.deferredAuthorization === transaction.authorization) {
                                "lifecycle lease already owns another deferred transaction"
                            }
                            lease.deferredAuthorization = transaction.authorization
                        }
                        pendingTransactionDeferred = true
                    }
                }
                mnnGenerationCompletedNormally = !mnnGenerationWasInterrupted
                emitGenerated(GenerateEvent.Done(finalStats))
            } catch (t: Throwable) {
                // Never let cleanup hide a downstream/cancellation exception.
                runCatching { runner.requestStop() }
                if (t is CancellationException || downstreamEmissionFailed) throw t
                val message = t.message ?: "Generation failed."
                val errorStats = errorStatsForRunnerFailure(runner, message)
                _stats.value = errorStats
                emitPersistPhase(errorStats)
                writeLog(errorStats, activeRequest.params, error = message)
                emit(GenerateEvent.Error(message, errorStats))
            } finally {
                activePersistentPrefix?.let { pending ->
                    withContext(NonCancellable + io) {
                        discardPersistentPrefix(pending)
                        if (activePersistentPrefix === pending) activePersistentPrefix = null
                    }
                }
                // Native beginCompletion() calls reset() for every request. That reset only
                // covers the base LLM context, however; MNN Omni keeps visual state in its
                // subclass. Recreate an MNN session before the next request after every
                // image turn so image embeddings/counters cannot leak into a later turn.
                // Most text-only successful turns remain reusable. MNN 3.5 Gemma 4
                // text-isolation bundles are the narrow exception: under the MNN 3.6
                // loader, their first reset can emit EOP as the entire next answer.
                // Those bundles have no legacy visual graph and have proven safe to
                // reconstruct, so refresh them before the next request.
                val mustRefreshMnnForVisualState = hasImageAttachments
                val mustRefreshMnnForTextCompatibility =
                    !hasImageAttachments &&
                        activeLoadSession?.let { session ->
                            MnnSessionLifecyclePolicy
                                .requiresFreshSessionAfterSuccessfulTextTurn(session.modelPath)
                        } == true
                if (shouldRefreshMnnAfterRequest &&
                    (!mnnGenerationCompletedNormally ||
                        mustRefreshMnnForVisualState ||
                        mustRefreshMnnForTextCompatibility)
                ) {
                    mnnSessionNeedsReloadBeforeNextRequest = true
                }
            }
            } finally {
                clearGenerationStopTarget(generationStopToken)
            }
            }
        } finally {
            try {
                val rollback = pendingRollbackContext
                if (rollback != null && !pendingTransactionCommitted && !pendingTransactionDeferred) {
                    withContext(NonCancellable) {
                        withLifecycleLock(executionContext.lifecycleLease) {
                            val rollbackError = rollbackAuthorizedPendingLocked(rollback)
                            if (rollbackError != null) {
                                parameterCoordinator.abortAuthorizedPending(rollback.authorization)
                                parameterCoordinator.markUnloaded()
                                activeLoadSession = null
                                _stats.value = _stats.value.copy(
                                    loaded = false,
                                    lastError = "Pending profile rollback failed: $rollbackError"
                                )
                            }
                        }
                    }
                }
            } finally {
                parameterCoordinator.finishRequest(executionContext.requestId)
            }
        }
    }

    suspend fun stopGeneration() = withContext(io) {
        runCatching { runnerFor(activeRuntime).requestStop() }
    }

    /** Returns the exact request currently owning native prefill/decode, if any. */
    fun activeGenerationStopToken(): GenerationStopToken? = synchronized(generationStopGate) {
        activeGenerationStopTarget?.token
    }

    /**
     * Stops only [expected]. The default token is evaluated by the caller before dispatcher
     * suspension, so a delayed background stop can never target a replacement request. The
     * engine-level ownership proof also covers native prefill, where runner-level active flags
     * may not become visible until beginCompletion returns.
     */
    suspend fun stopGenerationIfActive(
        expected: GenerationStopToken? = activeGenerationStopToken()
    ): Boolean {
        // A missing captured owner is a definitive no-op. Avoiding dispatcher suspension here
        // lets lifecycle callers cancel pre-native work immediately.
        if (expected == null) return false
        return withContext(io) {
            synchronized(generationStopGate) {
                val target = activeGenerationStopTarget
                if (target?.token != expected) {
                    false
                } else {
                    runCatching {
                        target.runner.requestStop()
                        true
                    }.getOrDefault(false)
                }
            }
        }
    }

    /** Releases every configured native runner, including inactive QAIRT sessions retained by callers. */
    suspend fun shutdown() = withContext(io) {
        // Interrupt an active stream before waiting for streamChat's mutex ownership.
        runCatching { runnerFor(activeRuntime).requestStop() }
        mutex.withLock {
            runners.values.distinct().forEach { runner ->
                runCatching { runner.requestStop() }
                runCatching { runner.shutdown() }
            }
            parameterCoordinator.markUnloaded()
            activeLoadSession = null
            qairtDryRunWitness = null
            lastQairtExecutionAdmission = null
            mnnSessionNeedsReloadBeforeNextRequest = false
            _stats.value = RuntimeStats(loaded = false, backend = activeRuntime.backendId)
        }
    }

    /**
     * Reads only process-local runner state when no load, unload, generation, or tuning lease owns
     * the lifecycle. This must never make a Binder/JNI call: a damaged native stats path cannot be
     * allowed to retain the engine lifecycle mutex.
     */
    suspend fun tryRuntimeHealthSnapshot(): RuntimeHealthSnapshot? = withContext(io) {
        if (!mutex.tryLock()) return@withContext null
        try {
            var runtimeStats = _stats.value
            val workerSessionLost = runnerFor(activeRuntime).isSessionKnownLost()
            if (workerSessionLost) {
                isolatedWorkerSessionNeedsReload = activeLoadSession != null
                parameterCoordinator.markUnloaded()
                runtimeStats = runtimeStats.copy(
                    loaded = false,
                    lastError = runtimeStats.lastError
                        ?: "The isolated local text worker session was lost; the model will reload on the next request."
                )
                _stats.value = runtimeStats
            }
            RuntimeHealthSnapshot(
                runtimeStats = runtimeStats,
                workerSessionLost = workerSessionLost
            )
        } finally {
            mutex.unlock()
        }
    }

    fun nativeStatsJson(): String = runCatching { runnerFor(activeRuntime).getRuntimeStatsJson() }.getOrElse {
        JSONObject().put("error", it.message).toString()
    }

    fun recentLogs(limit: Int = 200): List<RuntimeMetrics> = telemetry.recent(limit)

    private fun dispatchVisionDiagnostic(
        executionContext: LocalChatExecutionContext,
        stage: String,
        details: JSONObject
    ) {
        val snapshot = runCatching { details.toString() }.getOrDefault("{}")
        runCatching { LocalChatRunnerDebug.emit(stage, JSONObject(snapshot)) }
        executionContext.visionDiagnosticSink?.let { sink ->
            val redacted = JSONObject().apply {
                details.optString("status")
                    .trim()
                    .lowercase()
                    .takeIf(VISION_DIAGNOSTIC_TOKEN_PATTERN::matches)
                    ?.let { put("status", it) }
                details.optString("preprocessing")
                    .trim()
                    .lowercase()
                    .takeIf(VISION_DIAGNOSTIC_TOKEN_PATTERN::matches)
                    ?.let { put("preprocessing", it) }
                details.optString("inputSha256")
                    .trim()
                    .lowercase()
                    .takeIf(VISION_DIAGNOSTIC_SHA256_PATTERN::matches)
                    ?.let { put("inputSha256", it) }
            }
            runCatching { sink(stage, redacted) }
        }
    }

    private fun writeLog(stats: RuntimeStats, params: GenerationParams, error: String?) {
        telemetry.append(
            RuntimeMetrics(
                model = stats.modelPath.orEmpty(),
                backend = stats.backend,
                soc = socInfo.family.name.lowercase(),
                promptTokens = stats.promptTokens,
                genTokens = stats.completionTokens,
                loadMs = stats.loadMs,
                ttftMs = stats.ttftMs,
                prefillMs = stats.prefillMs,
                decodeMs = stats.decodeMs,
                decodeTps = stats.decodeTps,
                e2eTps = stats.e2eTps,
                nativePssKb = stats.nativePssKb,
                processRssKb = stats.processRssKb,
                nativeHeapKb = stats.nativeHeapKb,
                nativeHeapSizeKb = stats.nativeHeapSizeKb,
                javaHeapKb = stats.javaHeapKb,
                availMemKb = stats.availMemKb,
                totalMemKb = stats.totalMemKb,
                advertisedMemKb = stats.advertisedMemKb,
                memoryThresholdKb = stats.memoryThresholdKb,
                isLowMemory = stats.isLowMemory,
                procMemAvailableKb = stats.procMemAvailableKb,
                procMemFreeKb = stats.procMemFreeKb,
                cachedKb = stats.cachedKb,
                reclaimableKb = stats.reclaimableKb,
                modelMemoryBudgetKb = stats.modelMemoryBudgetKb,
                params = params.toJson(),
                error = error
            )
        )
    }

    private fun estimateTokens(text: String): Int = estimateLocalPromptTokens(text)

    private fun estimatePromptTokens(request: ChatRequest): Int {
        val estimated = request.messages.sumOf { estimateTokens(it.content).toLong() } +
            estimateTokens(request.params.systemPrompt).toLong() +
            estimateTokens(request.runtimeSystemContext).toLong() +
            REASONING_INSTRUCTION_ESTIMATE_TOKENS.toLong()
        return estimated.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun ChatRequest.hasImageAttachments(): Boolean =
        messages.any { it.imageAttachments.isNotEmpty() }

    private fun localVisionReady(): Boolean =
        runCatching { JSONObject(nativeStatsJson()).optBoolean("visionReady", false) }
            .getOrDefault(false)

    private fun runnerFor(runtime: LocalChatRuntime): LocalChatRunner =
        runners[runtime] ?: error("本地推理后端未注册：${runtime.label}")

    private fun activateGenerationStopTarget(
        requestId: String,
        runner: LocalChatRunner
    ): GenerationStopToken = synchronized(generationStopGate) {
        check(activeGenerationStopTarget == null) {
            "another native generation already owns the stop target"
        }
        generationStopEpoch += 1L
        GenerationStopToken(generationStopEpoch, requestId).also { token ->
            activeGenerationStopTarget = ActiveGenerationStopTarget(token, runner)
        }
    }

    private fun clearGenerationStopTarget(token: GenerationStopToken) {
        synchronized(generationStopGate) {
            if (activeGenerationStopTarget?.token == token) {
                activeGenerationStopTarget = null
            }
        }
    }

    private suspend fun <T> withLifecycleLock(
        lease: EngineLifecycleLease?,
        block: suspend () -> T
    ): T {
        if (lease == null) return mutex.withLock { block() }
        require(lease.service === this && lease.active && mutex.holdsLock(lease.ownerToken)) {
            "lifecycle lease is invalid, released, or belongs to another engine"
        }
        return block()
    }

    private fun loadParamsForProfile(
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

    /** Called only while [mutex] is held. */
    private fun validateExecutionProfilePathLocked(
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
    private fun adoptEquivalentLoadedProfileLocked(profile: ModelExecutionProfile) {
        val session = activeLoadSession ?: error("no loaded session is available for profile adoption")
        require(session.runtimeIdentity.identityHash == profile.runtimeIdentity.identityHash &&
            session.executionProfile.resolvedLoadSignature.digest == profile.resolvedLoadSignature.digest &&
            session.executionProfile.committedExecutionSignature.digest == profile.committedExecutionSignature.digest
        ) { "persisted profile is not equivalent to the active native session" }
        parameterCoordinator.commit(profile)
        activeLoadSession = session.copy(
            runtimeIdentity = profile.runtimeIdentity,
            executionProfile = profile,
            nativeLoadParamsJson = parameterCoordinator.nativeLoadJson(profile),
            params = loadParamsForProfile(session.params, profile)
        )
    }

    /** Called after ParameterCoordinator has atomically committed [profile]. */
    private fun adoptCommittedProfileSessionLocked(profile: ModelExecutionProfile) {
        val session = activeLoadSession ?: error("committed profile has no active native session")
        require(session.runtimeIdentity.identityHash == profile.runtimeIdentity.identityHash) {
            "committed profile identity does not match the active native session"
        }
        activeLoadSession = session.copy(
            params = loadParamsForProfile(session.params, profile),
            runtimeIdentity = profile.runtimeIdentity,
            executionProfile = profile,
            nativeLoadParamsJson = parameterCoordinator.nativeLoadJson(profile)
        )
    }

    /**
     * Reuse only an exact, healthy MNN load. Deliberately do not generalise this
     * to QAIRT: its bundle verification/admission and handle state are model
     * specific and must still run through a fresh load boundary when requested.
     */
    private fun reusableMnnLoadedStats(
        modelPath: String,
        runtime: LocalChatRuntime,
        profile: ModelExecutionProfile
    ): RuntimeStats? {
        if (runtime != LocalChatRuntime.MNN_CPU || activeRuntime != LocalChatRuntime.MNN_CPU) return null
        if (mnnSessionNeedsReloadBeforeNextRequest || !_stats.value.loaded) return null
        val session = activeLoadSession ?: return null
        if (session.runtime != LocalChatRuntime.MNN_CPU) return null
        if (session.runtimeIdentity.identityHash != profile.runtimeIdentity.identityHash) return null
        if (session.executionProfile.resolvedLoadSignature.digest != profile.resolvedLoadSignature.digest) return null
        if (session.executionProfile.committedExecutionSignature.digest != profile.committedExecutionSignature.digest) return null
        if (!sameModelLocation(session.modelPath, modelPath)) return null
        return _stats.value
    }

    private fun sameModelLocation(first: String, second: String): Boolean =
        runCatching { File(first).canonicalPath == File(second).canonicalPath }
            .getOrDefault(first == second)

    private suspend fun isolatedWorkerSessionLostLocked(): Boolean = withContext(io) {
        if (activeRuntime != LocalChatRuntime.LLAMA_CPP &&
            activeRuntime != LocalChatRuntime.MNN_CPU &&
            activeRuntime != LocalChatRuntime.GENIEX_QAIRT
        ) return@withContext false
        runCatching {
            JSONObject(runnerFor(activeRuntime).getRuntimeStatsJson())
                .optBoolean(ISOLATED_WORKER_SESSION_LOST_FIELD, false)
        }.getOrDefault(false)
    }

    /** Keeps the load descriptor for recovery while removing stale READY state. */
    private fun errorStatsForRunnerFailure(
        runner: LocalChatRunner,
        message: String
    ): RuntimeStats {
        if (!runner.isSessionKnownLost()) return _stats.value.copy(lastError = message)
        isolatedWorkerSessionNeedsReload = activeLoadSession != null
        parameterCoordinator.markUnloaded()
        return _stats.value.copy(loaded = false, lastError = message)
    }

    private suspend fun reloadActiveSessionForNextRequestLocked(
        reason: SessionReloadReason
    ): String? {
        val session = activeLoadSession
            ?: return reason.missingSessionMessage
        if (session.runtime != LocalChatRuntime.LLAMA_CPP &&
            session.runtime != LocalChatRuntime.MNN_CPU &&
            session.runtime != LocalChatRuntime.GENIEX_QAIRT
        ) {
            return "当前运行时不支持隔离文本 worker 会话恢复，请重新加载模型。"
        }
        val runner = runnerFor(session.runtime)
        if (!runner.isAvailable) {
            return unavailableStats(session.runtime, runner.loadError).optString("lastError")
        }
        withContext(io) {
            runCatching { runner.requestStop() }
            runCatching { runner.unloadModel() }
        }
        parameterCoordinator.markUnloaded()
        _stats.value = RuntimeStats(
            loaded = false,
            modelPath = session.modelPath,
            backend = session.runtime.backendId,
            nThreads = session.params.nThreads,
            nCtx = session.params.nCtx,
            maxAllTokens = session.params.nCtx,
            lastError = reason.reloadingStatus
        )
        suspend fun finishThrownReload(error: Throwable): String {
            withContext(NonCancellable + io) {
                runCatching { runner.requestStop() }
                runCatching { runner.unloadModel() }
            }
            parameterCoordinator.markUnloaded()
            if (reason != SessionReloadReason.WORKER_SESSION_LOST) {
                activeLoadSession = null
            }
            isolatedWorkerSessionNeedsReload = reason == SessionReloadReason.WORKER_SESSION_LOST
            mnnSessionNeedsReloadBeforeNextRequest = false
            val message = buildString {
                append(reason.loadFailurePrefix)
                append(error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName)
            }
            _stats.value = RuntimeStats(
                loaded = false,
                modelPath = session.modelPath,
                backend = session.runtime.backendId,
                nThreads = session.params.nThreads,
                nCtx = session.params.nCtx,
                maxAllTokens = session.params.nCtx,
                lastError = message
            )
            return message
        }

        val started = System.currentTimeMillis()
        val rc = try {
            withContext(io) { runner.loadModel(session.modelPath, session.nativeLoadParamsJson) }
        } catch (error: Throwable) {
            val message = finishThrownReload(error)
            if (error is CancellationException) throw error
            return message
        }
        if (rc != 0) {
            val nativeError = withContext(io) {
                runCatching {
                    JSONObject(runner.getRuntimeStatsJson())
                        .optString("lastError")
                        .takeIf { it.isNotBlank() }
                }.getOrNull()
            }
            val message = buildString {
                append(reason.loadFailurePrefix).append(rc)
                if (!nativeError.isNullOrBlank()) append("；").append(nativeError.trim())
            }
            activeLoadSession = null
            mnnSessionNeedsReloadBeforeNextRequest = false
            isolatedWorkerSessionNeedsReload = false
            withContext(NonCancellable + io) {
                runCatching { runner.requestStop() }
                runCatching { runner.unloadModel() }
            }
            _stats.value = RuntimeStats(
                loaded = false,
                modelPath = session.modelPath,
                backend = session.runtime.backendId,
                nThreads = session.params.nThreads,
                nCtx = session.params.nCtx,
                maxAllTokens = session.params.nCtx,
                lastError = message
            )
            return message
        }
        mnnSessionNeedsReloadBeforeNextRequest = false
        isolatedWorkerSessionNeedsReload = false
        val nativeStats = try {
            withContext(io) { runner.getRuntimeStatsJson() }
        } catch (error: Throwable) {
            val message = finishThrownReload(error)
            if (error is CancellationException) throw error
            return message
        }
        runCatching {
            parameterCoordinator.publishLoaded(session.executionProfile, nativeStats)
        }.getOrElse { signatureError ->
            activeLoadSession = null
            withContext(NonCancellable + io) {
                runCatching { runner.requestStop() }
                runCatching { runner.unloadModel() }
            }
            parameterCoordinator.markUnloaded()
            return "${reason.signatureFailurePrefix}${signatureError.message}"
        }
        _stats.value = withContext(io) {
            loadedStatsFromNative(
                modelPath = session.modelPath,
                runtime = session.runtime,
                params = session.params,
                loadMs = System.currentTimeMillis() - started
            )
        }
        return null
    }

    /** Called only while [mutex] is held; never re-enters public loadModel(). */
    private suspend fun reloadCoordinatedProfileLocked(
        session: LoadedModelSession,
        target: ModelExecutionProfile,
        authorization: LoadAuthorization?,
        commitAfterLoad: Boolean
    ): String? {
        if (session.runtimeIdentity.identityHash != target.runtimeIdentity.identityHash) {
            return "受控重载目标与当前模型身份不一致，请通过模型切换流程重新加载。"
        }
        val runner = runnerFor(session.runtime)
        if (!runner.isAvailable) {
            return unavailableStats(session.runtime, runner.loadError).optString("lastError")
        }
        if (authorization == null) {
            // A committed/drift recovery is an ordinary lifecycle boundary;
            // discard any stale pending transaction before publication.
            parameterCoordinator.prepareOrdinaryLoad(target)
        }
        val nativeLoadJson = parameterCoordinator.nativeLoadJson(target)
        runCatching { runner.requestStop() }
        runCatching { runner.unloadModel() }
        parameterCoordinator.markUnloaded()
        _stats.value = RuntimeStats(
            loaded = false,
            modelPath = session.modelPath,
            backend = session.runtime.backendId,
            nThreads = (target.hotExecutionValues.value("n_threads") as? Number)?.toInt()
                ?: session.params.nThreads,
            nCtx = (target.resolvedLoadBoundValues.value("n_ctx") as? Number)?.toInt()
                ?: session.params.nCtx,
            lastError = "Reloading verified model execution profile."
        )
        val started = System.currentTimeMillis()
        val rc = withContext(io) { runner.loadModel(session.modelPath, nativeLoadJson) }
        if (rc != 0) {
            val nativeError = runCatching {
                JSONObject(nativeStatsJson()).optString("lastError").takeIf { it.isNotBlank() }
            }.getOrNull()
            activeLoadSession = null
            val message = buildString {
                append("受控模型重载失败：").append(rc)
                if (!nativeError.isNullOrBlank()) append("；").append(nativeError)
            }
            _stats.value = _stats.value.copy(loaded = false, lastError = message)
            return message
        }
        if (session.runtime == LocalChatRuntime.GENIEX_QAIRT) {
            val nativeStats = runCatching { JSONObject(nativeStatsJson()) }.getOrNull()
            if (nativeStats == null || !hasQairtNpuExecutionEvidence(nativeStats)) {
                runCatching { runner.requestStop() }
                runCatching { runner.unloadModel() }
                parameterCoordinator.markUnloaded()
                activeLoadSession = null
                val message = "QAIRT 受控重载未取得 NPU 执行证据，拒绝发布 execution profile。"
                _stats.value = _stats.value.copy(loaded = false, lastError = message)
                return message
            }
        }
        runCatching {
            parameterCoordinator.publishLoaded(target, nativeStatsJson(), authorization)
            if (commitAfterLoad) parameterCoordinator.commit(target)
        }.getOrElse { signatureError ->
            runCatching { runner.requestStop() }
            runCatching { runner.unloadModel() }
            parameterCoordinator.markUnloaded()
            activeLoadSession = null
            val message = "受控重载后的参数回读不一致：${signatureError.message}"
            _stats.value = _stats.value.copy(loaded = false, lastError = message)
            return message
        }
        val resolvedParams = session.params.copy(
            nCtx = (target.resolvedLoadBoundValues.value("n_ctx") as? Number)?.toInt()
                ?: session.params.nCtx,
            nThreads = (target.hotExecutionValues.value("n_threads") as? Number)?.toInt()
                ?: session.params.nThreads,
            mmap = target.resolvedLoadBoundValues.value("mmap") as? Boolean ?: session.params.mmap,
            mlock = target.resolvedLoadBoundValues.value("mlock") as? Boolean ?: session.params.mlock
        )
        activeLoadSession = LoadedModelSession(
            modelPath = session.modelPath,
            runtime = session.runtime,
            params = resolvedParams,
            runtimeIdentity = target.runtimeIdentity,
            executionProfile = target,
            nativeLoadParamsJson = nativeLoadJson
        )
        mnnSessionNeedsReloadBeforeNextRequest = false
        _stats.value = loadedStatsFromNative(
            modelPath = session.modelPath,
            runtime = session.runtime,
            params = resolvedParams,
            loadMs = System.currentTimeMillis() - started
        )
        return null
    }

    /** Called only while [mutex] is held. A failed transaction is restored once. */
    private suspend fun rollbackAuthorizedPendingLocked(
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

    private fun loadedStatsFromNative(
        modelPath: String,
        runtime: LocalChatRuntime,
        params: LoadParams,
        loadMs: Long
    ): RuntimeStats {
        val memory = telemetry.memorySnapshotDetailed()
        val nativeStats = runCatching { JSONObject(nativeStatsJson()) }.getOrNull()
        val cacheReuse = nativeStats?.optJSONObject("cacheReuse")
        val gpuEvidence = nativeGpuOffloadEvidence(nativeStats, RuntimeStats())
        return RuntimeStats(
            loaded = true,
            modelPath = modelPath,
            backend = nativeStats?.optString("backend")?.takeIf { it.isNotBlank() } ?: runtime.backendId,
            loadMs = loadMs,
            promptTokens = nativeStats?.optInt("promptTokens") ?: 0,
            // Older workers do not publish prefillTokens. Keep that absence
            // distinguishable from a real zero-token prefill so the caller
            // can fall back to its prompt estimate.
            prefillTokens = nativeStats?.optInt("prefillTokens", -1)
                ?.takeIf { it >= 0 }
                ?: 0,
            prefillMs = nativeStats?.optLong("prefillMs") ?: 0L,
            prefillTps = nativeStats?.optDouble("prefillTps")
                ?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
            effectivePromptTps = nativeStats?.optDouble("effectivePromptTps")
                ?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
            nThreads = nativeStats?.optInt("nThreads") ?: params.nThreads,
            nThreadsBatch = nativeStats?.optInt("nThreadsBatch") ?: params.nThreads,
            nBatch = nativeStats?.optInt("nBatch") ?: 0,
            nUbatch = nativeStats?.optInt("nUbatch") ?: 0,
            nCtx = nativeStats?.optInt("nCtx")?.takeIf { it > 0 } ?: params.nCtx,
            maxAllTokens = nativeStats?.optInt("maxAllTokens")?.takeIf { it > 0 } ?: params.nCtx,
            maxNewTokens = nativeStats?.optInt("maxNewTokens")?.takeIf { it > 0 } ?: 0,
            backendDevices = nativeStats?.optJSONArray("backendDevices")?.toString() ?: "[]",
            gpuOffloadActive = gpuEvidence.active,
            gpuOffloadAllocationObserved = gpuEvidence.allocationObserved,
            gpuOffloadExecutionObserved = gpuEvidence.executionObserved,
            gpuOffloadBytes = gpuEvidence.bytes,
            gpuOffloadLayers = gpuEvidence.layers,
            gpuOffloadLayersKnown = gpuEvidence.layersKnown,
            gpuAutoFallbackApplied = gpuEvidence.autoFallbackApplied,
            gpuAutoFallbackReason = gpuEvidence.autoFallbackReason,
            cacheReuseHit = cacheReuse?.optBoolean("hit", false) ?: false,
            cacheReusedTokens = cacheReuse?.optInt("reusedTokens", 0) ?: 0,
            cacheReuseReason = cacheReuse?.optString("reason")?.takeIf { it.isNotBlank() },
            cacheReuseHits = cacheReuse?.optLong("hits", 0L) ?: 0L,
            cacheReuseMisses = cacheReuse?.optLong("misses", 0L) ?: 0L,
            persistentPrefixCacheHit = nativeStats
                ?.optJSONObject("persistentPrefixCache")
                ?.optBoolean("hit", false) == true,
            persistentPrefixCacheTokens = nativeStats
                ?.optJSONObject("persistentPrefixCache")
                ?.optInt("tokens", 0)
                ?: 0,
            persistentPrefixCacheReason = nativeStats
                ?.optJSONObject("persistentPrefixCache")
                ?.optString("reason")
                ?.takeIf { it.isNotBlank() }
        ).withMemory(memory)
    }

    private fun qairtExecutionAdmissionForLoad(
        modelPath: String,
        runtime: LocalChatRuntime,
        memory: MemorySnapshot,
        bundleSha256: String?
    ): QairtExecutionAdmission? {
        if (runtime != LocalChatRuntime.GENIEX_QAIRT) return null
        val totalRamBytes = memory.totalMemKb.takeIf { it > 0L }
            ?.times(1024L)
            ?: memory.advertisedMemKb.takeIf { it > 0L }?.times(1024L)
            ?: 0L
        val availableRamBytes = memory.availMemKb.coerceAtLeast(0L).times(1024L)
        val observedIdentity = qairtIdentityProvider(bundleSha256)
        return QairtBundleRiskAnalyzer.analyze(File(modelPath))
            .admissionForDeviceMemory(
                totalRamBytes = totalRamBytes,
                availableRamBytes = availableRamBytes,
                observedIdentity = observedIdentity,
                verifiedIdentities = qairtVerificationStore.verifiedIdentities()
            )
    }

    /**
     * Isolated QAIRT smoke is diagnostic-only. Normal QAIRT loads use the
     * generic isolated text worker, so device verification never gates a
     * user-facing load or run attempt.
     */
    private fun requireQairtExecutionPurpose(
        runtime: LocalChatRuntime,
        purpose: QairtExecutionPurpose
    ) {
        if (runtime != LocalChatRuntime.GENIEX_QAIRT) {
            require(purpose == QairtExecutionPurpose.NORMAL) {
                "Only QAIRT may use the isolated dry-run execution purpose."
            }
            return
        }
        if (purpose == QairtExecutionPurpose.ISOLATED_DRY_RUN) {
            check(qairtDryRunProcessVerifier()) {
                "QAIRT 隔离安全启动只能在 :qairt_smoke 独立进程中运行。"
            }
        }
    }

    private fun hasQairtNpuExecutionEvidence(nativeStats: JSONObject): Boolean {
        if (!nativeStats.optString("backend").equals(LocalChatRuntime.GENIEX_QAIRT.backendId, ignoreCase = true)) {
            return false
        }
        val devices = nativeStats.opt("backendDevices")?.toString().orEmpty().lowercase()
        return "qairt" in devices && ("npu" in devices || "htp" in devices)
    }

    private fun formatMb(kb: Long): String = "%.0f MB".format(kb / 1024.0)

    private fun mergeNativeStats(
        base: RuntimeStats,
        nativeStats: JSONObject?,
        memory: MemorySnapshot,
        started: Long,
        lastTokenAt: Long,
        request: ChatRequest,
        managedPrefixFailure: ManagedPersistentPrefixFailure? = null
    ): RuntimeStats {
        val completionTokens = nativeStats?.optInt("completionTokens")?.takeIf { it > 0 }
            ?: base.completionTokens
        val decodeMs = nativeStats?.optLong("decodeMs")?.takeIf { it > 0L }
            ?: base.decodeMs.takeIf { it > 0L }
            ?: max(1L, lastTokenAt - started)
        val promptTokens = nativeStats?.optInt("promptTokens")?.takeIf { it > 0 }
            ?: base.promptTokens.takeIf { it > 0 }
            ?: estimatePromptTokens(request)
        val prefillTokens = nativeStats?.optInt("prefillTokens", -1)?.takeIf { it >= 0 }
            ?: base.prefillTokens.takeIf { it > 0 }
            ?: promptTokens
        val prefillMs = nativeStats?.optLong("prefillMs") ?: base.prefillMs
        val cacheReuse = nativeStats?.optJSONObject("cacheReuse")
        val gpuEvidence = nativeGpuOffloadEvidence(nativeStats, base)
        val totalMs = max(1L, lastTokenAt - started)
        return base.copy(
            backend = nativeStats?.optString("backend")?.takeIf { it.isNotBlank() } ?: base.backend,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            prefillMs = prefillMs,
            prefillTokens = prefillTokens,
            prefillTps = nativeStats?.optDouble("prefillTps")
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?: if (prefillMs > 0L) prefillTokens * 1000.0 / prefillMs else 0.0,
            effectivePromptTps = nativeStats?.optDouble("effectivePromptTps")
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?: if (prefillMs > 0L) promptTokens * 1000.0 / prefillMs else 0.0,
            decodeMs = decodeMs,
            decodeTps = nativeStats?.optDouble("decodeTps")?.takeIf { it > 0.0 }
                ?: (completionTokens * 1000.0 / decodeMs),
            e2eTps = completionTokens * 1000.0 / totalMs,
            nThreads = nativeStats?.optInt("nThreads") ?: base.nThreads,
            nThreadsBatch = nativeStats?.optInt("nThreadsBatch") ?: base.nThreadsBatch,
            nBatch = nativeStats?.optInt("nBatch") ?: base.nBatch,
            nUbatch = nativeStats?.optInt("nUbatch") ?: base.nUbatch,
            nCtx = nativeStats?.optInt("nCtx")?.takeIf { it > 0 } ?: base.nCtx,
            maxAllTokens = nativeStats?.optInt("maxAllTokens")?.takeIf { it > 0 } ?: base.maxAllTokens,
            maxNewTokens = nativeStats?.optInt("maxNewTokens")?.takeIf { it > 0 } ?: base.maxNewTokens,
            backendDevices = nativeStats?.optJSONArray("backendDevices")?.toString() ?: base.backendDevices,
            gpuOffloadActive = gpuEvidence.active,
            gpuOffloadAllocationObserved = gpuEvidence.allocationObserved,
            gpuOffloadExecutionObserved = gpuEvidence.executionObserved,
            gpuOffloadBytes = gpuEvidence.bytes,
            gpuOffloadLayers = gpuEvidence.layers,
            gpuOffloadLayersKnown = gpuEvidence.layersKnown,
            gpuAutoFallbackApplied = gpuEvidence.autoFallbackApplied,
            gpuAutoFallbackReason = gpuEvidence.autoFallbackReason,
            cacheReuseHit = cacheReuse?.optBoolean("hit", base.cacheReuseHit) ?: base.cacheReuseHit,
            cacheReusedTokens = cacheReuse?.optInt("reusedTokens", base.cacheReusedTokens)
                ?: base.cacheReusedTokens,
            cacheReuseReason = cacheReuse?.optString("reason")?.takeIf { it.isNotBlank() }
                ?: base.cacheReuseReason,
            cacheReuseHits = cacheReuse?.optLong("hits", base.cacheReuseHits) ?: base.cacheReuseHits,
            cacheReuseMisses = cacheReuse?.optLong("misses", base.cacheReuseMisses)
                ?: base.cacheReuseMisses,
            persistentPrefixCacheHit = if (managedPrefixFailure != null) {
                false
            } else {
                nativeStats
                    ?.optJSONObject("persistentPrefixCache")
                    ?.optBoolean("hit", false)
                    ?: base.persistentPrefixCacheHit
            },
            persistentPrefixCacheTokens = managedPrefixFailure?.tokens
                ?: nativeStats
                    ?.optJSONObject("persistentPrefixCache")
                    ?.optInt("tokens", 0)
                ?: base.persistentPrefixCacheTokens,
            persistentPrefixCacheReason = managedPrefixFailure?.reason
                ?: nativeStats
                    ?.optJSONObject("persistentPrefixCache")
                    ?.optString("reason")
                    ?.takeIf { it.isNotBlank() }
                ?: base.persistentPrefixCacheReason,
            lastError = null
        ).withMemory(memory)
    }

    private fun RuntimeStats.withMemory(memory: MemorySnapshot): RuntimeStats = copy(
        nativePssKb = memory.processPssKb,
        processRssKb = memory.processRssKb,
        nativeHeapKb = memory.nativeHeapKb,
        nativeHeapSizeKb = memory.nativeHeapSizeKb,
        javaHeapKb = memory.javaHeapKb,
        availMemKb = memory.availMemKb,
        totalMemKb = memory.totalMemKb,
        advertisedMemKb = memory.advertisedMemKb,
        memoryThresholdKb = memory.memoryThresholdKb,
        isLowMemory = memory.isLowMemory,
        procMemAvailableKb = memory.procMemAvailableKb,
        procMemFreeKb = memory.procMemFreeKb,
        cachedKb = memory.cachedKb,
        reclaimableKb = memory.reclaimableKb,
        modelMemoryBudgetKb = memory.modelMemoryBudgetKb
    )

    private fun CompletionPreflight.Rejected.userFacingPresentation(): PreflightRejectionPresentation =
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

    private data class PreflightRejectionPresentation(
        val message: String,
        val action: String
    )

    private data class LoadedModelSession(
        val modelPath: String,
        val runtime: LocalChatRuntime,
        val params: LoadParams,
        val runtimeIdentity: ModelRuntimeIdentity,
        val executionProfile: ModelExecutionProfile,
        val nativeLoadParamsJson: String
    )

    private enum class SessionReloadReason(
        val missingSessionMessage: String,
        val reloadingStatus: String,
        val loadFailurePrefix: String,
        val signatureFailurePrefix: String
    ) {
        MNN_CONTEXT_RESET(
            missingSessionMessage = "MNN 会话需要刷新，但没有可恢复的模型加载记录。请重新加载本地模型后再试。",
            reloadingStatus = "Refreshing MNN session before next request.",
            loadFailurePrefix = "MNN 会话刷新失败：",
            signatureFailurePrefix = "MNN 会话刷新后的参数回读不一致："
        ),
        WORKER_SESSION_LOST(
            missingSessionMessage = "隔离文本 worker 已退出，且没有可恢复的模型加载记录。请重新加载本地模型后再试。",
            reloadingStatus = "Restoring model after isolated text worker loss.",
            loadFailurePrefix = "隔离文本 worker 会话恢复失败：",
            signatureFailurePrefix = "隔离文本 worker 会话恢复后的参数回读不一致："
        )
    }

    private data class PreparedPersistentPrefix(
        val key: PrefixCacheKey,
        val request: PersistentPrefixCacheRequest,
        val pending: PersistentPrefixCacheStore.PendingWrite,
        val existing: PersistentPrefixCacheEntry?
    )

    private data class ManagedPersistentPrefixFailure(
        val reason: String,
        val tokens: Int
    )

    private data class PendingRollbackContext(
        val baseSession: LoadedModelSession,
        val authorization: LoadAuthorization
    )

    private data class QairtDryRunWitness(
        val identity: QairtBundleRuntimeIdentity,
        val sawNpuExecution: Boolean,
        val npuEvidence: String,
        var sawVisibleCompletion: Boolean = false,
        var destroyedCleanly: Boolean = false
    )

    /**
     * Prepares either a stable role prefix entry or a session-scoped llama.cpp
     * state entry. Session state is accepted only after native token-prefix
     * validation, so dynamic world-book/retrieval text cannot be misapplied to
     * a changed conversation.
     */
    private fun preparePersistentPrefix(
        request: ChatRequest,
        preparedParameters: CompletionPreflight.Ready?,
        paramsJson: String,
        hasImageAttachments: Boolean
    ): PreparedPersistentPrefix? {
        if (!persistentPrefixCacheEnabled ||
            activeRuntime != LocalChatRuntime.LLAMA_CPP ||
            hasImageAttachments ||
            preparedParameters == null
        ) return null
        val session = activeLoadSession ?: return null
        val identity = session.runtimeIdentity
        if (!session.params.visionProjectorPath.isNullOrBlank() ||
            identity.capabilities.any { capability ->
                capability.equals("vision", ignoreCase = true) ||
                    capability.equals("multimodal", ignoreCase = true)
            }
        ) return null
        // A process-local identity is deliberately advisory-only for runtime
        // admission, but it is not stable enough for a cross-process cache.
        if (identity.installationScopeId.startsWith("process-local:", ignoreCase = true)) return null

        val fixedSystemPrompt = request.fixedSystemPromptForPrefixCache()
        val sessionId = request.persistentSessionId?.trim()?.takeIf { it.isNotBlank() }
        if (fixedSystemPrompt.isBlank() && sessionId == null) return null
        // Prefix-cache metadata crosses the isolated worker boundary. A cache
        // optimization must never reject an otherwise admissible chat request.
        if (fixedSystemPrompt.toByteArray(Charsets.UTF_8).size >
            MAX_PERSISTENT_PREFIX_CACHE_PROMPT_BYTES
        ) return null

        val root = runCatching { JSONObject(paramsJson) }.getOrNull() ?: return null
        val advanced = when (val raw = root.opt("advanced_json")) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        }
        fun value(name: String): Any? = when {
            root.has(name) && !root.isNull(name) -> root.opt(name)
            advanced?.has(name) == true && !advanced.isNull(name) -> advanced.opt(name)
            else -> null
        }
        val profile = session.executionProfile
        val nParallel = (profile.resolvedLoadBoundValues.value("n_parallel") as? Number)?.toInt()
            ?: (value("n_parallel") as? Number)?.toInt()
            ?: 1
        val specType = profile.resolvedLoadBoundValues.value("spec_type")
            ?.toString()
            ?.trim()
            ?.lowercase()
            ?: value("spec_type")?.toString()?.trim()?.lowercase()
            ?: "none"
        val cacheReuse = (value("cache_reuse") as? Number)?.toInt()
            ?: (profile.hotExecutionValues.value("cache_reuse") as? Number)?.toInt()
            ?: -1
        if (nParallel != 1 || specType != "none" || cacheReuse == 0) return null

        val signatures = preparedParameters.signatures
        val key = persistentPrefixCacheKey(
            identity = identity,
            signatures = signatures,
            nativeParams = root,
            request = request,
            fixedSystemPrompt = fixedSystemPrompt,
            sessionId = sessionId
        )
        val store = persistentPrefixCacheStore
        val existing = runCatching { store.load(key) }.getOrNull()
        val pending = runCatching { store.prepareWrite(key) }.getOrNull() ?: return null
        return PreparedPersistentPrefix(
            key = key,
            request = PersistentPrefixCacheRequest(
                restoreStatePath = existing?.stateFile?.absolutePath,
                writeStatePath = pending.stateFile.absolutePath,
                fixedSystemPrompt = fixedSystemPrompt.ifBlank { "MCA_SESSION_STATE" },
                fullSessionState = sessionId != null
            ),
            pending = pending,
            existing = existing
        )
    }

    private fun finishPersistentPrefix(
        prepared: PreparedPersistentPrefix?,
        resultCode: Int,
        runner: LocalChatRunner
    ): ManagedPersistentPrefixFailure? {
        if (prepared == null) return null
        if (!persistentPrefixCacheEnabled) {
            runCatching { persistentPrefixCacheStore.discard(prepared.pending) }
            return null
        }
        val stats = runCatching { JSONObject(runner.getRuntimeStatsJson()) }.getOrNull()
        val persistent = stats?.optJSONObject("persistentPrefixCache")
        val saved = resultCode == 0 && persistent?.optBoolean("saved", false) == true
        if (saved) {
            val committed = runCatching { persistentPrefixCacheStore.commit(prepared.pending) }
                .getOrNull()
            if (committed == null) {
                // Native export succeeded, but the managed atomic publication did
                // not. Keep generation successful while making the cache miss
                // explicit so diagnostics never claim state_saved.
                return ManagedPersistentPrefixFailure(
                    reason = MANAGED_PREFIX_COMMIT_FAILED_REASON,
                    tokens = persistent.optInt("tokens", 0)
                )
            }
        } else {
            runCatching { persistentPrefixCacheStore.discard(prepared.pending) }
            val reason = persistent?.optString("reason").orEmpty()
            if (prepared.existing != null && reason in setOf(
                    "state_load_failed",
                    "state_restore_failed",
                    "state_token_mismatch",
                    "state_prefix_mismatch"
                )
            ) {
                runCatching { persistentPrefixCacheStore.clear(prepared.key) }
            }
        }
        return null
    }

    private fun discardPersistentPrefix(prepared: PreparedPersistentPrefix?) {
        prepared ?: return
        runCatching { persistentPrefixCacheStore.discard(prepared.pending) }
    }

    private fun persistentPrefixCacheKey(
        identity: ModelRuntimeIdentity,
        signatures: ParameterSignatureSnapshot,
        nativeParams: JSONObject,
        request: ChatRequest,
        fixedSystemPrompt: String,
        sessionId: String?
    ): PrefixCacheKey {
        fun digest(value: String): String = PrefixCacheKey.sha256Utf8(value)
        val advanced = when (val raw = nativeParams.opt("advanced_json")) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        }
        fun stringValue(name: String, fallback: String = ""): String = when {
            nativeParams.has(name) && !nativeParams.isNull(name) -> nativeParams.optString(name, fallback)
            advanced?.has(name) == true && !advanced.isNull(name) -> advanced.optString(name, fallback)
            else -> fallback
        }
        val templateBinding = listOf(
            identity.templateFingerprint,
            request.params.chatTemplateMode,
            stringValue("use_jinja", "true"),
            stringValue("enable_thinking", "false"),
            stringValue("thinking_budget", "0")
        ).joinToString("\n")
        val runtimeBinding = listOf(
            PERSISTENT_PREFIX_CACHE_FORMAT,
            LLAMA_SEQUENCE_STATE_FORMAT,
            identity.runtimeVersion,
            identity.nativeLibrarySha256,
            identity.abi,
            identity.backendFingerprint,
            signatures.resolved.digest,
            signatures.committed.digest,
            stringValue("n_ctx", "0"),
            stringValue("cache_type_k", "f16"),
            stringValue("cache_type_v", "f16"),
            stringValue("n_parallel", "1")
        ).joinToString("\n")
        return PrefixCacheKey(
            modelFingerprint = digest("model\n${identity.identityHash}"),
            tokenizerFingerprint = digest("tokenizer\n${identity.tokenizerFingerprint}"),
            templateFingerprint = digest("template\n$templateBinding"),
            systemPromptFingerprint = digest(fixedSystemPrompt),
            runtimeFingerprint = digest("runtime\n$runtimeBinding"),
            prefixFingerprint = digest(
                if (sessionId != null) {
                    "full-session-prefix-v1\n$sessionId"
                } else {
                    "fixed-system-prefix\n$fixedSystemPrompt"
                }
            )
        )
    }

    private fun LocalChatRuntime.usesCoordinatedParameters(): Boolean =
        true

    private fun defaultRuntimeIdentity(
        modelPath: String,
        runtime: LocalChatRuntime,
        params: LoadParams,
        artifactFingerprintOverride: String? = null
    ): ModelRuntimeIdentity {
        val model = File(modelPath)
        val artifactMetadata = buildString {
            append(model.name).append('\n')
            append(model.length()).append('\n')
            append(model.lastModified()).append('\n')
            if (model.isDirectory) {
                model.listFiles().orEmpty()
                    .sortedBy { it.name }
                    .take(128)
                    .forEach { file ->
                        append(file.name).append(':').append(file.length()).append(':')
                            .append(file.lastModified()).append('\n')
                    }
            }
        }
        val projectorFingerprint = params.visionProjectorPath
            ?.takeIf { it.isNotBlank() }
            ?.let { path ->
                val projector = File(path)
                parameterSha256("${projector.name}:${projector.length()}:${projector.lastModified()}")
            }
            .orEmpty()
        return ModelRuntimeIdentity(
            modelId = model.name.ifBlank { "local-model" },
            artifactFingerprint = artifactFingerprintOverride ?: parameterSha256(artifactMetadata),
            runtime = runtime,
            runtimeVersion = when (runtime) {
                LocalChatRuntime.MNN_CPU -> "mnn-embedded"
                LocalChatRuntime.LLAMA_CPP -> "llama.cpp-embedded"
                LocalChatRuntime.GENIEX_LLAMA_CPP,
                LocalChatRuntime.GENIEX_QAIRT -> "geniex-embedded"
            },
            nativeLibrarySha256 = "embedded-unreported",
            backendFingerprint = listOfNotNull(
                runtime.backendId,
                params.geniexComputeUnit?.takeIf { it.isNotBlank() }
            ).joinToString(":"),
            projectorFingerprint = projectorFingerprint,
            deviceCapabilityFingerprint = parameterSha256(socInfo.toString()),
            installationScopeId = parameterInstallationScopeId,
            capabilities = if (runtime == LocalChatRuntime.MNN_CPU && isMnnVisualProfile(modelPath, params)) {
                setOf("vision", "mnn_vision_v1")
            } else {
                emptySet()
            }
        )
    }

    private fun isMnnVisualProfile(modelPath: String, params: LoadParams): Boolean {
        val advanced = runCatching { JSONObject(params.advancedJson) }.getOrNull()
        if (advanced?.optString("visual_model")?.isNotBlank() == true ||
            advanced?.optBoolean("is_visual", false) == true
        ) return true

        val selected = File(modelPath)
        val config = when {
            selected.isFile && selected.name.equals("config.json", ignoreCase = true) -> selected
            selected.isDirectory -> File(selected, "config.json")
            else -> selected.parentFile?.let { File(it, "config.json") }
        } ?: return false
        val root = runCatching {
            if (!config.isFile || !config.canRead()) return@runCatching null
            JSONObject(config.readText(Charsets.UTF_8))
        }.getOrNull() ?: return false
        return root.optBoolean("is_visual", false) || root.optString("visual_model").isNotBlank()
    }

    private fun parameterSha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        private const val PERSISTENT_PREFIX_CACHE_FORMAT = "mca-prefix-cache-v1"
        private const val MAX_PERSISTENT_PREFIX_CACHE_PROMPT_BYTES = 128 * 1024
        private const val LLAMA_SEQUENCE_STATE_FORMAT = "llama-state-seq-v1"
        private const val MANAGED_PREFIX_COMMIT_FAILED_REASON = "managed_commit_failed"
        private const val PREFILL_PROGRESS_POLL_INTERVAL_MS = 50L
        private const val ISOLATED_WORKER_SESSION_LOST_FIELD = "workerSessionLost"
        private const val CONTEXT_LENGTH_EXCEEDED_ERROR_CODE = "context_length_exceeded"
        private const val REASONING_INSTRUCTION_ESTIMATE_TOKENS = 96
        private const val LOW_MEMORY_START_GUARD_KB = 384L * 1024L
        private const val LOW_MEMORY_RUNTIME_STOP_KB = 256L * 1024L
        private const val HIDDEN_REASONING_PROGRESS_STEP_TOKENS = 16
        private const val STATS_SAMPLE_INTERVAL_MS = 250L
        private const val MEMORY_SAMPLE_INTERVAL_MS = 1000L
        private val MNN_UNSAFE_STOP_REASONS = setOf(
            "stop_requested",
            "mnn_user_cancel",
            "mnn_internal_error",
            "mnn_timeout",
            "runner_unavailable"
        )
        private val VISION_DIAGNOSTIC_TOKEN_PATTERN = Regex("[a-z0-9_.-]{1,64}")
        private val VISION_DIAGNOSTIC_SHA256_PATTERN = Regex("[a-f0-9]{64}")
    }
}

