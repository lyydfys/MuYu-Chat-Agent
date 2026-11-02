package com.muyuchat.mca

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * R3/C3-S13：启动对账 owner（自 MainViewModel 迁出，body 逐字）。
 *
 * The lazy async keeps its original contract: [start] is still called from
 * `startAfterConstruction`, and [await] performs the same status write on failure. Every
 * collaborator is a real object (Store / holder / container-owned), so the composition root
 * builds this owner instead of the ViewModel capturing it -- no transitional adapter.
 */
internal class ImageLibraryStartupReconciliation(
    scope: CoroutineScope,
    private val store: UiStateWriter<AssetReconciliationFields>,
    private val chatSessionStore: ChatSessionStore,
    private val recoveryPort: ChatImageCommitRecoveryPort,
    private val persistenceState: ChatSessionPersistenceState,
    private val imageLibraryMutationMutex: Mutex,
    private val imageLibraryBackup: ImageLibraryBackup,
    private val imageAssetDirectory: File,
    private val knowledgeBaseStore: KnowledgeBaseStore,
    private val initialKnowledgeBases: List<KnowledgeBaseRecord>
) {
    private val deferred = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
        try {
            val loadedImages = chatSessionStore.loadImages()
            val files = chatSessionStore.loadFiles()
            val (recoveredSessions, initialImages) = recoverPendingImageCommits(
                port = recoveryPort,
                sessions = persistenceState.durableChatSessions,
                images = loadedImages
            )
            persistenceState.durableChatSessions = recoveredSessions
            store.update { state ->
                val activeMessages = recoveredSessions.firstOrNull { it.id == state.activeChatSessionId }?.messages
                state.copy(
                    chatSessions = recoveredSessions,
                    messages = activeMessages ?: state.messages,
                    images = initialImages,
                    files = files
                )
            }
            imageLibraryMutationMutex.withLock {
                imageLibraryBackup.reconcile(initialImages)
                val report = reconcileImageAssetDirectory(imageAssetDirectory, initialImages)
                check(report.failed == 0) { "Image asset startup reconciliation was incomplete." }
            }
            val counts = initialKnowledgeBases.associate { knowledgeBase ->
                knowledgeBase.id to knowledgeBaseStore.documents(knowledgeBase.id).size
            }
            store.update { state -> state.copy(knowledgeDocumentCounts = counts) }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            error
        }
    }

    fun start() = deferred.start()

    /** Matches the ViewModel member verbatim: a failed reconciliation surfaces the retry status. */
    suspend fun await() {
        if (deferred.await() != null) {
            store.update { state ->
                state.copy(statusMessage = "图片库启动清理未完全完成，将在下次启动重试。")
            }
        }
    }
}
