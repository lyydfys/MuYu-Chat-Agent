package com.muyuchat.mca.presentation

import android.content.Context
import com.muyuchat.feature.agent.workspace.AgentMemoryAdminPort
import com.muyuchat.feature.agent.workspace.AgentMemoryCandidateConfidence
import com.muyuchat.feature.agent.workspace.AgentMemoryCandidateDecision
import com.muyuchat.feature.agent.workspace.AgentMemoryCandidateDecisionEntry
import com.muyuchat.feature.agent.workspace.AgentMemoryCandidateDecisionResult
import com.muyuchat.feature.agent.workspace.AgentMemoryCandidateItem
import com.muyuchat.feature.agent.workspace.AgentMemoryCandidateListResult
import com.muyuchat.feature.agent.workspace.AgentMemoryExtractionSubmitResult
import com.muyuchat.feature.agent.workspace.AgentMemoryItemActionResult
import com.muyuchat.feature.agent.workspace.AgentMemoryItemPageResult
import com.muyuchat.feature.agent.workspace.AgentMemoryListResult
import com.muyuchat.feature.agent.workspace.AgentMemoryManagedItem
import com.muyuchat.feature.agent.workspace.AgentMemorySemanticDiagnostics
import com.muyuchat.feature.agent.workspace.AgentMemoryExportResult
import com.muyuchat.feature.agent.workspace.AgentMemoryImportResult
import com.muyuchat.feature.agent.workspace.AgentMemorySpaceCreateResult
import com.muyuchat.feature.agent.workspace.AgentMemorySpaceItem
import com.muyuchat.feature.agent.workspace.AgentMemoryTextIngestResult
import com.muyuchat.feature.agent.workspace.memory.MemoryItemDetail
import com.muyuchat.feature.agent.workspace.memory.MemoryItemReadResult
import com.muyuchat.feature.agent.workspace.memory.MemoryItemReader
import com.muyuchat.mca.McaRoomDatabase
import com.muyuchat.mca.agent.memory.AgentMemoryCandidateConfidence as DomainConfidence
import com.muyuchat.mca.agent.memory.AgentMemoryErrorCodes
import com.muyuchat.mca.agent.memory.AgentMemoryExtractionCandidate
import com.muyuchat.mca.agent.memory.AgentMemoryExtractionCommitOutcome
import com.muyuchat.mca.agent.memory.AgentMemoryExtractionCommitStatus
import com.muyuchat.mca.agent.memory.AgentMemoryExtractionOrigin
import com.muyuchat.mca.agent.memory.AgentMemoryExtractionOutcome
import com.muyuchat.mca.agent.memory.AgentMemoryExtractionRequest
import com.muyuchat.mca.agent.memory.AgentMemoryExtractor
import com.muyuchat.mca.agent.memory.AgentMemoryItemPageOutcome
import com.muyuchat.mca.agent.memory.AgentMemoryLimits
import com.muyuchat.mca.agent.memory.AgentMemoryMutationOutcome
import com.muyuchat.mca.agent.memory.AgentMemoryPersistence
import com.muyuchat.mca.agent.memory.AgentMemoryStore
import com.muyuchat.mca.agent.memory.AgentMemoryTextEmbedder
import com.muyuchat.mca.agent.memory.AgentMemoryVectorIndex
import com.muyuchat.mca.agent.memory.AgentMemorySemanticStatus
import com.muyuchat.mca.agent.memory.AgentMemorySemanticWiring
import com.muyuchat.mca.agent.memory.AgentMemoryWriteApproval
import com.muyuchat.mca.agent.memory.AgentMemoryWriteReason
import com.muyuchat.mca.agent.memory.data.RoomAgentMemoryPersistence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-side adapter for the Agent memory-spaces settings surface.
 *
 * Two responsibilities, both **user-initiated** and both routed through the domain store's existing
 * transaction/approval rules:
 *
 * - spaces + item management: list (bounded, keyword, include-revoked), edit, delete (tombstone) and
 *   restore (undelete), each with revision CAS and a typed rejection;
 * - extraction review: submit text, list the pending candidates of this process, accept (the only
 *   write path: `AgentMemoryStore.ingest` through [AgentMemoryExtractor.commit]) or dismiss.
 *
 * It never becomes a second memory writer: every mutation mints a one-space [AgentMemoryWriteApproval]
 * here (`USER_EDIT`/`USER_DELETE`/`USER_RESTORE`/`USER_EXTRACT_ACCEPT`) and delegates to the store.
 * Candidate batches live only in this adapter's memory: schema 29 has no proposal table, so a process
 * restart drops unreviewed candidates (recorded as NOT_RUN in the N16 card).
 */
