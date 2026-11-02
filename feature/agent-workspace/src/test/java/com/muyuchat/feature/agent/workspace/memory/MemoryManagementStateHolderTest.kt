package com.muyuchat.feature.agent.workspace.memory

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
import com.muyuchat.feature.agent.workspace.AgentMemorySpaceItem
import com.muyuchat.feature.agent.workspace.AgentMemoryUpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM behavior of the memory management state holder over a fake `AgentMemoryAdminPort`.
 *
 * The Compose layer is not exercised here (device visual/interaction evidence stays NOT_RUN); these
 * tests pin the state machine: loading, paging, search, tombstone visibility, typed errors, editor
 * CAS and extraction review.
 */
class MemoryManagementStateHolderTest {

    // --- fixtures -------------------------------------------------------------------------------

    private data class ItemCall(
        val spaceId: String,
        val query: String?,
        val offset: Int,
        val limit: Int,
        val includeRevoked: Boolean
    )

    private data class DeleteCall(val spaceId: String, val itemId: String, val revision: Long)

    private class FakeAdmin : AgentMemoryAdminPort {
        var listResult: AgentMemoryListResult = AgentMemoryListResult.Loaded(emptyList())
        var listThrows = false
        var listCalls = 0

        var pageResult: AgentMemoryItemPageResult = emptyPage()
        val itemCalls = mutableListOf<ItemCall>()

        var deleteResult: AgentMemoryItemActionResult =
            AgentMemoryItemActionResult.Applied("space-a", "item-1", 2L)
        val deleteCalls = mutableListOf<DeleteCall>()

        var restoreResult: AgentMemoryItemActionResult =
            AgentMemoryItemActionResult.Applied("space-a", "item-2", 2L)
        val restoreCalls = mutableListOf<DeleteCall>()

        var updateResult: AgentMemoryUpdateResult =
            AgentMemoryUpdateResult.Updated("space-a", "item-1", 4L)
        val updateCalls = mutableListOf<UpdateCall>()

        var diagnostics: AgentMemorySemanticDiagnostics =
            AgentMemorySemanticDiagnostics(
                mode = AgentMemorySemanticDiagnostics.MODE_LEXICAL,
                degradedReason = "NOT_CONFIGURED"
            )
        var diagnosticsThrows = false

        var submitResult: AgentMemoryExtractionSubmitResult =
            AgentMemoryExtractionSubmitResult.Proposed(emptyList(), truncated = false, modelStatus = "DISABLED")
        var candidatesResult: AgentMemoryCandidateListResult =
            AgentMemoryCandidateListResult.Loaded("space-a", emptyList(), submittedAt = null)
        var acceptResult: AgentMemoryCandidateDecisionResult =
            AgentMemoryCandidateDecisionResult.Applied(emptyList(), 0, 0, 0, 0)
        var rejectResult: AgentMemoryCandidateDecisionResult =
            AgentMemoryCandidateDecisionResult.Applied(emptyList(), 0, 0, 0, 0)

        override suspend fun list(): AgentMemoryListResult {
            listCalls++
            if (listThrows) error("boom")
            return listResult
        }

        override suspend fun listItems(
            spaceId: String,
            query: String?,
            offset: Int,
            limit: Int,
            includeRevoked: Boolean
        ): AgentMemoryItemPageResult {
            itemCalls += ItemCall(spaceId, query, offset, limit, includeRevoked)
            return pageResult
        }

        override suspend fun deleteItem(
            spaceId: String,
            itemId: String,
            expectedRevision: Long
        ): AgentMemoryItemActionResult {
            deleteCalls += DeleteCall(spaceId, itemId, expectedRevision)
            return deleteResult
        }

        override suspend fun restoreItem(
            spaceId: String,
            itemId: String,
            expectedRevision: Long
        ): AgentMemoryItemActionResult {
            restoreCalls += DeleteCall(spaceId, itemId, expectedRevision)
            return restoreResult
        }

        override suspend fun updateItem(
            spaceId: String,
            itemId: String,
            expectedRevision: Long,
            title: String?,
            text: String
        ): AgentMemoryUpdateResult {
            updateCalls += UpdateCall(spaceId, itemId, expectedRevision, title, text)
            return updateResult
        }

