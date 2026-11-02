package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshClipboardActionTest {

    private val allowedKeys = setOf("ok", "content", "error")

    private suspend fun handle(
        port: FakeClipboardPort,
        body: String?,
        method: DshCapabilityHttpMethod = DshCapabilityHttpMethod.POST,
        query: Map<String, String> = emptyMap()
    ): JSONObject = responseJson(
        DshClipboardAction(port).handle(
            routeRequest(DshClipboardAction.ROUTE, method, query, body)
        )
    )

    @Test
    fun reads_text() = runTest {
        val port = FakeClipboardPort(
            readOutcome = DshClipboardReadOutcome.Text("你好，世界")
        )
        val response = handle(port, jsonBody("action" to "read"))

        assertTrue(response.getBoolean("ok"))
        assertEquals("你好，世界", response.getString("content"))
        assertOnlyKeys(response, allowedKeys)
        assertTrue(port.writes.isEmpty())
    }

    @Test
    fun reads_an_empty_clipboard_as_empty_content() = runTest {
        val response = handle(FakeClipboardPort(), jsonBody("action" to "read"))

        assertTrue(response.getBoolean("ok"))
        assertEquals("", response.getString("content"))
    }

    @Test
    fun reports_a_foreground_restricted_read() = runTest {
        val port = FakeClipboardPort(
            readOutcome = DshClipboardReadOutcome.Unavailable(
                DshAndroidActionFailure.of(
                    DshAndroidActionErrorCode.NOT_ENABLED,
                    "只能在前台读取剪贴板"
                )
            )
        )
        val response = handle(port, jsonBody("action" to "read"))

        assertFalse(response.getBoolean("ok"))
        assertEquals("NOT_ENABLED", errorCodeOf(response))
        assertOnlyKeys(response, allowedKeys)
    }

    @Test
    fun writes_with_the_sensitive_flag_and_no_content_echo() = runTest {
        val port = FakeClipboardPort()
        val response = handle(port, jsonBody("action" to "write", "content" to "生成的文本"))

        assertTrue(response.getBoolean("ok"))
        assertFalse(response.has("content"))
        assertEquals(listOf("生成的文本" to true), port.writes)
        assertOnlyKeys(response, allowedKeys)
    }

    @Test
    fun write_requires_content() = runTest {
        val port = FakeClipboardPort()
        val response = handle(port, jsonBody("action" to "write"))

        assertEquals("INVALID_REQUEST", errorCodeOf(response))
        assertTrue(port.writes.isEmpty())
    }

    @Test
    fun rejects_an_oversized_write() = runTest {
        val response = handle(
            FakeClipboardPort(),
            jsonBody("action" to "write", "content" to "a".repeat(100_001))
        )
        assertEquals("TOO_LARGE", errorCodeOf(response))
    }

    @Test
    fun refuses_an_oversized_read_instead_of_truncating_it_silently() = runTest {
        val port = FakeClipboardPort(
            readOutcome = DshClipboardReadOutcome.Text("a".repeat(100_001))
        )
        val response = handle(port, jsonBody("action" to "read"))

        assertEquals("TOO_LARGE", errorCodeOf(response))
    }

    @Test
    fun rejects_an_unknown_action() = runTest {
        val response = handle(FakeClipboardPort(), jsonBody("action" to "append"))
        assertEquals("INVALID_REQUEST", errorCodeOf(response))
    }

    @Test
    fun refuses_get_and_a_query_on_post() = runTest {
        assertEquals(
            "METHOD_NOT_ALLOWED",
            errorCodeOf(
                handle(
                    FakeClipboardPort(),
                    body = null,
                    method = DshCapabilityHttpMethod.GET,
                    query = mapOf("action" to "write")
                )
            )
        )
        assertEquals(
            "UNEXPECTED_QUERY",
            errorCodeOf(
                handle(
                    FakeClipboardPort(),
                    jsonBody("action" to "read"),
                    query = mapOf("action" to "write")
                )
            )
        )
    }

    @Test
    fun propagates_a_write_refusal() = runTest {
        val port = FakeClipboardPort(
            writeFailure = DshAndroidActionFailure.of(
                DshAndroidActionErrorCode.NOT_ENABLED,
                "只能在前台写入剪贴板"
            )
        )
        val response = handle(port, jsonBody("action" to "write", "content" to "x"))

        assertFalse(response.getBoolean("ok"))
        assertEquals("NOT_ENABLED", errorCodeOf(response))
    }
}
