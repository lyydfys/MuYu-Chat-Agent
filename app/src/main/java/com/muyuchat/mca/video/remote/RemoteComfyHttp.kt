package com.muyuchat.mca.video.remote

import com.muyuchat.core.video.contract.VideoRemoteEndpointBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal const val REMOTE_MAX_REQUEST_BODY_BYTES = 4 * 1024 * 1024
internal const val REMOTE_MAX_JSON_RESPONSE_BYTES = 4 * 1024 * 1024
internal const val REMOTE_MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024

internal val REMOTE_JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

internal fun isRedirectStatus(statusCode: Int): Boolean = statusCode in 300..399

/**
 * Redirects are always disabled on the transport we actually use, including when tests or a
 * future composition root inject their own client. A 3xx is a typed failure, never a silent hop to
 * another origin that could also carry the Authorization header.
 */
internal fun secureClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .build()

internal fun Request.Builder.withRemoteAuth(apiKey: String?): Request.Builder {
    if (apiKey != null) {
        require(apiKey.none { it == '\r' || it == '\n' }) { "invalid credential" }
        header("Authorization", "Bearer $apiKey")
    }
    return this
}

/**
 * Validated endpoint of one explicitly selected remote ComfyUI server.
 *
 * The original binding URL is parsed once: only http/https, no userinfo, no query and no fragment.
 * All later paths are appended to the SAME normalized base URL; neither a workflow nor a response
 * can introduce a second download host. The API key lives only in this in-memory value and is only
 * rendered as an Authorization header.
 */
internal data class RemoteComfyEndpoint(
    val endpointId: String,
    val baseUrl: String,
    val apiKey: String?,
    val allowTargetedCancel: Boolean,
) {

    /** Defensive redaction: this value must never render the bearer credential if logged. */
    override fun toString(): String = buildString {
        append("RemoteComfyEndpoint(endpointId=")
        append(endpointId)
        append(", baseUrl=")
        append(baseUrl)
        append(", apiKey=")
        append(if (apiKey == null) "null" else "***")
        append(", allowTargetedCancel=")
        append(allowTargetedCancel)
        append(')')
    }

    fun requestUrl(vararg segments: String): HttpUrl {
        val builder = baseUrl.toHttpUrl().newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    fun webSocketUrl(clientId: String): String {
        val base = baseUrl.toHttpUrl()
        val httpUrl = base.newBuilder()
            .addPathSegment("ws")
            .addQueryParameter("clientId", clientId)
            .build()
            .toString()
        return if (base.isHttps) {
            httpUrl.replaceFirst("https://", "wss://")
        } else {
            httpUrl.replaceFirst("http://", "ws://")
        }
    }

    fun queueRequest(): Request = getRequest("queue")

    fun historyRequest(promptId: String): Request = getRequest("history", promptId)

    fun promptRequest(body: ByteArray): Request = postJsonRequest(body, "prompt")

    fun viewRequest(output: RemoteComfyOutputRef): Request {
        val url = requestUrl("view").newBuilder()
            .addQueryParameter("filename", output.filename)
            .addQueryParameter("subfolder", output.subfolder)
            .addQueryParameter("type", "output")
            .build()
        return Request.Builder()
            .url(url)
            .header("Accept", "video/mp4, application/octet-stream;q=0.9, */*;q=0.1")
            .get()
            .withRemoteAuth(apiKey)
            .build()
    }

    fun getRequest(vararg segments: String): Request = Request.Builder()
        .url(requestUrl(*segments))
        .header("Accept", "application/json")
        .get()
        .withRemoteAuth(apiKey)
        .build()

    fun postJsonRequest(body: ByteArray, vararg segments: String): Request {
        if (body.size > REMOTE_MAX_REQUEST_BODY_BYTES) {
            throw RemoteComfyLimitException("远端请求体超过 4 MiB 上限")
        }
        return Request.Builder()
            .url(requestUrl(*segments))
            .header("Accept", "application/json")
            .post(body.toRequestBody(REMOTE_JSON_MEDIA_TYPE))
            .withRemoteAuth(apiKey)
            .build()
    }

    companion object {
        private const val MAX_URL_CHARS = 2048
        private const val MAX_KEY_CHARS = 8192

        fun parse(binding: VideoRemoteEndpointBinding): RemoteComfyEndpoint {
            val raw = binding.baseUrl
            if (raw.isEmpty() || raw.length > MAX_URL_CHARS) {
                throw RemoteComfyConfigException("远端 endpoint 长度无效")
            }
            if (raw.any { it.isWhitespace() || it.isISOControl() }) {
                throw RemoteComfyConfigException("远端 endpoint 不能包含空白或控制字符")
            }
            if (raw.contains('#') || raw.contains('?')) {
                throw RemoteComfyConfigException("远端 endpoint 不允许包含 query 或 fragment")
            }
            val authority = raw.substringAfter("://", "").substringBefore('/')
            if (authority.contains('@')) {
                throw RemoteComfyConfigException("远端 endpoint 不允许包含 userinfo")
            }
            val parsed = try {
                raw.toHttpUrl()
            } catch (_: IllegalArgumentException) {
                throw RemoteComfyConfigException("远端 endpoint 不是合法的 http/https URL")
            }
            if (parsed.scheme != "http" && parsed.scheme != "https") {
                throw RemoteComfyConfigException("远端 endpoint 只允许 http/https")
            }
            if (parsed.host.isEmpty()) {
                throw RemoteComfyConfigException("远端 endpoint 缺少 host")
            }
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
                throw RemoteComfyConfigException("远端 endpoint 不允许包含 userinfo")
            }
            if (parsed.query != null || parsed.fragment != null) {
                throw RemoteComfyConfigException("远端 endpoint 不允许包含 query 或 fragment")
            }
            val key = validateApiKey(binding.apiKey)
            val normalizedPath = parsed.encodedPath.trimEnd('/')
            val normalized = parsed.newBuilder()
                .encodedPath(if (normalizedPath.isEmpty()) "/" else normalizedPath)
                .query(null)
                .fragment(null)
                .build()
                .toString()
                .trimEnd('/')
            if (normalized.isEmpty()) {
                throw RemoteComfyConfigException("远端 endpoint 规范化失败")
            }
            return RemoteComfyEndpoint(
                endpointId = binding.endpointId,
                baseUrl = normalized,
                apiKey = key,
                allowTargetedCancel = binding.allowTargetedCancel,
            )
        }

        private fun validateApiKey(raw: String?): String? {
            if (raw == null || raw.isBlank()) return null
            if (raw.length > MAX_KEY_CHARS || raw.any { it.isISOControl() }) {
                throw RemoteComfyConfigException("远端凭据格式无效")
            }
            return raw
        }
    }
}

