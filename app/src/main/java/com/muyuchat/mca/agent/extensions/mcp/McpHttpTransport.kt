package com.muyuchat.mca.agent.extensions.mcp

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource

/** One POST to an MCP endpoint. Headers are already decided by the client (no implicit retries). */
data class McpHttpPost(
    val url: String,
    val bodyJson: String,
    val sessionId: String?,
    val protocolVersion: String?,
    val bearerToken: String?,
    val allowCleartext: Boolean
)

sealed interface McpHttpResult {
    data class Json(val statusCode: Int, val body: String, val sessionId: String? = null) : McpHttpResult

    data class EventStream(val statusCode: Int, val events: List<String>, val sessionId: String? = null) : McpHttpResult

    data class Accepted(val statusCode: Int, val sessionId: String? = null) : McpHttpResult

    data class Failed(
        val failure: McpFailure,
        val statusCode: Int? = null
    ) : McpHttpResult
}

/** Transport seam so wire behavior is testable without a device or a public server. */
interface McpHttpTransport {
    suspend fun post(post: McpHttpPost): McpHttpResult

    /** Best-effort session termination; 405 means the server does not support DELETE. */
    suspend fun deleteSession(post: McpHttpPost): Boolean

    fun close()
}

/**
 * OkHttp Streamable HTTP transport.
 *
 * - no automatic redirects (a 3xx is an origin change and must be re-authorized by the user);
 * - no dispatcher-wide cancellation: each call is cancelled through its own [okhttp3.Call];
 * - response bodies are accumulated through a bounded buffer; a single SSE message and the whole
 *   response are capped before any unbounded string can be built;
 * - cleartext is refused unless the caller explicitly marked a LOCAL_SERVER origin.
 */
