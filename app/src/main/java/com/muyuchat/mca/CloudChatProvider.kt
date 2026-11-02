package com.muyuchat.mca

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.deduplicateVisionAttachments
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeMonotonicClock
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.SystemRuntimeMonotonicClock
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ChatBackend {
    LOCAL,
    CLOUD
}

enum class CloudModelKind {
    CHAT,
    IMAGE
}

enum class CloudApiFormat(
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val requiresApiKey: Boolean = true
) {
    OPENAI_COMPATIBLE(
        "OpenAI-compatible",
        "https://api.openai.com/v1",
        "gpt-4.1-mini",
        requiresApiKey = false
    ),
    ANTHROPIC("Anthropic Messages", "https://api.anthropic.com/v1", "claude-3-5-sonnet-latest"),
    OPENAI_RESPONSES("OpenAI Responses", "https://api.openai.com/v1", "gpt-4.1-mini", requiresApiKey = false)
}

enum class CloudImageApiFormat(
    val label: String,
    val defaultBaseUrl: String,
    val defaultEndpointPath: String,
    val defaultImageModel: String,
    val requiresApiKey: Boolean = true
) {
    OPENAI_IMAGES(
        "OpenAI Images",
        "https://api.openai.com/v1",
        "images/generations",
        "gpt-image-1.5",
        requiresApiKey = false
    ),
    DASHSCOPE_IMAGE(
        "DashScope Image",
        "https://dashscope.aliyuncs.com",
        "api/v1/services/aigc/multimodal-generation/generation",
        "qwen-image-2.0-pro"
    ),
    CUSTOM_PATH(
        "Custom Image Path",
        "",
        "images/generations",
        ""
    );

    companion object {
        fun from(value: String?): CloudImageApiFormat =
            entries.firstOrNull { it.name == value || it.label.equals(value, ignoreCase = true) }
                ?: when (value) {
                    "OPENAI_COMPATIBLE" -> OPENAI_IMAGES
                    else -> OPENAI_IMAGES
                }
    }
}

data class CloudApiConfig(
    val enabled: Boolean = false,
    val apiFormat: CloudApiFormat = CloudApiFormat.OPENAI_COMPATIBLE,
    val providerName: String = CloudApiFormat.OPENAI_COMPATIBLE.label,
    val displayName: String = "自定义推理引擎",
    val baseUrl: String = "",
    val apiKey: String = "",
    val chatModel: String = "",
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = false,
    val responsesReasoningEnabled: Boolean = false,
    /** True = request SSE; false = one JSON body (for gateways whose SSE never terminates). */
    val streaming: Boolean = true,
    val imageApiFormat: CloudImageApiFormat = CloudImageApiFormat.OPENAI_IMAGES,
    val imageModel: String = "",
    val imageSize: String = "1024x1024",
    val imageEndpointPath: String = ""
) {
    val configured: Boolean
        get() = enabled &&
            baseUrl.isNotBlank() &&
            chatModel.isNotBlank() &&
            (!apiFormat.requiresApiKey || apiKey.isNotBlank())

    val imageConfigured: Boolean
        get() = enabled &&
            baseUrl.isNotBlank() &&
            imageModel.isNotBlank() &&
            imageEndpointPathForRequest().isNotBlank() &&
            (!imageApiFormat.requiresApiKey || apiKey.isNotBlank())

    val chatChoiceId: String
        get() = "cloud:${apiFormat.name.lowercase()}"

    fun safeDisplayName(): String =
        displayName.trim().ifBlank { chatModel.trim().ifBlank { apiFormat.label } }

    fun imageEndpointPathForRequest(): String =
        imageEndpointPath.trim().trim('/').ifBlank { imageApiFormat.defaultEndpointPath }
}

