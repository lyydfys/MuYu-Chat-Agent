package com.muyuchat.mca

import android.net.Uri
import com.muyuchat.mca.presentation.KnowledgeLibraryChange
import com.muyuchat.mca.presentation.KnowledgeLibraryOperations
import com.muyuchat.mca.presentation.KnowledgeLibraryPort
import com.muyuchat.mca.presentation.KnowledgeLibrarySnapshot
import com.muyuchat.mca.presentation.WorldBookImportOutcome
import com.muyuchat.mca.presentation.WorldBookImportRequest
import com.muyuchat.mca.presentation.importWorldBook
import com.muyuchat.mca.presentation.withKnowledgeLibraryChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun createKnowledgeBaseFor(
    knowledgeLibraryPort: KnowledgeLibraryPort,
    uiStateStore: UiStateStore,
    scope: CoroutineScope,
    currentSnapshot: () -> KnowledgeLibrarySnapshot,
    name: String,
    description: String = ""
) {
    scope.launch(Dispatchers.IO) {
        val change = KnowledgeLibraryOperations.createKnowledgeBase(
            knowledgeLibraryPort,
            currentSnapshot(),
            name,
            description
        )
        uiStateStore.update { it.withKnowledgeLibraryChange(change) }
    }
}

internal fun deleteKnowledgeBaseFor(
    knowledgeLibraryPort: KnowledgeLibraryPort,
    uiStateStore: UiStateStore,
    scope: CoroutineScope,
    currentSnapshot: () -> KnowledgeLibrarySnapshot,
    knowledgeBaseId: String
) {
    scope.launch(Dispatchers.IO) {
        val change = KnowledgeLibraryOperations.deleteKnowledgeBase(
            knowledgeLibraryPort,
            currentSnapshot(),
            knowledgeBaseId
        ) ?: return@launch
        uiStateStore.update { it.withKnowledgeLibraryChange(change) }
    }
}

internal fun deleteWorldBookFor(
    knowledgeLibraryPort: KnowledgeLibraryPort,
    uiStateStore: UiStateStore,
    scope: CoroutineScope,
    currentSnapshot: () -> KnowledgeLibrarySnapshot,
    worldBookId: String
) {
    scope.launch(Dispatchers.IO) {
        val change = KnowledgeLibraryOperations.deleteWorldBook(
            knowledgeLibraryPort,
            currentSnapshot(),
            worldBookId
        ) ?: return@launch
        uiStateStore.update { it.withKnowledgeLibraryChange(change) }
    }
}

internal fun setKnowledgeBaseSelectedFor(
    uiStateStore: UiStateStore,
    currentState: () -> MainUiState,
    persistKnowledgeBaseBindings: (sessionId: String, updatedIds: Set<String>) -> Unit,
    knowledgeBaseId: String,
    selected: Boolean
) {
    val state = currentState()
    if (knowledgeBaseId !in state.knowledgeBases.mapTo(mutableSetOf()) { it.id }) return
    val updatedIds = state.selectedKnowledgeBaseIds.toMutableSet().apply {
        if (selected) add(knowledgeBaseId) else remove(knowledgeBaseId)
    }.toSet()
    uiStateStore.update { it.copy(selectedKnowledgeBaseIds = updatedIds) }
    state.activeChatSessionId?.let { sessionId ->
        persistKnowledgeBaseBindings(sessionId, updatedIds)
    }
}

internal suspend fun importWorldBookOwnedFor(
    knowledgeLibraryPort: KnowledgeLibraryPort,
    uiStateStore: UiStateStore,
    currentState: () -> MainUiState,
    stageContentImportPreview: suspend (ContentImportPreview) -> Unit,
    markLocalConversationContextInvalid: () -> Unit,
    ownerIsCurrent: (ContentImportOwner) -> Boolean,
    rawJson: String,
    scope: WorldBookScope,
    owner: ContentImportOwner,
    commitId: String? = null
): Boolean {
    val state = currentState()
    val request = WorldBookImportRequest(
        rawJson = rawJson,
        scope = scope,
        owner = owner,
        commitId = commitId,
        hasActiveSession = owner.sessionId != null,
        ownerStillExists = when (scope) {
            WorldBookScope.GLOBAL -> true
            WorldBookScope.ASSISTANT -> state.assistants.any { it.id == owner.assistantId }
            WorldBookScope.CHAT -> state.chatSessions.any { it.id == owner.sessionId }
        },
        // Read after the store write, exactly as the original did.
        ownerIsCurrent = { ownerIsCurrent(owner) }
    )
    return when (val outcome = knowledgeLibraryPort.importWorldBook(request)) {
        is WorldBookImportOutcome.NeedsPreview -> {
            stageContentImportPreview(outcome.preview)
            true
        }

        is WorldBookImportOutcome.Committed -> {
            uiStateStore.update { it.withKnowledgeLibraryChange(outcome.change) }
            if (outcome.invalidateContext) markLocalConversationContextInvalid()
            true
        }

        is WorldBookImportOutcome.Rejected -> {
            uiStateStore.update {
                it.withKnowledgeLibraryChange(KnowledgeLibraryChange(statusMessage = outcome.statusMessage))
            }
            false
        }
    }
}

internal fun importWorldBookFileFor(
    scope: CoroutineScope,
    isGenerating: () -> Boolean,
    showStatus: (String) -> Unit,
    captureContentImportOwner: () -> ContentImportOwner,
    importWorldBookOwned: suspend (String, WorldBookScope, ContentImportOwner) -> Boolean,
    readAttachmentText: (Uri, Int) -> Pair<String, Boolean>,
    uriString: String,
    scopeKind: WorldBookScope = WorldBookScope.ASSISTANT,
    importOwner: ContentImportOwner? = null
) {
    if (isGenerating()) {
        showStatus("请先停止当前生成，再导入世界书")
        return
    }
    val owner = importOwner ?: captureContentImportOwner()
    scope.launch {
        val result = withContext(Dispatchers.IO) { runCatching {
            val (text, truncated) = readAttachmentText(Uri.parse(uriString), 1_048_576)
            require(!truncated) { "世界书文件超过 1 MiB。" }
            text
        } }
        result.onSuccess { text -> importWorldBookOwned(text, scopeKind, owner) }
            .onFailure { error ->
                showStatus("世界书导入失败：${error.message ?: "无法读取文件"}")
            }
    }
}

internal fun persistFilesFor(
    chatSessionStore: ChatSessionStore,
    uiStateStore: UiStateStore,
    scope: CoroutineScope,
    files: List<FileAssetRecord>
) {
    scope.launch(Dispatchers.IO) {
        runCatching { chatSessionStore.saveFiles(files) }
            .onFailure { error ->
                uiStateStore.update {
                    it.copy(statusMessage = "文件库保存失败：${error.message}")
                }
            }
    }
}
