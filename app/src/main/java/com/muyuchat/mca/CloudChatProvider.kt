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

internal fun CloudApiConfig.normalizedForImageRequest(preserveProviderName: Boolean = false): CloudApiConfig {
    val cleanBaseUrl = baseUrl.trim().trimEnd('/')
    val cleanImageModel = imageModel.trim()
    val cleanEndpointPath = imageEndpointPath.trim().trim('/')
    val inferredImageFormat = when {
        cleanBaseUrl.isDashScopeBaseUrl() -> CloudImageApiFormat.DASHSCOPE_IMAGE
        imageApiFormat == CloudImageApiFormat.DASHSCOPE_IMAGE -> CloudImageApiFormat.DASHSCOPE_IMAGE
        else -> imageApiFormat
    }
    val normalizedEndpointPath = when {
        inferredImageFormat == CloudImageApiFormat.DASHSCOPE_IMAGE &&
            (cleanEndpointPath.isBlank() || cleanEndpointPath.isOpenAiImagesEndpointPath()) ->
            CloudImageApiFormat.DASHSCOPE_IMAGE.defaultEndpointPath
        cleanEndpointPath.isBlank() -> inferredImageFormat.defaultEndpointPath
        else -> cleanEndpointPath
    }
    val imageProtocolLabels = (CloudImageApiFormat.entries.map { it.label } + CloudApiFormat.entries.map { it.label }).toSet()
    return copy(
        providerName = providerName.trim().ifBlank { inferredImageFormat.label }.let { current ->
            if (!preserveProviderName && current in imageProtocolLabels && current != inferredImageFormat.label) inferredImageFormat.label else current
        },
        baseUrl = cleanBaseUrl,
        imageApiFormat = inferredImageFormat,
        imageModel = cleanImageModel,
        imageEndpointPath = normalizedEndpointPath,
        imageSize = imageSize.trim().ifBlank { "1024x1024" }
    )
}

private fun String.isDashScopeBaseUrl(): Boolean =
    contains("dashscope.aliyuncs.com", ignoreCase = true) ||
            contains("dashscope-intl.aliyuncs.com", ignoreCase = true)

private fun String.isMiMoBaseUrl(): Boolean =
    contains("xiaomimimo.com", ignoreCase = true) ||
            contains("mimo.mi.com", ignoreCase = true)

private fun String.isOpenAiImagesEndpointPath(): Boolean =
    trim('/').endsWith(CloudImageApiFormat.OPENAI_IMAGES.defaultEndpointPath, ignoreCase = true)

internal fun Request.Builder.addCloudApiKeyHeaders(config: CloudApiConfig): Request.Builder {
    if (config.apiKey.isNotBlank()) {
        addHeader("Authorization", "Bearer ${config.apiKey}")
        if (config.baseUrl.isMiMoBaseUrl()) {
            addHeader("api-key", config.apiKey)
        }
    }
    return this
}