data class CloudModelRecord(
    val id: String = UUID.randomUUID().toString(),
    val kind: CloudModelKind,
    val apiFormat: CloudApiFormat,
    val providerName: String,
    val displayName: String,
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = false,
    val responsesReasoningEnabled: Boolean = false,
    /** False disables SSE for this record (compatibility with non-terminating gateways). */
    val streaming: Boolean = true,
    val imageApiFormat: CloudImageApiFormat = CloudImageApiFormat.OPENAI_IMAGES,
    val imageEndpointPath: String = "",
    val imageSize: String = "1024x1024",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val configured: Boolean
        get() = baseUrl.isNotBlank() &&
            modelName.isNotBlank() &&
            (!requiredApiKey || apiKey.isNotBlank())

    private val requiredApiKey: Boolean
        get() = if (kind == CloudModelKind.IMAGE) imageApiFormat.requiresApiKey else apiFormat.requiresApiKey

    val protocolLabel: String
        get() = if (kind == CloudModelKind.IMAGE) imageApiFormat.label else apiFormat.label

    fun toChatConfig(): CloudApiConfig =
        CloudApiConfig(
            enabled = true,
            apiFormat = apiFormat,
            providerName = providerName,
            displayName = displayName,
            baseUrl = baseUrl,
            apiKey = apiKey,
            chatModel = modelName,
            supportsVision = supportsVision,
            supportsTools = supportsTools && kind == CloudModelKind.CHAT,
            responsesReasoningEnabled = responsesReasoningEnabled,
            streaming = streaming,
            imageApiFormat = imageApiFormat,
            imageModel = imageApiFormat.defaultImageModel,
            imageSize = imageSize,
            imageEndpointPath = imageEndpointPath
        )

    fun toImageConfig(): CloudApiConfig =
        CloudApiConfig(
            enabled = true,
            apiFormat = apiFormat,
            providerName = providerName,
            displayName = displayName,
            baseUrl = baseUrl,
            apiKey = apiKey,
            chatModel = apiFormat.defaultModel,
            supportsVision = false,
            supportsTools = false,
            imageApiFormat = imageApiFormat,
            imageModel = modelName,
            imageSize = imageSize,
            imageEndpointPath = imageEndpointPath
        )
}

