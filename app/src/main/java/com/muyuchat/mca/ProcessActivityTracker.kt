package com.muyuchat.mca

/**
 * Decides whether a destroyed Activity is the last one leaving the task.
 *
 * Only an explicit finish (user removed the task) settles the process runtime root; a configuration
 * change, or a destroy while another Activity is still started, must not stop background work.
 */
internal class ProcessActivityTracker {
    private var startedActivities = 0
    private var liveActivities = 0

    @Synchronized
    fun created() {
        liveActivities += 1
    }

    @Synchronized
    fun started() {
        startedActivities += 1
    }

    @Synchronized
    fun stopped() {
        if (startedActivities > 0) startedActivities -= 1
    }

    @Synchronized
    fun destroyed() {
        if (liveActivities > 0) liveActivities -= 1
    }

    /** True while any Activity instance exists, including one that is only stopped/backgrounded. */
    @Synchronized
    fun hasLiveActivities(): Boolean = liveActivities > 0

    @Synchronized
    fun shouldCloseOnDestroyed(
        isFinishing: Boolean,
        isChangingConfigurations: Boolean
    ): Boolean = isFinishing && !isChangingConfigurations && startedActivities == 0
}
