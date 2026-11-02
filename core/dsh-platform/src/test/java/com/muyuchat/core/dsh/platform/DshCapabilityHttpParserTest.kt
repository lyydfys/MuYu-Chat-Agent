package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DshCapabilityHttpParserTest {

    @Test
    fun parsesMethodPathQueryHeadersAndBody() {
        val body = "{\"k\":1}"
        val request = parse(
            "POST /notify?action=show&ns=a.b HTTP/1.1\r\n" +
                "X-DSH-Token: abcdef\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "\r\n" +
                body
        )

        assertEquals("POST", request.method)
        assertEquals("/notify", request.path)
        assertEquals(mapOf("action" to "show", "ns" to "a.b"), request.query)
        assertEquals("abcdef", request.header("x-dsh-token"))
        assertEquals("application/json", request.header("Content-Type"))
        assertEquals(body, request.body)
    }

    @Test
    fun acceptsBareLineFeedHeaderTerminator() {
        val request = parse("GET /status HTTP/1.1\nX-DSH-Token: t\n\n")

        assertEquals("GET", request.method)
        assertEquals("/status", request.path)
        assertEquals("t", request.header("x-dsh-token"))
        assertEquals("", request.body)
    }

    @Test
    fun percentDecodesQueryKeysAndValues() {
        val request = parse(
            "GET /clipboard?text=a%20b%26c&zh=%E4%B8%AD HTTP/1.1\r\n\r\n"
        )

        assertEquals("a b&c", request.query["text"])
        assertEquals("中", request.query["zh"])
    }

    @Test
    fun rejectsDuplicateQueryKey() {
        val rejected = assertRejected(
            "GET /status?a=1&a=2 HTTP/1.1\r\n\r\n"
        )

        assertEquals(400, rejected.statusCode)
        assertEquals(DshCapabilityErrorCode.INVALID_REQUEST, rejected.errorCode)
    }

    @Test
    fun rejectsTooManyQueryPairs() {
        val query = (1..25).joinToString("&") { "k$it=v$it" }
        val rejected = assertRejected("GET /status?$query HTTP/1.1\r\n\r\n")

        assertEquals(413, rejected.statusCode)
        assertEquals(DshCapabilityErrorCode.TOO_LARGE, rejected.errorCode)
    }

    @Test
    fun rejectsOverlongQuery() {
        val rejected = assertRejected("GET /status?a=${"b".repeat(4_097)} HTTP/1.1\r\n\r\n")

        assertEquals(414, rejected.statusCode)
        assertEquals(DshCapabilityErrorCode.TOO_LARGE, rejected.errorCode)
    }

    @Test
    fun rejectsOverlongPath() {
        val rejected = assertRejected("GET /${"a".repeat(128)} HTTP/1.1\r\n\r\n")

        assertEquals(414, rejected.statusCode)
    }

    @Test
    fun rejectsOversizedDeclaredBodyBeforeReadingIt() {
        val rejected = assertRejected(
            "POST /notify HTTP/1.1\r\nContent-Length: 262145\r\n\r\n"
        )

        assertEquals(413, rejected.statusCode)
        assertEquals(DshCapabilityErrorCode.TOO_LARGE, rejected.errorCode)
    }

    @Test
    fun rejectsChunkedTransferEncoding() {
        val rejected = assertRejected(
            "POST /notify HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"
        )

        assertEquals(400, rejected.statusCode)
        assertEquals(DshCapabilityErrorCode.INVALID_REQUEST, rejected.errorCode)
    }

    @Test
    fun rejectsMalformedRequestLine() {
        val rejected = assertRejected("GETPOST\r\n\r\n")

        assertEquals(400, rejected.statusCode)
    }

    @Test
    fun rejectsIncompleteBody() {
        val rejected = assertRejected(
            "POST /notify HTTP/1.1\r\nContent-Length: 5\r\n\r\nab"
        )

        assertEquals(400, rejected.statusCode)
        assertEquals(DshCapabilityErrorCode.INVALID_REQUEST, rejected.errorCode)
    }

    @Test
    fun rejectsDuplicateHeader() {
        val rejected = assertRejected(
            "POST /notify HTTP/1.1\r\nX-DSH-Token: a\r\nX-DSH-Token: b\r\n\r\n"
        )

        assertEquals(400, rejected.statusCode)
    }

    @Test
    fun rejectsOversizedHeaderBlock() {
        val rejected = assertRejected("GET /status HTTP/1.1\r\n" + "a".repeat(70_000))

        assertEquals(431, rejected.statusCode)
        assertEquals(DshCapabilityErrorCode.TOO_LARGE, rejected.errorCode)
    }

    @Test
    fun rejectsMalformedPercentEscape() {
        val rejected = assertRejected("GET /status?a=%G1 HTTP/1.1\r\n\r\n")

        assertEquals(400, rejected.statusCode)
        assertEquals(DshCapabilityErrorCode.INVALID_REQUEST, rejected.errorCode)
    }

    @Test
    fun rejectsRelativeRequestTarget() {
        val rejected = assertRejected("GET notify HTTP/1.1\r\n\r\n")

        assertEquals(400, rejected.statusCode)
    }

    @Test
    fun rejectsFragmentInRequestTarget() {
        val rejected = assertRejected("GET /status#frag HTTP/1.1\r\n\r\n")

        assertEquals(400, rejected.statusCode)
    }

    @Test
    fun rejectsNegativeContentLength() {
        val rejected = assertRejected(
            "POST /notify HTTP/1.1\r\nContent-Length: -1\r\n\r\n"
        )

        assertEquals(400, rejected.statusCode)
    }

    @Test
    fun bodyIsEmptyWhenNoContentLengthIsDeclared() {
        val request = parse("POST /notify HTTP/1.1\r\n\r\n")

        assertEquals("", request.body)
        assertNull(request.header("content-length"))
    }

    private fun parse(raw: String): ParsedDshCapabilityRequest =
        DshCapabilityHttpParser.parse(ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)))

    private fun assertRejected(raw: String): DshCapabilityHttpParseException =
        assertThrows(DshCapabilityHttpParseException::class.java) {
            parse(raw)
        }
}
