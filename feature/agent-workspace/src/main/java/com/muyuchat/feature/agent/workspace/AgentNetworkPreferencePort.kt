package com.muyuchat.feature.agent.workspace

/**
 * Durable home for the Agent page's "允许联网" switch.
 *
 * ## Why this is a port and not `SavedStateHandle`
 *
 * The switch used to live only in `SavedStateHandle`, which survives a configuration change and an
 * in-process recreation but **not a cold start**. A user who enabled network access, closed the app
 * and reopened it found the switch off again and the page reporting「当前为离线模式，所选模型需要
 * 联网」. That happened on every restart and is the reason this port exists: a network grant the user
 * gave on purpose must outlive the process, and `SavedStateHandle` is the wrong store for a
 * preference (it is the right store for a half-typed draft, which is what it still holds here).
 *
 * ## Contract
 *
 * - The value is read once at construction, so the page's first frame already reflects the stored
 * preference instead of flashing the default.
 * - [write] is best-effort and must never throw: a failed preference write must not break the
 *   switch. A caller that cares can re-read [read].
 * - [Unavailable] answers `false`, which is the same meaning as "never enabled": closed until the
 *   user opens it. It is used by previews and by hosts that have not wired a store yet.
 */
interface AgentNetworkPreferencePort {
    /** The stored preference, or `false` when nothing was ever stored. */
    fun read(): Boolean

    /** Persists [enabled]. Never throws. */
    fun write(enabled: Boolean)

    /** No durable storage: every read answers the closed default. */
    object Unavailable : AgentNetworkPreferencePort {
        override fun read(): Boolean = false
        override fun write(enabled: Boolean) = Unit
    }
}
