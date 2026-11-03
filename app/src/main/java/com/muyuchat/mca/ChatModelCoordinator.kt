package com.muyuchat.mca

import android.app.Application
import android.util.Log
import com.muyuchat.core.advisor.AgentAdvisor
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LoadParams
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.chat.chatBackendFamilyForRuntime
import com.muyuchat.feature.chat.withChatBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Coordinator for model lifecycle (selection, load, verify, unload, delete),
 * backend preference persistence, smoke validation, runtime profile recovery,
 * and cloud model configuration.
 */
internal class ChatModelCoordinator(
    private val store: UiStateWriter<RuntimeProfileFields>,
    private val modelSelectionStore: UiStateWriter<ChatModelSelectionFields>,
    private val modelCatalogStore: UiStateWriter<ModelCatalogFields>,
    private val scope: CoroutineScope,
    private val application: Application,
    private val engine: McaInferenceService,
    private val modelStore: ModelStoreRepository,
    private val cloudApiStore: CloudApiStore,
    private val runtimeProfileStore: ModelRuntimeProfileStore,
    private val modelGenerationProfileStore: ModelGenerationProfileStore,
    private val tuningOwner: TuningOwner,
    private val advisor: AgentAdvisor,
    private val admission: RuntimeAdmissionState,
    private val deviceProfileReader: DeviceProfileReader,
    private val managedRuntimeReadinessRefreshGate: ManagedRuntimeReadinessRefreshGate,
    private val managedRuntimeReadinessPort: () -> ManagedRuntimeReadinessPort,
    val cloudModelConfigPort: CloudModelConfigPort,
    private val installationScopeId: String,
    private val cancelGenerationJob: () -> Unit,
    private val persistChatSessions: (List<ChatSessionRecord>?) -> Unit,
    private val publishManagedChatCatalogAfterValidation: () -> ManagedChatCatalogSnapshot,
    private val busy: (String) -> Unit,
    private val loadModelGenerationParams: (ChatBackend, String?, GenerationParams) -> GenerationParams,
    private val persistModelGenerationParams: (ChatBackend, String, GenerationParams) -> Unit,
    private val updateParams: (GenerationParams) -> Unit,
    private val cancelDirectParameterStageJob: () -> Unit,
    private val rejectImageModelSwitchWhileGenerationIsActive: () -> Boolean,
    private val invalidatePendingChatImagePreparation: () -> Unit,
    private val activeRuntimeIdentity: () -> ModelRuntimeIdentity?,
    private val currentDeviceProfile: () -> DeviceProfile,
    private val currentQairtBundleSha256: (ModelManifest, List<ModelManifest>) -> String?,
    private val currentQairtVerifiedLocalModelIds: (android.content.Context, List<ModelManifest>) -> Set<String>
) {
    private val pendingRuntimeRecoveries = linkedMapOf<String, RuntimeRecoveryPlan>()

    fun updateCloudApiEnabled(enabled: Boolean) = applyCloudApiEnabled(cloudModelConfigPort, enabled)
    fun updateCloudApiFormat(value: String) = applyCloudApiFormat(cloudModelConfigPort, value)
    fun updateCloudImageApiFormat(value: String) = applyCloudImageApiFormat(cloudModelConfigPort, value)
    fun applyCloudProviderPreset(value: String) = applyCloudProviderPreset(cloudModelConfigPort, value)
    fun beginAddCloudModel(kind: String) = applyBeginAddCloudModel(cloudModelConfigPort, kind)
    fun editCloudModel(modelId: String) = applyEditCloudModel(cloudModelConfigPort, modelId)
    fun updateCloudBaseUrl(value: String) = applyCloudBaseUrl(cloudModelConfigPort, value)
    fun updateCloudApiKey(value: String) = applyCloudApiKey(cloudModelConfigPort, value)
    fun updateCloudChatModel(value: String) = applyCloudChatModel(cloudModelConfigPort, value)
    fun updateCloudSupportsVision(value: Boolean) = applyCloudSupportsVision(cloudModelConfigPort, value)
    fun generationParamsForChatModel(modelId: String): GenerationParams = cloudChatModelGenerationParams(port = cloudModelConfigPort, modelId = modelId)
    fun updateCloudSupportsTools(value: Boolean) = applyCloudSupportsTools(port = cloudModelConfigPort, value = value)
    fun updateCloudStreaming(value: Boolean) = applyCloudStreaming(port = cloudModelConfigPort, value = value)
    fun updateCloudImageModel(value: String) = applyCloudImageModel(port = cloudModelConfigPort, value = value)
    fun updateCloudResponsesReasoning(value: Boolean) = applyCloudResponsesReasoning(port = cloudModelConfigPort, value = value)
    fun updateCloudImageSize(value: String) = applyCloudImageSize(port = cloudModelConfigPort, value = value)
    fun updateCloudImageEndpointPath(value: String) = applyCloudImageEndpointPath(port = cloudModelConfigPort, value = value)
    fun updateCloudDisplayName(value: String) = applyCloudDisplayName(port = cloudModelConfigPort, value = value)
    fun saveCloudApiConfig() = persistCloudApiConfig(port = cloudModelConfigPort)
    fun saveCloudChatModel() = saveCloudChatModelConfig(port = cloudModelConfigPort)
    fun saveCloudImageModel() = saveCloudImageModelConfig(port = cloudModelConfigPort)
    fun testCloudApiConfig() = runCloudApiConfigTest(port = cloudModelConfigPort, scope = scope)

    /** Reads the editor draft and asks the provider for its model ids; writes no UI state. */
    suspend fun fetchCloudModelOptions(): Result<List<String>> =
        fetchCloudModelOptionsFor(cloudModelConfigPort)

    /** The image editor's equivalent; separate protocol, so a separate catalog read. */
    suspend fun fetchCloudImageModelOptions(): Result<List<String>> =
        fetchCloudImageModelOptionsFor(cloudModelConfigPort)

    fun selectCloudImageModel(modelId: String) {
        if (rejectImageModelSwitchWhileGenerationIsActive()) return
        invalidatePendingChatImagePreparation()
        selectCloudImageModelFor(cloudModelConfigPort, modelId)
    }

    /**
     * Persists a GGUF backend choice against the target model before it is loaded.
     * A choice made for another model must never leak through the global parameter
     * object; loading the model later reads this profile and applies it to native.
     */
    fun updateModelBackendPreference(modelId: String, backendId: String) {
        val target = store.current.models.firstOrNull { it.id == modelId } ?: return
        val family = chatBackendFamilyForRuntime(target.runtime.storageValue)
        val current = loadModelGenerationParams(
            ChatBackend.LOCAL,
            modelId,
            store.current.params
        )
        val updated = current.withChatBackend(family, backendId)
        persistModelGenerationParams(ChatBackend.LOCAL, modelId, updated)
        if (store.current.loadedModelId == modelId) {
            updateParams(updated)
            cancelDirectParameterStageJob()
            store.update {
                it.copy(
                    statusMessage = "正在重新加载 ${target.displayName}（${backendLabel(family, backendId)}）…"
                )
            }
            loadModel(target)
        } else {
            store.update { state ->
                state.copy(
                    statusMessage = "已保存 ${target.displayName} 的 " +
                        "${backendLabel(family, backendId)} 设置；下次加载该模型时生效。"
                )
            }
        }
    }

    fun launchModelOperation(
        message: String,
        operation: suspend CoroutineScope.() -> Unit
    ): Job = scope.launch(Dispatchers.IO) {
        recoverModelOperation(operation = { operation() }, onFailure = { error ->
            Log.e("McaModelOperation", message, error)
            store.update { state ->
                state.copy(
                    busy = false,
                    statusMessage = "$message：${error.message.orEmpty()}。请检查模型文件和存储空间后重试。",
                    engineLifecycle = if (state.engineLifecycle == AgentEngineLifecycle.LOADING) {
                        if (state.stats.loaded) AgentEngineLifecycle.READY else AgentEngineLifecycle.ERROR
                    } else state.engineLifecycle
                )
            }
        })
    }

    fun selectChatModel(choiceId: String) =
        selectChatModelFor(store, ::loadModel, { selectCloudChatModel(it) }, choiceId)

    fun selectCloudChatModel(modelId: String?) =
        selectCloudChatModelFor(
            modelSelectionStore,
            cloudApiStore,
            engine,
            scope,
            application,
            modelGenerationProfileStore,
            { persistChatSessions(it) },
            modelId
        )

    private suspend fun CoroutineScope.loadModelBody(requestedModel: ModelManifest) =
        runLoadModelBody(
            requestedModel,
            store,
            admission,
            pendingRuntimeRecoveries,
            engine,
            modelStore,
            runtimeProfileStore,
            modelGenerationProfileStore,
            cloudApiStore,
            tuningOwner,
            advisor,
            deviceProfileReader,
            managedRuntimeReadinessRefreshGate,
            managedRuntimeReadinessPort(),
            application,
            installationScopeId,
            cancelGeneration = { cancelGenerationJob() },
            persistChatSessions = { persistChatSessions(it) }
        )

    fun loadModel(requestedModel: ModelManifest) {
        launchModelOperation("模型操作未完成") {
            loadModelBody(requestedModel)
        }
    }

    fun verifyModel(model: ModelManifest) {
        verifyModelFor(
            store = modelCatalogStore,
            application = application,
            modelStore = modelStore,
            managedRuntimeReadinessRefreshGate = managedRuntimeReadinessRefreshGate,
            publishManagedChatCatalogAfterValidation = publishManagedChatCatalogAfterValidation,
            busy = busy,
            launchModelOperation = ::launchModelOperation,
            model = model
        )
    }

    fun clearNativeRuntimeSessionState(
        lifecycle: AgentEngineLifecycle,
        statusMessage: String,
        busy: Boolean = false
    ) = clearNativeRuntimeSessionStateFor(
        store, admission, engine, lifecycle, statusMessage, busy
    )

    suspend fun restoreLoadedRuntimeSnapshot(
        snapshot: LoadedRuntimeSnapshot,
        statusMessage: String
    ): Boolean = restoreLoadedRuntimeSnapshotFor(
        store,
        admission,
        engine,
        runtimeProfileStore,
        modelStore,
        tuningOwner,
        managedRuntimeReadinessRefreshGate,
        snapshot,
        statusMessage
    )

    fun unloadModel(model: ModelManifest) = unloadModelFor(
        scope,
        store,
        admission,
        engine,
        runtimeProfileStore,
        model
    ) { cancelGenerationJob() }

    fun deleteModel(model: ModelManifest) = deleteModelFor(
        scope,
        store,
        admission,
        engine,
        runtimeProfileStore,
        modelStore,
        managedRuntimeReadinessRefreshGate,
        application,
        model
    ) { cancelGenerationJob() }

    suspend fun clearPendingRuntimeTransactionForLifecycle(
        reason: String,
        identity: ModelRuntimeIdentity? = activeRuntimeIdentity()
    ) = clearPendingRuntimeTransactionForLifecycleFor(
        runtimeProfileStore, admission, reason, identity
    )

    suspend fun timedLoadModel(
        model: ModelManifest,
        runtime: LocalChatRuntime,
        loadParams: LoadParams
    ): Long = timedLoadModelFor(
        engine = engine,
        activeRuntimeIdentity = activeRuntimeIdentity(),
        modelStore = modelStore,
        context = application,
        device = currentDeviceProfile(),
        installationScopeId = installationScopeId,
        model = model,
        runtime = runtime,
        loadParams = loadParams,
        currentQairtBundleSha256 = currentQairtBundleSha256,
        currentQairtVerifiedLocalModelIds = currentQairtVerifiedLocalModelIds
    )

    suspend fun runLocalGenerationSmoke(params: GenerationParams): LocalGenerationSmokeResult =
        runLocalGenerationSmokeFor(engine, params)

    suspend fun recoverInterruptedRuntimeProfiles() =
        recoverInterruptedRuntimeProfilesFor(runtimeProfileStore, pendingRuntimeRecoveries, store)
}
