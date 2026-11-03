package com.muyuchat.mca.dsh.overlay

import com.muyuchat.core.dsh.contract.DshOverlayAppearance
import com.muyuchat.core.dsh.contract.DshOverlayReplyPreview
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bubble throttle/dedupe policy.
 *
 * The overlay is visible over other apps, so the cost of a mistake is high in both directions: a
 * missing throttle turns a long tool-loop turn into a stream of bubbles, and an over-eager dedupe hides
 * a genuinely new answer. Each rule is asserted separately.
 */
class DshOverlayBubbleGateTest {

    private val appearance = DshOverlayAppearance()

    private fun preview(text: String) = DshOverlayReplyPreview.of(null, text, atEpochMs = 0L)

    @Test
    fun firstBoundaryIsShown() {
        val gate = DshOverlayBubbleGate()

        assertEquals(
            DshOverlayBubbleDecision.SHOW,
            gate.decide(preview("第一轮回答"), appearance, nowMillis = 10_000L)
        )
    }

    @Test
    fun blankOrMissingPreviewNeverShows() {
        val gate = DshOverlayBubbleGate()

        assertEquals(DshOverlayBubbleDecision.EMPTY, gate.decide(null, appearance, 1_000L))
        assertEquals(
            DshOverlayBubbleDecision.EMPTY,
            gate.decide(DshOverlayReplyPreview.of(null, "   ", 0L), appearance, 2_000L)
        )
    }

    @Test
    fun bubblesOffOrZeroLinesDisableTheBubbleOnly() {
        val gate = DshOverlayBubbleGate()

        assertEquals(
            DshOverlayBubbleDecision.DISABLED,
            gate.decide(preview("a"), appearance.copy(notifyReplies = false), 1_000L)
        )
        assertEquals(
            DshOverlayBubbleDecision.DISABLED,
            gate.decide(preview("a"), appearance.copy(bubbleLines = 0), 2_000L)
        )
    }

    @Test
    fun aSecondBoundaryInsideTheMinimumIntervalIsDroppedNotQueued() {
        val gate = DshOverlayBubbleGate(minIntervalMillis = 2_000L)

        assertEquals(
            DshOverlayBubbleDecision.SHOW,
            gate.decide(preview("第一句"), appearance, nowMillis = 0L)
        )
        assertEquals(
            DshOverlayBubbleDecision.THROTTLED,
            gate.decide(preview("第二句"), appearance, nowMillis = 1_999L)
        )
        assertEquals(
            DshOverlayBubbleDecision.SHOW,
            gate.decide(preview("第三句"), appearance, nowMillis = 2_000L)
        )
    }

    @Test
    fun identicalTextInsideTheDedupeWindowIsDropped() {
        val gate = DshOverlayBubbleGate(minIntervalMillis = 10L, dedupeWindowMillis = 30_000L)

        assertEquals(
            DshOverlayBubbleDecision.SHOW,
            gate.decide(preview("重复的回答"), appearance, nowMillis = 0L)
        )
        assertEquals(
            DshOverlayBubbleDecision.DUPLICATE,
            gate.decide(preview(" 重复的回答 "), appearance, nowMillis = 5_000L)
        )
        // After the dedupe window the same text is a genuinely new answer again.
        assertEquals(
            DshOverlayBubbleDecision.SHOW,
            gate.decide(preview("重复的回答"), appearance, nowMillis = 40_000L)
        )
    }

    @Test
    fun differentTextIsNotDeduped() {
        val gate = DshOverlayBubbleGate(minIntervalMillis = 10L)

        assertEquals(
            DshOverlayBubbleDecision.SHOW,
            gate.decide(preview("第一段"), appearance, nowMillis = 0L)
        )
        assertEquals(
            DshOverlayBubbleDecision.SHOW,
            gate.decide(preview("第二段"), appearance, nowMillis = 1_000L)
        )
    }

    @Test
    fun aBackwardsClockDoesNotSilenceTheOverlay() {
        val gate = DshOverlayBubbleGate(minIntervalMillis = 2_000L)

        assertEquals(
            DshOverlayBubbleDecision.SHOW,
            gate.decide(preview("先"), appearance, nowMillis = 60_000L)
        )
        assertEquals(
            DshOverlayBubbleDecision.SHOW,
            gate.decide(preview("后"), appearance, nowMillis = 1_000L)
        )
    }

    @Test
    fun resetForgetsThrottleAndDedupeHistory() {
        val gate = DshOverlayBubbleGate()

        assertEquals(
            DshOverlayBubbleDecision.SHOW,
            gate.decide(preview("同一句"), appearance, nowMillis = 0L)
        )
        gate.reset()
        assertEquals(
            DshOverlayBubbleDecision.SHOW,
            gate.decide(preview("同一句"), appearance, nowMillis = 10L)
        )
    }
}
