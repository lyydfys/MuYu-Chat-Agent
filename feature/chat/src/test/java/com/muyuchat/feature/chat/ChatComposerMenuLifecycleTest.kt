package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R04：behavior contract for the composer menu family.
 *
 * These tests drive the real [ComposerMenuLifecycle] state machine (the one the composables use),
 * not source strings. "0 / 30 / 70ms after the close" is modelled as "the exit is still running,
 * then on its last frame, then removed": the owner has no timers, so the phases are what decide
 * whether a business action may run.
 */
class ChatComposerMenuLifecycleTest {

    /** Minimal stand-in for the host: only the menu can stop a back from reaching the page. */
    private class Host {
        val menu = ComposerMenuLifecycle()
        var pageBacks = 0
        var businessActions = 0
        var draft = "draft text"

        fun showMenu() {
            menu.open()
            // The sheet content is composed on the next frame and stays composed until removed.
            menu.menu.onContentComposed()
        }

        fun showResearch() {
            menu.toggleResearch()
            menu.research.onContentComposed()
        }

        fun systemBack() {
            if (!menu.handleBack()) pageBacks++
        }

        /** A tap on a menu row while the sheet is at whatever phase it currently is. */
        fun tapRow() {
            menu.runMenuAction { businessActions++ }
        }

        /** Both layers' exit frames are over: their content leaves composition. */
        fun settleAll() {
            menu.menu.onContentDisposed()
            menu.research.onContentDisposed()
        }
    }

    @Test
    fun `system back closes the sheet only and keeps the page and draft`() {
        val host = Host()
        host.showMenu()
        assertEquals(ComposerMenuPhase.Open, host.menu.menu.phase)

        host.systemBack()
        assertEquals(ComposerMenuPhase.Closing, host.menu.menu.phase)
        assertEquals("the page must not receive the gesture", 0, host.pageBacks)
        assertEquals("draft text", host.draft)
        // Still composed and still blocking while the exit is visible.
        assertTrue(host.menu.isComposed)
        assertTrue(host.menu.menu.isVisible)

        host.systemBack()
        assertEquals("a fading menu consumes, it does not pop twice", 0, host.pageBacks)
        assertEquals(ComposerMenuPhase.Closing, host.menu.menu.phase)

        host.settleAll()
        assertEquals(ComposerMenuPhase.Closed, host.menu.menu.phase)
        assertFalse(host.menu.isComposed)
        host.systemBack()
        assertEquals("only a closed menu lets the page go back", 1, host.pageBacks)
    }

    @Test
    fun `research submenu closes before the sheet`() {
        val host = Host()
        host.showMenu()
        host.showResearch()
        assertEquals(ComposerMenuPhase.Open, host.menu.research.phase)

        host.systemBack()
        assertEquals(ComposerMenuPhase.Closing, host.menu.research.phase)
        assertEquals("the sheet stays open under the submenu", ComposerMenuPhase.Open, host.menu.menu.phase)
        assertEquals(0, host.pageBacks)

        host.menu.research.onContentDisposed()
        assertEquals(ComposerMenuPhase.Closed, host.menu.research.phase)

        host.systemBack()
        assertEquals(ComposerMenuPhase.Closing, host.menu.menu.phase)
        assertEquals(0, host.pageBacks)

        host.settleAll()
        host.systemBack()
        assertEquals(1, host.pageBacks)
    }

    @Test
    fun `a second back during the submenu exit is consumed, not forwarded`() {
        val host = Host()
        host.showMenu()
        host.showResearch()

        host.systemBack()
        host.systemBack()

        assertEquals(ComposerMenuPhase.Closing, host.menu.research.phase)
        assertEquals("the sheet must not close in the same double gesture", ComposerMenuPhase.Open, host.menu.menu.phase)
        assertEquals(0, host.pageBacks)
    }

    @Test
    fun `row taps in the closing window never run the business action`() {
        val host = Host()
        host.showMenu()

        // The close starts; the 0 / 30 / 70ms taps all land on the still-visible exit layer.
        host.systemBack()
        host.tapRow()
        host.tapRow()
        host.tapRow()
        assertEquals(0, host.businessActions)

        // The exit frame ends and the layer is gone: the old row position is no longer a control.
        host.settleAll()
        host.tapRow()
        assertEquals(0, host.businessActions)
        assertEquals(0, host.pageBacks)
    }

    @Test
    fun `a selected row runs its action exactly once and then closes`() {
        val host = Host()
        host.showMenu()

        var calls = 0
        assertTrue(host.menu.runMenuAction { calls++ })
        assertEquals(1, calls)
        assertEquals(ComposerMenuPhase.Closing, host.menu.menu.phase)

        // The same row cannot fire a second time while the close is in flight, and the exit
        // completion does not re-run it either.
        assertFalse(host.menu.runMenuAction { calls++ })
        host.settleAll()
        assertFalse(host.menu.runMenuAction { calls++ })
        assertEquals(1, calls)
    }