/** Bounded JSON response of one authenticated request. */
internal data class RemoteComfyJsonResponse(val statusCode: Int, val body: JSONObject)

/**
 * Small OkHttp transport for the remote adapter. It owns the credential header, bounded response
 * reads, redirect rejection and cancellation bridging; it never logs a URL or a request body.
 */
internal class RemoteComfyHttp(baseClient: OkHttpClient) {

    val client: OkHttpClient = secureClient(baseClient)
    private val activeCalls = ConcurrentHashMap<String, Call>()

    fun cancel(requestId: String) {
        activeCalls[requestId]?.cancel()
    }

    fun newWebSocket(url: String, apiKey: String?, listener: WebSocketListener): WebSocket {
        // Request.Builder rewrites ws/wss to the HTTP scheme used for the upgrade request.
        val request = Request.Builder()
            .url(url)
            .withRemoteAuth(apiKey)
            .build()
        return client.newWebSocket(request, listener)
    }

    suspend fun getJson(
        requestId: String,
        request: Request,
        timeoutMs: Long,
        maxBytes: Long = REMOTE_MAX_JSON_RESPONSE_BYTES.toLong(),
    ): RemoteComfyJsonResponse = withContext(Dispatchers.IO) {
        execute(requestId, request, timeoutMs) { response ->
            readJsonResponse(response, maxBytes)
        }
    }

    suspend fun postJson(
        requestId: String,
        request: Request,
        timeoutMs: Long,
        maxBytes: Long = REMOTE_MAX_JSON_RESPONSE_BYTES.toLong(),
    ): RemoteComfyJsonResponse = withContext(Dispatchers.IO) {
        execute(requestId, request, timeoutMs) { response ->
            readJsonResponse(response, maxBytes)
        }
    }

