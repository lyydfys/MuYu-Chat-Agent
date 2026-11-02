package com.muyuchat.core.dsh.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshBrowserTabsTest {

    @Test
    fun `allocates stable increasing ids and activates the first tab`() {
        val tabs = DshBrowserTabs(maxTabs = 3)
        val first = tabs.allocateId()
        assertEquals("t1", first)
        assertTrue(tabs.add(first))
        assertEquals("t1", tabs.activeTabId)
        val second = tabs.allocateId()
        assertEquals("t2", second)
        assertTrue(tabs.add(second))
        assertEquals("t1", tabs.activeTabId)
        tabs.switchTo(second)
        assertEquals("t2", tabs.activeTabId)
    }

    @Test
    fun `enforces the bounded tab count`() {
        val tabs = DshBrowserTabs(maxTabs = 2)
        assertTrue(tabs.add("t1"))
        assertTrue(tabs.add("t2"))
        assertTrue(tabs.isAtCapacity)
        assertFalse(tabs.add("t3"))
        assertEquals(2, tabs.size)
        assertEquals(2, tabs.capacity)
        tabs.remove("t2")
        assertTrue(tabs.add("t3"))
    }

    @Test
    fun `navigation advances both generations and drops the snapshot`() {
        val tabs = DshBrowserTabs(maxTabs = 2)
        tabs.add("t1")
        tabs.finishLoad("t1", "https://example.com", "Example", DshBrowserLoadState.READY)
        tabs.markSnapshot("t1")
        val before = tabs.find("t1")!!
        assertEquals(0L, before.pageGeneration)
        assertEquals(0L, before.navigationGeneration)
        assertTrue(before.hasSnapshot)

        val navigated = tabs.beginNavigation("t1", "https://example.com/next")!!
        assertEquals(1L, navigated.pageGeneration)
        assertEquals(1L, navigated.navigationGeneration)
        assertFalse(navigated.hasSnapshot)
        assertEquals(DshBrowserLoadState.LOADING, navigated.loadState)
        assertEquals("https://example.com/next", navigated.url)
        assertEquals("", navigated.title)
    }

    @Test
    fun `a DOM only mutation advances the page generation but not the ref epoch`() {
        val tabs = DshBrowserTabs(maxTabs = 2)
        tabs.add("t1")
        tabs.beginNavigation("t1", "https://example.com")
        val generation = tabs.noteDomMutation("t1")!!
        assertEquals(2L, generation)
        val tab = tabs.find("t1")!!
        assertEquals(2L, tab.pageGeneration)
        assertEquals(1L, tab.navigationGeneration)
    }

    @Test
    fun `closing the active tab activates the last remaining tab`() {
        val tabs = DshBrowserTabs(maxTabs = 3)
        tabs.add("t1")
        tabs.add("t2")
        tabs.add("t3")
        tabs.switchTo("t2")
        val removed = tabs.remove("t2")!!
        assertEquals("t2", removed.id)
        assertEquals("t3", tabs.activeTabId)
        assertEquals(2, tabs.size)
        tabs.remove("t3")
        assertEquals("t1", tabs.activeTabId)
        assertNull(tabs.remove("missing"))
    }

    @Test
    fun `list projects every tab and the model releases on clear`() {
        val tabs = DshBrowserTabs(maxTabs = 2)
        tabs.add("t1")
        tabs.add("t2")
        tabs.finishLoad("t1", "https://a.example", "A", DshBrowserLoadState.READY)
        val projected = tabs.list()
        assertEquals(listOf("t1", "t2"), projected.map { it.id })
        assertEquals("A", projected.first().title)
        tabs.clear()
        assertTrue(tabs.isEmpty())
        assertEquals("", tabs.activeTabId)
        assertNull(tabs.active())
    }
}
