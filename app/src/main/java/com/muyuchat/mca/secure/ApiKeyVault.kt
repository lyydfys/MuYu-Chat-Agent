package com.muyuchat.mca.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android-Keystore backed vault for API keys (DSHA parity item ⑨).
 *
 * Storage format: one self-describing string, `v1:<base64(ivLength|iv|ciphertext||tag)>`.
 *  - the leading `v1:` makes a legacy plaintext value trivially distinguishable, so migration is a
 *    prefix check instead of a heuristic;
 *  - AES-256-GCM provides confidentiality *and* integrity: a tampered blob fails to decrypt instead
 *    of yielding a different key;
 *  - the IV is stored in the blob (GCM requires a unique IV per encryption), and a one-byte length
 *    prefix keeps the encoding unambiguous if Android ever hands us a different IV size.
 *
 * The key itself never leaves the Android Keystore: [AndroidApiKeyVault] only asks the keystore to
 * encrypt/decrypt. `setUserAuthenticationRequired(false)` means the key is usable while the app
 * runs (the vault is not a biometric gate); `setRandomizedEncryptionRequired(true)` forces a fresh
 * IV per encryption.
 *
 * Everything in the "decision" half of this file is pure JVM code: format encode/decode, the
 * migration plan and the failure policy are unit-tested with a fake cipher, so the security rules
 * are provable without a device.
 */
object ApiKeyVaultFormat {

    const val PREFIX: String = "v1:"

    /** GCM tags are 128 bits; a smaller blob cannot be a valid ciphertext. */
    const val GCM_TAG_BITS: Int = 128

    /** A stored key longer than this is not a key: refuse instead of allocating for it. */
    const val MAX_STORED_CHARS: Int = 16 * 1024

    /**
     * Encodes one encryption result. [iv] is arbitrary length (Android uses 12 bytes); the layout is
     * `[ivLength:1][iv][ciphertext+tag]`.
     */
    fun encode(iv: ByteArray, cipherText: ByteArray): String {
        require(iv.isNotEmpty() && iv.size <= 255) { "vault: IV length must be 1..255" }
        require(cipherText.isNotEmpty()) { "vault: ciphertext must not be empty" }
        val payload = ByteArray(1 + iv.size + cipherText.size)
        payload[0] = iv.size.toByte()
        iv.copyInto(payload, destinationOffset = 1)
        cipherText.copyInto(payload, destinationOffset = 1 + iv.size)
        return PREFIX + Base64.getEncoder().encodeToString(payload)
    }

    /** Classifies one stored slot. Never throws: every malformed shape has its own outcome. */
    fun decode(stored: String?): ApiKeyVaultDecoded {
        if (stored.isNullOrEmpty()) return ApiKeyVaultDecoded.Absent
        if (stored.length > MAX_STORED_CHARS) return ApiKeyVaultDecoded.InvalidLength
        if (!stored.startsWith(PREFIX)) {
            // A value that is a key rather than a vault blob: the migration source.
            return if (stored.isBlank()) ApiKeyVaultDecoded.Absent else ApiKeyVaultDecoded.LegacyPlaintext(stored)
        }
        val body = stored.substring(PREFIX.length)
        if (body.isEmpty()) return ApiKeyVaultDecoded.InvalidPayload
        val bytes = runCatching { Base64.getDecoder().decode(body) }.getOrNull()
            ?: return ApiKeyVaultDecoded.InvalidBase64
        if (bytes.size < 2) return ApiKeyVaultDecoded.InvalidPayload
        val ivLength = bytes[0].toInt() and 0xFF
        if (ivLength == 0 || bytes.size <= 1 + ivLength) return ApiKeyVaultDecoded.InvalidPayload
        if (bytes.size - 1 - ivLength < GCM_TAG_BITS / 8) return ApiKeyVaultDecoded.InvalidPayload
        return ApiKeyVaultDecoded.Encrypted(
            iv = bytes.copyOfRange(1, 1 + ivLength),
            cipherText = bytes.copyOfRange(1 + ivLength, bytes.size)
        )
    }

    /** True when [stored] looks like a vault blob (correct prefix and decodable envelope). */
    fun isEncrypted(stored: String?): Boolean = decode(stored) is ApiKeyVaultDecoded.Encrypted
}

