package com.muyuchat.mca

/**
 * How a stored credential should be written.
 *
 * Cloud chat and web search both keep an API key in [android.content.SharedPreferences].
 * The key is encrypted with an Android Keystore AES/GCM key; the ciphertext and IV are
 * stored, and an older plaintext slot exists only so that configurations written before
 * encryption was introduced keep working.
 *
 * The plaintext slot is a migration source, never a fallback target. Writing a key in
 * clear text because encryption failed silently downgrades the user's credential, so the
 * decision is expressed here as a pure value that can be tested without Android and
 * reused by every provider that stores a key.
 */
internal sealed interface CredentialWrite {
    /** The credential is blank: every stored form, including the plaintext slot, is removed. */
    data object Clear : CredentialWrite

    /** Encryption succeeded and produced a usable pair that may be committed. */
    data class Store(val cipherText: String, val iv: String) : CredentialWrite

    /**
     * Encryption is unavailable or produced an unusable result.
     *
     * Nothing may be written. In particular the plaintext slot must not be created or
     * refreshed, and the caller must not report a successful save.
     */
    data object Unavailable : CredentialWrite
}

/**
 * Decide how to persist [apiKey] given an [encrypt] function.
 *
 * A blank [apiKey] clears every slot. Otherwise encryption is attempted once; a thrown
 * exception, a null result, or a blank ciphertext/IV pair all resolve to
 * [CredentialWrite.Unavailable] rather than a silent no-op, so a failed save is visible
 * to the caller instead of being mistaken for success.
 */
internal fun planCredentialWrite(
    apiKey: String,
    encrypt: (String) -> Pair<String, String>?
): CredentialWrite {
    if (apiKey.isBlank()) return CredentialWrite.Clear
    val encrypted = runCatching { encrypt(apiKey) }.getOrNull()
        ?: return CredentialWrite.Unavailable
    val cipherText = encrypted.first
    val iv = encrypted.second
    if (cipherText.isBlank() || iv.isBlank()) return CredentialWrite.Unavailable
    return CredentialWrite.Store(cipherText, iv)
}

/**
 * Outcome of persisting a credential.
 *
 * A boolean is not enough: the user-facing message differs between "the platform cannot
 * encrypt at all" and "the value was written but did not read back", and both must be
 * distinguishable from success. See plan section 11.3 item 4.
 *
 * Public because the credential stores expose it from their public `save` entry points.
 */
sealed interface CredentialSaveResult {
    /** The key is stored encrypted and was verified to decrypt back to the same value. */
    data object Stored : CredentialSaveResult

    /** The key was blank; every stored form, including the plaintext slot, is gone. */
    data object Cleared : CredentialSaveResult

    /** The platform keystore is unavailable or unusable. Nothing was written. */
    data object EncryptionUnavailable : CredentialSaveResult

    /**
     * The encrypted pair was written but did not decrypt back to the submitted value.
     *
     * Any pre-existing plaintext credential is deliberately left in place so the user
     * keeps a working configuration instead of losing it to a partial write.
     */
    data object VerificationFailed : CredentialSaveResult

    /** True only when the credential is durably usable. */
    val isStored: Boolean get() = this == Stored || this == Cleared
}
