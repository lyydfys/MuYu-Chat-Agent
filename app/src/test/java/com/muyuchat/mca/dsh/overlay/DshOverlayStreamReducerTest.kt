package com.muyuchat.mca.dsh.overlay

import com.muyuchat.core.dsh.contract.DshOverlayReplyPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Streaming state machine for the overlay.
 *
 * These are the rules the window relies on: append without losing the newest characters, never leak a
 * tool argument into a label, and always settle to a terminal state so the progress arc can stop.
 */
class DshOverlayStreamReducerTest {

    @Test
    fun aFirstDeltaStartsAStreamWithTheClockTimestamp() {
        val state = DshOverlayStreamReducer.onDelta(DshOverlayStream.Idle, "你好", nowMs = 1_000L)

        assertEquals(DshOverlayStream.Streaming(text = "你好", startedAtMs = 1_000L), state)
    }

    @Test
    fun laterDeltasAppendAndKeepTheOriginalStartTime() {
        var state: DshOverlayStream = DshOverlayStreamReducer.onDelta(
            DshOverlayStream.Idle, "第一句", nowMs = 1_000L
        )
        state = DshOverlayStreamReducer.onDelta(state, "，第二句", nowMs = 1_500L)

        assertEquals(
            DshOverlayStream.Streaming(text = "第一句，第二句", startedAtMs = 1_000L),
            state
        )
    }

    @Test
    fun anEmptyDeltaIsIgnored() {
        val current = DshOverlayStream.Streaming(text = "已有文字", startedAtMs = 5L)

        assertEquals(current, DshOverlayStreamReducer.onDelta(current, "", nowMs = 9L))
    }

    @Test
    fun aLeadingWhitespaceOnlyChunkDoesNotLookLikeBrokenText() {
        val state = DshOverlayStreamReducer.onDelta(DshOverlayStream.Idle, "\n\n  正文", nowMs = 5L)

        assertEquals("正文", (state as DshOverlayStream.Streaming).text)
    }

    @Test
    fun overflowKeepsTheTailAndMarksTheCutWithAnEllipsis() {
        val head = "A".repeat(50)
        val tail = "B".repeat(50)

        val state = DshOverlayStreamReducer.onDelta(
            DshOverlayStream.Streaming(text = head, startedAtMs = 0L),
            tail,
            nowMs = 1L,
            limit = 40
        )

        val text = (state as DshOverlayStream.Streaming).text
        assertEquals(40, text.length)
        assertTrue("the cut is marked: $text", text.startsWith("…"))
        assertTrue("the newest characters survive: $text", text.endsWith("B".repeat(39)))
    }

    @Test
    fun theDefaultLimitMatchesTheFinishedPreviewLimit() {
        assertEquals(
            DshOverlayReplyPreview.MAX_PREVIEW_CHARS,
            DshOverlayStreamReducer.MAX_STREAM_CHARS
        )
    }

    @Test
    fun theTailWindowNeverStartsInsideASurrogatePair() {
        val emoji = "🐳"
        val text = "x" + emoji.repeat(5)

        val clamped = DshOverlayStreamReducer.clampTail(text, limit = 4)

        // Dropping the dangling half of a split emoji costs one character, so the result is one
        // shorter than the limit instead of starting with a lone low surrogate.
        assertTrue(clamped.startsWith("…"))
        assertTrue("no lone low surrogate: $clamped", clamped[1].isHighSurrogate())
        assertEquals(3, clamped.length)
        assertEquals("…" + emoji, clamped)
    }

    @Test
    fun aZeroLimitProducesAnEmptyString() {
        assertEquals("", DshOverlayStreamReducer.clampTail("anything", limit = 0))
    }

    @Test
    fun toolNamesMapToHumanLabelsOnly() {
        assertEquals("运行命令", DshOverlayToolLabels.of("shell"))
        assertEquals("读取文件", DshOverlayToolLabels.of("read_file"))
        assertEquals("上网查看", DshOverlayToolLabels.of("browser"))
        assertEquals("使用工具", DshOverlayToolLabels.of("totally_unknown_tool"))
        assertEquals("使用工具", DshOverlayToolLabels.of(null))
        assertEquals("使用工具", DshOverlayToolLabels.of("   "))
        assertEquals("运行命令", DshOverlayToolLabels.of("SHELL"))
        assertEquals("读取文件", DshOverlayToolLabels.of("read_file(path=\"/etc/hosts\")"))
    }

    @Test
    fun aToolNameThatCarriesArgumentsNeverLeaksThem() {
        val labelled = DshOverlayToolLabels.of("shell\n{\"cmd\":\"rm -rf /data\"}")

        assertEquals("运行命令", labelled)
        assertTrue(!labelled.contains("/data"))
        assertTrue(!labelled.contains("rm -rf"))
    }

    @Test
    fun aPathPassedAsAToolNameIsReportedAsTheGenericLabel() {
        assertEquals("使用工具", DshOverlayToolLabels.of("/data/local/tmp/secret.txt"))
        assertEquals("使用工具", DshOverlayToolLabels.of("https://example.com/x?q=1"))
    }

    @Test
    fun aToolStartBecomesAQuietToolState() {
        val state = DshOverlayStreamReducer.onToolStart(
            DshOverlayStream.Streaming(text = "思考中", startedAtMs = 1L),
            "shell"
        )

        assertEquals(DshOverlayStream.ToolRunning(label = "运行命令"), state)
    }

    @Test
    fun aTurnEndUsesTheFinalTextWhenTheTransportSuppliesIt() {
        val state = DshOverlayStreamReducer.onTurnEnd(
            DshOverlayStream.Streaming(text = "半句", startedAtMs = 1L),
            finalText = "完整回答"
        )

        assertEquals(DshOverlayStream.Finished(text = "完整回答"), state)
    }

    @Test
    fun aTurnEndFallsBackToTheAccumulatedStreamText() {
        val state = DshOverlayStreamReducer.onTurnEnd(
            DshOverlayStream.Streaming(text = "已经收到的文字", startedAtMs = 1L),
            finalText = null
        )

        assertEquals(DshOverlayStream.Finished(text = "已经收到的文字"), state)
    }

    @Test
    fun anEmptyTurnEndGoesIdleInsteadOfShowingAnEmptyBubble() {
        assertEquals(
            DshOverlayStream.Idle,
            DshOverlayStreamReducer.onTurnEnd(DshOverlayStream.ToolRunning("运行命令"), finalText = "   ")
        )
        assertEquals(
            DshOverlayStream.Idle,
            DshOverlayStreamReducer.onTurnEnd(DshOverlayStream.Idle, finalText = null)
        )
    }

    @Test
    fun aFinishedTextIsClippedToThePreviewLimit() {
        val state = DshOverlayStreamReducer.onTurnEnd(
            DshOverlayStream.Idle,
            finalText = "长".repeat(DshOverlayStreamReducer.MAX_STREAM_CHARS + 40)
        )

        assertEquals(
            DshOverlayStreamReducer.MAX_STREAM_CHARS,
            (state as DshOverlayStream.Finished).text.length
        )
    }

    @Test
    fun abortAlwaysClearsTheLiveText() {
        assertEquals(
            DshOverlayStream.Idle,
            DshOverlayStreamReducer.onAbort(DshOverlayStream.Streaming("半截", 1L))
        )
        assertEquals(
            DshOverlayStream.Idle,
            DshOverlayStreamReducer.onAbort(DshOverlayStream.ToolRunning("运行命令"))
        )
        assertEquals(DshOverlayStream.Idle, DshOverlayStreamReducer.onAbort(DshOverlayStream.Idle))
    }
}
