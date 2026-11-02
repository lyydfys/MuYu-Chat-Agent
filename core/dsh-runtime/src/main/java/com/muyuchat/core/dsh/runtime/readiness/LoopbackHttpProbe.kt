package com.muyuchat.core.dsh.runtime.readiness

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Production loopback HTTP probe. It never follows redirects (the official 303 exchange is
 * inspected directly), never leaves the loopback literals, bounds the body, and treats
 * connection failures as data rather than exceptions.
 */
class LoopbackHttpProbe(
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 15_000,
    private val maxBodyBytes: Int = 2 * 1024 * 1024
) : DshHttpProbe {

    override fun get(url: String, cookie: String?): DshHttpResponse {
        val uri = try {
            URI(url)
        } catch (t: Throwable) {
            return failure("invalid URL")
        }
        if (!uri.scheme.equals("http", ignoreCase = true)) return failure("non-HTTP URL")
        val host = uri.host ?: return failure("missing host")
        if (host != "127.0.0.1" && host != "::1" && host != "[::1]" && host != "localhost") {
            return failure("non-loopback host")
        }
        if (uri.port !in 1..65535) return failure("missing port")
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("Accept", "text/html,application/javascript")
            if (cookie != null) connection.setRequestProperty("Cookie", cookie)
            val code = connection.responseCode
            val headers = LinkedHashMap<String, List<String>>()
            for ((name, values) in connection.headerFields) {
                if (name != null) headers[name] = values ?: emptyList()
            }
            // A real 401 is the expected unauthenticated readiness response. inputStream
            // throws for HTTP errors, so read errorStream for every error status and keep
            // the server's status even when it supplies no error body.
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val body = readBounded(stream, maxBodyBytes)
            DshHttpResponse(
                code = code,
                body = body,
                contentType = connection.contentType,
                headers = headers
            )
        } catch (t: Throwable) {
            DshHttpResponse(
                code = -1,
                body = "",
                contentType = null,
                headers = emptyMap(),
                failure = "${t.javaClass.simpleName}: ${t.message ?: ""}".trim()
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun readBounded(stream: InputStream?, cap: Int): String {
        if (stream == null) return ""
        return try {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                if (out.size() + read > cap) {
                    out.write(buffer, 0, cap - out.size())
                    break
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray().toString(Charsets.UTF_8)
        } catch (t: Throwable) {
            ""
        } finally {
            try {
                stream.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun failure(reason: String): DshHttpResponse = DshHttpResponse(
        code = -1,
        body = "",
        contentType = null,
        headers = emptyMap(),
        failure = reason
    )
}
