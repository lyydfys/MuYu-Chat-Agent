package com.muyuchat.mca

import android.app.Application
import android.net.Uri
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.mca.presentation.KnowledgeLibraryPort
import com.muyuchat.mca.presentation.knowledgeLibrarySnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinator for Assistant profile management, memory recording and summarization,
 * assistant selection, card imports, world books, and assistant persistence.
 */
internal class AssistantProfileCoordinator(
    private val store: UiStateWriter<AssistantProfileFields>,
    private val knowledgeStore: UiStateWriter<KnowledgeLibraryFields>,
    private val scope: CoroutineScope,
    private val application: Application,
    private val assistantStore: AssistantStore,
    private val modelGenerationProfileStore: ModelGenerationProfileStore,
    private val chatSessionStore: ChatSessionStore,
    private val worldBookStore: WorldBookStore,
    private val runtimeProfileStore: ModelRuntimeProfileStore,
    private val engine: McaInferenceService,
    private val cloudChatProvider: OpenAiCompatibleChatProvider,
    private val characterMemorySummaryCoordinator: CharacterMemorySummaryCoordinator,
    private val knowledgeLibraryPort: KnowledgeLibraryPort,
    private val chatSessionPersistenceState: ChatSessionPersistenceState,
    private val chatSessionPersistenceStateLock: Any,
    private val pendingWorldBookCleanupOwners: MutableMap<WorldBookScope, MutableSet<String>>,
    private val chatSessionPersistenceMutex: Mutex,
    private val chatSessionPersistenceSequence: AtomicLong,
    private val getPendingKnowledgeBindings: () -> Map<String, Set<String>>,
    private val setDurableChatSessions: (List<ChatSessionRecord>) -> Unit,
    private val conversationMutationBarrierIsActive: () -> Boolean,
    private val contentImportSelectionRevision: AtomicLong,
    private val activeRuntimeIdentity: () -> ModelRuntimeIdentity?,
    private val runtimeUserOverrideFields: Set<String>,
    private val directParameterStageGeneration: AtomicLong,
    private val getActiveDirectParameterStageJob: () -> Job?,
    private val setActiveDirectParameterStageJob: (Job?) -> Unit,
    private val directParameterStageMutex: Mutex,
    adaptiveTuningJobIsActive: () -> Boolean,
    getActiveTuningJobId: () -> String?,
    getPendingProfileTransactionId: () -> String?,
    setPendingProfileTransactionId: (String?) -> Unit,
    private val rejectWhileConversationMutationInProgress: () -> Boolean,
    private val showStatus: (String) -> Unit,
    private val persistChatSessions: (List<ChatSessionRecord>?) -> Unit,
    private val markLocalConversationContextInvalid: () -> Unit,
    private val onLocalConversationContextNeedsInvalidation: () -> Unit,
    private val selectChatSession: (String) -> Unit,
    private val loadModel: (ModelManifest) -> Unit,
    private val selectCloudChatModel: (String?) -> Unit,
    private val newChatForAssistant: (AssistantRecord, Boolean) -> Boolean,
    private val invalidatePendingChatImagePreparation: () -> Unit,
    private val chatAppearanceMutex: Mutex,
    private val cleanupUnusedChatBackgrounds: suspend () -> Unit,
    private val captureContentImportOwner: () -> ContentImportOwner,
    private val stageContentImportPreview: suspend (ContentImportPreview) -> Unit,
    private val readAttachmentText: (Uri, Int) -> Pair<String, Boolean>,
    private val resolveWorldBookFallbackName: (Uri) -> String = { "" },
    private val currentModelGenerationProfileKey: () -> String?
) {
    private fun ContentImportOwner.isCurrent(state: MainUiState): Boolean =
        selectionRevision == contentImportSelectionRevision.get() &&
            sessionId == state.activeChatSessionId && assistantId == state.selectedAssistantId
    private val adaptiveTuningJobIsActiveRef = adaptiveTuningJobIsActive
    private val getActiveTuningJobIdRef = getActiveTuningJobId
    private val getPendingProfileTransactionIdRef = getPendingProfileTransactionId
    private val setPendingProfileTransactionIdRef = setPendingProfileTransactionId
    private val assistantSaveMutex = Mutex()
    // Memory operations receive only their two writable fields, even within this profile owner.
    private val memoryStore = store.restrict(
        project = { fields -> AssistantMemoryFields(
            snapshot = fields.snapshot,
            assistantMemories = fields.assistantMemories,
            statusMessage = fields.statusMessage
        ) },
        merge = { fields, memory -> fields.copy(
            assistantMemories = memory.assistantMemories,
            statusMessage = memory.statusMessage
        ) }
    )

    fun persistAssistantsAsync() {
        scope.launch(Dispatchers.IO) {
            assistantSaveMutex.withLock {
                runCatching { assistantStore.saveAssistants(store.current.assistants) }
                    .onFailure { error ->
                        store.update { it.copy(
                            statusMessage = "助手保存失败：${error.message ?: "请重试"}"
                        ) }
                    }
            }
        }
    }

    fun stageDirectParameterProfile(params: GenerationParams) =
        stageDirectParameterProfileFor(
            store = store,
            scope = scope,
            engine = engine,
            runtimeProfileStore = runtimeProfileStore,
            activeRuntimeIdentity = activeRuntimeIdentity,
            runtimeUserOverrideFields = runtimeUserOverrideFields,
            directParameterStageGeneration = directParameterStageGeneration,
            getActiveDirectParameterStageJob = getActiveDirectParameterStageJob,
            setActiveDirectParameterStageJob = setActiveDirectParameterStageJob,
            directParameterStageMutex = directParameterStageMutex,
            adaptiveTuningJobIsActive = adaptiveTuningJobIsActiveRef,
            getActiveTuningJobId = getActiveTuningJobIdRef,
            getPendingProfileTransactionId = getPendingProfileTransactionIdRef,
            setPendingProfileTransactionId = setPendingProfileTransactionIdRef,
            params = params
        )

    fun saveAssistantProfile(
        id: String?,
        name: String,
        avatar: String,
        tag: String,
        systemPrompt: String,
        defaultModelMode: String,
        defaultModelId: String?,
        temperature: Float,
        topP: Float,
        nPredict: Int,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        presencePenalty: Float,
        frequencyPenalty: Float,
        stopWords: List<String>,
        reasoningMode: ReasoningMode,
        memoryEnabled: Boolean,
        memorySummaryInterval: Int,
        webSearchEnabled: Boolean,
        fileContextEnabled: Boolean,
        generationOverrideFields: Set<String> = emptySet()
    ): Boolean = saveAssistantProfileFor(
        store = store,
        assistantStore = assistantStore,
        application = application,
        modelGenerationProfileStore = modelGenerationProfileStore,
        rejectWhileConversationMutationInProgress = rejectWhileConversationMutationInProgress,
        hasActiveAssistantImageExecution = { hasActiveAssistantImageExecutionIn(store.current, it) },
        showStatus = showStatus,
        persistAssistantsAsync = ::persistAssistantsAsync,
        persistChatSessions = persistChatSessions,
        markLocalConversationContextInvalid = markLocalConversationContextInvalid,
        selectAssistant = ::selectAssistant,
        applyAssistantDefaultModel = ::applyAssistantDefaultModel,
        id = id, name = name, avatar = avatar, tag = tag, systemPrompt = systemPrompt,
        defaultModelMode = defaultModelMode, defaultModelId = defaultModelId,
        temperature = temperature, topP = topP, nPredict = nPredict, topK = topK,
        minP = minP, repeatPenalty = repeatPenalty, presencePenalty = presencePenalty,
        frequencyPenalty = frequencyPenalty, stopWords = stopWords,
        reasoningMode = reasoningMode, memoryEnabled = memoryEnabled,
        memorySummaryInterval = memorySummaryInterval, webSearchEnabled = webSearchEnabled,
        fileContextEnabled = fileContextEnabled,
        generationOverrideFields = generationOverrideFields
    )

    fun selectAssistant(assistantId: String) {
        val state = store.current
        if (rejectWhileConversationMutationInProgress()) return
        val assistant = state.assistants.firstOrNull { it.id == assistantId } ?: return
        val previousSession = state.chatSessions.latestSessionForAssistant(assistantId)
        if (previousSession != null) {
            if (previousSession.id != state.activeChatSessionId || state.selectedAssistantId != assistant.id) {
                selectChatSession(previousSession.id)
            }
            if (store.current.activeChatSessionId == previousSession.id &&
                previousSession.modelMode.toChatBackendOrNull() == ChatBackend.LOCAL
            ) {
                previousSession.modelId?.let { modelId ->
                    if (store.current.loadedModelId != modelId) {
                        store.current.models.firstOrNull { it.id == modelId }?.let(loadModel)
                    }
                }
            }
            return
        }
        if (!newChatForAssistant(assistant, true)) return
        finishSelectingNewAssistant(assistant)
    }

    fun createChatForAssistant(assistantId: String) {
        val assistant = store.current.assistants.firstOrNull { it.id == assistantId } ?: return
        if (!newChatForAssistant(assistant, true)) return
        finishSelectingNewAssistant(assistant)
    }

    fun finishSelectingNewAssistant(assistant: AssistantRecord) =
        finishSelectingNewAssistantFor(
            store = store,
            assistantStore = assistantStore,
            modelGenerationProfileStore = modelGenerationProfileStore,
            application = application,
            invalidatePendingChatImagePreparation = invalidatePendingChatImagePreparation,
            contentImportSelectionRevision = contentImportSelectionRevision,
            markLocalConversationContextInvalid = markLocalConversationContextInvalid,
            applyAssistantDefaultModel = ::applyAssistantDefaultModel,
            currentModelGenerationProfileKey = currentModelGenerationProfileKey,
            assistant = assistant
        )

    fun deleteAssistant(assistantId: String) = deleteAssistantFor(
        store = store,
        assistantStore = assistantStore,
        modelGenerationProfileStore = modelGenerationProfileStore,
        chatSessionStore = chatSessionStore,
        application = application,
        scope = scope,
        rejectWhileConversationMutationInProgress = rejectWhileConversationMutationInProgress,
        hasActiveAssistantImageExecution = { hasActiveAssistantImageExecutionIn(store.current, it) },
        showStatus = showStatus,
        queueWorldBookCleanup = ::queueWorldBookCleanup,
        cleanupPendingWorldBooksAfterOwnerCommit = ::cleanupPendingWorldBooksAfterOwnerCommit,
        selectAssistant = ::selectAssistant,
        persistChatSessions = persistChatSessions,
        chatAppearanceMutex = chatAppearanceMutex,
        cleanupUnusedChatBackgrounds = cleanupUnusedChatBackgrounds,
        currentModelGenerationProfileKey = currentModelGenerationProfileKey,
        assistantId = assistantId
    )

    fun queueWorldBookCleanup(scope: WorldBookScope, ownerIds: Set<String>) {
        require(scope != WorldBookScope.GLOBAL) { "Global world books are not owner-scoped." }
        val normalizedOwnerIds = ownerIds.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (normalizedOwnerIds.isEmpty()) return
        synchronized(chatSessionPersistenceStateLock) {
            pendingWorldBookCleanupOwners
                .getOrPut(scope) { linkedSetOf() }
                .addAll(normalizedOwnerIds)
        }
    }

    fun cancelPendingWorldBookCleanup(scope: WorldBookScope, ownerIds: Set<String>) =
        cancelPendingWorldBookCleanupIn(chatSessionPersistenceState, scope, ownerIds)

    fun cleanupPendingWorldBooksAfterOwnerCommit(scope: WorldBookScope, retainedOwnerIds: Set<String>) =
        cleanupPendingWorldBooksAfterOwnerCommitFor(chatSessionPersistenceState, worldBookStore, knowledgeStore, scope, retainedOwnerIds)

    fun importWorldBook(rawJson: String, scope: WorldBookScope = WorldBookScope.ASSISTANT) {
        val owner = captureContentImportOwner()
        this.scope.launch { importWorldBookOwned(rawJson, scope, owner) }
    }

    suspend fun importWorldBookOwned(
        rawJson: String,
        scope: WorldBookScope,
        owner: ContentImportOwner,
        commitId: String? = null,
        fallbackName: String? = null
    ): Boolean = importWorldBookOwnedFor(
        knowledgeLibraryPort = knowledgeLibraryPort,
        uiStateStore = knowledgeStore,
        currentState = { store.current },
        stageContentImportPreview = stageContentImportPreview,
        markLocalConversationContextInvalid = markLocalConversationContextInvalid,
        ownerIsCurrent = { it.isCurrent(store.current) },
        rawJson = rawJson,
        scope = scope,
        owner = owner,
        commitId = commitId,
        fallbackName = fallbackName
    )

    fun importWorldBookFile(
        uriString: String,
        scope: WorldBookScope = WorldBookScope.ASSISTANT,
        importOwner: ContentImportOwner? = null
    ) = importWorldBookFileFor(
        scope = this.scope,
        isGenerating = { store.current.isGenerating },
        showStatus = showStatus,
        captureContentImportOwner = captureContentImportOwner,
        importWorldBookOwned = { rawJson, bookScope, owner, fallbackName ->
            importWorldBookOwned(rawJson, bookScope, owner, null, fallbackName)
        },
        readAttachmentText = readAttachmentText,
        resolveFallbackName = resolveWorldBookFallbackName,
        uriString = uriString,
        scopeKind = scope,
        importOwner = importOwner
    )

    suspend fun finishCharacterCardImport(
        result: CharacterCardParseResult,
        owner: ContentImportOwner,
        commitId: String? = null
    ): Boolean = finishCharacterCardImportFor(
        store = store,
        assistantStore = assistantStore,
        worldBookStore = worldBookStore,
        chatSessionStore = chatSessionStore,
        chatSessionPersistenceMutex = chatSessionPersistenceMutex,
        chatSessionPersistenceSequence = chatSessionPersistenceSequence,
        getPendingKnowledgeBindings = getPendingKnowledgeBindings,
        setDurableChatSessions = setDurableChatSessions,
        chatSessionPersistenceStateLock = chatSessionPersistenceStateLock,
        stageContentImportPreview = stageContentImportPreview,
        conversationMutationBarrierIsActive = conversationMutationBarrierIsActive,
        hasActiveAssistantImageExecution = { hasActiveAssistantImageExecutionIn(store.current, it) },
        currentOwnerIsCurrent = { owner.isCurrent(store.current) },
        selectAssistant = ::selectAssistant,
        result = result,
        owner = owner,
        commitId = commitId
    )

    fun importAssistantCard(rawJson: String) =
        importAssistantCardFor(store, scope, showStatus, captureContentImportOwner, ::finishCharacterCardImport, rawJson)

    fun importAssistantCardFile(uriString: String, importOwner: ContentImportOwner? = null) =
        importAssistantCardFileFor(store, scope, application, showStatus, captureContentImportOwner, ::finishCharacterCardImport, uriString, importOwner)

    fun deleteWorldBook(worldBookId: String) =
        deleteWorldBookFor(knowledgeLibraryPort, knowledgeStore, scope, { store.current.knowledgeLibrarySnapshot() }, worldBookId)

    fun setWorldBookEnabled(worldBookId: String, enabled: Boolean) =
        setWorldBookEnabledFor(
            knowledgeLibraryPort = knowledgeLibraryPort,
            uiStateStore = knowledgeStore,
            scope = scope,
            currentSnapshot = { store.current.knowledgeLibrarySnapshot() },
            currentState = { store.current },
            markLocalConversationContextInvalid = markLocalConversationContextInvalid,
            worldBookId = worldBookId,
            enabled = enabled
        )

    fun createKnowledgeBase(name: String, description: String = "") =
        createKnowledgeBaseFor(knowledgeLibraryPort, knowledgeStore, scope, { store.current.knowledgeLibrarySnapshot() }, name, description)

    fun deleteKnowledgeBase(knowledgeBaseId: String) =
        deleteKnowledgeBaseFor(knowledgeLibraryPort, knowledgeStore, scope, { store.current.knowledgeLibrarySnapshot() }, knowledgeBaseId)

    fun applyAssistantDefaultModel(assistant: AssistantRecord) =
        applyAssistantDefaultModelFor(store, showStatus, selectCloudChatModel, loadModel, assistant)

    fun upsertAssistantMemory(
        assistantId: String,
        id: String?,
        memoryScope: String,
        content: String
    ): Deferred<Boolean> =
        upsertAssistantMemoryFor(memoryStore, scope, chatSessionStore, showStatus, markLocalConversationContextInvalid, assistantId, id, memoryScope, content)

    fun deleteAssistantMemory(assistantId: String, id: String) =
        deleteAssistantMemoryFor(memoryStore, scope, chatSessionStore, showStatus, markLocalConversationContextInvalid, assistantId, id)

    fun recordCompletedCharacterMemoryTurn(
        assistantId: String,
        sessionId: String,
        assistantSnapshot: AssistantConversationSnapshot,
        user: ChatMessage,
        assistant: ChatMessage,
        conversationPersistJob: Job?,
        backend: ChatBackend,
        params: GenerationParams,
        cloudConfig: CloudApiConfig?,
        localIdentity: String?
    ) = recordCompletedCharacterMemoryTurnFor(
        store = store, scope = scope, chatSessionStore = chatSessionStore,
        scheduleCharacterMemorySummary = ::scheduleCharacterMemorySummary,
        assistantId = assistantId, sessionId = sessionId, assistantSnapshot = assistantSnapshot,
        user = user, assistant = assistant, conversationPersistJob = conversationPersistJob,
        backend = backend, params = params, cloudConfig = cloudConfig, localIdentity = localIdentity
    )

    fun scheduleCharacterMemorySummary(
        assistantId: String,
        backend: ChatBackend,
        params: GenerationParams,
        cloudConfig: CloudApiConfig?,
        localIdentity: String?
    ) = characterMemorySummaryCoordinator.schedule(
        scope = scope, summarizeCharacterMemory = ::summarizeCharacterMemory,
        assistantId = assistantId, backend = backend, params = params, cloudConfig = cloudConfig, localIdentity = localIdentity
    )

    suspend fun summarizeCharacterMemory(
        assistantId: String,
        backend: ChatBackend,
        params: GenerationParams,
        cloudConfig: CloudApiConfig?,
        localIdentity: String?
    ) = summarizeCharacterMemoryFor(
        store = memoryStore, chatSessionStore = chatSessionStore, cloudChatProvider = cloudChatProvider,
        engine = engine, chatSessionPersistenceMutex = chatSessionPersistenceMutex,
        activeRuntimeIdentity = activeRuntimeIdentity,
        markLocalConversationContextInvalid = markLocalConversationContextInvalid,
        onLocalConversationContextNeedsInvalidation = onLocalConversationContextNeedsInvalidation,
        recordReschedule = characterMemorySummaryCoordinator::recordReschedule,
        assistantId = assistantId, backend = backend, params = params, cloudConfig = cloudConfig, localIdentity = localIdentity
    )
}
