package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cloud prompt budget: the last hard boundary before a chat request is serialized.
 *
 * These cases pin the promises the fix makes: the system prompt is never dropped, the oldest
 * conversation goes first, one oversized message keeps its head and tail, the visible
 * `[mca:prompt_trimmed …]` fact is present exactly when something was cut, and
 * `estimate(prompt) + output <= window` always holds.
 */
class CloudPromptBudgetTest {

    private fun message(role: Role, chars: Int, fill: Char = 'a'): ChatMessage =
        ChatMessage(role = role, content = fill.toString().repeat(chars))

    private fun request(
        systemChars: Int = 0,
        history: List<Int> = listOf(40, 40),
        requestedOutput: Int = 4_096
    ): ChatRequest = ChatRequest(
        messages = buildList {
            if (systemChars > 0) add(message(Role.SYSTEM, systemChars, 'S'))
            history.forEachIndexed { index, chars ->
                // Unique prefix per message so "the oldest one is gone" is actually assertable.
                add(
                    ChatMessage(
                        role = if (index % 2 == 0) Role.USER else Role.ASSISTANT,
                        content = "#$index:" + "m".repeat(chars)
                    )
                )
            }
        },
        params = GenerationParams(nPredict = requestedOutput, systemPrompt = "")
    )

    @Test
    fun theOldestMessagesAreDroppedFirst() {
        val original = request(systemChars = 400, history = List(100) { 1_200 })

        val fit = CloudPromptBudget.fit(original, windowTokens = 16_384, requestedOutputTokens = 4_096)

        assertTrue("something must be dropped", fit.droppedMessages > 0)
        assertNotNull(fit.note)
        // The newest message survives; the oldest input messages are gone.
        assertEquals(original.messages.last().content, fit.request.messages.last().content)
        assertTrue(fit.request.messages.size < original.messages.size)
        assertTrue(
            fit.request.messages.none { it.content == original.messages[1].content }
        )
    }

    @Test
    fun theSystemPromptIsNeverDropped() {
        val original = request(systemChars = 400, history = List(40) { 800 })

        val fit = CloudPromptBudget.fit(original, windowTokens = 8_192, requestedOutputTokens = 4_096)

        val system = fit.request.messages.first()
        assertEquals(Role.SYSTEM, system.role)
        assertTrue(system.content.startsWith("S"))
    }

    @Test
    fun oneOversizedMessageIsTruncatedKeepingItsHeadAndTail() {
        val oversized = "HEAD" + "x".repeat(200_000) + "TAIL"
        val original = ChatRequest(
            messages = listOf(
                message(Role.SYSTEM, 80, 'S'),
                ChatMessage(role = Role.USER, content = oversized)
            ),
            params = GenerationParams(nPredict = 4_096, systemPrompt = "")
        )

        val fit = CloudPromptBudget.fit(original, windowTokens = 8_192, requestedOutputTokens = 4_096)

        assertEquals(1, fit.truncatedMessages)
        val content = fit.request.messages.last().content
        assertTrue(content.startsWith("HEAD"))
        assertTrue(content.endsWith("TAIL"))
        assertTrue("the cut is marked: …${content.takeLast(80)}", content.contains("[mca:truncated"))
        assertTrue(content.length < oversized.length)
    }

    @Test
    fun aTrimmedRequestCarriesTheVisibleMarker() {
        val original = request(systemChars = 400, history = List(30) { 2_000 })

        val fit = CloudPromptBudget.fit(original, windowTokens = 8_192, requestedOutputTokens = 4_096)

        assertTrue(fit.trimmed)
        val system = fit.request.messages.first().content
        assertTrue(system.contains(CloudPromptBudget.TRIM_MARKER_PREFIX))
        assertTrue(system.contains("dropped="))
        assertTrue(system.contains("budget="))
        assertTrue(system.contains("est="))
    }