        override suspend fun submitExtraction(
            spaceId: String,
            text: String,
            sourceRef: String?
        ): AgentMemoryExtractionSubmitResult = submitResult

        override suspend fun listExtractionCandidates(spaceId: String): AgentMemoryCandidateListResult =
            candidatesResult

        override suspend fun acceptExtractionCandidates(
            spaceId: String,
            candidateIds: List<String>
        ): AgentMemoryCandidateDecisionResult = acceptResult

        override suspend fun rejectExtractionCandidates(
            spaceId: String,
            candidateIds: List<String>
        ): AgentMemoryCandidateDecisionResult = rejectResult

        override suspend fun semanticDiagnostics(): AgentMemorySemanticDiagnostics {
            if (diagnosticsThrows) error("diagnostics")
            return diagnostics
        }
    }

    private data class UpdateCall(
        val spaceId: String,
        val itemId: String,
        val expectedRevision: Long,
        val title: String?,
        val text: String
    )

    private class FakeReader : MemoryItemReader {
        var result: MemoryItemReadResult = MemoryItemReadResult.Loaded(
            MemoryItemDetail(
                spaceId = "space-a",
                itemId = "item-1",
                title = "标题",
                text = "完整内容",
                revision = 3L,
                updatedAt = 1_000L,
                sourceKind = "USER_TEXT",
                sourceRef = null,
                revoked = false
            )
        )
        var calls = 0

        override suspend fun readItem(spaceId: String, itemId: String): MemoryItemReadResult {
            calls++
            return result
        }
    }

    private fun holder(
        admin: FakeAdmin,
        reader: MemoryItemReader? = null,
        pageSize: Int = 2
    ): MemoryManagementStateHolder = MemoryManagementStateHolder(
        admin = admin,
        reader = reader,
        dispatcher = Dispatchers.Unconfined,
        pageSize = pageSize,
        clock = { NOW }
    )

    private fun space(
        id: String,
        name: String = id,
        count: Int = 3
    ): AgentMemorySpaceItem = AgentMemorySpaceItem(
        spaceId = id,
        displayName = name,
        itemCount = count,
        egressLabel = "仅本地"
    )

    private fun item(
        id: String,
        revision: Long = 1L,
        revoked: Boolean = false,
        sourceKind: String = "USER_TEXT"
    ): AgentMemoryManagedItem = AgentMemoryManagedItem(
        spaceId = "space-a",
        itemId = id,
        title = "标题 $id",
        excerpt = "摘要 $id",
        revision = revision,
        updatedAt = NOW - 60_000L,
        sourceKind = sourceKind,
        revoked = revoked,
        hasSourceRef = sourceKind == "AUTO_EXTRACTION"
    )

    private fun page(
        items: List<AgentMemoryManagedItem>,
        total: Long = items.size.toLong(),
        hasMore: Boolean = false,
        offset: Int = 0,
        limit: Int = 2
    ): AgentMemoryItemPageResult = AgentMemoryItemPageResult.Loaded(
        spaceId = "space-a",
        items = items,
        total = total.toInt(),
        offset = offset,
        limit = limit,
        hasMore = hasMore
    )

    private fun candidate(id: String) = AgentMemoryCandidateItem(
        candidateId = id,
        text = "请记住：$id",
        title = "$id 标题",
        confidence = AgentMemoryCandidateConfidence.HIGH,
        originLabel = "remember_explicit"
    )

    private fun loadedAdmin(vararg spaces: AgentMemorySpaceItem): FakeAdmin = FakeAdmin().apply {
        listResult = AgentMemoryListResult.Loaded(spaces.toList())
    }

    // --- tests ----------------------------------------------------------------------------------

    @Test
    fun `refresh loads spaces and the semantic capability label`() = runBlocking {
        val admin = loadedAdmin(space("space-a", "偏好", 3), space("space-b", "项目", 1))
        val holder = holder(admin)

        holder.refresh()

        val state = holder.state.value
        assertFalse(state.loadingSpaces)
        assertEquals(listOf("偏好", "项目"), state.spaces.map { it.displayName })
        assertEquals("3 条记忆", state.spaces.first().itemsLabel)
        assertNull(state.selectedSpaceId)
        assertEquals("仅词法检索（未配置本地向量）", state.semanticLabel)
        assertEquals(1, admin.listCalls)
    }