    @Test
    fun `closing keeps the layer visible and blocking but refuses actions`() {
        val host = Host()
        host.showMenu()
        host.menu.dismiss()

        assertTrue(host.menu.isComposed)
        assertTrue(host.menu.menu.isVisible)
        assertFalse("the exit must not accept business actions", host.menu.menu.acceptsActions)
        assertFalse(host.menu.menu.wantsVisible)

        // Repeated dismiss (scrim + back + row in one frame) is idempotent.
        assertFalse(host.menu.menu.requestClose())
        assertFalse(host.menu.menu.requestClose())
        assertEquals(ComposerMenuPhase.Closing, host.menu.menu.phase)
    }

    @Test
    fun `open and close in the same frame never waits for an animation frame`() {
        // System animator scale 0x collapses the transition; a layer that never composed its content
        // has nothing to animate and must close synchronously instead of staying "closing" forever.
        val menu = ComposerMenuLifecycle()
        menu.open()
        assertFalse(menu.menu.isRendered)
        assertEquals(ComposerMenuPhase.Opening, menu.menu.phase)

        menu.dismiss()
        assertEquals(ComposerMenuPhase.Closed, menu.menu.phase)
        assertFalse(menu.isComposed)
        assertFalse("nothing visible may keep consuming back", menu.handleBack())
    }

    @Test
    fun `twenty open and close cycles leave no layer behind`() {
        val host = Host()
        repeat(20) { cycle ->
            host.showMenu()
            host.showResearch()
            assertTrue("cycle $cycle: sheet open", host.menu.menu.acceptsActions)
            assertTrue("cycle $cycle: submenu open", host.menu.research.acceptsActions)

            host.systemBack()
            host.menu.research.onContentDisposed()
            host.systemBack()
            host.menu.menu.onContentDisposed()

            assertEquals(ComposerMenuPhase.Closed, host.menu.research.phase)
            assertEquals(ComposerMenuPhase.Closed, host.menu.menu.phase)
            assertFalse("cycle $cycle: no scrim may survive", host.menu.isComposed)
        }
        assertEquals("no cycle leaked a back gesture to the page", 0, host.pageBacks)
        host.systemBack()
        assertEquals(1, host.pageBacks)
    }

    @Test
    fun `reopening during the exit keeps the same owner and ignores the stale close`() {
        val host = Host()
        host.showMenu()
        host.systemBack()
        assertEquals(ComposerMenuPhase.Closing, host.menu.menu.phase)

        host.menu.open()
        assertEquals(ComposerMenuPhase.Open, host.menu.menu.phase)
        assertTrue(host.menu.menu.acceptsActions)

        // The previous exit completing late must not close the menu that is open again.
        host.menu.menu.onContentDisposed()
        assertEquals(ComposerMenuPhase.Open, host.menu.menu.phase)
        assertTrue(host.menu.menu.acceptsActions)

        host.tapRow()
        assertEquals(1, host.businessActions)
        assertEquals(ComposerMenuPhase.Closing, host.menu.menu.phase)
    }

    @Test
    fun `the research submenu never outlives the sheet`() {
        val host = Host()
        host.showMenu()
        host.showResearch()

        host.menu.dismiss()
        assertFalse(host.menu.menu.acceptsActions)
        assertFalse("the child layer closes with its parent", host.menu.research.acceptsActions)

        host.menu.menu.onContentDisposed()
        host.menu.research.onContentDisposed()
        assertEquals(ComposerMenuPhase.Closed, host.menu.menu.phase)
        assertEquals(ComposerMenuPhase.Closed, host.menu.research.phase)
        assertFalse(host.menu.isComposed)
    }

    @Test
    fun `research selection runs once and closes both layers`() {
        val host = Host()
        host.showMenu()
        host.showResearch()

        var selected: String? = null
        assertTrue(host.menu.runResearchAction { selected = "DEEP" })
        assertEquals("DEEP", selected)
        assertEquals(ComposerMenuPhase.Closing, host.menu.research.phase)
        assertEquals(ComposerMenuPhase.Closing, host.menu.menu.phase)

        assertFalse(host.menu.runResearchAction { selected = "OFF" })
        assertEquals("DEEP", selected)
    }

    @Test
    fun `a restored lifecycle starts open and actionable without replaying the entrance`() {
        val restored = ComposerMenuLifecycle(restoredVisible = true)
        assertEquals(ComposerMenuPhase.Open, restored.menu.phase)
        assertTrue(restored.menu.acceptsActions)
        assertTrue(restored.isComposed)
        assertFalse(restored.research.isVisible)
    }
}
