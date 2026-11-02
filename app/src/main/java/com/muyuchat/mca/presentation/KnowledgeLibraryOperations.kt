package com.muyuchat.mca.presentation

import com.muyuchat.mca.ContentImportOwner
import com.muyuchat.mca.ContentImportPreview
import com.muyuchat.mca.KnowledgeBaseRecord
import com.muyuchat.mca.KnowledgeBaseStore
import com.muyuchat.mca.MainUiState
import com.muyuchat.mca.WorldBookCodec
import com.muyuchat.mca.WorldBookImportResult
import com.muyuchat.mca.WorldBookRecord
import com.muyuchat.mca.WorldBookScope
import com.muyuchat.mca.WorldBookStore
import com.muyuchat.mca.worldBookPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The knowledge-library operations: create and delete knowledge bases, delete world books.
 *
 * These were inline in `MainViewModel`, each one a `viewModelScope.launch(Dispatchers.IO) { runCatching
 * { store... } }` with the resulting state change spelled out by hand. They are moved here so the
 * decision -- what changes, what the user is told, and what happens when the store throws -- can be
 * read and tested without a 20,000-line class. `MainViewModel` keeps the threading and the single
 * atomic publish; it no longer decides any of it.
 *
 * ## Why the operations are synchronous
 *
 * Each function performs its store call and returns the change to publish. The caller keeps doing
 * what it did before: run the operation on `Dispatchers.IO` and apply the returned change in one
 * `_uiState.update { }`. Making these `suspend` would move the dispatcher choice in here and change
 * when the state is published, which is exactly the timing change this refactor must not make.
 *
 * ## Failure semantics, preserved exactly
 *
 * The stores throw; the original code caught with `runCatching` and turned the throwable into a
 * status line. That mapping is reproduced verbatim, including the fallback wording, because the
 * strings are user-visible.
 */
internal interface KnowledgeLibraryPort {
    /** Removes a world book and returns the remaining books. */
    fun removeWorldBook(id: String): List<WorldBookRecord>

    /** Creates a knowledge base, or throws when the name is invalid. */
    fun createKnowledgeBase(name: String, description: String): KnowledgeBaseRecord

    /** Removes a knowledge base together with its documents and chunks. */
    fun removeKnowledgeBase(id: String)

    /** Inserts or replaces a world book and returns the resulting list. */
    fun upsertWorldBook(book: WorldBookRecord): List<WorldBookRecord>
}

/**
 * What the caller must publish after an operation, and what to tell the user.
 *
 * Every field is nullable and means "leave this alone": an operation that only deletes a world book
 * must not reset the knowledge bases, and a failure must publish no state change at all.
 */
internal data class KnowledgeLibraryChange(
    val worldBooks: List<WorldBookRecord>? = null,
    val knowledgeBases: List<KnowledgeBaseRecord>? = null,
    val knowledgeDocumentCounts: Map<String, Int>? = null,
    val knowledgeBaseImportingIds: Set<String>? = null,
    val selectedKnowledgeBaseIds: Set<String>? = null,
    val statusMessage: String
)

/** The state an operation needs to read before it decides anything. */
internal data class KnowledgeLibrarySnapshot(
    val worldBooks: List<WorldBookRecord>,
    val knowledgeBases: List<KnowledgeBaseRecord>,
    val knowledgeDocumentCounts: Map<String, Int>,
    val knowledgeBaseImportingIds: Set<String>,
    val selectedKnowledgeBaseIds: Set<String>
)

internal object KnowledgeLibraryOperations {

    /**
     * Deletes a world book.
     *
     * Returns null when the id is not in the snapshot: the original code returned before touching
     * the store, so an unknown id stays a silent no-op rather than reporting a failure.
     */
    fun deleteWorldBook(
        port: KnowledgeLibraryPort,
        snapshot: KnowledgeLibrarySnapshot,
        worldBookId: String
    ): KnowledgeLibraryChange? {
        val removed = snapshot.worldBooks.firstOrNull { it.id == worldBookId } ?: return null
        return runCatching { port.removeWorldBook(worldBookId) }
            .fold(
                onSuccess = { remaining ->
                    KnowledgeLibraryChange(
                        worldBooks = remaining,
                        statusMessage = "已删除世界书：${removed.name}"
                    )
                },
                onFailure = { error ->
                    KnowledgeLibraryChange(statusMessage = "世界书删除失败：${error.message ?: "请重试"}")
                }
            )
    }

