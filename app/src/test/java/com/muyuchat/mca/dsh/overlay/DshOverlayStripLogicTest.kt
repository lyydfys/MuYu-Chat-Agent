package com.muyuchat.mca.dsh.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure rules of the top streaming strip.
 *
 * The window itself needs a device, but everything that can be wrong (height envelope, tail clipping,
 * elapsed progress, fade, approval routing, persisted values) is a total function here and is pinned by
 * these cases. The service only wires timers and views around them.
 */
class DshOverlayStripLogicTest {

    @Test
    fun `height adapts to line count and font scale inside the 44 to 64 dp envelope`() {
        assertEquals(44, DshOverlayStripLogic.heightDp(lines = 1, fontScale = 1f))
        assertEquals(56, DshOverlayStripLogic.heightDp(lines = 2, fontScale = 1f))
        assertEquals(53, DshOverlayStripLogic.heightDp(lines = 1, fontScale = 1.2f))
        assertEquals(64, DshOverlayStripLogic.heightDp(lines = 2, fontScale = 2f))
    }

    @Test
    fun `height clamps invalid lines and unusable font scales instead of crashing`() {
        assertEquals(44, DshOverlayStripLogic.heightDp(lines = 0, fontScale = 1f))
        assertEquals(56, DshOverlayStripLogic.heightDp(lines = 9, fontScale = 1f))
        assertEquals(44, DshOverlayStripLogic.heightDp(lines = 1, fontScale = 0f))
        assertEquals(44, DshOverlayStripLogic.heightDp(lines = 1, fontScale = Float.NaN))
        assertEquals(64, DshOverlayStripLogic.heightDp(lines = 2, fontScale = 1.4f))
    }

    @Test
    fun `tail text keeps the newest characters and marks the cut`() {
        val tail = DshOverlayStripLogic.tailText("0123456789", maxChars = 4)

        assertEquals("…789", tail)
        assertTrue(tail.endsWith("789"))
    }

    @Test
    fun `tail text leaves short text untouched and handles an empty budget`() {
        assertEquals("abc", DshOverlayStripLogic.tailText("abc", maxChars = 10))
        assertEquals("", DshOverlayStripLogic.tailText("abc", maxChars = 0))
    }

    @Test
    fun `tail text never starts inside a surrogate pair`() {
        val tail = DshOverlayStripLogic.tailText("\uD83D\uDE00\uD83D\uDE00", maxChars = 2)

        assertFalse(tail.isNotEmpty() && tail.first().isLowSurrogate())
        assertTrue(tail.length <= 2)
    }

    @Test
    fun `elapsed progress is clamped to zero and one`() {
        assertEquals(0f, DshOverlayStripLogic.progress(-50L), 0.0001f)
        assertEquals(0.5f, DshOverlayStripLogic.progress(15_000L), 0.0001f)
        assertEquals(1f, DshOverlayStripLogic.progress(30_000L), 0.0001f)
        assertEquals(1f, DshOverlayStripLogic.progress(99_000L), 0.0001f)
        assertEquals(0f, DshOverlayStripLogic.progress(1_000L, fullMs = 0L), 0.0001f)
    }

    @Test
    fun `tool wording is human and never echoes raw arguments`() {
        assertEquals("▸ 运行命令…", DshOverlayStripLogic.toolText("运行命令"))
        assertEquals("▸ 使用工具…", DshOverlayStripLogic.toolText("  "))
    }

    @Test
    fun `line selection prefers tool wording and carries activity`() {
        val streaming = DshOverlayStripLogic.lineFor(
            DshOverlayStream.Streaming("你好世界", startedAtMs = 1L)
        )
        assertNotNull(streaming)
        assertEquals(false, streaming!!.tool)
        assertTrue(streaming.active)
        assertTrue(streaming.text.contains("你好世界"))

        val tool = DshOverlayStripLogic.lineFor(DshOverlayStream.ToolRunning("运行命令"))
        assertEquals("▸ 运行命令…", tool!!.text)
        assertTrue(tool.tool)

        val finished = DshOverlayStripLogic.lineFor(DshOverlayStream.Finished("完成"))
        assertEquals(false, finished!!.active)

        assertNull(DshOverlayStripLogic.lineFor(DshOverlayStream.Idle))
    }

