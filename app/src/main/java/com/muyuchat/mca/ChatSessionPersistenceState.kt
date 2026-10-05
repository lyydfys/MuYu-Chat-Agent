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