    @Test
    fun `a typed list rejection becomes the spaces error`() = runBlocking {
        val admin = FakeAdmin().apply {
            listResult = AgentMemoryListResult.Rejected("MEMORY_LIST_DENIED", "无法读取记忆空间。")
        }
        val holder = holder(admin)

        holder.refresh()

        val state = holder.state.value
        assertEquals("MEMORY_LIST_DENIED", state.spacesError?.code)
        assertEquals("无法读取记忆空间。", state.spacesError?.message)
        assertFalse(state.spacesEmpty)
    }

    @Test
    fun `a thrown list failure maps to the generic safe error`() = runBlocking {
        val admin = FakeAdmin().apply { listThrows = true }
        val holder = holder(admin)

        holder.refresh()

        assertEquals("MEMORY_ADMIN_FAILED", holder.state.value.spacesError?.code)
        assertTrue(holder.state.value.spaces.isEmpty())
    }

    @Test
    fun `selecting a space requests the first page with bounded parameters`() = runBlocking {
        val admin = loadedAdmin(space("space-a"))
        admin.pageResult = page(listOf(item("item-1"), item("item-2")), total = 5, hasMore = true)
        val holder = holder(admin)
        holder.refresh()

        holder.selectSpace("space-a")

        assertEquals(
            ItemCall("space-a", null, 0, 2, false),
            admin.itemCalls.single()
        )
        val state = holder.state.value
        assertEquals("space-a", state.selectedSpaceId)
        assertEquals(2, state.items.size)
        assertEquals(5, state.itemsTotal)
        assertTrue(state.canLoadMore)
        assertFalse(state.itemsEmpty)
    }

    @Test
    fun `an unknown space selection is ignored and never fabricates one`() = runBlocking {
        val admin = loadedAdmin(space("space-a"))
        val holder = holder(admin)
        holder.refresh()

        holder.selectSpace("space-missing")

        assertNull(holder.state.value.selectedSpaceId)
        assertTrue(admin.itemCalls.isEmpty())
    }

    @Test
    fun `search trims the query and reloads from offset zero`() = runBlocking {
        val admin = loadedAdmin(space("space-a"))
        admin.pageResult = page(listOf(item("item-1")), total = 1)
        val holder = holder(admin)
        holder.refresh()
        holder.selectSpace("space-a")

        holder.onQueryChange("  退款  ")
        holder.search()
        assertEquals("退款", admin.itemCalls.last().query)
        assertEquals(0, admin.itemCalls.last().offset)

        holder.onQueryChange("   ")
        holder.search()
        assertNull(admin.itemCalls.last().query)
    }

    @Test
    fun `load more appends the next page at the current size offset`() = runBlocking {
        val admin = loadedAdmin(space("space-a"))
        admin.pageResult = page(listOf(item("item-1"), item("item-2")), total = 3, hasMore = true)
        val holder = holder(admin)
        holder.refresh()
        holder.selectSpace("space-a")

        admin.pageResult = page(listOf(item("item-3")), total = 3, hasMore = false, offset = 2)
        holder.loadMore()

        assertEquals(2, admin.itemCalls.last().offset)
        assertEquals(listOf("item-1", "item-2", "item-3"), holder.state.value.items.map { it.itemId })
        assertFalse(holder.state.value.canLoadMore)

        val callsBefore = admin.itemCalls.size
        holder.loadMore()
        assertEquals(callsBefore, admin.itemCalls.size)
    }

    @Test
    fun `toggling tombstone visibility reloads the page with the flag`() = runBlocking {
        val admin = loadedAdmin(space("space-a"))
        admin.pageResult = page(listOf(item("item-1")), total = 1)
        val holder = holder(admin)
        holder.refresh()
        holder.selectSpace("space-a")

        holder.setIncludeRevoked(true)

        assertTrue(holder.state.value.includeRevoked)
        assertTrue(admin.itemCalls.last().includeRevoked)
    }

