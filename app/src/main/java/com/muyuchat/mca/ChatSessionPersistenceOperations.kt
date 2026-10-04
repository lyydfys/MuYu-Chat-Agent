package com.muyuchat.mca

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