    /**
     * Status-only read for probes and cancel requests. A 200 body is consumed through the same
     * 4 MiB bound; error bodies are closed without being copied anywhere.
     */
    suspend fun getStatus(
        requestId: String,
        request: Request,
        timeoutMs: Long,
        maxBytes: Long = REMOTE_MAX_JSON_RESPONSE_BYTES.toLong(),
    ): Int = withContext(Dispatchers.IO) {
        execute(requestId, request, timeoutMs) { response ->
            if (isRedirectStatus(response.code)) throw RemoteComfyRedirectException(response.code)
            if (response.code == 200) {
                response.body?.byteStream()?.let { stream -> readBounded(stream, maxBytes) }
            }
            response.code
        }
    }

    suspend fun postStatus(
        requestId: String,
        request: Request,
        timeoutMs: Long,
    ): Int = withContext(Dispatchers.IO) {
        execute(requestId, request, timeoutMs) { response ->
            if (isRedirectStatus(response.code)) throw RemoteComfyRedirectException(response.code)
            response.code
        }
    }

    /**
     * Streams a /view response into [target]. The caller supplies a path inside the owned run
     * directory; nothing here interprets a server filename as a local path. The first-byte prefix
     * is validated by the caller before the file is promoted to output.mp4.
     */
    suspend fun download(
        requestId: String,
        request: Request,
        target: File,
        maxBytes: Long = REMOTE_MAX_DOWNLOAD_BYTES,
    ): Long = withContext(Dispatchers.IO) {
        execute(requestId, request, null) { response ->
            if (isRedirectStatus(response.code)) throw RemoteComfyRedirectException(response.code)
            if (response.code == 401 || response.code == 403) throw RemoteComfyAuthException(response.code)
            if (response.code != 200) throw RemoteComfyStatusException(response.code)
            val body = response.body ?: throw RemoteComfyProtocolException("远端输出为空")
            if (body.contentLength() > maxBytes) {
                throw RemoteComfyLimitException("远端输出超过 512 MiB 上限")
            }
            var total = 0L
            FileOutputStream(target).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            throw RemoteComfyLimitException("远端输出超过 512 MiB 上限")
                        }
                        output.write(buffer, 0, read)
                    }
                }
                output.fd.sync()
            }
            total
        }
    }

    private fun requireJsonStatus(response: Response) {
        if (isRedirectStatus(response.code)) throw RemoteComfyRedirectException(response.code)
        if (response.code == 401 || response.code == 403) throw RemoteComfyAuthException(response.code)
        if (response.code != 200) throw RemoteComfyStatusException(response.code)
    }

    private fun readJsonResponse(response: Response, maxBytes: Long): RemoteComfyJsonResponse {
        requireJsonStatus(response)
        val body = response.body ?: throw RemoteComfyProtocolException("远端返回了空响应")
        if (body.contentLength() > maxBytes) {
            throw RemoteComfyLimitException("远端响应超过 4 MiB 上限")
        }
        val bytes = readBounded(body.byteStream(), maxBytes)
        val json = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            throw RemoteComfyProtocolException("远端返回了无法解析的 JSON")
        }
        return RemoteComfyJsonResponse(response.code, json)
    }

    private suspend fun <T> execute(
        requestId: String,
        request: Request,
        timeoutMs: Long?,
        block: (Response) -> T,
    ): T {
        val call = client.newCall(request)
        activeCalls[requestId] = call
        if (timeoutMs != null) call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        val job = currentCoroutineContext()[Job]
        val registration = job?.invokeOnCompletion { call.cancel() }
        try {
            return call.execute().use(block)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            registration?.dispose()
            if (activeCalls[requestId] === call) activeCalls.remove(requestId)
            currentCoroutineContext().ensureActive()
        }
    }
}

/**
 * Reads at most [maxBytes] and FAILS when the producer has more. Truncating a JSON response or an
 * output would silently turn an over-limit answer into a "valid" one.
 */
internal fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
    if (maxBytes <= 0L) throw RemoteComfyLimitException("远端响应超过上限")
    val output = ByteArrayOutputStream(minOf(maxBytes, 64L * 1024L).toInt())
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) {
            throw RemoteComfyLimitException("远端响应超过上限")
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