    @Test
    fun `deleting a live row removes it while tombstones stay hidden`() = runBlocking {
        val admin = loadedAdmin(space("space-a", count = 2))
        admin.pageResult = page(listOf(item("item-1"), item("item-2")), total = 2)
        admin.deleteResult = AgentMemoryItemActionResult.Applied("space-a", "item-1", 2L)
        val holder = holder(admin)
        holder.refresh()
        holder.selectSpace("space-a")

        holder.deleteItem("item-1")

        val state = holder.state.value
        assertEquals(listOf("item-2"), state.items.map { it.itemId })
        assertEquals(1, state.itemsTotal)
        assertEquals(1, state.spaces.single().itemCount)
        assertEquals(1L, admin.deleteCalls.single().revision)
        assertTrue(state.notice.orEmpty().contains("可恢复"))
    }

    @Test
    fun `deleting with tombstones visible keeps the row as revoked`() = runBlocking {
        val admin = loadedAdmin(space("space-a", count = 1))
        admin.pageResult = page(listOf(item("item-1")), total = 2, hasMore = true)
        admin.deleteResult = AgentMemoryItemActionResult.Applied("space-a", "item-1", 2L)
        val holder = holder(admin)
        holder.refresh()
        holder.selectSpace("space-a")
        holder.setIncludeRevoked(true)

        holder.deleteItem("item-1")

        assertEquals(listOf("item-1"), holder.state.value.items.map { it.itemId })
        assertTrue(holder.state.value.items.single().revoked)
        assertEquals(2L, holder.state.value.items.single().revision)
    }

    @Test
    fun `a stale delete exposes the typed conflict and refreshes the page`() = runBlocking {
        val admin = loadedAdmin(space("space-a", count = 3))
        admin.pageResult = page(listOf(item("item-1")), total = 1)
        admin.deleteResult = AgentMemoryItemActionResult.Rejected(
            code = "MEMORY_REVISION_CONFLICT",
            safeMessage = "该条目已被修改，请重新加载。"
        )
        val holder = holder(admin)
        holder.refresh()
        holder.selectSpace("space-a")
        admin.pageResult = page(listOf(item("item-1", revision = 5L)), total = 1)

        holder.deleteItem("item-1")

        assertEquals("MEMORY_REVISION_CONFLICT", holder.state.value.actionError?.code)
        assertEquals(5L, holder.state.value.items.single().revision)
        // select page + one refresh after the conflict; the reload starts at offset 0.
        assertEquals(2, admin.itemCalls.size)
        assertEquals(0, admin.itemCalls.last().offset)
    }

    @Test
    fun `restoring a revoked row marks it live and increments the space count`() = runBlocking {
        val admin = loadedAdmin(space("space-a", count = 1))
        admin.pageResult = page(listOf(item("item-2", revoked = true)), total = 1)
        admin.restoreResult = AgentMemoryItemActionResult.Applied("space-a", "item-2", 4L)
        val holder = holder(admin)
        holder.refresh()
        holder.selectSpace("space-a")

        holder.restoreItem("item-2")

        val state = holder.state.value
        assertFalse(state.items.single().revoked)
        assertEquals(4L, state.items.single().revision)
        assertEquals(2, state.spaces.single().itemCount)
        assertTrue(state.notice.orEmpty().contains("已恢复"))
    }

    @Test
    fun `opening the editor without a reader is a typed unavailability`() = runBlocking {
        val admin = loadedAdmin(space("space-a"))
        admin.pageResult = page(listOf(item("item-1")), total = 1)
        val holder = holder(admin)
        holder.refresh()
        holder.selectSpace("space-a")

        holder.openEditor("item-1")

        assertNull(holder.state.value.editor)
        assertEquals("MEMORY_READ_UNAVAILABLE", holder.state.value.actionError?.code)
    }

