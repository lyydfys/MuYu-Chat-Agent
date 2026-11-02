package com.muyuchat.mca.account

import android.content.Context
import com.muyuchat.core.dsh.runtime.auth.DshAccountAttemptJournal
import com.muyuchat.core.dsh.runtime.auth.DshAccountAttemptMarker

/**
 * Durable attempt marker for the "app process died during a sign-in" case.
 *
 * Only an opaque host attempt id and a timestamp are stored: no authorize URL (it carries
 * `state`/`code_challenge`), no authorization code, no cookie and no token. The official
 * credential store stays the only persistence owner of the grant.
 *
 * Writes use a synchronous commit because the point of this journal is to survive an abrupt
 * process kill; it is written a handful of times per attempt, never on a hot path.
 */
internal class SharedPreferencesDshAccountAttemptJournal(
    context: Context
) : DshAccountAttemptJournal {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun load(): DshAccountAttemptMarker? {
        val attemptId = preferences.getString(KEY_ATTEMPT_ID, null)?.trim().orEmpty()
        if (attemptId.isEmpty()) return null
        return runCatching {
            DshAccountAttemptMarker(
                attemptId = attemptId,
                active = preferences.getBoolean(KEY_ACTIVE, false),
                updatedAtEpochMillis = preferences.getLong(KEY_UPDATED_AT, 0L),
                credentialFingerprintAtStart = preferences
                    .getString(KEY_CREDENTIAL_FINGERPRINT_AT_START, null)
            )
        }.getOrNull()
    }

    override fun save(marker: DshAccountAttemptMarker) {
        runCatching {
            preferences.edit()
                .putString(KEY_ATTEMPT_ID, marker.attemptId)
                .putBoolean(KEY_ACTIVE, marker.active)
                .putLong(KEY_UPDATED_AT, marker.updatedAtEpochMillis)
                .putString(KEY_CREDENTIAL_FINGERPRINT_AT_START, marker.credentialFingerprintAtStart)
                .commit()
        }
    }

    override fun clear() {
        runCatching { preferences.edit().clear().commit() }
    }

    private companion object {
        const val PREFERENCES_NAME = "mca.dsh.account.attempt"
        const val KEY_ATTEMPT_ID = "attemptId"
        const val KEY_ACTIVE = "active"
        const val KEY_UPDATED_AT = "updatedAt"
        const val KEY_CREDENTIAL_FINGERPRINT_AT_START = "credentialFingerprintAtStart"
    }
}
