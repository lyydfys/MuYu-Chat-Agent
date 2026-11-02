package com.muyuchat.feature.dsh.workspace

import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import com.muyuchat.core.dsh.contract.DshWebEntry

/** What the ViewModel must ask the frozen [com.muyuchat.core.dsh.contract.DshRuntimeClient] to do. */
enum class DshStartAction { ATTACH, RESTART }

/**
 * Pure start policy: a failed host is restarted with a new generation, anything else is attached
 * with `ensureStarted()` (which also waits for a host that is already `STARTING`).
 */
object DshStartPolicy {
    fun actionFor(snapshot: DshRuntimeSnapshot): DshStartAction =
        if (snapshot.phase == DshRuntimePhase.FAILED) DshStartAction.RESTART else DshStartAction.ATTACH
}

/**
 * The one URL a freshly composed WebView must load.
 *
 * [TokenEntry] is emitted only when the client just handed out the authenticated entry for the
 * current epoch. It is loaded once, immediately, and never persisted. Every other case resumes the
 * official page with its HttpOnly first-party cookie; when that cookie is gone the page answers
 * 401 and the ViewModel restarts the runtime for a fresh entry.
 */
sealed interface DshWebLoadPlan {
    val epoch: Long

    data class TokenEntry(
        override val epoch: Long,
        /** Contains `?token=...`; heap-only, never logged or persisted. */
        val url: String,
        val origin: DshOrigin,
        /** Same-origin path to apply after the cookie exchange settles, if any. */
        val restorePath: String?
    ) : DshWebLoadPlan

    data class CookieResume(
        override val epoch: Long,
        val url: String,
        val origin: DshOrigin
    ) : DshWebLoadPlan

    data class Failure(val projection: DshFailureProjection) : DshWebLoadPlan {
        override val epoch: Long get() = -1L
    }
}

object DshLoadPlanner {

    /**
     * Decides what to load for [snapshot].
     *
     * The entry is accepted only when it belongs to the current epoch and points at the exact
     * current loopback origin; an entry for another generation is stale and is ignored instead of
     * being loaded into a different port. A saved path is only ever used for the same origin and
     * is sanitized by [DshNavigationPolicy].
     */
    fun plan(
        snapshot: DshRuntimeSnapshot,
        entry: DshWebEntry?,
        savedPath: String?
    ): DshWebLoadPlan {
        val origin = DshOrigin.parse(snapshot.origin)
        if (snapshot.phase != DshRuntimePhase.READY || origin == null) {
            return DshWebLoadPlan.Failure(DshFailureProjectionMapper.project(snapshot.failure))
        }
        val path = DshNavigationPolicy.sanitizeRestorePath(savedPath)
        val currentEntry = entry?.takeIf { candidate ->
            candidate.epoch == snapshot.epoch && origin.matches(candidate.authenticatedUrl)
        }
        return if (currentEntry != null) {
            DshWebLoadPlan.TokenEntry(
                epoch = snapshot.epoch,
                url = currentEntry.authenticatedUrl,
                origin = origin,
                restorePath = path
            )
        } else {
            DshWebLoadPlan.CookieResume(
                epoch = snapshot.epoch,
                url = DshNavigationPolicy.restoreUrl(origin, path) ?: origin.pathUrl("/"),
                origin = origin
            )
        }
    }
}
