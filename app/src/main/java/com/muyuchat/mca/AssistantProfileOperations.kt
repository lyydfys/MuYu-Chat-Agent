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
    assistantGenerationParamsFromJson(paramsJson, defaults, systemPrompt)

internal fun stageDirectParameterProfileFor(
    store: UiStateStore,
    scope: CoroutineScope,
    engine: McaInferenceService,
    runtimeProfileStore: ModelRuntimeProfileStore,
    activeRuntimeIdentity: () -> ModelRuntimeIdentity?,
    runtimeUserOverrideFields: Set<String>,
    directParameterStageGeneration: AtomicLong,
    getActiveDirectParameterStageJob: () -> Job?,
    setActiveDirectParameterStageJob: (Job?) -> Unit,
    directParameterStageMutex: Mutex,
    adaptiveTuningJobIsActive: () -> Boolean,
    getActiveTuningJobId: () -> String?,
    getPendingProfileTransactionId: () -> String?,
    setPendingProfileTransactionId: (String?) -> Unit,
    params: GenerationParams
) {
    val identity = activeRuntimeIdentity() ?: return
    val active = engine.activeExecutionProfile() ?: return
    if (store.state.value.loadedModelId == null || active.runtimeIdentity.identityHash != identity.identityHash) return
    val stageGeneration = directParameterStageGeneration.incrementAndGet()
    getActiveDirectParameterStageJob()?.cancel()
    val job = scope.launch(Dispatchers.IO) {
        delay(120L)
        directParameterStageMutex.withLock {
            withContext(NonCancellable) stage@{
                if (stageGeneration != directParameterStageGeneration.get()) return@stage
                if (adaptiveTuningJobIsActive() || getActiveTuningJobId() != null) {
                    store.update {
                        it.copy(statusMessage = "当前调优任务尚未结束；完成或取消后再应用自定义运行参数。")
                    }
                    return@stage
                }
                val latestParams = store.state.value.params
                if (latestParams != params) return@stage
                runCatching {
                    val nextRevision = nextExecutionProfileRevision(
                        committedRevision = active.revision,
                        persistedRevisions = runtimeProfileStore.profiles(identity.identityHash).map { it.revision }
                    )
                    val requested = engine.resolveExecutionProfile(
                        identity = identity,
                        requestedParamsJson = latestParams.toJson(),
                        profileId = "manual-${UUID.randomUUID().toString().replace("-", "").take(20)}",
                        revision = nextRevision
                    ).profile
                    val candidate = mergeUserRequestedIntoProfile(
                        base = active,
                        requested = requested,
                        authoritativeFields = runtimeUserOverrideFields
                    )
                    if (candidate.resolvedLoadSignature.digest == active.resolvedLoadSignature.digest &&
                        candidate.committedExecutionSignature.digest == active.committedExecutionSignature.digest
                    ) {
                        setPendingProfileTransactionId(null)
                        store.update {
                            it.copy(
                                reloadRequired = false,
                                pendingProfile = null,
                                statusMessage = "自定义参数与当前生效配置一致。"
                            )
                        }
                        return@runCatching
                    }

                    runtimeProfileStore.pendingTransaction(identity.identityHash)?.let { existing ->
                        if (existing.journal.transactionId != getPendingProfileTransactionId()) {
                            error("已有调优候选等待处理，请先应用、回滚或取消该候选。")
                        }
                        val recovery = runtimeProfileStore.rejectCandidate(
                            transactionId = existing.journal.transactionId,
                            failureStage = "USER_EDIT_REPLACED",
                            failureCode = "SUPERSEDED",
                            failureSummary = "用户继续修改运行参数，旧候选已被新候选替换。"
                        )
                        runtimeProfileStore.completeRecovery(
                            transactionId = existing.journal.transactionId,
                            restoredProfileId = recovery.rollbackProfileId
                        )
                    }

                    val transactionId = "manual-${UUID.randomUUID()}"
                    runtimeProfileStore.stageCandidate(
                        snapshot = candidate.toPersistedExecutionProfileSnapshot(
                            parentCommittedProfileId = active.profileId,
                            verificationLevel = PersistedProfileVerificationLevel.SAFE,
                            sourceSummaryJson = JSONObject()
                                .put("kind", "user_parameter_edit")
                                .put("authoritativeFields", JSONArray(runtimeUserOverrideFields.sorted()))
                                .toString()
                        ),
                        transactionId = transactionId,
                        rollbackTargetProfileId = active.profileId
                    )
                    if (stageGeneration != directParameterStageGeneration.get() || store.state.value.params != params) {
                        val recovery = runtimeProfileStore.rejectCandidate(
                            transactionId = transactionId,
                            failureStage = "USER_EDIT_SUPERSEDED",
                            failureCode = "SUPERSEDED",
                            failureSummary = "候选建立期间用户再次修改参数，失效候选已撤销。"
                        )
                        runtimeProfileStore.completeRecovery(transactionId, recovery.rollbackProfileId)
                        return@runCatching
                    }
                    setPendingProfileTransactionId(transactionId)
                    val currentCtx = active.resolvedLoadBoundValues.toJsonObject().optInt("n_ctx", store.state.value.stats.nCtx)
                    val pendingCtx = candidate.resolvedLoadBoundValues.toJsonObject().optInt("n_ctx", latestParams.nCtx)
                    store.update {
                        it.copy(
                            reloadRequired = true,
                            pendingProfile = AgentPendingProfile(
                                profileId = candidate.profileId,
                                revision = candidate.revision,
                                summary = "当前 n_ctx=$currentCtx · 待应用 n_ctx=$pendingCtx",
                                readyToApply = true
                            ),
                            tuningJobState = AgentTuningJobState.PAUSED,
                            tuningPhase = "自定义参数等待应用",
                            statusMessage = "上下文已修改为 $pendingCtx，需要重新加载后生效；当前聊天仍使用 $currentCtx。"
                        )
                    }
                }.onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    store.update {
                        it.copy(
                            pendingProfile = null,
                            reloadRequired = true,
                            statusMessage = "自定义参数候选建立失败：${error.message ?: "未知错误"}"
                        )
                    }
                }
            }
        }
    }
    setActiveDirectParameterStageJob(job)
}

