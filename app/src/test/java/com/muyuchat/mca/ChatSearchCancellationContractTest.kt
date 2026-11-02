package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P04 contract: the search turn keeps cancellation and ordinary failure distinct.
 *
 * A cancelled search must reach the generation coroutine (so the caller can stop before creating a
 * model request and before appending any answer); an ordinary search failure keeps the historical
 * degraded behavior ("本轮将直接回答").
 */
class ChatSearchCancellationContractTest {

    private fun searchConfig() = WebSearchConfig(
        enabled = true,
        provider = WebSearchProviderType.SEARXNG,
        endpoint = "https://search.example.com",
        maxResults = 5,
        fetchPageContent = false
    )

    @Test
    fun aCancelledSearchIsRethrownAndNoAnswerStepRuns() {
        val cancelled = CancellationException("user stopped the search")
        var answerRequests = 0
        var searchCalls = 0

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                executeWebSearchForChatTurn(
                    messages = listOf(ChatMessage(Role.USER, "Android AI")),
                    config = searchConfig(),
                    oneShotEnabled = true,
                    assistantWebSearchEnabled = false,
                    search = { _, _ ->
                        searchCalls += 1
                        throw cancelled
                    }
                )
                // Reaching this line means the turn would continue into the model request/answer.
                answerRequests += 1
            }
        }

        assertEquals(1, searchCalls)
        assertSame("the caller's cancellation must travel unchanged", cancelled, thrown)
        assertEquals("a cancelled search must not continue into an answer", 0, answerRequests)
    }

    @Test
    fun anOrdinarySearchFailureKeepsTheDegradedAnswerPath() = runBlocking {
        val outcome = executeWebSearchForChatTurn(
            messages = listOf(ChatMessage(Role.USER, "Android AI")),
            config = searchConfig(),
            oneShotEnabled = true,
            assistantWebSearchEnabled = false,
            search = { _, _ -> throw IOException("network unreachable") }
        )

        assertFalse(outcome.success)
        assertTrue("the turn still records that it tried to search", outcome.searched)
        assertEquals("联网检索失败，本轮将直接回答", outcome.statusMessage)
        assertTrue(outcome.webSearchStatusMessage.orEmpty().startsWith("联网检索失败"))
    }

    @Test
    fun aCancelledProviderQueryIsNotTurnedIntoAProviderWarning() {
        val cancelled = CancellationException("provider call cancelled")
        val port = object : WebSearchCollectionPort {
            override fun searchProviderQuery(
                query: String,
                config: WebSearchConfig
            ): List<WebSearchDocument> = throw cancelled

            override fun fetchReadableDocument(
                url: String,
                provider: String,
                config: WebSearchConfig?
            ): WebSearchDocument = error("not used")
        }

        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                runProviderDocumentCollection(
                    port = port,
                    queries = listOf("Android AI"),
                    providerConfigs = listOf(searchConfig()),
                    hasDirectDocuments = false
                )
            }
        }

        assertEquals(
            "provider collection must preserve the cancellation reason, not record a warning",
            cancelled.message,
            thrown.message
        )
    }

    @Test
    fun theSearchTurnCompletesBeforeAnyModelStreamIsCreated() {
        val turnSource = sourceFile("WebSearchTurnExecution.kt")
        val body = functionBody(
            sourceFile("ChatExecutionOperations.kt"),
            "fun ChatGenerationExecutionPort.runChatGeneration("
        )
        val search = body.indexOf("runWebSearchTurnFor(")
        val localStream = body.indexOf("LocalApiRuntime.streamChat(")
        val cloudStream = body.indexOf("cloudChatProvider.streamChat(")

        assertTrue("the turn must run the search step", search >= 0)
        assertTrue("local model streaming starts only after the search step",
            localStream > search)
        assertTrue("cloud model streaming starts only after the search step",
            cloudStream > search)
        assertFalse("the search step must not use a cancellation-swallowing runCatching",
            turnSource.contains("runCatching { search(plan, searchConfig) }"))
        assertTrue("cancellation is rethrown before the failure degradation",
            turnSource.indexOf("throw cancelled") in 0 until turnSource.indexOf("联网检索失败"))
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing source signature: $signature" }
        val openingBrace = source.indexOf('{', start)
        require(openingBrace >= 0) { "Missing function body: $signature" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated function body: $signature")
    }

    private fun sourceFile(name: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            val candidates = listOf(
                File(root, "src/main/java/com/muyuchat/mca/$name"),
                File(root, "app/src/main/java/com/muyuchat/mca/$name")
            )
            candidates.firstOrNull { it.isFile }?.let { return it.readText(Charsets.UTF_8) }
            directory = root.parentFile
        }
        error("$name not found from ${System.getProperty("user.dir")}")
    }
}
