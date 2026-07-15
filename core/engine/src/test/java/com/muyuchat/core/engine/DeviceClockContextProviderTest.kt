package com.muyuchat.core.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class DeviceClockContextProviderTest {
    private val provider = DeviceClockContextProvider(
        clock = Clock.fixed(Instant.parse("2026-07-15T04:34:56Z"), ZoneId.of("UTC")),
        zoneIdProvider = { ZoneId.of("Asia/Shanghai") },
        localeProvider = { Locale.SIMPLIFIED_CHINESE }
    )

    @Test
    fun dailyContextUsesAuthoritativeDateAndZoneWithoutVolatileTime() {
        val context = provider.contextForUserText("今天几号？")

        assertTrue(context.contains("2026-07-15"))
        assertTrue(context.contains("Asia/Shanghai"))
        assertFalse(context.contains("12:34:56"))
    }

    @Test
    fun explicitCurrentTimeQuestionIncludesExactDeviceTimeAndOffset() {
        val context = provider.contextFor(
            listOf(ChatMessage(role = Role.USER, content = "现在几点了？"))
        )

        assertTrue(context.contains("12:34:56"))
        assertTrue(context.contains("+08:00"))
    }

    @Test
    fun unrelatedTimeZoneQuestionDoesNotMakePromptChangeEverySecond() {
        val context = provider.contextForUserText("北京时间使用什么时区？")

        assertFalse(provider.asksForCurrentExactTime("北京时间使用什么时区？"))
        assertFalse(context.contains("12:34:56"))
    }

    @Test
    fun englishCurrentTimeQuestionIsRecognized() {
        assertTrue(provider.asksForCurrentExactTime("What time is it?"))
        assertTrue(provider.asksForCurrentExactTime("show the current local time"))
    }

    @Test
    fun requestScopedClockContextIsSerializedButNotWrittenIntoGenerationParams() {
        val runtimeContext = provider.contextForUserText("今天几号？")
        val request = ChatRequest(
            messages = listOf(ChatMessage(Role.USER, "今天几号？")),
            params = GenerationParams(systemPrompt = "固定助手提示"),
            runtimeSystemContext = runtimeContext
        )

        val messages = JSONArray(request.messagesJson())
        val system = messages.getJSONObject(0).getString("content")
        assertTrue(system.contains("固定助手提示"))
        assertTrue(system.contains("2026-07-15"))
        assertFalse(request.params.systemPrompt.contains("2026-07-15"))
    }

    @Test
    fun explicitApiSystemMessageStillReceivesTheSameRuntimeClockContext() {
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(Role.SYSTEM, "API persona"),
                ChatMessage(Role.USER, "What time is it?")
            ),
            runtimeSystemContext = provider.contextForUserText("What time is it?")
        )

        val system = JSONArray(request.messagesJson()).getJSONObject(0).getString("content")
        assertTrue(system.contains("API persona"))
        assertTrue(system.contains("12:34:56"))
    }
}
