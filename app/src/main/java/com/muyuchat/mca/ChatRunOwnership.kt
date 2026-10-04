package com.muyuchat.mca

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job

/**
 * The single seam for "which chat run owns the UI right now".
 *
 * Before this existed, `MainViewModel` held three primitives side by side and used them directly:
 * a bare `AtomicLong` epoch that twenty-odd call sites compared by hand
 * (`if (generationRunId != null && generationRunSequence.get() != generationRunId) return`),
 * the [UiGenerationOwnership] object itself, and the `Job` of the run being published. Every
 * delayed Room callback, every terminal stream event, every persistence write open-coded the same
 * staleness test against the raw counter, so "is this callback still mine?" had no single answer and
 * no single place to change.
 *
 * This owner answers that question once -- [isCurrent] -- and keeps the primitives private:
 *
 *  * the epoch counter and the `Job?` handle are fields of this class, not of the ViewModel;
 *  * [UiGenerationOwnership] is an implementation detail (its lock, foreground flag and
 *    pending/active slots are no longer reachable from business code);
 *  * callers get intents -- begin, activate, markPhase, finish, cancelPending, cancelCurrent,
 *    background, foreground, isCurrent, publishJob, awaitPublishedJob -- not a mutable counter.
 *
 * It deliberately does **not** own the engine, the stream buffer or any state: this is identity and
 * cancellation only. `McaInferenceService` keeps the engine lock, the stop target and the lease;
 * [ChatStreamAccumulator] keeps the output buffer. Nothing here duplicates either.
 *
 * Identity is `===` throughout, inherited from [UiGenerationOwnership]: a job that merely compares
 * equal must not be able to retire or complete somebody else's run.
 */
internal class ChatRunOwnership(
    private val sequence: AtomicLong = AtomicLong(0L)
) {

    private val ownership = UiGenerationOwnership(sequence)

    /**
     * The run whose Job has been published to the rest of the ViewModel, if any.
     *
     * This is an [AtomicReference] rather than a plain `var` because the three writers/readers do not
     * share a dispatcher: `publishJob` is called on the main dispatcher when a run starts,
     * `retirePublishedJob` is called from `Job.invokeOnCompletion`, which runs on **whatever thread
     * completes the job**, and `activePublishedJob` / `publishedJobUnless` are read from unrelated
     * call sites (`schedulePersistentPrefixCacheClear`, the recovery wait in `startGeneration`).
     * A plain field there is a data race with no happens-before edge.
     *
     * Retirement is a `compareAndSet` on identity, which is what makes "a finishing older run must
     * not clear the job of a newer run" true by construction rather than by timing: if a newer run
     * has already published, the reference no longer points at the old job and the CAS fails.
     */
    private val publishedJob = AtomicReference<Job?>(null)

    // ── identity ────────────────────────────────────────────────────────────────────────────

    /** The epoch of the run that currently owns the UI. */
    fun currentRunId(): Long = sequence.get()

    /**
     * Whether [runId] still owns the UI. A `null` run id means "unbound" and is always current --
     * that is the behaviour every caller of the old hand-written guard relied on.
     */
    fun isCurrent(runId: Long?): Boolean = runId == null || sequence.get() == runId

    /** The strict form, for the few sites that need "an id was given and it matches". */
    fun matches(runId: Long?): Boolean = runId != null && sequence.get() == runId

    // ── reservation / activation ────────────────────────────────────────────────────────────

    fun reserveStart(): UiGenerationReservation? = ownership.reserveStart()

    /**
     * Reserves the next run and cancels the Job of the run it superseded.
     *
     * The order is load-bearing and must not be inverted: `reserveStart()` clears the previous
     * ownership **and advances the epoch** first, so a cancellation handler that runs as a result of
     * the `cancel()` below already observes the new epoch and cannot claim the replacement request.
     * When the reservation is refused (the app is backgrounded) nothing is cancelled -- the
     * short-circuit is the behaviour, not an optimisation.
     */
    fun reserveStartAndCancelSuperseded(): UiGenerationReservation? =
        ownership.reserveStart()?.also { reservation ->
            (reservation.supersededOwner as? Job)?.cancel()
        }

    /**
     * Cancels the run that currently owns the UI and cancels its Job.
     *
     * Returns the cancellation exactly as the ownership gate produced it -- in particular
     * [UiGenerationCancellation.owner] stays the (now cancelled) owner rather than becoming null,
     * because callers read it after this returns to decide whether the local runtime still needs a
     * stop. Replacing it with null would silently change that decision.
     */
    fun cancelCurrentAndCancelOwner(): UiGenerationCancellation {
        // A cancelled flow still runs finally blocks. The ownership gate advances the epoch before
        // exposing the captured Job, so late cleanup cannot claim a replacement request.
        val cancellation = ownership.cancelCurrent()
        (cancellation.owner as? Job)?.cancel()
        return cancellation
    }

    fun activate(reservation: UiGenerationReservation, owner: Any): Boolean =
        ownership.activate(reservation, owner)

    fun markPhase(runId: Long, owner: Any, phase: UiGenerationRuntimePhase): Boolean =
        ownership.markPhase(runId, owner, phase)

    fun finish(runId: Long, owner: Any) = ownership.finish(runId, owner)

    fun cancelPending(reservation: UiGenerationReservation): Boolean =
        ownership.cancelPending(reservation)

    fun cancelCurrent(): UiGenerationCancellation = ownership.cancelCurrent()

    fun background(): UiGenerationCancellation = ownership.background()

    fun foreground() = ownership.foreground()

    // ── published job handle ────────────────────────────────────────────────────────────────

    /** Publishes [job] as the run the rest of the ViewModel may wait on. */
    fun publishJob(job: Job) {
        publishedJob.set(job)
    }

    /**
     * Retires [job] if it is still the published one; a newer run's job is left alone.
     *
     * Identity CAS, so a completion callback that fires late -- after a newer run has already
     * published -- cannot clear the newer handle.
     */
    fun retirePublishedJob(job: Job) {
        publishedJob.compareAndSet(job, null)
    }

    /** The published job, if it is still active. */
    fun activePublishedJob(): Job? = publishedJob.get()?.takeIf { it.isActive }

    /** The published job unless it is [other] -- used to avoid joining the run we are cancelling. */
    fun publishedJobUnless(other: Job?): Job? = publishedJob.get()?.takeUnless { it === other }
}