    @Test
    fun `the editor loads full text and save closes it under the loaded revision`() = runBlocking {
        val admin = loadedAdmin(space("space-a"))
        admin.pageResult = page(listOf(item("item-1")), total = 1)
        val reader = FakeReader()
        val holder = holder(admin, reader)
        holder.refresh()
        holder.selectSpace("space-a")

        holder.openEditor("item-1")
        assertEquals("完整内容", holder.state.value.editor?.text)
        assertEquals(3L, holder.state.value.editor?.expectedRevision)

        holder.onEditorTitleChange("新标题")
        holder.onEditorTextChange("新的完整内容")
        val callsBefore = admin.itemCalls.size
        holder.saveEditor()

        assertNull(holder.state.value.editor)
        val update = admin.updateCalls.single()
        assertEquals("space-a", update.spaceId)
        assertEquals("item-1", update.itemId)
        assertEquals(3L, update.expectedRevision)
        assertEquals("新标题", update.title)
        assertEquals("新的完整内容", update.text)
        assertEquals(callsBefore + 1, admin.itemCalls.size)
        assertTrue(holder.state.value.notice.orEmpty().contains("已保存"))
    }

    @Test
    fun `a save conflict keeps the editor open with a typed error`() = runBlocking {
        val admin = loadedAdmin(space("space-a"))
        admin.pageResult = page(listOf(item("item-1")), total = 1)
        admin.updateResult = AgentMemoryUpdateResult.Rejected(
            code = "MEMORY_REVISION_CONFLICT",
            safeMessage = "该条目已被修改。"
        )
        val holder = holder(admin, FakeReader())
        holder.refresh()
        holder.selectSpace("space-a")
        holder.openEditor("item-1")
        holder.onEditorTextChange("新内容")

        holder.saveEditor()

        val editor = holder.state.value.editor
        assertNotNull(editor)
        assertEquals("MEMORY_REVISION_CONFLICT", editor?.error?.code)
        assertFalse(editor!!.saving)
        assertEquals("新内容", editor.text)
    }

    @Test
    fun `editing a revoked row is refused before reading its body`() = runBlocking {
        val admin = loadedAdmin(space("space-a"))
        admin.pageResult = page(listOf(item("item-1", revoked = true)), total = 1)
        val reader = FakeReader()
        val holder = holder(admin, reader)
        holder.refresh()
        holder.selectSpace("space-a")

        holder.openEditor("item-1")

        assertNull(holder.state.value.editor)
        assertEquals("MEMORY_READ_UNAVAILABLE", holder.state.value.actionError?.code)
        assertEquals(0, reader.calls)
    }

    @Test
    fun `extraction publishes reviewable candidates and clears the input`() = runBlocking {
        val admin = loadedAdmin(space("space-a"))
        admin.pageResult = page(emptyList(), total = 0)
        admin.submitResult = AgentMemoryExtractionSubmitResult.Proposed(
            candidates = listOf(candidate("candidate-1")),
            truncated = true,
            modelStatus = "DISABLED"
        )
        val holder = holder(admin)
        holder.refresh()
        holder.selectSpace("space-a")

        holder.onExtractionInputChange("请记住：周五发版")
        holder.submitExtraction()

        val state = holder.state.value
        assertEquals("", state.extractionInput)
        assertEquals(1, state.candidates.size)
        assertEquals("高", state.candidates.single().confidenceLabel)
        assertEquals("规则：remember_explicit", state.candidates.single().originLabel)
        assertEquals("刚刚", state.candidatesSubmittedLabel)
        assertTrue(state.notice.orEmpty().contains("候选较多"))
        assertFalse(state.candidatesEmpty)
    }

    @Test
    fun `extraction rejection is surfaced on the candidates section`() = runBlocking {
        val admin = loadedAdmin(space("space-a"))
        admin.submitResult = AgentMemoryExtractionSubmitResult.Rejected(
            code = "MEMORY_TEXT_EMPTY",
            safeMessage = "文本不能为空。"
        )
        val holder = holder(admin)
        holder.refresh()

        holder.selectSpace("space-a")
        holder.submitExtraction()

        assertEquals("MEMORY_TEXT_EMPTY", holder.state.value.candidatesError?.code)
        assertTrue(holder.state.value.candidates.isEmpty())
    }

