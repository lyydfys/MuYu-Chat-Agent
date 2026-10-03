package com.muyuchat.mca.presentation

import com.muyuchat.mca.KnowledgeBaseRecord
import com.muyuchat.mca.KnowledgeBaseStore
import com.muyuchat.mca.MainUiState
import com.muyuchat.mca.WorldBookRecord
import com.muyuchat.mca.WorldBookStore

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
}
