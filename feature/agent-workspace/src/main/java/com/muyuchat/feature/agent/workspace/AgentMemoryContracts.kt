package com.muyuchat.feature.agent.workspace

/**
 * Feature-local Agent memory administration port.
 *
 * Agent memory is its own domain: it is not chat knowledge, not character memory and not a widened
 * chat-session query. This slice is a read-only spaces/counts surface so the page can show real
 * spaces and their item counts before task selection exists. No memory content, no item body and no
 * chat record crosses this port.
 *
 * The host adapter maps [AgentMemoryAdminPort.list] to the future `AgentMemoryStore` bounded query.
 */
object AgentMemoryLimits {
    const val MAX_DISPLAY_NAME_CHARS: Int = 128
    const val MAX_DESCRIPTION_CHARS: Int = 280
    const val MAX_LABEL_CHARS: Int = 80
}

/**
 * One memory space summary. [itemCount] is the only content fact exposed; the page must never
 * synthesise items or counts when the list is empty or rejected.
 */
data class AgentMemorySpaceItem(
    val spaceId: String,
    val displayName: String,
    val description: String = "",
    val itemCount: Int,
    /** Egress class label supplied by the store, e.g. "仅本地"; never inferred by the UI. */
    val egressLabel: String? = null,
    /** Store-provided last-update label, e.g. "10:24"; omitted when the store has no value. */
    val updatedLabel: String? = null
) {
    init {
        require(spaceId.isNotBlank()) { "spaceId must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(itemCount >= 0) { "itemCount must not be negative" }
    }
}

sealed interface AgentMemoryListResult {
    data class Loaded(val spaces: List<AgentMemorySpaceItem>) : AgentMemoryListResult

    data class Rejected(val code: String, val safeMessage: String) : AgentMemoryListResult {
        init {
            require(code.isNotBlank()) { "list rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "list rejection message must not be blank" }
        }
    }
}

/** Result of one user-initiated memory item edit. */
sealed interface AgentMemoryUpdateResult {
    data class Updated(val spaceId: String, val itemId: String, val revision: Long) : AgentMemoryUpdateResult

    data class Rejected(val code: String, val safeMessage: String) : AgentMemoryUpdateResult {
        init {
            require(code.isNotBlank()) { "update rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "update rejection message must not be blank" }
        }
    }
}

/**
 * One row of the memory management list.
 *
 * The body is an [excerpt]; a surface that needs the full text performs a per-item read. The host
 * adapter maps this from the domain summary and never invents counts or bodies.
 */
data class AgentMemoryManagedItem(
    val spaceId: String,
    val itemId: String,
    val title: String,
    val excerpt: String,
    val revision: Long,
    val updatedAt: Long,
    val sourceKind: String,
    val revoked: Boolean,
    val hasSourceRef: Boolean
) {
    init {
        require(spaceId.isNotBlank()) { "managed item spaceId must not be blank" }
        require(itemId.isNotBlank()) { "managed item id must not be blank" }
        require(revision >= 1L) { "managed item revision must be positive" }
    }
}

/** Result of one bounded management page. */
sealed interface AgentMemoryItemPageResult {
    data class Loaded(
        val spaceId: String,
        val items: List<AgentMemoryManagedItem>,
        val total: Int,
        val offset: Int,
        val limit: Int,
        val hasMore: Boolean
    ) : AgentMemoryItemPageResult

    data class Rejected(val code: String, val safeMessage: String) : AgentMemoryItemPageResult {
        init {
            require(code.isNotBlank()) { "page rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "page rejection message must not be blank" }
        }
    }
}

/** Result of a user-initiated delete/revoke or restore/undelete. */
sealed interface AgentMemoryItemActionResult {
    data class Applied(val spaceId: String, val itemId: String, val revision: Long) : AgentMemoryItemActionResult

    data class Rejected(val code: String, val safeMessage: String) : AgentMemoryItemActionResult {
        init {
            require(code.isNotBlank()) { "item action rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "item action rejection message must not be blank" }
        }
    }
}

/** Confidence hint attached to an extraction candidate; the user still decides. */
enum class AgentMemoryCandidateConfidence {
    LOW,
    MEDIUM,
    HIGH
}

/** One reviewable extraction candidate. [originLabel] names the rule or the model fingerprint. */
data class AgentMemoryCandidateItem(
    val candidateId: String,
    val text: String,
    val title: String,
    val confidence: AgentMemoryCandidateConfidence,
    val originLabel: String,
    val duplicateOfItemId: String? = null,
    val duplicateRevoked: Boolean = false
) {
    init {
        require(candidateId.isNotBlank()) { "candidate id must not be blank" }
        require(text.isNotBlank()) { "candidate text must not be blank" }
        require(originLabel.isNotBlank()) { "candidate origin must not be blank" }
    }
}

/** Result of submitting new text for extraction; nothing is stored until a candidate is accepted. */
sealed interface AgentMemoryExtractionSubmitResult {
    data class Proposed(
        val candidates: List<AgentMemoryCandidateItem>,
        val truncated: Boolean,
        val modelStatus: String
    ) : AgentMemoryExtractionSubmitResult

    data class Rejected(val code: String, val safeMessage: String) : AgentMemoryExtractionSubmitResult {
        init {
            require(code.isNotBlank()) { "extraction rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "extraction rejection message must not be blank" }
        }
    }
}

/** Result of listing the pending candidate batch for one space. */
sealed interface AgentMemoryCandidateListResult {
    data class Loaded(
        val spaceId: String,
        val candidates: List<AgentMemoryCandidateItem>,
        val submittedAt: Long?
    ) : AgentMemoryCandidateListResult

    data class Rejected(val code: String, val safeMessage: String) : AgentMemoryCandidateListResult {
        init {
            require(code.isNotBlank()) { "candidate list rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "candidate list rejection message must not be blank" }
        }
    }
}

/** Per-candidate decision outcome of an accept/reject call. */
enum class AgentMemoryCandidateDecision {
    STORED,
    ALREADY_PRESENT,
    REJECTED,
    DISMISSED
}

data class AgentMemoryCandidateDecisionEntry(
    val candidateId: String,
    val decision: AgentMemoryCandidateDecision,
    val itemId: String? = null,
    val errorCode: String? = null
)

/** Result of one accept/reject decision over reviewed candidates. */
sealed interface AgentMemoryCandidateDecisionResult {
    data class Applied(
        val entries: List<AgentMemoryCandidateDecisionEntry>,
        val stored: Int,
        val alreadyPresent: Int,
        val rejected: Int,
        val dismissed: Int
    ) : AgentMemoryCandidateDecisionResult

    data class Rejected(val code: String, val safeMessage: String) : AgentMemoryCandidateDecisionResult {
        init {
            require(code.isNotBlank()) { "candidate decision rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "candidate decision rejection message must not be blank" }
        }
    }
}

/**
 * Which retrieval capability the host store actually has.
 *
 * [mode] is `HYBRID` only when a real embedder + vector index were selected; `LEXICAL` always comes
 * with a typed [degradedReason] so a settings surface can explain the fallback instead of pretending
 * semantic search is active. [indexedChunks] is the current index size, when the index can report it.
 */
data class AgentMemorySemanticDiagnostics(
    val mode: String,
    val fingerprint: String? = null,
    val degradedReason: String? = null,
    val indexedChunks: Int? = null
) {
    init {
        require(mode == MODE_LEXICAL || mode == MODE_HYBRID) { "unknown semantic mode '$mode'" }
    }

    companion object {
        const val MODE_LEXICAL: String = "LEXICAL"
        const val MODE_HYBRID: String = "HYBRID"
    }
}

/** Result of creating one user memory space. */
sealed interface AgentMemorySpaceCreateResult {
    data class Created(val spaceId: String, val displayName: String) : AgentMemorySpaceCreateResult {
        init {
            require(spaceId.isNotBlank()) { "created spaceId must not be blank" }
            require(displayName.isNotBlank()) { "created displayName must not be blank" }
        }
    }

    data class Rejected(val code: String, val safeMessage: String) : AgentMemorySpaceCreateResult {
        init {
            require(code.isNotBlank()) { "create rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "create rejection message must not be blank" }
        }
    }
}

/** Result of one user-initiated text ingest into a space. */
sealed interface AgentMemoryTextIngestResult {
    data class Stored(
        val spaceId: String,
        val memoryId: String,
        val chunkCount: Int
    ) : AgentMemoryTextIngestResult {
        init {
            require(spaceId.isNotBlank()) { "ingest spaceId must not be blank" }
            require(memoryId.isNotBlank()) { "ingest memoryId must not be blank" }
            require(chunkCount >= 1) { "ingest chunkCount must be positive" }
        }
    }

    /** The same `(spaceId, contentHash)` already exists: a successful no-op, not an error. */
    data class Deduplicated(
        val spaceId: String,
        val memoryId: String,
        val revoked: Boolean
    ) : AgentMemoryTextIngestResult {
        init {
            require(spaceId.isNotBlank()) { "ingest spaceId must not be blank" }
            require(memoryId.isNotBlank()) { "ingest memoryId must not be blank" }
        }
    }

    data class Rejected(val code: String, val safeMessage: String) : AgentMemoryTextIngestResult {
        init {
            require(code.isNotBlank()) { "ingest rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "ingest rejection message must not be blank" }
        }
    }
}

/** Result of exporting live memory content into one bounded JSON package. */
sealed interface AgentMemoryExportResult {
    data class Exported(
        val packageJson: String,
        val spaceCount: Int,
        val itemCount: Int
    ) : AgentMemoryExportResult {
        init {
            require(packageJson.isNotBlank()) { "exported package must not be blank" }
            require(spaceCount >= 1) { "exported spaceCount must be positive" }
            require(itemCount >= 0) { "exported itemCount must not be negative" }
        }
    }

    data class Rejected(val code: String, val safeMessage: String) : AgentMemoryExportResult {
        init {
            require(code.isNotBlank()) { "export rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "export rejection message must not be blank" }
        }
    }
}

/**
 * Result of importing one bounded JSON package.
 *
 * [PartiallyImported] is explicit: spaces/items are written one by one through the domain store, so
 * a storage failure after validation can leave a prefix. Counts say exactly what was written; the
 * import never reports a clean success for a partial write.
 */
sealed interface AgentMemoryImportResult {
    data class Imported(
        val spaceCount: Int,
        val itemCount: Int,
        val deduplicated: Int
    ) : AgentMemoryImportResult {
        init {
            require(spaceCount >= 1) { "imported spaceCount must be positive" }
            require(itemCount >= 0) { "imported itemCount must not be negative" }
            require(deduplicated >= 0) { "imported deduplicated must not be negative" }
        }
    }

    data class PartiallyImported(
        val spaceCount: Int,
        val itemCount: Int,
        val deduplicated: Int,
        val errorCode: String,
        val safeMessage: String
    ) : AgentMemoryImportResult {
        init {
            require(spaceCount >= 0 && itemCount >= 0 && deduplicated >= 0) {
                "partial import counts must not be negative"
            }
            require(errorCode.isNotBlank()) { "partial import errorCode must not be blank" }
            require(safeMessage.isNotBlank()) { "partial import message must not be blank" }
        }
    }

    data class Rejected(val code: String, val safeMessage: String) : AgentMemoryImportResult {
        init {
            require(code.isNotBlank()) { "import rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "import rejection message must not be blank" }
        }
    }
}

/**
 * Memory-spaces settings surface.
 *
 * `updateItem` is a **user-initiated edit** from this surface: it never runs from a model tool call
 * and the adapter mints the memory write approval itself with `USER_EDIT`. Delete/restore and the
 * extraction review are the same kind of user-initiated call (`USER_DELETE`/`USER_RESTORE`/
 * `USER_EXTRACT_ACCEPT`); every one is revision-CAS and returns typed errors. The default methods
 * keep older implementations (and the `Unavailable` preview port) source-compatible.
 */
interface AgentMemoryAdminPort {
    suspend fun list(): AgentMemoryListResult

    /**
     * Creates one empty space through the domain store's `SPACE_ADMIN` approval. This is the
     * prerequisite for selection, ingestion and retrieval; without it a settings surface can only
     * edit spaces that can never exist.
     */
    suspend fun createSpace(displayName: String): AgentMemorySpaceCreateResult =
        AgentMemorySpaceCreateResult.Rejected(
            code = UNAVAILABLE_CODE,
            safeMessage = "记忆空间创建尚未接入。"
        )

    /**
     * Ingests one text body as `USER_TEXT` under a one-space `USER_IMPORT` approval. The domain
     * store owns normalization, chunking, size limits and the duplicate `(space, contentHash)`
     * no-op, so this adapter never duplicates those rules.
     */
    suspend fun ingestText(
        spaceId: String,
        title: String,
        text: String,
        sourceRef: String? = null
    ): AgentMemoryTextIngestResult = AgentMemoryTextIngestResult.Rejected(
        code = UNAVAILABLE_CODE,
        safeMessage = "记忆文本导入尚未接入。"
    )

    /**
     * Exports live items of [spaceIds] (all spaces when empty) into one bounded JSON package.
     * Revoked items are never exported. The package contains content only; ids/hashes are
     * recomputed on import by the authoritative store.
     */
    suspend fun exportMemories(
        spaceIds: List<String> = emptyList()
    ): AgentMemoryExportResult = AgentMemoryExportResult.Rejected(
        code = UNAVAILABLE_CODE,
        safeMessage = "记忆导出尚未接入。"
    )

    /** Imports one bounded JSON package as new spaces/items through `USER_IMPORT` approvals. */
    suspend fun importMemories(
        packageJson: String
    ): AgentMemoryImportResult = AgentMemoryImportResult.Rejected(
        code = UNAVAILABLE_CODE,
        safeMessage = "记忆导入尚未接入。"
    )

    suspend fun updateItem(
        spaceId: String,
        itemId: String,
        expectedRevision: Long,
        title: String?,
        text: String
    ): AgentMemoryUpdateResult = AgentMemoryUpdateResult.Rejected(
        code = "MEMORY_ADMIN_UNAVAILABLE",
        safeMessage = "记忆编辑尚未接入。"
    )

    /** Bounded list of one space's items; [query] is a literal keyword over title/text. */
    suspend fun listItems(
        spaceId: String,
        query: String? = null,
        offset: Int = 0,
        limit: Int = DEFAULT_ITEM_PAGE_SIZE,
        includeRevoked: Boolean = false
    ): AgentMemoryItemPageResult = AgentMemoryItemPageResult.Rejected(
        code = UNAVAILABLE_CODE,
        safeMessage = "记忆条目列表尚未接入。"
    )

    /** Soft delete (tombstone) under the caller's expected revision. */
    suspend fun deleteItem(
        spaceId: String,
        itemId: String,
        expectedRevision: Long
    ): AgentMemoryItemActionResult = AgentMemoryItemActionResult.Rejected(
        code = UNAVAILABLE_CODE,
        safeMessage = "记忆条目删除尚未接入。"
    )

    /** Undelete of a previously tombstoned item under the caller's expected revision. */
    suspend fun restoreItem(
        spaceId: String,
        itemId: String,
        expectedRevision: Long
    ): AgentMemoryItemActionResult = AgentMemoryItemActionResult.Rejected(
        code = UNAVAILABLE_CODE,
        safeMessage = "记忆条目恢复尚未接入。"
    )

    /** Runs the local extractor over [text]; the result is reviewable candidates, not stored items. */
    suspend fun submitExtraction(
        spaceId: String,
        text: String,
        sourceRef: String? = null
    ): AgentMemoryExtractionSubmitResult = AgentMemoryExtractionSubmitResult.Rejected(
        code = UNAVAILABLE_CODE,
        safeMessage = "记忆提取尚未接入。"
    )

    /** Pending candidate batch for [spaceId], if the surface submitted one in this process. */
    suspend fun listExtractionCandidates(spaceId: String): AgentMemoryCandidateListResult =
        AgentMemoryCandidateListResult.Rejected(
            code = UNAVAILABLE_CODE,
            safeMessage = "记忆提取审核尚未接入。"
        )

    /** Accepts exactly the reviewed candidates; acceptance is the only path that writes items. */
    suspend fun acceptExtractionCandidates(
        spaceId: String,
        candidateIds: List<String>
    ): AgentMemoryCandidateDecisionResult = AgentMemoryCandidateDecisionResult.Rejected(
        code = UNAVAILABLE_CODE,
        safeMessage = "记忆提取审核尚未接入。"
    )

    /** Dismisses reviewed candidates without writing anything. */
    suspend fun rejectExtractionCandidates(
        spaceId: String,
        candidateIds: List<String>
    ): AgentMemoryCandidateDecisionResult = AgentMemoryCandidateDecisionResult.Rejected(
        code = UNAVAILABLE_CODE,
        safeMessage = "记忆提取审核尚未接入。"
    )

    /**
     * Retrieval capability for diagnostics: [mode] is `HYBRID` only when a real embedder and vector
     * index are wired; otherwise `LEXICAL` with the typed [degradedReason]. This never implies a
     * model is installed — N15a must prove that separately.
     */
    suspend fun semanticDiagnostics(): AgentMemorySemanticDiagnostics =
        AgentMemorySemanticDiagnostics(
            mode = AgentMemorySemanticDiagnostics.MODE_LEXICAL,
            degradedReason = "NOT_CONFIGURED"
        )

    companion object {
        /** Default page size for the management list; the host may pass a smaller/larger bounded one. */
        const val DEFAULT_ITEM_PAGE_SIZE: Int = 20

        internal const val UNAVAILABLE_CODE: String = "MEMORY_ADMIN_UNAVAILABLE"

        /** Preview/test fallback only; production wiring must provide a real port. */
        val Unavailable: AgentMemoryAdminPort = object : AgentMemoryAdminPort {
            override suspend fun list(): AgentMemoryListResult =
                AgentMemoryListResult.Rejected(
                    code = UNAVAILABLE_CODE,
                    safeMessage = "记忆空间管理尚未接入。"
                )
        }
    }
}