class AgentMemoryAdminService(
    private val store: AgentMemoryStore,
    private val persistence: AgentMemoryPersistence,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val extractor: AgentMemoryExtractor = AgentMemoryExtractor(store),
    private val clock: () -> Long = System::currentTimeMillis
) : AgentMemoryAdminPort, MemoryItemReader {

    private val pendingMutex = Mutex()
    private val pendingBatches = LinkedHashMap<String, PendingBatch>()

    private data class PendingBatch(
        val candidates: List<AgentMemoryExtractionCandidate>,
        val submittedAt: Long
    )

    override suspend fun list(): AgentMemoryListResult = withContext(ioDispatcher) {
        try {
            // AgentMemoryStore.listSpaces() maps a storage failure to an empty list. Re-read the
            // same bounded query through persistence only when the store reports no spaces, so a
            // real empty list stays empty and a failed read becomes the typed error below.
            val fromStore = store.listSpaces()
            val spaces = if (fromStore.isEmpty()) {
                persistence.listSpaces(AgentMemoryLimits.MAX_SPACES)
            } else {
                fromStore
            }
            AgentMemoryListResult.Loaded(
                spaces.map { space ->
                    AgentMemorySpaceItem(
                        spaceId = space.spaceId,
                        displayName = space.displayName,
                        itemCount = persistence.liveItemCount(space.spaceId)
                    )
                }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AgentMemoryListResult.Rejected(
                code = FAILURE_CODE,
                safeMessage = FAILURE_MESSAGE
            )
        }
    }

    override suspend fun createSpace(displayName: String): AgentMemorySpaceCreateResult =
        withContext(ioDispatcher) {
            val approval = AgentMemoryWriteApproval.spaceAdmin(
                principal = USER_PRINCIPAL,
                reason = AgentMemoryWriteReason.SPACE_ADMIN,
                approvedAtMillis = clock()
            )
            try {
                when (val outcome = store.createSpace(displayName, approval)) {
                    is com.muyuchat.mca.agent.memory.AgentMemorySpaceOutcome.Created ->
                        AgentMemorySpaceCreateResult.Created(
                            spaceId = outcome.space.spaceId,
                            displayName = outcome.space.displayName
                        )
                    is com.muyuchat.mca.agent.memory.AgentMemorySpaceOutcome.Rejected ->
                        AgentMemorySpaceCreateResult.Rejected(
                            code = outcome.error.code,
                            safeMessage = outcome.error.safeMessage
                        )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                AgentMemorySpaceCreateResult.Rejected(
                    code = FAILURE_CODE,
                    safeMessage = CREATE_FAILURE_MESSAGE
                )
            }
        }

    override suspend fun ingestText(
        spaceId: String,
        title: String,
        text: String,
        sourceRef: String?
    ): AgentMemoryTextIngestResult = withContext(ioDispatcher) {
        val approval = itemsApproval(spaceId, AgentMemoryWriteReason.USER_IMPORT)
        try {
            when (
                val outcome = store.ingest(
                    spaceId = spaceId,
                    request = com.muyuchat.mca.agent.memory.AgentMemoryIngestRequest(
                        title = title,
                        text = text,
                        sourceKind = com.muyuchat.mca.agent.memory.AgentMemorySourceKind.USER_TEXT,
                        sourceRef = sourceRef
                    ),
                    approval = approval
                )
            ) {
                is com.muyuchat.mca.agent.memory.AgentMemoryIngestOutcome.Ingested ->
                    AgentMemoryTextIngestResult.Stored(
                        spaceId = spaceId,
                        memoryId = outcome.item.memoryId,
                        chunkCount = outcome.chunkCount
                    )
                is com.muyuchat.mca.agent.memory.AgentMemoryIngestOutcome.Deduplicated ->
                    AgentMemoryTextIngestResult.Deduplicated(
                        spaceId = spaceId,
                        memoryId = outcome.memoryId,
                        revoked = outcome.revoked
                    )
                is com.muyuchat.mca.agent.memory.AgentMemoryIngestOutcome.Rejected ->
                    AgentMemoryTextIngestResult.Rejected(
                        code = outcome.error.code,
                        safeMessage = outcome.error.safeMessage
                    )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AgentMemoryTextIngestResult.Rejected(
                code = FAILURE_CODE,
                safeMessage = INGEST_FAILURE_MESSAGE
            )
        }
    }

    override suspend fun exportMemories(spaceIds: List<String>): AgentMemoryExportResult =
        withContext(ioDispatcher) {
            try {
                val requested = spaceIds.map(String::trim).filter(String::isNotEmpty)
                    .distinct().sorted()
                val spaces = if (requested.isEmpty()) {
                    persistence.listSpaces(AgentMemoryLimits.MAX_SPACES)
                } else {
                    requested.map { spaceId ->
                        persistence.findSpace(spaceId)
                            ?: return@withContext AgentMemoryExportResult.Rejected(
                                EXPORT_EMPTY_CODE,
                                "要导出的记忆空间不存在。"
                            )
                    }
                }
                if (spaces.isEmpty()) {
                    return@withContext AgentMemoryExportResult.Rejected(
                        EXPORT_EMPTY_CODE,
                        "没有可导出的记忆空间。"
                    )
                }
                var totalItems = 0
                val spaceArray = JSONArray()
                for (space in spaces) {
                    val count = persistence.countItems(space.spaceId, null, false)
                    if (totalItems + count > MAX_EXPORT_ITEMS) {
                        return@withContext AgentMemoryExportResult.Rejected(
                            EXPORT_LIMIT_CODE,
                            "一次最多导出 $MAX_EXPORT_ITEMS 条记忆。"
                        )
                    }
                    val items = ArrayList<com.muyuchat.mca.agent.memory.AgentMemoryItemRecord>()
                    var offset = 0
                    while (offset < count) {
                        val page = persistence.listItemPage(
                            spaceId = space.spaceId,
                            query = null,
                            includeRevoked = false,
                            limit = EXPORT_PAGE_SIZE,
                            offset = offset
                        )
                        if (page.isEmpty()) break
                        items += page
                        offset += page.size
                    }
                    val itemArray = JSONArray()
                    items.forEach { item ->
                        itemArray.put(
                            JSONObject()
                                .put("title", item.title)
                                .put("text", item.text)
                                .put("sourceRef", item.sourceRef ?: JSONObject.NULL)
                        )
                    }
                    spaceArray.put(
                        JSONObject()
                            .put("displayName", space.displayName)
                            .put("items", itemArray)
                    )
                    totalItems += items.size
                }
                val json = JSONObject()
                    .put("schema", MEMORY_PACKAGE_SCHEMA)
                    .put("spaces", spaceArray)
                    .toString()
                if (json.toByteArray(Charsets.UTF_8).size > MAX_PACKAGE_BYTES) {
                    return@withContext AgentMemoryExportResult.Rejected(
                        EXPORT_LIMIT_CODE,
                        "导出的记忆包超过 ${MAX_PACKAGE_BYTES / 1024} KiB 上限。"
                    )
                }
                AgentMemoryExportResult.Exported(
                    packageJson = json,
                    spaceCount = spaces.size,
                    itemCount = totalItems
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                AgentMemoryExportResult.Rejected(
                    EXPORT_FAILED_CODE,
                    "记忆导出失败，请稍后重试。"
                )
            }
        }

    override suspend fun importMemories(packageJson: String): AgentMemoryImportResult =
        withContext(ioDispatcher) {
            if (packageJson.toByteArray(Charsets.UTF_8).size > MAX_PACKAGE_BYTES) {
                return@withContext AgentMemoryImportResult.Rejected(
                    IMPORT_LIMIT_CODE,
                    "记忆包超过 ${MAX_PACKAGE_BYTES / 1024} KiB 上限。"
                )
            }
            val parsed = parseImportPackage(packageJson)
                ?: return@withContext AgentMemoryImportResult.Rejected(
                    IMPORT_INVALID_CODE,
                    "记忆包格式不正确或超出限制。"
                )
            var spacesCreated = 0
            var itemsStored = 0
            var deduplicated = 0
            try {
                for (spaceSpec in parsed) {
                    val created = when (
                        val outcome = store.createSpace(
                            spaceSpec.displayName,
                            AgentMemoryWriteApproval.spaceAdmin(
                                principal = USER_PRINCIPAL,
                                reason = AgentMemoryWriteReason.SPACE_ADMIN,
                                approvedAtMillis = clock()
                            )
                        )
                    ) {
                        is com.muyuchat.mca.agent.memory.AgentMemorySpaceOutcome.Created -> outcome
                        is com.muyuchat.mca.agent.memory.AgentMemorySpaceOutcome.Rejected ->
                            return@withContext partialImport(
                                spacesCreated,
                                itemsStored,
                                deduplicated,
                                outcome.error.code,
                                outcome.error.safeMessage
                            )
                    }
                    spacesCreated += 1
                    val spaceId = created.space.spaceId
                    for (item in spaceSpec.items) {
                        val approval = itemsApproval(spaceId, AgentMemoryWriteReason.USER_IMPORT)
                        when (
                            val outcome = store.ingest(
                                spaceId = spaceId,
                                request = com.muyuchat.mca.agent.memory.AgentMemoryIngestRequest(
                                    title = item.title,
                                    text = item.text,
                                    sourceKind =
                                        com.muyuchat.mca.agent.memory.AgentMemorySourceKind.USER_TEXT,
                                    sourceRef = item.sourceRef
                                ),
                                approval = approval
                            )
                        ) {
                            is com.muyuchat.mca.agent.memory.AgentMemoryIngestOutcome.Ingested ->
                                itemsStored += 1
                            is com.muyuchat.mca.agent.memory.AgentMemoryIngestOutcome.Deduplicated ->
                                deduplicated += 1
                            is com.muyuchat.mca.agent.memory.AgentMemoryIngestOutcome.Rejected ->
                                return@withContext partialImport(
                                    spacesCreated,
                                    itemsStored,
                                    deduplicated,
                                    outcome.error.code,
                                    outcome.error.safeMessage
                                )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@withContext partialImport(
                    spacesCreated,
                    itemsStored,
                    deduplicated,
                    IMPORT_FAILED_CODE,
                    "记忆导入中断，已写入的空间和条目已保留。"
                )
            }
            AgentMemoryImportResult.Imported(
                spaceCount = spacesCreated,
                itemCount = itemsStored,
                deduplicated = deduplicated
            )
        }

    private data class ImportedSpace(
        val displayName: String,
        val items: List<ImportedItem>
    )

    private data class ImportedItem(
        val title: String,
        val text: String,
        val sourceRef: String?
    )

    /** Strict, bounded parse before any write. Null means the whole package is rejected. */
    private fun parseImportPackage(packageJson: String): List<ImportedSpace>? {
        val root = try {
            JSONObject(packageJson)
        } catch (_: Throwable) {
            return null
        }
        if (root.keys().asSequence().toSet() != setOf("schema", "spaces")) return null
        if (root.optInt("schema", -1) != MEMORY_PACKAGE_SCHEMA) return null
        val spaces = root.optJSONArray("spaces") ?: return null
        if (spaces.length() !in 1..MAX_IMPORT_SPACES) return null
        return (0 until spaces.length()).map { index ->
            val space = spaces.optJSONObject(index) ?: return null
            if (space.keys().asSequence().toSet() != setOf("displayName", "items")) return null
            val displayName = space.optString("displayName").trim()
            if (displayName.isEmpty() ||
                displayName.length > AgentMemoryLimits.MAX_SPACE_NAME_CHARS ||
                displayName.any { it.isISOControl() }
            ) {
                return null
            }
            val items = space.optJSONArray("items") ?: return null
            if (items.length() > MAX_IMPORT_ITEMS_PER_SPACE) return null
            val parsedItems = (0 until items.length()).map { itemIndex ->
                val item = items.optJSONObject(itemIndex) ?: return null
                if (item.keys().asSequence().toSet() != setOf("title", "text", "sourceRef")) {
                    return null
                }
                val title = item.optString("title")
                val text = item.optString("text")
                val sourceRef = if (item.isNull("sourceRef")) {
                    null
                } else {
                    item.optString("sourceRef")
                }
                if (title.length > AgentMemoryLimits.MAX_TITLE_CHARS ||
                    text.isBlank() ||
                    text.toByteArray(Charsets.UTF_8).size > AgentMemoryLimits.MAX_TEXT_BYTES ||
                    (sourceRef != null &&
                        sourceRef.length > AgentMemoryLimits.MAX_SOURCE_REF_CHARS)
                ) {
                    return null
                }
                ImportedItem(title = title, text = text, sourceRef = sourceRef)
            }
            ImportedSpace(displayName = displayName, items = parsedItems)
        }
    }

    private fun partialImport(
        spaces: Int,
        items: Int,
        deduplicated: Int,
        code: String,
        message: String
    ): AgentMemoryImportResult.PartiallyImported =
        AgentMemoryImportResult.PartiallyImported(
            spaceCount = spaces,
            itemCount = items,
            deduplicated = deduplicated,
            errorCode = code,
            safeMessage = message.take(200)
        )

    /**
     * User-initiated edit from the memory settings surface.
     *
     * The write approval is minted here, not by a model: this method is only reachable from the user
     * surface, binds exactly one concrete space and carries reason `USER_EDIT`. A stale revision is
     * returned as a typed rejection so the UI can reload instead of overwriting.
     */
    override suspend fun updateItem(
        spaceId: String,
        itemId: String,
        expectedRevision: Long,
        title: String?,
        text: String
    ): com.muyuchat.feature.agent.workspace.AgentMemoryUpdateResult = withContext(ioDispatcher) {
        try {
            val approval = itemsApproval(spaceId, AgentMemoryWriteReason.USER_EDIT)
            when (val outcome = store.updateItem(spaceId, itemId, expectedRevision, title, text, approval)) {
                is AgentMemoryMutationOutcome.Applied -> {
                    val updated = persistence.findItem(itemId)
                    com.muyuchat.feature.agent.workspace.AgentMemoryUpdateResult.Updated(
                        spaceId = spaceId,
                        itemId = itemId,
                        revision = updated?.revision ?: (expectedRevision + 1L)
                    )
                }
                is AgentMemoryMutationOutcome.Rejected ->
                    com.muyuchat.feature.agent.workspace.AgentMemoryUpdateResult.Rejected(
                        code = outcome.error.code,
                        safeMessage = outcome.error.safeMessage
                    )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            com.muyuchat.feature.agent.workspace.AgentMemoryUpdateResult.Rejected(
                code = FAILURE_CODE,
                safeMessage = UPDATE_FAILURE_MESSAGE
            )
        }
    }

    /** Bounded item page for one space; [query] is a literal keyword, not a SQL fragment. */
    override suspend fun listItems(
        spaceId: String,
        query: String?,
        offset: Int,
        limit: Int,
        includeRevoked: Boolean
    ): AgentMemoryItemPageResult = withContext(ioDispatcher) {
        try {
            when (
                val outcome = store.listItemPage(
                    spaceId = spaceId,
                    query = query,
                    includeRevoked = includeRevoked,
                    offset = offset,
                    limit = limit
                )
            ) {
                is AgentMemoryItemPageOutcome.Found -> AgentMemoryItemPageResult.Loaded(
                    spaceId = outcome.page.spaceId,
                    items = outcome.page.items.map { item ->
                        AgentMemoryManagedItem(
                            spaceId = item.spaceId,
                            itemId = item.memoryId,
                            title = item.title,
                            excerpt = item.excerpt,
                            revision = item.revision,
                            updatedAt = item.updatedAt,
                            sourceKind = item.sourceKind.name,
                            revoked = item.revoked,
                            hasSourceRef = item.hasSourceRef
                        )
                    },
                    total = outcome.page.total,
                    offset = outcome.page.offset,
                    limit = outcome.page.limit,
                    hasMore = outcome.page.hasMore
                )
                is AgentMemoryItemPageOutcome.Rejected -> AgentMemoryItemPageResult.Rejected(
                    code = outcome.error.code,
                    safeMessage = outcome.error.safeMessage
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AgentMemoryItemPageResult.Rejected(code = FAILURE_CODE, safeMessage = ITEMS_FAILURE_MESSAGE)
        }
    }

    /** Soft delete: a tombstone keeps the item (and its chunks) restorable for audit. */
    override suspend fun deleteItem(
        spaceId: String,
        itemId: String,
        expectedRevision: Long
    ): AgentMemoryItemActionResult = withContext(ioDispatcher) {
        mutateItem(spaceId, itemId, expectedRevision, AgentMemoryWriteReason.USER_DELETE) { approval ->
            store.revokeItem(spaceId, itemId, expectedRevision, approval)
        }
    }

    /** Undelete of a tombstoned item; a revision mismatch is a typed conflict. */
    override suspend fun restoreItem(
        spaceId: String,
        itemId: String,
        expectedRevision: Long
    ): AgentMemoryItemActionResult = withContext(ioDispatcher) {
        mutateItem(spaceId, itemId, expectedRevision, AgentMemoryWriteReason.USER_RESTORE) { approval ->
            store.restoreItem(spaceId, itemId, expectedRevision, approval)
        }
    }

    /** Runs the local extractor; the returned candidates are **not** stored. */
    override suspend fun submitExtraction(
        spaceId: String,
        text: String,
        sourceRef: String?
    ): AgentMemoryExtractionSubmitResult = withContext(ioDispatcher) {
        try {
            when (val outcome = extractor.extract(AgentMemoryExtractionRequest(spaceId, text, sourceRef))) {
                is AgentMemoryExtractionOutcome.Proposed -> {
                    replaceBatch(spaceId, outcome.result.candidates)
                    AgentMemoryExtractionSubmitResult.Proposed(
                        candidates = outcome.result.candidates.map(::toCandidateItem),
                        truncated = outcome.result.truncated,
                        modelStatus = outcome.result.modelStatus.name
                    )
                }
                is AgentMemoryExtractionOutcome.Rejected ->
                    AgentMemoryExtractionSubmitResult.Rejected(
                        code = outcome.error.code,
                        safeMessage = outcome.error.safeMessage
                    )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AgentMemoryExtractionSubmitResult.Rejected(
                code = FAILURE_CODE,
                safeMessage = EXTRACTION_FAILURE_MESSAGE
            )
        }
    }

    /** Pending candidate batch for [spaceId]; empty when nothing was submitted in this process. */
    override suspend fun listExtractionCandidates(spaceId: String): AgentMemoryCandidateListResult =
        withContext(ioDispatcher) {
            val batch = pendingMutex.withLock { pendingBatches[spaceId] }
            if (batch == null) {
                AgentMemoryCandidateListResult.Loaded(spaceId, emptyList(), submittedAt = null)
            } else {
                AgentMemoryCandidateListResult.Loaded(
                    spaceId = spaceId,
                    candidates = batch.candidates.map(::toCandidateItem),
                    submittedAt = batch.submittedAt
                )
            }
        }

    /**
     * Accepts exactly the reviewed candidates of the pending batch.
     *
     * Every id must belong to the current batch (all-or-nothing validation) before the store sees
     * anything. Candidates the store reports as already present stay removed from the batch; a
     * store-rejected candidate stays pending so the user can retry or dismiss it.
     */
    override suspend fun acceptExtractionCandidates(
        spaceId: String,
        candidateIds: List<String>
    ): AgentMemoryCandidateDecisionResult = withContext(ioDispatcher) {
        val batch = pendingMutex.withLock { pendingBatches[spaceId] }
            ?: return@withContext AgentMemoryCandidateDecisionResult.Rejected(
                code = AgentMemoryErrorCodes.CANDIDATE_INVALID,
                safeMessage = "没有待审核的提取候选。"
            )
        val selected = selectFromBatch(batch, candidateIds)
            ?: return@withContext AgentMemoryCandidateDecisionResult.Rejected(
                code = AgentMemoryErrorCodes.CANDIDATE_INVALID,
                safeMessage = "所选候选不属于本次提取结果。"
            )
        val approval = itemsApproval(spaceId, AgentMemoryWriteReason.USER_EXTRACT_ACCEPT)
        val commit = extractor.commit(spaceId, selected, approval)
        when (commit) {
            is AgentMemoryExtractionCommitOutcome.Rejected ->
                AgentMemoryCandidateDecisionResult.Rejected(
                    code = commit.error.code,
                    safeMessage = commit.error.safeMessage
                )
            is AgentMemoryExtractionCommitOutcome.Applied -> {
                val entries = commit.result.entries
                val storedIds = entries
                    .filter { it.status != AgentMemoryExtractionCommitStatus.REJECTED }
                    .map { it.candidateId }
                    .toSet()
                removeFromBatch(spaceId, storedIds)
                AgentMemoryCandidateDecisionResult.Applied(
                    entries = entries.map { entry ->
                        AgentMemoryCandidateDecisionEntry(
                            candidateId = entry.candidateId,
                            decision = when (entry.status) {
                                AgentMemoryExtractionCommitStatus.STORED ->
                                    AgentMemoryCandidateDecision.STORED
                                AgentMemoryExtractionCommitStatus.ALREADY_PRESENT ->
                                    AgentMemoryCandidateDecision.ALREADY_PRESENT
                                AgentMemoryExtractionCommitStatus.REJECTED ->
                                    AgentMemoryCandidateDecision.REJECTED
                            },
                            itemId = entry.memoryId,
                            errorCode = entry.errorCode
                        )
                    },
                    stored = commit.result.stored,
                    alreadyPresent = commit.result.alreadyPresent,
                    rejected = commit.result.rejected,
                    dismissed = 0
                )
            }
        }
    }

    /** Dismisses reviewed candidates; nothing is written and the rest of the batch stays. */
    override suspend fun rejectExtractionCandidates(
        spaceId: String,
        candidateIds: List<String>
    ): AgentMemoryCandidateDecisionResult = withContext(ioDispatcher) {
        val batch = pendingMutex.withLock { pendingBatches[spaceId] }
            ?: return@withContext AgentMemoryCandidateDecisionResult.Rejected(
                code = AgentMemoryErrorCodes.CANDIDATE_INVALID,
                safeMessage = "没有待审核的提取候选。"
            )
        val selected = selectFromBatch(batch, candidateIds)
            ?: return@withContext AgentMemoryCandidateDecisionResult.Rejected(
                code = AgentMemoryErrorCodes.CANDIDATE_INVALID,
                safeMessage = "所选候选不属于本次提取结果。"
            )
        removeFromBatch(spaceId, selected.map { it.candidateId }.toSet())
        AgentMemoryCandidateDecisionResult.Applied(
            entries = selected.map { candidate ->
                AgentMemoryCandidateDecisionEntry(
                    candidateId = candidate.candidateId,
                    decision = AgentMemoryCandidateDecision.DISMISSED
                )
            },
            stored = 0,
            alreadyPresent = 0,
            rejected = 0,
            dismissed = selected.size
        )
    }

    /**
     * Retrieval capability for the settings surface: a lexical-only store explains itself with a
     * typed degradation reason instead of showing an empty "semantic" state. No embedder is bundled,
     * so production reports `LEXICAL/NOT_CONFIGURED` until N15a lands.
     */
    override suspend fun semanticDiagnostics(): AgentMemorySemanticDiagnostics = withContext(ioDispatcher) {
        when (val status = store.semanticStatus()) {
            is AgentMemorySemanticStatus.Disabled -> AgentMemorySemanticDiagnostics(
                mode = AgentMemorySemanticDiagnostics.MODE_LEXICAL,
                degradedReason = status.reason.name
            )
            is AgentMemorySemanticStatus.Unavailable -> AgentMemorySemanticDiagnostics(
                mode = AgentMemorySemanticDiagnostics.MODE_LEXICAL,
                degradedReason = status.reason.name
            )
            is AgentMemorySemanticStatus.Ready -> AgentMemorySemanticDiagnostics(
                mode = AgentMemorySemanticDiagnostics.MODE_HYBRID,
                fingerprint = status.fingerprint,
                indexedChunks = status.recordCount
            )
        }
    }

    /**
     * Full-body read for the management editor.
     *
     * The list port only exposes bounded excerpts, so the editor asks for exactly one item by the
     * `(spaceId, itemId)` pair it received from a list row. A foreign or missing item is reported as
     * not found (no existence probing across spaces); a revoked item is still readable so the page can
     * explain why editing is unavailable.
     */
    override suspend fun readItem(spaceId: String, itemId: String): MemoryItemReadResult =
        withContext(ioDispatcher) {
            try {
                val item = persistence.findItem(itemId)
                if (item == null || item.spaceId != spaceId) {
                    MemoryItemReadResult.Rejected(
                        code = AgentMemoryErrorCodes.ITEM_NOT_FOUND,
                        safeMessage = "该记忆条目不属于当前记忆空间。"
                    )
                } else {
                    MemoryItemReadResult.Loaded(
                        MemoryItemDetail(
                            spaceId = item.spaceId,
                            itemId = item.memoryId,
                            title = item.title,
                            text = item.text,
                            revision = item.revision,
                            updatedAt = item.updatedAt,
                            sourceKind = item.sourceKind.name,
                            sourceRef = item.sourceRef,
                            revoked = item.revoked
                        )
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                MemoryItemReadResult.Rejected(
                    code = FAILURE_CODE,
                    safeMessage = READ_FAILURE_MESSAGE
                )
            }
        }

    private suspend fun mutateItem(
        spaceId: String,
        itemId: String,
        expectedRevision: Long,
        reason: AgentMemoryWriteReason,
        action: suspend (AgentMemoryWriteApproval) -> AgentMemoryMutationOutcome
    ): AgentMemoryItemActionResult {
        return try {
            when (val outcome = action(itemsApproval(spaceId, reason))) {
                is AgentMemoryMutationOutcome.Applied -> AgentMemoryItemActionResult.Applied(
                    spaceId = spaceId,
                    itemId = itemId,
                    revision = persistence.findItem(itemId)?.revision ?: (expectedRevision + 1L)
                )
                is AgentMemoryMutationOutcome.Rejected -> AgentMemoryItemActionResult.Rejected(
                    code = outcome.error.code,
                    safeMessage = outcome.error.safeMessage
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            AgentMemoryItemActionResult.Rejected(
                code = FAILURE_CODE,
                safeMessage = ACTION_FAILURE_MESSAGE
            )
        }
    }

    /**
     * All-or-nothing selection: an unknown/duplicate/empty/oversized id list is rejected before any
     * write, and the returned candidates keep the batch order (never the caller's order).
     */
    private fun selectFromBatch(
        batch: PendingBatch,
        candidateIds: List<String>
    ): List<AgentMemoryExtractionCandidate>? {
        if (candidateIds.isEmpty() ||
            candidateIds.size > AgentMemoryLimits.MAX_EXTRACTION_CANDIDATES +
            AgentMemoryLimits.MAX_EXTRACTION_MODEL_PROPOSALS ||
            candidateIds.distinct().size != candidateIds.size
        ) {
            return null
        }
        val wanted = candidateIds.toSet()
        val byId = batch.candidates.associateBy { it.candidateId }
        if (!byId.keys.containsAll(wanted)) return null
        return batch.candidates.filter { it.candidateId in wanted }
    }

    private suspend fun replaceBatch(spaceId: String, candidates: List<AgentMemoryExtractionCandidate>) {
        pendingMutex.withLock {
            if (candidates.isEmpty()) {
                pendingBatches.remove(spaceId)
                return@withLock
            }
            if (!pendingBatches.containsKey(spaceId) && pendingBatches.size >= MAX_PENDING_BATCHES) {
                pendingBatches.keys.firstOrNull()?.let { oldest -> pendingBatches.remove(oldest) }
            }
            pendingBatches[spaceId] = PendingBatch(candidates, clock())
        }
    }

    private suspend fun removeFromBatch(spaceId: String, candidateIds: Set<String>) {
        pendingMutex.withLock {
            val current = pendingBatches[spaceId] ?: return@withLock
            val remaining = current.candidates.filterNot { it.candidateId in candidateIds }
            if (remaining.isEmpty()) {
                pendingBatches.remove(spaceId)
            } else {
                pendingBatches[spaceId] = current.copy(candidates = remaining)
            }
        }
    }

    private fun toCandidateItem(candidate: AgentMemoryExtractionCandidate): AgentMemoryCandidateItem =
        AgentMemoryCandidateItem(
            candidateId = candidate.candidateId,
            text = candidate.text,
            title = candidate.title,
            confidence = when (candidate.confidence) {
                DomainConfidence.LOW -> AgentMemoryCandidateConfidence.LOW
                DomainConfidence.MEDIUM -> AgentMemoryCandidateConfidence.MEDIUM
                DomainConfidence.HIGH -> AgentMemoryCandidateConfidence.HIGH
            },
            originLabel = when (val origin = candidate.origin) {
                is AgentMemoryExtractionOrigin.Rule -> origin.ruleId
                is AgentMemoryExtractionOrigin.Model -> "model:${origin.fingerprint}"
            },
            duplicateOfItemId = candidate.duplicateOf?.memoryId,
            duplicateRevoked = candidate.duplicateOf?.revoked ?: false
        )

    private fun itemsApproval(spaceId: String, reason: AgentMemoryWriteReason): AgentMemoryWriteApproval =
        AgentMemoryWriteApproval.items(
            spaceId = spaceId,
            principal = USER_PRINCIPAL,
            reason = reason,
            approvedAtMillis = clock()
        )

    companion object {
        /** Same safe copy the feature coordinator uses for a thrown list failure. */
        internal const val FAILURE_CODE: String = "MEMORY_ADMIN_FAILED"
        internal const val FAILURE_MESSAGE: String = "记忆空间列表暂时无法载入，请稍后重试。"
        internal const val CREATE_FAILURE_MESSAGE: String = "记忆空间创建失败，请稍后重试。"
        internal const val INGEST_FAILURE_MESSAGE: String = "记忆文本导入失败，请稍后重试。"
        internal const val EXPORT_EMPTY_CODE: String = "MEMORY_EXPORT_EMPTY"
        internal const val EXPORT_LIMIT_CODE: String = "MEMORY_EXPORT_LIMIT"
        internal const val EXPORT_FAILED_CODE: String = "MEMORY_EXPORT_FAILED"
        internal const val IMPORT_INVALID_CODE: String = "MEMORY_IMPORT_INVALID"
        internal const val IMPORT_LIMIT_CODE: String = "MEMORY_IMPORT_LIMIT"
        internal const val IMPORT_FAILED_CODE: String = "MEMORY_IMPORT_FAILED"
        internal const val MEMORY_PACKAGE_SCHEMA: Int = 1
        internal const val MAX_PACKAGE_BYTES: Int = 512 * 1024
        internal const val MAX_EXPORT_ITEMS: Int = 500
        internal const val MAX_IMPORT_SPACES: Int = 32
        internal const val MAX_IMPORT_ITEMS_PER_SPACE: Int = 200
        internal const val EXPORT_PAGE_SIZE: Int = 100
        internal const val UPDATE_FAILURE_MESSAGE: String = "记忆条目保存失败，请稍后重试。"
        internal const val ITEMS_FAILURE_MESSAGE: String = "记忆条目列表暂时无法载入，请稍后重试。"
        internal const val ACTION_FAILURE_MESSAGE: String = "记忆条目操作失败，请稍后重试。"
        internal const val EXTRACTION_FAILURE_MESSAGE: String = "记忆提取失败，请稍后重试。"
        internal const val READ_FAILURE_MESSAGE: String = "记忆全文读取失败，请稍后重试。"

        /** At most one pending batch per addressable space; older unreviewed batches are dropped. */
        internal const val MAX_PENDING_BATCHES: Int = 16

        /**
         * Production construction over the shared database. The adapter reads spaces through the
         * domain store and item counts through the same persistence, which is the only count query.
         */
        private const val USER_PRINCIPAL: String = "user.memory-admin"

        fun create(
            context: Context,
            embedder: AgentMemoryTextEmbedder? = null,
            vectorIndex: AgentMemoryVectorIndex? = null
        ): AgentMemoryAdminService {
            val database = McaRoomDatabase.get(context)
            val persistence = RoomAgentMemoryPersistence(database, database.agentMemoryDao())
            // Selection policy, not a fake embedder: with no local text-embedding backend the wiring
            // yields no source and the store stays lexical. The composition root may pass a real
            // embedder/index here once N15a proves one.
            val semantic = AgentMemorySemanticWiring.select(embedder, vectorIndex)
            return AgentMemoryAdminService(
                store = AgentMemoryStore(persistence, semantic = semantic.source),
                persistence = persistence
            )
        }
    }
}
