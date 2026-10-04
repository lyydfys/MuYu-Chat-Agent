package com.muyuchat.mca

import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.mca.presentation.restoreAfterConversationMutationFailure
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The session-persistence owner: the single write path for chat sessions (P4). The body below is
 * the verbatim successor of MainViewModel.persistChatSessions -- the run-id gates, the coalescing
 * sequence check, the pending-knowledge-binding handoff and the post-commit world-book cleanup are
 * unchanged; the only rewrites are _uiState->uiState, viewModelScope->port.scope and port-qualified
 * collaborator calls. MainViewModel keeps a same-signature delegate so no call site changed.
 */
internal interface ChatSessionPersistencePort {
    val uiState: MutableStateFlow<MainUiState>
    val scope: CoroutineScope
    val chatRuns: ChatRunOwnership
    val chatSessionStore: ChatSessionStore
    val chatSessionPersistenceMutex: Mutex
    val chatSessionPersistenceSequence: AtomicLong
    val chatSessionPersistenceStateLock: Any
    val pendingKnowledgeBindings: MutableMap<String, Set<String>>
    var durableChatSessions: List<ChatSessionRecord>

    fun flushPendingAssistantOutput(generationRunId: Long?): Boolean

    fun reconciledChatSessionsAfterSave(snapshot: List<ChatSessionRecord>): List<ChatSessionRecord>

    fun publishReconciledChatSessions(requested: List<ChatSessionRecord>, persisted: List<ChatSessionRecord>)

    fun cleanupPendingWorldBooksAfterOwnerCommit(scope: WorldBookScope, retainedOwnerIds: Set<String>)

    val engine: McaInferenceService
    var localConversationContextNeedsInvalidation: Boolean
    val localConversationContextInvalidationSequence: AtomicLong
    var conversationMutationBarrier: Job?
    var localConversationContextInvalidationJob: Job?

    fun cancelPendingWorldBookCleanup(scope: WorldBookScope, ownerIds: Set<String>)
}

internal fun runChatSessionPersistence(
    port: ChatSessionPersistencePort,
    sessions: List<ChatSessionRecord>? = null,
    knowledgeBinding: Pair<String, Set<String>>? = null,
    generationRunId: Long? = null
): Job? {
    val uiState = port.uiState
    val chatRuns = port.chatRuns
    val chatSessionStore = port.chatSessionStore
    val chatSessionPersistenceMutex = port.chatSessionPersistenceMutex
    val chatSessionPersistenceSequence = port.chatSessionPersistenceSequence
    val chatSessionPersistenceStateLock = port.chatSessionPersistenceStateLock
    val pendingKnowledgeBindings = port.pendingKnowledgeBindings
    if (generationRunId != null && !chatRuns.isCurrent(generationRunId)) return null
    // A completion can end between chunks. Materialize its final bounded
    // buffer before taking the Room snapshot.
    port.flushPendingAssistantOutput(generationRunId)
    if (generationRunId != null && !chatRuns.isCurrent(generationRunId)) return null
    val snapshot = (sessions ?: uiState.value.chatSessions).map { session ->
        session.copy(messages = session.messages.toList())
    }
    synchronized(chatSessionPersistenceStateLock) {
        knowledgeBinding?.let { (sessionId, knowledgeBaseIds) ->
            pendingKnowledgeBindings[sessionId] = knowledgeBaseIds.toSet()
        }
    }
    val sequence = chatSessionPersistenceSequence.incrementAndGet()
    return port.scope.launch(Dispatchers.IO) {
        if (generationRunId != null && !chatRuns.isCurrent(generationRunId)) {
            return@launch
        }
        chatSessionPersistenceMutex.withLock {
            if (generationRunId != null && !chatRuns.isCurrent(generationRunId)) {
                return@withLock
            }
            // A newer snapshot supersedes this one before it reaches the
            // database; skipping it prevents an old delete/rename snapshot
            // from landing after a newer conversation state.
            if (sequence != chatSessionPersistenceSequence.get()) return@withLock
            val liveSessionIds = snapshot.mapTo(hashSetOf()) { it.id }
            val knowledgeBindingsForSave = synchronized(chatSessionPersistenceStateLock) {
                pendingKnowledgeBindings
                    .filterKeys { it in liveSessionIds }
                    .mapValues { (_, ids) -> ids.toSet() }
            }
            runCatching {
                chatSessionStore.save(snapshot, knowledgeBindingsForSave)
            }.onSuccess {
                val persisted = port.reconciledChatSessionsAfterSave(snapshot)
                // This write reached Room even if a newer in-memory snapshot
                // arrived while it was running. Keep the rollback anchor in
                // lockstep with the last transaction that actually committed.
                port.durableChatSessions = persisted
                // A newer snapshot may have arrived while Room was writing.
                // It owns pending state and orphan cleanup.
                if (sequence != chatSessionPersistenceSequence.get()) return@onSuccess
                port.publishReconciledChatSessions(snapshot, persisted)
                synchronized(chatSessionPersistenceStateLock) {
                    knowledgeBindingsForSave.forEach { (sessionId, persistedIds) ->
                        if (pendingKnowledgeBindings[sessionId] == persistedIds) {
                            pendingKnowledgeBindings.remove(sessionId)
                        }
                    }
                    pendingKnowledgeBindings.keys
                        .filterNot { it in liveSessionIds }
                        .forEach(pendingKnowledgeBindings::remove)
                }
                port.cleanupPendingWorldBooksAfterOwnerCommit(
                    scope = WorldBookScope.CHAT,
                    retainedOwnerIds = liveSessionIds
                )
            }.onFailure { error ->
                if (sequence == chatSessionPersistenceSequence.get()) {
                    uiState.update {
                        it.copy(statusMessage = "聊天历史保存失败，关联世界书已保留：${error.message}")
                    }
                }
            }
        }
    }
}

