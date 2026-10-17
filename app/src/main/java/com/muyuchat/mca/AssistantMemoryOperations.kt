package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelRuntimeIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

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