class OkHttpMcpTransport(
    private val client: OkHttpClient = defaultClient(),
    private val closeClient: Boolean = false
) : McpHttpTransport {

    override suspend fun post(post: McpHttpPost): McpHttpResult {
        val request = try {
            buildRequest(post)
        } catch (_: IllegalArgumentException) {
            return McpHttpResult.Failed(McpFailure.Endpoint("mcp_endpoint_invalid"))
        }
        return execute(post, request, expectEvents = true)
    }

    override suspend fun deleteSession(post: McpHttpPost): Boolean {
        val request = try {
            buildDelete(post)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return withContext(Dispatchers.IO) {
            val call = client.newCall(request)
            val registration = currentCoroutineContext().installCallCancellation(call)
            try {
                call.execute().use { response -> response.code == 200 || response.code == 204 }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                false
            } finally {
                registration.close()
            }
        }
    }

    override fun close() {
        if (closeClient) client.dispatcher.executorService.shutdown()
    }

    private suspend fun execute(
        post: McpHttpPost,
        request: Request,
        expectEvents: Boolean
    ): McpHttpResult = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        val registration = currentCoroutineContext().installCallCancellation(call)
        try {
            call.execute().use { response -> readResponse(response, post, expectEvents) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timeout: SocketTimeoutException) {
            McpHttpResult.Failed(McpFailure.Timeout("mcp_timeout"))
        } catch (_: IOException) {
            McpHttpResult.Failed(McpFailure.Protocol("mcp_transport_io"))
        } finally {
            registration.close()
        }
    }

    private fun readResponse(
        response: Response,
        post: McpHttpPost,
        expectEvents: Boolean
    ): McpHttpResult {
        val code = response.code
        val sessionId = response.header(McpProtocol.HEADER_SESSION_ID)
        when {
            code in 300..399 -> return McpHttpResult.Failed(
                McpFailure.Endpoint("mcp_redirect_not_allowed", originChanged = true),
                code
            )
            code == 401 -> return McpHttpResult.Failed(McpFailure.Authentication("mcp_unauthorized"), code)
            code == 403 -> return McpHttpResult.Failed(McpFailure.Authorization("mcp_forbidden"), code)
            code == 202 || code == 204 -> return McpHttpResult.Accepted(code, sessionId)
            code !in 200..299 -> return McpHttpResult.Failed(
                if (code == 404) McpFailure.SessionExpired("mcp_not_found") else McpFailure.Protocol("mcp_http_$code"),
                code
            )
        }
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        if (contentType.contains("text/event-stream")) {
            if (!expectEvents) return McpHttpResult.Failed(McpFailure.Protocol("mcp_unexpected_event_stream"), code)
            return readEventStream(response, code, sessionId)
        }
        if (!contentType.contains("application/json")) {
            return McpHttpResult.Failed(McpFailure.Protocol("mcp_unexpected_content_type"), code)
        }
        val body = response.body ?: return McpHttpResult.Failed(McpFailure.Protocol("mcp_empty_body"), code)
        val text = readBounded(body.source(), McpProtocol.MAX_MESSAGE_BYTES)
            ?: return McpHttpResult.Failed(McpFailure.SizeLimit("mcp_message_too_large"), code)
        return McpHttpResult.Json(code, text, sessionId)
    }

    private fun readEventStream(response: Response, code: Int, sessionId: String?): McpHttpResult {
        val body = response.body ?: return McpHttpResult.Failed(McpFailure.Protocol("mcp_empty_body"), code)
        val parser = McpSseParser(
            maxEventBytes = McpProtocol.MAX_MESSAGE_BYTES,
            maxTotalBytes = McpProtocol.MAX_TOOL_LIST_BYTES
        )
        val events = ArrayList<String>()
        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(buffer, READ_CHUNK_BYTES)
            if (read == -1L) break
            total += read
            if (total > McpProtocol.MAX_TOOL_LIST_BYTES) {
                return McpHttpResult.Failed(McpFailure.SizeLimit("mcp_sse_total_too_large"), code)
            }
            parser.feed(buffer.readUtf8()) { event -> events += event }
            if (source.exhausted()) break
        }
        parser.finish()?.let { failure -> return McpHttpResult.Failed(failure, code) }
        if (events.isEmpty()) return McpHttpResult.Failed(McpFailure.Protocol("mcp_sse_empty"), code)
        return McpHttpResult.EventStream(code, events, sessionId)
    }

    private fun readBounded(source: BufferedSource, limit: Int): String? {
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(buffer, READ_CHUNK_BYTES)
            if (read == -1L) break
            total += read
            if (total > limit) return null
        }
        return buffer.readUtf8()
    }

    private fun buildRequest(post: McpHttpPost): Request {
        val url = validatedUrl(post)
        val body = post.bodyJson.toRequestBody(JSON_MEDIA_TYPE)
        return Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", McpProtocol.ACCEPT_BOTH)
            .header("Content-Type", McpProtocol.CONTENT_TYPE_JSON)
            .apply {
                post.sessionId?.let { header(McpProtocol.HEADER_SESSION_ID, it) }
                post.protocolVersion?.let { header(McpProtocol.HEADER_PROTOCOL_VERSION, it) }
                post.bearerToken?.let { header("Authorization", "Bearer $it") }
            }
            .build()
    }

    private fun buildDelete(post: McpHttpPost): Request {
        val url = validatedUrl(post)
        return Request.Builder()
            .url(url)
            .delete()
            .apply {
                post.sessionId?.let { header(McpProtocol.HEADER_SESSION_ID, it) }
                post.protocolVersion?.let { header(McpProtocol.HEADER_PROTOCOL_VERSION, it) }
                post.bearerToken?.let { header("Authorization", "Bearer $it") }
            }
            .build()
    }

    private fun validatedUrl(post: McpHttpPost): String {
        val parsed = java.net.URI(post.url)
        require(parsed.scheme == "https" || (post.allowCleartext && parsed.scheme == "http")) {
            "MCP endpoint must be HTTPS unless an explicit local-server grant allows cleartext"
        }
        require(parsed.host != null && !parsed.host.isNullOrBlank()) { "MCP endpoint needs a host" }
        require(parsed.userInfo == null && parsed.fragment == null) { "MCP endpoint must not embed credentials or a fragment" }
        return post.url
    }

    private fun kotlin.coroutines.CoroutineContext.installCallCancellation(
        call: okhttp3.Call
    ): AutoCloseable {
        val job = this[kotlinx.coroutines.Job]
        val handle = job?.invokeOnCompletion { call.cancel() }
        return AutoCloseable { handle?.dispose() }
    }

    companion object {
        private const val READ_CHUNK_BYTES = 8_192L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(McpProtocol.DEFAULT_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
            .readTimeout(McpProtocol.DEFAULT_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
            .writeTimeout(McpProtocol.DEFAULT_DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
            .build()
    }
}
