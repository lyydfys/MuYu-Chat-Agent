package com.muyuchat.mca.composition

import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.mca.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope

/** Composition-only factories: each receives only the dependencies of its result. */
internal fun assembleMainPostStartupCoordinator(
    store: UiStateStore,
    scope: CoroutineScope,
    container: McaAppContainer,
    engine: McaInferenceService,
    imageLibraryStartupReconciliation: ImageLibraryStartupReconciliation,
    persistentPrefixCacheOperationSequence: AtomicLong,
    modelIdentityReferenceMigration: ModelIdentityReferenceMigration,
    skipInitialModelDiscovery: Boolean,
    observeManagedDownloads: () -> Unit,
    schedulePersistentPrefixCacheClear: (Long, String, String) -> Unit,
    managedRuntimeReadinessPortProvider: () -> ManagedRuntimeReadinessPort,
    modelImportAndDownloadCoordinator: ModelImportAndDownloadCoordinator,
    offlinePromptTranslationAndChatImageCoordinator: OfflinePromptTranslationAndChatImageCoordinator,
    chatModelCoordinator: ChatModelCoordinator,
    contentImportPreviewCoordinator: ContentImportPreviewCoordinator,
    injectedMainPostStartupCoordinator: MainPostStartupCoordinator?,
): MainPostStartupCoordinator = injectedMainPostStartupCoordinator
    ?: container.createMainPostStartupCoordinator(
        store = store,
        scope = scope,
        engine = engine,
        imageLibraryStartupReconciliation = imageLibraryStartupReconciliation,
        modelImportAndDownloadCoordinator = modelImportAndDownloadCoordinator,
        offlinePromptTranslationAndChatImageCoordinator = offlinePromptTranslationAndChatImageCoordinator,
        chatModelCoordinator = chatModelCoordinator,
        restorePendingContentImportPreview = { contentImportPreviewCoordinator.restorePendingContentImportPreview() },
        observeManagedDownloadsAction = { observeManagedDownloads() },
        schedulePersistentPrefixCacheClear = { op, succ, fail -> schedulePersistentPrefixCacheClear(op, succ, fail) },
        persistentPrefixCacheOperationSequence = persistentPrefixCacheOperationSequence,
        managedRuntimeReadinessPortProvider = managedRuntimeReadinessPortProvider,
        chatSessionPersistenceMutex = container.chatSessionPersistenceState.persistenceMutex,
        modelIdentityReferenceMigration = modelIdentityReferenceMigration,
        getDurableChatSessions = { container.chatSessionPersistenceState.durableChatSessions },
        setDurableChatSessions = { container.chatSessionPersistenceState.durableChatSessions = it },
        skipInitialModelDiscovery = skipInitialModelDiscovery
    )

