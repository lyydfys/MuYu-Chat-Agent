package com.muyuchat.feature.agent.workspace

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun memorySpace(
    id: String,
    name: String = id,
    count: Int = 0,
    description: String = "",
    egress: String? = "仅本地",
    updated: String? = null
) = AgentMemorySpaceItem(
    spaceId = id,
    displayName = name,
    description = description,
    itemCount = count,
    egressLabel = egress,
    updatedLabel = updated
)

class AgentMemoryCoordinatorTest {

    private class FakePort : AgentMemoryAdminPort {
        var result: AgentMemoryListResult = AgentMemoryListResult.Loaded(emptyList())
        var throwOnList = false
        var listCalls = 0

        override suspend fun list(): AgentMemoryListResult {
            listCalls++
            if (throwOnList) error("boom")
            return result
        }
    }

    private class Harness {
        val port = FakePort()
        val coordinator = AgentMemoryCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            port = port
        )
    }

    @Test
    fun `open lists spaces with their real item counts`() {
        val harness = Harness()
        harness.port.result = AgentMemoryListResult.Loaded(
            listOf(
                memorySpace("space-b", name = "项目笔记", count = 7),
                memorySpace("space-a", name = "偏好", count = 2)
            )
        )

        harness.coordinator.open()

        val state = harness.coordinator.state.value
        assertTrue(state.visible)
        assertFalse(state.loading)
        assertEquals(listOf("偏好", "项目笔记"), state.spaces.map { it.displayName })
        assertEquals(9, state.totalItemCount)
        assertEquals("7 条记忆", state.spaces.single { it.spaceId == "space-b" }.itemsLabel)
        assertNull(state.selectedSpace)
        assertFalse(state.empty)
    }

    @Test
    fun `selection info is derived from the loaded row, not a second copy`() {
        val harness = Harness()
        harness.port.result = AgentMemoryListResult.Loaded(
            listOf(
                memorySpace(
                    id = "space-a",
                    name = "项目笔记",
                    count = 3,
                    description = "任务复盘",
                    updated = "10:24"
                )
            )
        )
        harness.coordinator.open()

        harness.coordinator.selectSpace("space-a")

        val selected = harness.coordinator.state.value.selectedSpace
        assertEquals("space-a", selected?.spaceId)
        assertEquals("项目笔记", selected?.displayName)
        assertEquals(3, selected?.itemCount)
        assertEquals("3 条记忆", selected?.itemsLabel)
        assertEquals("任务复盘", selected?.description)
        assertEquals("仅本地", selected?.egressLabel)
        assertTrue(harness.coordinator.state.value.isSelected(
            harness.coordinator.state.value.spaces.single()
        ))
    }

    @Test
    fun `selecting an unknown space is ignored and does not fabricate one`() {
        val harness = Harness()
        harness.port.result = AgentMemoryListResult.Loaded(listOf(memorySpace("space-a")))
        harness.coordinator.open()

        harness.coordinator.selectSpace("missing")

        assertNull(harness.coordinator.state.value.selectedSpaceId)
        assertNull(harness.coordinator.state.value.selectedSpace)
        assertEquals(1, harness.coordinator.state.value.spaces.size)
    }

    @Test
    fun `refresh clears a selection that no longer exists`() {
        val harness = Harness()
        harness.port.result = AgentMemoryListResult.Loaded(
            listOf(memorySpace("space-a"), memorySpace("space-b"))
        )
        harness.coordinator.open()
        harness.coordinator.selectSpace("space-b")

        harness.port.result = AgentMemoryListResult.Loaded(listOf(memorySpace("space-a")))
        harness.coordinator.refresh()

        assertNull(harness.coordinator.state.value.selectedSpaceId)
        assertNull(harness.coordinator.state.value.selectedSpace)
    }

    @Test
    fun `close clears the page-local selection marker`() {
        val harness = Harness()
        harness.port.result = AgentMemoryListResult.Loaded(listOf(memorySpace("space-a")))
        harness.coordinator.open()
        harness.coordinator.selectSpace("space-a")

        harness.coordinator.close()

        assertFalse(harness.coordinator.state.value.visible)
        assertNull(harness.coordinator.state.value.selectedSpaceId)
        // The list itself is cached by the coordinator; only page-local selection resets.
        assertEquals(1, harness.coordinator.state.value.spaces.size)
    }

    @Test
    fun `empty and rejected states never invent a space`() {
        val emptyHarness = Harness()
        emptyHarness.port.result = AgentMemoryListResult.Loaded(emptyList())
        emptyHarness.coordinator.open()
        assertTrue(emptyHarness.coordinator.state.value.empty)
        assertTrue(emptyHarness.coordinator.state.value.spaces.isEmpty())
        assertNull(emptyHarness.coordinator.state.value.selectedSpace)

        val rejectedHarness = Harness()
        rejectedHarness.port.result = AgentMemoryListResult.Rejected(
            code = "MEMORY_LIST_DENIED",
            safeMessage = "无法读取记忆空间。"
        )
        rejectedHarness.coordinator.open()
        assertEquals("MEMORY_LIST_DENIED", rejectedHarness.coordinator.state.value.error?.code)
        assertEquals(
            "无法读取记忆空间。",
            rejectedHarness.coordinator.state.value.error?.message
        )
        assertTrue(rejectedHarness.coordinator.state.value.spaces.isEmpty())
    }

    @Test
    fun `a thrown list failure becomes a generic safe error and the default port is safe`() {
        val harness = Harness()
        harness.port.throwOnList = true
        harness.coordinator.open()
        assertEquals("MEMORY_ADMIN_FAILED", harness.coordinator.state.value.error?.code)

        val coordinator = AgentMemoryCoordinator(
            scope = CoroutineScope(Dispatchers.Unconfined),
            port = AgentMemoryAdminPort.Unavailable
        )
        coordinator.open()
        assertEquals("MEMORY_ADMIN_UNAVAILABLE", coordinator.state.value.error?.code)
        assertTrue(coordinator.state.value.spaces.isEmpty())
    }
}
