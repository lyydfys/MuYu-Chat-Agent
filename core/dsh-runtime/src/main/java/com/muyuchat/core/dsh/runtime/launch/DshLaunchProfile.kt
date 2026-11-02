package com.muyuchat.core.dsh.runtime.launch

import com.muyuchat.core.dsh.runtime.config.DshRoots
import java.io.File

/**
 * Typed outcome of a profile's home-seeding step.
 *
 * Seeding is profile policy (the production profile binds the MCA model gateway or the official
 * account provider; the isolated trial profile seeds the verified official template only), so the
 * owner supplies it and the launcher only consumes the result.
 */
internal sealed interface DshSeedAttempt {
    data object Seeded : DshSeedAttempt

    /** [message] is path-free (it can reach a snapshot); [diagnostics] is bounded evidence. */
    data class Refused(val message: String, val diagnostics: String? = null) : DshSeedAttempt
}

/**
 * One host launch description.
 *
 * The launch *sequence* (payload verification, Node exec strategy, environment, process start,
 * authenticated entry, readiness gate) is identical for both generations of the single owner, so
 * it lives in [DshHostLauncher]. What differs is exactly this value:
 *
 * - the production profile publishes READY, verifies/installs the packaged payload, prepares the
 *   payload command links and arms the exit watcher;
 * - the isolated trial profile (D24) is **non-publishing**: it runs the already verified installed
 *   payload tree, keeps its own home/workspace/tmp, binds `--port 0`, never rewrites the payload's
 *   command links, never records the READY entry/binding and never arms the exit watcher.
 *
 * [publishReady] and [watchExit] are read by the owner, not by the launcher: they decide whether
 * the owner may call `markReady`, store the in-memory entry and start the exit watcher.
 */
internal data class DshLaunchProfile(
    /** Log prefix only ("dsh: " / "dsh: trial: "). Never carries a path or a secret. */
    val logPrefix: String,
    /** Human noun used in failure messages ("DSH host" / "isolated trial host"). */
    val hostNoun: String,
    val roots: DshRoots,
    val kernelEntry: File,
    val bindHost: String,
    val port: Int,
    val publishReady: Boolean,
    /** Install/verify the packaged payload. False for a trial: it must not install a second copy. */
    val verifyPayload: Boolean,
    /**
     * Install the packaged payload even though this generation executes a committed closure.
     * The install only prepares the source for the *next* upgrade; a failure must never block the
     * already-verified closure from starting, so the launcher treats it as advisory here.
     */
    val installPackagedPayload: Boolean = verifyPayload,
    /** Rewrite the payload command links. False for a trial: the verified payload is read-only. */
    val prepareCommandLinks: Boolean,
    val watchExit: Boolean
)
