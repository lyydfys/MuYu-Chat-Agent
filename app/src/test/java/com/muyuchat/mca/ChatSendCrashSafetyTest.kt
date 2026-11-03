package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the send-path crash fixes. A user reported "tap send, app disappears" with a cloud chat
 * model; each case here is one of the throw shapes that used to escape the launched coroutine and
 * kill the process instead of publishing a status line.
 */
class ChatSendCrashSafetyTest {

    @Test
    fun `an active session id that no longer exists no longer aborts the send with an exception`() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/ChatExecutionOperations.kt")

        // The exact shape that used to crash: first { } on a missing id + requireNotNull on the
        // snapshot. Both are now guard clauses that settle the turn through the normal path.
        assertTrue(source.contains("val frozenSession = initialState.chatSessions.firstOrNull {"))
        assertTrue(source.contains("val frozenAssistantSnapshot = frozenSession?.assistantSnapshot"))
        assertTrue(source.contains("if (frozenSession == null || frozenAssistantSnapshot == null) {"))
        assertTrue(!source.contains("initialState.chatSessions.first { it.id == initialState.activeChatSessionId }"))
        assertTrue(!source.contains("requireNotNull(frozenSession.assistantSnapshot)"))
    }

    @Test
    fun `a failed submission is reported instead of escaping the view model scope`() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/ChatWorkflowCoordinator.kt")

        assertTrue(source.contains("private fun reportSubmissionFailure(error: Throwable)"))
        assertTrue(source.contains("发送失败："))
        assertTrue(source.contains("请重试；若持续失败，请检查会话数据是否损坏。"))
        // Both submission launches (multi-attachment preflight and the plain path) must convert a
        // throw into that status line; a bare `finally` was what let it reach the default handler.
        val launches = Regex("viewModelScope\\.launch").findAll(source).count()
        val calls = Regex("reportSubmissionFailure\\(error\\)").findAll(source).count()
        assertTrue("expected the plain launch plus the IO launch to be guarded", launches >= 2)
        // Exactly the two guard sites dispatch into the helper.
        assertTrue("expected two guard sites, found $calls", calls == 2)
        assertTrue(source.contains("catch (error: CancellationException)"))
    }

    @Test
    fun `assistant selection helpers fall back instead of throwing on an empty store`() {
        val store = sourceFile("app/src/main/java/com/muyuchat/mca/AssistantStore.kt")
        assertTrue(store.contains("?: assistants.firstOrNull()?.id"))
        assertTrue(store.contains("?: AssistantRecord.DEFAULT_ID"))

        val profiles = sourceFile("app/src/main/java/com/muyuchat/mca/AssistantProfileOperations.kt")
        assertTrue(!profiles.contains("remaining.first()"))
        assertTrue(profiles.contains("remaining.firstOrNull()"))
        // The deletion must also tolerate having nothing left to select.
        assertTrue(profiles.contains("if (deletingSelectedAssistant && nextSelection != null)"))
    }

    @Test
    fun `restoring after a failed mutation does not assume the session survived`() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/presentation/GenerationStateTransitions.kt")
        assertTrue(source.contains("durableSessions.firstOrNull { it.id == activeId }?.messages"))
        assertTrue(!source.contains("durableSessions.first { it.id == activeId }.messages"))
    }

    @Test
    fun `render paths degrade instead of asserting on an empty block list`() {
        val chain = sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatReplayUiChain.kt")
        assertTrue(chain.contains("blockBranches.firstOrNull() ?: ChatReplayRenderBranch.RICH"))
        assertTrue(!chain.contains("blockBranches.first()"))

        val markdown = sourceFile("feature/chat/src/main/java/com/muyuchat/feature/chat/ChatMarkdownRenderSupport.kt")
        assertTrue(!markdown.contains("urlMatchAt(index)!!"))
        assertTrue(markdown.contains("if (token.isEmpty()) continue"))
    }

    /** The image collector has to survive an empty generated batch rather than indexing it. */
    @Test
    fun `an empty generated image batch is never indexed`() {
        val source = sourceFile("app/src/main/java/com/muyuchat/mca/ImageLibraryCommitOperations.kt")
        assertTrue(source.contains("if (generatedImages.isEmpty()) {"))
        assertTrue(source.contains("return IllegalStateException(\"生图结果为空，未写入图片库。\")"))
    }

    private fun sourceFile(relativePath: String) =
        java.io.File(projectRoot(), relativePath).readText(Charsets.UTF_8)

    private fun projectRoot(): java.io.File {
        var directory: java.io.File? = java.io.File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (java.io.File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("project root not found")
    }
}
