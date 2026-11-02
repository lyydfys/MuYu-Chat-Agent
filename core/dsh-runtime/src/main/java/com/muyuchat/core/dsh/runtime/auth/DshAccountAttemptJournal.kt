package com.muyuchat.core.dsh.runtime.auth

/**
 * What one in-flight account attempt looked like when it was last written to disk.
 *
 * Deliberately minimal and secret-free: an opaque host attempt id plus its phase class. The
 * authorize URL (which carries `state`/`code_challenge`), the callback code and every token stay
 * inside the official module and are never journaled here.
 */
data class DshAccountAttemptMarker(
    val attemptId: String,
    /** True while the host phase was active, i.e. the outcome was still open. */
    val active: Boolean,
    val updatedAtEpochMillis: Long,
    /**
     * Non-secret fingerprint (size:mtime) of the official credential store when this attempt
     * started, or null when no store existed / it could not be read. It lets the next process
     * resolve an UNKNOWN attempt to AUTHENTICATED only when the store actually changed; it is not
     * a credential and never contains file contents.
     */
    val credentialFingerprintAtStart: String? = null
) {
    init {
        require(attemptId.isNotEmpty() && attemptId.length <= MAX_ATTEMPT_ID_CHARS) {
            "account attempt id must be a bounded non-empty host token"
        }
        require(attemptId.none { it.code < 0x20 || it.code in 0x7F..0x9F }) {
            "account attempt id must not carry control characters"
        }
        require(updatedAtEpochMillis >= 0L) { "marker timestamp must be non-negative" }
        require(
            credentialFingerprintAtStart == null || (
                credentialFingerprintAtStart.length <= MAX_FINGERPRINT_CHARS &&
                    credentialFingerprintAtStart.none { it.code < 0x20 || it.code in 0x7F..0x9F }
                )
        ) { "credential fingerprint must be a bounded control-character-free token" }
    }

    override fun toString(): String =
        "DshAccountAttemptMarker(active=$active, updatedAt=$updatedAtEpochMillis)"

    private companion object {
        const val MAX_ATTEMPT_ID_CHARS = 64
        const val MAX_FINGERPRINT_CHARS = 96
    }
}

/**
 * Durable, bounded journal of the one host-side account attempt.
 *
 * Purpose: an app process can be killed while an attempt is in flight. The official attempt may
 * even still be alive in the separate `:dsh` node process, so the host cannot prove whether the
 * grant was committed. The journal lets the next process restore the attempt as
 * `OUTCOME_UNKNOWN` instead of silently starting over (a second grant) or claiming a success.
 *
 * The journal is *not* a credential store: [com.muyuchat.core.dsh.contract.auth.DshCredentialPersistenceOwner]
 * stays `OFFICIAL_DSH`. Implementations must therefore be cheap, synchronous and loss-tolerant;
 * `load` returning null simply means "no attempt to restore".
 */
interface DshAccountAttemptJournal {

    /** The last written marker, or null when nothing is pending. */
    fun load(): DshAccountAttemptMarker?

    /** Persists the marker of an in-flight attempt. */
    fun save(marker: DshAccountAttemptMarker)

    /** Drops the marker once the attempt reached a typed terminal state. */
    fun clear()
}
