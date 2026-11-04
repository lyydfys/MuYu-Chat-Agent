package com.muyuchat.mca.dsh.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Intent mapping of the two overlay panels.
 *
 * The window itself is Compose and cannot be asserted on the JVM, but every row only produces a
 * [DshOverlayActionId], and [DshOverlayActionId.perform] is pure. A recording fake therefore proves
 * exactly which side effect each row has — especially that "返回 DSH" only asks the app to come
 * forward and "停止后台运行" only stops the keep-alive.
 */
class DshOverlayWindowActionTest {

    private class RecordingActions(base: DshOverlaySettings = DshOverlaySettings()) : DshOverlayWindowActions {
        var settings: DshOverlaySettings = base
        var returnedToDsh = 0
        var stoppedBackground = 0

        override fun returnToDsh() {
            returnedToDsh += 1
        }

        override fun stopBackground() {
            stoppedBackground += 1
        }

        override fun updateSettings(transform: (DshOverlaySettings) -> DshOverlaySettings) {
            settings = transform(settings)
        }
    }

    @Test
    fun returnToDshAsksTheAppToComeForwardAndNothingElse() {
        val actions = RecordingActions(base = DshOverlaySettings(enabled = true, bubbleLines = 3))

        DshOverlayActionId.RETURN_TO_DSH.perform(actions)

        assertEquals(1, actions.returnedToDsh)
        assertEquals(0, actions.stoppedBackground)
        assertEquals("returning to DSH must not change any preference", 3, actions.settings.bubbleLines)
        assertTrue(actions.settings.enabled)
    }

    @Test
    fun stopBackgroundStopsTheKeepAliveAndNothingElse() {
        val actions = RecordingActions(base = DshOverlaySettings(enabled = true))

        DshOverlayActionId.STOP_BACKGROUND.perform(actions)

        assertEquals(1, actions.stoppedBackground)
        assertEquals(0, actions.returnedToDsh)
        assertTrue("stopping is a lifecycle decision, not a preference write", actions.settings.enabled)
    }

    @Test
    fun theNotificationSwitchIsAPureToggle() {
        val actions = RecordingActions(base = DshOverlaySettings(notifyReplies = true))

        DshOverlayActionId.TOGGLE_NOTIFY_REPLIES.perform(actions)
        assertFalse(actions.settings.notifyReplies)

        DshOverlayActionId.TOGGLE_NOTIFY_REPLIES.perform(actions)
        assertTrue(actions.settings.notifyReplies)
    }

    @Test
    fun theStreamingSwitchIsAPureToggle() {
        val actions = RecordingActions(base = DshOverlaySettings(streamingEnabled = true))

        DshOverlayActionId.TOGGLE_STREAMING.perform(actions)
        assertFalse(actions.settings.streamingEnabled)
    }

    @Test
    fun theDragSwitchIsAPureToggle() {
        val actions = RecordingActions(base = DshOverlaySettings(draggable = true))

        DshOverlayActionId.TOGGLE_DRAGGABLE.perform(actions)
        assertFalse(actions.settings.draggable)
    }

    @Test
    fun theBubbleLineRowCyclesForwardAndWrapsAtZero() {
        val actions = RecordingActions(base = DshOverlaySettings(bubbleLines = 3))

        DshOverlayActionId.CYCLE_BUBBLE_LINES.perform(actions)
        assertEquals(4, actions.settings.bubbleLines)

        repeat(2) { DshOverlayActionId.CYCLE_BUBBLE_LINES.perform(actions) }
        assertEquals("5 is the last stop before off", 0, actions.settings.bubbleLines)
    }

    @Test
    fun theBubbleLineCycleLeavesTheOtherSwitchesAlone() {
        val actions = RecordingActions(
            base = DshOverlaySettings(enabled = true, notifyReplies = false, draggable = false)
        )

        DshOverlayActionId.CYCLE_BUBBLE_LINES.perform(actions)

        assertTrue(actions.settings.enabled)
        assertFalse(actions.settings.notifyReplies)
        assertFalse(actions.settings.draggable)
    }

    @Test
    fun nextBubbleLinesCoversTheWholeRangeAndClampsStoredGarbage() {
        assertEquals(1, nextBubbleLines(0))
        assertEquals(2, nextBubbleLines(1))
        assertEquals(5, nextBubbleLines(4))
        assertEquals(0, nextBubbleLines(5))
        assertEquals("a stored value above the range restarts at off", 0, nextBubbleLines(9))
        assertEquals("a negative stored value advances to one line", 1, nextBubbleLines(-2))
    }
}
