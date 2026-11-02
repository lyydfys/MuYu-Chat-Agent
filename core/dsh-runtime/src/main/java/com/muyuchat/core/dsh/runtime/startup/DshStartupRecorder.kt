package com.muyuchat.core.dsh.runtime.startup

/**
 * Single-writer recorder for [DshStartupProgress].
 *
 * One instance lives in [com.muyuchat.core.dsh.runtime.DshRuntimeOwner] for the lifetime of the
 * host; it is an observation timeline, not a second lifecycle state machine. Rules:
 *
 * - only the current epoch is accepted, so a stale generation can never write into a new timeline;
 * - stages advance forward only (declaration order); duplicates and backwards stages are ignored;
 * - [DshStartupStage.FIRST_FRAME_RENDERED] additionally requires
 *   [DshStartupStage.PLUGINS_LOADED] first, so a page can never be observed as rendered before
 *   its client plugins loaded.
 *
 * The class is platform-free and clock-injectable so every rule is JVM-testable.
 */
internal class DshStartupRecorder(
    private val clock: () -> Long = System::currentTimeMillis
) {    private val lock = Any()
    private var state = DshStartupProgress()

    /** Starts a new generation timeline, clearing the previous one. */
    fun begin(epoch: Long): DshStartupProgress {
        require(epoch > 0L) { "dsh: startup epoch must be positive, was $epoch" }
        return synchronized(lock) {
            state = DshStartupProgress(epoch = epoch, changedAtMillis = clock())
            state
        }
    }

    /**
     * Records [stage] for [epoch]. Returns true only when the timeline advanced; a stale epoch, a
     * duplicate/backwards stage or a first frame without loaded plugins returns false.
     */
    fun record(epoch: Long, stage: DshStartupStage): Boolean = synchronized(lock) {
        val current = state
        val currentStage = current.stage
        val accepted = epoch > 0L && epoch == current.epoch &&
            (currentStage == null || stage.ordinal > currentStage.ordinal) &&
            (stage != DshStartupStage.FIRST_FRAME_RENDERED ||
                current.hasReached(DshStartupStage.PLUGINS_LOADED))
        if (!accepted) {
            false
        } else {
            val at = clock()
            state = current.copy(
                stage = stage,
                changedAtMillis = at,
                events = current.events + DshStartupEvent(epoch = epoch, stage = stage, atMillis = at)
            )
            true
        }
    }

    /** The current immutable timeline; never null so observers can read it before a start. */
    fun progress(): DshStartupProgress = synchronized(lock) { state }
}
