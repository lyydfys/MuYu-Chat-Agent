package com.muyuchat.mca

import android.app.Application
import android.util.Log
import com.muyuchat.core.advisor.AgentAdvisor
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.recycleIsolatedWorkerAfterUnload
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.feature.agent.AgentEngineLifecycle
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.feature.agent.AgentTuningJobState
import com.muyuchat.feature.agent.AgentProfileRecordState
import com.muyuchat.feature.agent.AgentProfileVerification
import com.muyuchat.feature.agent.AgentPendingProfile
import com.muyuchat.feature.agent.AgentRollbackProfile
import com.muyuchat.feature.agent.AgentCandidateProgress
import com.muyuchat.core.tuning.BootstrapLoadCanaryPolicy
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.liteRtVisionInputAvailable
import com.muyuchat.core.engine.LITERT_LM_VISION_TRANSPORT_UNAVAILABLE_MESSAGE
import com.muyuchat.core.engine.LITERT_LM_VISION_COMPONENTS_UNAVAILABLE_MESSAGE
import com.muyuchat.core.engine.LITERT_LM_KNOWN_TEXT_ONLY_VISION_UNAVAILABLE_MESSAGE
import kotlinx.coroutines.CancellationException

// R3-S24：模型装载主链自 MainViewModel 迁出（体逐字；依赖参数化）。

    /**
     * The body of [loadModel], moved out of its inline lambda.
     *
     * That lambda was 579 lines and compiled into a single `invokeSuspend` method, which sat
     * right at the JVM 64 KB per-method limit: any edit to this file could tip it over and
     * crash the Kotlin compiler with "Exception while generating code for". Moving the body
     * into this function leaves `loadModel` with one call, so neither method is near a limit.
     *
     * Moved verbatim: no statement, condition, ordering or message was edited. Because the
     * lambda's receiver was the launch's CoroutineScope, this is an extension function on
     * CoroutineScope, and the two `return@launchModelOperation` early exits became `return`.
     */
