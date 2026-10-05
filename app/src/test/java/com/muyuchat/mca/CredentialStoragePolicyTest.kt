package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the credential storage rule: an API key is either stored encrypted and verified,
 * or not stored at all.
 *
 * Cloud chat and web search previously wrote the key into a plaintext SharedPreferences
 * slot whenever encryption failed, and treated a blank ciphertext/IV pair as a silent
 * no-op, so a failed save looked like a successful one. These tests cover the decision
 * logic directly and then assert that neither provider reintroduces a plaintext write or
 * retires the plaintext slot before the encrypted value is verified.
 */
class CredentialStoragePolicyTest {

    @Test
    fun `blank key clears every stored form`() {
        val plan = planCredentialWrite("") { error("encrypt must not be called for a blank key") }
        assertEquals(CredentialWrite.Clear, plan)
    }

    @Test
    fun `whitespace only key is treated as blank`() {
        val plan = planCredentialWrite("   \n\t ") { error("encrypt must not be called") }
        assertEquals(CredentialWrite.Clear, plan)
    }

    @Test
    fun `successful encryption yields the ciphertext and iv`() {
        val plan = planCredentialWrite("sk-secret") { key -> "$key-cipher" to "iv-1" }
        assertEquals(CredentialWrite.Store("sk-secret-cipher", "iv-1"), plan)
    }

    @Test
    fun `throwing encryption is unavailable rather than a plaintext fallback`() {
        val plan = planCredentialWrite("sk-secret") { throw IllegalStateException("keystore unavailable") }
        assertEquals(CredentialWrite.Unavailable, plan)
    }

    @Test
    fun `null encryption result is unavailable`() {
        val plan = planCredentialWrite("sk-secret") { null }
        assertEquals(CredentialWrite.Unavailable, plan)
    }

    @Test
    fun `blank ciphertext is unavailable instead of a silent no-op`() {
        val plan = planCredentialWrite("sk-secret") { "" to "iv-1" }
        assertEquals(CredentialWrite.Unavailable, plan)
    }

    @Test
    fun `blank iv is unavailable`() {
        val plan = planCredentialWrite("sk-secret") { "cipher-1" to "" }
        assertEquals(CredentialWrite.Unavailable, plan)
    }

    @Test
    fun `every failure mode resolves to unavailable so callers can report honestly`() {
        val failures: List<(String) -> Pair<String, String>?> = listOf(
            { throw IllegalStateException("keystore unavailable") },
            { throw RuntimeException("key invalidated") },
            { null },
            { "" to "iv" },
            { "cipher" to "" },
            { "" to "" }
        )
        failures.forEach { encrypt ->
            assertEquals(
                "a failed encryption must never resolve to a storable value",
                CredentialWrite.Unavailable,
                planCredentialWrite("sk-secret", encrypt)
            )
        }
    }

    @Test
    fun `only a usable pair produces a storable value`() {
        val plan = planCredentialWrite("sk-secret") { "cipher" to "iv" }
        assertTrue(plan is CredentialWrite.Store)
    }

    @Test
    fun `only stored and cleared count as durable`() {
        assertTrue(CredentialSaveResult.Stored.isStored)
        assertTrue(CredentialSaveResult.Cleared.isStored)
        assertFalse(CredentialSaveResult.EncryptionUnavailable.isStored)
        assertFalse(CredentialSaveResult.VerificationFailed.isStored)
    }

    @Test
    fun `cloud chat never writes the plaintext slot`() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/CloudChatProvider.kt")