    @Test
    fun anUntrimmedRequestIsLeftAlone() {
        val original = request(systemChars = 400, history = listOf(800, 800), requestedOutput = 1_024)

        val fit = CloudPromptBudget.fit(original, windowTokens = 128_000, requestedOutputTokens = 1_024)

        assertNull(fit.note)
        assertEquals(0, fit.droppedMessages)
        assertEquals(0, fit.truncatedMessages)
        // Conversation messages are byte-identical; the system message may also carry the app's
        // reasoning instruction, which the merge adds on purpose.
        assertEquals(
            original.messages.filter { it.role != Role.SYSTEM }.map { it.content },
            fit.request.messages.filter { it.role != Role.SYSTEM }.map { it.content }
        )
        assertEquals(Role.SYSTEM, fit.request.messages.first().role)
        assertTrue(fit.request.messages.first().content.startsWith("S"))
    }

    @Test
    fun promptPlusOutputNeverExceedsTheWindow() {
        val windows = listOf(2_048, 4_096, 16_384, 32_768, 128_000, 262_144)
        val requests = listOf(
            request(0, listOf(400), 4_096),
            request(400, List(10) { 4_000 }, 32_768),
            request(2_000, List(3) { 300_000 }, 65_536)
        )

        windows.forEach { window ->
            requests.forEach { original ->
                val fit = CloudPromptBudget.fit(original, window, original.params.nPredict)
                assertTrue(
                    "est=${fit.promptTokens} + out=${fit.outputTokens} must fit $window",
                    fit.promptTokens + fit.outputTokens <= fit.windowTokens
                )
                assertTrue(fit.outputTokens in 1..fit.windowTokens)
            }
        }
    }

    @Test
    fun theOutputBudgetIsAtMostHalfTheWindowAndAtMostTheHardCeiling() {
        assertEquals(2_048, CloudPromptBudget.outputTokens(4_096, Int.MAX_VALUE))
        assertEquals(64_000, CloudPromptBudget.outputTokens(128_000, Int.MAX_VALUE))
        assertEquals(8_192, CloudPromptBudget.outputTokens(128_000, 8_192))
        assertEquals(32_768, CloudPromptBudget.outputTokens(262_144, 99_999, outputCap = 32_768))
    }

    @Test
    fun theOutputBudgetNeverFallsBelowItsFloorUnlessTheWindowIsSmaller() {
        assertEquals(256, CloudPromptBudget.outputTokens(2_048, requested = 1))
        assertEquals(256, CloudPromptBudget.outputTokens(128_000, requested = 0))
    }

    @Test
    fun anUnknownWindowFallsBackToOneHundredTwentyEightThousand() {
        assertEquals(128_000, CloudPromptBudget.windowTokens(0))
        assertEquals(128_000, CloudPromptBudget.windowTokens(-5))
        assertEquals(128_000, CloudPromptBudget.windowTokens(512))
        assertEquals(8_192, CloudPromptBudget.windowTokens(8_192))
    }

    @Test
    fun theModelWindowTableFallsBackForUnknownNames() {
        assertEquals(128_000, CloudModelWindow.windowTokens("some-gateway-model-v3"))
        assertEquals(128_000, CloudModelWindow.windowTokens(""))
        assertEquals(8_192, CloudModelWindow.outputTokens(16_384))
    }

    @Test
    fun theFittedRequestIsIdempotentUnderTheSystemPromptMerge() {
        val original = request(systemChars = 400, history = List(30) { 2_000 })

        val fit = CloudPromptBudget.fit(original, windowTokens = 8_192, requestedOutputTokens = 4_096)

        assertEquals("", fit.request.params.systemPrompt)
        assertEquals("", fit.request.runtimeSystemContext)
        assertEquals(
            "a second merge must not re-add dropped text",
            fit.request.messages.map { it.content },
            fit.request.messagesWithSystemPrompt().map { it.content }
        )
    }

    @Test
    fun aTinyWindowStillReturnsTheNewestMessage() {
        val original = request(systemChars = 100, history = listOf(40_000), requestedOutput = 65_536)

        val fit = CloudPromptBudget.fit(original, windowTokens = 1_024, requestedOutputTokens = 65_536)

        assertTrue(fit.request.messages.isNotEmpty())
        assertEquals(Role.SYSTEM, fit.request.messages.first().role)
        assertTrue(fit.promptTokens + fit.outputTokens <= fit.windowTokens)
    }
}