    @Test
    fun `the strip owns approval only while it is on and attached`() {
        assertEquals(
            DshOverlayStripOwner.STRIP,
            DshOverlayStripLogic.approvalOwner(true, stripAttached = true, ballAttached = true)
        )
        assertEquals(
            DshOverlayStripOwner.BALL,
            DshOverlayStripLogic.approvalOwner(true, stripAttached = false, ballAttached = true)
        )
        assertEquals(
            DshOverlayStripOwner.BALL,
            DshOverlayStripLogic.approvalOwner(false, stripAttached = true, ballAttached = true)
        )
        assertEquals(
            DshOverlayStripOwner.NONE,
            DshOverlayStripLogic.approvalOwner(false, stripAttached = false, ballAttached = false)
        )
    }

    @Test
    fun `finished turns keep the strip for the same 6 point 5 seconds then fade`() {
        val finishedAt = 1_000L
        val fadeAt = DshOverlayStripLogic.fadeDeadline(finishedAt)

        assertEquals(7_500L, fadeAt)
        assertEquals(1f, DshOverlayStripLogic.alphaAt(7_000L, fadeAt), 0.0001f)
        assertEquals(0.5f, DshOverlayStripLogic.alphaAt(7_660L, fadeAt), 0.0001f)
        assertEquals(0f, DshOverlayStripLogic.alphaAt(8_000L, fadeAt), 0.0001f)
        assertEquals(1f, DshOverlayStripLogic.alphaAt(1L, fadeStartMs = null), 0.0001f)
    }

    @Test
    fun `settings clamp opacity and lines and default to off`() {
        val defaults = DshOverlayStripSettings()
        assertFalse(defaults.enabled)
        assertEquals(DshOverlayStripSurface.DARK, defaults.surface)
        assertEquals(1f, defaults.opacity, 0.0001f)
        assertEquals(2, defaults.lines)
        assertTrue(defaults.safeArea)

        val clamped = DshOverlayStripSettings(opacity = 0.1f, lines = 9).normalized()
        assertEquals(0.35f, clamped.opacity, 0.0001f)
        assertEquals(2, clamped.lines)

        val tooHigh = DshOverlayStripSettings(opacity = 4f, lines = 0).normalized()
        assertEquals(1f, tooHigh.opacity, 0.0001f)
        assertEquals(1, tooHigh.lines)
    }

    @Test
    fun `the codec round trips every stored field`() {
        val original = DshOverlayStripSettings(
            enabled = true,
            surface = DshOverlayStripSurface.TRANSLUCENT,
            opacity = 0.55f,
            lines = 1,
            safeArea = false
        )

        assertEquals(original, DshOverlayStripCodec.decode(DshOverlayStripCodec.encode(original)))
    }

    @Test
    fun `bad documents and out of range values fall back to clamped defaults`() {
        assertEquals(DshOverlayStripSettings(), DshOverlayStripCodec.decode(null))
        assertEquals(DshOverlayStripSettings(), DshOverlayStripCodec.decode("{"))
        assertEquals(DshOverlayStripSettings(), DshOverlayStripCodec.decode("[]"))

        val clamped = DshOverlayStripCodec.decode(
            """{"enabled":true,"surface":"NEON","opacity":0.01,"lines":99,"safeArea":false}"""
        )
        assertTrue(clamped.enabled)
        assertEquals(DshOverlayStripSurface.DARK, clamped.surface)
        assertEquals(0.35f, clamped.opacity, 0.0001f)
        assertEquals(2, clamped.lines)
        assertFalse(clamped.safeArea)
    }

    @Test
    fun `approval decisions are idempotent per pending request`() {
        val queue = DshOverlayStripApprovalQueue()

        assertTrue(queue.present(7L))
        assertFalse(queue.present(7L))
        assertFalse(queue.decide(8L))
        assertTrue(queue.isPending(7L))
        assertTrue(queue.decide(7L))
        assertFalse(queue.decide(7L))
        assertFalse(queue.isPending(7L))
        assertFalse(queue.present(7L))
        assertTrue(queue.present(9L))
    }

    @Test
    fun `a cancelled request can never be authorised afterwards`() {
        val queue = DshOverlayStripApprovalQueue()
        assertTrue(queue.present(3L))
        queue.cancel(3L)

        assertFalse(queue.decide(3L))
        assertFalse(queue.isPending(3L))
        assertTrue(queue.present(4L))
        queue.dismissAny()
        assertFalse(queue.decide(4L))
    }
}
