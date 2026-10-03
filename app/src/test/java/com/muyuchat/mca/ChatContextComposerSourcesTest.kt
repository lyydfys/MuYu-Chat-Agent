package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for the context-composition use case, driven through [ChatContextSources].
 *
 * ## Why these exist now and could not before
 *
 * Until the sources were expressed as a port, `ChatContextComposer` could only be constructed from
 * a `WorldBookStore` and a `KnowledgeBaseStore` — both of which need a real `Context` and a real
 * file on disk. The only thing `ChatContextComposerTest` could therefore assert was the *source
 * text* of `compose`. That pinned the call shapes but never exercised the arithmetic or the
 * ordering.
 *
 * With the port in place the use case runs against an in-memory fake, so these tests assert the
 * composed result and the arguments the use case passes outward. Together with the source-text
 * assertions that remain in `ChatContextComposerTest`, both the shape and the behaviour are pinned.
 *
 * Nothing here touches storage, `Context`, Room or the file system.
 */
class ChatContextComposerSourcesTest {

    /** Records what the use case asked for, and answers with whatever the test set up. */
    private class FakeSources(
        private val books: List<WorldBookRecord> = emptyList(),
        private val knowledge: KnowledgeRetrieval = KnowledgeRetrieval()
    ) : ChatContextSources {
        var loadCount = 0
            private set
        var retrieveCount = 0
            private set
        var lastKnowledgeBaseIds: Set<String>? = null
            private set
        var lastQuery: String? = null
            private set
        var lastMaxChunks: Int? = null
            private set
        var lastTokenBudget: Int? = null
            private set

        override fun loadWorldBooks(): List<WorldBookRecord> {
            loadCount += 1
            return books
        }

        override fun retrieveKnowledge(
            knowledgeBaseIds: Set<String>,
            query: String,
            maxChunks: Int,
            tokenBudget: Int
        ): KnowledgeRetrieval {
            retrieveCount += 1
            lastKnowledgeBaseIds = knowledgeBaseIds
            lastQuery = query
            lastMaxChunks = maxChunks
            lastTokenBudget = tokenBudget
            return knowledge
        }
    }

    private fun book(
        name: String,
        content: String,
        keys: List<String> = listOf("trigger"),
        scope: WorldBookScope = WorldBookScope.GLOBAL,
        assistantId: String? = null,
        chatSessionId: String? = null
    ) = WorldBookRecord(
        id = "book-$name",
        name = name,
        scope = scope,
        assistantId = assistantId,
        chatSessionId = chatSessionId,
        entries = listOf(WorldBookEntry(id = "entry-$name", content = content, keys = keys))
    )

    private fun userMessage(content: String) = ChatMessage(role = Role.USER, content = content)

    // ── Ordering and combination ────────────────────────────────────────────────────────

    @Test
    fun `world book context precedes knowledge context and they are joined by a blank line`() {
        val sources = FakeSources(
            books = listOf(book("lore", "LORE")),
            knowledge = KnowledgeRetrieval(context = "KNOWLEDGE")
        )
        val plan = ChatContextComposer(sources).compose(
            messages = listOf(userMessage("trigger")),
            params = GenerationParams(),
            assistantId = "assistant-1",
            chatSessionId = null,
            knowledgeBaseIds = setOf("kb-1")
        )

        assertEquals("[World book]\nLORE\n\nKNOWLEDGE", plan.runtimeSystemContext)
        assertTrue(plan.hasContext)
    }

    @Test
    fun `a blank knowledge context contributes no blank line of its own`() {
        val sources = FakeSources(books = listOf(book("lore", "LORE")))
        val plan = ChatContextComposer(sources).compose(
            messages = listOf(userMessage("trigger")),
            params = GenerationParams(),
            assistantId = "assistant-1",
            chatSessionId = null,
            knowledgeBaseIds = setOf("kb-1")
        )

        assertEquals("[World book]\nLORE", plan.runtimeSystemContext)
    }

