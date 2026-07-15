package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.feature.agent.AgentEngineLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainViewModelCompletionFallbackTest {
    @Test
    fun blankAssistantReplyGetsVisibleFallbackWithoutDroppingReasoning() {
        val messages = listOf(
            ChatMessage(Role.USER, "hello"),
            ChatMessage(Role.ASSISTANT, "  ", reasoningContent = "hidden reasoning")
        )

        val updated = messages.withVisibleAssistantCompletionFallback()

        assertEquals(EMPTY_ASSISTANT_COMPLETION_MESSAGE, updated.last().content)
        assertEquals("hidden reasoning", updated.last().reasoningContent)
    }

    @Test
    fun visibleAssistantReplyIsLeftUntouched() {
        val messages = listOf(
            ChatMessage(Role.USER, "hello"),
            ChatMessage(Role.ASSISTANT, "visible answer")
        )

        val updated = messages.withVisibleAssistantCompletionFallback()

        assertSame(messages, updated)
    }

    @Test
    fun completedLoadedGenerationAlwaysReturnsLifecycleToReady() {
        assertEquals(
            AgentEngineLifecycle.READY,
            RuntimeStats(loaded = true).lifecycleAfterGeneration()
        )
        assertEquals(
            AgentEngineLifecycle.READY,
            RuntimeStats(loaded = true, lastError = "request failed").lifecycleAfterGeneration()
        )
    }

    @Test
    fun completedUnloadedGenerationPreservesErrorOrUnloadedState() {
        assertEquals(
            AgentEngineLifecycle.ERROR,
            RuntimeStats(loaded = false, lastError = "failed").lifecycleAfterGeneration()
        )
        assertEquals(
            AgentEngineLifecycle.UNLOADED,
            RuntimeStats(loaded = false).lifecycleAfterGeneration()
        )
    }

    @Test
    fun sharedGenerationLifecycleMarksApiAndUiWorkAsGenerating() {
        val started = MainUiState(
            isGenerating = false,
            engineLifecycle = AgentEngineLifecycle.READY
        ).afterGenerationStarted()

        assertTrue(started.isGenerating)
        assertEquals(AgentEngineLifecycle.GENERATING, started.engineLifecycle)
    }

    @Test
    fun sharedGenerationLifecycleReturnsLoadedRuntimeToReadyOnCompletion() {
        val completed = MainUiState(
            isGenerating = true,
            engineLifecycle = AgentEngineLifecycle.GENERATING
        ).afterGenerationCompleted(RuntimeStats(loaded = true, completionTokens = 3))

        assertEquals(false, completed.isGenerating)
        assertEquals(AgentEngineLifecycle.READY, completed.engineLifecycle)
        assertEquals(3, completed.stats.completionTokens)
    }

    @Test
    fun clearChatStopPathReturnsLoadedRuntimeToReady() {
        val stopped = MainUiState(
            messages = listOf(ChatMessage(Role.USER, "hello")),
            input = "draft",
            activeChatSessionId = "session-1",
            isGenerating = true,
            engineLifecycle = AgentEngineLifecycle.GENERATING
        ).afterClearChatGenerationStopped(RuntimeStats(loaded = true))

        assertEquals(emptyList<ChatMessage>(), stopped.messages)
        assertEquals("", stopped.input)
        assertNull(stopped.activeChatSessionId)
        assertEquals(false, stopped.isGenerating)
        assertEquals(AgentEngineLifecycle.READY, stopped.engineLifecycle)
        assertEquals("已清空对话，上下文已重置", stopped.statusMessage)
    }

    @Test
    fun backgroundStopPathReturnsLoadedRuntimeToReadyWithoutClearingConversation() {
        val messages = listOf(ChatMessage(Role.USER, "hello"))
        val stopped = MainUiState(
            messages = messages,
            input = "draft",
            activeChatSessionId = "session-1",
            isGenerating = true,
            engineLifecycle = AgentEngineLifecycle.GENERATING
        ).afterBackgroundGenerationStopped(RuntimeStats(loaded = true))

        assertSame(messages, stopped.messages)
        assertEquals("draft", stopped.input)
        assertEquals("session-1", stopped.activeChatSessionId)
        assertEquals(false, stopped.isGenerating)
        assertEquals(AgentEngineLifecycle.READY, stopped.engineLifecycle)
        assertEquals("应用进入后台，已停止生成以降低发热和耗电。", stopped.statusMessage)
    }

    @Test
    fun clearChatInvokesLifecycleClosureAfterStopAndCancel() {
        val body = functionBody(mainViewModelSource(), "fun clearChat()")
        val stop = body.indexOf("engine.stopGeneration()")
        val cancel = body.indexOf("generationJob?.cancel()")
        val close = body.indexOf("afterClearChatGenerationStopped(engine.stats.value)")

        assertTrue(stop >= 0)
        assertTrue(cancel > stop)
        assertTrue(close > cancel)
    }

    @Test
    fun backgroundStopInvokesLifecycleClosureAfterStopAndCancel() {
        val body = functionBody(mainViewModelSource(), "fun onAppBackgrounded()")
        val stop = body.indexOf("engine.stopGeneration()")
        val cancel = body.indexOf("generationJob?.cancel()")
        val close = body.indexOf("afterBackgroundGenerationStopped(engine.stats.value)")

        assertTrue(stop >= 0)
        assertTrue(cancel > stop)
        assertTrue(close > cancel)
    }

    @Test
    fun localApiProviderOwnsSharedGenerationLifecycle() {
        val source = mainViewModelSource()
        val provider = source.substring(
            source.indexOf("LocalApiRuntime.streamChatWithContextProvider ="),
            source.indexOf("LocalApiRuntime.stopGenerationProvider =")
        )

        assertTrue(provider.contains(".onStart"))
        assertTrue(provider.contains("afterGenerationStarted"))
        assertTrue(provider.contains(".onCompletion"))
        assertTrue(provider.contains("afterGenerationCompleted(engine.stats.value)"))
        assertTrue(source.contains("state.engineLifecycle == AgentEngineLifecycle.GENERATING"))
        assertTrue(source.contains("\"generation_in_progress\""))
    }

    private fun mainViewModelSource(): String {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            val candidates = listOf(
                File(root, "src/main/java/com/muyuchat/mca/MainViewModel.kt"),
                File(root, "app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
            )
            candidates.firstOrNull { it.isFile }?.let { return it.readText(Charsets.UTF_8) }
            directory = root.parentFile
        }
        error("MainViewModel.kt not found from ${System.getProperty("user.dir")}")
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
}
