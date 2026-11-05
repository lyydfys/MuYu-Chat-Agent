package com.muyuchat.feature.dsh.workspace.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native terminal page's risk lives in the pure half: turning raw PTY bytes into readable text,
 * folding modifiers into the right control bytes, keeping the tab strip consistent, and keeping the
 * text readable at both zoom ends. None of that needs a device to prove.
 */
class DshTerminalCoreTest {

    // ---------------------------------------------------------------- screen model

    @Test
    fun `plain text keeps its lines`() {
        val screen = DshTerminalScreen()
        screen.append("$ echo hi\nhi\n")
        // Completed lines plus the in-progress prompt line, which is empty after a trailing newline.
        assertEquals(listOf("$ echo hi", "hi", ""), screen.lines())
        assertTrue(screen.text().contains("hi"))
    }

    @Test
    fun `carriage return overwrites the prompt line instead of appending`() {
        val screen = DshTerminalScreen()
        screen.append("progress 10%\rprogress 100%\n")
        assertEquals(listOf("progress 100%"), screen.lines().dropLast(1))
    }

    @Test
    fun `backspace removes the previous character`() {
        val screen = DshTerminalScreen()
        screen.append("abcd\u0008\u0008X")
        assertEquals("abX", screen.tail())
    }

    @Test
    fun `colour sequences are dropped and text survives`() {
        val screen = DshTerminalScreen()
        screen.append("\u001b[31merror\u001b[0m: broke\n")
        assertEquals("error: broke", screen.lines().first())
    }

    @Test
    fun `erase line and erase display are honoured`() {
        val screen = DshTerminalScreen()
        screen.append("junk\njunk2\u001b[K")
        assertEquals("", screen.tail())
        screen.append("\u001b[2Jfresh\n")
        assertEquals("fresh", screen.lines().first())
    }

    @Test
    fun `an OSC title sequence is swallowed and does not leak into the text`() {
        val screen = DshTerminalScreen()
        screen.append("\u001b]0;bash\u0007prompt$ ")
        assertEquals("prompt$ ", screen.tail())
    }

    @Test
    fun `a sequence split across two reads is still recognised`() {
        val screen = DshTerminalScreen()
        screen.append("abc\u001b[")
        screen.append("31mred\n")
        assertEquals("abcred", screen.lines().first())
    }

    @Test
    fun `a runaway escape sequence cannot swallow the stream`() {
        val screen = DshTerminalScreen()
        screen.append("\u001b[" + "0".repeat(DshTerminalScreen.MAX_SEQUENCE_LENGTH + 10) + "after\n")
        // The sequence is abandoned, the trailing text is still visible somewhere on screen.
        assertTrue(screen.text().contains("after"))
    }

    @Test
    fun `the buffer is bounded by lines and by line length`() {
        val screen = DshTerminalScreen(maxLines = 5, maxLineLength = 10)
        repeat(50) { screen.append("line-$it\n") }
        // maxLines bounds *completed* lines; the in-progress prompt line is reported on top.
        assertTrue("kept ${screen.lines().size} lines", screen.lines().size <= 6)

        val long = DshTerminalScreen(maxLineLength = 4)
        long.append("abcdefghij\n")
        assertEquals("abcd", long.lines().first())
    }

    @Test
    fun `append never leaves an empty screen`() {
        val screen = DshTerminalScreen()
        screen.append("")
        assertTrue(screen.lines().isNotEmpty())
        screen.clear()
        assertEquals(listOf(""), screen.lines())
    }

    @Test
    fun `raw bytes decode as utf8 with replacement instead of throwing`() {
        val screen = DshTerminalScreen()
        val bytes = "中文 ok\n".toByteArray(Charsets.UTF_8)
        // Feed a truncated code point, then the rest: this is what a split PTY read looks like.
        screen.append(bytes, 0)
        screen.append("中文 ok\n".toByteArray(Charsets.UTF_8))
        assertTrue(screen.text().contains("ok"))
    }

    // ---------------------------------------------------------------- extended keys

    @Test
    fun `the key row carries real terminal sequences`() {
        assertEquals("\u001b[A", DshTerminalKeys.UP.payload)
        assertEquals("\u001b[D", DshTerminalKeys.LEFT.payload)
        assertEquals("\t", DshTerminalKeys.TAB.payload)
        assertEquals(DshTerminalKeys.CTRL_C.toString(), DshTerminalKeys.CTRL_C_KEY.payload)
        assertTrue("the row must offer enough keys to be usable", DshTerminalKeys.ROW.size >= 12)
    }

    @Test
    fun `ctrl folds a letter into its control byte`() {
        val ctrl = DshTerminalModifiers(ctrl = true)
        assertEquals(DshTerminalKeys.CTRL_C.toString(), DshTerminalKeys.applyModifiers("c", ctrl))
        assertEquals(DshTerminalKeys.CTRL_D.toString(), DshTerminalKeys.applyModifiers("D", ctrl))
        assertEquals(DshTerminalKeys.CTRL_L.toString(), DshTerminalKeys.applyModifiers("l", ctrl))
    }

