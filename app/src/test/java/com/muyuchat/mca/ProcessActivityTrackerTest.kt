package com.muyuchat.mca

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The last explicit Activity finish settles the process root; background/config changes do not. */
class ProcessActivityTrackerTest {

    @Test
    fun anExplicitFinishOfTheLastActivityClosesTheRoot() {
        val tracker = ProcessActivityTracker()
        tracker.started()
        tracker.stopped()

        assertTrue(tracker.shouldCloseOnDestroyed(isFinishing = true, isChangingConfigurations = false))
    }

    @Test
    fun aConfigurationChangeNeverClosesTheRoot() {
        val tracker = ProcessActivityTracker()
        tracker.started()
        tracker.stopped()

        assertFalse(tracker.shouldCloseOnDestroyed(isFinishing = true, isChangingConfigurations = true))
    }

    @Test
    fun aDestroyWithoutFinishNeverClosesTheRoot() {
        val tracker = ProcessActivityTracker()
        tracker.started()
        tracker.stopped()

        assertFalse(tracker.shouldCloseOnDestroyed(isFinishing = false, isChangingConfigurations = false))
    }

    @Test
    fun anotherStartedActivityKeepsTheRootAlive() {
        val tracker = ProcessActivityTracker()
        tracker.started() // first Activity
        tracker.started() // second Activity
        tracker.stopped()  // first stops

        assertFalse(
            tracker.shouldCloseOnDestroyed(isFinishing = true, isChangingConfigurations = false)
        )

        tracker.stopped() // second stops
        assertTrue(
            tracker.shouldCloseOnDestroyed(isFinishing = true, isChangingConfigurations = false)
        )
    }

    @Test
    fun stoppedIsClampedAtZero() {
        val tracker = ProcessActivityTracker()
        tracker.stopped()
        tracker.stopped()

        assertTrue(tracker.shouldCloseOnDestroyed(isFinishing = true, isChangingConfigurations = false))
    }

    @Test
    fun aStoppedButExistingActivityBlocksAWorkerOnlySettlement() {
        val tracker = ProcessActivityTracker()
        tracker.created()
        tracker.started()
        tracker.stopped()

        assertTrue(tracker.hasLiveActivities())
    }

    @Test
    fun destroyingTheLastActivityClearsTheLiveSet() {
        val tracker = ProcessActivityTracker()
        tracker.created()
        tracker.destroyed()
        tracker.destroyed()

        assertFalse(tracker.hasLiveActivities())
    }
}
