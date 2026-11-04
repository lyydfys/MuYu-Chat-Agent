package com.muyuchat.mca

import android.net.Uri
import com.muyuchat.mca.presentation.KnowledgeLibraryChange
import com.muyuchat.mca.presentation.KnowledgeLibraryOperations
import com.muyuchat.mca.presentation.KnowledgeLibraryPort
import com.muyuchat.mca.presentation.KnowledgeLibrarySnapshot
import com.muyuchat.mca.presentation.WorldBookImportOutcome
import com.muyuchat.mca.presentation.WorldBookImportRequest
import com.muyuchat.mca.presentation.importWorldBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun createKnowledgeBaseFor(
    knowledgeLibraryPort: KnowledgeLibraryPort,
    uiStateStore: UiStateWriter<KnowledgeLibraryFields>,
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
    uiStateStore: UiStateWriter<KnowledgeLibraryFields>,
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
    uiStateStore: UiStateWriter<KnowledgeLibraryFields>,
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

/**
 * Toggles a book's enabled flag and invalidates the live context when that book is in scope for
 * the current conversation, mirroring the import's invalidation rule.
 */
internal fun setWorldBookEnabledFor(
    knowledgeLibraryPort: KnowledgeLibraryPort,
    uiStateStore: UiStateWriter<KnowledgeLibraryFields>,
    scope: CoroutineScope,
    currentSnapshot: () -> KnowledgeLibrarySnapshot,
    currentState: () -> MainUiState,
    markLocalConversationContextInvalid: () -> Unit,
    worldBookId: String,
    enabled: Boolean
) {
    scope.launch(Dispatchers.IO) {
        val snapshot = currentSnapshot()
        val book = snapshot.worldBooks.firstOrNull { it.id == worldBookId }
        val change = KnowledgeLibraryOperations.setWorldBookEnabled(
            knowledgeLibraryPort,
            snapshot,
            worldBookId,
            enabled
        ) ?: return@launch
        val state = currentState()
        val affectsCurrentContext = book?.let {
            it.scope == WorldBookScope.GLOBAL ||
                (it.scope == WorldBookScope.ASSISTANT && it.assistantId == state.selectedAssistantId) ||
                (it.scope == WorldBookScope.CHAT && it.chatSessionId == state.activeChatSessionId)
        } ?: true
        if (affectsCurrentContext) markLocalConversationContextInvalid()
        uiStateStore.update { it.withKnowledgeLibraryChange(change) }
    }
}

internal fun setKnowledgeBaseSelectedFor(
    uiStateStore: UiStateWriter<KnowledgeLibraryFields>,
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
    uiStateStore: UiStateWriter<KnowledgeLibraryFields>,
    currentState: () -> MainUiState,
    stageContentImportPreview: suspend (ContentImportPreview) -> Unit,
    markLocalConversationContextInvalid: () -> Unit,
    ownerIsCurrent: (ContentImportOwner) -> Boolean,
    rawJson: String,
    scope: WorldBookScope,
    owner: ContentImportOwner,
    commitId: String? = null,
    fallbackName: String? = null
): Boolean {
    val state = currentState()
    val request = WorldBookImportRequest(
        rawJson = rawJson,
        scope = scope,
        owner = owner,
        commitId = commitId,
        fallbackName = fallbackName?.takeIf { it.isNotBlank() },
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

/** Same ceiling as [WorldBookCodec]'s byte guard so the read fails before the parse does. */
internal const val WORLD_BOOK_IMPORT_MAX_BYTES = 8_388_608

/**
 * Tavern World Info exports carry no top-level name, so the picked file name becomes the book
 * name; the extension is dropped and a blank result means "no fallback" (the codec default).
 */
internal fun worldBookFallbackNameFromFile(displayName: String): String? = displayName
    .trim()
    .takeIf { it.isNotBlank() }
    ?.substringBeforeLast('.')
    ?.takeIf { it.isNotBlank() }

/**
 * Batch world-book import from one multi-select picker round-trip.
 *
 * The picker result is read once and every file is then handled independently, so one bad export
 * cannot block the rest. Each file is committed directly (`commitId` supplied) instead of being
 * staged as a preview: staging one preview per file is what made the old path look like it "jumped"
 * through the selection and only kept the last one.
 *
 * Duplicate detection is content based ([WorldBookDuplicateGuard]): a book whose entries are already
 * in the library — or were imported earlier in this same batch — is skipped and counted, so running
 * the same selection twice is a no-op.
 *
 * The owner check is re-read per file: a book committed for a session that was deleted mid-batch
 * must not resurrect a binding.
 */
internal fun importWorldBookFilesFor(
    scope: CoroutineScope,
    isGenerating: () -> Boolean,
    showStatus: (String) -> Unit,
    captureContentImportOwner: () -> ContentImportOwner,
    importWorldBookOwned: suspend (String, WorldBookScope, ContentImportOwner, String?, String) -> Boolean,
    readAttachmentText: (Uri, Int) -> Pair<String, Boolean>,
    resolveFallbackName: (Uri) -> String,
    existingWorldBooks: () -> List<WorldBookRecord>,
    uriStrings: List<String>,
    scopeKind: WorldBookScope = WorldBookScope.ASSISTANT,
    importOwner: ContentImportOwner? = null
) {
    if (isGenerating()) {
        showStatus("请先停止当前生成，再导入世界书")
        return
    }
    val targets = uriStrings.filter { it.isNotBlank() }.distinct()
    if (targets.isEmpty()) return
    val owner = importOwner ?: captureContentImportOwner()
    // Same pre-condition as the single-file path: a chat-scoped book needs an active conversation.
    // Checked before the loop so a batch is refused once with the real reason instead of reporting
    // every file as "格式不正确".
    if (scopeKind == WorldBookScope.CHAT && owner.sessionId == null) {
        showStatus("当前没有活动对话，无法导入当前对话作用域的世界书")
        return
    }
    scope.launch {
        var imported = 0
        var skippedDuplicates = 0
        val failures = mutableListOf<String>()
        // Seeded from the library and extended per committed file, so both "already present" and
        // "same file twice in this batch" are caught without re-reading the store between files.
        val knownFingerprints = WorldBookDuplicateGuard.fingerprints(existingWorldBooks()).toMutableSet()
        for (uriString in targets) {
            val loaded = withContext(Dispatchers.IO) { runCatching {
                val uri = Uri.parse(uriString)
                val fallbackName = worldBookFallbackNameFromFile(resolveFallbackName(uri))
                val (text, truncated) = readAttachmentText(uri, WORLD_BOOK_IMPORT_MAX_BYTES)
                require(!truncated) { "世界书文件超过 8 MiB。" }
                fallbackName to text
            } }
            val (fallbackName, text) = loaded.getOrElse { error ->
                failures += "${Uri.parse(uriString).lastPathSegment ?: uriString}（${error.message ?: "无法读取文件"}）"
                continue
            }
            // Parse once more, on the parse dispatcher, only to fingerprint the book. A file that
            // cannot be parsed is a failure here and would fail in the import call as well.
            val fingerprint = withContext(Dispatchers.Default) {
                WorldBookCodec.parse(
                    rawJson = text,
                    scope = scopeKind,
                    assistantId = owner.assistantId.takeIf { scopeKind == WorldBookScope.ASSISTANT },
                    chatSessionId = owner.sessionId.takeIf { scopeKind == WorldBookScope.CHAT },
                    fallbackName = fallbackName ?: "Imported World Book"
                ).book?.let(WorldBookDuplicateGuard::fingerprint)
            }
            if (fingerprint == null) {
                failures += "${fallbackName ?: uriString}（世界书格式不正确）"
                continue
            }
            if (fingerprint in knownFingerprints) {
                skippedDuplicates++
                continue
            }
            val accepted = runCatching {
                importWorldBookOwned(
                    text,
                    scopeKind,
                    owner,
                    fallbackName,
                    WorldBookDuplicateGuard.bookIdFor(fingerprint)
                )
            }.getOrElse { error ->
                failures += "${fallbackName ?: uriString}（${error.message ?: "导入失败"}）"
                false
            }
            if (accepted) {
                imported++
                knownFingerprints += fingerprint
            } else {
                failures += (fallbackName ?: uriString)
            }
        }
        val summary = WorldBookBatchImportSummary(imported, skippedDuplicates, failures)
        when {
            summary.isQuietSingleImport(targets.size) -> Unit
            imported == 0 && skippedDuplicates == 0 && failures.isNotEmpty() ->
                showStatus("世界书导入失败：${failures.joinToString("；")}")
            else -> showStatus(summary.statusMessage())
        }
    }
}

internal fun importWorldBookFileFor(
    scope: CoroutineScope,
    isGenerating: () -> Boolean,
    showStatus: (String) -> Unit,
    captureContentImportOwner: () -> ContentImportOwner,
    importWorldBookOwned: suspend (String, WorldBookScope, ContentImportOwner, String?) -> Boolean,
    readAttachmentText: (Uri, Int) -> Pair<String, Boolean>,
    resolveFallbackName: (Uri) -> String,
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
            val uri = Uri.parse(uriString)
            val fallbackName = worldBookFallbackNameFromFile(resolveFallbackName(uri))
            val (text, truncated) = readAttachmentText(uri, WORLD_BOOK_IMPORT_MAX_BYTES)
            require(!truncated) { "世界书文件超过 8 MiB。" }
            fallbackName to text
        } }
        result.onSuccess { (fallbackName, text) ->
            importWorldBookOwned(text, scopeKind, owner, fallbackName)
        }.onFailure { error ->
            showStatus("世界书导入失败：${error.message ?: "无法读取文件"}")
        }
    }
}

internal fun persistFilesFor(
    chatSessionStore: ChatSessionStore,
    uiStateStore: UiStatusWriter,
    scope: CoroutineScope,
    files: List<FileAssetRecord>
): Deferred<Result<Unit>> =
    scope.async(Dispatchers.IO) {
        try {
            chatSessionStore.saveFiles(files)
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            uiStateStore.showStatus("文件库保存失败：${error.message}")
            Result.failure(error)
        }
    }

/** Applies the library's atomic change without granting a root-state write capability. */
private fun KnowledgeLibraryFields.withKnowledgeLibraryChange(change: KnowledgeLibraryChange): KnowledgeLibraryFields = copy(
    worldBooks = change.worldBooks ?: worldBooks,
    knowledgeBases = change.knowledgeBases ?: knowledgeBases,
    knowledgeDocumentCounts = change.knowledgeDocumentCounts ?: knowledgeDocumentCounts,
    knowledgeBaseImportingIds = change.knowledgeBaseImportingIds ?: knowledgeBaseImportingIds,
    selectedKnowledgeBaseIds = change.selectedKnowledgeBaseIds ?: selectedKnowledgeBaseIds,
    statusMessage = change.statusMessage
)
