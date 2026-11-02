package com.muyuchat.core.dsh.runtime.trial

import com.muyuchat.core.dsh.runtime.config.DshRoots
import com.muyuchat.core.dsh.runtime.process.DshNodeHost
import java.io.File

/**
 * The in-memory marker of the one isolated trial generation that currently occupies the host slot.
 *
 * It is deliberately *not* a second lifecycle state machine: the owner's
 * [com.muyuchat.core.dsh.runtime.state.DshRuntimeStateMachine] stays the single authority for the
 * production projection (the trial publishes `STARTING` there so production is refused, and never
 * publishes `READY`). This value only answers "is a trial generation alive, and what did it prove
 * at launch?".
 */
internal data class DshTrialSession(
    val generation: Long,
    val host: DshNodeHost,
    val trialRoot: File,
    val roots: DshRoots,
    val candidateRoot: File,
    val candidateFingerprint: String,
    /** The readiness gate proved the official authenticated surface for this generation. */
    val hostReady: Boolean,
    /** A write/read/delete probe succeeded in the isolated TMPDIR before the launch. */
    val tmpWritable: Boolean,
    /** Bounded, path-free description of the session data-half copy for this trial. */
    val dataSeedDetail: String,
    /**
     * Committed session logs the production home offered when this trial was seeded. `0` marks a
     * new-user profile: there is no container to verify, so the trial proves an initialized empty
     * official session store instead (see `DshTrialSessionReader.probe`).
     */
    val sourceSessions: Int
)
