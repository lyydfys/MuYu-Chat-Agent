package com.muyuchat.mca

import android.app.Application
import android.net.Uri
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.feature.agent.AgentPendingProfile
import com.muyuchat.feature.agent.AgentTuningJobState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal const val ASSISTANT_MODEL_MODE_FOLLOW_CURRENT = "follow_current"
internal const val ASSISTANT_MODEL_MODE_LOCAL = "local"
internal const val ASSISTANT_MODEL_MODE_CLOUD = "cloud"

internal fun String.normalizedAssistantModelMode(): String =
    when (trim().lowercase()) {
        ASSISTANT_MODEL_MODE_LOCAL -> ASSISTANT_MODEL_MODE_LOCAL
        ASSISTANT_MODEL_MODE_CLOUD -> ASSISTANT_MODEL_MODE_CLOUD
        else -> ASSISTANT_MODEL_MODE_FOLLOW_CURRENT
    }

internal fun AssistantRecord.toGenerationParams(defaults: GenerationParams): GenerationParams =
    assistantGenerationParamsResolved(paramsJson, defaults, systemPrompt)

internal fun saveAssistantProfileFor(
    store: UiStateWriter<AssistantProfileFields>,
    assistantStore: AssistantStore,
    application: Application,
    modelGenerationProfileStore: ModelGenerationProfileStore,
    rejectWhileConversationMutationInProgress: () -> Boolean,
    hasActiveAssistantImageExecution: (String?) -> Boolean,
    showStatus: (String) -> Unit,
    persistAssistantsAsync: () -> Unit,
    persistChatSessions: (List<ChatSessionRecord>?) -> Unit,
    markLocalConversationContextInvalid: () -> Unit,
    selectAssistant: (String) -> Unit,
    applyAssistantDefaultModel: (AssistantRecord) -> Unit,
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
): Boolean {
    val cleanName = name.trim().take(36).ifBlank { "未命名助手" }
    val cleanAvatar = avatar.trim().take(4)
    val cleanTag = tag.trim().take(24)
    val cleanPrompt = boundedManualAssistantSystemPrompt(systemPrompt)
    val cleanDefaultModelMode = defaultModelMode.normalizedAssistantModelMode()
    val cleanDefaultModelId = defaultModelId
        ?.trim()
        ?.takeIf { it.isNotBlank() && cleanDefaultModelMode != ASSISTANT_MODEL_MODE_FOLLOW_CURRENT }
    val state = store.state.value
    if (rejectWhileConversationMutationInProgress() || state.isGenerating ||
        hasActiveAssistantImageExecution(state.activeChatSessionId)) {
        showStatus("请先结束当前角色请求，再保存角色卡。")
        return false
    }
    val currentModelKey = GenerationPreferences.modelGenerationProfileKey(
        state.selectedChatBackend,
        state.currentChatModelId()
    )
    val modelPreset = currentModelKey
        ?.let { key -> modelGenerationProfileStore.loadOrCreate(key, state.params) }
        ?: state.params
    val now = System.currentTimeMillis()
    val existing = id?.let { assistantId -> state.assistants.firstOrNull { it.id == assistantId } }
    val assistant = (existing ?: AssistantRecord(name = cleanName, createdAt = now)).copy(
        name = cleanName,
        avatar = cleanAvatar,
        tag = cleanTag,
        systemPrompt = cleanPrompt,
        defaultModelMode = cleanDefaultModelMode,
        defaultModelId = cleanDefaultModelId,
        characterCardJson = existing?.characterCardJson,
        paramsJson = modelPreset.copy(
            systemPrompt = cleanPrompt,
            temperature = temperature.coerceIn(0f, 2f),
            topP = topP.coerceIn(0f, 1f),
            nPredict = nPredict.coerceIn(16, 65_536),
            topK = topK.coerceIn(0, 1000),
            minP = minP.coerceIn(0f, 1f),
            repeatPenalty = repeatPenalty.coerceIn(0.5f, 2f),
            presencePenalty = presencePenalty.coerceIn(-2f, 2f),
            frequencyPenalty = frequencyPenalty.coerceIn(-2f, 2f),
            stopWords = stopWords.asSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && it.length <= 128 }
                .distinct()
                .take(32)
                .toList(),
            reasoningMode = reasoningMode,
            hideReasoning = reasoningMode == ReasoningMode.OFF
        ).toAssistantGenerationJsonWithOverrides(generationOverrideFields)
            .withAssistantPromptProvenance(AssistantPromptProvenance.USER_AUTHORED),
        memoryEnabled = memoryEnabled,
        memorySummaryInterval = memorySummaryInterval.coerceIn(2, 100),
        webSearchEnabled = webSearchEnabled,
        fileContextEnabled = fileContextEnabled,
        updatedAt = now
    )
    val updatedAssistants = if (existing == null) {
        (state.assistants + assistant).distinctBy { it.id }
    } else {
        state.assistants.map { if (it.id == assistant.id) assistant else it }
    }
    val shouldSelectAssistant = existing == null || existing.id == state.selectedAssistantId
    if (shouldSelectAssistant && existing != null) assistantStore.saveSelectedAssistantId(assistant.id)
    val updatedParams = assistant.toGenerationParams(modelPreset)
    if (shouldSelectAssistant && existing != null) {
        GenerationPreferences.persistGenerationParams(application, updatedParams)
    }
    // An explicit edit applies to this role's active conversation only. Other
    // conversations keep the prompt version they captured when created.
    val activeSession = state.chatSessions.firstOrNull { it.id == state.activeChatSessionId }
    val applyToActiveConversation = existing != null && shouldSelectAssistant &&
        activeSession?.assistantId == assistant.id &&
        activeSession?.assistantSnapshot?.assistantId == assistant.id &&
        activeSession?.mixedAssistantHistory == false
    val updatedSessions = if (applyToActiveConversation) state.chatSessions.map { session ->
        val previousSnapshot = session.assistantSnapshot
        if (session.id != state.activeChatSessionId || session.mixedAssistantHistory ||
            session.assistantId != assistant.id ||
            previousSnapshot?.assistantId != assistant.id) session else {
            val revisedSnapshot = assistant.toConversationSnapshot(previousSnapshot.capturedAt).copy(
                priorSystemPromptHashes = (
                    previousSnapshot.priorSystemPromptHashes +
                        com.muyuchat.core.engine.PrefixCacheKey.sha256Utf8(previousSnapshot.systemPrompt)
                    ).distinct().takeLast(32)
            )
            session.copy(assistantSnapshot = revisedSnapshot)
        }
    } else state.chatSessions
    store.update {
        it.copy(
            assistants = updatedAssistants,
            selectedAssistantId = if (shouldSelectAssistant && existing != null) assistant.id else state.selectedAssistantId,
            params = if (shouldSelectAssistant && existing != null) updatedParams else state.params,
            chatSessions = updatedSessions,
            statusMessage = when {
                existing == null -> "已创建助手：${assistant.name}"
                applyToActiveConversation ->
                    "已更新助手：${assistant.name}；当前对话已应用新的角色卡。"
                else -> "已更新助手：${assistant.name}"
            }
        )
    }
    persistAssistantsAsync()
    if (existing != null) persistChatSessions(updatedSessions)
    if (applyToActiveConversation) {
        // The persona prefix changed. Do not allow a local runner to continue from the
        // previous role's KV cache, regardless of whether the assistant was newly created.
        markLocalConversationContextInvalid()
    }
    if (existing == null) {
        selectAssistant(assistant.id)
        persistChatSessions(null)
        showStatus("已创建助手：${assistant.name}")
    } else if (shouldSelectAssistant) {
        applyAssistantDefaultModel(assistant)
    }
    return true
}