internal suspend fun CoroutineScope.runLoadModelBody(
    requestedModel: ModelManifest,
    store: UiStateWriter<RuntimeProfileFields>,
    admission: RuntimeAdmissionState,
    pendingRuntimeRecoveries: MutableMap<String, RuntimeRecoveryPlan>,
    engine: McaInferenceService,
    modelStore: ModelStoreRepository,
    runtimeProfileStore: ModelRuntimeProfileStore,
    modelGenerationProfileStore: ModelGenerationProfileStore,
    cloudApiStore: CloudApiStore,
    tuningOwner: TuningOwner,
    advisor: AgentAdvisor,
    deviceReader: DeviceProfileReader,
    managedRuntimeReadinessRefreshGate: ManagedRuntimeReadinessRefreshGate,
    readinessPort: ManagedRuntimeReadinessPort,
    application: Application,
    installationScopeId: String,
    cancelGeneration: () -> Unit,
    persistChatSessions: (List<ChatSessionRecord>) -> Unit
) {
    val runtimeBeforeLoad = captureLoadedRuntimeSnapshotFor(
        engine = engine,
        state = store.current,
        activeModelForRuntimeProfile = admission.activeModelForRuntimeProfile,
        activeAdaptiveRecommendation = admission.activeAdaptiveRecommendation
    )
    fun failBeforeNativeReplacement(message: String) {
        // Release the input lock before diagnostics. Native/Binder reads can
        // be slow or fail, and a preflight failure must not leave a cancelled
        // generation projected as active in the UI.
        val stats = engine.stats.value
        store.update {
            it.afterRuntimeGenerationTerminated(
                stats = stats,
                statusMessage = message
            ).copy(
                busy = false,
                engineLifecycle = if (stats.loaded) {
                    AgentEngineLifecycle.READY
                } else {
                    AgentEngineLifecycle.ERROR
                }
            )
        }
        val nativeStats = engine.nativeStatsJsonOrEmpty()
        val recentLogs = engine.recentEngineLogsOrEmpty()
        store.update {
            it.copy(
                nativeStatsJson = nativeStats,
                logs = recentLogs,
            )
        }
    }
    cancelGeneration()
    engine.stopGeneration()
    store.update {
        it.copy(
            busy = true,
            engineLifecycle = AgentEngineLifecycle.LOADING,
            statusMessage = "正在加载 ${requestedModel.displayName}...",
            modelLoadStage = "正在检查模型文件"
        )
    }
    val preflight = modelStore.validateForLoad(requestedModel.id)
    val validatedCatalog = publishChatCatalogSnapshot(readinessPort)
    if (!preflight.canLoad) {
        // Keep model-load failures observable without leaking prompts, API keys,
        // or model contents.  The UI message remains user friendly while this
        // structured event gives support tooling a stable runtime/code boundary.
        Log.e(
            "MCA-ModelLoad",
            "preflight_failed modelId=${requestedModel.id} runtime=${requestedModel.runtime.storageValue} " +
                "title=${preflight.title} details=${preflight.details.take(512)}"
        )
        failBeforeNativeReplacement("加载前检查失败：${preflight.message}")
        return
    }
    // Resolve the semantic profile before any isolated bootstrap/load work.  A model
    // switch must never probe the new native runtime with the previous model's sampling
    // budget or model-specific overrides; the formal publish below resolves it again
    // after the committed execution profile is known.
    val modelParams = loadModelGenerationParamsFor(
        modelGenerationProfileStore,
        backend = ChatBackend.LOCAL,
        modelId = requestedModel.id,
        defaults = store.current.params
    )
    val params = store.current.selectedAssistant()?.toGenerationParams(modelParams) ?: modelParams
    var persistedModels = validatedCatalog.models
    store.update { it.copy(modelLoadStage = "正在准备运行环境") }
    var model = persistedModels.firstOrNull { it.id == requestedModel.id } ?: requestedModel
    val qairtVerifiedIds = if (model.runtime == ChatModelRuntime.GENIEX_QAIRT) {
        validatedCatalog.qairtVerifiedLocalModelIds
    } else {
        emptySet()
    }
    val device = deviceReader.read()
    val memoryAdmission = LocalModelMemoryAdmissionPolicy.evaluate(model, device)
    memoryAdmission.blocker?.let { message ->
        failBeforeNativeReplacement("加载前内存检查失败：$message")
        return
    }
    var nativeReplacementOccurred = false
    var stagedBootstrapTransactionId: String? = null
    var stagedBootstrapIdentityKey: String? = null

    suspend fun recoverAfterNativeReplacement(
        message: String,
        emptyLifecycle: AgentEngineLifecycle = AgentEngineLifecycle.ERROR
    ): String? = withContext(NonCancellable) {
        if (!nativeReplacementOccurred) {
            failBeforeNativeReplacement(message)
            return@withContext null
        }
        val restored = runtimeBeforeLoad?.let { snapshot ->
            runCatching {
                restoreLoadedRuntimeSnapshotFor(store, admission, engine, runtimeProfileStore, modelStore, tuningOwner, managedRuntimeReadinessRefreshGate, 
                    snapshot,
                    "$message；已恢复此前加载的 ${snapshot.model.displayName}。"
                )
            }.getOrDefault(false)
        } ?: false
        if (!restored) {
            val unloadError = if (engine.stats.value.loaded) {
                runCatching { engine.unloadModel() }.exceptionOrNull()
            } else {
                null
            }
            clearNativeRuntimeSessionStateFor(store, admission, engine, 
                lifecycle = emptyLifecycle,
                statusMessage = buildString {
                    append(message)
                    if (runtimeBeforeLoad != null) append("；此前稳定模型恢复失败")
                    if (unloadError != null) append("；原生卸载同时失败：${unloadError.message}")
                }
            )
        }
        if (restored) runtimeBeforeLoad.profile.profileId else null
    }

    suspend fun rejectStagedBootstrap(
        error: Throwable,
        failureStage: String
    ): RuntimeRecoveryPlan? = withContext(NonCancellable) {
        val transactionId = stagedBootstrapTransactionId ?: return@withContext null
        val identityKey = stagedBootstrapIdentityKey ?: return@withContext null
        val pending = runtimeProfileStore.pendingTransaction(identityKey)
        if (pending?.journal?.transactionId != transactionId) return@withContext null
        val remoteCode = (error as? TuningProbeWorkerRemoteException)?.code
        val recovery = runtimeProfileStore.rejectCandidate(
            transactionId = transactionId,
            failureStage = failureStage,
            failureCode = remoteCode?.takeIf(String::isNotBlank) ?: "BOOTSTRAP_LOAD_FAILED",
            failureSummary = error.message ?: "The isolated bootstrap load failed."
        )
        stagedBootstrapTransactionId = null
        stagedBootstrapIdentityKey = null
        recovery.rollbackProfileId?.let {
            synchronized(pendingRuntimeRecoveries) {
                pendingRuntimeRecoveries[recovery.identityKey] = recovery
            }
        }
        recovery
    }

    suspend fun completeBootstrapRecoveryIfRestored(
        recovery: RuntimeRecoveryPlan?,
        restoredProfileId: String?
    ) = withContext(NonCancellable) {
        val rollbackId = recovery?.rollbackProfileId ?: return@withContext
        val restoredSnapshot = runtimeBeforeLoad
        if (restoredProfileId != rollbackId ||
            restoredSnapshot?.profile?.runtimeIdentity?.identityHash != recovery.identityKey
        ) {
            return@withContext
        }
        runtimeProfileStore.completeRecovery(recovery.transactionId, rollbackId)
        synchronized(pendingRuntimeRecoveries) {
            pendingRuntimeRecoveries.remove(recovery.identityKey)
        }
    }

    try {
        if (memoryAdmission.mode == LocalModelMemoryAdmissionMode.SPARSE_MOE_MMAP) {
            store.update {
                it.copy(statusMessage = "正在以稀疏 MoE mmap 模式加载 ${model.displayName}…")
            }
        }
    val runtime = model.runtime.toLocalChatRuntime()
    val qairtBundleSha256 = currentQairtBundleSha256(
        requested = model,
        persistedModels = persistedModels
    )
    val qairtAdmissionPassed = model.runtime == ChatModelRuntime.GENIEX_QAIRT &&
        model.id in qairtVerifiedIds
    val identity = RuntimeIdentityFactory.create(
        context = application,
        model = model,
        runtime = runtime,
        device = device,
        installationScopeId = installationScopeId,
        qairtAdmissionPassed = qairtAdmissionPassed
    )
    runtimeProfileStore.upsertIdentity(identity.toRuntimeIdentityEntity())

    val persistedState = runtimeProfileStore.currentRuntimeState(identity.identityHash)
    val recoveryPlan = synchronized(pendingRuntimeRecoveries) {
        pendingRuntimeRecoveries[identity.identityHash]
    }
    val persistedProfile = recoveryPlan?.rollbackProfileId?.let {
        runtimeProfileStore.reconstructedProfile(it)
    } ?: persistedState?.activeExecutionProfile
    val profileResolution = resolveLoadModelProfileFor(
        tuningOwner = tuningOwner,
        model = model,
        identity = identity,
        qairtAdmissionPassed = qairtAdmissionPassed,
        device = device,
        admission = admission,
        engine = engine,
        runtimeProfileStore = runtimeProfileStore,
        params = params,
        persistedProfile = persistedProfile
    )
    val bootstrapProfile = profileResolution.bootstrapProfile
    val adaptive = profileResolution.adaptive
    val activeProfile = persistedState?.activeProfile
    val reusableBootstrapProof = activeProfile != null &&
        activeProfile.recordState == PersistedProfileRecordState.COMMITTED.name &&
        activeProfile.verificationLevel in setOf(
            PersistedProfileVerificationLevel.SAFE.name,
            PersistedProfileVerificationLevel.COMPATIBLE.name,
            PersistedProfileVerificationLevel.DEVICE_VERIFIED.name
        ) &&
        activeProfile.profileId == bootstrapProfile.profileId &&
        activeProfile.resolvedLoadSignature == bootstrapProfile.resolvedLoadSignature.digest &&
        activeProfile.committedExecutionSignature ==
            bootstrapProfile.committedExecutionSignature.digest
    val needsBootstrapCommit = !reusableBootstrapProof
    // The formal MainActivity load is the real native admission path.
    // Running a full isolated load + canary here and then loading the
    // same multi-gigabyte GGUF again doubles cold-start latency and
    // gives no additional capability to the user-facing action.
    val ordinaryBootstrapRuntime = false
    if (needsBootstrapCommit && ordinaryBootstrapRuntime) {
        val transactionId = "bootstrap-load-${UUID.randomUUID()}"
        stagedBootstrapTransactionId = transactionId
        stagedBootstrapIdentityKey = identity.identityHash
        stageBootstrapProfileFor(runtimeProfileStore, 
            transactionId = transactionId,
            profile = bootstrapProfile,
            rollbackTargetProfileId = activeProfile
                ?.takeIf { it.recordState == PersistedProfileRecordState.COMMITTED.name }
                ?.profileId
        )

        if (engine.stats.value.loaded) {
            try {
                engine.stopGeneration()
                engine.unloadModel()
            } catch (error: Throwable) {
                if (engine.nativeRuntimeReleaseObservedNow()) {
                    nativeReplacementOccurred = true
                    clearNativeRuntimeSessionStateFor(store, admission, engine, 
                        lifecycle = AgentEngineLifecycle.LOADING,
                        statusMessage = "当前模型已释放，但隔离安全启动准备失败。",
                        busy = true
                    )
                }
                throw IllegalStateException(
                    "隔离安全启动前无法释放当前模型：${error.message ?: "未知错误"}",
                    error
                )
            }
            nativeReplacementOccurred = true
            clearNativeRuntimeSessionStateFor(store, admission, engine, 
                lifecycle = AgentEngineLifecycle.LOADING,
                statusMessage = "已释放当前模型内存，正在隔离进程验证 ${model.displayName}…",
                busy = true
            )
        }

        val workerResult = TuningProbeWorkerClient(application).probe(
            probeKind = TuningProbeWorkerProtocol.ProbeKind.BOOTSTRAP_LOAD,
            transactionId = transactionId,
            identityKey = identity.identityHash,
            modelId = model.id,
            profileId = bootstrapProfile.profileId,
            resolvedLoadSignature = bootstrapProfile.resolvedLoadSignature.digest,
            committedExecutionSignature = bootstrapProfile.committedExecutionSignature.digest
        ) { progress ->
            store.update { state ->
                if (state.busy) {
                    state.copy(statusMessage = "隔离安全启动：${progress.message}")
                } else {
                    state
                }
            }
        }
        recordBootstrapProbeMeasurementFor(runtimeProfileStore, workerResult)
        require(
            workerResult.passed &&
                workerResult.signatureMatched &&
                BootstrapLoadCanaryPolicy.matches(workerResult.output)
        ) {
            "隔离安全启动未通过：${workerResult.detail}"
        }
        store.update {
            it.copy(statusMessage = "隔离安全启动通过，正在正式加载 ${model.displayName}…")
        }
    }
    val loadParams = model.loadParamsForExecutionProfile(bootstrapProfile)
    // B44 (device A 2026-10-16): a replacement load of a *different* model must start in a
    // fresh isolated worker process. A 4B QAIRT model loaded while the previous model stayed
    // resident in the same worker pushed DMA-BUF past MIUI's guard (force-stop of the whole
    // package), and MNN loads failed after a QAIRT model stayed resident. Re-selecting the
    // already loaded model keeps the original idempotent fast path (MNN/Gemma) untouched.
    if (engine.stats.value.loaded && store.current.loadedModelId != requestedModel.id) {
        store.update { it.copy(modelLoadStage = "正在释放当前模型内存") }
        engine.stopGeneration()
        val unloadError = runCatching { engine.unloadModel() }.exceptionOrNull()
        if (unloadError != null) {
            failBeforeNativeReplacement("释放当前模型失败：${unloadError.message ?: "未知错误"}。")
            return
        }
        nativeReplacementOccurred = true
        val recycled = runCatching { engine.recycleIsolatedWorkerAfterUnload() }.getOrDefault(false)
        if (!recycled) {
            recoverAfterNativeReplacement("释放当前模型内存失败，请重试。")
            return
        }
    }
    // LLAMA, MNN, and QAIRT keep the long-lived native handle in a worker process.
    // The load Result still owns concrete runtime failures and recovery decisions.
    store.update { it.copy(modelLoadStage = "正在加载模型，首次加载可能较慢") }
    val nativeLoad = engine.loadModel(
        modelPath = model.path,
        runtime = runtime,
        params = loadParams,
        qairtBundleSha256 = qairtBundleSha256,
        runtimeIdentity = identity,
        executionProfile = bootstrapProfile
    )
    if (nativeLoad.isFailure && runtimeBeforeLoad != null &&
        engine.nativeRuntimeReleaseObservedNow()
    ) {
        nativeReplacementOccurred = true
    }
    nativeLoad.getOrThrow()
    nativeReplacementOccurred = true
    val nativeVisionLoadStats = engine.nativeStatsJsonOrEmpty()
    val baseRunnerLoaded = runCatching {
        JSONObject(nativeVisionLoadStats).optBoolean("loaded", false)
    }.getOrDefault(false)
    require(baseRunnerLoaded) {
        "本地聊天引擎未确认模型已加载，已安全阻止请求。请重试加载；如果仍失败，请重新校验模型文件。"
    }
    val visionReadinessWarning = when (model.runtime) {
        ChatModelRuntime.LLAMA_CPP -> llamaCppVisionReadinessWarning(
            runtime = model.runtime,
            configuredProjectorPath = loadParams.visionProjectorPath,
            nativeStatsJson = nativeVisionLoadStats
        )
        ChatModelRuntime.LITERT_LM -> {
            val stats = runCatching { JSONObject(nativeVisionLoadStats) }.getOrNull()
            when {
                stats?.optBoolean("visionModelKnownTextOnly", false) == true ->
                    LITERT_LM_KNOWN_TEXT_ONLY_VISION_UNAVAILABLE_MESSAGE
                stats?.has("visionModelVisualComponentsPresent") == true &&
                    !stats.optBoolean("visionModelVisualComponentsPresent", false) ->
                    LITERT_LM_VISION_COMPONENTS_UNAVAILABLE_MESSAGE
                !liteRtVisionInputAvailable(nativeVisionLoadStats) ->
                    LITERT_LM_VISION_TRANSPORT_UNAVAILABLE_MESSAGE
                else -> null
            }
        }
        else -> null
    }
    store.update { it.copy(modelLoadStage = "正在确认模型运行配置") }
    val formalProfile = engine.activeExecutionProfile() ?: bootstrapProfile
    require(formalProfile.profileId == bootstrapProfile.profileId) {
        "正式加载激活了非预期 profile。"
    }
    val formalSignatures = engine.parameterSignatureSnapshot()
        ?: error("正式加载未发布参数签名快照。")
    require(bootstrapProfile.matchesExactParameterSignatures(formalSignatures)) {
        "正式加载的参数签名与隔离进程验证的 profile 不一致。"
    }

    stagedBootstrapTransactionId?.let { transactionId ->
        runtimeProfileStore.updateJournalStage(
            transactionId = transactionId,
            state = TuningJournalState.VALIDATING,
            stage = "BOOTSTRAP_FORMAL_LOAD_VERIFIED"
        )
        runtimeProfileStore.commitCandidate(
            transactionId = transactionId,
            verificationLevel = PersistedProfileVerificationLevel.SAFE,
            activeLoadedSignature = requireNotNull(formalSignatures.active).digest,
            effectiveExecutionSignature = requireNotNull(formalSignatures.effective).digest
        )
        stagedBootstrapTransactionId = null
        stagedBootstrapIdentityKey = null
    }
    if (needsBootstrapCommit && !ordinaryBootstrapRuntime) {
        persistBootstrapProfileFor(
            runtimeProfileStore, engine,
            profile = formalProfile,
            sourceSummary = JSONObject()
                .put("kind", "formal_load")
                .put("runtime", model.runtime.name)
                .toString()
        )
    }

    // The new model is not user-visible until the exact formal
    // signatures and any staged bootstrap transaction are committed.
    clearNativeRuntimeSessionStateFor(store, admission, engine, 
        lifecycle = AgentEngineLifecycle.LOADING,
        statusMessage = "正式加载已验证，正在发布运行状态…",
        busy = true
    )
    admission.activeRuntimeIdentity = identity
    admission.activeVisionProjectorStamp = loadedVisionProjectorStamp(model, identity)
    admission.activeModelForRuntimeProfile = model
    store.update { it.copy(modelLoadStage = "正在完成准备") }
    val modelParams = loadModelGenerationParamsFor(
        modelGenerationProfileStore,
        backend = ChatBackend.LOCAL,
        modelId = model.id,
        defaults = params
    )
    GenerationPreferences.persistGenerationParams(application, modelParams)
    val effectiveParams = mergedExecutionProfile(modelParams, formalProfile)
    store.update { state ->
        state.copy(
            params = effectiveParams,
            reloadRequired = false,
            engineLifecycle = AgentEngineLifecycle.LOADING,
            statusMessage = "正式加载已验证，正在发布运行状态…"
        )
    }
        if (recoveryPlan != null) {
            runtimeProfileStore.completeRecovery(
                transactionId = recoveryPlan.transactionId,
                restoredProfileId = recoveryPlan.rollbackProfileId
            )
            synchronized(pendingRuntimeRecoveries) {
                pendingRuntimeRecoveries.remove(identity.identityHash)
            }
        }
    publishLoadedModelStateFor(
        store = store,
        admission = admission,
        engine = engine,
        modelStore = modelStore,
        model = model,
        adaptive = adaptive,
        advisor = advisor,
        device = device,
        runtimeProfileStore = runtimeProfileStore,
        identity = identity,
        managedRuntimeReadinessRefreshGate = managedRuntimeReadinessRefreshGate,
        cloudApiStore = cloudApiStore,
        memoryAdmission = memoryAdmission,
        visionReadinessWarning = visionReadinessWarning,
        persistChatSessions = persistChatSessions
    )
    } catch (error: CancellationException) {
        if (runtimeBeforeLoad != null &&
            engine.nativeRuntimeReleaseObservedNow()
        ) {
            nativeReplacementOccurred = true
        }
        val bootstrapRecovery = runCatching {
            rejectStagedBootstrap(error, "BOOTSTRAP_LOAD_CANCELLED")
        }.getOrNull()
        val restoredProfileId = recoverAfterNativeReplacement(
            message = "模型加载已取消。",
            emptyLifecycle = AgentEngineLifecycle.UNLOADED
        )
        runCatching {
            completeBootstrapRecoveryIfRestored(bootstrapRecovery, restoredProfileId)
        }
        throw error
    } catch (error: Throwable) {
        Log.e(
            "McaMainViewModel",
            "formal model load failed after native handoff: ${error.message.orEmpty()}",
            error
        )
        if (runtimeBeforeLoad != null &&
            engine.nativeRuntimeReleaseObservedNow()
        ) {
            nativeReplacementOccurred = true
        }
        val nativeStats = engine.nativeStatsJsonOrEmpty()
        val failure = LocalModelLoadFailureClassifier.classify(error.message, nativeStats)
        val bootstrapRecovery = runCatching {
            rejectStagedBootstrap(error, "BOOTSTRAP_LOAD")
        }.getOrNull()
        val restoredProfileId = recoverAfterNativeReplacement("加载失败：${failure.userMessage}")
        runCatching {
            completeBootstrapRecoveryIfRestored(bootstrapRecovery, restoredProfileId)
        }
    }
    }

