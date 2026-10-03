package com.muyuchat.mca

/**
 * The context sources one chat turn needs, expressed as a port.
 *
 * ## Why this exists
 *
 * [ChatContextComposer] is a business use case: it decides how much of the loaded context window
 * optional lore and retrieval may occupy, and it fixes the order in which the two are combined.
 * It used to take `WorldBookStore` and `KnowledgeBaseStore` directly, which are persistence
 * implementations. That made the use case impossible to exercise without a real store on disk and
 * tied the decision logic to the storage layer.
 *
 * It now takes this port. The concrete stores are adapted to it by
 * [StoreChatContextSources], which stays next to them.
 *
 * ## What the port deliberately does not expose
 *
 * Only the two reads the use case performs. It does not expose saving, deleting, importing,
 * indexing or any file location, so a caller cannot reach past the use case into storage. The
 * return types are the plain records, so an implementation is free to be backed by a database, a
 * file, or nothing at all.
 */
interface ChatContextSources {

    /** Every stored world book, in the store's own order. */
    fun loadWorldBooks(): List<WorldBookRecord>

    /**
     * Lexical retrieval over the named knowledge bases.
     *
     * [maxChunks] and [tokenBudget] are passed explicitly rather than defaulted here: the caller
     * computed the budget from the loaded context window, and a silent default would let a caller
     * retrieve outside that budget.
     */
    fun retrieveKnowledge(
        knowledgeBaseIds: Set<String>,
        query: String,
        maxChunks: Int,
        tokenBudget: Int
    ): KnowledgeRetrieval
}

/**
 * Adapts the two concrete stores to [ChatContextSources].
 *
 * Kept beside the stores rather than beside the use case, so the direction is explicit: storage
 * knows how to satisfy the port, and the use case never learns that storage exists.
 */
class StoreChatContextSources(
    private val worldBookStore: WorldBookStore,
    private val knowledgeBaseStore: KnowledgeBaseStore
) : ChatContextSources {

    override fun loadWorldBooks(): List<WorldBookRecord> = worldBookStore.load()

    override fun retrieveKnowledge(
        knowledgeBaseIds: Set<String>,
        query: String,
        maxChunks: Int,
        tokenBudget: Int
    ): KnowledgeRetrieval = knowledgeBaseStore.retrieve(
        knowledgeBaseIds = knowledgeBaseIds,
        query = query,
        maxChunks = maxChunks,
        tokenBudget = tokenBudget
    )
}
