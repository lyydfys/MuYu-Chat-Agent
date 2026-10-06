package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex

/**
 * R3/C3-S4（插队裁定 3）：会话持久化状态自 MainViewModel 迁出、由组合根持有
 * （初值来自 VM 首段装配的两段式初始化；VM 以 get/set 别名共享同一实例，读写语义不变）。
 */
internal class ChatSessionPersistenceState(
    initialDurableSessions: List<ChatSessionRecord>
) {
    
    var durableChatSessions: List<ChatSessionRecord> = initialDurableSessions

    val persistenceMutex = Mutex()

    val persistenceSequence = AtomicLong(0L)

    val stateLock = Any()

    val pendingKnowledgeBindings = linkedMapOf<String, Set<String>>()

    // R3/C3-S11：world-book 延迟清理队列（自 VM 迁出，随持久化状态同址）。
    val pendingWorldBookCleanupOwners =
        linkedMapOf<WorldBookScope, MutableSet<String>>()
}

/** R3/C3-S4：`List<ChatSessionRecord>.upsertSession` 类成员体顶层化（体逐字等价；`_uiState.value.assistants` → `state.assistants` 参数化）。 */
internal fun List<ChatSessionRecord>.upsertSessionFor(
    state: MainUiState,
    sessionId: String,
        messages: List<ChatMessage>,
        assistantId: String? = null,
        assistantSnapshot: AssistantConversationSnapshot? = null,
        replaceAssistantSnapshot: Boolean = false,
        modelMode: String? = null,
        modelId: String? = null
    ): List<ChatSessionRecord> {
        if (messages.isEmpty()) return this
        val existing = firstOrNull { it.id == sessionId }
        val existingOwner = existing?.assistantSnapshot?.assistantId ?: existing?.assistantId
        if (existing != null && (existing.mixedAssistantHistory ||
                (assistantId != null && existingOwner != assistantId) ||
                (assistantSnapshot != null && assistantSnapshot.assistantId != existingOwner))) return this
        val capturedSnapshot = existing?.assistantSnapshot ?: assistantSnapshot
            ?: assistantId?.let { owner ->
                state.assistants.firstOrNull { it.id == owner }?.toConversationSnapshot()
            }
        val record = ChatSessionRecord(
            id = sessionId,
            title = if (existing?.manualTitle == true) existing.title else messages.chatTitle(),
            messages = messages,
            pinned = existing?.pinned ?: false,
            manualTitle = existing?.manualTitle ?: false,
            updatedAt = System.currentTimeMillis(),
            projectId = existing?.projectId,
            assistantId = existing?.assistantId ?: assistantId,
            assistantSnapshot = if (replaceAssistantSnapshot) {
                assistantSnapshot ?: capturedSnapshot
            } else {
                capturedSnapshot
            },
            mixedAssistantHistory = existing?.mixedAssistantHistory ?: false,
            modelMode = modelMode ?: existing?.modelMode,
            modelId = if (modelMode != null) modelId else existing?.modelId,
            appearanceOverride = existing?.appearanceOverride,
            contextSummaries = existing?.contextSummaries.orEmpty()
        )
        return (listOf(record) + filterNot { it.id == sessionId }).sortedForHistory()
    }

/** R3/C3-S5：`MainUiState.currentChatModelId()` 顶层化（纯函数，体逐字等价）。 */
internal fun MainUiState.currentChatModelId(): String? =
    when (selectedChatBackend) {
            ChatBackend.LOCAL -> loadedModelId
            ChatBackend.CLOUD -> selectedCloudChatModelId
        }

/** R3/C3-S11：`reconciledChatSessionsAfterSave` 顶层化（体逐字等价；持久化状态/会话仓参数化）。 */
internal fun reconciledChatSessionsAfterSaveFor(
    persistenceState: ChatSessionPersistenceState,
    chatSessionStore: ChatSessionStore,
    snapshot: List<ChatSessionRecord>
): List<ChatSessionRecord> {
        val mixedIds = runCatching { chatSessionStore.mixedSessionIds() }.getOrNull() ?: return snapshot
        val currentById = snapshot.associateBy { it.id }
        val durableById = persistenceState.durableChatSessions.associateBy { it.id }
        if (mixedIds.none { id ->
                currentById[id]?.mixedAssistantHistory != true || currentById[id] != durableById[id]
            }) return snapshot
        val persisted = chatSessionStore.load()
        return persisted.takeIf { sessions ->
            mixedIds.all { id -> sessions.any { it.id == id && it.mixedAssistantHistory } }
        } ?: snapshot
}

