package com.muyuchat.mca

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The single state source publishes a stable read-only view and named status updates. */
class UiStateStoreTest {

    private val initial = MainUiState()

    @Test
    fun `status command updates both read views`() {
        val store = UiStateStore(initial)
        store.showStatus("第一")
        assertEquals("第一", store.current.statusMessage)
        assertEquals("第一", store.state.value.statusMessage)
    }

    @Test
    fun `public flow cannot be cast back to the mutable source`() {
        val store = UiStateStore(initial)
        val exposed: StateFlow<*> = store.state
        assertFalse(exposed is MutableStateFlow<*>)
    }

    @Test
    fun `state exposure is the store's own flow and is stable across reads`() {
        val store = UiStateStore(initial)
        assertSame(store.state, store.state)
        store.showStatus("稳定")
        assertEquals("稳定", store.current.statusMessage)
    }

    @Test
    fun `clearing status preserves all unrelated state`() {
        val before = initial.copy(input = "草稿", hubPage = 7, revision = 23L, statusMessage = "A")
        val store = UiStateStore(before)
        store.showStatus(null)
        assertNull(store.current.statusMessage)
        assertEquals(before.copy(statusMessage = null), store.current)
    }
}
