package com.muyuchat.mca

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

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
    ANTHROPIC("Anthropic Messages", "https://api.anthropic.com/v1", "claude-3-5-sonnet-latest")
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

internal fun CloudApiConfig.normalizedForImageRequest(): CloudApiConfig {
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
            if (current in imageProtocolLabels && current != inferredImageFormat.label) inferredImageFormat.label else current
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

data class CloudModelRecord(
    val id: String = UUID.randomUUID().toString(),
    val kind: CloudModelKind,
    val apiFormat: CloudApiFormat,
    val providerName: String,
    val displayName: String,
    val baseUrl: String,
    val apiKey: String,
    val modelName: String,
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
            imageApiFormat = imageApiFormat,
            imageModel = modelName,
            imageSize = imageSize,
            imageEndpointPath = imageEndpointPath
        )
}

class CloudApiStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mca_cloud_api", Context.MODE_PRIVATE)

    fun load(): CloudApiConfig {
        val format = parseFormat(prefs.getString(KEY_API_FORMAT, null))
        val imageFormat = CloudImageApiFormat.from(prefs.getString(KEY_IMAGE_API_FORMAT, null))
        return CloudApiConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            apiFormat = format,
            providerName = prefs.getString(KEY_PROVIDER_NAME, null).orEmpty().ifBlank { format.label },
            displayName = prefs.getString(KEY_DISPLAY_NAME, null).orEmpty().ifBlank { "Cloud Chat" },
            baseUrl = prefs.getString(KEY_BASE_URL, null).orEmpty().ifBlank { format.defaultBaseUrl },
            apiKey = decryptApiKey(),
            chatModel = prefs.getString(KEY_CHAT_MODEL, null).orEmpty().ifBlank { format.defaultModel },
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
        return CloudModelRecord(
            id = optString("id").ifBlank { UUID.randomUUID().toString() },
            kind = parseKind(optString("kind")),
            apiFormat = parseFormat(optString("apiFormat")),
            providerName = optString("providerName"),
            displayName = optString("displayName"),
            baseUrl = optString("baseUrl"),
            apiKey = if (cipher != null && iv != null) decryptApiKeyPayload(cipher, iv) else optString("apiKey"),
            modelName = optString("modelName"),
            imageApiFormat = parseImageFormat(optString("imageApiFormat", optString("apiFormat"))),
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
        ).normalizedForImageRequest()

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

class CloudImageProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    suspend fun generate(config: CloudApiConfig, prompt: String): CloudImageResult = withContext(Dispatchers.IO) {
        val requestConfig = config.normalizedForImageRequest()
        if (!requestConfig.imageConfigured) {
            error("当前云端 API 未配置可用的生图协议。请启用 OpenAI Images、DashScope Image 或自定义路径，并填写生图模型。")
        }
        val request = when (requestConfig.imageApiFormat) {
            CloudImageApiFormat.OPENAI_IMAGES -> openAiImageRequest(requestConfig, prompt)
            CloudImageApiFormat.DASHSCOPE_IMAGE -> dashScopeImageRequest(requestConfig, prompt)
            CloudImageApiFormat.CUSTOM_PATH -> customImageRequest(requestConfig, prompt)
        }
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error(
                    "生图接口错误 ${response.code}: ${parseProviderError(body)}。" +
                        "协议：${requestConfig.imageApiFormat.label}，路径：${request.url.encodedPath}"
                )
            }
            when (requestConfig.imageApiFormat) {
                CloudImageApiFormat.OPENAI_IMAGES -> parseOpenAiImageResponse(body)
                CloudImageApiFormat.DASHSCOPE_IMAGE -> parseDashScopeImageResponse(requestConfig, body)
                CloudImageApiFormat.CUSTOM_PATH -> parseFlexibleImageResponse(requestConfig, body)
            }
        }
    }

    private fun openAiImageRequest(config: CloudApiConfig, prompt: String): Request {
        val root = JSONObject()
            .put("model", config.imageModel.trim())
            .put("prompt", prompt)
            .put("n", 1)
            .put("size", openAiImageSize(config.imageSize))
        if (!config.imageModel.startsWith("gpt-image", ignoreCase = true)) {
            root.put("response_format", "b64_json")
        }
        val builder = Request.Builder()
            .url(endpointUrl(config.baseUrl, config.imageEndpointPathForRequest()))
            .addHeader("Accept", "application/json")
        if (config.apiKey.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer ${config.apiKey}")
            if (config.baseUrl.isMiMoBaseUrl()) {
                builder.addHeader("api-key", config.apiKey)
            }
        }
        return builder.post(root.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
    }

    private fun dashScopeImageRequest(config: CloudApiConfig, prompt: String): Request {
        val root = JSONObject()
            .put("model", config.imageModel.trim())
            .put(
                "input",
                JSONObject().put(
                    "messages",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", JSONArray().put(JSONObject().put("text", prompt)))
                    )
                )
            )
            .put(
                "parameters",
                JSONObject()
                    .put("size", dashScopeImageSize(config.imageSize))
                    .put("n", 1)
            )
        return Request.Builder()
            .url(endpointUrl(config.baseUrl, config.imageEndpointPathForRequest()))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Accept", "application/json")
            .post(root.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun customImageRequest(config: CloudApiConfig, prompt: String): Request {
        val root = JSONObject()
            .put("model", config.imageModel.trim())
            .put("prompt", prompt)
            .put("n", 1)
            .put("size", openAiImageSize(config.imageSize))
        val builder = Request.Builder()
            .url(endpointUrl(config.baseUrl, config.imageEndpointPathForRequest()))
            .addHeader("Accept", "application/json")
        if (config.apiKey.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer ${config.apiKey}")
            if (config.baseUrl.isMiMoBaseUrl()) {
                builder.addHeader("api-key", config.apiKey)
            }
        }
        return builder.post(root.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
    }

    private fun parseOpenAiImageResponse(body: String): CloudImageResult {
        val root = JSONObject(body)
        root.imageError()?.let { error(it) }
        val item = root.optJSONArray("data")?.optJSONObject(0) ?: error("生图接口未返回图片数据")
        val revisedPrompt = item.optString("revised_prompt", item.optString("revisedPrompt"))
        val b64 = item.optString("b64_json", item.optString("b64Json"))
        if (b64.isNotBlank()) {
            return CloudImageResult(
                bytes = Base64.decode(b64, Base64.DEFAULT),
                mimeType = item.optString("mime_type", "image/png"),
                revisedPrompt = revisedPrompt
            )
        }
        val url = item.optString("url")
        if (url.isNotBlank()) {
            return downloadImage(url, revisedPrompt)
        }
        error("生图接口未返回 b64_json 或 url")
    }

    private fun parseDashScopeImageResponse(config: CloudApiConfig, body: String): CloudImageResult {
        val root = JSONObject(body)
        root.imageError()?.let { error(it) }
        val taskId = root.optJSONObject("output")?.optString("task_id").orEmpty()
        if (taskId.isNotBlank()) {
            return waitForDashScopeTask(config, taskId)
        }
        return parseFlexibleImageResponse(config, body)
    }

    private fun waitForDashScopeTask(config: CloudApiConfig, taskId: String): CloudImageResult {
        val taskUrl = dashScopeTaskUrl(config.baseUrl, taskId)
        repeat(60) {
            Thread.sleep(1500)
            client.newCall(
                Request.Builder()
                    .url(taskUrl)
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .addHeader("Accept", "application/json")
                    .get()
                    .build()
            ).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("DashScope task ${response.code}: ${parseProviderError(body)}")
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
        }
        error("DashScope image task timed out")
    }

    private fun parseFlexibleImageResponse(config: CloudApiConfig, body: String): CloudImageResult {
        val root = JSONObject(body)
        root.imageError()?.let { error(it) }
        root.optJSONArray("data")?.optJSONObject(0)?.let { item ->
            val revisedPrompt = item.optString("revised_prompt", item.optString("revisedPrompt"))
            val b64 = item.optString("b64_json", item.optString("b64Json"))
            if (b64.isNotBlank()) {
                return CloudImageResult(
                    bytes = Base64.decode(b64, Base64.DEFAULT),
                    mimeType = item.optString("mime_type", "image/png"),
                    revisedPrompt = revisedPrompt
                )
            }
            item.optString("url").takeIf { it.isNotBlank() }?.let { return downloadImage(it, revisedPrompt) }
        }
        findFirstImageUrl(root)?.let { return downloadImage(it, root.optString("revised_prompt")) }
        error("生图接口未返回可下载图片。协议：${config.imageApiFormat.label}")
    }

    private fun downloadImage(url: String, revisedPrompt: String): CloudImageResult {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) error("图片下载失败 ${response.code}")
            val body = response.body ?: error("图片下载没有返回内容")
            return CloudImageResult(
                bytes = body.bytes(),
                mimeType = body.contentType()?.toString() ?: "image/png",
                revisedPrompt = revisedPrompt
            )
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
    }
}

class OpenAiCompatibleChatProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
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
    ): Flow<GenerateEvent> = flow {
        if (!config.configured) {
            emit(GenerateEvent.Error("云端模型未配置完整。请填写协议、Base URL、模型名和必要的 API Key。", cloudStats(config)))
            return@flow
        }
        val startedAt = System.currentTimeMillis()
        var firstChunkAt = 0L
        var completionChars = 0
        val promptChars = request.messages.sumOf { it.content.length }

        client.newCall(buildHttpRequest(config, request)).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                emit(GenerateEvent.Error("云端接口错误 ${response.code}: ${parseErrorMessage(errorBody)}", cloudStats(config)))
                return@flow
            }
            val body = response.body ?: run {
                emit(GenerateEvent.Error("云端接口没有返回内容", cloudStats(config)))
                return@flow
            }
            var sawStreamData = false
            val nonStreamBody = StringBuilder()
            body.byteStream().bufferedReader().use { reader ->
                while (true) {
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
                    chunk.error?.let { error ->
                        emit(GenerateEvent.Error(error, cloudStats(config)))
                        return@flow
                    }
                    if (chunk.done) break
                    val visibleText = chunk.text.cleanProviderDelta()
                    val reasoningText = if (request.params.reasoningMode == ReasoningMode.OFF) {
                        ""
                    } else {
                        chunk.reasoning.cleanProviderDelta()
                    }
                    if (visibleText.isBlank() && reasoningText.isBlank()) continue
                    if (firstChunkAt == 0L) firstChunkAt = System.currentTimeMillis()
                    completionChars += visibleText.length
                    emit(
                        GenerateEvent.Chunk(
                            text = visibleText,
                            reasoning = reasoningText,
                            reasoningDurationMs = 0L,
                            stats = cloudStats(
                                config = config,
                                startedAt = startedAt,
                                firstChunkAt = firstChunkAt,
                                promptChars = promptChars,
                                completionChars = completionChars
                            )
                        )
                    )
                }
            }
            if (!sawStreamData) {
                val fallback = parseNonStreamResponse(config.apiFormat, nonStreamBody.toString())
                if (fallback == null) {
                    emit(GenerateEvent.Error("云端接口没有返回可解析的 SSE 或 JSON 内容。请确认协议、模型名和 Base URL。", cloudStats(config)))
                    return@flow
                }
                fallback.error?.let { error ->
                    emit(GenerateEvent.Error(error, cloudStats(config)))
                    return@flow
                }
                val visibleText = fallback.text.cleanProviderDelta()
                val reasoningText = if (request.params.reasoningMode == ReasoningMode.OFF) {
                    ""
                } else {
                    fallback.reasoning.cleanProviderDelta()
                }
                if (visibleText.isNotBlank() || reasoningText.isNotBlank()) {
                    if (firstChunkAt == 0L) firstChunkAt = System.currentTimeMillis()
                    completionChars += visibleText.length
                    emit(
                        GenerateEvent.Chunk(
                            text = visibleText,
                            reasoning = reasoningText,
                            reasoningDurationMs = 0L,
                            stats = cloudStats(
                                config = config,
                                startedAt = startedAt,
                                firstChunkAt = firstChunkAt,
                                promptChars = promptChars,
                                completionChars = completionChars
                            )
                        )
                    )
                }
                val finishedAt = System.currentTimeMillis()
                emit(
                    GenerateEvent.Done(
                        cloudStats(
                            config = config,
                            startedAt = startedAt,
                            firstChunkAt = firstChunkAt,
                            finishedAt = finishedAt,
                            promptChars = promptChars,
                            completionChars = completionChars
                        )
                    )
                )
                return@flow
            }
            val finishedAt = System.currentTimeMillis()
            emit(
                GenerateEvent.Done(
                    cloudStats(
                        config = config,
                        startedAt = startedAt,
                        firstChunkAt = firstChunkAt,
                        finishedAt = finishedAt,
                        promptChars = promptChars,
                        completionChars = completionChars
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

    suspend fun quickTest(config: CloudApiConfig): Result<Unit> = withContext(Dispatchers.IO) {
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
        }

    private fun buildQuickTestHttpRequest(config: CloudApiConfig): Request =
        when (config.apiFormat) {
            CloudApiFormat.OPENAI_COMPATIBLE -> quickOpenAiRequest(config)
            CloudApiFormat.ANTHROPIC -> quickAnthropicRequest(config)
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

    private fun openAiRequest(config: CloudApiConfig, request: ChatRequest): Request {
        val builder = Request.Builder()
            .url(chatEndpointUrl(config.baseUrl, "chat/completions"))
            .addHeader("Accept", "text/event-stream")
        if (config.apiKey.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer ${config.apiKey}")
        }
        return builder
            .post(buildOpenAiJson(config, request).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun anthropicRequest(config: CloudApiConfig, request: ChatRequest): Request =
        Request.Builder()
            .url(anthropicMessagesUrl(config.baseUrl))
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Accept", "text/event-stream")
            .post(buildAnthropicJson(config, request).toString().toRequestBody(JSON_MEDIA_TYPE))
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
        if (config.apiKey.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer ${config.apiKey}")
        }
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

    private fun buildOpenAiJson(config: CloudApiConfig, request: ChatRequest): JSONObject {
        val params = request.params
        return JSONObject()
            .put("model", config.chatModel.trim())
            .put("messages", JSONArray(request.messagesJson(multimodal = true)))
            .put("stream", true)
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

    private fun buildAnthropicJson(config: CloudApiConfig, request: ChatRequest): JSONObject {
        val split = splitSystemMessages(request)
        val params = request.params
        return JSONObject()
            .put("model", config.chatModel.trim())
            .put("stream", true)
            .put("max_tokens", params.effectiveNPredict().coerceIn(1, 8192))
            .put("temperature", params.temperature.toDouble())
            .put("top_p", params.topP.toDouble())
            .put("system", split.system)
            .put("messages", split.messages.toAnthropicMessages())
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

    private fun parseStreamChunk(format: CloudApiFormat, data: String): CloudChunk? =
        when (format) {
            CloudApiFormat.OPENAI_COMPATIBLE -> parseOpenAiChunk(data)
            CloudApiFormat.ANTHROPIC -> parseAnthropicChunk(data)
        }

    private fun parseOpenAiChunk(data: String): CloudChunk? =
        runCatching {
            val root = JSONObject(data)
            val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return null
            val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: JSONObject()
            CloudChunk(
                text = delta.cleanString("content"),
                reasoning = delta.cleanString("reasoning_content", "reasoning", "reasoning_text", "thinking", "thinking_content")
            )
        }.getOrNull()

    private fun parseAnthropicChunk(data: String): CloudChunk? =
        runCatching {
            val root = JSONObject(data)
            when (root.optString("type")) {
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
        }
    }

    private fun parseOpenAiResponse(body: String): CloudChunk? =
        runCatching {
            val root = JSONObject(body)
            root.jsonError()?.let { return CloudChunk(error = it) }
            val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: return null
            val message = choice.optJSONObject("message") ?: choice.optJSONObject("delta") ?: JSONObject()
            CloudChunk(
                text = message.cleanString("content").ifBlank { choice.cleanString("text") },
                reasoning = message.cleanString("reasoning_content", "reasoning", "reasoning_text", "thinking", "thinking_content")
            )
        }.getOrNull()

    private fun parseAnthropicResponse(body: String): CloudChunk? =
        runCatching {
            val root = JSONObject(body)
            root.jsonError()?.let { return CloudChunk(error = it) }
            val content = root.optJSONArray("content") ?: return CloudChunk(text = root.optString("content"))
            val text = StringBuilder()
            val reasoning = StringBuilder()
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                when (item.optString("type")) {
                    "text" -> text.append(item.cleanString("text"))
                    "thinking" -> reasoning.append(item.cleanString("thinking"))
                }
            }
            CloudChunk(text = text.toString(), reasoning = reasoning.toString())
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
                    ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            }
        }.orEmpty()

    private fun String.cleanProviderDelta(): String =
        takeUnless { it.equals("null", ignoreCase = true) }.orEmpty()

    private fun splitSystemMessages(request: ChatRequest): SplitMessages {
        val system = StringBuilder()
        val messages = mutableListOf<ChatMessage>()
        for (message in request.messages) {
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
        return SplitMessages(system.toString(), messages.coalescedByRole())
    }

    private fun List<ChatMessage>.coalescedByRole(): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        forEach { message ->
            val last = result.lastOrNull()
            if (last != null && last.role == message.role) {
                result[result.lastIndex] = last.copy(content = last.content + "\n\n" + message.content)
            } else {
                result.add(message)
            }
        }
        return result
    }

    private fun List<ChatMessage>.toAnthropicMessages(): JSONArray {
        val array = JSONArray()
        forEach { message ->
            array.put(
                JSONObject()
                    .put("role", if (message.role == Role.ASSISTANT) "assistant" else "user")
                    .put("content", message.toAnthropicContent())
            )
        }
        return array
    }

    private fun ChatMessage.toAnthropicContent(): Any {
        if (imageAttachments.isEmpty()) return content
        val parts = JSONArray()
        imageAttachments
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

    private fun parseErrorMessage(body: String): String {
        if (body.isBlank()) return "请求失败"
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return body.take(240)
        val error = json.optJSONObject("error")
        return error?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json.optString("message", body.take(240))
    }

    private fun cloudStats(
        config: CloudApiConfig,
        startedAt: Long = System.currentTimeMillis(),
        firstChunkAt: Long = 0L,
        finishedAt: Long = System.currentTimeMillis(),
        promptChars: Int = 0,
        completionChars: Int = 0
    ): RuntimeStats {
        val decodeMs = (finishedAt - (firstChunkAt.takeIf { it > 0L } ?: startedAt)).coerceAtLeast(0L)
        val completionTokens = (completionChars / 4).coerceAtLeast(0)
        val promptTokens = (promptChars / 4).coerceAtLeast(0)
        val tps = if (decodeMs > 0L && completionTokens > 0) completionTokens * 1000.0 / decodeMs else 0.0
        return RuntimeStats(
            loaded = true,
            modelPath = "${config.apiFormat.label}/${config.chatModel}",
            backend = "cloud",
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            ttftMs = if (firstChunkAt > 0L) firstChunkAt - startedAt else 0L,
            decodeMs = decodeMs,
            decodeTps = tps,
            e2eTps = tps
        )
    }

    private data class SplitMessages(
        val system: String,
        val messages: List<ChatMessage>
    )

    private data class CloudChunk(
        val text: String = "",
        val reasoning: String = "",
        val done: Boolean = false,
        val error: String? = null
    )

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_NON_STREAM_BODY_CHARS = 1_048_576
    }
}