    /**
     * Enables or disables a world book without touching its entries.
     *
     * A disabled book stays listed and re-importable; the resolver simply skips it, so the user can
     * turn a lorebook on and off per conversation instead of deleting it. Returns null for an
     * unknown id, like [deleteWorldBook]; a same-state toggle publishes only a status line.
     */
    fun setWorldBookEnabled(
        port: KnowledgeLibraryPort,
        snapshot: KnowledgeLibrarySnapshot,
        worldBookId: String,
        enabled: Boolean
    ): KnowledgeLibraryChange? {
        val book = snapshot.worldBooks.firstOrNull { it.id == worldBookId } ?: return null
        if (book.enabled == enabled) {
            return KnowledgeLibraryChange(
                statusMessage = "世界书「${book.name}」已是${if (enabled) "启用" else "停用"}状态"
            )
        }
        return runCatching {
            port.upsertWorldBook(book.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
        }.fold(
            onSuccess = { updated ->
                KnowledgeLibraryChange(
                    worldBooks = updated,
                    statusMessage = if (enabled) "已启用世界书：${book.name}" else "已停用世界书：${book.name}"
                )
            },
            onFailure = { error ->
                KnowledgeLibraryChange(statusMessage = "世界书状态更新失败：${error.message ?: "请重试"}")
            }
        )
    }

    /**
     * Creates a knowledge base.
     *
     * A failure publishes only a status line -- no list, no count -- matching the original, which
     * returned from the coroutine before updating any state.
     */
    fun createKnowledgeBase(
        port: KnowledgeLibraryPort,
        snapshot: KnowledgeLibrarySnapshot,
        name: String,
        description: String
    ): KnowledgeLibraryChange {
        val created = runCatching { port.createKnowledgeBase(name, description) }.getOrElse { error ->
            return KnowledgeLibraryChange(statusMessage = "知识库创建失败：${error.message ?: "名称无效"}")
        }
        val merged = (snapshot.knowledgeBases.filterNot { it.id == created.id } + created)
            .sortedBy { it.name.lowercase() }
        return KnowledgeLibraryChange(
            knowledgeBases = merged,
            knowledgeDocumentCounts = snapshot.knowledgeDocumentCounts + (created.id to 0),
            statusMessage = "已创建知识库：${created.name}"
        )
    }

    /**
     * Deletes a knowledge base and every trace of it in the state.
     *
     * Returns null for an unknown id, like [deleteWorldBook]. On success the four dependent fields
     * are cleared in the same change, so the caller can publish them in one atomic update.
     */
    fun deleteKnowledgeBase(
        port: KnowledgeLibraryPort,
        snapshot: KnowledgeLibrarySnapshot,
        knowledgeBaseId: String
    ): KnowledgeLibraryChange? {
        val removed = snapshot.knowledgeBases.firstOrNull { it.id == knowledgeBaseId } ?: return null
        return runCatching { port.removeKnowledgeBase(knowledgeBaseId) }
            .fold(
                onSuccess = {
                    KnowledgeLibraryChange(
                        knowledgeBases = snapshot.knowledgeBases.filterNot { it.id == knowledgeBaseId },
                        knowledgeDocumentCounts = snapshot.knowledgeDocumentCounts - knowledgeBaseId,
                        knowledgeBaseImportingIds = snapshot.knowledgeBaseImportingIds - knowledgeBaseId,
                        selectedKnowledgeBaseIds = snapshot.selectedKnowledgeBaseIds - knowledgeBaseId,
                        statusMessage = "已删除知识库：${removed.name}"
                    )
                },
                onFailure = { error ->
                    KnowledgeLibraryChange(statusMessage = "知识库删除失败：${error.message ?: "请重试"}")
                }
            )
    }
}

/** The inputs a world-book import needs, including the two facts only the caller can observe. */
internal data class WorldBookImportRequest(
    val rawJson: String,
    val scope: WorldBookScope,
    val owner: ContentImportOwner,
    val commitId: String?,
    /** File-derived name used when the exported JSON carries no name/title of its own. */
    val fallbackName: String? = null,
    /** `owner.sessionId != null`: a CHAT-scoped import needs an active conversation. */
    val hasActiveSession: Boolean,
    /** Whether the assistant or session the book belongs to still exists. */
    val ownerStillExists: Boolean,
    /**
     * `ContentImportOwner.isCurrent(state)`, evaluated by the caller against the live revision.
     *
     * This is a provider, not a value, because the original read it **after** the store write. A slow
     * write can race a selection change, and in that case the message must say "已保存，当前对话未切换
     * 绑定" rather than claiming the binding moved. Passing a pre-computed boolean would silently
     * change that.
     */
    val ownerIsCurrent: suspend () -> Boolean
)

/**
 * What the caller still has to do.
 *
 * The two side effects a world-book import has beyond state — staging a preview and invalidating the
 * local conversation context — stay in the caller, because they own the preview store and the engine.
 * Everything else (parsing, the existence check, the store write, the message) is decided here.
 */
internal sealed interface WorldBookImportOutcome {
    /** No commit yet: the caller must stage this preview and report success. */
    data class NeedsPreview(val preview: ContentImportPreview) : WorldBookImportOutcome

    /** Committed: publish the change, then invalidate the context when asked. */
    data class Committed(
        val change: KnowledgeLibraryChange,
        val invalidateContext: Boolean
    ) : WorldBookImportOutcome

