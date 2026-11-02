package com.muyuchat.mca.composition

import android.app.Application
import android.content.SharedPreferences
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.mca.*
import com.muyuchat.mca.presentation.KnowledgeLibraryPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** Composition-only factories: each receives only the dependencies of its result. */
internal fun assembleAssistantProfileCoordinator(
    store: UiStateStore,
    scope: CoroutineScope,
    application: Application,
    container: McaAppContainer,
    engine: McaInferenceService,
    characterMemorySummaryCoordinator: CharacterMemorySummaryCoordinator,
    knowledgeLibraryPort: KnowledgeLibraryPort,
    chatAppearanceCoordinator: ChatAppearanceCoordinator,
    showStatus: (String?) -> Unit,
    loadModel: (ModelManifest) -> Unit,
    persistChatSessions: (List<ChatSessionRecord>?, Pair<String, Set<String>>?, Long?) -> Job?,
    markLocalConversationContextInvalid: () -> Unit,
    selectChatSession: (String) -> Unit,
    newChatForAssistant: (AssistantRecord, Boolean) -> Boolean,
    captureContentImportOwner: () -> ContentImportOwner,
    stageContentImportPreview: suspend (ContentImportPreview) -> Unit,
    offlinePromptTranslationAndChatImageCoordinator: OfflinePromptTranslationAndChatImageCoordinator,
    agentTuningCoordinator: AgentTuningCoordinator,
    chatModelCoordinator: ChatModelCoordinator,
): AssistantProfileCoordinator = container.createAssistantProfileCoordinator(
    store = store,
    scope = scope,
    modelGenerationProfileStore = container.modelGenerationProfileStore,
    chatSessionStore = container.chatSessionStore,
    knowledgeLibraryPort = knowledgeLibraryPort,
    chatSessionPersistenceStateLock = container.chatSessionPersistenceState.stateLock,
    pendingWorldBookCleanupOwners = container.chatSessionPersistenceState.pendingWorldBookCleanupOwners,
    engine = engine,
    characterMemorySummaryCoordinator = characterMemorySummaryCoordinator,
    chatSessionPersistenceMutex = container.chatSessionPersistenceState.persistenceMutex,
    chatSessionPersistenceSequence = container.chatSessionPersistenceState.persistenceSequence,
    getPendingKnowledgeBindings = { container.chatSessionPersistenceState.pendingKnowledgeBindings },
    setDurableChatSessions = { container.chatSessionPersistenceState.durableChatSessions = it },
    conversationMutationBarrierIsActive = { container.runtimeAdmission.conversationMutationBarrier?.isActive == true },
    contentImportSelectionRevision = container.runtimeAdmission.contentImportSelectionRevision,
    activeRuntimeIdentity = { container.runtimeAdmission.activeRuntimeIdentity },
    runtimeUserOverrideFields = container.runtimeAdmission.runtimeUserOverrideFields,
    directParameterStageGeneration = container.runtimeAdmission.directParameterStageGeneration,
    getActiveDirectParameterStageJob = { container.runtimeAdmission.directParameterStageJob },
    setActiveDirectParameterStageJob = { container.runtimeAdmission.directParameterStageJob = it },
    directParameterStageMutex = container.runtimeAdmission.directParameterStageMutex,
    adaptiveTuningJobIsActive = { agentTuningCoordinator.adaptiveTuningJob?.isActive == true },
    getActiveTuningJobId = { agentTuningCoordinator.activeTuningJobId },
    getPendingProfileTransactionId = { container.runtimeAdmission.pendingProfileTransactionId },
    setPendingProfileTransactionId = { container.runtimeAdmission.pendingProfileTransactionId = it },
    rejectWhileConversationMutationInProgress = { rejectWhileConversationMutationInProgressInto(store, container.runtimeAdmission) },
    showStatus = showStatus,
    persistChatSessions = { sessions -> persistChatSessions(sessions, null, null) },
    markLocalConversationContextInvalid = markLocalConversationContextInvalid,
    onLocalConversationContextNeedsInvalidation = {
        container.runtimeAdmission.localConversationContextNeedsInvalidation = true
        container.runtimeAdmission.localConversationContextInvalidationSequence.incrementAndGet()
    },
    selectChatSession = selectChatSession,
    loadModel = loadModel,
    selectCloudChatModel = { chatModelCoordinator.selectCloudChatModel(it) },
    newChatForAssistant = newChatForAssistant,
    invalidatePendingChatImagePreparation = offlinePromptTranslationAndChatImageCoordinator::invalidatePendingChatImagePreparation,
    chatAppearanceMutex = chatAppearanceCoordinator.mutex,
    cleanupUnusedChatBackgrounds = { chatAppearanceCoordinator.cleanupUnusedChatBackgrounds() },
    captureContentImportOwner = captureContentImportOwner,
    stageContentImportPreview = stageContentImportPreview,
    readAttachmentText = { uri, maxBytes -> readAttachmentText(application.contentResolver, uri, maxBytes) },
    resolveWorldBookFallbackName = { uri -> displayNameForUri(application.contentResolver, uri) },
    currentModelGenerationProfileKey = { GenerationPreferences.modelGenerationProfileKey(store.current.selectedChatBackend, store.current.currentChatModelId()) }
)

internal fun assembleContextCompressionCoordinator(
    store: UiStateStore,
    container: McaAppContainer,
    engine: McaInferenceService,
    contextCompressionPreferences: SharedPreferences,
    showStatus: (String?) -> Unit,
    persistChatSessions: (List<ChatSessionRecord>?, Pair<String, Set<String>>?, Long?) -> Job?,
    markLocalConversationContextInvalid: () -> Unit,
): ContextCompressionCoordinator = ContextCompressionCoordinator(
    store = store.contextCompressionWriter(),
    engine = engine,
    cloudChatProvider = container.cloudChatProvider,
    modelGenerationProfileStore = container.modelGenerationProfileStore,
    hasActiveImageRequest = { container.localImageGenerationCoordinator.activeRequestId() != null },
    manualContextCompressionRequests = container.manualContextCompressionRequests,
    preferences = contextCompressionPreferences,
    chatRuns = container.chatRuns,
    getActiveRuntimeIdentity = { container.runtimeAdmission.activeRuntimeIdentity },
    markLocalConversationContextInvalid = markLocalConversationContextInvalid,
    persistChatSessions = { persistChatSessions(null, null, null) },
    showStatus = showStatus,
    onLocalInferenceStarted = {
        container.runtimeAdmission.localConversationContextNeedsInvalidation = true
        container.runtimeAdmission.localConversationContextInvalidationSequence.incrementAndGet()
    }
)

