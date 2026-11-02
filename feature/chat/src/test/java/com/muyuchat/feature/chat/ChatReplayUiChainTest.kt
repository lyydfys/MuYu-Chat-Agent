package com.muyuchat.feature.chat

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R10.3 boundary tests for the pure replay -> UI chain.
 *
 * The driver must reproduce the shipped page / budget / parser rules, not approximate them, so these
 * tests drive the real [ChatReplayUiChain] over the same [MarkdownProjectionController] the renderer
 * uses, and they pin the block dispatch to the renderer source rather than to a copy of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatReplayUiChainTest {

    private fun frame(
        content: String,
        owner: ChatMessageIdentity? = null,
        visibleCharacters: Int = ChatReplayUiChain.initialPageCharacters
    ): ChatReplayFrame = ChatReplayUiChain.frame(content, owner, visibleCharacters)

    private fun project(content: String, visibleCharacters: Int = 16_384): ChatReplayFrameProjection =
        ChatReplayUiChain.project(frame(content, visibleCharacters = visibleCharacters))

    @Test
    fun `every parsed block kind selects exactly the renderer the production surface uses`() {
        val cases = listOf(
            Triple("plain paragraph", "A short answer.", listOf(ChatReplayRenderBranch.RICH)),
            Triple("heading", "# Heading", listOf(ChatReplayRenderBranch.RICH)),
            Triple("quote", "> quoted line", listOf(ChatReplayRenderBranch.RICH)),
            Triple("divider", "---", listOf(ChatReplayRenderBranch.DIVIDER)),
            Triple("bullet list", "- one\n- two", listOf(ChatReplayRenderBranch.LIST)),
            Triple("numbered list", "1. one\n2. two", listOf(ChatReplayRenderBranch.LIST)),
            Triple("fenced code", "```kotlin\nval x = 1\n```", listOf(ChatReplayRenderBranch.CODE)),
            Triple("table", "| a | b |\n| --- | --- |\n| 1 | 2 |", listOf(ChatReplayRenderBranch.TABLE))
        )
        cases.forEach { (name, content, expected) ->
            val projection = project(content)
            assertEquals("$name: renderer branches", expected, projection.blockBranches)
            assertEquals("$name: primary branch", expected.first(), projection.primaryBranch)
            assertEquals("$name: block count", projection.blockBranches.size, projection.blockCount)
            assertEquals("$name: distinct branches", expected.toSet(), projection.branches)
            assertNull("$name: the production parse must not fail", projection.failure)
        }
    }

    @Test
    fun `an empty frame still selects the paragraph renderer`() {
        val projection = project("")

        assertEquals("", projection.displayContent)
        assertEquals(0, projection.contentLength)
        assertFalse(projection.needsBackgroundProjection)
        assertEquals(listOf(ChatReplayRenderBranch.RICH), projection.blockBranches)
        assertEquals(1, projection.blockCount)
        assertNull(projection.failure)
        assertFalse(
            "nothing may be displayed before the first frame of a run",
            ChatReplayUiChain.keepsPreviousProjection(null, projection)
        )
    }

    @Test
    fun `the parse budget boundary and the page cut come from the shipped rules`() {
        val budget = ChatReplayUiChain.syncParseBudgetCharacters
        assertFalse(
            "a body exactly at the budget still takes the synchronous fast path",
            project("x".repeat(budget)).needsBackgroundProjection
        )
        assertTrue(
            "one character above the budget switches to the background projection",
            project("x".repeat(budget + 1)).needsBackgroundProjection
        )

        // A page cut may shorten by one unit, and only to avoid splitting a surrogate pair.
        val body = "a".repeat(9) + "\uD83D\uDE00" + "b".repeat(9)
        val cut = project(body, visibleCharacters = 10)
        assertEquals("aaaaaaaaa", cut.displayContent)
        assertEquals(body.length, cut.contentLength)
        assertTrue(body.startsWith(cut.displayContent))
    }

    @Test
    fun `a code fence cut by the page budget keeps the complete copy payload`() {
        val body = "Intro\n```kotlin\nval x = 1\nprintln(x)\n```\nEnd"
        val projection = project(body, visibleCharacters = 20)

        assertEquals("Intro\n```kotlin\nval ", projection.displayContent)
        assertEquals(
            listOf(ChatReplayRenderBranch.RICH, ChatReplayRenderBranch.CODE),
            projection.blockBranches
        )
        val code = projection.blocks.filterIsInstance<MarkdownBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("the displayed page only holds the unfinished fence", "val ", code.code)
        assertEquals(
            "the copy payload keeps the complete code even though the page was cut",
            "val x = 1\nprintln(x)\n",
            code.copyCode
        )
        assertFalse(projection.needsBackgroundProjection)
    }

    @Test
    fun `a malformed table still selects the table renderer and keeps every cell`() {
        val ragged = project("| a | b |\n| --- |\n| 1 | 2 | 3 |")
        assertEquals(listOf(ChatReplayRenderBranch.TABLE), ragged.blockBranches)
        assertEquals(
            listOf(listOf("a", "b", ""), listOf("1", "2", "3")),
            ragged.blocks.filterIsInstance<MarkdownBlock.Table>().single().rows
        )

        val noSeparator = project("| a | b |\n| 1 |")
        assertEquals(listOf(ChatReplayRenderBranch.TABLE), noSeparator.blockBranches)
        assertEquals(
            listOf(listOf("a", "b"), listOf("1", "")),
            noSeparator.blocks.filterIsInstance<MarkdownBlock.Table>().single().rows
        )
    }

    @Test
    fun `half-written markdown keeps renderer order instead of collapsing into one paragraph`() {
        val projection =
            project("**unfinished bold\n- item one\n- item two\n### heading\n4. numbered")

        assertEquals(
            listOf(
                ChatReplayRenderBranch.RICH,
                ChatReplayRenderBranch.LIST,
                ChatReplayRenderBranch.RICH,
                ChatReplayRenderBranch.LIST
            ),
            projection.blockBranches
        )
    }

    @Test
    fun `projecting the same frame twice is stable`() {
        val replayFrame = frame("Intro\n- one\n```kotlin\nval x = 1\n```")
        val first = ChatReplayUiChain.project(replayFrame)
        val second = ChatReplayUiChain.project(replayFrame)

        assertEquals(
            listOf(
                ChatReplayRenderBranch.RICH,
                ChatReplayRenderBranch.LIST,
                ChatReplayRenderBranch.CODE
            ),
            first.blockBranches
        )
        assertEquals(first.displayContent, second.displayContent)
        assertEquals(first.blockBranches, second.blockBranches)
        assertEquals(first.primaryBranch, second.primaryBranch)
        assertEquals(first.needsBackgroundProjection, second.needsBackgroundProjection)
        assertEquals(first.contentLength, second.contentLength)
    }

    @Test
    fun `a replaced body a rewound body a page change or a new identity must not keep the previous projection`() {
        val owner = ChatMessageIdentity("conversation-a", "message-1")
        val previous = ChatReplayUiChain.project(frame("alpha", owner = owner))
        assertFalse(
            "the first frame of a run has nothing to display",
            ChatReplayUiChain.keepsPreviousProjection(null, previous)
        )

        val appended = ChatReplayUiChain.project(frame("alpha and more", owner = owner))
        assertTrue(
            "a strict append may keep the previous projection on screen",
            ChatReplayUiChain.keepsPreviousProjection(previous, appended)
        )
        assertTrue(
            "the settled frame repeats the same body, which is still the same projection",
            ChatReplayUiChain.keepsPreviousProjection(appended, ChatReplayUiChain.project(frame("alpha and more", owner = owner)))
        )

        assertFalse(
            "a replaced body must not borrow the previous blocks",
            ChatReplayUiChain.keepsPreviousProjection(previous, ChatReplayUiChain.project(frame("omega", owner = owner)))
        )
        assertFalse(
            "a rewound body must not borrow the previous blocks",
            ChatReplayUiChain.keepsPreviousProjection(previous, ChatReplayUiChain.project(frame("alph", owner = owner)))
        )
        assertFalse(
            "a page budget change must not borrow the previous blocks",
            ChatReplayUiChain.keepsPreviousProjection(
                previous,
                ChatReplayUiChain.project(frame("alpha", owner = owner, visibleCharacters = 32))
            )
        )
        assertFalse(
            "a new run owns a new identity",
            ChatReplayUiChain.keepsPreviousProjection(
                previous,
                ChatReplayUiChain.project(
                    frame("alpha and more", owner = ChatMessageIdentity("conversation-a", "message-2"))
                )
            )
        )
    }

    @Test
    fun `a paged frame above the budget commits the same blocks through the real projection controller`() =
        runTest {
            val body = "long body line\n".repeat(600)
            val replayFrame = frame(body)
            val projection = ChatReplayUiChain.project(replayFrame)
            assertTrue(
                "a body above the budget must be projected off the fast path",
                projection.needsBackgroundProjection
            )
            assertEquals(body, projection.displayContent)

            var committed: MarkdownProjectionResult? = null
            val controller = MarkdownProjectionController(
                parseBlocks = { display, full -> parseMarkdownBlocks(display, full) },
                onCommitted = { committed = it }
            )
            backgroundScope.launch { controller.run() }
            controller.submit(projection.request)
            settle()

            val result = requireNotNull(committed) { "the controller must commit the frame" }
            assertEquals(
                "the committed projection and the replay projection must select the same renderers",
                projection.blockBranches,
                result.blocks.map(ChatReplayUiChain::renderBranchOf)
            )
            assertEquals(projection.displayContent, result.request.displayContent)
            assertEquals(projection.contentLength, result.request.contentLength)
            assertEquals(projection.visibleCharacters, result.request.visibleCharacters)
        }

    @Test
    fun `the production renderer dispatch still matches the replay branch mapping`() {
        val renderer = projectFile(
            "feature/chat/src/main/java/com/muyuchat/feature/chat/ChatMarkdownBlockRenderers.kt"
        ).readText(Charsets.UTF_8)
        val arms = mapOf(
            ChatReplayRenderBranch.CODE to listOf("is MarkdownBlock.Code -> CodeBlock("),
            ChatReplayRenderBranch.TABLE to listOf("is MarkdownBlock.Table -> TableBlock("),
            ChatReplayRenderBranch.LIST to listOf(
                "is MarkdownBlock.BulletList -> ListBlock(",
                "is MarkdownBlock.NumberedList -> NumberedListBlock("
            ),
            ChatReplayRenderBranch.RICH to listOf(
                "is MarkdownBlock.Heading -> HeadingBlock(",
                "is MarkdownBlock.Paragraph -> ParagraphBlock(",
                "is MarkdownBlock.Quote -> QuoteBlock("
            ),
            ChatReplayRenderBranch.DIVIDER to listOf("MarkdownBlock.Divider -> HorizontalDivider(")
        )
        arms.forEach { (branch, markers) ->
            markers.forEach { marker ->
                assertEquals(
                    "the renderer must keep exactly one dispatch for $branch: $marker",
                    1,
                    renderer.countOccurrences(marker)
                )
            }
        }
        // A block kind without a renderer arm would make the replay branch a lie; a renderer arm the
        // replay chain does not model would silently escape the per-frame assertions.
        assertEquals(
            "the renderer must dispatch exactly the block kinds the replay chain models",
            arms.values.sumOf { it.size },
            Regex("""(?m)^\s+(?:is )?MarkdownBlock\.\w+\s*->""").findAll(renderer).count()
        )
    }

    // ---------------------------------------------------------------------------------------------

    private fun TestScope.settle() {
        runCurrent()
        advanceUntilIdle()
        runCurrent()
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count += 1
            index = indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun projectFile(relativePath: String): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) {
                val file = File(directory, relativePath)
                require(file.isFile) { "missing project file: $file" }
                return file
            }
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }
}
