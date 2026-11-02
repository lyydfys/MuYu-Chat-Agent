package com.muyuchat.mca.composition

import android.app.Application
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.mca.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** Composition-only factories: each receives only the dependencies of its result. */
internal fun assembleModelImportAndDownloadCoordinator(
    store: UiStateStore,
    scope: CoroutineScope,
    application: Application,
    container: McaAppContainer,
    initialChatSessions: List<ChatSessionRecord>,
    modelIdentityReferenceMigration: ModelIdentityReferenceMigration,
    showStatus: (String?) -> Unit,
    fail: (String) -> Unit,
    busy: (String) -> Unit,
    failDownload: (String) -> Unit,
    loadModel: (ModelManifest) -> Unit,
    managedRuntimeReadinessPortProvider: () -> ManagedRuntimeReadinessPort,
): ModelImportAndDownloadCoordinator = ModelImportAndDownloadCoordinator(
    application = application,
    scope = scope,
    store = store.modelCatalogWriter(),
    modelStore = container.modelStore,
    localImageModelStore = container.localImageModelStore,
    getManagedRuntimeReadinessRefreshGate = { container.readinessRefreshGate },
    getManagedRuntimeReadinessPort = managedRuntimeReadinessPortProvider,
    chatImageToolAuthorizationStore = container.chatImageToolAuthorizationStore,
    getInitialChatSessionIds = { initialChatSessions.map { it.id } },
    showStatus = showStatus,
    failDownload = failDownload,
    busy = busy,
    fail = fail,
    loadModel = loadModel,
    reconcileModelIdentities = {
        reconcileModelIdentitiesFor(
            chatSessionPersistenceMutex = container.chatSessionPersistenceState.persistenceMutex,
            modelIdentityReferenceMigration = modelIdentityReferenceMigration,
            store = store.modelIdentityWriter(),
            getDurableChatSessions = { container.chatSessionPersistenceState.durableChatSessions },
            setDurableChatSessions = { container.chatSessionPersistenceState.durableChatSessions = it }
        )
    }
)

internal fun assembleChatModelCoordinator(
    store: UiStateStore,
    scope: CoroutineScope,
    container: McaAppContainer,
    engine: McaInferenceService,
    tuningOwner: TuningOwner,
    installationScopeId: String,
    busy: (String) -> Unit,
    persistChatSessions: (List<ChatSessionRecord>?, Pair<String, Set<String>>?, Long?) -> Job?,
    persistModelGenerationParams: (ChatBackend, String?, GenerationParams) -> Unit,
    updateParams: (GenerationParams) -> Unit,
    cancelGenerationJob: () -> UiGenerationCancellation,
    publishManagedChatCatalogAfterValidation: () -> ManagedChatCatalogSnapshot,
    loadModelGenerationParams: (ChatBackend, String?, GenerationParams) -> GenerationParams,
    currentDeviceProfile: () -> DeviceProfile,
    managedRuntimeReadinessPortProvider: () -> ManagedRuntimeReadinessPort,
    offlinePromptTranslationAndChatImageCoordinator: OfflinePromptTranslationAndChatImageCoordinator,
): ChatModelCoordinator = container.createChatModelCoordinator(
    store = store,
    scope = scope,
    engine = engine,
    tuningOwner = tuningOwner,
    modelGenerationProfileStore = container.modelGenerationProfileStore,
    managedRuntimeReadinessPort = managedRuntimeReadinessPortProvider,
    installationScopeId = installationScopeId,
    cancelGenerationJob = {
        cancelGenerationJob()
        Unit
    },
    persistChatSessions = { persistChatSessions(it, null, null) },
    persistChatSessionsWithBinding = { sessions, binding -> persistChatSessions(sessions, binding, null) },
    publishManagedChatCatalogAfterValidation = publishManagedChatCatalogAfterValidation,
    busy = busy,
    loadModelGenerationParams = { backend, modelId, defaults ->
        loadModelGenerationParams(backend, modelId, defaults)
    },
    persistModelGenerationParams = { backend, modelId, params ->
        persistModelGenerationParams(backend, modelId, params)
    },
    updateParams = updateParams,
    cancelDirectParameterStageJob = { container.runtimeAdmission.directParameterStageJob?.cancel() },
    rejectImageModelSwitchWhileGenerationIsActive = { rejectImageModelSwitchWhileGenerationIsActiveFor(store, container.runtimeAdmission) },
    invalidatePendingChatImagePreparation = offlinePromptTranslationAndChatImageCoordinator::invalidatePendingChatImagePreparation,
    activeRuntimeIdentity = { container.runtimeAdmission.activeRuntimeIdentity },
    currentDeviceProfile = currentDeviceProfile,
    currentQairtBundleSha256 = ::currentQairtBundleSha256,
    currentQairtVerifiedLocalModelIds = ::currentQairtVerifiedLocalModelIds
)