    @Test
    fun `accepting candidates removes stored rows, keeps rejections and reloads items`() = runBlocking {
        val admin = loadedAdmin(space("space-a", count = 1))
        admin.pageResult = page(emptyList(), total = 0)
        admin.candidatesResult = AgentMemoryCandidateListResult.Loaded(
            spaceId = "space-a",
            candidates = listOf(candidate("candidate-1"), candidate("candidate-2")),
            submittedAt = NOW - 1_000L
        )
        admin.acceptResult = AgentMemoryCandidateDecisionResult.Applied(
            entries = listOf(
                AgentMemoryCandidateDecisionEntry(
                    candidateId = "candidate-1",
                    decision = AgentMemoryCandidateDecision.STORED,
                    itemId = "item-9"
                ),
                AgentMemoryCandidateDecisionEntry(
                    candidateId = "candidate-2",
                    decision = AgentMemoryCandidateDecision.REJECTED,
                    errorCode = "MEMORY_CANDIDATE_INVALID"
                )
            ),
            stored = 1,
            alreadyPresent = 0,
            rejected = 1,
            dismissed = 0
        )
        val holder = holder(admin)
        holder.refresh()
        holder.selectSpace("space-a")
        assertEquals(2, holder.state.value.candidates.size)
        val callsBefore = admin.itemCalls.size

        holder.acceptCandidates(listOf("candidate-1", "candidate-2"))

        val state = holder.state.value
        assertEquals(listOf("candidate-2"), state.candidates.map { it.candidateId })
        assertEquals("MEMORY_CANDIDATE_INVALID", state.candidates.single().errorLabel)
        assertEquals(2, state.spaces.single().itemCount)
        assertEquals(callsBefore + 1, admin.itemCalls.size)
        assertTrue(state.notice.orEmpty().contains("已接受 1 条"))
    }

    @Test
    fun `rejecting candidates removes them without touching items`() = runBlocking {
        val admin = loadedAdmin(space("space-a"))
        admin.candidatesResult = AgentMemoryCandidateListResult.Loaded(
            spaceId = "space-a",
            candidates = listOf(candidate("candidate-1"), candidate("candidate-2")),
            submittedAt = NOW
        )
        admin.rejectResult = AgentMemoryCandidateDecisionResult.Applied(
            entries = listOf(
                AgentMemoryCandidateDecisionEntry("candidate-1", AgentMemoryCandidateDecision.DISMISSED)
            ),
            stored = 0,
            alreadyPresent = 0,
            rejected = 0,
            dismissed = 1
        )
        val holder = holder(admin)
        holder.refresh()
        holder.selectSpace("space-a")
        val callsBefore = admin.itemCalls.size

        holder.rejectCandidates(listOf("candidate-1"))

        assertEquals(listOf("candidate-2"), holder.state.value.candidates.map { it.candidateId })
        assertEquals(callsBefore, admin.itemCalls.size)
        assertTrue(holder.state.value.notice.orEmpty().contains("已忽略"))
    }

    @Test
    fun `dismiss clears the notice and the typed action error`() = runBlocking {
        val admin = loadedAdmin(space("space-a", count = 1))
        admin.pageResult = page(listOf(item("item-1")), total = 1)
        admin.deleteResult = AgentMemoryItemActionResult.Rejected("MEMORY_STORAGE_FAILED", "写入失败。")
        val holder = holder(admin)
        holder.refresh()
        holder.selectSpace("space-a")
        holder.deleteItem("item-1")
        assertNotNull(holder.state.value.actionError)

        holder.dismissActionError()

        assertNull(holder.state.value.actionError)
    }

    @Test
    fun `items empty is derived from the loaded page, not from a fabricated row`() = runBlocking {
        val admin = loadedAdmin(space("space-a"))
        admin.pageResult = page(emptyList(), total = 0)
        val holder = holder(admin)
        holder.refresh()
        holder.selectSpace("space-a")

        assertTrue(holder.state.value.itemsEmpty)
        assertTrue(holder.state.value.items.isEmpty())
        assertFalse(holder.state.value.loadingItems)
    }

    private companion object {
        const val NOW: Long = 1_760_000_000_000L
    }
}

private fun emptyPage(): AgentMemoryItemPageResult = AgentMemoryItemPageResult.Loaded(
    spaceId = "space-a",
    items = emptyList(),
    total = 0,
    offset = 0,
    limit = 2,
    hasMore = false
)
