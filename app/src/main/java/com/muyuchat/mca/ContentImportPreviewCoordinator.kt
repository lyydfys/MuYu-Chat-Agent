package com.muyuchat.mca

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ContentImportPreviewCoordinator(
    private val store: UiStateStore,
    private val scope: CoroutineScope,
    private val contentImportPreviewStore: ContentImportPreviewStore,
    private val assistantProfileCoordinator: AssistantProfileCoordinator,
    private val showStatus: (String?) -> Unit
) {
    private val contentImportCommitMutex = Mutex()

    fun restorePendingContentImportPreview() {
        scope.launch {
            val preview = withContext(Dispatchers.IO) { contentImportPreviewStore.load() } ?: return@launch
            store.update { state ->
                if (state.contentImportPreview == null) state.copy(contentImportPreview = preview) else state
            }
        }
    }

    suspend fun stageContentImportPreview(preview: ContentImportPreview) {
        contentImportCommitMutex.withLock {
            if (store.current.contentImportCommitting) return
            try {
                withContext(Dispatchers.IO) { contentImportPreviewStore.save(preview) }
                store.update { it.copy(contentImportPreview = preview) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showStatus("导入预览保存失败：${error.message}")
            }
        }
    }

    fun cancelContentImportPreview(id: String) {
        scope.launch {
            contentImportCommitMutex.withLock {
                if (store.current.contentImportCommitting || store.current.contentImportPreview?.id != id) return@withLock
                try {
                    withContext(Dispatchers.IO) { contentImportPreviewStore.clear(id) }
                    store.update { it.copy(contentImportPreview = null) }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    showStatus("无法关闭导入预览：${error.message}")
                }
            }
        }
    }

    fun confirmContentImportPreview(
        id: String,
        confirmKnowledgeDocumentImport: suspend (ContentImportPreview) -> Boolean
    ) {
        scope.launch {
            contentImportCommitMutex.withLock {
                val preview = store.current.contentImportPreview?.takeIf { it.id == id } ?: return@withLock
                if (store.current.contentImportCommitting || store.current.isGenerating) return@withLock
                store.update { it.copy(contentImportCommitting = true) }
                try {
                    val committed = when (preview.kind) {
                        "character_card" -> {
                            val parsed = withContext(Dispatchers.Default) { CharacterCardCodec.parseJson(preview.rawSource) }
                            val originalSource = (parsed as? CharacterCardParseResult.Success)?.copy(
                                source = preview.sourceKind ?: CharacterCardSource.JSON
                            ) ?: parsed
                            assistantProfileCoordinator.finishCharacterCardImport(originalSource, preview.owner, preview.id)
                        }
                        "world_book" -> assistantProfileCoordinator.importWorldBookOwned(
                            preview.rawSource, requireNotNull(preview.scope), preview.owner, preview.id
                        )
                        "knowledge_document" -> confirmKnowledgeDocumentImport(preview)
                        else -> error("未知的导入预览类型")
                    }
                    if (committed) {
                        withContext(Dispatchers.IO) { contentImportPreviewStore.clear(id) }
                        store.update { it.copy(contentImportPreview = null) }
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    showStatus("导入尚未完整提交：${error.message}；预览已保留，可重试。")
                } finally {
                    store.update { it.copy(contentImportCommitting = false) }
                }
            }
        }
    }
}