/** Classification of one stored API-key slot. */
sealed interface ApiKeyVaultDecoded {
    data object Absent : ApiKeyVaultDecoded
    data class LegacyPlaintext(val value: String) : ApiKeyVaultDecoded
    data class Encrypted(val iv: ByteArray, val cipherText: ByteArray) : ApiKeyVaultDecoded
    data object InvalidBase64 : ApiKeyVaultDecoded
    data object InvalidPayload : ApiKeyVaultDecoded
    data object InvalidLength : ApiKeyVaultDecoded

    /** True when the slot is already in the encrypted, non-migrated form. */
    val isVaultForm: Boolean get() = this is Encrypted
}

/** Encryption seam: the Android implementation is [AndroidApiKeyVault], tests use a fake. */
interface ApiKeyVaultCipher {
    /** Returns the `v1:` blob, or null when the platform keystore is unavailable. */
    fun encrypt(plaintext: String): String?

    /** Returns the plaintext for a `v1:` blob, or null when it cannot be restored. */
    fun decrypt(stored: String): String?
}

/** Outcome of reading the stored slot, including what (if anything) must be written back. */
enum class ApiKeyVaultStatus {
    /** No stored key yet: the caller creates one. */
    ABSENT,

    /** Read from an existing `v1:` blob. */
    ENCRYPTED,

    /** A legacy plaintext value was read and successfully re-encrypted; [ApiKeyReadPlan.write] holds it. */
    MIGRATED_FROM_PLAINTEXT,

    /** A legacy plaintext value is still usable but the keystore refused to encrypt it. */
    PLAINTEXT_KEPT_VAULT_UNAVAILABLE,

    /**
     * The stored blob could not be decrypted. The stored value is left untouched; the caller gets a
     * fail-closed session key instead of the user's key, and must not clear the slot.
     */
    UNDECRYPTABLE_KEPT
}

/** What the caller should use and persist after one vault read. */
data class ApiKeyReadPlan(
    val status: ApiKeyVaultStatus,
    /** The value callers may use now; null only when nothing usable exists yet. */
    val value: String?,
    /** When non-null, this `v1:` blob must be written over the plaintext slot. */
    val write: String?,
    /** Human-readable, log-safe explanation; never contains key material. */
    val detail: String?
) {
    val usable: Boolean get() = !value.isNullOrBlank()
}

/**
 * Pure migration/read policy (DSHA rule: a decryption failure must never delete the user's value).
 *
 *  - absent → [ApiKeyVaultStatus.ABSENT]
 *  - `v1:` + decrypt ok → ENCRYPTED
 *  - `v1:` + decrypt fails → UNDECRYPTABLE_KEPT with **no write**: the ciphertext stays in place so a
 *    later working keystore can still recover it;
 *  - plaintext → try to encrypt: success → MIGRATED_FROM_PLAINTEXT + the blob to write;
 *    keystore unavailable → PLAINTEXT_KEPT_VAULT_UNAVAILABLE, value still works, nothing cleared.
 */
fun planApiKeyRead(stored: String?, cipher: ApiKeyVaultCipher): ApiKeyReadPlan =
    when (val decoded = ApiKeyVaultFormat.decode(stored)) {
        ApiKeyVaultDecoded.Absent -> ApiKeyReadPlan(
            status = ApiKeyVaultStatus.ABSENT,
            value = null,
            write = null,
            detail = null
        )

        is ApiKeyVaultDecoded.LegacyPlaintext -> {
            val encrypted = runCatching { cipher.encrypt(decoded.value) }.getOrNull()
            if (encrypted == null) {
                ApiKeyReadPlan(
                    status = ApiKeyVaultStatus.PLAINTEXT_KEPT_VAULT_UNAVAILABLE,
                    value = decoded.value,
                    write = null,
                    detail = "系统密钥库不可用；旧明文密钥仍可使用，未做任何改写。"
                )
            } else {
                ApiKeyReadPlan(
                    status = ApiKeyVaultStatus.MIGRATED_FROM_PLAINTEXT,
                    value = decoded.value,
                    write = encrypted,
                    detail = "旧明文密钥已加密，明文槽位随后被覆盖。"
                )
            }
        }

        is ApiKeyVaultDecoded.Encrypted -> {
            val plaintext = runCatching { cipher.decrypt(stored!!) }.getOrNull()
            if (plaintext.isNullOrBlank()) {
                ApiKeyReadPlan(
                    status = ApiKeyVaultStatus.UNDECRYPTABLE_KEPT,
                    value = null,
                    write = null,
                    detail = "密文无法解密（密钥库丢失或数据损坏）；已保留原值，未清空、未改写。"
                )
            } else {
                ApiKeyReadPlan(
                    status = ApiKeyVaultStatus.ENCRYPTED,
                    value = plaintext,
                    write = null,
                    detail = null
                )
            }
        }

        ApiKeyVaultDecoded.InvalidBase64,
        ApiKeyVaultDecoded.InvalidPayload,
        ApiKeyVaultDecoded.InvalidLength -> ApiKeyReadPlan(
            status = ApiKeyVaultStatus.UNDECRYPTABLE_KEPT,
            value = null,
            write = null,
            detail = "密钥槽位格式损坏；已保留原值，未清空、未改写。"
        )
    }