/**
 * Durable destructive-edit boundary.  Room is committed before any native
 * cache is invalidated: after a process death the next runtime starts with
 * an empty KV, while after a normal return the next generation waits for
 * both the durable message state and the native invalidation barrier.
 */
internal fun runPersistConversationMutation(
    port: ChatSessionPersistencePort,
    sessions: List<ChatSessionRecord>,
    rollback: ConversationMutationRollbackState,
    preserveReusableNativePrefix: Boolean = false,
    onCommitted: (() -> Unit)? = null,
    onCommitFailed: (() -> Unit)? = null
): Job {
    val uiState = port.uiState
    val engine = port.engine
    val chatSessionStore = port.chatSessionStore
    val chatSessionPersistenceMutex = port.chatSessionPersistenceMutex
    val chatSessionPersistenceStateLock = port.chatSessionPersistenceStateLock
    val pendingKnowledgeBindings = port.pendingKnowledgeBindings
    val snapshot = sessions.map { session ->
        session.copy(messages = session.messages.toList())
    }
    val removedChatOwners = rollback.chatSessions
        .mapTo(linkedSetOf()) { it.id }
        .minus(snapshot.mapTo(hashSetOf()) { it.id })
    port.localConversationContextNeedsInvalidation = !preserveReusableNativePrefix
    val invalidationSequence = port.localConversationContextInvalidationSequence.incrementAndGet()
    port.chatSessionPersistenceSequence.incrementAndGet()
    val mutation = port.scope.launch(Dispatchers.IO) {
        chatSessionPersistenceMutex.withLock {
            val liveSessionIds = snapshot.mapTo(hashSetOf()) { it.id }
            val knowledgeBindingsForSave = synchronized(chatSessionPersistenceStateLock) {
                pendingKnowledgeBindings
                    .filterKeys { it in liveSessionIds }
                    .mapValues { (_, ids) -> ids.toSet() }
            }
            // Purge deleted conversations before the Room commit. If the process exits
            // immediately after commit, no full-session KV file is left behind.
            val sessionCachesCleared = removedChatOwners.isEmpty() ||
                engine.clearPersistentSessionStates(removedChatOwners)
            runCatching {
                // replaceAllWithKnowledgeBindings is a Room @Transaction.
                // User/assistant tail removal cannot persist half a turn.
                chatSessionStore.save(snapshot, knowledgeBindingsForSave, removedChatOwners)
            }.onSuccess {
                val persisted = port.reconciledChatSessionsAfterSave(snapshot)
                if (persisted == snapshot) {
                    port.durableChatSessions = snapshot
                } else {
                    port.durableChatSessions = persisted
                }
                port.publishReconciledChatSessions(snapshot, persisted)
                if (!sessionCachesCleared) {
                    uiState.update {
                        it.copy(statusMessage = "聊天已删除，但磁盘会话缓存清理失败；请在设置中清空缓存。")
                    }
                }
                synchronized(chatSessionPersistenceStateLock) {
                    knowledgeBindingsForSave.forEach { (sessionId, persistedIds) ->
                        if (pendingKnowledgeBindings[sessionId] == persistedIds) {
                            pendingKnowledgeBindings.remove(sessionId)
                        }
                    }
                    pendingKnowledgeBindings.keys
                        .filterNot { it in liveSessionIds }
                        .forEach(pendingKnowledgeBindings::remove)
                }
                port.cleanupPendingWorldBooksAfterOwnerCommit(
                    scope = WorldBookScope.CHAT,
                    retainedOwnerIds = liveSessionIds
                )
                if (preserveReusableNativePrefix) {
                    // Tail pruning keeps the prior request's token/KV checkpoint. The next
                    // begin performs an exact token-prefix comparison and trims the native
                    // suffix to that proven boundary. A mismatch still falls back to a full
                    // prefill inside the runtime, so preservation cannot change semantics.
                    if (port.localConversationContextInvalidationSequence.get() == invalidationSequence) {
                        port.localConversationContextNeedsInvalidation = false
                    }
                } else {
                    runCatching { engine.invalidateConversationContext() }
                        .onSuccess {
                            if (port.localConversationContextInvalidationSequence.get() == invalidationSequence) {
                                port.localConversationContextNeedsInvalidation = false
                            }
                        }
                        .onFailure { error ->
                            if (port.localConversationContextInvalidationSequence.get() == invalidationSequence) {
                                uiState.update {
                                    it.copy(
                                        statusMessage = "聊天已保存；上下文重置失败，下次本地生成前将重试：${error.message ?: "native runtime error"}"
                                    )
                                }
                            }
                        }
                }
                if (persisted == snapshot) onCommitted?.invoke() else onCommitFailed?.invoke()
            }.onFailure { error ->
                onCommitFailed?.invoke()
                // Do not invalidate KV after a failed durable write. Room
                // rolls the transaction back; restore managed state to the
                // last snapshot known to have committed to that database.
                port.cancelPendingWorldBookCleanup(WorldBookScope.CHAT, removedChatOwners)
                uiState.update { current ->
                    val mutationStillVisible = current.chatSessions == sessions
                    if (!mutationStillVisible) {
                        current.copy(
                            isGenerating = false,
                            generationPhase = null,
                            generationTokenProgress = null,
                generationPersistProgress = null,
                            statusMessage = "对话修改未保存，内存状态已发生后续变化；请重新打开该会话：${error.message ?: "Room transaction failed"}"
                        )
                    } else {
                        current.restoreAfterConversationMutationFailure(
                            durableSessions = port.durableChatSessions,
                            rollback = rollback,
                            statusMessage = "对话修改保存失败，已恢复原状态：${error.message ?: "Room transaction failed"}"
                        )
                    }
                }
                // Room rolled back and managed state now matches its last
                // commit, so the previous native KV remains the checkpoint.
                if (port.localConversationContextInvalidationSequence.get() == invalidationSequence) {
                    port.localConversationContextNeedsInvalidation = false
                }
            }
        }
    }
    port.conversationMutationBarrier = mutation
    port.localConversationContextInvalidationJob = mutation
    return mutation
}
