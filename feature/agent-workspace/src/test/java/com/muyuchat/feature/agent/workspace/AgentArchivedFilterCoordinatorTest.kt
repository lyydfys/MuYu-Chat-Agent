package com.muyuchat.feature.agent.workspace

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentArchivedFilterCoordinatorTest {

    @Test
    fun `default is off and toggle persists across a new coordinator`() {
        val handle = SavedStateHandle()
        val changes = mutableListOf<Boolean>()
        val coordinator = AgentArchivedFilterCoordinator(handle) { changes += it }

        assertFalse(coordinator.includeArchived)
        coordinator.toggle()
        assertTrue(coordinator.includeArchived)
        assertEquals(listOf(true), changes)

        val restored = AgentArchivedFilterCoordinator(handle) { }
        assertTrue(restored.includeArchived)

        coordinator.set(false)
        assertFalse(coordinator.includeArchived)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun `setting the current value does not restart the query`() {
        val handle = SavedStateHandle()
        val changes = mutableListOf<Boolean>()
        val coordinator = AgentArchivedFilterCoordinator(handle) { changes += it }

        coordinator.set(false)
        assertTrue(changes.isEmpty())
        coordinator.set(true)
        coordinator.set(true)
        assertEquals(listOf(true), changes)
    }

    @Test
    fun `two toggles return to the default off state`() {
        val handle = SavedStateHandle()
        val coordinator = AgentArchivedFilterCoordinator(handle) { }

        coordinator.toggle()
        coordinator.toggle()

        assertFalse(coordinator.includeArchived)
        assertEquals(false, handle.get<Boolean>(AgentArchivedFilterCoordinator.KEY_INCLUDE_ARCHIVED))
    }
}