internal fun deleteAssistantFor(
    store: UiStateWriter<AssistantProfileFields>,
    assistantStore: AssistantStore,
    modelGenerationProfileStore: ModelGenerationProfileStore,
    chatSessionStore: ChatSessionStore,
    application: Application,
    scope: CoroutineScope,
    rejectWhileConversationMutationInProgress: () -> Boolean,
    hasActiveAssistantImageExecution: (String?) -> Boolean,
    showStatus: (String) -> Unit,
    queueWorldBookCleanup: (WorldBookScope, Set<String>) -> Unit,
    cleanupPendingWorldBooksAfterOwnerCommit: (WorldBookScope, Set<String>) -> Unit,
    selectAssistant: (String) -> Unit,
    persistChatSessions: (List<ChatSessionRecord>?) -> Unit,
    chatAppearanceMutex: Mutex,
    cleanupUnusedChatBackgrounds: suspend () -> Unit,
    currentModelGenerationProfileKey: () -> String?,
    assistantId: String
) {
    val state = store.state.value
    if (rejectWhileConversationMutationInProgress()) return
    if (state.isGenerating || hasActiveAssistantImageExecution(state.activeChatSessionId)) {
        showStatus("请先结束当前角色请求，再删除助手。")
        return
    }
    if (assistantId == AssistantRecord.DEFAULT_ID || state.assistants.size <= 1) {
        showStatus("默认助手不能删除")
        return
    }
    val removed = state.assistants.firstOrNull { it.id == assistantId } ?: return
    val remaining = state.assistants.filterNot { it.id == assistantId }
    // A store holding only the deleted record would make a plain `first` on the remainder throw;
    // keep the default assistant as the fallback and skip the switch when nothing is left.
    val next = if (state.selectedAssistantId == assistantId) {
        remaining.firstOrNull { it.id == AssistantRecord.DEFAULT_ID } ?: remaining.firstOrNull()
    } else {
        remaining.firstOrNull { it.id == state.selectedAssistantId } ?: remaining.firstOrNull()
    }
    try {
        assistantStore.saveAssistants(remaining)
    } catch (error: Throwable) {
        store.update {
            it.copy(
                statusMessage = "删除助手失败，助手和关联世界书均未修改：${error.message ?: "存储错误"}"
            )
        }
        return
    }
    val deletingSelectedAssistant = state.selectedAssistantId == assistantId
    // With nothing left to select there is no consistent selection to publish; keep the stored id
    // so the next app start recreates the default assistant instead of pointing at a deleted one.
    val nextSelection = next
    if (deletingSelectedAssistant && nextSelection != null) {
        assistantStore.saveSelectedAssistantId(nextSelection.id)
    }
    val nextModelPreset = currentModelGenerationProfileKey()
        ?.let { key -> modelGenerationProfileStore.loadOrCreate(key, state.params) }
        ?: state.params
    val updatedParams = if (deletingSelectedAssistant && nextSelection != null) {
        nextSelection.toGenerationParams(nextModelPreset)
    } else {
        state.params
    }
    if (deletingSelectedAssistant && nextSelection != null) {
        GenerationPreferences.persistGenerationParams(application, updatedParams)
    }
    val updatedSessions = state.chatSessions
    store.update {
        it.copy(
            assistants = remaining,
            assistantMemories = it.assistantMemories.filterNot { memory ->
                memory.assistantId == assistantId
            },
            selectedAssistantId = if (deletingSelectedAssistant && nextSelection != null) {
                nextSelection.id
            } else {
                it.selectedAssistantId
            },
            params = updatedParams,
            chatSessions = updatedSessions,
            statusMessage = "已删除助手：${removed.name}"
        )
    }
    scope.launch(Dispatchers.IO) {
        runCatching { chatSessionStore.deleteAssistantMemoryData(assistantId) }
            .onFailure { error ->
                store.update {
                    it.copy(statusMessage = "助手已删除，但关联记忆清理失败：${error.message ?: "存储错误"}")
                }
            }
    }
    queueWorldBookCleanup(WorldBookScope.ASSISTANT, setOf(assistantId))
    // AssistantStore's canonical Room transaction has already committed.
    // The file-backed world-book cleanup is therefore safe to run now.
    cleanupPendingWorldBooksAfterOwnerCommit(
        WorldBookScope.ASSISTANT,
        remaining.mapTo(hashSetOf()) { it.id }
    )
    if (deletingSelectedAssistant && nextSelection != null) {
        selectAssistant(nextSelection.id)
        persistChatSessions(null)
    } else {
        persistChatSessions(updatedSessions)
    }
    scope.launch { chatAppearanceMutex.withLock { cleanupUnusedChatBackgrounds() } }
}