internal fun saveAssistantProfileFor(
    store: UiStateStore,
    assistantStore: AssistantStore,
    application: Application,
    rejectWhileConversationMutationInProgress: () -> Boolean,
    hasActiveAssistantImageExecution: (String?) -> Boolean,
    showStatus: (String) -> Unit,
    persistModelGenerationParams: (MainUiState, GenerationParams) -> Unit,
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
    fileContextEnabled: Boolean
) {
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
        return
    }
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
        paramsJson = state.params.copy(
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
        ).toAssistantGenerationJson()
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
    val updatedParams = assistant.toGenerationParams(state.params)
    if (shouldSelectAssistant && existing != null) {
        GenerationPreferences.persistGenerationParams(application, updatedParams)
        // Editing the active assistant is an explicit user change for the
        // currently selected model; retain it in that model's profile.
        persistModelGenerationParams(state, updatedParams)
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
}

internal fun deleteAssistantFor(
    store: UiStateStore,
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
    val next = if (state.selectedAssistantId == assistantId) {
        remaining.firstOrNull { it.id == AssistantRecord.DEFAULT_ID } ?: remaining.first()
    } else {
        remaining.firstOrNull { it.id == state.selectedAssistantId } ?: remaining.first()
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
    if (deletingSelectedAssistant) assistantStore.saveSelectedAssistantId(next.id)
    val nextAssistantParams = next.toGenerationParams(state.params)
    val updatedParams = if (deletingSelectedAssistant) currentModelGenerationProfileKey()
        ?.let { key -> modelGenerationProfileStore.loadOrCreate(key, nextAssistantParams) }
        ?: nextAssistantParams else state.params
    if (deletingSelectedAssistant) GenerationPreferences.persistGenerationParams(application, updatedParams)
    val updatedSessions = state.chatSessions
    store.update {
        it.copy(
            assistants = remaining,
            assistantMemories = it.assistantMemories.filterNot { memory ->
                memory.assistantId == assistantId
            },
            selectedAssistantId = if (deletingSelectedAssistant) next.id else it.selectedAssistantId,
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
    if (deletingSelectedAssistant) {
        selectAssistant(next.id)
        persistChatSessions(null)
    } else {
        persistChatSessions(updatedSessions)
    }
    scope.launch { chatAppearanceMutex.withLock { cleanupUnusedChatBackgrounds() } }
}

internal suspend fun finishCharacterCardImportFor(
    store: UiStateStore,
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

internal suspend fun summarizeCharacterMemoryFor(
    store: UiStateStore,
    chatSessionStore: ChatSessionStore,
    cloudChatProvider: OpenAiCompatibleChatProvider,
    engine: McaInferenceService,
    chatSessionPersistenceMutex: Mutex,
    activeRuntimeIdentity: () -> ModelRuntimeIdentity?,
    markLocalConversationContextInvalid: () -> Unit,
    onLocalConversationContextNeedsInvalidation: () -> Unit,
    recordReschedule: (String) -> Unit,
    assistantId: String,
    backend: ChatBackend,
    params: GenerationParams,
    cloudConfig: CloudApiConfig?,
    localIdentity: String?
) {
    val selected = store.state.value.assistants.firstOrNull { it.id == assistantId && it.memoryEnabled }
        ?: return
    var pending = chatSessionStore.loadMemoryTurns(assistantId)
    var remaining = pending.size
    if (remaining < selected.memorySummaryInterval.coerceIn(2, 100)) return
    while (remaining > 0 && pending.isNotEmpty()) {
        val previousAuto = chatSessionStore.loadMemories(assistantId)
            .firstOrNull { it.source == "automatic" || it.source == "auto" }
        val prepared = listOf(24, 12, 6, 3, 1)
            .map { minOf(it, remaining, pending.size) }
            .distinct()
            .firstNotNullOfOrNull { size ->
                val turns = pending.take(size)
                buildCharacterMemorySummaryRequest(turns, previousAuto, params)
                    ?.let { turns to it }
            } ?: return
        val (batch, request) = prepared
        fun stillCurrent(): Boolean = store.state.value.assistants.any {
            it.id == assistantId && it.memoryEnabled
        } && (backend != ChatBackend.LOCAL ||
            localIdentity != null && activeRuntimeIdentity()?.identityHash == localIdentity)
        if (!stillCurrent()) return
        val raw = try {
            withTimeoutOrNull(30_000L) {
                if (backend == ChatBackend.CLOUD) {
                    val config = cloudConfig ?: return@withTimeoutOrNull null
                    collectContextSummary(cloudChatProvider.streamChat(config, request), ::stillCurrent)
                } else {
                    withContext(Dispatchers.IO) {
                        val lease = engine.acquireExclusiveLifecycleLease(stopActiveGeneration = false)
                        try {
                            if (!stillCurrent()) return@withContext null
                            collectContextSummary(
                                engine.streamChat(
                                    request,
                                    LocalChatExecutionContext(
                                        requestId = "character-memory-${UUID.randomUUID()}",
                                        lifecycleLease = lease,
                                        includeDeviceClockContext = false
                                    )
                                ),
                                ::stillCurrent
                            )
                        } finally {
                            onLocalConversationContextNeedsInvalidation()
                            withContext(NonCancellable) { lease.release() }
                        }
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        } ?: return
        val validated = validateCharacterMemorySummary(raw, batch, previousAuto) ?: return
        if (!stillCurrent()) return
        val currentAuto = chatSessionStore.loadMemories(assistantId)
            .firstOrNull { it.source == "automatic" || it.source == "auto" }
        if (currentAuto != previousAuto) return
        val summary = MemoryRecord(
            assistantId = assistantId,
            scope = "auto_summary",
            content = validated,
            source = "automatic"
        )
        val committed = chatSessionPersistenceMutex.withLock {
            if (!stillCurrent() || batch.any { turn ->
                    !store.state.value.chatSessions.containsCompletedMemoryTurn(turn)
                }) false
            else chatSessionStore.commitMemorySummary(assistantId, batch.map { it.id }, summary)
        }
        if (!committed) return
        val refreshed = chatSessionStore.loadMemories(assistantId)
        store.update { state ->
            state.copy(assistantMemories = state.assistantMemories.filterNot {
                it.assistantId == assistantId
            } + refreshed)
        }
        markLocalConversationContextInvalid()
        remaining -= batch.size
        pending = chatSessionStore.loadMemoryTurns(assistantId)
    }
    val interval = store.state.value.assistants.firstOrNull { it.id == assistantId && it.memoryEnabled }
        ?.memorySummaryInterval?.coerceIn(2, 100) ?: return
    if (pending.size >= interval) {
        recordReschedule(assistantId)
    }
}

internal fun upsertAssistantMemoryFor(
    store: UiStateStore,
    scope: CoroutineScope,
    chatSessionStore: ChatSessionStore,
    showStatus: (String) -> Unit,
    markLocalConversationContextInvalid: () -> Unit,
    assistantId: String,
    id: String?,
    scopeKind: String,
    content: String
) {
    val state = store.state.value
    if (state.assistants.none { it.id == assistantId }) return
    if (scopeKind !in setOf("user_profile", "role_progress")) return
    val cleanContent = content.trim().take(4_096)
    if (cleanContent.isBlank()) return
    val existing = id?.let { memoryId ->
        state.assistantMemories.firstOrNull { it.id == memoryId && it.assistantId == assistantId }
    }
    if (id != null && existing == null) return
    val record = MemoryRecord(
        id = existing?.id ?: UUID.randomUUID().toString(),
        assistantId = assistantId,
        scope = scopeKind,
        content = cleanContent,
        source = "manual",
        createdAt = existing?.createdAt ?: System.currentTimeMillis()
    )
    scope.launch(Dispatchers.IO) {
        try {
            check(chatSessionStore.upsertMemory(record)) { "记忆与角色不匹配" }
            val refreshed = chatSessionStore.loadMemories(assistantId)
            store.update { current ->
                current.copy(
                    assistantMemories = current.assistantMemories.filterNot { it.assistantId == assistantId } + refreshed,
                    statusMessage = "角色记忆已保存"
                )
            }
            markLocalConversationContextInvalid()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            showStatus("保存角色记忆失败：${error.message ?: "存储错误"}")
        }
    }
}

internal fun deleteAssistantMemoryFor(
    store: UiStateStore,
    scope: CoroutineScope,
    chatSessionStore: ChatSessionStore,
    showStatus: (String) -> Unit,
    markLocalConversationContextInvalid: () -> Unit,
    assistantId: String,
    id: String
) {
    if (store.state.value.assistantMemories.none { it.id == id && it.assistantId == assistantId }) return
    scope.launch(Dispatchers.IO) {
        try {
            check(chatSessionStore.deleteMemory(id, assistantId)) { "记忆不存在或不属于该角色" }
            store.update { current ->
                current.copy(
                    assistantMemories = current.assistantMemories.filterNot {
                        it.id == id && it.assistantId == assistantId
                    },
                    statusMessage = "角色记忆已删除"
                )
            }
            markLocalConversationContextInvalid()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            showStatus("删除角色记忆失败：${error.message ?: "存储错误"}")
        }
    }
}

internal fun recordCompletedCharacterMemoryTurnFor(
    store: UiStateStore,
    scope: CoroutineScope,
    chatSessionStore: ChatSessionStore,
    scheduleCharacterMemorySummary: (String, ChatBackend, GenerationParams, CloudApiConfig?, String?) -> Unit,
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
) {
    if (user.content.isBlank() || assistant.content.isBlank()) return
    val turn = AssistantMemoryTurnRecord(
        id = assistant.id,
        assistantId = assistantId,
        sessionId = sessionId,
        userText = user.content,
        assistantText = assistant.content,
        createdAt = assistant.createdAt
    )
    scope.launch(Dispatchers.IO) {
        conversationPersistJob?.join()
        val state = store.state.value
        if (state.assistants.none { it.id == assistantId && it.memoryEnabled } ||
            state.chatSessions.none { it.id == sessionId && it.assistantSnapshot == assistantSnapshot } ||
            !state.chatSessions.containsCompletedMemoryTurn(turn)) return@launch
        val inserted = runCatching { chatSessionStore.appendMemoryTurn(turn) }.getOrDefault(false)
        if (inserted) {
            if (store.state.value.assistants.none { it.id == assistantId }) {
                chatSessionStore.deleteAssistantMemoryData(assistantId)
            } else if (store.state.value.chatSessions.containsCompletedMemoryTurn(turn)) {
                scheduleCharacterMemorySummary(assistantId, backend, params, cloudConfig, localIdentity)
            }
        }
    }
}

internal fun scheduleCharacterMemorySummaryFor(
    scope: CoroutineScope,
    characterMemorySummaryJobs: MutableMap<String, Job>,
    characterMemorySummaryReschedule: MutableSet<String>,
    summarizeCharacterMemory: suspend (String, ChatBackend, GenerationParams, CloudApiConfig?, String?) -> Unit,
    assistantId: String,
    backend: ChatBackend,
    params: GenerationParams,
    cloudConfig: CloudApiConfig?,
    localIdentity: String?
) {
    synchronized(characterMemorySummaryJobs) {
        if (characterMemorySummaryJobs[assistantId]?.isActive == true) {
            characterMemorySummaryReschedule.add(assistantId)
            return
        }
        lateinit var job: Job
        job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            summarizeCharacterMemory(assistantId, backend, params, cloudConfig, localIdentity)
        }
        characterMemorySummaryJobs[assistantId] = job
        job.invokeOnCompletion {
            val reschedule = synchronized(characterMemorySummaryJobs) {
                if (characterMemorySummaryJobs[assistantId] === job) {
                    characterMemorySummaryJobs.remove(assistantId)
                }
                characterMemorySummaryReschedule.remove(assistantId)
            }
            if (reschedule) {
                scheduleCharacterMemorySummaryFor(
                    scope = scope,
                    characterMemorySummaryJobs = characterMemorySummaryJobs,
                    characterMemorySummaryReschedule = characterMemorySummaryReschedule,
                    summarizeCharacterMemory = summarizeCharacterMemory,
                    assistantId = assistantId,
                    backend = backend,
                    params = params,
                    cloudConfig = cloudConfig,
                    localIdentity = localIdentity
                )
            }
        }
        job.start()
    }
}

internal class CharacterMemorySummaryCoordinator(
    private val jobs: MutableMap<String, Job> = mutableMapOf(),
    private val reschedule: MutableSet<String> = mutableSetOf()
) {
    fun schedule(
        scope: CoroutineScope,
        summarizeCharacterMemory: suspend (String, ChatBackend, GenerationParams, CloudApiConfig?, String?) -> Unit,
        assistantId: String,
        backend: ChatBackend,
        params: GenerationParams,
        cloudConfig: CloudApiConfig?,
        localIdentity: String?
    ) {
        scheduleCharacterMemorySummaryFor(
            scope = scope,
            characterMemorySummaryJobs = jobs,
            characterMemorySummaryReschedule = reschedule,
            summarizeCharacterMemory = summarizeCharacterMemory,
            assistantId = assistantId,
            backend = backend,
            params = params,
            cloudConfig = cloudConfig,
            localIdentity = localIdentity
        )
    }

    fun recordReschedule(id: String) {
        synchronized(jobs) {
            reschedule.add(id)
        }
    }
}

internal fun updatedAssistantsWithParams(
    assistants: List<AssistantRecord>,
    selectedAssistantId: String?,
    params: GenerationParams
): List<AssistantRecord> = assistants.map { assistant ->
    if (assistant.id == selectedAssistantId) {
        assistant.copy(
            paramsJson = params.toAssistantGenerationJson()
                .withAssistantPromptProvenance(assistant.systemPromptProvenance),
            updatedAt = System.currentTimeMillis()
        )
    } else {
        assistant
    }
}

internal fun applyAssistantDefaultModelFor(
    store: UiStateStore,
    showStatus: (String) -> Unit,
    selectCloudChatModel: (String) -> Unit,
    loadModel: (ModelManifest) -> Unit,
    assistant: AssistantRecord
) {
    val modelId = assistant.defaultModelId?.trim().orEmpty()
    val state = store.state.value
    when (assistant.defaultModelMode.normalizedAssistantModelMode()) {
        ASSISTANT_MODEL_MODE_CLOUD -> {
            if (modelId.isBlank()) {
                showStatus("已切换助手：${assistant.name}，但该助手未绑定云端模型。")
                return
            }
            val model = state.cloudModels.firstOrNull { it.id == modelId && it.kind == CloudModelKind.CHAT }
            if (model == null) {
                showStatus("已切换助手：${assistant.name}，但绑定的云端模型已不可用。")
                return
            }
            selectCloudChatModel(model.id)
        }
        ASSISTANT_MODEL_MODE_LOCAL -> {
            if (modelId.isBlank()) {
                showStatus("已切换助手：${assistant.name}，但该助手未绑定本地模型。")
                return
            }
            val model = state.models.firstOrNull { it.id == modelId }
            if (model == null) {
                showStatus("已切换助手：${assistant.name}，但绑定的本地模型已不可用。")
                return
            }
            if (state.selectedChatBackend != ChatBackend.LOCAL || state.loadedModelId != model.id) {
                loadModel(model)
            }
        }
    }
}

internal fun finishSelectingNewAssistantFor(
    store: UiStateStore,
    assistantStore: AssistantStore,
    modelGenerationProfileStore: ModelGenerationProfileStore,
    application: Application,
    invalidatePendingChatImagePreparation: () -> Unit,
    contentImportSelectionRevision: AtomicLong,
    markLocalConversationContextInvalid: () -> Unit,
    applyAssistantDefaultModel: (AssistantRecord) -> Unit,
    currentModelGenerationProfileKey: () -> String?,
    assistant: AssistantRecord
) {
    invalidatePendingChatImagePreparation()
    contentImportSelectionRevision.incrementAndGet()
    assistantStore.saveSelectedAssistantId(assistant.id)
    val current = store.state.value
    val assistantParams = assistant.toGenerationParams(current.params)
    val updatedParams = currentModelGenerationProfileKey()
        ?.let { key -> modelGenerationProfileStore.loadOrCreate(key, assistantParams) }
        ?: assistantParams
    GenerationPreferences.persistGenerationParams(application, updatedParams)
    store.update {
        it.copy(
            selectedAssistantId = assistant.id,
            params = updatedParams,
            statusMessage = "已切换助手：${assistant.name}"
        )
    }
    markLocalConversationContextInvalid()
    applyAssistantDefaultModel(assistant)
}

internal fun importAssistantCardFor(
    store: UiStateStore,
    scope: CoroutineScope,
    showStatus: (String) -> Unit,
    captureContentImportOwner: () -> ContentImportOwner,
    finishCharacterCardImport: suspend (CharacterCardParseResult, ContentImportOwner) -> Boolean,
    rawJson: String
) {
    if (store.state.value.isGenerating) {
        showStatus("请先停止当前生成，再导入角色卡")
        return
    }
    val owner = captureContentImportOwner()
    scope.launch {
        val result = withContext(Dispatchers.Default) {
            CharacterCardCodec.parseJson(rawJson)
        }
        finishCharacterCardImport(result, owner)
    }
}

internal fun importAssistantCardFileFor(
    store: UiStateStore,
    scope: CoroutineScope,
    application: Application,
    showStatus: (String) -> Unit,
    captureContentImportOwner: () -> ContentImportOwner,
    finishCharacterCardImport: suspend (CharacterCardParseResult, ContentImportOwner) -> Boolean,
    uriString: String,
    importOwner: ContentImportOwner? = null
) {
    if (store.state.value.isGenerating) {
        showStatus("请先停止当前生成，再导入角色卡")
        return
    }
    val owner = importOwner ?: captureContentImportOwner()
    scope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriString)
                val stream = application.contentResolver.openInputStream(uri)
                    ?: error("无法打开所选文件")
                stream.use(CharacterCardCodec::parse)
            }.getOrElse { error ->
                CharacterCardParseResult.Failure(
                    CharacterCardParseError(
                        code = CharacterCardParseErrorCode.IO_ERROR,
                        message = error.message ?: "无法读取所选文件",
                        cause = error
                    )
                )
            }
        }
        finishCharacterCardImport(result, owner)
    }
}


