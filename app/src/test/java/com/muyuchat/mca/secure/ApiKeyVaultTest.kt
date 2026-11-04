package com.muyuchat.mca.secure

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the API-key vault format and the migration/failure policy without Android.
 *
 * The DSHA rule under test: a value that cannot be decrypted is **kept exactly as stored** — the
 * user must never lose a credential because the keystore key disappeared. The Android keystore
 * wrapper is a thin class that is not exercised here; a fake cipher stands in for it.
 */
class ApiKeyVaultTest {

    private class FakeCipher(
        var encryptionAvailable: Boolean = true,
        var decryptionAvailable: Boolean = true
    ) : ApiKeyVaultCipher {
        var encryptCalls = 0
        var decryptCalls = 0

        override fun encrypt(plaintext: String): String? {
            encryptCalls += 1
            if (!encryptionAvailable) return null
            return ApiKeyVaultFormat.encode(
                iv = ByteArray(12) { (it + 1).toByte() },
                cipherText = plaintext.toByteArray(Charsets.UTF_8) + ByteArray(16) { 7 }
            )
        }

        override fun decrypt(stored: String): String? {
            decryptCalls += 1
            if (!decryptionAvailable) return null
            val decoded = ApiKeyVaultFormat.decode(stored) as? ApiKeyVaultDecoded.Encrypted ?: return null
            val plain = decoded.cipherText.copyOf(decoded.cipherText.size - 16)
            return plain.toString(Charsets.UTF_8)
        }
    }

    @Test
    fun `format round trips iv and ciphertext`() {
        val iv = ByteArray(12) { (it * 3).toByte() }
        val cipherText = ByteArray(37) { (it * 5).toByte() }

        val encoded = ApiKeyVaultFormat.encode(iv, cipherText)
        assertTrue(encoded.startsWith(ApiKeyVaultFormat.PREFIX))

        val decoded = ApiKeyVaultFormat.decode(encoded)
        assertTrue(decoded is ApiKeyVaultDecoded.Encrypted)
        val encrypted = decoded as ApiKeyVaultDecoded.Encrypted
        assertTrue(iv.contentEquals(encrypted.iv))
        assertTrue(cipherText.contentEquals(encrypted.cipherText))
        assertTrue(ApiKeyVaultFormat.isEncrypted(encoded))
    }

    @Test
    fun `legacy plaintext is classified as migration source`() {
        val decoded = ApiKeyVaultFormat.decode("mca-1234567890abcdef12345678")
        assertTrue(decoded is ApiKeyVaultDecoded.LegacyPlaintext)
        assertEquals("mca-1234567890abcdef12345678", (decoded as ApiKeyVaultDecoded.LegacyPlaintext).value)
        assertFalse(ApiKeyVaultFormat.isEncrypted(decoded.value))
    }

    @Test
    fun `malformed blobs are classified instead of throwing`() {
        assertTrue(ApiKeyVaultFormat.decode(null) is ApiKeyVaultDecoded.Absent)
        assertTrue(ApiKeyVaultFormat.decode("") is ApiKeyVaultDecoded.Absent)
        assertTrue(ApiKeyVaultFormat.decode("   ") is ApiKeyVaultDecoded.Absent)
        assertTrue(ApiKeyVaultFormat.decode("v1:") is ApiKeyVaultDecoded.InvalidPayload)
        assertTrue(ApiKeyVaultFormat.decode("v1:%%%not-base64%%%") is ApiKeyVaultDecoded.InvalidBase64)
        // Decodes but has no room for IV + GCM tag.
        assertTrue(ApiKeyVaultFormat.decode("v1:" + base64(byteArrayOf(1, 2, 3))) is ApiKeyVaultDecoded.InvalidPayload)
        assertTrue(
            ApiKeyVaultFormat.decode("v1:" + "A".repeat(ApiKeyVaultFormat.MAX_STORED_CHARS)) is
                ApiKeyVaultDecoded.InvalidLength
        )
    }

    @Test
    fun `absent slot asks the caller to create a key`() {
        val plan = planApiKeyRead(null, FakeCipher())
        assertEquals(ApiKeyVaultStatus.ABSENT, plan.status)
        assertNull(plan.value)
        assertNull(plan.write)
        assertFalse(plan.usable)
    }

