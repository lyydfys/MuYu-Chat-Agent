package com.muyuchat.mca

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Web-search persistence owners (P7/S3), moved verbatim from WebSearchProvider.kt: the
 * SharedPreferences-backed configuration store (encrypted API key plus backup providers) and the
 * diagnostic-record history store. Same-package top-level classes, so every production caller and
 * behavioral test call is unchanged; the credential source-text pins in CredentialStoragePolicyTest
 * now read this file with the same assertions.
 */

class WebSearchStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mca_web_search", Context.MODE_PRIVATE)

    fun load(): WebSearchConfig =
        WebSearchConfig(
            enabled = prefs.getBoolean("enabled", false),
            provider = WebSearchProviderType.from(prefs.getString("provider", WebSearchProviderType.SEARXNG.name)),
            endpoint = prefs.getString("endpoint", "").orEmpty(),
            apiKey = decryptApiKey(),
            maxResults = prefs.getInt("max_results", 5),
            fetchPageContent = prefs.getBoolean("fetch_page_content", true),
            triggerMode = WebSearchTriggerMode.from(prefs.getString("trigger_mode", WebSearchTriggerMode.SMART.name)),
            researchMode = WebSearchResearchMode.from(prefs.getString("research_mode", WebSearchResearchMode.AUTO.name)),
            backupProviders = decryptBackupProviders()
        )

    /**
     * Persist the web search configuration.
     *
     * @return [CredentialSaveResult.Stored] or [CredentialSaveResult.Cleared] when the API
     * key is in a durable state. The remaining fields are always saved; a failed credential
     * write leaves the previously stored key untouched and must not be reported to the user
     * as a completed save.
     */
    fun save(config: WebSearchConfig): CredentialSaveResult {
        prefs.edit()
            .putBoolean("enabled", config.enabled)
            .putString("provider", config.provider.name)
            .putString("endpoint", config.endpoint.trim())
            .putInt("max_results", config.sanitizedMaxResults())
            .putBoolean("fetch_page_content", config.fetchPageContent)
            .putString("trigger_mode", config.triggerMode.name)
            .putString("research_mode", config.researchMode.name)
            .apply()
        saveEncryptedBackupProviders(config.backupProviders)
        return saveEncryptedApiKey(config.apiKey.trim())
    }

    private fun decryptApiKey(): String {
        val cipherText = prefs.getString(KEY_API_KEY_CIPHER, null)
        val iv = prefs.getString(KEY_API_KEY_IV, null)
        if (cipherText.isNullOrBlank() || iv.isNullOrBlank()) {
            val legacy = prefs.getString(KEY_API_KEY_LEGACY, "").orEmpty()
            // Upgrade a configuration written before encryption existed. The plaintext
            // slot is only removed inside a successful encrypted commit, so an
            // encryption failure here leaves the working credential untouched.
            if (legacy.isNotBlank()) saveEncryptedApiKey(legacy)
            return legacy
        }
        return runCatching {
            decryptPayload(cipherText, iv)
        }.getOrDefault("")
    }

    /**
     * Persist the search API key and report a typed outcome.
     *
     * The encrypted pair is written and read back first; the plaintext slot is retired only
     * after the stored value decrypts to exactly the submitted key. Encryption failure
     * writes nothing and never degrades to plaintext.
     */
    private fun saveEncryptedApiKey(apiKey: String): CredentialSaveResult =
        when (val write = planCredentialWrite(apiKey, ::encryptPayload)) {
            CredentialWrite.Clear -> {
                prefs.edit()
                    .remove(KEY_API_KEY_CIPHER)
                    .remove(KEY_API_KEY_IV)
                    .remove(KEY_API_KEY_LEGACY)
                    .apply()
                CredentialSaveResult.Cleared
            }

            is CredentialWrite.Store -> {
                prefs.edit()
                    .putString(KEY_API_KEY_CIPHER, write.cipherText)
                    .putString(KEY_API_KEY_IV, write.iv)
                    .apply()
                val readable = runCatching { decryptPayload(write.cipherText, write.iv) }
                    .getOrNull()
                if (readable != apiKey) {
                    CredentialSaveResult.VerificationFailed
                } else {
                    prefs.edit().remove(KEY_API_KEY_LEGACY).apply()
                    CredentialSaveResult.Stored
                }
            }

            CredentialWrite.Unavailable -> CredentialSaveResult.EncryptionUnavailable
        }

    private fun decryptBackupProviders(): List<WebSearchBackupProviderConfig> {
        val cipherText = prefs.getString(KEY_BACKUP_CONFIGS_CIPHER, null)
        val iv = prefs.getString(KEY_BACKUP_CONFIGS_IV, null)
        if (cipherText.isNullOrBlank() || iv.isNullOrBlank()) {
            return emptyList()
        }
        return runCatching {
            JSONArray(decryptPayload(cipherText, iv)).toBackupProviderConfigs()
        }.getOrDefault(emptyList())
    }

    private fun saveEncryptedBackupProviders(providers: List<WebSearchBackupProviderConfig>) {
        val payload = JSONArray().also { array ->
            providers.take(MAX_WEB_SEARCH_BACKUP_PROVIDERS).forEach { provider ->
                array.put(
                    JSONObject()
                        .put("enabled", provider.enabled)
                        .put("provider", provider.provider.name)
                        .put("endpoint", provider.endpoint.trim())
                        .put("apiKey", provider.apiKey.trim())
                )
            }
        }.toString()
        if (providers.none { it.enabled || it.endpoint.isNotBlank() || it.apiKey.isNotBlank() }) {
            prefs.edit()
                .remove(KEY_BACKUP_CONFIGS_CIPHER)
                .remove(KEY_BACKUP_CONFIGS_IV)
                .apply()
            return
        }
        runCatching { encryptPayload(payload) }
            .onSuccess { encrypted ->
                val cipherText = encrypted?.first.orEmpty()
                val iv = encrypted?.second.orEmpty()
                if (cipherText.isBlank() || iv.isBlank()) return@onSuccess
                prefs.edit()
                    .putString(KEY_BACKUP_CONFIGS_CIPHER, cipherText)
                    .putString(KEY_BACKUP_CONFIGS_IV, iv)
                    .apply()
            }
    }

    private fun JSONArray.toBackupProviderConfigs(): List<WebSearchBackupProviderConfig> =
        buildList {
            for (index in 0 until length().coerceAtMost(MAX_WEB_SEARCH_BACKUP_PROVIDERS)) {
                val item = optJSONObject(index) ?: continue
                add(
                    WebSearchBackupProviderConfig(
                        enabled = item.optBoolean("enabled", false),
                        provider = WebSearchProviderType.from(item.optString("provider")),
                        endpoint = item.optString("endpoint"),
                        apiKey = item.optString("apiKey")
                    )
                )
            }
        }

    private fun encryptPayload(payload: String): Pair<String, String>? {
        if (payload.isBlank()) return null
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipherText, Base64.NO_WRAP) to Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }

    private fun decryptPayload(cipherText: String, iv: String): String {
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        return cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_API_KEY_CIPHER = "api_key_cipher"
        private const val KEY_API_KEY_IV = "api_key_iv"
        private const val KEY_API_KEY_LEGACY = "api_key"
        private const val KEY_BACKUP_CONFIGS_CIPHER = "backup_configs_cipher"
        private const val KEY_BACKUP_CONFIGS_IV = "backup_configs_iv"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "mca_web_search_api_key"
        private const val AES_MODE = "AES/GCM/NoPadding"
    }
}

class WebSearchDiagnosticStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mca_web_search_diagnostics", Context.MODE_PRIVATE)

    fun load(): List<WebSearchDiagnosticRecord> =
        runCatching {
            val array = JSONArray(prefs.getString(KEY_RECORDS, "[]").orEmpty())
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    add(WebSearchDiagnosticRecord.fromJson(json))
                }
            }
        }.getOrDefault(emptyList())

    fun add(record: WebSearchDiagnosticRecord): List<WebSearchDiagnosticRecord> {
        val records = (listOf(record) + load()).take(MAX_RECORDS)
        save(records)
        return records
    }

    fun replace(record: WebSearchDiagnosticRecord): List<WebSearchDiagnosticRecord> {
        val records = load()
        val updated = records
            .map { existing -> if (existing.id == record.id) record else existing }
            .let { candidates ->
                if (candidates.any { it.id == record.id }) candidates else (listOf(record) + candidates)
            }
            .take(MAX_RECORDS)
        save(updated)
        return updated
    }

    fun clear(): List<WebSearchDiagnosticRecord> {
        save(emptyList())
        return emptyList()
    }

    private fun save(records: List<WebSearchDiagnosticRecord>) {
        val array = JSONArray()
        records.take(MAX_RECORDS).forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    companion object {
        private const val KEY_RECORDS = "records"
        private const val MAX_RECORDS = 20
    }
}
