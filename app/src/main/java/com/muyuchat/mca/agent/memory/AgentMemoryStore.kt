package com.muyuchat.mca.agent.memory

import com.muyuchat.mca.WorldBookResolver
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Creation outcome of one memory space. */
sealed interface AgentMemorySpaceOutcome {
    data class Created(val space: AgentMemorySpaceRecord) : AgentMemorySpaceOutcome

    data class Rejected(val error: AgentMemoryError) : AgentMemorySpaceOutcome
}

/** Outcome of one bounded ingest. A duplicate is a *successful no-op*, not an error. */
sealed interface AgentMemoryIngestOutcome {
    data class Ingested(val item: AgentMemoryItemRecord, val chunkCount: Int) : AgentMemoryIngestOutcome

    /** The same `(spaceId, sourceHash)` already exists; `revoked` reports its current state. */
    data class Deduplicated(val memoryId: String, val revoked: Boolean) : AgentMemoryIngestOutcome

    data class Rejected(val error: AgentMemoryError) : AgentMemoryIngestOutcome
}

/** Outcome of revoke/delete. */
sealed interface AgentMemoryMutationOutcome {
    data object Applied : AgentMemoryMutationOutcome

    data class Rejected(val error: AgentMemoryError) : AgentMemoryMutationOutcome
}

/** Outcome of one bounded lexical search. */
sealed interface AgentMemorySearchOutcome {
    data class Found(val result: AgentMemorySearchResult) : AgentMemorySearchOutcome

    data class Rejected(val error: AgentMemoryError) : AgentMemorySearchOutcome
}

/** Outcome of one exact-item read inside the frozen selection. */
sealed interface AgentMemoryGetOutcome {
    data class Found(val item: AgentMemoryItemRecord) : AgentMemoryGetOutcome

    data class Rejected(val error: AgentMemoryError) : AgentMemoryGetOutcome
}

/**
 * Memory spaces, items and local retrieval (plan §7.1/§7.3/§7.4).
 *
 * Retrieval is lexical by default. An optional [AgentMemorySemanticCandidateSource] can add vector
 * candidates through reciprocal-rank fusion, but every semantic failure falls back to the untouched
 * lexical result, so a missing or broken local embedder never removes an existing capability.
 *
 * Ownership: this store is the only writer of memory content. It is constructed by the app
 * composition root and shared by the admin surface and the `memory.search` executor. The model can
 * only reach the read path, and only through a frozen [AgentMemoryReadGrant].
 *
 * Safety rules enforced here, not in a caller:
 * - an oversized text/chunk/item is rejected with a typed error instead of being truncated;
 * - ingest dedupes on `(spaceId, sha256(normalized text))` so the same source never produces two
 *   items, including after a revoke;
 * - revoked items disappear from retrieval but remain readable for audit;
 * - retrieval never leaves the granted scope, is bounded by candidate/page/token caps and is
 *   deterministic.
 */
