package com.muyuchat.feature.agent.workspace.memory

import com.muyuchat.feature.agent.workspace.AgentMemoryAdminPort
import com.muyuchat.feature.agent.workspace.AgentMemoryCandidateConfidence
import com.muyuchat.feature.agent.workspace.AgentMemoryCandidateDecision
import com.muyuchat.feature.agent.workspace.AgentMemoryCandidateDecisionResult
import com.muyuchat.feature.agent.workspace.AgentMemoryCandidateItem
import com.muyuchat.feature.agent.workspace.AgentMemoryCandidateListResult
import com.muyuchat.feature.agent.workspace.AgentMemoryExtractionSubmitResult
import com.muyuchat.feature.agent.workspace.AgentMemoryItemActionResult
import com.muyuchat.feature.agent.workspace.AgentMemoryItemPageResult
import com.muyuchat.feature.agent.workspace.AgentMemoryListResult
import com.muyuchat.feature.agent.workspace.AgentMemoryManagedItem
import com.muyuchat.feature.agent.workspace.AgentMemorySemanticDiagnostics
import com.muyuchat.feature.agent.workspace.AgentMemorySpaceItem
import com.muyuchat.feature.agent.workspace.AgentMemoryUpdateResult
import com.muyuchat.feature.agent.workspace.formatRelativeTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Page state holder for memory management (spaces, items, editor, extraction review).
 *
 * It is deliberately UI-free: every intent is a suspend function over the existing
 * [AgentMemoryAdminPort], so a JVM test drives the whole page with a fake port, and the Compose layer
 * only renders the immutable [MemoryManagementState] and forwards intents.
 *
 * Concurrency: all intents are serialized by one mutex. A management page has no throughput
 * requirement and serial execution removes stale-response races (a slow first page can never
 * overwrite a newer selection/query). Stale *loads* inside one intent are additionally guarded by
 * comparing the selected space before applying a result.
 *
 * Write rules: delete (revoke), restore (undelete), edit (revision CAS) and candidate acceptance are
 * user-initiated; the port mints the `USER_*` approval, so no model can reach this holder.
 */
