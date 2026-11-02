package com.muyuchat.core.dsh.contract.capability

/**
 * Narrow admission seam of one terminal session, owned by the app composition.
 *
 * A PTY session outlives the capability request that opened it, so a maintenance transaction must
 * not start while one is live. The app adapts its shared maintenance gate here; the terminal
 * module never sees lease ids, kinds or the gate, and holds no copy of that state.
 *
 * [acquire] is called once per session before the child process is spawned. A null result means an
 * exclusive maintenance write owns the data, and the session is refused instead of opened.
 */
fun interface DshTerminalSessionAdmission {
    /** Returns a one-shot release handle, or null when maintenance currently owns the data. */
    fun acquire(ownerKey: String, sessionId: String): (() -> Unit)?
}