/**
 * Creation policy for a freshly generated Local API key.
 *
 * Prefer the vault; when the keystore is unavailable the plaintext fallback is kept so an existing
 * install's Local API keeps working (this is a generated device-local key, not a user secret) — but
 * the status says so, and the next successful read migrates it.
 */
fun planApiKeyCreate(newKey: String, cipher: ApiKeyVaultCipher): ApiKeyReadPlan {
    require(newKey.isNotBlank()) { "vault: a new API key must not be blank" }
    val encrypted = runCatching { cipher.encrypt(newKey) }.getOrNull()
    return if (encrypted == null) {
        ApiKeyReadPlan(
            status = ApiKeyVaultStatus.PLAINTEXT_KEPT_VAULT_UNAVAILABLE,
            value = newKey,
            write = null,
            detail = "系统密钥库不可用；新密钥以明文保存（下次可加密时自动迁移）。"
        )
    } else {
        ApiKeyReadPlan(
            status = ApiKeyVaultStatus.MIGRATED_FROM_PLAINTEXT,
            value = newKey,
            write = encrypted,
            detail = null
        )
    }
}

/**
 * Thin Android implementation of [ApiKeyVaultCipher].
 *
 * This class is intentionally the only place that touches `AndroidKeyStore`. A failure is reported
 * as null (never an exception, never a plaintext fallback written behind the caller's back).
 */
class AndroidApiKeyVault private constructor(
    private val keystoreAlias: String,
    private val provider: String = ANDROID_KEYSTORE
) : ApiKeyVaultCipher {

    private val lock = Any()

    override fun encrypt(plaintext: String): String? = runCatching {
        synchronized(lock) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val cipherText = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            ApiKeyVaultFormat.encode(cipher.iv, cipherText)
        }
    }.getOrNull()

    override fun decrypt(stored: String): String? {
        val decoded = ApiKeyVaultFormat.decode(stored)
        if (decoded !is ApiKeyVaultDecoded.Encrypted) return null
        return runCatching {
            synchronized(lock) {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(ApiKeyVaultFormat.GCM_TAG_BITS, decoded.iv)
                )
                cipher.doFinal(decoded.cipherText).toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    /** Creates/returns the AES-256-GCM key in the Android Keystore. */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(provider).apply { load(null) }
        (keyStore.getEntry(keystoreAlias, null) as? KeyStore.SecretKeyEntry)
            ?.secretKey
            ?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, provider)
        generator.init(
            KeyGenParameterSpec.Builder(
                keystoreAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        const val ALIAS: String = "mca_api_key_vault"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** One vault per process; the keystore key is process-independent anyway. */
        private val instance: AndroidApiKeyVault by lazy { AndroidApiKeyVault(ALIAS) }

        fun forApp(@Suppress("UNUSED_PARAMETER") context: Context): AndroidApiKeyVault = instance
    }
}

/**
 * Fail-closed stand-in for a Local API key that cannot be decrypted.
 *
 * Returning `""` would make the loopback server consider every request authorised, so an unusable
 * but unguessable value is used instead: the server keeps requiring a token, and the owner can still
 * read/copy it from the Local API page if they choose to keep using this session.
 */
object ApiKeyVaultUnavailable {
    val sessionKey: String = "vault-unavailable-" + UUID.randomUUID().toString().replace("-", "").take(24)
}

/**
 * Process-visible, value-free projection of the last vault read.
 *
 * Only the status and a log-safe explanation are kept (never key material), so diagnostics and a
 * future settings row can explain `UNDECRYPTABLE_KEPT` without reading the credential again.
 */
object ApiKeyVaultState {
    @Volatile
    var lastStatus: ApiKeyVaultStatus = ApiKeyVaultStatus.ABSENT
        private set

    @Volatile
    var lastDetail: String? = null
        private set

    internal fun record(plan: ApiKeyReadPlan) {
        lastStatus = plan.status
        lastDetail = plan.detail
    }
}
