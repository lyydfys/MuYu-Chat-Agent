package com.muyuchat.mca

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * 集中管理知识库文档导入、校验、预览构建与持久化提交流程。
 */
internal class DocumentImportCoordinator(
    private val application: Application,
    private val uiStateStore: UiStateWriter<KnowledgeLibraryFields>,
    private val scope: CoroutineScope,
    private val knowledgeBaseStore: KnowledgeBaseStore,
    private val chatSessionStore: ChatSessionStore,
    private val chatSessionPersistenceState: ChatSessionPersistenceState,
    private val contentImportSelectionRevision: AtomicLong,
    private val stageContentImportPreview: suspend (ContentImportPreview) -> Unit,
    private val markLocalConversationContextInvalid: () -> Unit,
    private val showStatus: (String?) -> Unit
) {
    private val viewModelScope: CoroutineScope = scope

    private var durableChatSessions: List<ChatSessionRecord>
        get() = chatSessionPersistenceState.durableChatSessions
        set(value) { chatSessionPersistenceState.durableChatSessions = value }
    private val chatSessionPersistenceMutex: Mutex get() = chatSessionPersistenceState.persistenceMutex
    private val chatSessionPersistenceSequence: AtomicLong get() = chatSessionPersistenceState.persistenceSequence
    private val chatSessionPersistenceStateLock: Any get() = chatSessionPersistenceState.stateLock
    private val pendingKnowledgeBindings: MutableMap<String, Set<String>> get() = chatSessionPersistenceState.pendingKnowledgeBindings

    private fun captureContentImportOwner(): ContentImportOwner = ContentImportOwner(
        sessionId = uiStateStore.current.activeChatSessionId,
        assistantId = uiStateStore.current.selectedAssistantId,
        selectionRevision = contentImportSelectionRevision.get()
    )

    private fun ContentImportOwner.isCurrent(state: MainUiState): Boolean =
        selectionRevision == contentImportSelectionRevision.get() &&
            sessionId == state.activeChatSessionId && assistantId == state.selectedAssistantId

    @Suppress("UNCHECKED_CAST")
    private fun <T : Application> getApplication(): T = application as T

    fun importKnowledgeDocument(
        knowledgeBaseId: String,
        uriString: String,
        importOwner: ContentImportOwner? = null
    ) {
        if (uiStateStore.current.isGenerating) {
            showStatus("请先停止当前生成，再导入知识库文件")
            return
        }
        if (knowledgeBaseId in uiStateStore.current.knowledgeBaseImportingIds) {
            showStatus("该知识库正在导入文件，请稍候")
            return
        }
        val known = uiStateStore.current.knowledgeBases.any { it.id == knowledgeBaseId }
        if (!known) {
            showStatus("未找到要导入的知识库")
            return
        }
        val owner = importOwner ?: captureContentImportOwner()
        uiStateStore.update { state ->
            state.copy(
                knowledgeBaseImportingIds = state.knowledgeBaseImportingIds + knowledgeBaseId,
                statusMessage = "正在准备知识库文档预览…"
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val preview = runCatching {
                    val uri = Uri.parse(uriString)
                    val name = displayNameForUri(getApplication<Application>().contentResolver, uri)
                    require(isSupportedTextAttachment(getApplication<Application>().contentResolver, uri, name)) { "知识库目前支持文本、Markdown、JSON、XML、CSV 与代码文件。" }
                    val text = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { KnowledgeDocumentImportCodec.readTextCancellable(it) }
                        ?: error("无法读取所选文档")
                    knowledgeDocumentPreview(text, name, knowledgeBaseId, uriString, owner)
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    showStatus("知识库导入失败：${error.message ?: "无法读取文件"}")
                    return@launch
                }
                stageContentImportPreview(preview)
            } finally {
                uiStateStore.update { it.copy(knowledgeBaseImportingIds = it.knowledgeBaseImportingIds - knowledgeBaseId) }
            }
        }
    }

    fun importKnowledgeDocument(uriString: String) {
        val baseId = uiStateStore.current.selectedKnowledgeBaseIds.firstOrNull()
            ?: uiStateStore.current.knowledgeBases.firstOrNull()?.id
            ?: run {
                showStatus("未找到要导入的知识库")
                return
            }
        importKnowledgeDocument(baseId, uriString)
    }

    suspend fun confirmKnowledgeDocumentImport(preview: ContentImportPreview): Boolean {
        val baseId = requireNotNull(preview.knowledgeBaseId)
        require(contentImportSha256(preview.rawSource) == preview.sourceHash) {
            "导入预览内容已改变，请重新选择文档。"
        }
        require(uiStateStore.current.knowledgeBases.any { it.id == baseId }) {
            "原知识库已删除，请重新选择知识库。"
        }
        val document = withContext(Dispatchers.IO) {
            knowledgeBaseStore.importDocument(baseId, preview.title, preview.rawSource, preview.source ?: "local")
        }
        val owner = preview.owner
        chatSessionPersistenceSequence.incrementAndGet()
        withContext(Dispatchers.IO) {
            chatSessionPersistenceMutex.withLock {
                val snapshot = uiStateStore.current
                val ownerSessionId = owner.sessionId?.takeIf { id -> snapshot.chatSessions.any { it.id == id } }
                val binding = ownerSessionId?.let { sessionId ->
                    val selected = synchronized(chatSessionPersistenceStateLock) {
                        pendingKnowledgeBindings[sessionId]?.toSet()
                    } ?: if (owner.isCurrent(snapshot)) snapshot.selectedKnowledgeBaseIds
                        else knowledgeBaseStore.selectedKnowledgeBaseIds(sessionId)
                    sessionId to (selected + baseId)
                }
                if (binding != null) {
                    val bindings = synchronized(chatSessionPersistenceStateLock) {
                        pendingKnowledgeBindings[binding.first] = binding.second
                        pendingKnowledgeBindings.filterKeys { id -> snapshot.chatSessions.any { it.id == id } }
                            .mapValues { (_, ids) -> ids.toSet() }
                    }
                    chatSessionStore.save(snapshot.chatSessions, bindings)
                    durableChatSessions = snapshot.chatSessions
                    synchronized(chatSessionPersistenceStateLock) {
                        bindings.forEach { (sessionId, ids) ->
                            if (pendingKnowledgeBindings[sessionId] == ids) pendingKnowledgeBindings.remove(sessionId)
                        }
                    }
                }
                val bases = knowledgeBaseStore.loadBases()
                val count = knowledgeBaseStore.documents(baseId).size
                uiStateStore.update { current ->
                    val ownerIsCurrent = owner.isCurrent(current.snapshot)
                    current.copy(
                        knowledgeBases = bases,
                        knowledgeDocumentCounts = current.knowledgeDocumentCounts + (baseId to count),
                        selectedKnowledgeBaseIds = if (ownerIsCurrent) current.selectedKnowledgeBaseIds + baseId
                            else current.selectedKnowledgeBaseIds,
                        statusMessage = "已导入知识库文档：${document.title}（${document.chunkCount} 段）" +
                            if (ownerIsCurrent) "，已启用" else if (binding != null) "；已绑定原对话" else "；已保存，原对话已不存在"
                    )
                }
            }
        }
        if (owner.isCurrent(uiStateStore.current)) markLocalConversationContextInvalid()
        return true
    }
}
