package com.muyuchat.feature.video.workspace

/**
 * Durable draft storage. `SavedStateHandle` only survives states the system restores for us; a
 * cold start (fresh task, reinstall, force-stop) falls back to the template and the user loses the
 * prompt and frame count they typed. The composition root backs this with a private file.
 */
interface VideoDraftStore {
    /** Last persisted draft, or null when none was ever written or it is unreadable. */
    fun load(): String?

    /** Persist the encoded draft; must not throw (a failed write only costs durability). */
    fun save(encoded: String)
}
