package com.muyuchat.core.engine

import android.content.Context
import com.muyuchat.core.modelstore.QairtExecutionAdmission
import com.muyuchat.core.modelstore.QairtBundleRuntimeIdentity
import com.muyuchat.core.telemetry.MemorySnapshot
import com.muyuchat.core.telemetry.RuntimeMetrics
import com.muyuchat.core.telemetry.SocDetector
import com.muyuchat.core.telemetry.TelemetryLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.io.File
import java.util.UUID

class McaInferenceService(
    context: Context,
    runners: Map<LocalChatRuntime, LocalChatRunner>? = null,
    internal val io: CoroutineDispatcher = Dispatchers.IO,
    internal val memorySnapshotProvider: (() -> MemorySnapshot)? = null,
    qairtVerificationStoreOverride: QairtExecutionVerificationStore? = null,
    qairtIdentityProviderOverride: ((String?) -> QairtBundleRuntimeIdentity?)? = null,
    qairtDryRunProcessVerifierOverride: (() -> Boolean)? = null,
    internal val parameterCoordinator: ParameterCoordinator = ParameterCoordinator(),
    installationScopeId: String? = null,
    internal val deviceClockContextProvider: DeviceClockContextProvider = DeviceClockContextProvider(),
    persistentPrefixCacheStoreOverride: PersistentPrefixCacheStore? = null,
    internal val prefillProgressPollIntervalMs: Long = PREFILL_PROGRESS_POLL_INTERVAL_MS,
    private val nativeLibraryDirOverride: String? = null,
    internal val clock: RuntimeMonotonicClock = SystemRuntimeMonotonicClock
) {
    init {
        require(prefillProgressPollIntervalMs > 0L) {
            "prefillProgressPollIntervalMs must be positive."
        }
    }
    internal val runners: Map<LocalChatRuntime, LocalChatRunner> =
        runners ?: defaultLocalChatRunners(context.applicationContext)
    internal val mutex = Mutex()
    internal val retainedVisionInputs = mutableListOf<Triple<LocalChatRunner, ChatRequest, ChatRequest>>()
    internal val generationStopGate = Any()
    internal var generationStopEpoch = 0L
    internal var activeGenerationStopTarget: ActiveGenerationStopTarget? = null
    /** Prevents duplicate stop calls while the original request is still unwinding. */
    internal var stopRequestedToken: GenerationStopToken? = null
    internal val telemetry = TelemetryLogger(context.applicationContext)
    internal val socInfo = SocDetector.detect()
    internal val appContext = context.applicationContext
    internal val parameterInstallationScopeId = installationScopeId
        ?.takeIf { it.isNotBlank() }
        ?: "process-local:${UUID.randomUUID()}"
    internal val persistentPrefixCacheStore: PersistentPrefixCacheStore by lazy {
        persistentPrefixCacheStoreOverride ?: PersistentPrefixCacheStore(
            rootDirectory = runCatching { appContext.noBackupFilesDir }
                .getOrNull()
                ?.let { File(it, "mca_llama_prefix_cache/v1") }
                // Unit-test Context wrappers may not implement noBackupFilesDir;
                // production Android always takes the no-backup branch above.
                ?: File(appContext.filesDir, "mca_llama_prefix_cache/v1")
        )
    }
    internal val qairtVerificationStore = qairtVerificationStoreOverride
        ?: QairtExecutionVerificationStore.forContext(appContext)
    internal val qairtIdentityProvider: (String?) -> QairtBundleRuntimeIdentity? =
        qairtIdentityProviderOverride ?: { bundleSha256 ->
            qairtRuntimeIdentityFor(appContext, bundleSha256)
        }
    internal val qairtDryRunProcessVerifier: () -> Boolean =
        qairtDryRunProcessVerifierOverride ?: { isQairtIsolatedDryRunProcess(appContext) }
    internal val _stats = MutableStateFlow(RuntimeStats(backend = "cpu", loaded = false))
    internal var activeRuntime: LocalChatRuntime = LocalChatRuntime.MNN_CPU
    internal var activeLoadSession: LoadedModelSession? = null
    @Volatile
    internal var lastQairtExecutionAdmission: QairtExecutionAdmission? = null
    internal var qairtDryRunWitness: QairtDryRunWitness? = null
    @Volatile
    internal var persistentPrefixCacheEnabled = true
    // A failed or cancelled MNN turn can leave native state unsafe to reuse.
    // Most successful text turns are reset in native beginCompletion(); the
    // narrow Gemma 4 text-isolation exception is selected by
    // MnnSessionLifecyclePolicy after a successful turn.
    internal var mnnSessionNeedsReloadBeforeNextRequest = false
    internal var isolatedWorkerSessionNeedsReload = false

    val stats = _stats.asStateFlow()

    /**
     * Latest QAIRT diagnostic recommendation. Unknown bundle/device/runtime
     * combinations remain runnable; this value never admits or rejects a load.
     */
    val qairtExecutionAdmission: QairtExecutionAdmission?
        get() = lastQairtExecutionAdmission

    fun persistentPrefixCacheSummary(): PersistentPrefixCacheSummary =
        persistentPrefixCacheSummaryImpl()
    fun setPersistentPrefixCacheEnabled(enabled: Boolean) {
        setPersistentPrefixCacheEnabledImpl(enabled)
    }
    suspend fun clearPersistentPrefixCache(): Boolean = clearPersistentPrefixCacheImpl()
    suspend fun clearPersistentSessionStates(sessionIds: Collection<String>): Boolean = clearPersistentSessionStatesImpl(sessionIds)

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

    fun authorizedPendingSignatureVerification(
        authorization: LoadAuthorization
    ): AuthorizedPendingSignatureVerification? =
        authorizedPendingSignatureVerificationImpl(authorization)
    fun activeExecutionProfile(): ModelExecutionProfile? =
        activeExecutionProfileImpl()
    fun resolveExecutionProfile(
        identity: ModelRuntimeIdentity,
        requestedParamsJson: String,
        profileId: String = UUID.randomUUID().toString(),
        revision: Long = 1
    ): ParameterResolution = resolveExecutionProfileImpl(identity, requestedParamsJson, profileId, revision)
    fun stagePendingExecutionProfile(
        transactionId: String,
        profile: ModelExecutionProfile,
        rollbackTargetProfileId: String? = activeExecutionProfile()?.profileId
    ): LoadAuthorization = stagePendingExecutionProfileImpl(transactionId, profile, rollbackTargetProfileId)
    fun authorizeHotExecutionOverride(
        profile: ModelExecutionProfile,
        values: CanonicalParameterSet
    ): HotOverrideAuthorization = authorizeHotExecutionOverrideImpl(profile, values)
    suspend fun acquireExclusiveLifecycleLease(
        stopActiveGeneration: Boolean = true
    ): EngineLifecycleLease {
        return acquireExclusiveLifecycleLeaseImpl(stopActiveGeneration)
    }
    suspend fun commitDeferredPendingExecutionProfile(
        authorization: LoadAuthorization,
        lease: EngineLifecycleLease
    ): ModelExecutionProfile = commitDeferredPendingExecutionProfileImpl(authorization, lease)
    suspend fun rollbackDeferredPendingExecutionProfile(
        authorization: LoadAuthorization,
        lease: EngineLifecycleLease
    ): String? = rollbackDeferredPendingExecutionProfileImpl(authorization, lease)
    suspend fun preflightChat(
        request: ChatRequest,
        executionContext: LocalChatExecutionContext = LocalChatExecutionContext()
    ): CompletionPreflight = preflightChatImpl(request, executionContext)

    init {
        val runnerNativeLibraryDir = nativeLibraryDirOverride
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: appContext.applicationInfo.nativeLibraryDir
        this.runners.values.forEach { runner ->
            if (runner.isAvailable) {
                runCatching { runner.initBackends(runnerNativeLibraryDir) }
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
    ): Result<RuntimeStats> = loadModelImpl(modelPath, runtime, params, qairtBundleSha256, qairtExecutionPurpose, runtimeIdentity, executionProfile)
    suspend fun unloadModel(lease: EngineLifecycleLease? = null) = unloadModelImpl(lease)
    suspend fun invalidateConversationContext() = invalidateConversationContextImpl()
    fun streamChat(
        request: ChatRequest,
        executionContext: LocalChatExecutionContext = LocalChatExecutionContext()
    ): Flow<GenerateEvent> = streamChatImpl(request, executionContext)
    suspend fun stopGeneration() {
        stopGenerationImpl()
    }
    fun activeGenerationStopToken(): GenerationStopToken? = activeGenerationStopTokenImpl()
    suspend fun stopGenerationIfActive(
        expected: GenerationStopToken? = activeGenerationStopToken()
    ): Boolean = stopGenerationIfActiveImpl(expected)
    suspend fun shutdown() = shutdownImpl()
    suspend fun tryRuntimeHealthSnapshot(): RuntimeHealthSnapshot? = tryRuntimeHealthSnapshotImpl()
    fun nativeStatsJson(): String = nativeStatsJsonImpl()
    fun recentLogs(limit: Int = 200): List<RuntimeMetrics> = recentLogsImpl(limit)

    internal fun estimateTokens(text: String): Int = estimateLocalPromptTokens(text)

    internal fun estimatePromptTokens(request: ChatRequest): Int {
        val estimated = request.messages.sumOf { estimateTokens(it.content).toLong() } +
            estimateTokens(request.params.systemPrompt).toLong() +
            estimateTokens(request.runtimeSystemContext).toLong() +
            REASONING_INSTRUCTION_ESTIMATE_TOKENS.toLong()
        return estimated.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    internal fun ChatRequest.hasImageAttachments(): Boolean =
        messages.any { it.imageAttachments.isNotEmpty() }

    internal fun localVisionReady(runtime: LocalChatRuntime, nativeStats: String): Boolean {
        val stats = runCatching { JSONObject(nativeStats) }.getOrNull() ?: return false
        if (!stats.optBoolean("loaded", false)) return false
        return if (runtime == LocalChatRuntime.LITERT_LM) {
            // Transport alone is not model capability. A known text-only
            // bundle is rejected before entering LiteRT's native delegate;
            // unknown bundles remain eligible for a real request.
            liteRtVisionInputAvailable(stats.toString())
        } else {
            stats.optBoolean("visionReady", false)
        }
    }

    internal fun runnerFor(runtime: LocalChatRuntime): LocalChatRunner =
        runners[runtime] ?: error("本地推理后端未注册：${runtime.label}")
    private fun activateGenerationStopTarget(
        requestId: String,
        runner: LocalChatRunner,
        collectorJob: Job?
    ): GenerationStopToken = activateGenerationStopTargetImpl(requestId, runner, collectorJob)
    private fun clearGenerationStopTarget(token: GenerationStopToken) = clearGenerationStopTargetImpl(token)
    private companion object {
        private const val REASONING_INSTRUCTION_ESTIMATE_TOKENS = 96
    }
}