class AgentMemoryStore(
    private val persistence: AgentMemoryPersistence,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    /**
     * Optional semantic candidate source (N14/N15). Null means lexical-only retrieval, which is the
     * production state until a local text-embedding backend is proven (N15a). A source that fails is
     * reported through [AgentMemorySearchResult.semanticFallback]; it never fails the query.
     */
    private val semantic: AgentMemorySemanticCandidateSource? = null
) {
    suspend fun createSpace(
        displayName: String,
        approval: AgentMemoryWriteApproval
    ): AgentMemorySpaceOutcome {
        if (!approval.authorizesSpaceAdmin()) return rejectedSpace(AgentMemoryErrorCodes.WRITE_NOT_APPROVED, WRITE_APPROVAL)
        val name = displayName.trim()
        if (name.isEmpty() || name.length > AgentMemoryLimits.MAX_SPACE_NAME_CHARS) {
            return rejectedSpace(
                AgentMemoryErrorCodes.SPACE_NAME_INVALID,
                "A memory space name must contain 1-${AgentMemoryLimits.MAX_SPACE_NAME_CHARS} characters."
            )
        }
        val existing = catching { persistence.listSpaces(AgentMemoryLimits.MAX_SPACES + 1) }
            .getOrElse { return rejectedSpace(AgentMemoryErrorCodes.STORAGE_FAILED, STORAGE_FAILED) }
        if (existing.size >= AgentMemoryLimits.MAX_SPACES) {
            return rejectedSpace(
                AgentMemoryErrorCodes.SPACE_LIMIT,
                "The memory space limit of ${AgentMemoryLimits.MAX_SPACES} has been reached."
            )
        }
        val now = clock()
        val space = AgentMemorySpaceRecord(
            spaceId = idFactory(),
            displayName = name,
            createdAt = now,
            updatedAt = now,
            revision = 1
        )
        return try {
            persistence.insertSpace(space)
            AgentMemorySpaceOutcome.Created(space)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            rejectedSpace(AgentMemoryErrorCodes.STORAGE_FAILED, STORAGE_FAILED)
        }
    }

    suspend fun listSpaces(): List<AgentMemorySpaceRecord> =
        catching { persistence.listSpaces(AgentMemoryLimits.MAX_SPACES) }.getOrDefault(emptyList())

    /** Live (existing) space ids among [spaceIds]; used to re-check a frozen scope at execution time. */
    suspend fun liveSpaceIds(spaceIds: Collection<String>): Set<String> =
        if (spaceIds.isEmpty()) emptySet() else persistence.existingSpaceIds(spaceIds.distinct())

    suspend fun ingest(
        spaceId: String,
        request: AgentMemoryIngestRequest,
        approval: AgentMemoryWriteApproval
    ): AgentMemoryIngestOutcome {
        if (!approval.authorizesItems(spaceId)) {
            return rejectedIngest(AgentMemoryErrorCodes.WRITE_NOT_APPROVED, WRITE_APPROVAL)
        }
        val text = AgentMemoryText.normalize(request.text)
        if (text.isBlank()) return rejectedIngest(AgentMemoryErrorCodes.TEXT_EMPTY, "Memory text must not be empty.")
        if (AgentMemoryText.utf8Size(text) > AgentMemoryLimits.MAX_TEXT_BYTES) {
            return rejectedIngest(
                AgentMemoryErrorCodes.ITEM_TEXT_TOO_LARGE,
                "Memory text exceeds the ${AgentMemoryLimits.MAX_TEXT_BYTES / 1024} KiB limit."
            )
        }
        val title = resolveTitle(request.title, text)
        if (title.length > AgentMemoryLimits.MAX_TITLE_CHARS) {
            return rejectedIngest(
                AgentMemoryErrorCodes.TITLE_TOO_LARGE,
                "Memory title exceeds ${AgentMemoryLimits.MAX_TITLE_CHARS} characters."
            )
        }
        val sourceRef = request.sourceRef?.trim()?.takeIf(String::isNotEmpty)
        if (sourceRef != null && sourceRef.length > AgentMemoryLimits.MAX_SOURCE_REF_CHARS) {
            return rejectedIngest(
                AgentMemoryErrorCodes.SOURCE_REF_TOO_LARGE,
                "Memory source reference exceeds ${AgentMemoryLimits.MAX_SOURCE_REF_CHARS} characters."
            )
        }
        val space = catching { persistence.findSpace(spaceId) }
            .getOrElse { return rejectedIngest(AgentMemoryErrorCodes.STORAGE_FAILED, STORAGE_FAILED) }
            ?: return rejectedIngest(AgentMemoryErrorCodes.SPACE_NOT_FOUND, "The memory space does not exist.")

        val contentHash = AgentMemoryText.sha256(text)
        val duplicate = catching { persistence.findItemBySourceHash(spaceId, contentHash) }
            .getOrElse { return rejectedIngest(AgentMemoryErrorCodes.STORAGE_FAILED, STORAGE_FAILED) }
        if (duplicate != null) {
            return AgentMemoryIngestOutcome.Deduplicated(duplicate.memoryId, duplicate.revoked)
        }
        val liveItems = catching { persistence.liveItemCount(spaceId) }
            .getOrElse { return rejectedIngest(AgentMemoryErrorCodes.STORAGE_FAILED, STORAGE_FAILED) }
        if (liveItems >= AgentMemoryLimits.MAX_ITEMS_PER_SPACE) {
            return rejectedIngest(
                AgentMemoryErrorCodes.ITEM_LIMIT,
                "The memory space is full (${AgentMemoryLimits.MAX_ITEMS_PER_SPACE} live items)."
            )
        }

        val now = clock()
        val memoryId = idFactory()
        val chunks = try {
            AgentMemoryChunker.chunk(memoryId, spaceId, text, now)
        } catch (overflow: AgentMemoryTextException) {
            return AgentMemoryIngestOutcome.Rejected(overflow.error)
        }
        val item = AgentMemoryItemRecord(
            memoryId = memoryId,
            spaceId = spaceId,
            title = title,
            text = text,
            contentHash = contentHash,
            sourceKind = request.sourceKind,
            sourceRef = sourceRef,
            tokenCount = chunks.sumOf { it.estimatedTokens },
            chunkCount = chunks.size,
            createdAt = now,
            updatedAt = now,
            revoked = false,
            revokedAt = null
        )
        return try {
            persistence.inTransaction { persistence.insertItemWithChunks(item, chunks) }
            AgentMemoryIngestOutcome.Ingested(item, chunks.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            rejectedIngest(AgentMemoryErrorCodes.STORAGE_FAILED, STORAGE_FAILED)
        }
    }

    /**
     * Revision-CAS update of a live item's title/text and chunks (MEM-04).
     *
     * The caller must state the revision it read; a different revision is a typed conflict, never a
     * last-writer-wins overwrite. A revoked or foreign item is reported as not found. The text is
     * re-chunked and the content hash replaced in the same persistence transaction as the chunks.
     */
    suspend fun updateItem(
        spaceId: String,
        memoryId: String,
        expectedRevision: Long,
        title: String?,
        text: String,
        approval: AgentMemoryWriteApproval
    ): AgentMemoryMutationOutcome {
        if (!approval.authorizesItems(spaceId)) {
            return rejectedMutation(AgentMemoryErrorCodes.WRITE_NOT_APPROVED, WRITE_APPROVAL)
        }
        val normalized = AgentMemoryText.normalize(text)
        if (normalized.isBlank()) {
            return rejectedMutation(AgentMemoryErrorCodes.TEXT_EMPTY, "Memory text must not be empty.")
        }
        if (AgentMemoryText.utf8Size(normalized) > AgentMemoryLimits.MAX_TEXT_BYTES) {
            return rejectedMutation(
                AgentMemoryErrorCodes.ITEM_TEXT_TOO_LARGE,
                "Memory text exceeds the ${AgentMemoryLimits.MAX_TEXT_BYTES / 1024} KiB limit."
            )
        }
        val current = catching { persistence.findItem(memoryId) }.getOrElse {
            return rejectedMutation(AgentMemoryErrorCodes.STORAGE_FAILED, STORAGE_FAILED)
        }
        if (current == null || current.revoked || current.spaceId != spaceId) {
            return rejectedMutation(
                AgentMemoryErrorCodes.ITEM_NOT_FOUND,
                "The memory item does not exist in this space."
            )
        }
        val resolvedTitle = title?.trim()?.takeIf(String::isNotEmpty) ?: current.title
        if (resolvedTitle.length > AgentMemoryLimits.MAX_TITLE_CHARS) {
            return rejectedMutation(
                AgentMemoryErrorCodes.TITLE_TOO_LARGE,
                "Memory title exceeds ${AgentMemoryLimits.MAX_TITLE_CHARS} characters."
            )
        }
        val now = clock()
        val chunks = try {
            AgentMemoryChunker.chunk(memoryId, spaceId, normalized, now)
        } catch (overflow: AgentMemoryTextException) {
            return AgentMemoryMutationOutcome.Rejected(overflow.error)
        }
        return try {
            when (
                val outcome = persistence.updateItemWithChunks(
                    spaceId = spaceId,
                    memoryId = memoryId,
                    expectedRevision = expectedRevision,
                    title = resolvedTitle,
                    text = normalized,
                    contentHash = AgentMemoryText.sha256(normalized),
                    tokenCount = chunks.sumOf { it.estimatedTokens },
                    chunkCount = chunks.size,
                    chunks = chunks,
                    updatedAt = now
                )
            ) {
                is AgentMemoryUpdateOutcome.Updated -> AgentMemoryMutationOutcome.Applied
                AgentMemoryUpdateOutcome.NotFound -> rejectedMutation(
                    AgentMemoryErrorCodes.ITEM_NOT_FOUND,
                    "The memory item does not exist in this space."
                )
                AgentMemoryUpdateOutcome.Conflict -> rejectedMutation(
                    AgentMemoryErrorCodes.REVISION_CONFLICT,
                    "The memory item changed since it was read."
                )
                AgentMemoryUpdateOutcome.DuplicateContent -> rejectedMutation(
                    AgentMemoryErrorCodes.DUPLICATE_CONTENT,
                    "An item with the same text already exists in this space."
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            rejectedMutation(AgentMemoryErrorCodes.STORAGE_FAILED, STORAGE_FAILED)
        }
    }

    /**
     * Revision-CAS tombstone (delete) of one live item.
     *
     * The caller states the revision it read; a different revision is a typed conflict, never a
     * last-writer-wins delete. Revocation keeps the row and its chunks for audit and for [restoreItem].
     */
    suspend fun revokeItem(
        spaceId: String,
        memoryId: String,
        expectedRevision: Long,
        approval: AgentMemoryWriteApproval
    ): AgentMemoryMutationOutcome {
        if (!approval.authorizesItems(spaceId)) {
            return rejectedMutation(AgentMemoryErrorCodes.WRITE_NOT_APPROVED, WRITE_APPROVAL)
        }
        val outcome = catching { persistence.revokeItem(spaceId, memoryId, expectedRevision, clock()) }
            .getOrElse { return rejectedMutation(AgentMemoryErrorCodes.STORAGE_FAILED, STORAGE_FAILED) }
        return mutationFromUpdate(outcome)
    }

    /**
     * Revision-CAS undelete of one revoked item (N13/N16).
     *
     * Restore is a tombstone reversal, not a re-ingest: the item, its chunks and therefore its
     * retrieval presence come back under a new revision. A live/foreign/missing item is reported as
     * not found so a caller cannot probe another space.
     */
    suspend fun restoreItem(
        spaceId: String,
        memoryId: String,
        expectedRevision: Long,
        approval: AgentMemoryWriteApproval
    ): AgentMemoryMutationOutcome {
        if (!approval.authorizesItems(spaceId)) {
            return rejectedMutation(AgentMemoryErrorCodes.WRITE_NOT_APPROVED, WRITE_APPROVAL)
        }
        val outcome = catching { persistence.restoreItem(spaceId, memoryId, expectedRevision, clock()) }
            .getOrElse { return rejectedMutation(AgentMemoryErrorCodes.STORAGE_FAILED, STORAGE_FAILED) }
        return mutationFromUpdate(outcome)
    }

    suspend fun deleteItem(
        spaceId: String,
        memoryId: String,
        approval: AgentMemoryWriteApproval
    ): AgentMemoryMutationOutcome {
        if (!approval.authorizesItems(spaceId)) {
            return rejectedMutation(AgentMemoryErrorCodes.WRITE_NOT_APPROVED, WRITE_APPROVAL)
        }
        val removed = catching {
            persistence.inTransaction { persistence.deleteItemWithChunks(spaceId, memoryId) }
        }.getOrElse { return rejectedMutation(AgentMemoryErrorCodes.STORAGE_FAILED, STORAGE_FAILED) }
        return if (removed > 0) {
            AgentMemoryMutationOutcome.Applied
        } else {
            rejectedMutation(AgentMemoryErrorCodes.ITEM_NOT_FOUND, "The memory item does not exist in this space.")
        }
    }

    suspend fun deleteSpace(
        spaceId: String,
        approval: AgentMemoryWriteApproval
    ): AgentMemoryMutationOutcome {
        if (!approval.authorizesSpaceAdmin()) {
            return rejectedMutation(AgentMemoryErrorCodes.WRITE_NOT_APPROVED, WRITE_APPROVAL)
        }
        return try {
            val removed = persistence.inTransaction {
                persistence.deleteChunksForSpace(spaceId)
                persistence.deleteItemsForSpace(spaceId)
                persistence.deleteSpace(spaceId)
            }
            if (removed > 0) {
                AgentMemoryMutationOutcome.Applied
            } else {
                rejectedMutation(AgentMemoryErrorCodes.SPACE_NOT_FOUND, "The memory space does not exist.")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            rejectedMutation(AgentMemoryErrorCodes.STORAGE_FAILED, STORAGE_FAILED)
        }
    }

    /**
     * Bounded administration page over one space (N13/N16), newest update first.
     *
     * The query is a literal keyword over title/text, never a caller-built SQL fragment; pages are
     * capped independently from search budgets and a revoked item is only visible when the caller
     * explicitly asks for [includeRevoked] (needed to offer restore). No body text beyond the bounded
     * excerpt leaves this method.
     */
    suspend fun listItemPage(
        spaceId: String,
        query: String?,
        includeRevoked: Boolean,
        offset: Int,
        limit: Int
    ): AgentMemoryItemPageOutcome {
        if (!AgentMemorySelection.isValidSpaceId(spaceId)) {
            return rejectedPage(
                AgentMemoryErrorCodes.SPACE_NOT_FOUND,
                "The memory space does not exist."
            )
        }
        val keyword = query?.trim()?.takeIf(String::isNotEmpty)
        if (keyword != null && keyword.length > AgentMemoryLimits.MAX_ADMIN_QUERY_CHARS) {
            return rejectedPage(
                AgentMemoryErrorCodes.QUERY_TOO_LONG,
                "The management keyword exceeds ${AgentMemoryLimits.MAX_ADMIN_QUERY_CHARS} characters."
            )
        }
        if (limit !in AgentMemoryLimits.MIN_ADMIN_PAGE_SIZE..AgentMemoryLimits.MAX_ADMIN_PAGE_SIZE ||
            offset !in 0..AgentMemoryLimits.MAX_ADMIN_OFFSET
        ) {
            return rejectedPage(
                AgentMemoryErrorCodes.PAGE_INVALID,
                "The page must request ${AgentMemoryLimits.MIN_ADMIN_PAGE_SIZE}-" +
                    "${AgentMemoryLimits.MAX_ADMIN_PAGE_SIZE} rows with an offset in " +
                    "0-${AgentMemoryLimits.MAX_ADMIN_OFFSET}."
            )
        }
        val space = catching { persistence.findSpace(spaceId) }.getOrElse {
            return rejectedPage(AgentMemoryErrorCodes.STORAGE_FAILED, STORAGE_FAILED)
        } ?: return rejectedPage(
            AgentMemoryErrorCodes.SPACE_NOT_FOUND,
            "The memory space does not exist."
        )
        val items = catching {
            persistence.listItemPage(spaceId, keyword, includeRevoked, limit, offset)
        }.getOrElse {
            return rejectedPage(AgentMemoryErrorCodes.STORAGE_FAILED, STORAGE_FAILED)
        }
        val total = catching { persistence.countItems(spaceId, keyword, includeRevoked) }.getOrElse {
            return rejectedPage(AgentMemoryErrorCodes.STORAGE_FAILED, STORAGE_FAILED)
        }
        return AgentMemoryItemPageOutcome.Found(
            AgentMemoryItemPage(
                spaceId = space.spaceId,
                items = items.map { item ->
                    AgentMemoryItemSummary(
                        memoryId = item.memoryId,
                        spaceId = item.spaceId,
                        title = item.title,
                        excerpt = adminExcerpt(item.text),
                        contentHash = item.contentHash,
                        sourceKind = item.sourceKind,
                        revision = item.revision,
                        updatedAt = item.updatedAt,
                        revoked = item.revoked,
                        hasSourceRef = item.sourceRef != null
                    )
                },
                total = total,
                offset = offset,
                limit = limit
            )
        )
    }

    /**
     * Current semantic capability of this store, for diagnostics.
     *
     * A lexical-only store (the production state until N15a proves a local embedding backend)
     * reports `Disabled(NOT_CONFIGURED)` instead of pretending vectors exist.
     */
    suspend fun semanticStatus(): AgentMemorySemanticStatus =
        semantic?.status()
            ?: AgentMemorySemanticStatus.Disabled(AgentMemorySemanticUnavailableReason.NOT_CONFIGURED)

    /**
     * Bounded retrieval inside [grant] only.
     *
     * [requestedSpaceId] may narrow the scope but can never widen it: a non-granted id is rejected
     * before any query runs. Candidate selection is query-driven: up to
     * [AgentMemoryLimits.MAX_SCAN_CHUNKS] chunks are examined in `(spaceId, memoryId, position)`
     * order, at most [AgentMemoryLimits.MAX_CANDIDATE_CHUNKS] query-matching chunks are ranked, and
     * the returned citations are capped at [AgentMemoryLimits.MAX_RETURNED_CITATIONS] /
     * [AgentMemoryLimits.MAX_RETURNED_TOKENS]. `scannedChunks` reports how far the scan actually got.
     */
    suspend fun search(
        grant: AgentMemoryReadGrant,
        query: String,
        requestedSpaceId: String?,
        maxItems: Int
    ): AgentMemorySearchOutcome {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return rejectedSearch(AgentMemoryErrorCodes.QUERY_EMPTY, "The memory search query must not be empty.")
        }
        if (trimmedQuery.length > AgentMemoryLimits.MAX_QUERY_CHARS) {
            return rejectedSearch(
                AgentMemoryErrorCodes.QUERY_TOO_LONG,
                "The memory search query exceeds ${AgentMemoryLimits.MAX_QUERY_CHARS} characters."
            )
        }
        if (maxItems !in AgentMemoryLimits.MIN_MAX_ITEMS..AgentMemoryLimits.MAX_MAX_ITEMS) {
            return rejectedSearch(
                AgentMemoryErrorCodes.MAX_ITEMS_INVALID,
                "maxItems must be between ${AgentMemoryLimits.MIN_MAX_ITEMS} and " +
                    "${AgentMemoryLimits.MAX_MAX_ITEMS}."
            )
        }
        if (grant.revoked) {
            return rejectedSearch(
                AgentMemoryErrorCodes.SELECTION_REVOKED,
                "The memory selection for this task was revoked."
            )
        }
        val scope = if (requestedSpaceId == null) {
            grant.spaceIds
        } else {
            if (!grant.covers(listOf(requestedSpaceId))) {
                return rejectedSearch(
                    AgentMemoryErrorCodes.SPACE_NOT_SELECTED,
                    "The requested memory space is outside this task's selection."
                )
            }
            listOf(requestedSpaceId)
        }
        val live = liveSpaceIds(scope)
        if (live.size != scope.size) {
            return rejectedSearch(
                AgentMemoryErrorCodes.SPACE_NOT_FOUND,
                "A selected memory space no longer exists; update the task selection."
            )
        }

        // B05: the query decides which chunks become candidates, instead of ranking only the first
        // fixed page of the space. The scan is bounded independently from the candidate/topK caps;
        // the result reports how much of the space was actually examined, and `truncated` stays true
        // whenever the scan budget or candidate cap cut the search short.
        val queryTerms = AgentMemoryLexicalRanker.terms(trimmedQuery)
        val candidates = ArrayList<AgentMemoryChunkRecord>(AgentMemoryLimits.MAX_CANDIDATE_CHUNKS)
        var scanned = 0
        var offset = 0
        var scanCapped = false
        try {
            while (scanned < AgentMemoryLimits.MAX_SCAN_CHUNKS &&
                candidates.size < AgentMemoryLimits.MAX_CANDIDATE_CHUNKS
            ) {
                val remainingScan = AgentMemoryLimits.MAX_SCAN_CHUNKS - scanned
                val page = persistence.liveChunkPage(
                    spaceIds = scope,
                    limit = minOf(AgentMemoryLimits.CHUNK_PAGE_SIZE, remainingScan),
                    offset = offset
                )
                if (page.isEmpty()) break
                scanned += page.size
                offset += page.size
                for (chunk in page) {
                    if (candidates.size >= AgentMemoryLimits.MAX_CANDIDATE_CHUNKS) break
                    if (AgentMemoryLexicalRanker.score(
                            queryTerms,
                            AgentMemoryLexicalRanker.terms(chunk.text)
                        ) > 0
                    ) {
                        candidates += chunk
                    }
                }
                if (page.size < AgentMemoryLimits.CHUNK_PAGE_SIZE) break
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return rejectedSearch(AgentMemoryErrorCodes.STORAGE_FAILED, STORAGE_FAILED)
        }
        if (scanned >= AgentMemoryLimits.MAX_SCAN_CHUNKS &&
            candidates.size < AgentMemoryLimits.MAX_CANDIDATE_CHUNKS
        ) {
            scanCapped = true
        }

        val ranked = AgentMemoryLexicalRanker.rank(trimmedQuery, candidates)
        val retrieval = resolveRetrieval(ranked, scope, trimmedQuery)
        val deduplicated = deduplicateByItem(retrieval.chunks)
        val citations = ArrayList<AgentMemoryCitation>()
        var tokens = 0
        var truncated = scanCapped || deduplicated.size > minOf(maxItems, AgentMemoryLimits.MAX_RETURNED_CITATIONS)
        for (hit in deduplicated) {
            if (citations.size >= minOf(maxItems, AgentMemoryLimits.MAX_RETURNED_CITATIONS)) {
                truncated = true
                break
            }
            // Budget the text the model actually receives: the citation excerpt, never the whole
            // chunk, so the reported total matches the assembled context.
            val citationExcerpt = excerpt(hit.chunk.text)
            val excerptTokens = WorldBookResolver.estimateTokens(citationExcerpt)
            if (tokens + excerptTokens > AgentMemoryLimits.MAX_RETURNED_TOKENS) {
                truncated = true
                break
            }
            tokens += excerptTokens
            citations += AgentMemoryCitation(
                spaceId = hit.chunk.spaceId,
                memoryId = hit.chunk.memoryId,
                chunkId = hit.chunk.chunkId,
                contentHash = hit.chunk.contentHash,
                sourceHash = hit.chunk.sourceHash,
                excerpt = citationExcerpt,
                score = hit.score,
                estimatedTokens = excerptTokens
            )
        }
        return AgentMemorySearchOutcome.Found(
            AgentMemorySearchResult(
                citations = citations,
                truncated = truncated,
                scannedChunks = scanned,
                estimatedTokens = tokens,
                retrievalMode = retrieval.mode,
                semanticFallback = retrieval.fallback,
                semanticCandidates = retrieval.semanticCandidates
            )
        )
    }

    /**
     * Content-hash lookup inside one space, used by the extraction preflight (N16) to mark a
     * candidate that already exists.
     *
     * This is a write-path helper, not a retrieval capability: it takes no grant and returns the
     * item including a revoked tombstone. No memory tool is wired to it.
     */
    suspend fun findByContentHash(spaceId: String, contentHash: String): AgentMemoryItemRecord? =
        catching { persistence.findItemBySourceHash(spaceId, contentHash) }.getOrNull()

    /**
     * Fuses the lexical ranking with optional semantic candidates (N14/N15).
     *
     * Fail-open contract: a null source, an unavailable source, a source exception, an unavailable
     * persistence read or a semantic hit set that resolves to nothing all return the untouched
     * lexical ranking plus a diagnostic. Semantic hits are filtered to the granted scope *before*
     * any lookup, and the text always comes from the authoritative persistence, so an index row can
     * never surface foreign, revoked or fabricated content.
     */
    private suspend fun resolveRetrieval(
        lexical: List<AgentMemoryLexicalRanker.Ranked>,
        scope: List<String>,
        query: String
    ): Retrieval {
        val plain = lexical.map { hit -> ScoredChunk(hit.chunk, hit.score) }
        val source = semantic ?: return Retrieval(plain, AgentMemoryRetrievalMode.LEXICAL)
        val semanticQuery = try {
            source.candidates(scope, query, AgentMemoryLimits.MAX_SEMANTIC_CANDIDATES)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AgentMemorySemanticQuery.Unavailable(AgentMemorySemanticUnavailableReason.INDEX_UNAVAILABLE)
        }
        if (semanticQuery is AgentMemorySemanticQuery.Unavailable) {
            return Retrieval(plain, AgentMemoryRetrievalMode.LEXICAL, semanticQuery.reason)
        }
        val hits = (semanticQuery as AgentMemorySemanticQuery.Available).hits
            .filter { hit -> hit.spaceId in scope }
            .distinctBy { hit -> hit.chunkId }
            .take(AgentMemoryLimits.MAX_SEMANTIC_CANDIDATES)
        if (hits.isEmpty()) return Retrieval(plain, AgentMemoryRetrievalMode.LEXICAL)
        val resolved = try {
            persistence.liveChunks(scope, hits.map { hit -> hit.chunkId })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Retrieval(
                plain,
                AgentMemoryRetrievalMode.LEXICAL,
                AgentMemorySemanticUnavailableReason.INDEX_UNAVAILABLE
            )
        }
        val byChunkId = resolved.associateBy { chunk -> chunk.chunkId }
        // Preserve the source's similarity order; drop ids that are unknown, foreign or deleted.
        val semanticChunks = hits.mapNotNull { hit -> byChunkId[hit.chunkId] }
        if (semanticChunks.isEmpty()) return Retrieval(plain, AgentMemoryRetrievalMode.LEXICAL)
        val fused = AgentMemoryHybridFusion.fuse(
            lexical = lexical,
            semantic = semanticChunks,
            limit = AgentMemoryLimits.MAX_SEMANTIC_CANDIDATES
        )
        return Retrieval(
            chunks = fused.map { hit -> ScoredChunk(hit.chunk, hit.score) },
            mode = AgentMemoryRetrievalMode.HYBRID,
            semanticCandidates = semanticChunks.size
        )
    }

    /**
     * Exact-item read inside the frozen selection (MEM-06 partial).
     *
     * A foreign-space or revoked item is reported as `ITEM_NOT_FOUND`, never as a permission error,
     * so the caller cannot probe existence outside its grant. The text returned is the stored
     * (already bounded) item text; no chunk reassembly or source file access happens here.
     */
    suspend fun getItem(
        grant: AgentMemoryReadGrant,
        memoryId: String,
        requestedSpaceId: String?,
        expectedContentHash: String? = null
    ): AgentMemoryGetOutcome {
        if (grant.revoked) {
            return AgentMemoryGetOutcome.Rejected(
                AgentMemoryError(
                    AgentMemoryErrorCodes.SELECTION_REVOKED,
                    "The memory selection for this task was revoked."
                )
            )
        }
        val scope = if (requestedSpaceId == null) {
            grant.spaceIds
        } else {
            if (!grant.covers(listOf(requestedSpaceId))) {
                return AgentMemoryGetOutcome.Rejected(
                    AgentMemoryError(
                        AgentMemoryErrorCodes.SPACE_NOT_SELECTED,
                        "The requested memory space is outside this task's selection."
                    )
                )
            }
            listOf(requestedSpaceId)
        }
        val live = liveSpaceIds(scope)
        if (live.size != scope.size) {
            return AgentMemoryGetOutcome.Rejected(
                AgentMemoryError(
                    AgentMemoryErrorCodes.SPACE_NOT_FOUND,
                    "A selected memory space no longer exists; update the task selection."
                )
            )
        }
        val item = catching { persistence.findItem(memoryId) }.getOrElse {
            return AgentMemoryGetOutcome.Rejected(
                AgentMemoryError(AgentMemoryErrorCodes.STORAGE_FAILED, STORAGE_FAILED)
            )
        }
        if (item == null || item.revoked || item.spaceId !in scope) {
            return AgentMemoryGetOutcome.Rejected(
                AgentMemoryError(
                    AgentMemoryErrorCodes.ITEM_NOT_FOUND,
                    "The memory item does not exist in the selected spaces."
                )
            )
        }
        // A citation is only a pointer. If the item changed since the citation was produced, the
        // caller must search again instead of reading a different document under the same id.
        if (expectedContentHash != null && expectedContentHash != item.contentHash) {
            return AgentMemoryGetOutcome.Rejected(
                AgentMemoryError(
                    AgentMemoryErrorCodes.CITATION_STALE,
                    "The cited memory item changed; search again before reading it."
                )
            )
        }
        return AgentMemoryGetOutcome.Found(item)
    }

    private fun deduplicateByItem(ranked: List<ScoredChunk>): List<ScoredChunk> {
        val seen = LinkedHashSet<String>(ranked.size)
        val kept = ArrayList<ScoredChunk>(ranked.size)
        ranked.forEach { hit ->
            if (seen.add(hit.chunk.memoryId)) kept += hit
        }
        return kept
    }

    /** One candidate with the score its own source assigned (lexical weight or fused RRF score). */
    private data class ScoredChunk(val chunk: AgentMemoryChunkRecord, val score: Int)

    private data class Retrieval(
        val chunks: List<ScoredChunk>,
        val mode: AgentMemoryRetrievalMode,
        val fallback: AgentMemorySemanticUnavailableReason? = null,
        val semanticCandidates: Int = 0
    )

    private fun excerpt(text: String, maxChars: Int = AgentMemoryLimits.MAX_EXCERPT_CHARS): String {
        val collapsed = buildString(text.length.coerceAtMost(maxChars + 1)) {
            text.forEach { character ->
                append(if (character.isISOControl() && character != '\n') ' ' else character)
            }
        }.replace("\n", " ").trim()
        return if (collapsed.length <= maxChars) {
            collapsed
        } else {
            collapsed.take(maxChars - 1) + "\u2026"
        }
    }

    /** Same bounded preview as retrieval, with the tighter administration cap. */
    private fun adminExcerpt(text: String): String =
        excerpt(text, AgentMemoryLimits.MAX_ADMIN_EXCERPT_CHARS)

    /**
     * Maps the persistence CAS outcome to the domain mutation outcome.
     *
     * Revoke/restore never produce `DuplicateContent`; it is mapped to "not found" so the sealed
     * persistence taxonomy cannot leak a misleading conflict.
     */
    private fun mutationFromUpdate(outcome: AgentMemoryUpdateOutcome): AgentMemoryMutationOutcome =
        when (outcome) {
            is AgentMemoryUpdateOutcome.Updated -> AgentMemoryMutationOutcome.Applied
            AgentMemoryUpdateOutcome.NotFound -> rejectedMutation(
                AgentMemoryErrorCodes.ITEM_NOT_FOUND,
                "The memory item does not exist in this space."
            )
            AgentMemoryUpdateOutcome.Conflict -> rejectedMutation(
                AgentMemoryErrorCodes.REVISION_CONFLICT,
                "The memory item changed since it was read."
            )
            AgentMemoryUpdateOutcome.DuplicateContent -> rejectedMutation(
                AgentMemoryErrorCodes.ITEM_NOT_FOUND,
                "The memory item does not exist in this space."
            )
        }

    private fun resolveTitle(rawTitle: String, text: String): String {
        val explicit = rawTitle.trim()
        if (explicit.isNotEmpty()) return explicit
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (firstLine.length <= AgentMemoryLimits.MAX_TITLE_CHARS) {
            firstLine
        } else {
            firstLine.take(AgentMemoryLimits.MAX_TITLE_CHARS)
        }
    }

    private fun rejectedSpace(code: String, message: String): AgentMemorySpaceOutcome =
        AgentMemorySpaceOutcome.Rejected(AgentMemoryError(code, message))

    private fun rejectedIngest(code: String, message: String): AgentMemoryIngestOutcome =
        AgentMemoryIngestOutcome.Rejected(AgentMemoryError(code, message))

    private fun rejectedMutation(code: String, message: String): AgentMemoryMutationOutcome =
        AgentMemoryMutationOutcome.Rejected(AgentMemoryError(code, message))

    private fun rejectedSearch(code: String, message: String): AgentMemorySearchOutcome =
        AgentMemorySearchOutcome.Rejected(AgentMemoryError(code, message))

    private fun rejectedPage(code: String, message: String): AgentMemoryItemPageOutcome =
        AgentMemoryItemPageOutcome.Rejected(AgentMemoryError(code, message))

    /**
     * `runCatching` that never turns cancellation into a typed failure: the run owner must still see
     * the cancellation. Only genuine storage/IO failures map to `MEMORY_STORAGE_FAILED`.
     */
    private inline fun <T> catching(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }

    private companion object {
        const val WRITE_APPROVAL = "This memory write requires an explicit user approval for this space."
        const val STORAGE_FAILED = "The memory store could not complete the operation."
    }
}

