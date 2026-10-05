package com.muyuchat.mca

import android.util.Base64
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
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

/**
 * Cloud image generation provider (P7/S24), moved verbatim out of CloudChatProvider.kt with its
 * record types (CloudImageResult/Request/Capabilities/BatchResult). Consumed by MainViewModel and
 * the cloud image tests in the same package; the chat transport stays in CloudChatProvider.kt.
 */

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
