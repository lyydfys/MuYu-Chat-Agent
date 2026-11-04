package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ownership shape of the single chat-session write path (P4).
 *
 * `runChatSessionPersistence` is the verbatim successor of `MainViewModel.persistChatSessions`;
 * this test reads the moved source directly, so it doubles as the ownership audit: the moved body
 * must not touch ViewModel internals (`_uiState`, `viewModelScope`, `this@MainViewModel`).
 */
class ChatSessionPersistenceOperationsTest {
    @Test
    fun runKeepsTheRunGatesTheCoalescingAndTheBindingHandoff() {
        val body = functionBody(ownerSource(), "runChatSessionPersistence(")

        assertEquals(
            "the run-id guard must check before the flush, after the flush, in the launch and in the lock",
            4,
            Regex("""chatRuns\.isCurrent\(generationRunId\)""").findAll(body).count()
        )
        assertTrue(body.contains("port.flushPendingAssistantOutput(generationRunId)"))
        assertTrue(body.contains("chatSessionPersistenceMutex.withLock"))
        assertTrue(body.contains("if (sequence != chatSessionPersistenceSequence.get()) return@withLock"))
        assertTrue(body.contains("pendingKnowledgeBindings[sessionId] = knowledgeBaseIds.toSet()"))
        assertTrue(body.contains("chatSessionStore.save(snapshot, knowledgeBindingsForSave)"))
        assertTrue(body.contains("port.durableChatSessions = persisted"))
        assertTrue(body.contains("port.cleanupPendingWorldBooksAfterOwnerCommit("))
        assertTrue(body.contains("port.publishReconciledChatSessions(snapshot, persisted)"))
    }

    @Test
    fun runDoesNotReachIntoViewModelInternals() {
        val body = functionBody(ownerSource(), "runChatSessionPersistence(")

        assertFalse(body.contains("_uiState"))
        assertFalse(body.contains("viewModelScope"))
        assertFalse(body.contains("this@MainViewModel"))
    }

    @Test
    fun theViewModelKeepsOnlyTheDelegateAndNoInlineSave() {
        val vm = sourceFile("MainViewModel.kt")

        assertEquals(1, Regex("""(?:private|internal) fun persistChatSessions\(""").findAll(vm).count())
        assertTrue(vm.contains("runChatSessionPersistence(chatSessionPersistencePort"))
        assertFalse(vm.contains("chatSessionStore.save(snapshot, knowledgeBindingsForSave)"))
    }

    @Test
    fun mutationKeepsTheCommitThenKvBoundaryAndTheRollbackPath() {
        val owner = ownerSource().substringAfter("fun runPersistConversationMutation(")

        assertTrue(owner.contains("chatSessionStore.save(snapshot, knowledgeBindingsForSave, removedChatOwners)"))
        assertTrue(owner.contains("port.durableChatSessions = snapshot"))
        assertTrue(
            owner.indexOf("chatSessionStore.save(snapshot, knowledgeBindingsForSave, removedChatOwners)") <
                owner.indexOf("engine.invalidateConversationContext()")
        )
        assertTrue(owner.contains("port.cancelPendingWorldBookCleanup(WorldBookScope.CHAT, removedChatOwners)"))
        assertTrue(owner.contains("restoreAfterConversationMutationFailure("))
        assertTrue(owner.contains("port.conversationMutationBarrier = mutation"))
        assertTrue(owner.contains("port.localConversationContextInvalidationJob = mutation"))
        assertTrue(owner.contains("port.localConversationContextNeedsInvalidation = !preserveReusableNativePrefix"))
    }

    @Test
    fun theViewModelKeepsOnlyTheMutationDelegate() {
        val vm = sourceFile("MainViewModel.kt")

        // The chat edit/quote delegates call the same entry point, so it is module-internal rather
        // than file-private; there must still be exactly one definition.
        assertEquals(1, Regex("""internal fun persistConversationMutation\(""").findAll(vm).count())
        assertTrue(vm.contains("runPersistConversationMutation("))
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

    private fun ownerSource(): String = sourceFile("ChatSessionPersistenceOperations.kt")

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