        assertTrue(
            "cloud chat must route credential writes through the shared policy",
            source.contains("planCredentialWrite(apiKey, ::encryptApiKey)")
        )
        assertFalse(
            "the plaintext slot must never be written",
            source.contains("putString(KEY_API_KEY_LEGACY")
        )
        assertFalse(
            "the dead plaintext writer must stay removed",
            source.contains("fun saveLegacyApiKey")
        )
        assertTrue(
            "an encryption failure must be reported as a typed result",
            source.contains("CredentialWrite.Unavailable -> CredentialSaveResult.EncryptionUnavailable")
        )
    }

    @Test
    fun `web search never writes the plaintext slot`() {
        // P7/S3: the web-search credential store moved to WebSearchStores.kt; this pin reads the
        // owner file with the same assertions.
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/WebSearchStores.kt")

        assertTrue(
            "web search must route credential writes through the shared policy",
            source.contains("planCredentialWrite(apiKey, ::encryptPayload)")
        )
        assertFalse(
            "the plaintext slot must never be written",
            source.contains("putString(KEY_API_KEY_LEGACY")
        )
        assertTrue(
            "an encryption failure must be reported as a typed result",
            source.contains("CredentialWrite.Unavailable -> CredentialSaveResult.EncryptionUnavailable")
        )
    }

    @Test
    fun `the plaintext slot is retired only after the stored value is verified`() {
        // P7/S3: web search's entry now points at WebSearchStores.kt (the store moved); assertions unchanged.
        listOf(
            "app/src/main/java/com/muyuchat/mca/CloudChatProvider.kt",
            "app/src/main/java/com/muyuchat/mca/WebSearchStores.kt"
        ).forEach { path ->
            val source = sourceFile(path)
            val verifyIndex = source.indexOf("if (readable != apiKey)")
            val retireIndex = source.indexOf("prefs.edit().remove(KEY_API_KEY_LEGACY).apply()")

            assertTrue("$path must verify the stored value before retiring plaintext", verifyIndex >= 0)
            assertTrue("$path must still retire the legacy plaintext slot", retireIndex >= 0)
            assertTrue(
                "$path must retire the plaintext slot after the verification",
                retireIndex > verifyIndex
            )
            assertTrue(
                "$path must report an unverifiable write instead of claiming success",
                source.contains("CredentialSaveResult.VerificationFailed")
            )
            assertFalse(
                "$path must not remove the legacy slot in the failure branch",
                source.substringAfter("if (readable != apiKey)")
                    .substringBefore("} else {")
                    .contains("remove(KEY_API_KEY_LEGACY)")
            )
        }
    }

    @Test
    fun `legacy plaintext credentials are migrated on read without losing the working value`() {
        // P7/S3: web search's entry now points at WebSearchStores.kt (the store moved); assertions unchanged.
        listOf(
            "app/src/main/java/com/muyuchat/mca/CloudChatProvider.kt",
            "app/src/main/java/com/muyuchat/mca/WebSearchStores.kt"
        ).forEach { path ->
            val source = sourceFile(path)
            assertTrue(
                "$path must keep reading a pre-encryption credential",
                source.contains("getString(KEY_API_KEY_LEGACY")
            )
            assertTrue(
                "$path must upgrade that credential to the encrypted store",
                source.contains("if (legacy.isNotBlank()) saveEncryptedApiKey(legacy)")
            )
            assertTrue(
                "$path must return the legacy value even when the upgrade fails",
                source.contains("return legacy")
            )
        }
    }

    @Test
    fun `cloud quick test does not claim success when the key could not be stored`() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        // The two honest failure messages and the typed-result mapping moved to
        // GenerationPreferenceStore.kt during the settings extraction. Reading both files keeps the
        // same contract under test -- and the assertions below are unchanged.
        val preferences = sourceFile("app/src/main/java/com/muyuchat/mca/GenerationPreferenceStore.kt")
        // The quick test itself moved to CloudModelConfigurationOperations.kt with the cloud
        // configuration family. The ViewModel keeps the public entry point. Reading all three files
        // keeps the same contract under test; the two substrings follow the extraction (the store is
        // reached through the port, and the helper is the top-level `credentialWriteFailureMessage`).
        val cloudConfig =
            sourceFile("app/src/main/java/com/muyuchat/mca/CloudModelConfigurationOperations.kt")
        val both = source + preferences
        val sources = both + cloudConfig

        assertTrue(
            "the quick test must consume the typed credential result",
            cloudConfig.contains("val credential = port.cloudApiStore.save(config)")
        )
        assertTrue(
            "the success message must be suppressed when the credential write failed",
            cloudConfig.contains("credentialWriteFailureMessage(credential)")
        )
        assertTrue(
            "both failure modes need distinct honest messages",
            both.contains("系统密钥库当前不可用") && both.contains("写入后校验失败")
        )
        assertFalse(
            "the old boolean flag must not come back",
            sources.contains("val credentialStored = cloudApiStore.save(config)")
        )
    }

    private fun sourceFile(relativePath: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile
        }
        error("Unable to locate $relativePath from ${System.getProperty("user.dir")}")
    }
}