class CloudApiStore internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("mca_cloud_api", Context.MODE_PRIVATE))

    fun load(): CloudApiConfig {
        val format = parseFormat(prefs.getString(KEY_API_FORMAT, null))
        val imageFormat = CloudImageApiFormat.from(prefs.getString(KEY_IMAGE_API_FORMAT, null))
        val baseUrl = prefs.getString(KEY_BASE_URL, null).orEmpty().ifBlank { format.defaultBaseUrl }
        val chatModel = prefs.getString(KEY_CHAT_MODEL, null).orEmpty().ifBlank { format.defaultModel }
        return CloudApiConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            apiFormat = format,
            providerName = prefs.getString(KEY_PROVIDER_NAME, null).orEmpty().ifBlank { format.label },
            displayName = prefs.getString(KEY_DISPLAY_NAME, null).orEmpty().ifBlank { "Cloud Chat" },
            baseUrl = baseUrl,
            apiKey = decryptApiKey(),
            chatModel = chatModel,
            supportsVision = if (prefs.contains(KEY_CHAT_SUPPORTS_VISION)) {
                prefs.getBoolean(KEY_CHAT_SUPPORTS_VISION, false)
            } else {
                guessCloudVisionSupport(chatModel, baseUrl)
            },
            supportsTools = prefs.getBoolean(KEY_CHAT_SUPPORTS_TOOLS, false),
            responsesReasoningEnabled = prefs.getBoolean(KEY_RESPONSES_REASONING, false),
            streaming = prefs.getBoolean(KEY_CHAT_STREAMING, true),
            imageApiFormat = imageFormat,
            imageModel = if (prefs.contains(KEY_IMAGE_MODEL)) {
                prefs.getString(KEY_IMAGE_MODEL, null).orEmpty()
            } else {
                imageFormat.defaultImageModel
            },
            imageSize = prefs.getString(KEY_IMAGE_SIZE, null).orEmpty().ifBlank { DEFAULT_IMAGE_SIZE },
            imageEndpointPath = prefs.getString(KEY_IMAGE_ENDPOINT_PATH, null).orEmpty()
        )
    }

    /**
     * Persist the cloud API configuration.
     *
     * @return [CredentialSaveResult.Stored] or [CredentialSaveResult.Cleared] when the API
     * key is in a durable state. The remaining fields are always saved; a failed credential
     * write leaves the previously stored API key untouched and must not be reported to the
     * user as a completed save.
     */
    fun save(config: CloudApiConfig): CredentialSaveResult {
        val normalized = config.normalizedForStore()
        prefs.edit()
            .putBoolean(KEY_ENABLED, normalized.enabled)
            .putString(KEY_API_FORMAT, normalized.apiFormat.name)
            .putString(KEY_PROVIDER_NAME, normalized.providerName)
            .putString(KEY_DISPLAY_NAME, normalized.displayName)
            .putString(KEY_BASE_URL, normalized.baseUrl)
            .putString(KEY_CHAT_MODEL, normalized.chatModel)
            .putBoolean(KEY_CHAT_SUPPORTS_VISION, normalized.supportsVision)
            .putBoolean(KEY_CHAT_SUPPORTS_TOOLS, normalized.supportsTools)
            .putBoolean(KEY_RESPONSES_REASONING, normalized.responsesReasoningEnabled)
            .putBoolean(KEY_CHAT_STREAMING, normalized.streaming)
            .putString(KEY_IMAGE_API_FORMAT, normalized.imageApiFormat.name)
            .putString(KEY_IMAGE_MODEL, normalized.imageModel)
            .putString(KEY_IMAGE_SIZE, normalized.imageSize)
            .putString(KEY_IMAGE_ENDPOINT_PATH, normalized.imageEndpointPath)
            .apply()
        return saveEncryptedApiKey(normalized.apiKey)
    }

    fun loadModels(): List<CloudModelRecord> {
        val raw = prefs.getString(KEY_CLOUD_MODELS, null)
        if (raw.isNullOrBlank()) {
            val legacy = load()
            return buildList {
                if (legacy.configured) {
                    add(
                        CloudModelRecord(
                            kind = CloudModelKind.CHAT,
                            apiFormat = legacy.apiFormat,
                            providerName = legacy.providerName,
                            displayName = legacy.safeDisplayName(),
                            baseUrl = legacy.baseUrl,
                            apiKey = legacy.apiKey,
                            modelName = legacy.chatModel,
                            supportsVision = legacy.supportsVision,
                            supportsTools = legacy.supportsTools,
                            responsesReasoningEnabled = legacy.responsesReasoningEnabled,
                            streaming = legacy.streaming,
                            imageSize = legacy.imageSize
                        )
                    )
                }
                if (legacy.imageConfigured && legacy.imageModel.isNotBlank()) {
                    add(
                        CloudModelRecord(
                            kind = CloudModelKind.IMAGE,
                            apiFormat = legacy.apiFormat,
                            providerName = legacy.providerName,
                            displayName = "${legacy.providerName} Image",
                            baseUrl = legacy.baseUrl,
                            apiKey = legacy.apiKey,
                            modelName = legacy.imageModel,
                            imageApiFormat = legacy.imageApiFormat,
                            imageEndpointPath = legacy.imageEndpointPathForRequest(),
                            imageSize = legacy.imageSize
                        )
                    )
                }
            }
        }
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                array.getJSONObject(index).toCloudModelRecord()
            }.sortedWith(
                compareBy<CloudModelRecord> { it.kind.name }
                    .thenByDescending { it.updatedAt }
            )
        }.getOrDefault(emptyList())
    }

    fun saveModels(models: List<CloudModelRecord>) {
        val array = JSONArray()
        models.forEach { model -> array.put(model.toJson()) }
        prefs.edit().putString(KEY_CLOUD_MODELS, array.toString()).apply()
    }

    fun upsertModel(model: CloudModelRecord) {
        val existing = loadModels()
        saveModels((listOf(model.copy(updatedAt = System.currentTimeMillis())) + existing.filterNot { it.id == model.id }))
    }

    fun loadSelectedBackend(): ChatBackend =
        runCatching { ChatBackend.valueOf(prefs.getString(KEY_SELECTED_BACKEND, ChatBackend.LOCAL.name).orEmpty()) }
            .getOrDefault(ChatBackend.LOCAL)

    fun saveSelectedBackend(backend: ChatBackend) {
        prefs.edit().putString(KEY_SELECTED_BACKEND, backend.name).apply()
    }

    fun loadSelectedCloudChatModelId(): String? =
        prefs.getString(KEY_SELECTED_CLOUD_CHAT_MODEL_ID, null)?.takeIf { it.isNotBlank() }

    fun saveSelectedCloudChatModelId(modelId: String?) {
        prefs.edit().putString(KEY_SELECTED_CLOUD_CHAT_MODEL_ID, modelId.orEmpty()).apply()
    }

    fun loadSelectedCloudImageModelId(): String? =
        prefs.getString(KEY_SELECTED_CLOUD_IMAGE_MODEL_ID, null)?.takeIf { it.isNotBlank() }

    fun saveSelectedCloudImageModelId(modelId: String?) {
        prefs.edit().putString(KEY_SELECTED_CLOUD_IMAGE_MODEL_ID, modelId.orEmpty()).apply()
    }

    private fun parseFormat(value: String?): CloudApiFormat =
        CloudApiFormat.entries.firstOrNull { it.name == value || it.label.equals(value, ignoreCase = true) }
            ?: CloudApiFormat.OPENAI_COMPATIBLE

    private fun parseImageFormat(value: String?): CloudImageApiFormat =
        CloudImageApiFormat.from(value)

    private fun parseKind(value: String?): CloudModelKind =
        CloudModelKind.entries.firstOrNull { it.name == value } ?: CloudModelKind.CHAT

    private fun JSONObject.toCloudModelRecord(): CloudModelRecord {
        val cipher = optString("apiKeyCipher").takeIf { it.isNotBlank() }
        val iv = optString("apiKeyIv").takeIf { it.isNotBlank() }
        val kind = parseKind(optString("kind"))
        return CloudModelRecord(
            id = optString("id").ifBlank { UUID.randomUUID().toString() },
            kind = kind,
            apiFormat = parseFormat(optString("apiFormat")),
            providerName = optString("providerName"),
            displayName = optString("displayName"),
            baseUrl = optString("baseUrl"),
            apiKey = if (cipher != null && iv != null) decryptApiKeyPayload(cipher, iv) else optString("apiKey"),
            modelName = optString("modelName"),
            supportsVision = if (has("supportsVision")) {
                optBoolean("supportsVision", false)
            } else {
                guessCloudVisionSupport(
                    modelName = optString("modelName"),
                    baseUrl = optString("baseUrl")
                )
            },
            supportsTools = kind == CloudModelKind.CHAT && optBoolean("supportsTools", false),
            imageApiFormat = parseImageFormat(optString("imageApiFormat", optString("apiFormat"))),
            responsesReasoningEnabled = optBoolean("responsesReasoningEnabled", false),
            streaming = optBoolean("streaming", true),
            imageEndpointPath = optString("imageEndpointPath"),
            imageSize = optString("imageSize", DEFAULT_IMAGE_SIZE),
            createdAt = optLong("createdAt", System.currentTimeMillis()),
            updatedAt = optLong("updatedAt", System.currentTimeMillis())
        )
    }

    private fun CloudModelRecord.toJson(): JSONObject {
        val encrypted = encryptApiKey(apiKey)
        return JSONObject()
            .put("id", id)
            .put("kind", kind.name)
            .put("apiFormat", apiFormat.name)
            .put("providerName", providerName)
            .put("displayName", displayName)
            .put("baseUrl", baseUrl)
            .put("apiKeyCipher", encrypted?.first.orEmpty())
            .put("apiKeyIv", encrypted?.second.orEmpty())
            .put("modelName", modelName)
            .put("supportsVision", supportsVision)
            .put("supportsTools", kind == CloudModelKind.CHAT && supportsTools)
            .put("responsesReasoningEnabled", responsesReasoningEnabled)
            .put("streaming", streaming)
            .put("imageApiFormat", imageApiFormat.name)
            .put("imageEndpointPath", imageEndpointPath)
            .put("imageSize", imageSize)
            .put("createdAt", createdAt)
            .put("updatedAt", updatedAt)
    }

    private fun CloudApiConfig.normalizedForStore(): CloudApiConfig =
        copy(
            providerName = providerName.trim().ifBlank { apiFormat.label },
            displayName = displayName.trim().ifBlank { chatModel.trim().ifBlank { "自定义推理引擎" } },
            baseUrl = baseUrl.trim().trimEnd('/'),
            chatModel = chatModel.trim(),
            imageEndpointPath = imageEndpointPath.trim().trim('/'),
            imageModel = imageModel.trim(),
            imageSize = imageSize.trim().ifBlank { DEFAULT_IMAGE_SIZE }
        ).normalizedForImageRequest(preserveProviderName = true)

    private fun decryptApiKey(): String {
        val cipherText = prefs.getString(KEY_API_KEY_CIPHER, null)
        val iv = prefs.getString(KEY_API_KEY_IV, null)
        if (cipherText.isNullOrBlank() || iv.isNullOrBlank()) {
            val legacy = prefs.getString(KEY_API_KEY_LEGACY, null).orEmpty()
            // Upgrade a configuration written before encryption existed. The plaintext
            // slot is only removed inside a successful encrypted commit, so an
            // encryption failure here leaves the working credential untouched.
            if (legacy.isNotBlank()) saveEncryptedApiKey(legacy)
            return legacy
        }
        return runCatching {
            decryptApiKeyPayload(cipherText, iv)
        }.getOrDefault("")
    }

    /**
     * Persist the API key and report a typed outcome.
     *
     * Order matters (plan section 11.3 items 3-4): the encrypted pair is written and read
     * back first, and the plaintext slot is retired only after the stored value decrypts
     * to exactly the submitted key. An encryption failure writes nothing at all — it never
     * degrades to plaintext — and an unverifiable write leaves any existing plaintext
     * credential in place so the user does not silently lose a working configuration.
     */
    private fun saveEncryptedApiKey(apiKey: String): CredentialSaveResult =
        when (val write = planCredentialWrite(apiKey, ::encryptApiKey)) {
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
                // SharedPreferences.apply() publishes to the in-memory map synchronously,
                // so this read observes exactly what will be persisted.
                val readable = runCatching { decryptApiKeyPayload(write.cipherText, write.iv) }
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

    private fun encryptApiKey(apiKey: String): Pair<String, String>? {
        if (apiKey.isBlank()) return null
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipherText, Base64.NO_WRAP) to Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }

    private fun decryptApiKeyPayload(cipherText: String, iv: String): String {
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
        private const val KEY_ENABLED = "cloud_enabled"
        private const val KEY_API_FORMAT = "cloud_api_format"
        private const val KEY_PROVIDER_NAME = "cloud_provider_name"
        private const val KEY_DISPLAY_NAME = "cloud_display_name"
        private const val KEY_BASE_URL = "cloud_base_url"
        private const val KEY_CHAT_MODEL = "cloud_chat_model"
        private const val KEY_CHAT_SUPPORTS_VISION = "cloud_chat_supports_vision"
        private const val KEY_CHAT_SUPPORTS_TOOLS = "cloud_chat_supports_tools"
        private const val KEY_RESPONSES_REASONING = "cloud_responses_reasoning"
        private const val KEY_CHAT_STREAMING = "cloud_chat_streaming"
        private const val KEY_IMAGE_API_FORMAT = "cloud_image_api_format"
        private const val KEY_IMAGE_MODEL = "cloud_image_model"
        private const val KEY_IMAGE_SIZE = "cloud_image_size"
        private const val KEY_IMAGE_ENDPOINT_PATH = "cloud_image_endpoint_path"
        private const val KEY_CLOUD_MODELS = "cloud_models_json"
        private const val KEY_SELECTED_BACKEND = "selected_backend"
        private const val KEY_SELECTED_CLOUD_CHAT_MODEL_ID = "selected_cloud_chat_model_id"
        private const val KEY_SELECTED_CLOUD_IMAGE_MODEL_ID = "selected_cloud_image_model_id"
        private const val KEY_API_KEY_CIPHER = "cloud_api_key_cipher"
        private const val KEY_API_KEY_IV = "cloud_api_key_iv"
        private const val KEY_API_KEY_LEGACY = "cloud_api_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "mca_cloud_api_key"
        private const val AES_MODE = "AES/GCM/NoPadding"
        private const val DEFAULT_IMAGE_SIZE = "1024x1024"
    }
}
