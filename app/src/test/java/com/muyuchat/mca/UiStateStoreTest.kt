package com.muyuchat.mca

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** R1/S10：UiStateStore 最小契约的行为保护网（纯 JVM 执行）。 */
class UiStateStoreTest {

    private val initial = MainUiState()

    @Test
    fun `update applies reducer and both views observe the new value`() {
        val store = UiStateStore(initial)
        store.update { it.copy(statusMessage = "第一") }
        assertEquals("第一", store.current.statusMessage)
        assertEquals("第一", store.state.value.statusMessage)
    }

    @Test
    fun `ownerFlow is the same backing instance as state source`() {
        val store = UiStateStore(initial)
        assertTrue(store.ownerFlow is MutableStateFlow)
        store.ownerFlow.value = initial.copy(statusMessage = "直写")
        assertEquals("直写", store.state.value.statusMessage)
    }

    @Test
    fun `state exposure is the store's own flow and is stable across reads`() {
        val store = UiStateStore(initial)
        assertSame(store.state, store.state)
        store.update { it.copy(statusMessage = "稳定") }
        assertEquals("稳定", store.current.statusMessage)
    }

    @Test
    fun `successive updates compose in order`() {
        val store = UiStateStore(initial)
        store.update { it.copy(statusMessage = "A") }
        store.update { it.copy(statusMessage = it.statusMessage + "B") }
        assertEquals("AB", store.current.statusMessage)
    }
}
