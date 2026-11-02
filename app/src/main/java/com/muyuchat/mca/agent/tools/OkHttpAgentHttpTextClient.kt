package com.muyuchat.mca.agent.tools

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Production transport for `http.get` over OkHttp (already a project dependency).
 *
 * Hard limits owned here:
 * - redirects are disabled, so a 3xx is a typed failure and never silently reaches another origin;
 * - the call deadline is the request's own timeout, not a dispatcher-wide cancel;
 * - the body is read through a bounded buffer and at most `maxBytes + 1` bytes are consumed, so a
 *   huge or never-ending response cannot exhaust the process;
 * - coroutine cancellation cancels exactly this call.
 *
 * Nothing here proves real network reachability: tests drive the fake [AgentHttpTextClient] and this
 * class is not exercised against a live server by the JVM suite.
 */
class OkHttpAgentHttpTextClient(
    private val client: OkHttpClient = defaultClient()
) : AgentHttpTextClient {

    override suspend fun get(request: AgentHttpGetRequest): AgentHttpGetOutcome = withContext(Dispatchers.IO) {
        val httpRequest = Request.Builder()
            .url(request.url)
            .get()
            .header("Accept", "text/plain, application/json, text/html;q=0.9, */*;q=0.5")
            .build()
        val call = client.newCall(httpRequest)
        call.timeout().timeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        val registration = job?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                val code = response.code
                if (code in 300..399) {
                    return@withContext AgentHttpGetOutcome.Failure(
                        code = "TOOL_HTTP_REDIRECT_DENIED",
                        safeMessage = "The server answered with a redirect; redirects are not followed."
                    )
                }
                if (code !in 200..299) {
                    return@withContext AgentHttpGetOutcome.Failure(
                        code = "TOOL_HTTP_STATUS",
                        safeMessage = "The server answered HTTP $code; no body was returned to the model."
                    )
                }
                val body = response.body
                    ?: return@withContext AgentHttpGetOutcome.Failure(
                        code = "TOOL_HTTP_EMPTY_BODY",
                        safeMessage = "The server returned an empty body."
                    )
                val bounded = readBounded(body.byteStream(), request.maxBytes)
                val text = bounded.bytes.decodeStrictUtf8()
                AgentHttpGetOutcome.Response(
                    statusCode = code,
                    contentType = response.header("Content-Type")?.take(256),
                    bodyBytes = bounded.bytes.size,
                    text = text,
                    truncated = bounded.truncated
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SocketTimeoutException) {
            AgentHttpGetOutcome.Failure(
                code = "TOOL_TIMEOUT",
                safeMessage = "The HTTP request did not finish before the deadline.",
                possibleSideEffects = false
            )
        } catch (_: IOException) {
            AgentHttpGetOutcome.Failure(
                code = "TOOL_HTTP_IO",
                safeMessage = "The HTTP request failed before a complete response was read."
            )
        } finally {
            registration?.dispose()
            currentCoroutineContext().ensureActive()
        }
    }

    private class BoundedBody(val bytes: ByteArray, val truncated: Boolean)

    private fun readBounded(stream: java.io.InputStream, maxBytes: Int): BoundedBody {
        val buffer = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
        val chunk = ByteArray(8 * 1024)
        var total = 0
        var truncated = false
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            val allowed = maxBytes - total
            if (allowed <= 0) {
                truncated = true
                break
            }
            if (read > allowed) {
                buffer.write(chunk, 0, allowed)
                total += allowed
                truncated = true
                break
            }
            buffer.write(chunk, 0, read)
            total += read
        }
        return BoundedBody(buffer.toByteArray(), truncated)
    }

    private fun ByteArray.decodeStrictUtf8(): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