internal fun guessCloudVisionSupport(modelName: String, baseUrl: String): Boolean {
    val text = "$modelName $baseUrl".lowercase()
    val visionHints = listOf(
        "vision",
        "multimodal",
        "multi-modal",
        "vl",
        "mimo",
        "gpt-4o",
        "qwen-vl",
        "qvq",
        "claude-3",
        "claude-sonnet",
        "claude-opus",
        "claude-haiku"
    )
    return visionHints.any { it in text }
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

    fun save(config: CloudApiConfig) {
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
            .putString(KEY_IMAGE_API_FORMAT, normalized.imageApiFormat.name)
            .putString(KEY_IMAGE_MODEL, normalized.imageModel)
            .putString(KEY_IMAGE_SIZE, normalized.imageSize)
            .putString(KEY_IMAGE_ENDPOINT_PATH, normalized.imageEndpointPath)
            .apply()
        saveEncryptedApiKey(normalized.apiKey)
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
            return prefs.getString(KEY_API_KEY_LEGACY, null).orEmpty()
        }
        return runCatching {
            decryptApiKeyPayload(cipherText, iv)
        }.getOrDefault("")
    }

    private fun saveEncryptedApiKey(apiKey: String) {
        if (apiKey.isBlank()) {
            prefs.edit()
                .remove(KEY_API_KEY_CIPHER)
                .remove(KEY_API_KEY_IV)
                .remove(KEY_API_KEY_LEGACY)
                .apply()
            return
        }
        runCatching { encryptApiKey(apiKey) }
            .onSuccess { encrypted ->
                val cipherText = encrypted?.first.orEmpty()
                val iv = encrypted?.second.orEmpty()
                if (cipherText.isBlank() || iv.isBlank()) return@onSuccess
                prefs.edit()
                    .putString(KEY_API_KEY_CIPHER, cipherText)
                    .putString(KEY_API_KEY_IV, iv)
                    .remove(KEY_API_KEY_LEGACY)
                    .apply()
            }
            .onFailure {
                prefs.edit().putString(KEY_API_KEY_LEGACY, apiKey).apply()
            }
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

    private fun saveLegacyApiKey(apiKey: String) {
        if (apiKey.isBlank()) {
            prefs.edit()
                .remove(KEY_API_KEY_CIPHER)
                .remove(KEY_API_KEY_IV)
                .remove(KEY_API_KEY_LEGACY)
                .apply()
        } else {
            prefs.edit().putString(KEY_API_KEY_LEGACY, apiKey).apply()
        }
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

data class CloudImageResult(
    val bytes: ByteArray,
    val mimeType: String = "image/png",
    val revisedPrompt: String = ""
)

data class CloudImageRequest(
    val positivePrompt: String,
    val negativePrompt: String? = null,
    val count: Int = 1,
    val size: String? = null
) {
    init {
        require(positivePrompt.isNotBlank()) { "Cloud image prompt must not be blank." }
        require(count in 1..8) { "Cloud image count must be between 1 and 8." }
    }
}

data class CloudImageCapabilities(
    val maxNativeCount: Int,
    val supportsNegativePrompt: Boolean,
    val supportsSequentialCount: Boolean = true
)

data class CloudImageBatchResult(
    val outputs: List<CloudImageResult>,
    val providerRequestCount: Int,
    val requestedCount: Int
) {
    init {
        require(outputs.size == requestedCount) { "Cloud image response count does not match the request." }
    }
}

class CloudImageProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    fun capabilities(config: CloudApiConfig): CloudImageCapabilities {
        val normalized = config.normalizedForImageRequest()
        return when (normalized.imageApiFormat) {
            CloudImageApiFormat.OPENAI_IMAGES -> CloudImageCapabilities(
                maxNativeCount = if (normalized.imageModel.startsWith("dall-e-3", ignoreCase = true)) 1 else 8,
                supportsNegativePrompt = false
            )
            // The current DashScope and custom adapters only establish single-output request
            // semantics. They can still fulfill a batch through separate owned requests.
            CloudImageApiFormat.DASHSCOPE_IMAGE,
            CloudImageApiFormat.CUSTOM_PATH -> CloudImageCapabilities(1, false)
        }
    }

    suspend fun generate(config: CloudApiConfig, prompt: String): CloudImageResult =
        generateBatch(config, CloudImageRequest(prompt)).outputs.single()

    suspend fun generate(config: CloudApiConfig, imageRequest: CloudImageRequest): CloudImageBatchResult =
        generateBatch(config, imageRequest)

    suspend fun generateBatch(config: CloudApiConfig, imageRequest: CloudImageRequest): CloudImageBatchResult =
        withContext(Dispatchers.IO) {
        val requestConfig = config.normalizedForImageRequest()
        if (!requestConfig.imageConfigured) {
            error("当前云端 API 未配置可用的生图协议。请启用 OpenAI Images、DashScope Image 或自定义路径，并填写生图模型。")
        }
        val capability = capabilities(requestConfig)
        require(imageRequest.negativePrompt.isNullOrBlank()) {
            "${requestConfig.imageApiFormat.label} does not support a separate negative prompt; edit or explicitly omit it before submission."
        }
        val outputs = mutableListOf<CloudImageResult>()
        var requestCount = 0
        while (outputs.size < imageRequest.count) {
            currentCoroutineContext().ensureActive()
            val remaining = imageRequest.count - outputs.size
            val nativeCount = minOf(remaining, capability.maxNativeCount)
            val request = when (requestConfig.imageApiFormat) {
                CloudImageApiFormat.OPENAI_IMAGES -> openAiImageRequest(requestConfig, imageRequest, nativeCount)
                CloudImageApiFormat.DASHSCOPE_IMAGE -> dashScopeImageRequest(requestConfig, imageRequest, nativeCount)
                CloudImageApiFormat.CUSTOM_PATH -> customImageRequest(requestConfig, imageRequest, nativeCount)
            }
            // Keep the cancellable request alive until the response body has been consumed.
            // Returning a Response as soon as headers arrive leaves a non-cancellable body read.
            val body = executeImageRequest(request) { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error(
                        "生图接口错误 ${response.code}: ${parseProviderError(responseBody)}。" +
                            "协议：${requestConfig.imageApiFormat.label}，路径：${request.url.encodedPath}"
                    )
                }
                responseBody
            }
            val parsed = when (requestConfig.imageApiFormat) {
                CloudImageApiFormat.OPENAI_IMAGES -> parseOpenAiImageResponse(body)
                CloudImageApiFormat.DASHSCOPE_IMAGE -> parseDashScopeImageResponse(requestConfig, body)
                CloudImageApiFormat.CUSTOM_PATH -> parseFlexibleImageResponse(requestConfig, body)
            }
            require(parsed.size == nativeCount) {
                "${requestConfig.imageApiFormat.label} returned ${parsed.size} image(s); expected $nativeCount."
            }
            outputs += parsed
            requestCount++
        }
        CloudImageBatchResult(outputs, requestCount, imageRequest.count)
    }

    private fun openAiImageRequest(config: CloudApiConfig, imageRequest: CloudImageRequest, count: Int): Request {
        val root = JSONObject()
            .put("model", config.imageModel.trim())
            .put("prompt", imageRequest.positivePrompt)
            .put("n", count)
            .put("size", openAiImageSize(imageRequest.size ?: config.imageSize))
        if (!config.imageModel.startsWith("gpt-image", ignoreCase = true)) {
            root.put("response_format", "b64_json")
        }
        val builder = Request.Builder()
            .url(endpointUrl(config.baseUrl, config.imageEndpointPathForRequest()))
            .addHeader("Accept", "application/json")
            .addCloudApiKeyHeaders(config)
        return builder.post(root.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
    }

    private fun dashScopeImageRequest(config: CloudApiConfig, imageRequest: CloudImageRequest, count: Int): Request {
        val root = JSONObject()
            .put("model", config.imageModel.trim())
            .put(
                "input",
                JSONObject().put(
                    "messages",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", JSONArray().put(JSONObject().put("text", imageRequest.positivePrompt)))
                    )
                )
            )
            .put(
                "parameters",
                JSONObject()
                    .put("size", dashScopeImageSize(imageRequest.size ?: config.imageSize))
                    .put("n", count)
            )
        return Request.Builder()
            .url(endpointUrl(config.baseUrl, config.imageEndpointPathForRequest()))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Accept", "application/json")
            .post(root.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun customImageRequest(config: CloudApiConfig, imageRequest: CloudImageRequest, count: Int): Request {
        val root = JSONObject()
            .put("model", config.imageModel.trim())
            .put("prompt", imageRequest.positivePrompt)
            .put("n", count)
            .put("size", openAiImageSize(imageRequest.size ?: config.imageSize))
        val builder = Request.Builder()
            .url(endpointUrl(config.baseUrl, config.imageEndpointPathForRequest()))
            .addHeader("Accept", "application/json")
            .addCloudApiKeyHeaders(config)
        return builder.post(root.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
    }

    internal suspend fun parseOpenAiImageResponse(body: String): List<CloudImageResult> {
        val root = JSONObject(body)
        root.imageError()?.let { error(it) }
        val data = root.optJSONArray("data") ?: error("生图接口未返回图片数据")
        require(data.length() in 1..8) { "生图接口返回的图片数量无效。" }
        return buildList(data.length()) {
            for (index in 0 until data.length()) {
                add(parseImageItem(data.optJSONObject(index) ?: error("图片 ${index + 1} 不是对象。"), index))
            }
        }
    }

    private suspend fun parseDashScopeImageResponse(config: CloudApiConfig, body: String): List<CloudImageResult> {
        val root = JSONObject(body)
        root.imageError()?.let { error(it) }
        val taskId = root.optJSONObject("output")?.optString("task_id").orEmpty()
        if (taskId.isNotBlank()) {
            return waitForDashScopeTask(config, taskId)
        }
        return parseFlexibleImageResponse(config, body)
    }

    private suspend fun waitForDashScopeTask(config: CloudApiConfig, taskId: String): List<CloudImageResult> {
        val taskUrl = dashScopeTaskUrl(config.baseUrl, taskId)
        repeat(60) {
            delay(1500)
            val body = executeImageRequest(
                Request.Builder()
                    .url(taskUrl)
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()
            ) { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("DashScope task ${response.code}: ${parseProviderError(responseBody)}")
                }
                responseBody
            }
            val root = JSONObject(body)
            val output = root.optJSONObject("output")
            val status = output?.optString("task_status").orEmpty()
            if (status.equals("SUCCEEDED", ignoreCase = true)) {
                return parseFlexibleImageResponse(config, body)
            }
            if (status.equals("FAILED", ignoreCase = true) || status.equals("CANCELED", ignoreCase = true)) {
                error(parseProviderError(body))
            }
        }
        error("DashScope image task timed out")
    }

    internal suspend fun parseFlexibleImageResponse(config: CloudApiConfig, body: String): List<CloudImageResult> {
        val root = JSONObject(body)
        root.imageError()?.let { error(it) }
        val output = root.optJSONObject("output")
        val items = root.optJSONArray("data")
            ?: output?.optJSONArray("results")
            ?: output?.optJSONArray("images")
            ?: root.optJSONArray("results")
        if (items != null) {
            require(items.length() in 1..8) { "生图接口返回的图片数量无效。" }
            return buildList(items.length()) {
                for (index in 0 until items.length()) {
                    add(parseImageItem(items.optJSONObject(index) ?: error("图片 ${index + 1} 不是对象。"), index))
                }
            }
        }
        findFirstImageUrl(root)?.let {
            return listOf(downloadImage(it, root.optString("revised_prompt")))
        }
        error("生图接口未返回可下载图片。协议：${config.imageApiFormat.label}")
    }

    private suspend fun parseImageItem(item: JSONObject, index: Int): CloudImageResult {
        val revisedPrompt = item.optString("revised_prompt", item.optString("revisedPrompt"))
        val b64 = item.optString("b64_json", item.optString("b64Json"))
        if (b64.isNotBlank()) {
            require(b64.length <= MAX_IMAGE_BYTES * 4 / 3 + 16) { "图片 ${index + 1} 超过大小限制。" }
            val bytes = runCatching { java.util.Base64.getDecoder().decode(b64) }
                .getOrElse { error("图片 ${index + 1} 的 base64 数据无效。") }
            return validatedImage(bytes, item.optString("mime_type"), revisedPrompt, index)
        }
        val url = listOf("url", "image_url", "image", "output_url")
            .firstNotNullOfOrNull { key -> item.optString(key).takeIf(String::isNotBlank) }
        if (url != null) return downloadImage(url, revisedPrompt)
        error("图片 ${index + 1} 缺少 b64_json 或 url。")
    }

    private fun validatedImage(bytes: ByteArray, declaredMime: String, revisedPrompt: String, index: Int): CloudImageResult {
        require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES) { "图片 ${index + 1} 的字节数无效。" }
        val actualMime = when {
            bytes.size >= 24 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> {
                val width = readPngDimension(bytes, 16)
                val height = readPngDimension(bytes, 20)
                require(width in 1..MAX_IMAGE_DIMENSION && height in 1..MAX_IMAGE_DIMENSION) {
                    "图片 ${index + 1} 的 PNG 尺寸无效。"
                }
                "image/png"
            }
            bytes.size >= 3 && (bytes[0].toInt() and 255) == 0xff &&
                (bytes[1].toInt() and 255) == 0xd8 && (bytes[2].toInt() and 255) == 0xff -> "image/jpeg"
            bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
                bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "image/webp"
            else -> error("图片 ${index + 1} 不是受支持的 PNG、JPEG 或 WebP 格式。")
        }
        require(declaredMime.isBlank() || declaredMime.substringBefore(';').trim().equals(actualMime, ignoreCase = true)) {
            "图片 ${index + 1} 的 MIME 声明与实际格式不一致。"
        }
        return CloudImageResult(bytes, actualMime, revisedPrompt)
    }

    private fun readPngDimension(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 24) or
            ((bytes[offset + 1].toInt() and 255) shl 16) or
            ((bytes[offset + 2].toInt() and 255) shl 8) or
            (bytes[offset + 3].toInt() and 255)

    private suspend fun downloadImage(url: String, revisedPrompt: String): CloudImageResult {
        val parsed = url.toHttpUrlOrNull() ?: error("图片下载地址无效。")
        require(parsed.scheme == "https" || (parsed.scheme == "http" && parsed.host in LOOPBACK_HOSTS)) {
            "图片下载地址必须使用 HTTPS。"
        }
        return executeImageRequest(Request.Builder().url(parsed).get().build()) { response ->
            if (!response.isSuccessful) error("图片下载失败 ${response.code}")
            val body = response.body ?: error("图片下载没有返回内容")
            require(body.contentLength() <= MAX_IMAGE_BYTES) { "下载图片超过大小限制。" }
            val bytes = body.byteStream().use { stream ->
                val buffer = ByteArray(8192)
                val output = java.io.ByteArrayOutputStream()
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    require(read <= MAX_IMAGE_BYTES - output.size()) { "下载图片超过大小限制。" }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            validatedImage(bytes, body.contentType()?.toString().orEmpty(), revisedPrompt, 0)
        }
    }

    private fun findFirstImageUrl(value: Any?): String? {
        return when (value) {
            is JSONObject -> {
                val directKeys = listOf("url", "image_url", "image", "output_url")
                directKeys.firstNotNullOfOrNull { key ->
                    value.optString(key).takeIf { it.startsWith("http", ignoreCase = true) }
                } ?: value.keys().asSequence().firstNotNullOfOrNull { key ->
                    findFirstImageUrl(value.opt(key))
                }
            }
            is JSONArray -> (0 until value.length()).firstNotNullOfOrNull { index -> findFirstImageUrl(value.opt(index)) }
            is String -> value.takeIf { it.startsWith("http", ignoreCase = true) }
            else -> null
        }
    }

    private fun JSONObject.imageError(): String? {
        val error = optJSONObject("error") ?: return null
        return error.optString("message")
            .takeIf { it.isNotBlank() }
            ?: error.optString("type").takeIf { it.isNotBlank() }
            ?: "云端图片接口返回错误"
    }

    private suspend fun <T> executeImageRequest(request: Request, read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use(read)
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun parseProviderError(body: String): String {
        if (body.isBlank()) return "请求失败"
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return body.take(240)
        return root.imageError() ?: root.optString("message", body.take(240))
    }

    private fun openAiImageSize(value: String): String =
        when (value.trim()) {
            "1024x1024", "1024x1536", "1536x1024", "1792x1024", "1024x1792", "512x512", "256x256" -> value.trim()
            "16:9" -> "1536x1024"
            "9:16" -> "1024x1536"
            else -> "1024x1024"
        }

    private fun dashScopeImageSize(value: String): String =
        openAiImageSize(value).replace('x', '*')

    private fun endpointUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val endpointPath = path.trim().trim('/').ifBlank { "images/generations" }
        if (base.endsWith("/$endpointPath")) return base
        val normalizedPath = when {
            base.endsWith("/api/v1") && endpointPath.startsWith("api/v1/") ->
                endpointPath.removePrefix("api/v1/")
            base.endsWith("/v1") && endpointPath.startsWith("v1/") ->
                endpointPath.removePrefix("v1/")
            else -> endpointPath
        }
        return "$base/$normalizedPath"
    }

    private fun dashScopeTaskUrl(baseUrl: String, taskId: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val apiRoot = when {
            "/api/v1/" in base -> base.substringBefore("/api/v1/") + "/api/v1"
            base.endsWith("/api/v1") -> base
            else -> "$base/api/v1"
        }
        return "$apiRoot/tasks/$taskId"
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
        private const val MAX_IMAGE_DIMENSION = 8192
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}

internal fun coalesceCloudChatMessagesByRole(messages: List<ChatMessage>): List<ChatMessage> {
    val result = mutableListOf<ChatMessage>()
    messages.forEach { message ->
        val last = result.lastOrNull()
        if (last != null && last.role == message.role) {
            result[result.lastIndex] = last.copy(
                content = listOf(last.content, message.content)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n"),
                imageAttachments = (last.imageAttachments + message.imageAttachments)
                    .deduplicateVisionAttachments()
            )
        } else {
            result.add(message)
        }
    }
    return result
}

internal fun buildOpenAiChatJson(config: CloudApiConfig, request: ChatRequest): JSONObject {
    val params = request.params
    return JSONObject()
        .put("model", config.chatModel.trim())
        .put("messages", JSONArray(request.messagesJson(multimodal = true)))
        .put("stream", true)
        .put("stream_options", JSONObject().put("include_usage", true))
        .put("temperature", params.temperature.toDouble())
        .put("top_p", params.topP.toDouble())
        .put("presence_penalty", params.presencePenalty.toDouble())
        .put("frequency_penalty", params.frequencyPenalty.toDouble())
        .put("max_tokens", params.effectiveNPredict().coerceIn(1, 32768))
        .also { root ->
            if (params.stopWords.isNotEmpty()) {
                root.put("stop", JSONArray(params.stopWords))
            }
            applyOpenAiCompatibleReasoning(root, config, params)
        }
}

internal fun buildAnthropicChatJson(config: CloudApiConfig, request: ChatRequest): JSONObject {
    val split = splitCloudSystemMessages(request)
    val params = request.params
    return JSONObject()
        .put("model", config.chatModel.trim())
        .put("stream", true)
        .put("max_tokens", params.effectiveNPredict().coerceIn(1, 8192))
        .put("temperature", params.temperature.toDouble())
        .put("top_p", params.topP.toDouble())
        .put("system", split.system)
        .put("messages", split.messages.toAnthropicMessagesJson())
        .also { root ->
            if (params.reasoningMode != ReasoningMode.OFF) {
                root.put(
                    "thinking",
                    JSONObject()
                        .put("type", "enabled")
                        .put("budget_tokens", params.effectiveThinkingBudget().coerceAtLeast(1024))
                )
            }
        }
}

private fun applyOpenAiCompatibleReasoning(
    root: JSONObject,
    config: CloudApiConfig,
    params: GenerationParams
) {
    val thinkingEnabled = params.reasoningMode != ReasoningMode.OFF
    if (!config.baseUrl.contains("openai.com", ignoreCase = true)) {
        root.put("enable_thinking", thinkingEnabled)
        root.put("thinking_budget", params.effectiveThinkingBudget())
        root.put(
            "chat_template_kwargs",
            JSONObject().put("enable_thinking", thinkingEnabled)
        )
    }
}

private fun splitCloudSystemMessages(request: ChatRequest): CloudSplitMessages {
    val system = StringBuilder()
    val messages = mutableListOf<ChatMessage>()
    for (message in request.messagesWithSystemPrompt()) {
        if (message.role == Role.SYSTEM) {
            if (system.isNotBlank()) system.append("\n\n")
            system.append(message.content)
        } else {
            messages.add(message)
        }
    }
    if (messages.isEmpty() || messages.first().role != Role.USER) {
        messages.add(0, ChatMessage(Role.USER, "Continue."))
    }
    return CloudSplitMessages(system.toString(), coalesceCloudChatMessagesByRole(messages))
}

private fun List<ChatMessage>.toAnthropicMessagesJson(): JSONArray {
    val array = JSONArray()
    forEach { message ->
        array.put(
            JSONObject()
                .put("role", if (message.role == Role.ASSISTANT) "assistant" else "user")
                .put("content", message.toAnthropicContentJson())
        )
    }
    return array
}

private fun ChatMessage.toAnthropicContentJson(): Any {
    if (imageAttachments.isEmpty()) return content
    val parts = JSONArray()
    imageAttachments
        .deduplicateVisionAttachments()
        .filter { it.hasInlineData }
        .forEach { attachment ->
            parts.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "source",
                        JSONObject()
                            .put("type", "base64")
                            .put("media_type", attachment.mimeType.ifBlank { "image/jpeg" })
                            .put("data", attachment.plainBase64())
                    )
            )
        }
    if (content.isNotBlank()) {
        parts.put(JSONObject().put("type", "text").put("text", content))
    }
    return if (parts.length() == 0) content else parts
}

private data class CloudSplitMessages(
    val system: String,
    val messages: List<ChatMessage>
)

class OpenAiCompatibleChatProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val clock: RuntimeMonotonicClock = SystemRuntimeMonotonicClock
) {
    private val quickClient = client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .callTimeout(16, TimeUnit.SECONDS)
        .build()

    fun streamChat(
        config: CloudApiConfig,
        request: ChatRequest
    ): Flow<GenerateEvent> = if (config.apiFormat == CloudApiFormat.OPENAI_RESPONSES) {
        streamOpenAiResponsesChat(client, config, request)
    } else flow {
        if (!config.configured) {
            emit(GenerateEvent.Error("云端模型未配置完整。请填写协议、Base URL、模型名和必要的 API Key。", cloudStats(config)))
            return@flow
        }
        val startedAt = clock.nowMs()
        var firstChunkAt: Long? = null
        var completionChars = 0
        val estimatedPromptTokens = estimateCloudPromptTokens(request)
        var usage = CloudTokenUsage()

        executeChatRequest(config, request) { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                emit(GenerateEvent.Error("云端接口错误 ${response.code}: ${parseErrorMessage(errorBody)}", cloudStats(config)))
                return@executeChatRequest
            }
            val body = response.body ?: run {
                emit(GenerateEvent.Error("云端接口没有返回内容", cloudStats(config)))
                return@executeChatRequest
            }
            var sawStreamData = false
            val nonStreamBody = StringBuilder()
            body.byteStream().bufferedReader().use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val rawLine = reader.readLine() ?: break
                    val line = rawLine.trim()
                    if (!line.startsWith("data:")) {
                        if (!sawStreamData && line.isNotBlank() && nonStreamBody.length < MAX_NON_STREAM_BODY_CHARS) {
                            nonStreamBody.append(line).append('\n')
                        }
                        continue
                    }
                    sawStreamData = true
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val chunk = parseStreamChunk(config.apiFormat, data) ?: continue
                    usage = usage.merge(chunk.usage)
                    chunk.error?.let { error ->
                        emit(GenerateEvent.Error(error, cloudStats(config)))
                        return@executeChatRequest
                    }
                    if (chunk.done) break
                    val visibleText = chunk.text
                    val reasoningText = if (request.params.reasoningMode == ReasoningMode.OFF) {
                        ""
                    } else {
                        chunk.reasoning
                    }
                    completionChars += visibleText.length + chunk.reasoning.length
                    if (visibleText.isEmpty() && reasoningText.isEmpty()) continue
                    if (firstChunkAt == null) firstChunkAt = clock.nowMs()
                    emit(
                        GenerateEvent.Chunk(
                            text = visibleText,
                            reasoning = reasoningText,
                            reasoningDurationMs = 0L,
                            stats = cloudStats(
                                config = config,
                                startedAt = startedAt,
                                firstChunkAt = firstChunkAt,
                                estimatedPromptTokens = estimatedPromptTokens,
                                completionChars = completionChars,
                                usage = usage
                            )
                        )
                    )
                }
            }
            if (!sawStreamData) {
                val fallback = parseNonStreamResponse(config.apiFormat, nonStreamBody.toString())
                if (fallback == null) {
                    emit(GenerateEvent.Error("云端接口没有返回可解析的 SSE 或 JSON 内容。请确认协议、模型名和 Base URL。", cloudStats(config)))
                    return@executeChatRequest
                }
                fallback.error?.let { error ->
                    emit(GenerateEvent.Error(error, cloudStats(config)))
                    return@executeChatRequest
                }
                usage = usage.merge(fallback.usage)
                val visibleText = fallback.text
                val reasoningText = if (request.params.reasoningMode == ReasoningMode.OFF) {
                    ""
                } else {
                    fallback.reasoning
                }
                completionChars += visibleText.length + fallback.reasoning.length
                if (visibleText.isNotEmpty() || reasoningText.isNotEmpty()) {
                    if (firstChunkAt == null) firstChunkAt = clock.nowMs()
                    emit(
                        GenerateEvent.Chunk(
                            text = visibleText,
                            reasoning = reasoningText,
                            reasoningDurationMs = 0L,
                            stats = cloudStats(
                                config = config,
                                startedAt = startedAt,
                                firstChunkAt = firstChunkAt,
                                estimatedPromptTokens = estimatedPromptTokens,
                                completionChars = completionChars,
                                usage = usage,
                                streaming = false
                            )
                        )
                    )
                }
                val finishedAt = clock.nowMs()
                emit(
                    GenerateEvent.Done(
                        cloudStats(
                            config = config,
                            startedAt = startedAt,
                            firstChunkAt = firstChunkAt,
                            finishedAt = finishedAt,
                            estimatedPromptTokens = estimatedPromptTokens,
                            completionChars = completionChars,
                            usage = usage,
                            streaming = false
                        )
                    )
                )
                return@executeChatRequest
            }
            val finishedAt = clock.nowMs()
            emit(
                GenerateEvent.Done(
                    cloudStats(
                        config = config,
                        startedAt = startedAt,
                        firstChunkAt = firstChunkAt,
                        finishedAt = finishedAt,
                        estimatedPromptTokens = estimatedPromptTokens,
                        completionChars = completionChars,
                        usage = usage
                    )
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    suspend fun test(config: CloudApiConfig): Result<Unit> = runCatching {
        var failed: String? = null
        streamChat(
            config = config,
            request = ChatRequest(
                messages = listOf(ChatMessage(Role.USER, "ping")),
                params = GenerationParams(nPredict = 8, temperature = 0f, reasoningMode = ReasoningMode.OFF)
            )
        ).collect { event ->
            if (event is GenerateEvent.Error) failed = event.message
        }
        failed?.let { error(it) }
    }

    suspend fun quickTest(config: CloudApiConfig): Result<Unit> = if (config.apiFormat == CloudApiFormat.OPENAI_RESPONSES) {
        quickTestOpenAiResponses(quickClient, config)
    } else withContext(Dispatchers.IO) {
        runCatching {
            if (!config.configured) {
                error("云端模型未配置完整。请填写协议、Base URL、模型名和必要的 API Key。")
            }
            quickClient.newCall(buildQuickTestHttpRequest(config)).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("云端接口错误 ${response.code}: ${parseErrorMessage(body)}")
                }
                val parsedError = runCatching { JSONObject(body).jsonError() }.getOrNull()
                if (!parsedError.isNullOrBlank()) {
                    error(parsedError)
                }
            }
        }
    }

    private fun buildHttpRequest(config: CloudApiConfig, request: ChatRequest): Request =
        when (config.apiFormat) {
            CloudApiFormat.OPENAI_COMPATIBLE -> openAiRequest(config, request)
            CloudApiFormat.ANTHROPIC -> anthropicRequest(config, request)
            CloudApiFormat.OPENAI_RESPONSES -> responsesHttpRequest(config, request)
        }

    private fun buildQuickTestHttpRequest(config: CloudApiConfig): Request =
        when (config.apiFormat) {
            CloudApiFormat.OPENAI_COMPATIBLE -> quickOpenAiRequest(config)
            CloudApiFormat.ANTHROPIC -> quickAnthropicRequest(config)
            CloudApiFormat.OPENAI_RESPONSES -> responsesHttpRequest(config,
                ChatRequest(listOf(ChatMessage(Role.USER, "ping")), GenerationParams(nPredict = 16)), stream = false)
        }

    private fun chatEndpointUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val endpointPath = path.trim().trim('/')
        return if (base.endsWith("/$endpointPath")) base else "$base/$endpointPath"
    }

    private fun anthropicMessagesUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/v1/messages") -> base
            base.contains("anthropic.com", ignoreCase = true) && base.endsWith("/messages") ->
                "${base.removeSuffix("/messages")}/v1/messages"
            base.endsWith("/messages") -> base
            base.endsWith("/v1") -> "$base/messages"
            base.contains("anthropic.com", ignoreCase = true) -> "$base/v1/messages"
            else -> "$base/messages"
        }
    }

    private suspend fun <T> executeChatRequest(
        config: CloudApiConfig,
        request: ChatRequest,
        read: suspend (Response) -> T
    ): T = withCancellableCloudCall(client.newCall(buildHttpRequest(config, request))) { response ->
        if (config.apiFormat == CloudApiFormat.OPENAI_COMPATIBLE && response.code in listOf(400, 422)) {
            val error = response.peekBody(65_536L).string().lowercase()
            // Older compatible gateways may reject the optional usage field. Retry only
            // an explicit validation rejection, before any assistant output was accepted.
            if (("stream_options" in error || "include_usage" in error) &&
                listOf("unsupported", "unknown", "unrecognized", "not allowed", "not permitted", "extra", "not support")
                    .any { it in error }
            ) {
                response.close()
                return@withCancellableCloudCall withCancellableCloudCall(
                    client.newCall(openAiRequest(config, request, includeUsage = false)),
                    read
                )
            }
        }
        read(response)
    }

    private fun openAiRequest(config: CloudApiConfig, request: ChatRequest, includeUsage: Boolean = true): Request {
        val builder = Request.Builder()
            .url(chatEndpointUrl(config.baseUrl, "chat/completions"))
            .addHeader("Accept", "text/event-stream")
            .addCloudApiKeyHeaders(config)
        return builder
            .post(buildOpenAiChatJson(config, request).apply {
                if (!includeUsage) remove("stream_options")
            }.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun anthropicRequest(config: CloudApiConfig, request: ChatRequest): Request =
        Request.Builder()
            .url(anthropicMessagesUrl(config.baseUrl))
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Accept", "text/event-stream")
            .post(buildAnthropicChatJson(config, request).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

    private fun quickOpenAiRequest(config: CloudApiConfig): Request {
        val body = JSONObject()
            .put("model", config.chatModel.trim())
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))
            )
            .put("stream", false)
            .put("temperature", 0.0)
            .put("max_tokens", 1)
        val builder = Request.Builder()
            .url(chatEndpointUrl(config.baseUrl, "chat/completions"))
            .addHeader("Accept", "application/json")
            .addCloudApiKeyHeaders(config)
        return builder.post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
    }

    private fun quickAnthropicRequest(config: CloudApiConfig): Request =
        Request.Builder()
            .url(anthropicMessagesUrl(config.baseUrl))
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Accept", "application/json")
            .post(
                JSONObject()
                    .put("model", config.chatModel.trim())
                    .put("max_tokens", 1)
                    .put("temperature", 0.0)
                    .put(
                        "messages",
                        JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))
                    )
                    .toString()
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()

    private fun parseStreamChunk(format: CloudApiFormat, data: String): CloudChunk? =
        when (format) {
            CloudApiFormat.OPENAI_COMPATIBLE -> parseOpenAiChunk(data)
            CloudApiFormat.ANTHROPIC -> parseAnthropicChunk(data)
            CloudApiFormat.OPENAI_RESPONSES -> error("Responses requires a request-scoped stream decoder")
        }

    private fun parseOpenAiChunk(data: String): CloudChunk? =
        runCatching {
            val root = JSONObject(data)
            root.jsonError()?.let { return CloudChunk(error = it) }
            val choice = root.optJSONArray("choices")?.optJSONObject(0)
            val delta = choice?.optJSONObject("delta") ?: choice?.optJSONObject("message") ?: JSONObject()
            CloudChunk(
                text = delta.cleanString("content"),
                reasoning = delta.cleanString("reasoning_content", "reasoning", "reasoning_text", "thinking", "thinking_content"),
                usage = CloudTokenUsage.parse(root.optJSONObject("usage"))
            )
        }.getOrNull()

    private fun parseAnthropicChunk(data: String): CloudChunk? =
        runCatching {
            val root = JSONObject(data)
            when (root.optString("type")) {
                "message_start" -> CloudChunk(usage = CloudTokenUsage.parse(
                    root.optJSONObject("message")?.optJSONObject("usage"), anthropic = true).copy(output = null))
                "message_delta" -> CloudChunk(usage = CloudTokenUsage.parse(root.optJSONObject("usage"), anthropic = true))
                "content_block_delta" -> {
                    val delta = root.optJSONObject("delta") ?: JSONObject()
                    when (delta.optString("type")) {
                        "text_delta" -> CloudChunk(text = delta.cleanString("text"))
                        "thinking_delta" -> CloudChunk(reasoning = delta.cleanString("thinking"))
                        else -> CloudChunk()
                    }
                }
                "message_stop" -> CloudChunk(done = true)
                "error" -> CloudChunk(error = root.optJSONObject("error")?.optString("message") ?: "Anthropic stream error")
                else -> CloudChunk()
            }
        }.getOrNull()

    private fun parseNonStreamResponse(format: CloudApiFormat, body: String): CloudChunk? {
        val cleanBody = body.trim()
        if (cleanBody.isBlank()) return null
        return when (format) {
            CloudApiFormat.OPENAI_COMPATIBLE -> parseOpenAiResponse(cleanBody)
            CloudApiFormat.ANTHROPIC -> parseAnthropicResponse(cleanBody)
            CloudApiFormat.OPENAI_RESPONSES -> error("Responses requires a request-scoped stream decoder")
        }
    }

    private fun parseOpenAiResponse(body: String): CloudChunk? =
        runCatching {
            val root = JSONObject(body)
            root.jsonError()?.let { return CloudChunk(error = it) }
            val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return null
            val message = choice.optJSONObject("message") ?: choice.optJSONObject("delta") ?: JSONObject()
            CloudChunk(
                text = message.cleanString("content").ifEmpty { choice.cleanString("text") },
                reasoning = message.cleanString("reasoning_content", "reasoning", "reasoning_text", "thinking", "thinking_content"),
                usage = CloudTokenUsage.parse(root.optJSONObject("usage"))
            )
        }.getOrNull()

    private fun parseAnthropicResponse(body: String): CloudChunk? =
        runCatching {
            val root = JSONObject(body)
            root.jsonError()?.let { return CloudChunk(error = it) }
            val usage = CloudTokenUsage.parse(root.optJSONObject("usage"), anthropic = true)
            val content = root.optJSONArray("content") ?: return CloudChunk(text = root.optString("content"), usage = usage)
            val text = StringBuilder()
            val reasoning = StringBuilder()
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                when (item.optString("type")) {
                    "text" -> text.append(item.cleanString("text"))
                    "thinking" -> reasoning.append(item.cleanString("thinking"))
                }
            }
            CloudChunk(text = text.toString(), reasoning = reasoning.toString(), usage = usage)
        }.getOrNull()

    private fun JSONObject.jsonError(): String? {
        val error = optJSONObject("error") ?: return null
        return error.cleanString("message")
            .takeIf { it.isNotBlank() }
            ?: error.cleanString("type").takeIf { it.isNotBlank() }
            ?: "云端接口返回错误"
    }

    private fun JSONObject.cleanString(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key ->
            if (!has(key) || isNull(key)) {
                null
            } else {
                opt(key)
                    ?.toString()
                    ?.takeIf { it.isNotEmpty() }
            }
        }.orEmpty()

    private fun parseErrorMessage(body: String): String {
        if (body.isBlank()) return "请求失败"
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return body.take(240)
        val error = json.optJSONObject("error")
        return error?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json.optString("message", body.take(240))
    }

    private fun cloudStats(
        config: CloudApiConfig,
        startedAt: Long = clock.nowMs(),
        firstChunkAt: Long? = null,
        finishedAt: Long = clock.nowMs(),
        estimatedPromptTokens: Int = 0,
        completionChars: Int = 0,
        usage: CloudTokenUsage = CloudTokenUsage(),
        streaming: Boolean = true
    ): RuntimeStats {
        val decodeMs = (finishedAt - (firstChunkAt ?: startedAt)).coerceAtLeast(0L)
        val completionTokens = usage.output ?: estimateCloudTokens(completionChars)
        val promptTokens = usage.input ?: estimatedPromptTokens
        val totalMs = (finishedAt - startedAt).coerceAtLeast(0L)
        val e2eTps = if (totalMs > 0L) completionTokens * 1000.0 / totalMs else 0.0
        val tps = if (streaming && decodeMs > 0L) completionTokens * 1000.0 / decodeMs else e2eTps
        return RuntimeStats(
            loaded = true,
            modelPath = "${config.apiFormat.label}/${config.chatModel}",
            backend = "cloud",
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            ttftMs = firstChunkAt?.let { (it - startedAt).coerceAtLeast(0L) } ?: 0L,
            decodeMs = decodeMs,
            decodeTps = tps,
            e2eTps = e2eTps,
            promptTokensEstimated = usage.input == null,
            completionTokensEstimated = usage.output == null
        )
    }

    private data class CloudChunk(
        val text: String = "",
        val reasoning: String = "",
        val done: Boolean = false,
        val error: String? = null,
        val usage: CloudTokenUsage = CloudTokenUsage()
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_NON_STREAM_BODY_CHARS = 1_048_576
    }
}
