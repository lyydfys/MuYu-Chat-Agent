package com.muyuchat.mca.dsh.overlay

import com.muyuchat.api.dsh.gateway.DshGatewayBackend
import com.muyuchat.core.dsh.contract.DshFinishReason
import com.muyuchat.core.dsh.contract.DshGatewayModel
import com.muyuchat.core.dsh.contract.DshModelEvent
import com.muyuchat.core.dsh.contract.DshModelRequest
import com.muyuchat.core.dsh.contract.DshOverlayReplyPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reply observation on the existing DSH model gateway.
 *
 * The decorator must be invisible to the transport (same events, same order) and must produce exactly
 * one bubble boundary per completed turn that actually contains assistant text.
 */
class DshOverlayReplyObserverTest {

    private val request = DshModelRequest(callId = "dsh:1:1", modelId = "m", messages = emptyList())

    private class Recorder {
        var busy = 0
        var idle = 0
        val replies = mutableListOf<DshOverlayReplyPreview>()
    }

    private fun observerFor(
        events: List<DshModelEvent>,
        recorder: Recorder
    ): DshOverlayReplyObserver = DshOverlayReplyObserver(
        delegate = object : DshGatewayBackend {
            override suspend fun catalog(): List<DshGatewayModel> = emptyList()

            override fun stream(request: DshModelRequest): Flow<DshModelEvent> =
                flowOf(*events.toTypedArray())
        },
        onBusy = { recorder.busy += 1 },
        onIdle = { recorder.idle += 1 },
        onReply = { recorder.replies += it },
        clock = { 1_234L }
    )

    @Test
    fun aCompletedTurnWithTextRaisesExactlyOneBubble() = runTest {
        val events = listOf(
            DshModelEvent.TextDelta("你好"),
            DshModelEvent.TextDelta("，我是小鲸鱼"),
            DshModelEvent.Completed(DshFinishReason.STOP, promptTokens = 10, completionTokens = 5)
        )
        val recorder = Recorder()

        val forwarded = observerFor(events, recorder).stream(request).toList()

        assertEquals("the transport must still see every event unchanged", events, forwarded)
        assertEquals(1, recorder.busy)
        assertEquals(1, recorder.idle)
        assertEquals(1, recorder.replies.size)
        assertEquals("你好，我是小鲸鱼", recorder.replies.single().text)
        assertEquals(1_234L, recorder.replies.single().atEpochMs)
    }

    @Test
    fun streamingDeltasNeverRaiseABubbleOnTheirOwn() = runTest {
        val events = listOf(
            DshModelEvent.TextDelta("第一段"),
            DshModelEvent.TextDelta("，第二段")
        )
        val recorder = Recorder()

        // A cancelled collection is what a peer disconnect/host stop looks like.
        observerFor(events, recorder).stream(request).take(1).toList()

        assertEquals(1, recorder.busy)
        assertEquals("the mark must leave the working mood on cancellation", 1, recorder.idle)
        assertTrue(recorder.replies.isEmpty())
    }

    @Test
    fun aTruncatedAnswerStillRaisesABubble() = runTest {
        val events = listOf(
            DshModelEvent.TextDelta("被截断的回答"),
            DshModelEvent.Completed(DshFinishReason.LENGTH)
        )
        val recorder = Recorder()

        observerFor(events, recorder).stream(request).toList()

        assertEquals(1, recorder.replies.size)
    }

    @Test
    fun toolCallAndFailedTurnsDoNotRaiseABubble() = runTest {
        val toolCall = Recorder()
        observerFor(
            listOf(DshModelEvent.Completed(DshFinishReason.TOOL_CALLS)),
            toolCall
        ).stream(request).toList()
        assertTrue(toolCall.replies.isEmpty())
        assertEquals(0, toolCall.busy)

        val failed = Recorder()
        observerFor(
            listOf(
                DshModelEvent.TextDelta("部分文字"),
                DshModelEvent.Failed(code = "backend_error", message = "boom")
            ),
            failed
        ).stream(request).toList()
        assertTrue(failed.replies.isEmpty())
        assertEquals(1, failed.busy)
        assertEquals(1, failed.idle)
    }

    @Test
    fun cancelledFinishReasonDoesNotRaiseABubble() = runTest {
        val recorder = Recorder()

        observerFor(
            listOf(
                DshModelEvent.TextDelta("半句"),
                DshModelEvent.Completed(DshFinishReason.CANCELLED)
            ),
            recorder
        ).stream(request).toList()

        assertTrue(recorder.replies.isEmpty())
        assertEquals(1, recorder.idle)
    }

    @Test
    fun aTurnWithoutTextDoesNotRaiseABubble() = runTest {
        val recorder = Recorder()

        observerFor(
            listOf(DshModelEvent.Completed(DshFinishReason.STOP)),
            recorder
        ).stream(request).toList()

        assertTrue(recorder.replies.isEmpty())
        assertNull(recorder.replies.firstOrNull())
        assertEquals(0, recorder.busy)
        assertEquals(0, recorder.idle)
        assertFalse(recorder.replies.isNotEmpty())
    }

    @Test
    fun theBubbleTextIsClampedAtTheContractLimit() = runTest {
        val recorder = Recorder()
        val longAnswer = "长".repeat(DshOverlayReplyPreview.MAX_PREVIEW_CHARS + 50)

        observerFor(
            listOf(
                DshModelEvent.TextDelta(longAnswer),
                DshModelEvent.Completed(DshFinishReason.STOP)
            ),
            recorder
        ).stream(request).toList()

        val preview = recorder.replies.single()
        assertEquals(DshOverlayReplyPreview.MAX_PREVIEW_CHARS, preview.text.length)
        assertTrue(preview.truncated)
    }
}