/** R3/C3-S11：`publishReconciledChatSessions` 顶层化（体逐字等价；store 参数化）。 */
internal fun publishReconciledChatSessionsInto(
    store: UiStateStore,
    chatRuns: ChatRunOwnership,
    requested: List<ChatSessionRecord>,
    persisted: List<ChatSessionRecord>
) {
        if (requested == persisted) return
        val requestedById = requested.associateBy { it.id }
        val correctedById = persisted.filter { it.mixedAssistantHistory }.associateBy { it.id }
        var activeCorrection = false
        store.update { current ->
            val corrected = current.chatSessions.map { session ->
                correctedById[session.id]?.takeIf { requestedById[session.id] == session } ?: session
            }
            if (corrected == current.chatSessions) current else {
                val activeBefore = current.chatSessions.firstOrNull { it.id == current.activeChatSessionId }
                val activeAfter = corrected.firstOrNull { it.id == current.activeChatSessionId }
                val activeWasCorrected = activeAfter != activeBefore
                if (activeWasCorrected) activeCorrection = true
                current.copy(
                    chatSessions = corrected,
                    messages = if (activeWasCorrected) activeAfter?.messages.orEmpty() else current.messages,
                    isGenerating = if (activeWasCorrected) false else current.isGenerating,
                    generationPhase = if (activeWasCorrected) null else current.generationPhase,
                    generationTokenProgress = if (activeWasCorrected) null else current.generationTokenProgress,
                    generationPersistProgress = if (activeWasCorrected) null else current.generationPersistProgress,
                    statusMessage = if (activeWasCorrected) {
                        "会话角色归属不明确，原始消息已保留；可从历史菜单创建角色分支。"
                    } else current.statusMessage
                )
            }
        }
        if (activeCorrection) chatRuns.cancelCurrentAndCancelOwner()
}

/** R3/C3-S11：`cleanupPendingWorldBooksAfterOwnerCommit` 顶层化（体逐字等价）。 */
internal fun cleanupPendingWorldBooksAfterOwnerCommitFor(
    persistenceState: ChatSessionPersistenceState,
    worldBookStore: WorldBookStore,
    store: UiStateStore,
    scope: WorldBookScope,
    retainedOwnerIds: Set<String>
) {
        val ownersToRemove = synchronized(persistenceState.stateLock) {
            persistenceState.pendingWorldBookCleanupOwners[scope]
                ?.filterNotTo(linkedSetOf()) { it in retainedOwnerIds }
                .orEmpty()
        }
        if (ownersToRemove.isEmpty()) return
        runCatching {
            worldBookStore.removeScopedOwners(scope, ownersToRemove)
        }.onSuccess { updatedWorldBooks ->
            synchronized(persistenceState.stateLock) {
                persistenceState.pendingWorldBookCleanupOwners[scope]?.let { pending ->
                    pending.removeAll(ownersToRemove)
                    if (pending.isEmpty()) persistenceState.pendingWorldBookCleanupOwners.remove(scope)
                }
            }
            store.update { it.copy(worldBooks = updatedWorldBooks) }
        }.onFailure { error ->
            store.update {
                it.copy(
                    statusMessage = "主体数据已保存，但关联世界书清理失败，原数据已保留：${error.message ?: "存储错误"}"
                )
            }
        }
}

/** R3/C3-S11：`cancelPendingWorldBookCleanup` 顶层化（体逐字等价）。 */
internal fun cancelPendingWorldBookCleanupIn(
    persistenceState: ChatSessionPersistenceState,
    scope: WorldBookScope,
    ownerIds: Set<String>
) {
        if (ownerIds.isEmpty()) return
        synchronized(persistenceState.stateLock) {
            persistenceState.pendingWorldBookCleanupOwners[scope]?.let { pending ->
                pending.removeAll(ownerIds)
                if (pending.isEmpty()) persistenceState.pendingWorldBookCleanupOwners.remove(scope)
            }
        }
}