    /** Nothing to publish beyond a status line; the caller reports failure. */
    data class Rejected(val statusMessage: String) : WorldBookImportOutcome
}

/**
 * Parses, validates and stores an imported world book.
 *
 * Dispatchers match the original: parsing on [Dispatchers.Default], the store write on
 * [Dispatchers.IO]. A cancellation is rethrown rather than reported as a failure, exactly as before
 * — turning a cancelled import into "保存失败" would be a user-visible lie.
 */
internal suspend fun KnowledgeLibraryPort.importWorldBook(request: WorldBookImportRequest): WorldBookImportOutcome {
    if (request.scope == WorldBookScope.CHAT && !request.hasActiveSession) {
        return WorldBookImportOutcome.Rejected("当前没有活动对话，无法导入当前对话作用域的世界书")
    }
    val result = withContext(Dispatchers.Default) {
        WorldBookCodec.parse(
            rawJson = request.rawJson,
            scope = request.scope,
            assistantId = request.owner.assistantId.takeIf { request.scope == WorldBookScope.ASSISTANT },
            chatSessionId = request.owner.sessionId.takeIf { request.scope == WorldBookScope.CHAT },
            fallbackName = request.fallbackName ?: "Imported World Book"
        )
    }
    val imported = result.book
        ?: return WorldBookImportOutcome.Rejected("世界书导入失败：${result.error ?: "格式不正确"}")
    if (request.commitId == null) {
        return WorldBookImportOutcome.NeedsPreview(
            worldBookPreview(request.rawJson, result, request.owner, request.scope, request.fallbackName)
        )
    }
    val frozenBook = imported.copy(id = request.commitId, enabled = request.ownerStillExists)
    val updated = runCatching { withContext(Dispatchers.IO) { upsertWorldBook(frozenBook) } }.getOrElse { error ->
        if (error is CancellationException) throw error
        return WorldBookImportOutcome.Rejected(
            "世界书保存失败：${error.message ?: "请检查本机存储空间后重试"}"
        )
    }
    val ownerIsCurrent = request.ownerIsCurrent()
    val statusMessage = buildString {
        append("已导入世界书：${imported.name}（${imported.entries.size} 条）")
        if (!ownerIsCurrent) append("；已保存，当前对话未切换绑定")
        if (!request.ownerStillExists) append("；原所属对象已删除，资料已保留但未启用")
        result.warnings.takeIf { it.isNotEmpty() }?.let { warnings ->
            append("；").append(warnings.joinToString(" "))
        }
    }
    return WorldBookImportOutcome.Committed(
        change = KnowledgeLibraryChange(worldBooks = updated, statusMessage = statusMessage),
        invalidateContext = ownerIsCurrent || request.scope == WorldBookScope.GLOBAL
    )
}

/**
 * Reads the fields the knowledge-library operations decide on.
 *
 * Kept here, next to the operations, so `MainViewModel` does not grow a helper for each migrated
 * operation. That matters: the class has methods close to the JVM 64 KB bytecode limit, and adding
 * to it can break the build.
 */
internal fun MainUiState.knowledgeLibrarySnapshot(): KnowledgeLibrarySnapshot = KnowledgeLibrarySnapshot(
    worldBooks = worldBooks,
    knowledgeBases = knowledgeBases,
    knowledgeDocumentCounts = knowledgeDocumentCounts,
    knowledgeBaseImportingIds = knowledgeBaseImportingIds,
    selectedKnowledgeBaseIds = selectedKnowledgeBaseIds
)

/**
 * Applies a change in one step, so the caller can publish atomically.
 *
 * A field that the operation did not touch is left exactly as it was: the nulls in
 * [KnowledgeLibraryChange] mean "unchanged", not "clear".
 */
internal fun MainUiState.withKnowledgeLibraryChange(change: KnowledgeLibraryChange): MainUiState = copy(
    worldBooks = change.worldBooks ?: worldBooks,
    knowledgeBases = change.knowledgeBases ?: knowledgeBases,
    knowledgeDocumentCounts = change.knowledgeDocumentCounts ?: knowledgeDocumentCounts,
    knowledgeBaseImportingIds = change.knowledgeBaseImportingIds ?: knowledgeBaseImportingIds,
    selectedKnowledgeBaseIds = change.selectedKnowledgeBaseIds ?: selectedKnowledgeBaseIds,
    statusMessage = change.statusMessage
)

/**
 * Adapts the two concrete stores to [KnowledgeLibraryPort].
 *
 * Kept beside the operations rather than beside the use case callers, so the direction stays
 * explicit: storage knows how to satisfy the port, and the operations never learn that storage exists.
 */
internal class StoreKnowledgeLibraryPort(
    private val worldBookStore: WorldBookStore,
    private val knowledgeBaseStore: KnowledgeBaseStore
) : KnowledgeLibraryPort {

    override fun removeWorldBook(id: String): List<WorldBookRecord> = worldBookStore.remove(id)

    override fun createKnowledgeBase(name: String, description: String): KnowledgeBaseRecord =
        knowledgeBaseStore.create(name, description)

    override fun removeKnowledgeBase(id: String) = knowledgeBaseStore.remove(id)

    override fun upsertWorldBook(book: WorldBookRecord): List<WorldBookRecord> = worldBookStore.upsert(book)
}