    @Test
    fun `alt prefixes escape and the combination keeps both`() {
        val alt = DshTerminalModifiers(alt = true)
        assertEquals("\u001bx", DshTerminalKeys.applyModifiers("x", alt))
        val both = DshTerminalModifiers(ctrl = true, alt = true)
        assertEquals("\u001b${DshTerminalKeys.CTRL_C}", DshTerminalKeys.applyModifiers("c", both))
    }

    @Test
    fun `no modifiers leaves the payload untouched`() {
        val none = DshTerminalModifiers()
        assertEquals("\u001b[A", DshTerminalKeys.applyModifiers("\u001b[A", none))
        assertEquals("", DshTerminalKeys.applyModifiers("", DshTerminalModifiers(ctrl = true)))
        assertFalse(none.any)
        assertTrue(none.toggleCtrl().any)
        assertTrue(none.toggleCtrl().cleared() == none)
    }

    // ---------------------------------------------------------------- tabs

    @Test
    fun `a new tab waits for its session and then becomes ready`() {
        val tabs = DshTerminalTabs().open()
        assertEquals(1, tabs.tabs.size)
        val opening = requireNotNull(tabs.selected)
        assertEquals(DshTerminalTab.State.OPENING, opening.state)
        assertFalse(opening.isReady)

        val ready = tabs.opened(opening.id, "s-1")
        val readyTab = requireNotNull(ready.selected)
        assertTrue(readyTab.isReady)
        assertEquals("s-1", readyTab.sessionId)
        assertEquals("终端 1", readyTab.title)
    }

    @Test
    fun `the strip is bounded`() {
        var tabs = DshTerminalTabs(maxTabs = 2)
        tabs = tabs.open().open()
        assertFalse(tabs.canOpen)
        val unchanged = tabs.open()
        assertEquals(2, unchanged.tabs.size)
        assertNotEquals(null, unchanged.selectedId)
    }

    @Test
    fun `closing selects a neighbour and never leaves a dangling selection`() {
        var tabs = DshTerminalTabs().open().open().open()
        val ids = tabs.tabs.map { it.id }
        assertTrue(tabs.selectedId == ids[2])

        tabs = tabs.close(ids[2])
        assertTrue(tabs.selectedId == ids[1])

        tabs = tabs.close(ids[0])
        assertTrue(tabs.selectedId in ids)
        assertEquals(1, tabs.tabs.size)

        tabs = tabs.close(ids[1])
        assertNull(tabs.selectedId)
        assertTrue(tabs.tabs.isEmpty())
    }

    @Test
    fun `closing an unselected tab keeps the selection`() {
        val tabs = DshTerminalTabs().open().open()
        val ids = tabs.tabs.map { it.id }
        val after = tabs.close(ids[0])
        assertEquals(ids[1], after.selectedId)
    }

    @Test
    fun `a failed open stays visible with its reason`() {
        val tabs = DshTerminalTabs().open()
        val failed = tabs.failed(requireNotNull(tabs.selected).id, "no pty: " + "x".repeat(500))
        val failedTab = requireNotNull(failed.selected)
        assertEquals(DshTerminalTab.State.FAILED, failedTab.state)
        assertTrue(failedTab.detail!!.length <= DshTerminalTabs.MAX_DETAIL)
    }

    @Test
    fun `renaming is trimmed and bounded`() {
        val tabs = DshTerminalTabs().open()
        val id = requireNotNull(tabs.selected).id
        assertEquals("api", requireNotNull(tabs.rename(id, "  api  ").selected).title)
        assertTrue(
            requireNotNull(tabs.rename(id, "x".repeat(100)).selected).title.length <=
                DshTerminalTabs.MAX_TITLE
        )
        assertEquals("终端 1", requireNotNull(tabs.rename(id, "   ").selected).title)
    }

    // ---------------------------------------------------------------- zoom

    @Test
    fun `zoom clamps at both ends and survives nonsense input`() {
        assertEquals(DshTerminalZoom.MIN_SP, DshTerminalZoom.zoom(4f, 0.01f))
        assertEquals(DshTerminalZoom.MAX_SP, DshTerminalZoom.zoom(100f, 10f))
        assertEquals(DshTerminalZoom.DEFAULT_SP, DshTerminalZoom.zoom(Float.NaN, 2f))
        assertEquals(DshTerminalZoom.DEFAULT_SP, DshTerminalZoom.clamp(Float.NaN))
        assertEquals(DshTerminalZoom.MIN_SP, DshTerminalZoom.step(DshTerminalZoom.MIN_SP, -1))
        assertEquals(DshTerminalZoom.MAX_SP, DshTerminalZoom.step(DshTerminalZoom.MAX_SP, 1))
        assertEquals(DshTerminalZoom.clamp(14f), DshTerminalZoom.zoom(14f, 1f), 0.001f)
    }
}