internal suspend fun finishCharacterCardImportFor(
    store: UiStateWriter<AssistantProfileFields>,
    assistantStore: AssistantStore,
    worldBookStore: WorldBookStore,
    chatSessionStore: ChatSessionStore,
    chatSessionPersistenceMutex: Mutex,
    chatSessionPersistenceSequence: AtomicLong,
    getPendingKnowledgeBindings: () -> Map<String, Set<String>>,
    setDurableChatSessions: (List<ChatSessionRecord>) -> Unit,
    chatSessionPersistenceStateLock: Any,
    stageContentImportPreview: suspend (ContentImportPreview) -> Unit,
    conversationMutationBarrierIsActive: () -> Boolean,
    hasActiveAssistantImageExecution: (String?) -> Boolean,
    currentOwnerIsCurrent: () -> Boolean,
    selectAssistant: (String) -> Unit,
    result: CharacterCardParseResult,
    owner: ContentImportOwner,
    commitId: String? = null
): Boolean {
    val success = result as? CharacterCardParseResult.Success ?: run {
        val failure = result as CharacterCardParseResult.Failure
        val nextStep = when (failure.error.code) {
            CharacterCardParseErrorCode.MISSING_CARD_METADATA ->
                "请确认选择的是带 chara/ccv3 角色卡元数据的 PNG，而不是普通图片。"
            CharacterCardParseErrorCode.INVALID_PNG,
            CharacterCardParseErrorCode.PNG_TOO_LARGE ->
                "请重新导出 PNG 角色卡，或改用同一角色卡的 JSON 文件。"
            CharacterCardParseErrorCode.INVALID_CARD_METADATA,
            CharacterCardParseErrorCode.CARD_METADATA_TOO_LARGE ->
                "请使用标准 Character Card v2/v3 导出文件；如果来自其他应用，请先重新导出。"
            CharacterCardParseErrorCode.INVALID_JSON,
            CharacterCardParseErrorCode.UNSUPPORTED_CARD,
            CharacterCardParseErrorCode.MISSING_CARD_DATA ->
                "请导入标准角色卡 JSON（chara_card_v2/chara_card_v3）后重试。"
            CharacterCardParseErrorCode.IO_ERROR ->
                "请重新选择文件并允许文件读取权限。"
            else -> "请换用标准 PNG/JSON 角色卡后重试。"
        }
        store.update {
            it.copy(statusMessage = "角色卡导入失败：${failure.error.message} 下一步：$nextStep")
        }
        return false
    }
    if (commitId == null) {
        stageContentImportPreview(characterCardPreview(success, owner))
        return true
    }
    val state = store.state.value
    val imported = success.card.toAssistantRecord()
    val now = System.currentTimeMillis()
    val assistant = imported.copy(
        id = commitId,
        name = imported.name.ifBlank { "导入助手" }.take(36),
        systemPrompt = imported.systemPrompt.ifBlank { characterCardFallbackSystemPrompt(imported.name) },
        createdAt = now,
        updatedAt = now
    )
    val embeddedWorldBookImport = parseEmbeddedCharacterBook(success.card, assistant.id).let { parsed ->
        parsed.copy(book = parsed.book?.copy(id = "$commitId:world-book"))
    }
    val updatedAssistants = state.assistants.filterNot { it.id == assistant.id } + assistant
    try {
        withContext(Dispatchers.IO) { assistantStore.saveAssistants(updatedAssistants) }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        store.update {
            it.copy(statusMessage = "角色卡导入失败：${error.message ?: "无法保存角色"}")
        }
        return false
    }
    var updatedWorldBooks = state.worldBooks
    var embeddedWorldBookSaveError: String? = null
    embeddedWorldBookImport.book?.let { book ->
        try {
            updatedWorldBooks = withContext(Dispatchers.IO) { worldBookStore.upsert(book) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            embeddedWorldBookSaveError = error.message?.trim()?.takeIf(String::isNotBlank)
                ?: "无法保存到本机"
        }
    }
    val currentState = store.state.value
    val selectImportedAssistant = currentOwnerIsCurrent() &&
        !currentState.isGenerating && !conversationMutationBarrierIsActive() &&
        !hasActiveAssistantImageExecution(currentState.activeChatSessionId)
    val updatedSessions = currentState.chatSessions
    store.update {
        it.copy(
            assistants = it.assistants.filterNot { record -> record.id == assistant.id } + assistant,
            chatSessions = updatedSessions,
            worldBooks = updatedWorldBooks,
            statusMessage = buildString {
                append("已导入")
                append(if (success.source == CharacterCardSource.JSON) " JSON " else " PNG ")
                append("角色卡：")
                append(assistant.name)
                if (!selectImportedAssistant) {
                    append("；当前对话未切换角色")
                }
                append(embeddedCharacterBookStatusSuffix(embeddedWorldBookImport, embeddedWorldBookSaveError))
            }
        )
    }
    if (selectImportedAssistant) {
        val importStatus = store.state.value.statusMessage
        try {
            selectAssistant(assistant.id)
            if (store.state.value.selectedAssistantId == assistant.id &&
                store.state.value.activeChatSessionId != currentState.activeChatSessionId) {
                store.update { it.copy(statusMessage = importStatus) }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            store.update {
                it.copy(statusMessage = "$importStatus；角色卡已保存，自动切换会话失败：${error.message ?: "请手动选择角色卡"}")
            }
        }
    }
    val createdSessionId = store.state.value.activeChatSessionId?.takeIf { activeId ->
        currentState.chatSessions.none { it.id == activeId }
    }
    if (createdSessionId != null) {
        try {
            chatSessionPersistenceSequence.incrementAndGet()
            withContext(Dispatchers.IO) {
                chatSessionPersistenceMutex.withLock {
                    val snapshot = store.state.value.chatSessions
                    val liveIds = snapshot.mapTo(hashSetOf()) { it.id }
                    val bindings = synchronized(chatSessionPersistenceStateLock) {
                        getPendingKnowledgeBindings().filterKeys { it in liveIds }.mapValues { (_, ids) -> ids.toSet() }
                    }
                    chatSessionStore.save(snapshot, bindings)
                    setDurableChatSessions(snapshot)
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            store.update {
                it.copy(statusMessage = "${it.statusMessage.orEmpty()}；角色卡已保存，但新会话保存失败：${error.message ?: "存储错误"}")
            }
        }
    }
    // Import success depends on the role and its book, not on optional chat navigation.
    return embeddedWorldBookSaveError == null
}
