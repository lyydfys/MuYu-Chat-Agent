package com.muyuchat.mca

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Model-id discovery for the cloud **image** engine editor.
 *
 * The chat editor already lists models through the chat transport; until now the image editor
 * had no equivalent, so users had to type image model ids from memory. The listing endpoint is
 * the same OpenAI-compatible `GET {base}/models`; only the base-URL normalisation differs, because
 * the image form keeps its own `imageApiFormat` and DashScope's native host exposes the model
 * list under its OpenAI-compatible prefix.
 *
 * Nothing here publishes UI state: the caller owns the in-progress flag and renders the result
 * locally, exactly like the chat variant.
 */
internal suspend fun listCloudImageModels(config: CloudApiConfig): Result<List<String>> =
    withContext(Dispatchers.IO) {
        runCatching {
            val normalized = config.normalizedForImageRequest()
            val url = cloudImageModelsUrl(normalized)
            if (url == null) {
                error("请先填写 Base URL，再获取图像模型列表。")
            }
            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/json")
                .addCloudApiKeyHeaders(normalized)
                .get()
                .build()
            modelCatalogClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("云端接口错误 ${response.code}: ${parseModelCatalogError(body)}")
                }
                parseCloudImageModelsResponse(body)
            }
        }
    }

/**
 * Builds the model-list URL for the current image protocol.
 *
 * DashScope's native base URL (`https://dashscope.aliyuncs.com`) serves the request path
 * `/api/v1/...` but only exposes model ids through its OpenAI-compatible prefix
 * (`/compatible-mode/v1/models`); asking the native root would answer 404. An empty Base URL is
 * reported as `null` so the caller can show the same hint the chat editor shows.
 */
internal fun cloudImageModelsUrl(config: CloudApiConfig): String? {
    val base = config.baseUrl.trim().trimEnd('/')
    if (base.isBlank()) return null
    if (base.endsWith("/models")) return base
    val dashScopeCompat = config.imageApiFormat == CloudImageApiFormat.DASHSCOPE_IMAGE &&
        base.contains("dashscope", ignoreCase = true) &&
        !base.contains("/compatible-mode", ignoreCase = true)
    if (dashScopeCompat) return "$base/compatible-mode/v1/models"
    return "$base/models"
}

/** Accepts `data[].id` (OpenAI) plus the lenient `models[]`/plain-string shapes gateways return. */
internal fun parseCloudImageModelsResponse(body: String): List<String> {
    if (body.isBlank()) error("接口没有返回模型列表。")
    val root = runCatching { JSONObject(body) }
        .getOrElse { JSONObject().put("data", JSONArray().put(body.trim())) }
    val models = mutableListOf<String>()
    fun collect(array: JSONArray?) {
        if (array == null) return
        for (index in 0 until array.length()) {
            when (val item = array.opt(index)) {
                is JSONObject -> item.optString("id")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?.let(models::add)

                is String -> item.trim().takeIf { it.isNotBlank() }?.let(models::add)
            }
        }
    }
    collect(root.optJSONArray("data"))
    collect(root.optJSONArray("models"))
    val unique = models.distinct().take(MAX_LISTED_IMAGE_MODELS)
    if (unique.isEmpty()) {
        error("接口返回的模型列表为空，请手动填写图像模型名。")
    }
    return unique
}

private fun parseModelCatalogError(body: String): String {
    if (body.isBlank()) return "请求失败"
    val json = runCatching { JSONObject(body) }.getOrNull() ?: return body.take(240)
    val error = json.optJSONObject("error")
    return error?.optString("message")?.takeIf { it.isNotBlank() }
        ?: json.optString("message", body.take(240))
}

private val modelCatalogClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .callTimeout(60, TimeUnit.SECONDS)
    .build()

private const val MAX_LISTED_IMAGE_MODELS = 256
