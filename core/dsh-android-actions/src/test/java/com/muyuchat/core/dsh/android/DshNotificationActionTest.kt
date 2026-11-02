package com.muyuchat.core.dsh.android

import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DshNotificationActionTest {

    private val allowedKeys = setOf("ok", "error", "posted", "visible", "evidence")

    private suspend fun handle(
        port: FakeNotificationPort,
        body: String?,
        method: DshCapabilityHttpMethod = DshCapabilityHttpMethod.POST,
        query: Map<String, String> = emptyMap()
    ): JSONObject = responseJson(
        DshNotificationAction(port).handle(
            routeRequest(DshNotificationAction.ROUTE, method, query, body)
        )
    )

    @Test
    fun posts_notification_with_deterministic_id() = runTest {
        val port = FakeNotificationPort()
        val response = handle(port, jsonBody("title" to "任务完成", "text" to "长任务已完成"))

        assertTrue(response.getBoolean("ok"))
        assertTrue(response.getBoolean("posted"))
        assertOnlyKeys(response, allowedKeys)
        assertEquals(1, port.posts.size)
        assertEquals("任务完成", port.posts[0].first)
        assertEquals("长任务已完成", port.posts[0].second)
        assertEquals(DshNotificationIds.of("任务完成", "长任务已完成"), port.posts[0].third)
    }

    @Test
    fun identical_content_reuses_the_same_notification_id() = runTest {
        val port = FakeNotificationPort()
        val action = DshNotificationAction(port)
        val body = jsonBody("title" to "t", "text" to "x")
        action.handle(routeRequest(DshNotificationAction.ROUTE, body = body))
        action.handle(routeRequest(DshNotificationAction.ROUTE, body = body))

        assertEquals(2, port.posts.size)
        assertEquals(port.posts[0].third, port.posts[1].third)
    }

    @Test
    fun rejects_a_query_on_the_post_route() = runTest {
        val port = FakeNotificationPort()
        val response = handle(port, jsonBody("title" to "t", "text" to "x"), query = mapOf("title" to "t"))

        assertFalse(response.getBoolean("ok"))
        assertEquals("UNEXPECTED_QUERY", errorCodeOf(response))
        assertTrue(port.posts.isEmpty())
    }

    @Test
    fun rejects_get() = runTest {
        val response = handle(
            FakeNotificationPort(),
            body = null,
            method = DshCapabilityHttpMethod.GET
        )
        assertEquals("METHOD_NOT_ALLOWED", errorCodeOf(response))
    }

    @Test
    fun rejects_malformed_json_body() = runTest {
        val response = handle(FakeNotificationPort(), "this is not json")
        assertEquals("INVALID_REQUEST", errorCodeOf(response))
    }

    @Test
    fun rejects_an_oversized_title() = runTest {
        val response = handle(
            FakeNotificationPort(),
            jsonBody("title" to "a".repeat(121), "text" to "x")
        )
        assertEquals("TOO_LARGE", errorCodeOf(response))
    }

    @Test
    fun rejects_a_blank_title() = runTest {
        val response = handle(FakeNotificationPort(), jsonBody("title" to "   ", "text" to "x"))
        assertEquals("INVALID_REQUEST", errorCodeOf(response))
    }

    @Test
    fun rejects_a_control_character_in_text() = runTest {
        val response = handle(
            FakeNotificationPort(),
            jsonBody("title" to "t", "text" to "a\u0007b")
        )
        assertEquals("INVALID_REQUEST", errorCodeOf(response))
    }

    @Test
    fun projects_a_permission_failure_without_claiming_it_posted() = runTest {
        val port = FakeNotificationPort().apply {
            failure = DshAndroidActionFailure.of(
                DshAndroidActionErrorCode.PERMISSION_DENIED,
                "未授予通知权限"
            )
        }
        val response = handle(port, jsonBody("title" to "t", "text" to "x"))

        assertFalse(response.getBoolean("ok"))
        assertFalse(response.getBoolean("posted"))
        assertEquals("PERMISSION_DENIED", errorCodeOf(response))
        assertOnlyKeys(response, allowedKeys)
    }

    @Test
    fun cancel_all_delegates_to_the_port() = runTest {
        val port = FakeNotificationPort().apply { cancelAllResult = 2 }
        assertEquals(2, DshNotificationAction(port).cancelAll())
    }
}