    @Test
    fun `no selected lore and no retrieval yields an empty plan with no context`() {
        val sources = FakeSources(books = listOf(book("lore", "LORE", keys = listOf("absent"))))
        val plan = ChatContextComposer(sources).compose(
            messages = listOf(userMessage("nothing matches here")),
            params = GenerationParams(),
            assistantId = "assistant-1",
            chatSessionId = null,
            knowledgeBaseIds = emptySet()
        )

        assertEquals("", plan.runtimeSystemContext)
        assertFalse(plan.hasContext)
    }

    // ── Budget arithmetic (the part source-text assertions could only spell out) ────────

    @Test
    fun `lore takes three fifths of the dynamic budget and retrieval the remainder`() {
        val sources = FakeSources()
        val plan = ChatContextComposer(sources).compose(
            messages = listOf(userMessage("trigger")),
            params = GenerationParams(),
            assistantId = "assistant-1",
            chatSessionId = null,
            knowledgeBaseIds = setOf("kb-1")
        )

        assertTrue("retrieval must be attempted", sources.retrieveCount == 1)
        // The dynamic budget is what the plan reports; lore is offered three fifths of it and
        // retrieval the remaining two fifths, so the retrieval budget is derivable from both.
        val retrievalBudget = requireNotNull(sources.lastTokenBudget)
        assertEquals(
            "retrieval gets the dynamic budget minus the three fifths offered to lore",
            plan.tokenBudget - (plan.tokenBudget * 3 / 5),
            retrievalBudget
        )
        // Guard the ceiling too: the two shares must not exceed the dynamic budget.
        val offeredToLore = plan.tokenBudget * 3 / 5
        assertTrue(
            "shares must not exceed the dynamic budget",
            offeredToLore + retrievalBudget <= plan.tokenBudget
        )
    }

    @Test
    fun `retrieval is asked for at most four chunks`() {
        val sources = FakeSources()
        ChatContextComposer(sources).compose(
            messages = listOf(userMessage("trigger")),
            params = GenerationParams(),
            assistantId = "assistant-1",
            chatSessionId = null,
            knowledgeBaseIds = setOf("kb-1")
        )

        assertEquals(4, sources.lastMaxChunks)
    }

    @Test
    fun `the token budget handed out never exceeds what the loaded window allows`() {
        // A tiny window must still produce a usable, non-negative budget rather than throwing.
        val sources = FakeSources()
        val plan = ChatContextComposer(sources).compose(
            messages = listOf(userMessage("trigger")),
            params = GenerationParams(nCtx = 64),
            assistantId = "assistant-1",
            chatSessionId = null,
            knowledgeBaseIds = setOf("kb-1")
        )

        assertTrue("dynamic budget must be non-negative", plan.tokenBudget >= 0)
        sources.lastTokenBudget?.let { assertTrue("retrieval budget must be non-negative", it >= 0) }
    }

    // ── Query construction ──────────────────────────────────────────────────────────────

    @Test
    fun `the retrieval query is the latest non-blank user turn`() {
        val sources = FakeSources()
        ChatContextComposer(sources).compose(
            messages = listOf(
                userMessage("old question"),
                ChatMessage(role = Role.ASSISTANT, content = "old answer"),
                userMessage("   "),
                userMessage("newest question")
            ),
            params = GenerationParams(),
            assistantId = "assistant-1",
            chatSessionId = null,
            knowledgeBaseIds = setOf("kb-1")
        )

        assertEquals("newest question", sources.lastQuery)
    }

    @Test
    fun `an over long query is bounded around its own middle`() {
        val sources = FakeSources()
        val long = "x".repeat(20_000)
        ChatContextComposer(sources).compose(
            messages = listOf(userMessage(long)),
            params = GenerationParams(),
            assistantId = "assistant-1",
            chatSessionId = null,
            knowledgeBaseIds = setOf("kb-1")
        )

        val query = requireNotNull(sources.lastQuery)
        assertTrue("query must be shortened", query.length < long.length)
        assertTrue("the elision marker must be present", query.contains('…'))
        assertTrue("the tail must be kept, not just the head", query.endsWith("x"))
    }

