package com.muyuchat.feature.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

internal suspend fun runLocalApiModelsSelfTest(baseUrl: String, apiKey: String): String =
    withContext(Dispatchers.IO) {
        val url = "${baseUrl.normalizedApiBase()}/models"
        runCatching {
            val response = httpRequest(
                method = "GET",
                url = url,
                apiKey = apiKey
            )
            response.toSelfTestMessage(
                successPrefix = "模型列表请求成功",
                knownFailureHint = "请确认本机调用已开启，Base URL 填到 /v1，API Key 正确。"
            )
        }.getOrElse { error ->
            "请求失败：${error.message ?: error.javaClass.simpleName}\n$url"
        }
    }

internal suspend fun runLocalApiChatSelfTest(
    baseUrl: String,
    apiKey: String,
    loadedModelId: String?
): String =
    withContext(Dispatchers.IO) {
        val url = "${baseUrl.normalizedApiBase()}/chat/completions"
        val modelField = localApiSelfTestModelField(loadedModelId)
        val body = """
            {$modelField"messages":[{"role":"user","content":"ping"}],"max_tokens":8,"temperature":0,"stream":true}
        """.trimIndent()
        runCatching {
            val response = httpRequest(
                method = "POST",
                url = url,
                apiKey = apiKey,
                contentType = "application/json; charset=utf-8",
                body = body,
                accept = "text/event-stream"
            )
            if (response.code in 200..299 && !response.body.contains("data: [DONE]")) {
                return@runCatching "聊天接口已连接，但流式响应缺少 [DONE] 终止帧，请检查本地 API 服务状态。"
            }
            response.toSelfTestMessage(
                successPrefix = "流式聊天接口请求成功",
                knownFailureHint = "如果返回 503，说明接口已连通但本地聊天模型尚未加载。"
            )
        }.getOrElse { error ->
            "请求失败：${error.message ?: error.javaClass.simpleName}\n$url"
        }
    }

internal suspend fun runLocalApiVisionSelfTest(
    baseUrl: String,
    apiKey: String,
    loadedModelId: String?
): String =
    withContext(Dispatchers.IO) {
        val url = "${baseUrl.normalizedApiBase()}/chat/completions"
        val modelField = localApiSelfTestModelField(loadedModelId)
        val body = """
            {
              $modelField
              "messages": [
                {
                  "role": "user",
                  "content": [
                    {"type": "text", "text": "请用一句中文说明这张测试图。"},
                    {
                      "type": "image_url",
                      "image_url": {
                        "url": "data:image/png;base64,$LOCAL_API_VISION_SELF_TEST_PNG_BASE64",
                        "detail": "auto"
                      }
                    }
                  ]
                }
              ],
              "max_tokens": 48,
              "temperature": 0,
              "stream": false
            }
        """.trimIndent()
        runCatching {
            val response = httpRequest(
                method = "POST",
                url = url,
                apiKey = apiKey,
                contentType = "application/json; charset=utf-8",
                body = body,
                readTimeoutMillis = 120_000
            )
            response.toSelfTestMessage(
                successPrefix = "识图接口请求成功",
                knownFailureHint = "若提示未启用识图，请先加载 MNN 多模态包，或加载多模态 GGUF 并绑定匹配 mmproj。"
            )
        }.getOrElse { error ->
            "请求失败：${error.message ?: error.javaClass.simpleName}\n$url"
        }
    }

internal fun localApiSelfTestModelField(loadedModelId: String?): String = loadedModelId
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { "\"model\":${it.asJsonString()}," }
    .orEmpty()

private fun String.asJsonString(): String = buildString(length + 2) {
    append('\"')
    this@asJsonString.forEach { character ->
        when (character) {
            '\"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('\"')
}

private data class ApiHttpResponse(
    val code: Int,
    val body: String
)

private fun httpRequest(
    method: String,
    url: String,
    apiKey: String,
    contentType: String? = null,
    body: String? = null,
    accept: String = "application/json",
    readTimeoutMillis: Int = 20_000
): ApiHttpResponse {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method.uppercase()
        connectTimeout = 5_000
        readTimeout = readTimeoutMillis
        setRequestProperty("Accept", accept)
        if (apiKey.isNotBlank()) {
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        if (contentType != null) {
            setRequestProperty("Content-Type", contentType)
        }
        if (body != null) {
            doOutput = true
        }
    }
    return connection.useConnection {
        if (body != null) {
            outputStream.use { stream ->
                stream.write(body.toByteArray(Charsets.UTF_8))
            }
        }
        val code = responseCode
        val stream = if (code in 200..299) inputStream else errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        ApiHttpResponse(code, text)
    }
}

private inline fun <T> HttpURLConnection.useConnection(block: HttpURLConnection.() -> T): T =
    try {
        block()
    } finally {
        disconnect()
    }

private fun ApiHttpResponse.toSelfTestMessage(successPrefix: String, knownFailureHint: String): String {
    val shortBody = body
        .lineSequence()
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .let { if (it.length > 220) it.take(217) + "..." else it }
        .ifBlank { "无响应正文" }
    return if (code in 200..299) {
        "$successPrefix：HTTP $code\n$shortBody"
    } else {
        "请求返回 HTTP $code\n$shortBody\n$knownFailureHint"
    }
}

internal fun String.normalizedApiBase(): String {
    var base = trim().trimEnd('/')
    listOf("/chat/completions", "/completions", "/models").forEach { suffix ->
        if (base.endsWith(suffix)) {
            base = base.removeSuffix(suffix).trimEnd('/')
        }
    }
    base = base.removeSuffix("/v1").trimEnd('/')
    return "$base/v1"
}