class MemoryManagementStateHolder(
    private val admin: AgentMemoryAdminPort,
    private val reader: MemoryItemReader? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val pageSize: Int = AgentMemoryAdminPort.DEFAULT_ITEM_PAGE_SIZE,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    init {
        require(pageSize >= 1) { "pageSize must be positive" }
    }

    private val gate = Mutex()
    private val mutable = MutableStateFlow(MemoryManagementState())

    val state: StateFlow<MemoryManagementState> = mutable.asStateFlow()

    // --- spaces ---------------------------------------------------------------------------------

    suspend fun refresh(): Unit = withContext(dispatcher) {
        gate.withLock { refreshLocked() }
    }

    private suspend fun refreshLocked() {
        mutable.update { it.copy(loadingSpaces = true, spacesError = null, actionError = null, notice = null) }
        val result = listSpacesSafely()
        val rows = when (result) {
            null -> {
                mutable.update { it.copy(loadingSpaces = false, spacesError = ADMIN_FAILURE) }
                return
            }
            is AgentMemoryListResult.Rejected -> {
                mutable.update { it.copy(loadingSpaces = false, spacesError = result.toUi()) }
                return
            }
            is AgentMemoryListResult.Loaded -> result.spaces.map(::toSpaceRow)
        }
        val stillSelected = mutable.value.selectedSpaceId?.takeIf { id -> rows.any { it.spaceId == id } }
        mutable.update { current ->
            current.copy(
                loadingSpaces = false,
                spaces = rows,
                spacesError = null,
                selectedSpaceId = stillSelected,
                items = if (stillSelected == null) emptyList() else current.items,
                itemsTotal = if (stillSelected == null) 0 else current.itemsTotal,
                hasMoreItems = if (stillSelected == null) false else current.hasMoreItems,
                itemsError = if (stillSelected == null) null else current.itemsError,
                candidates = if (stillSelected == null) emptyList() else current.candidates,
                candidatesSubmittedLabel = if (stillSelected == null) null else current.candidatesSubmittedLabel,
                editor = if (stillSelected == null) null else current.editor
            )
        }
        loadDiagnostics()
        if (stillSelected != null) {
            loadItemsLocked(reset = true)
            loadCandidatesLocked()
        }
    }

    suspend fun selectSpace(spaceId: String): Unit = withContext(dispatcher) {
        gate.withLock {
            val known = mutable.value.spaces.firstOrNull { it.spaceId == spaceId } ?: return@withLock
            mutable.update {
                it.copy(
                    selectedSpaceId = known.spaceId,
                    query = "",
                    items = emptyList(),
                    itemsTotal = 0,
                    hasMoreItems = false,
                    itemsError = null,
                    candidates = emptyList(),
                    candidatesError = null,
                    candidatesSubmittedLabel = null,
                    editor = null,
                    actionError = null,
                    notice = null
                )
            }
            loadItemsLocked(reset = true)
            loadCandidatesLocked()
        }
    }

    // --- items ----------------------------------------------------------------------------------

    fun onQueryChange(query: String) {
        mutable.update { it.copy(query = query) }
    }

    suspend fun search(): Unit = withContext(dispatcher) {
        gate.withLock { loadItemsLocked(reset = true) }
    }

    suspend fun loadMore(): Unit = withContext(dispatcher) {
        gate.withLock { loadItemsLocked(reset = false) }
    }

    suspend fun setIncludeRevoked(enabled: Boolean): Unit = withContext(dispatcher) {
        gate.withLock {
            if (mutable.value.includeRevoked == enabled) return@withLock
            mutable.update { it.copy(includeRevoked = enabled) }
            loadItemsLocked(reset = true)
        }
    }

    private suspend fun loadItemsLocked(reset: Boolean) {
        val snapshot = mutable.value
        val spaceId = snapshot.selectedSpaceId ?: return
        if (!reset && !snapshot.canLoadMore) return
        val query = snapshot.query.trim().takeIf(String::isNotEmpty)
        val includeRevoked = snapshot.includeRevoked
        val offset = if (reset) 0 else snapshot.items.size
        mutable.update {
            if (reset) {
                it.copy(loadingItems = true, loadingMore = false, itemsError = null)
            } else {
                it.copy(loadingMore = true, itemsError = null)
            }
        }
        val result = try {
            admin.listItems(
                spaceId = spaceId,
                query = query,
                offset = offset,
                limit = pageSize,
                includeRevoked = includeRevoked
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        when (result) {
            null -> mutable.update { current ->
                if (current.selectedSpaceId != spaceId) {
                    current
                } else {
                    current.copy(loadingItems = false, loadingMore = false, itemsError = ITEMS_FAILURE)
                }
            }
            is AgentMemoryItemPageResult.Rejected -> mutable.update { current ->
                if (current.selectedSpaceId != spaceId) {
                    current
                } else {
                    current.copy(loadingItems = false, loadingMore = false, itemsError = result.toUi())
                }
            }
            is AgentMemoryItemPageResult.Loaded -> {
                val rows = result.items.map(::toItemRow)
                mutable.update { current ->
                    if (current.selectedSpaceId != spaceId) {
                        current
                    } else {
                        current.copy(
                            loadingItems = false,
                            loadingMore = false,
                            itemsError = null,
                            items = if (reset) rows else mergeItems(current.items, rows),
                            itemsTotal = result.total,
                            hasMoreItems = result.hasMore
                        )
                    }
                }
            }
        }
    }

    // --- delete / restore -----------------------------------------------------------------------

    suspend fun deleteItem(itemId: String): Unit = withContext(dispatcher) {
        gate.withLock { deleteLocked(itemId) }
    }

    private suspend fun deleteLocked(itemId: String) {
        val snapshot = mutable.value
        val spaceId = snapshot.selectedSpaceId ?: return
        val item = snapshot.items.firstOrNull { it.itemId == itemId } ?: return
        mutable.update { it.copy(actionInFlight = itemId, actionError = null, notice = null) }
        val result = try {
            admin.deleteItem(spaceId, itemId, item.revision)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        when (result) {
            null -> mutable.update { it.copy(actionInFlight = null, actionError = ACTION_FAILURE) }
            is AgentMemoryItemActionResult.Rejected -> {
                mutable.update { it.copy(actionInFlight = null, actionError = result.toUi()) }
                if (result.code == CODE_REVISION_CONFLICT) loadItemsLocked(reset = true)
            }
            is AgentMemoryItemActionResult.Applied -> {
                val wasLive = !item.revoked
                mutable.update { current ->
                    val keepRows = current.includeRevoked
                    current.copy(
                        actionInFlight = null,
                        items = if (keepRows) {
                            current.items.map { row ->
                                if (row.itemId == itemId) row.copy(revoked = true, revision = result.revision) else row
                            }
                        } else {
                            current.items.filterNot { row -> row.itemId == itemId }
                        },
                        itemsTotal = if (keepRows) {
                            current.itemsTotal
                        } else {
                            (current.itemsTotal - 1).coerceAtLeast(0)
                        },
                        spaces = current.spaces.map { space ->
                            if (space.spaceId == spaceId && wasLive) space.decrement() else space
                        },
                        notice = "已删除该条目；开启「显示已删除」后可恢复。"
                    )
                }
            }
        }
    }

    suspend fun restoreItem(itemId: String): Unit = withContext(dispatcher) {
        gate.withLock { restoreLocked(itemId) }
    }

    private suspend fun restoreLocked(itemId: String) {
        val snapshot = mutable.value
        val spaceId = snapshot.selectedSpaceId ?: return
        val item = snapshot.items.firstOrNull { it.itemId == itemId } ?: return
        mutable.update { it.copy(actionInFlight = itemId, actionError = null, notice = null) }
        val result = try {
            admin.restoreItem(spaceId, itemId, item.revision)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        when (result) {
            null -> mutable.update { it.copy(actionInFlight = null, actionError = ACTION_FAILURE) }
            is AgentMemoryItemActionResult.Rejected -> {
                mutable.update { it.copy(actionInFlight = null, actionError = result.toUi()) }
                if (result.code == CODE_REVISION_CONFLICT) loadItemsLocked(reset = true)
            }
            is AgentMemoryItemActionResult.Applied -> {
                val wasRevoked = item.revoked
                mutable.update { current ->
                    current.copy(
                        actionInFlight = null,
                        items = current.items.map { row ->
                            if (row.itemId == itemId) row.copy(revoked = false, revision = result.revision) else row
                        },
                        spaces = current.spaces.map { space ->
                            if (space.spaceId == spaceId && wasRevoked) space.increment() else space
                        },
                        notice = "已恢复该条目。"
                    )
                }
            }
        }
    }

    // --- editor ---------------------------------------------------------------------------------

    suspend fun openEditor(itemId: String): Unit = withContext(dispatcher) {
        gate.withLock { openEditorLocked(itemId) }
    }

    private suspend fun openEditorLocked(itemId: String) {
        val snapshot = mutable.value
        val spaceId = snapshot.selectedSpaceId ?: return
        val row = snapshot.items.firstOrNull { it.itemId == itemId } ?: return
        if (row.revoked) {
            mutable.update {
                it.copy(actionError = MemoryManagementErrorUi(CODE_READ_UNAVAILABLE, "已删除的条目不支持编辑；请先恢复。"))
            }
            return
        }
        val source = reader
        if (source == null) {
            mutable.update {
                it.copy(actionError = MemoryManagementErrorUi(CODE_READ_UNAVAILABLE, "记忆全文读取尚未接入，暂时无法编辑条目。"))
            }
            return
        }
        mutable.update {
            it.copy(
                actionError = null,
                notice = null,
                editor = MemoryEditorUi(
                    itemId = row.itemId,
                    title = row.title,
                    text = "",
                    expectedRevision = row.revision,
                    loading = true
                )
            )
        }
        val result = try {
            source.readItem(spaceId, itemId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        when (result) {
            null -> mutable.update { it.copy(editor = null, actionError = READ_FAILURE) }
            is MemoryItemReadResult.Rejected -> mutable.update {
                it.copy(editor = null, actionError = result.toUi())
            }
            is MemoryItemReadResult.Loaded -> {
                val detail = result.item
                mutable.update { current ->
                    if (current.editor?.itemId != itemId || current.selectedSpaceId != spaceId) {
                        current
                    } else {
                        current.copy(
                            editor = MemoryEditorUi(
                                itemId = detail.itemId,
                                title = detail.title,
                                text = detail.text,
                                expectedRevision = detail.revision,
                                loading = false
                            )
                        )
                    }
                }
            }
        }
    }

    fun onEditorTitleChange(title: String) {
        mutable.update { current ->
            val editor = current.editor ?: return@update current
            current.copy(editor = editor.copy(title = title, error = null))
        }
    }

    fun onEditorTextChange(text: String) {
        mutable.update { current ->
            val editor = current.editor ?: return@update current
            current.copy(editor = editor.copy(text = text, error = null))
        }
    }

    fun closeEditor() {
        mutable.update { it.copy(editor = null) }
    }

    suspend fun saveEditor(): Unit = withContext(dispatcher) {
        gate.withLock { saveEditorLocked() }
    }

    private suspend fun saveEditorLocked() {
        val snapshot = mutable.value
        val editor = snapshot.editor ?: return
        if (editor.loading || editor.saving) return
        val spaceId = snapshot.selectedSpaceId ?: return
        mutable.update { current ->
            val open = current.editor ?: return@update current
            current.copy(editor = open.copy(saving = true, error = null))
        }
        val result = try {
            admin.updateItem(
                spaceId = spaceId,
                itemId = editor.itemId,
                expectedRevision = editor.expectedRevision,
                title = editor.title.trim().takeIf(String::isNotEmpty),
                text = editor.text
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        when (result) {
            null -> mutable.update { current ->
                val open = current.editor ?: return@update current
                current.copy(editor = open.copy(saving = false, error = ACTION_FAILURE))
            }
            is AgentMemoryUpdateResult.Rejected -> mutable.update { current ->
                val open = current.editor ?: return@update current
                current.copy(editor = open.copy(saving = false, error = result.toUi()))
            }
            is AgentMemoryUpdateResult.Updated -> {
                mutable.update { it.copy(editor = null, notice = "已保存条目改动。") }
                loadItemsLocked(reset = true)
            }
        }
    }

    // --- extraction review ----------------------------------------------------------------------

    fun onExtractionInputChange(text: String) {
        mutable.update { it.copy(extractionInput = text) }
    }

    suspend fun submitExtraction(): Unit = withContext(dispatcher) {
        gate.withLock { submitExtractionLocked() }
    }

    private suspend fun submitExtractionLocked() {
        val snapshot = mutable.value
        val spaceId = snapshot.selectedSpaceId
        if (spaceId == null) {
            mutable.update {
                it.copy(actionError = MemoryManagementErrorUi(CODE_SPACE_NOT_SELECTED, "请先选择一个记忆空间。"))
            }
            return
        }
        if (snapshot.submittingExtraction) return
        mutable.update { it.copy(submittingExtraction = true, candidatesError = null, notice = null) }
        val result = try {
            admin.submitExtraction(spaceId, snapshot.extractionInput)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        when (result) {
            null -> mutable.update { it.copy(submittingExtraction = false, candidatesError = CANDIDATES_FAILURE) }
            is AgentMemoryExtractionSubmitResult.Rejected -> mutable.update {
                it.copy(submittingExtraction = false, candidatesError = result.toUi())
            }
            is AgentMemoryExtractionSubmitResult.Proposed -> {
                val rows = result.candidates.map(::toCandidateRow)
                mutable.update {
                    it.copy(
                        submittingExtraction = false,
                        candidates = rows,
                        candidatesError = null,
                        extractionInput = "",
                        candidatesSubmittedLabel = "刚刚",
                        notice = if (result.truncated) {
                            "候选较多，仅显示前 ${rows.size} 条；接受后才会写入。"
                        } else {
                            "已生成 ${rows.size} 条待审核候选；接受后才会写入。"
                        }
                    )
                }
            }
        }
    }

    private suspend fun loadCandidatesLocked() {
        val spaceId = mutable.value.selectedSpaceId ?: return
        mutable.update { it.copy(loadingCandidates = true, candidatesError = null) }
        val result = try {
            admin.listExtractionCandidates(spaceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        when (result) {
            null -> mutable.update { it.copy(loadingCandidates = false, candidatesError = CANDIDATES_FAILURE) }
            is AgentMemoryCandidateListResult.Rejected -> mutable.update {
                it.copy(loadingCandidates = false, candidatesError = result.toUi())
            }
            is AgentMemoryCandidateListResult.Loaded -> {
                val rows = result.candidates.map(::toCandidateRow)
                mutable.update { current ->
                    if (current.selectedSpaceId != spaceId) {
                        current
                    } else {
                        current.copy(
                            loadingCandidates = false,
                            candidates = rows,
                            candidatesError = null,
                            candidatesSubmittedLabel = result.submittedAt?.let { submitted ->
                                formatRelativeTime(clock(), submitted)
                            }
                        )
                    }
                }
            }
        }
    }

    suspend fun acceptCandidates(candidateIds: List<String>): Unit = withContext(dispatcher) {
        gate.withLock { acceptLocked(candidateIds) }
    }

    private suspend fun acceptLocked(candidateIds: List<String>) {
        val spaceId = mutable.value.selectedSpaceId ?: return
        if (candidateIds.isEmpty() || mutable.value.candidateActionInFlight) return
        mutable.update { it.copy(candidateActionInFlight = true, candidatesError = null, notice = null) }
        val result = try {
            admin.acceptExtractionCandidates(spaceId, candidateIds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        when (result) {
            null -> mutable.update { it.copy(candidateActionInFlight = false, candidatesError = CANDIDATES_FAILURE) }
            is AgentMemoryCandidateDecisionResult.Rejected -> mutable.update {
                it.copy(candidateActionInFlight = false, candidatesError = result.toUi())
            }
            is AgentMemoryCandidateDecisionResult.Applied -> {
                val decisionById = result.entries.associateBy { it.candidateId }
                mutable.update { current ->
                    current.copy(
                        candidateActionInFlight = false,
                        candidates = current.candidates.mapNotNull { row ->
                            when (decisionById[row.candidateId]?.decision) {
                                null -> row
                                AgentMemoryCandidateDecision.STORED,
                                AgentMemoryCandidateDecision.ALREADY_PRESENT,
                                AgentMemoryCandidateDecision.DISMISSED -> null
                                AgentMemoryCandidateDecision.REJECTED -> row.copy(
                                    errorLabel = decisionById[row.candidateId]?.errorCode
                                        ?: "MEMORY_CANDIDATE_REJECTED"
                                )
                            }
                        },
                        spaces = if (result.stored > 0) {
                            current.spaces.map { space ->
                                if (space.spaceId == spaceId) space.increaseBy(result.stored) else space
                            }
                        } else {
                            current.spaces
                        },
                        notice = decisionSummary(result)
                    )
                }
                if (result.stored > 0) loadItemsLocked(reset = true)
            }
        }
    }

    suspend fun rejectCandidates(candidateIds: List<String>): Unit = withContext(dispatcher) {
        gate.withLock { rejectLocked(candidateIds) }
    }

    private suspend fun rejectLocked(candidateIds: List<String>) {
        val spaceId = mutable.value.selectedSpaceId ?: return
        if (candidateIds.isEmpty() || mutable.value.candidateActionInFlight) return
        mutable.update { it.copy(candidateActionInFlight = true, candidatesError = null, notice = null) }
        val result = try {
            admin.rejectExtractionCandidates(spaceId, candidateIds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        when (result) {
            null -> mutable.update { it.copy(candidateActionInFlight = false, candidatesError = CANDIDATES_FAILURE) }
            is AgentMemoryCandidateDecisionResult.Rejected -> mutable.update {
                it.copy(candidateActionInFlight = false, candidatesError = result.toUi())
            }
            is AgentMemoryCandidateDecisionResult.Applied -> {
                val removed = candidateIds.toSet()
                mutable.update { current ->
                    current.copy(
                        candidateActionInFlight = false,
                        candidates = current.candidates.filterNot { it.candidateId in removed },
                        notice = "已忽略 ${result.dismissed} 条候选。"
                    )
                }
            }
        }
    }

    // --- dismissal ------------------------------------------------------------------------------

    fun dismissActionError() {
        mutable.update { it.copy(actionError = null) }
    }

    fun dismissSpacesError() {
        mutable.update { it.copy(spacesError = null) }
    }

    fun dismissItemsError() {
        mutable.update { it.copy(itemsError = null) }
    }

    fun dismissCandidatesError() {
        mutable.update { it.copy(candidatesError = null) }
    }

    fun dismissNotice() {
        mutable.update { it.copy(notice = null) }
    }

    // --- mapping --------------------------------------------------------------------------------

    private suspend fun listSpacesSafely(): AgentMemoryListResult? = try {
        admin.list()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private suspend fun loadDiagnostics() {
        val diagnostics = try {
            admin.semanticDiagnostics()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        } ?: return
        mutable.update { it.copy(semanticLabel = diagnosticsLabel(diagnostics)) }
    }

    private fun diagnosticsLabel(diagnostics: AgentMemorySemanticDiagnostics): String =
        if (diagnostics.mode == AgentMemorySemanticDiagnostics.MODE_HYBRID) {
            "混合检索（${diagnostics.fingerprint ?: "已配置"}）"
        } else {
            "仅词法检索（${reasonLabel(diagnostics.degradedReason)}）"
        }

    private fun reasonLabel(reason: String?): String = when (reason) {
        null -> "未说明"
        "NOT_CONFIGURED" -> "未配置本地向量"
        "DISABLED_BY_POLICY" -> "已按设置关闭"
        "INDEX_UNAVAILABLE" -> "向量索引不可用"
        "EMBEDDING_FAILED" -> "本地向量生成失败"
        "FINGERPRINT_MISMATCH" -> "向量指纹不匹配"
        "DIMENSION_MISMATCH" -> "向量维度不匹配"
        else -> reason
    }

    private fun toSpaceRow(item: AgentMemorySpaceItem): MemorySpaceRowUi = MemorySpaceRowUi(
        spaceId = item.spaceId,
        displayName = item.displayName,
        description = item.description,
        itemCount = item.itemCount,
        itemsLabel = itemsLabel(item.itemCount),
        egressLabel = item.egressLabel,
        updatedLabel = item.updatedLabel
    )

    private fun toItemRow(item: AgentMemoryManagedItem): MemoryItemRowUi = MemoryItemRowUi(
        spaceId = item.spaceId,
        itemId = item.itemId,
        title = item.title,
        excerpt = item.excerpt,
        revision = item.revision,
        sourceLabel = sourceKindLabel(item.sourceKind),
        updatedLabel = formatRelativeTime(clock(), item.updatedAt),
        revoked = item.revoked,
        hasSourceRef = item.hasSourceRef
    )

    private fun toCandidateRow(item: AgentMemoryCandidateItem): MemoryCandidateRowUi = MemoryCandidateRowUi(
        candidateId = item.candidateId,
        title = item.title,
        text = item.text,
        confidenceLabel = when (item.confidence) {
            AgentMemoryCandidateConfidence.LOW -> "低"
            AgentMemoryCandidateConfidence.MEDIUM -> "中"
            AgentMemoryCandidateConfidence.HIGH -> "高"
        },
        originLabel = if (item.originLabel.startsWith("model:")) {
            "模型：${item.originLabel.removePrefix("model:")}"
        } else {
            "规则：${item.originLabel}"
        },
        duplicateLabel = item.duplicateOfItemId?.let {
            if (item.duplicateRevoked) "已存在于已删除条目" else "已存在于该空间"
        }
    )

    private fun mergeItems(
        current: List<MemoryItemRowUi>,
        incoming: List<MemoryItemRowUi>
    ): List<MemoryItemRowUi> {
        val byId = LinkedHashMap<String, MemoryItemRowUi>(current.size + incoming.size)
        current.forEach { byId[it.itemId] = it }
        incoming.forEach { byId[it.itemId] = it }
        return byId.values.toList()
    }

    private fun decisionSummary(result: AgentMemoryCandidateDecisionResult.Applied): String = buildString {
        append("已接受 ${result.stored} 条候选")
        if (result.alreadyPresent > 0) append("；${result.alreadyPresent} 条已存在")
        if (result.rejected > 0) append("；${result.rejected} 条被拒绝")
        append("。")
    }

    private fun MemorySpaceRowUi.decrement(): MemorySpaceRowUi {
        val next = (itemCount - 1).coerceAtLeast(0)
        return copy(itemCount = next, itemsLabel = itemsLabel(next))
    }

    private fun MemorySpaceRowUi.increment(): MemorySpaceRowUi {
        val next = itemCount + 1
        return copy(itemCount = next, itemsLabel = itemsLabel(next))
    }

    private fun MemorySpaceRowUi.increaseBy(delta: Int): MemorySpaceRowUi {
        val next = itemCount + delta
        return copy(itemCount = next, itemsLabel = itemsLabel(next))
    }

    private fun AgentMemoryListResult.Rejected.toUi(): MemoryManagementErrorUi =
        MemoryManagementErrorUi(code, safeMessage)

    private fun AgentMemoryItemPageResult.Rejected.toUi(): MemoryManagementErrorUi =
        MemoryManagementErrorUi(code, safeMessage)

    private fun AgentMemoryItemActionResult.Rejected.toUi(): MemoryManagementErrorUi =
        MemoryManagementErrorUi(code, safeMessage)

    private fun AgentMemoryUpdateResult.Rejected.toUi(): MemoryManagementErrorUi =
        MemoryManagementErrorUi(code, safeMessage)

    private fun AgentMemoryExtractionSubmitResult.Rejected.toUi(): MemoryManagementErrorUi =
        MemoryManagementErrorUi(code, safeMessage)

    private fun AgentMemoryCandidateListResult.Rejected.toUi(): MemoryManagementErrorUi =
        MemoryManagementErrorUi(code, safeMessage)

    private fun AgentMemoryCandidateDecisionResult.Rejected.toUi(): MemoryManagementErrorUi =
        MemoryManagementErrorUi(code, safeMessage)

    private fun MemoryItemReadResult.Rejected.toUi(): MemoryManagementErrorUi =
        MemoryManagementErrorUi(code, safeMessage)

    private fun itemsLabel(count: Int): String = "$count 条记忆"

    private fun sourceKindLabel(sourceKind: String): String = when (sourceKind) {
        "AUTO_EXTRACTION" -> "自动提取"
        "FILE_IMPORT" -> "文件导入"
        "USER_TEXT" -> "手动记录"
        else -> sourceKind
    }

    private companion object {
        const val CODE_REVISION_CONFLICT: String = "MEMORY_REVISION_CONFLICT"
        const val CODE_SPACE_NOT_SELECTED: String = "MEMORY_SPACE_NOT_SELECTED"
        const val CODE_READ_UNAVAILABLE: String = "MEMORY_READ_UNAVAILABLE"

        val ADMIN_FAILURE: MemoryManagementErrorUi = MemoryManagementErrorUi(
            code = "MEMORY_ADMIN_FAILED",
            message = "记忆空间列表暂时无法载入，请稍后重试。"
        )
        val ITEMS_FAILURE: MemoryManagementErrorUi = MemoryManagementErrorUi(
            code = "MEMORY_ITEMS_FAILED",
            message = "记忆条目暂时无法载入，请稍后重试。"
        )
        val CANDIDATES_FAILURE: MemoryManagementErrorUi = MemoryManagementErrorUi(
            code = "MEMORY_CANDIDATES_FAILED",
            message = "待审核候选暂时无法载入，请稍后重试。"
        )
        val ACTION_FAILURE: MemoryManagementErrorUi = MemoryManagementErrorUi(
            code = "MEMORY_ACTION_FAILED",
            message = "记忆操作失败，请稍后重试。"
        )
        val READ_FAILURE: MemoryManagementErrorUi = MemoryManagementErrorUi(
            code = "MEMORY_READ_FAILED",
            message = "记忆全文读取失败，请稍后重试。"
        )
    }
}