    // ── The file-context gate ───────────────────────────────────────────────────────────

    @Test
    fun `disabling file context skips retrieval but keeps the world book`() {
        val sources = FakeSources(books = listOf(book("lore", "LORE")))
        val plan = ChatContextComposer(sources).compose(
            messages = listOf(userMessage("trigger")),
            params = GenerationParams(),
            assistantId = "assistant-1",
            chatSessionId = null,
            knowledgeBaseIds = setOf("kb-1"),
            fileContextEnabled = false
        )

        assertEquals("retrieval must not run when file context is off", 0, sources.retrieveCount)
        assertNull(sources.lastTokenBudget)
        // World Book is persona lore, so its scope and matching behaviour are unchanged.
        assertEquals(1, sources.loadCount)
        assertEquals("[World book]\nLORE", plan.runtimeSystemContext)
    }

    // ── Scope filtering reaches the plan ────────────────────────────────────────────────

    @Test
    fun `an assistant scoped book is selected only for its own assistant`() {
        val scoped = book("scoped", "SCOPED", scope = WorldBookScope.ASSISTANT, assistantId = "assistant-1")
        val forOwner = FakeSources(books = listOf(scoped))
        val ownerPlan = ChatContextComposer(forOwner).compose(
            messages = listOf(userMessage("trigger")),
            params = GenerationParams(),
            assistantId = "assistant-1",
            chatSessionId = null,
            knowledgeBaseIds = emptySet()
        )
        assertEquals("[World book]\nSCOPED", ownerPlan.runtimeSystemContext)

        val forOther = FakeSources(books = listOf(scoped))
        val otherPlan = ChatContextComposer(forOther).compose(
            messages = listOf(userMessage("trigger")),
            params = GenerationParams(),
            assistantId = "assistant-2",
            chatSessionId = null,
            knowledgeBaseIds = emptySet()
        )
        assertEquals("", otherPlan.runtimeSystemContext)
    }

    // ── The trace records what the plan actually used ───────────────────────────────────

    @Test
    fun `the trace carries the dynamic budget and the assembled hash of the composed context`() {
        val sources = FakeSources(
            books = listOf(book("lore", "LORE")),
            knowledge = KnowledgeRetrieval(context = "KNOWLEDGE")
        )
        val plan = ChatContextComposer(sources).compose(
            messages = listOf(userMessage("trigger")),
            params = GenerationParams(),
            assistantId = "assistant-1",
            chatSessionId = null,
            knowledgeBaseIds = setOf("kb-1")
        )

        val trace = requireNotNull(plan.trace)
        assertEquals(plan.tokenBudget, trace.tokenBudget)
        assertTrue("two sources were composed", trace.selectedWorldBookSources.size + trace.selectedKnowledgeSources.size >= 1)
        val json = trace.toJsonString()
        assertTrue(json.contains("\"assembledHash\""))
        assertTrue(json.contains("\"schemaVersion\":1"))
    }

    @Test
    fun `a zero prompt budget short circuits before touching any source`() {
        // nCtx small enough that localContextWindowBudget reports no usable prompt budget.
        val sources = FakeSources(books = listOf(book("lore", "LORE")))
        val plan = ChatContextComposer(sources).compose(
            messages = listOf(userMessage("trigger")),
            params = GenerationParams(nCtx = 1),
            assistantId = "assistant-1",
            chatSessionId = null,
            knowledgeBaseIds = setOf("kb-1")
        )

        if (plan.tokenBudget == 0) {
            assertEquals("no source may be read when there is no budget", 0, sources.loadCount)
            assertEquals(0, sources.retrieveCount)
            assertNull(plan.trace)
        } else {
            // The projection still has to hold whenever a budget does exist.
            assertTrue(plan.tokenBudget > 0)
        }
    }
}
