package com.muyuchat.mca.settings

import android.content.Context
import com.muyuchat.feature.agent.workspace.AgentNetworkPreferencePort

/**
 * `SharedPreferences` implementation of [AgentNetworkPreferencePort].
 *
 * Uses its own file rather than joining an unrelated preference document: this is one boolean the
 * user set on purpose, and mixing it into a golden-tested surface would make both harder to reason
 * about. The write is `apply()`, so the switch never blocks the main thread on disk; a read at the
 * next cold start sees the value because `apply` publishes to memory immediately and flushes before
 * the process dies under normal conditions.
 *
 * A corrupt or unreadable preference answers `false` — the closed default — so a broken store can
 * never silently grant network access.
 */
class AgentNetworkPreferenceStore(context: Context) : AgentNetworkPreferencePort {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun read(): Boolean =
        runCatching { preferences.getBoolean(KEY_NETWORK_ENABLED, false) }.getOrDefault(false)

    override fun write(enabled: Boolean) {
        runCatching { preferences.edit().putBoolean(KEY_NETWORK_ENABLED, enabled).apply() }
    }

    private companion object {
        const val PREFERENCES_NAME = "mca_agent_network"
        const val KEY_NETWORK_ENABLED = "networkEnabled"
    }
}
