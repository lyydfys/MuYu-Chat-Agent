package com.muyuchat.mca.composition

import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptPhase
import com.muyuchat.core.dsh.contract.auth.DshAuthAttemptView
import com.muyuchat.core.dsh.contract.console.DshConsoleAccountProjection
import com.muyuchat.core.dsh.contract.console.DshConsoleAccountReason
import com.muyuchat.core.dsh.contract.console.DshConsoleAccountState

/**
 * Narrow app-composition adapter between the DeepSeek account lane and the D20 console.
 *
 * Source of truth: `DshAccountLoginOwner.attemptView()` (a URL-free, credential-free snapshot of
 * the host-side attempt). The owner is the only adapter that correlates the official attempt, so
 * this class owns no attempt state and never starts, retries, cancels or "completes" a login. It
 * only:
 *
 * - maps the official attempt phase to the bounded console state/reason vocabulary
 *   ([DshConsoleAccountProjection.from]);
 * - adds the one fact the attempt view does not expose: how long this app process has observed the
 *   live attempt. The official attempt start is owned by the official module, so this is an
 *   observed age (a lower bound), bounded by
 *   [DshConsoleAccountProjection.MAX_ATTEMPT_AGE_MILLIS], never a claimed start time;
 * - keeps the last live age of a terminal attempt of the same id, and drops everything when the
 *   attempt id changes or no attempt is active.
 *
 * It never reads a credential, the authorize URL, a callback code, a PKCE verifier or a cookie, and
 * it has no success state: an official success is projected as UNKNOWN because only the official
 * credential store can confirm it.
 */
internal class DshConsoleAccountProjectionAdapter(
    private val attemptView: () -> DshAuthAttemptView?,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val lock = Any()
    private var observedAttemptId: String? = null
    private var observedAtMillis: Long = 0L
    private var lastLiveAgeMillis: Long = 0L

    /** Bounded, credential-free projection for one console snapshot. Cheap and synchronous. */
    fun project(): DshConsoleAccountProjection {
        // Read the owner before taking this adapter's lock: the owner has its own lock and this
        // adapter must never be the reason a console refresh contends with the account lane.
        val view = attemptView()
        return synchronized(lock) { projectLocked(view) }
    }

    private fun projectLocked(view: DshAuthAttemptView?): DshConsoleAccountProjection {
        if (view == null) {
            return DshConsoleAccountProjection(
                state = DshConsoleAccountState.UNKNOWN,
                attemptAgeMillis = 0L,
                reason = DshConsoleAccountReason.SOURCE_UNAVAILABLE
            )
        }
        if (view.phase == DshAuthAttemptPhase.IDLE) {
            reset()
            return DshConsoleAccountProjection.from(view, attemptAgeMillis = 0L)
        }
        val now = clock()
        if (view.phase.isLiveAttempt()) {
            if (view.attemptId != observedAttemptId) {
                observedAttemptId = view.attemptId
                observedAtMillis = now
                lastLiveAgeMillis = 0L
            }
            val age = (now - observedAtMillis)
                .coerceIn(0L, DshConsoleAccountProjection.MAX_ATTEMPT_AGE_MILLIS)
            lastLiveAgeMillis = age
            return DshConsoleAccountProjection.from(view, attemptAgeMillis = age)
        }
        // Terminal state: only an attempt that was actually observed live carries an age. A
        // restored `OUTCOME_UNKNOWN` attempt (process killed before this console existed) has no
        // observed age and must not borrow one from the journal or from the deadline.
        // A terminal phase may clear the attempt id (the owner has no attempt any more). Keep the
        // last age only when this process actually observed that attempt live; a restored unknown
        // attempt (never observed) still gets 0.
        val sameObservedAttempt = observedAttemptId != null &&
            (view.attemptId.isEmpty() || view.attemptId == observedAttemptId)
        val age = if (sameObservedAttempt) lastLiveAgeMillis else 0L
        return DshConsoleAccountProjection.from(view, attemptAgeMillis = age)
    }

    private fun reset() {
        observedAttemptId = null
        observedAtMillis = 0L
        lastLiveAgeMillis = 0L
    }
}

/**
 * The attempt phases that mean "the host is still working on this attempt". Mirrors
 * `DshAccountSignInMachine`'s active host phases; kept local so the app adapter does not depend on
 * the runtime module's private host phase enum.
 */
private fun DshAuthAttemptPhase.isLiveAttempt(): Boolean = when (this) {
    DshAuthAttemptPhase.INITIALIZING,
    DshAuthAttemptPhase.WAITING_BROWSER,
    DshAuthAttemptPhase.EXCHANGING,
    DshAuthAttemptPhase.COMMITTING -> true

    DshAuthAttemptPhase.IDLE,
    DshAuthAttemptPhase.SUCCEEDED,
    DshAuthAttemptPhase.CANCELLED,
    DshAuthAttemptPhase.FAILED,
    DshAuthAttemptPhase.EXPIRED,
    DshAuthAttemptPhase.OUTCOME_UNKNOWN -> false
}
