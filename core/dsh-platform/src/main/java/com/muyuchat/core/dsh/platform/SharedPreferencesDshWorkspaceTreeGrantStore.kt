package com.muyuchat.core.dsh.platform

import android.content.Context
import com.muyuchat.core.dsh.contract.capability.DshWorkspaceTreeFlags

/**
 * App-private durable store of the one workspace-tree grant.
 *
 * It holds the SAF URI string, the persisted modes, the grant time and the explicit release
 * marker. It is intentionally not the DSH home, not a WebView preference and not part of any DSH
 * payload: a DSH-side write cannot fabricate a grant, and clearing DSH data cannot silently keep
 * one alive.
 */
class SharedPreferencesDshWorkspaceTreeGrantStore(context: Context) : DshWorkspaceTreeGrantStore {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Never throws: malformed or partial data is treated as "no grant" (fail closed). */
    override fun read(): DshWorkspaceTreeGrantRecord? {
        val treeUri = runCatching { preferences.getString(KEY_TREE_URI, null) }.getOrNull()
            ?: return null
        val grantedAtMillis = runCatching { preferences.getLong(KEY_GRANTED_AT_MILLIS, -1L) }
            .getOrDefault(-1L)
        if (grantedAtMillis < 0L) return null
        val revokedAtMillis = runCatching { preferences.getLong(KEY_REVOKED_AT_MILLIS, NOT_REVOKED) }
            .getOrDefault(NOT_REVOKED)
            .takeIf { it != NOT_REVOKED }
        val flags = DshWorkspaceTreeFlags(
            read = runCatching { preferences.getBoolean(KEY_READ, false) }.getOrDefault(false),
            write = runCatching { preferences.getBoolean(KEY_WRITE, false) }.getOrDefault(false)
        )
        return runCatching {
            DshWorkspaceTreeGrantRecord(
                treeUri = treeUri,
                flags = flags,
                grantedAtMillis = grantedAtMillis,
                revokedAtMillis = revokedAtMillis
            )
        }.getOrNull()
    }

    /**
     * Synchronous commit: the caller only reports GRANTED/REVOKED after this returns, so a
     * process death cannot leave a platform permission without its durable record.
     */
    override fun write(record: DshWorkspaceTreeGrantRecord) {
        preferences.edit()
            .putString(KEY_TREE_URI, record.treeUri)
            .putBoolean(KEY_READ, record.flags.read)
            .putBoolean(KEY_WRITE, record.flags.write)
            .putLong(KEY_GRANTED_AT_MILLIS, record.grantedAtMillis)
            .putLong(KEY_REVOKED_AT_MILLIS, record.revokedAtMillis ?: NOT_REVOKED)
            .commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "dsh_workspace_tree_grant"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_READ = "read"
        const val KEY_WRITE = "write"
        const val KEY_GRANTED_AT_MILLIS = "granted_at_millis"
        const val KEY_REVOKED_AT_MILLIS = "revoked_at_millis"
        const val NOT_REVOKED = -1L
    }
}
