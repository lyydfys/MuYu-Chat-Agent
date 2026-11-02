package com.muyuchat.feature.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R01 behaviour tests for the bounded Markdown projection. They drive the real controller
 * (conflated single-slot queue, identity + version commit gate, cancellation rethrow) instead of
 * asserting that the renderer mentions `Dispatchers.Default`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMarkdownProjectionTest {

    private val conversationOwner = ChatMessageIdentity("conversation-a", "message-1")

    private fun request(
        display: String,
        full: String = display,
        owner: ChatMessageIdentity? = conversationOwner,
        visibleCharacters: Int = display.length
    ): MarkdownProjectionRequest = MarkdownProjectionRequest(
        owner = owner,
        visibleCharacters = visibleCharacters,
        contentLength = full.length,
        displayContent = display,
        fullContent = full
    )

    /**
     * Runs everything the controller needs on the test scheduler.
     *
     * [runCurrent] is required, not belt-and-braces: the worker is a `backgroundScope` coroutine, and
     * advancing virtual time alone does not run a freshly dispatched background task that is still
     * waiting for its first resumption. Running the current-time queue first makes the commit
     * deterministic instead of timing-dependent.
     */
    private fun TestScope.settleProjection() {
        runCurrent()
        advanceUntilIdle()
        runCurrent()
    }

    private fun TestScope.projected(
        request: MarkdownProjectionRequest,
        parse: suspend (displayContent: String, fullContent: String) -> List<MarkdownBlock> =
            { display, full -> parseMarkdownBlocks(display, full) }
    ): MarkdownProjectionResult {
        var committed: MarkdownProjectionResult? = null
        val controller = MarkdownProjectionController(parse, onCommitted = { committed = it })
        backgroundScope.launch { controller.run() }
        controller.submit(request)
        settleProjection()
        return requireNotNull(committed)
    }

    @Test
    fun `background projection starts only when display and complete source both exceed the budget`() {
        val exact = "x".repeat(MARKDOWN_SYNC_PARSE_BUDGET_CHARS)

        assertFalse(markdownProjectionNeedsBackground(exact, exact))
        assertFalse(markdownProjectionNeedsBackground("short reply", exact))
        assertTrue(
            markdownProjectionNeedsBackground("x".repeat(MARKDOWN_SYNC_PARSE_BUDGET_CHARS + 1), exact)
        )
        assertTrue(markdownProjectionNeedsBackground(exact, exact + "y"))
    }

    @Test
    fun `a late result from conversation A never commits once B is current`() = runTest {
        val committed = mutableListOf<MarkdownProjectionResult>()
        val slowGate = CompletableDeferred<Unit>()
        val controller = MarkdownProjectionController(
            parseBlocks = { display, full ->
                if (display == "A") slowGate.await()
                parseMarkdownBlocks(display, full)
            },
            onCommitted = { committed += it }
        )
        backgroundScope.launch { controller.run() }

        controller.submit(request(display = "A", owner = ChatMessageIdentity("conversation-a", "message-1")))
        settleProjection()
        controller.submit(request(display = "B", owner = ChatMessageIdentity("conversation-b", "message-1")))
        settleProjection()

        slowGate.complete(Unit)
        settleProjection()

        assertEquals(1, committed.size)
        assertEquals("conversation-b", committed.single().owner?.conversationId)
        assertEquals("B", committed.single().request.displayContent)
    }

    @Test
    fun `the same message id in another conversation never borrows the completed projection`() = runTest {
        val committed = mutableListOf<MarkdownProjectionResult>()
        val controller = MarkdownProjectionController(
            parseBlocks = { display, full -> parseMarkdownBlocks(display, full) },
            onCommitted = { committed += it }
        )
        backgroundScope.launch { controller.run() }

        val ownerA = ChatMessageIdentity("conversation-a", "message-42")
        val ownerB = ChatMessageIdentity("conversation-b", "message-42")
        controller.submit(request(display = "identical body", owner = ownerA))
        settleProjection()
        val completed = requireNotNull(controller.current)
        assertEquals(ownerA, completed.owner)

        // Identical text and identical messageId: only the conversation differs, and that must be
        // enough to hide the old projection.
        val replacement = request(display = "identical body", owner = ownerB)
        assertFalse(replacement.canDisplay(completed))

        controller.submit(replacement)
        settleProjection()
        assertEquals(ownerB, controller.current?.owner)
        assertEquals(2, committed.size)
    }

    @Test
    fun `strict append keeps the completed projection until the newer one commits`() = runTest {
        val committed = mutableListOf<MarkdownProjectionResult>()
        val secondParseGate = CompletableDeferred<Unit>()
        var parseCalls = 0
        val controller = MarkdownProjectionController(
            parseBlocks = { display, full ->
                if (parseCalls++ == 1) secondParseGate.await()
                parseMarkdownBlocks(display, full)
            },
            onCommitted = { committed += it }
        )
        backgroundScope.launch { controller.run() }

        controller.submit(request(display = "prefix", full = "prefix"))
        settleProjection()
        val completed = requireNotNull(controller.current)

        val appended = request(display = "prefix", full = "prefix and more")
        assertTrue("a strict append may keep the previous result", appended.canDisplay(completed))
        controller.submit(appended)
        settleProjection()
        assertSame("completed blocks stay on screen while the append parses", completed, controller.current)

        secondParseGate.complete(Unit)
        settleProjection()
        assertNotSame(completed, controller.current)
        assertEquals("prefix and more", controller.current?.request?.fullContent)
        assertEquals(2, committed.size)
    }

    @Test
    fun `replaced bodies narrowed pages and page range changes never display the previous projection`() = runTest {
        val completed = projected(request(display = "alpha", full = "alpha"))

        assertTrue(request(display = "alpha", full = "alpha beta").canDisplay(completed))
        assertFalse(
            "a page range change must not keep the previous projection",
            request(
                display = "alpha beta",
                full = "alpha beta gamma",
                visibleCharacters = 64
            ).canDisplay(completed)
        )
        assertFalse(
            "a replaced body must not reuse the old blocks",
            request(display = "omega", full = "omega").canDisplay(completed)
        )
        assertFalse(
            "another conversation with the same messageId must not reuse the old blocks",
            request(
                display = "alpha",
                full = "alpha beta",
                owner = ChatMessageIdentity("conversation-b", "message-1")
            ).canDisplay(completed)
        )
        assertFalse(
            "a smaller page range must not reveal more than the old result",
            request(display = "alp", full = "alpha", visibleCharacters = 3).canDisplay(completed)
        )
        assertFalse(request(display = "alpha", full = "alpha").canDisplay(null))
    }

    @Test
    fun `copy payload refreshes when only the complete source keeps growing`() = runTest {
        val display = "Intro\n```kotlin\nval answer = 42"
        val firstFull = display
        val latestFull = display + "\n```\nAfter\n```python\nprint('next')\n```\nEnd"
        val secondParseGate = CompletableDeferred<Unit>()
        var parseCalls = 0
        val controller = MarkdownProjectionController(
            parseBlocks = { visible, complete ->
                if (parseCalls++ == 1) secondParseGate.await()
                parseMarkdownBlocks(visible, complete)
            }
        )
        backgroundScope.launch { controller.run() }

        controller.submit(request(display = display, full = firstFull))
        settleProjection()
        val stale = requireNotNull(controller.current)
        assertEquals(
            "val answer = 42",
            stale.blocks.filterIsInstance<MarkdownBlock.Code>().single().copyCode
        )

        // The displayed prefix does not change; only the complete source grows. The projection must
        // still be requested again so the copy payload is the latest complete code, and the old
        // result may only stay on screen while the new one is pending.
        val latest = request(display = display, full = latestFull)
        assertTrue(latest.canDisplay(stale))
        controller.submit(latest)
        settleProjection()
        assertSame(stale, controller.current)

        secondParseGate.complete(Unit)
        settleProjection()
        val refreshed = requireNotNull(controller.current)
        assertNotSame(stale, refreshed)
        assertEquals(
            "val answer = 42\n",
            refreshed.blocks.filterIsInstance<MarkdownBlock.Code>().single().copyCode
        )
    }

    @Test
    fun `a burst of updates keeps one pending request and commits only the newest`() = runTest {
        val parsed = mutableListOf<String>()
        val committed = mutableListOf<MarkdownProjectionResult>()
        val firstParseGate = CompletableDeferred<Unit>()
        val controller = MarkdownProjectionController(
            parseBlocks = { display, full ->
                parsed += display
                if (parsed.size == 1) firstParseGate.await()
                parseMarkdownBlocks(display, full)
            },
            onCommitted = { committed += it }
        )
        backgroundScope.launch { controller.run() }

        controller.submit(request(display = "0"))
        settleProjection()
        for (index in 1 until 50) {
            controller.submit(request(display = index.toString()))
        }
        assertEquals("49", controller.pendingRequest?.displayContent)

        firstParseGate.complete(Unit)
        settleProjection()

        assertEquals(2, parsed.size)
        assertEquals(listOf("0", "49"), parsed)
        assertEquals(1, committed.size)
        assertEquals("49", committed.single().request.displayContent)
        assertNull(controller.pendingRequest)
    }

    @Test
    fun `a destroyed owner never commits the result that finishes after cancellation`() = runTest {
        val committed = mutableListOf<MarkdownProjectionResult>()
        val scanGate = CompletableDeferred<Unit>()
        val controller = MarkdownProjectionController(
            parseBlocks = { display, full ->
                // A real CPU scan is not cancellable mid-loop; model that with NonCancellable.
                withContext(NonCancellable) { scanGate.await() }
                parseMarkdownBlocks(display, full)
            },
            onCommitted = { committed += it }
        )
        val job = backgroundScope.launch { controller.run() }
        controller.submit(request(display = "long body"))
        settleProjection()

        job.cancel()
        scanGate.complete(Unit)
        settleProjection()

        assertTrue("nothing may be published after the owner is gone", committed.isEmpty())
        assertTrue(job.isCancelled)
    }

    @Test
    fun `parser failures keep readable source and an engineering diagnostic`() = runTest {
        val committed = mutableListOf<MarkdownProjectionResult>()
        val controller = MarkdownProjectionController(
            parseBlocks = { _, _ -> throw IllegalStateException("parser exploded") },
            onCommitted = { committed += it }
        )
        backgroundScope.launch { controller.run() }
        controller.submit(request(display = "| a | b |"))
        settleProjection()

        val result = committed.single()
        assertEquals(listOf(MarkdownBlock.Paragraph("| a | b |")), result.blocks)
        val failure = result.failure
        assertNotNull(failure)
        assertTrue(failure!!.contains("parser exploded"))
    }

    @Test
    fun `cancellation is rethrown and never mapped to a committed error`() = runTest {
        val committed = mutableListOf<MarkdownProjectionResult>()
        val controller = MarkdownProjectionController(
            parseBlocks = { _, _ -> throw CancellationException("stop parsing") },
            onCommitted = { committed += it }
        )
        val job = backgroundScope.launch { controller.run() }
        controller.submit(request(display = "body"))
        settleProjection()

        assertTrue(committed.isEmpty())
        assertTrue("CancellationException must stay cancellation", job.isCancelled)
    }

    @Test
    fun `special characters and unfinished fences match the parser exactly`() = runTest {
        val fixtures = listOf(
            "Family 👨‍👩‍👧‍👦 and flag 🇨🇳\r\n",
            "```kotlin\r\nval emoji = \"😀\"\r\n",
            "Line one\r\n\r\nA ZWJ astronaut 🧑‍🚀 and `code|pipe` here"
        )
        fixtures.forEach { fixture ->
            val projection = projected(request(display = fixture, full = fixture))
            assertEquals(parseMarkdownBlocks(fixture, fixture), projection.blocks)
            assertNull(projection.failure)
        }

        val zwj = projected(request(display = fixtures[0], full = fixtures[0]))
        assertTrue(
            "emoji and ZWJ sequences must be preserved exactly",
            zwj.blocks.filterIsInstance<MarkdownBlock.Paragraph>().single().text.contains("👨‍👩‍👧‍👦")
        )

        val openFence = projected(request(display = fixtures[1], full = fixtures[1]))
        val code = openFence.blocks.filterIsInstance<MarkdownBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val emoji = \"😀\"\r\n", code.code)
    }

    @Test
    fun `mixed blocks keep source order content and links`() = runTest {
        val fixture = "Intro with [docs](https://example.com/doc) and https://a.example/1\n\n" +
            "```kotlin file=\"one.kt\"\nval one = 1\n```\n" +
            "| 指标 | 说明 |\n| --- | --- |\n| 中文 | English |\n" +
            "- first\n- second\n" +
            "```python\nprint('two')\n```"
        val projection = projected(request(display = fixture, full = fixture))
        val blocks = projection.blocks

        assertEquals(parseMarkdownBlocks(fixture, fixture), blocks)
        val codes = blocks.filterIsInstance<MarkdownBlock.Code>()
        assertEquals(listOf("kotlin", "python"), codes.map { it.language })
        val table = blocks.filterIsInstance<MarkdownBlock.Table>().single()
        assertEquals(listOf(listOf("指标", "说明"), listOf("中文", "English")), table.rows)
        val bullets = blocks.filterIsInstance<MarkdownBlock.BulletList>().single()
        assertEquals(listOf("first", "second"), bullets.items)
        assertTrue(
            blocks.filterIsInstance<MarkdownBlock.Paragraph>().any {
                it.text.contains("[docs](https://example.com/doc)") &&
                    it.text.contains("https://a.example/1")
            }
        )
        val tableIndex = blocks.indexOfFirst { it is MarkdownBlock.Table }
        val bulletIndex = blocks.indexOfFirst { it is MarkdownBlock.BulletList }
        val secondCodeIndex = blocks.indexOfLast { it is MarkdownBlock.Code }
        assertTrue(tableIndex in 0 until bulletIndex)
        assertTrue(bulletIndex < secondCodeIndex)
    }

    @Test
    fun `matches rejects another owner another page range or another version`() {
        val sequenced = request(display = "body", full = "body").copy(sequence = 3)
        val result = MarkdownProjectionResult(sequenced, emptyList())

        assertTrue(sequenced.matches(result))
        assertFalse(sequenced.copy(sequence = 4).matches(result))
        assertFalse(
            sequenced.copy(owner = ChatMessageIdentity("conversation-b", "message-1")).matches(result)
        )
        assertFalse(sequenced.copy(visibleCharacters = 2).matches(result))
        val grown = sequenced.copy(
            fullContent = "body and more",
            fullRevision = markdownContentRevision("body and more")
        )
        assertFalse(grown.matches(result))
        assertNotEquals(markdownContentRevision("body"), markdownContentRevision("body and more"))
    }
}