    @Test
    fun `legacy plaintext is encrypted and marked for migration`() {
        val cipher = FakeCipher()
        val plan = planApiKeyRead("mca-legacy-plain", cipher)

        assertEquals(ApiKeyVaultStatus.MIGRATED_FROM_PLAINTEXT, plan.status)
        assertEquals("mca-legacy-plain", plan.value)
        assertNotNull("the encrypted blob must be written over the plaintext", plan.write)
        assertTrue(plan.write!!.startsWith(ApiKeyVaultFormat.PREFIX))
        assertEquals(1, cipher.encryptCalls)
        assertTrue(plan.detail!!.contains("加密"))
    }

    @Test
    fun `keystore unavailable keeps the plaintext usable and writes nothing`() {
        val cipher = FakeCipher(encryptionAvailable = false)
        val plan = planApiKeyRead("mca-legacy-plain", cipher)

        assertEquals(ApiKeyVaultStatus.PLAINTEXT_KEPT_VAULT_UNAVAILABLE, plan.status)
        assertEquals("mca-legacy-plain", plan.value)
        assertNull("no write may happen when encryption failed", plan.write)
        assertTrue(plan.detail!!.contains("未做任何改写"))
    }

    @Test
    fun `encrypted slot decrypts without rewriting`() {
        val cipher = FakeCipher()
        val stored = cipher.encrypt("mca-encrypted")!!

        val plan = planApiKeyRead(stored, cipher)
        assertEquals(ApiKeyVaultStatus.ENCRYPTED, plan.status)
        assertEquals("mca-encrypted", plan.value)
        assertNull(plan.write)
        assertEquals(1, cipher.decryptCalls)
    }

    @Test
    fun `undecryptable blob is kept and never cleared`() {
        val writer = FakeCipher()
        val stored = writer.encrypt("mca-encrypted")!!
        val reader = FakeCipher(decryptionAvailable = false)

        val plan = planApiKeyRead(stored, reader)

        assertEquals(ApiKeyVaultStatus.UNDECRYPTABLE_KEPT, plan.status)
        assertNull("the caller must not receive a fabricated key", plan.value)
        assertNull("the stored blob must not be overwritten or cleared", plan.write)
        assertFalse(plan.usable)
        assertTrue(plan.detail!!.contains("保留"))
        assertEquals("decrypt must be attempted exactly once", 1, reader.decryptCalls)
        assertEquals("a failed read must never re-encrypt", 0, reader.encryptCalls)
    }

    @Test
    fun `corrupt vault payload is preserved rather than rewritten`() {
        val plan = planApiKeyRead("v1:%%broken%%", FakeCipher())
        assertEquals(ApiKeyVaultStatus.UNDECRYPTABLE_KEPT, plan.status)
        assertNull(plan.write)
        assertTrue(plan.detail!!.contains("格式损坏"))
    }

    @Test
    fun `creating a key prefers the vault`() {
        val cipher = FakeCipher()
        val plan = planApiKeyCreate("mca-new-key", cipher)

        assertEquals("mca-new-key", plan.value)
        assertNotNull(plan.write)
        assertTrue(plan.write!!.startsWith(ApiKeyVaultFormat.PREFIX))
        assertEquals(ApiKeyVaultStatus.MIGRATED_FROM_PLAINTEXT, plan.status)
    }

    @Test
    fun `creating a key falls back to plaintext only when the keystore is unavailable`() {
        val plan = planApiKeyCreate("mca-new-key", FakeCipher(encryptionAvailable = false))

        assertEquals("mca-new-key", plan.value)
        assertNull(plan.write)
        assertEquals(ApiKeyVaultStatus.PLAINTEXT_KEPT_VAULT_UNAVAILABLE, plan.status)
        assertTrue(plan.detail!!.contains("密钥库不可用"))
    }

    @Test
    fun `fail-closed session key is non blank so the loopback server still authenticates`() {
        assertTrue(ApiKeyVaultUnavailable.sessionKey.isNotBlank())
        assertTrue(ApiKeyVaultUnavailable.sessionKey.startsWith("vault-unavailable-"))
        assertFalse(ApiKeyVaultFormat.isEncrypted(ApiKeyVaultUnavailable.sessionKey))
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
