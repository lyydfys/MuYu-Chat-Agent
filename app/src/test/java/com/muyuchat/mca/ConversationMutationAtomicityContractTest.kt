package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMutationAtomicityContractTest {
    @Test
    fun destructiveConversationCommandsShareTheRoomThenKvBoundary() {
        val source = mainViewModelSource()
        listOf(
            "regenerateLastResponse",
            "deleteMessageAt",
            "deleteLastConversationTurn",
            "stopGeneration",
            "deleteChatSession",
            "clearChatHistory"
        ).forEach { functionName ->
            val body = functionBody(source, functionName)
            assertTrue("$functionName must use the durable mutation boundary", body.contains("persistConversationMutation("))
            assertFalse("$functionName must not independently invalidate KV", body.contains("markLocalConversationContextInvalid()"))
        }

        val persist = functionBody(source, "persistConversationMutation")
        assertInOrder(
            persist,
            "chatSessionStore.save(snapshot, knowledgeBindingsForSave)",
            "durableChatSessions = snapshot",
            "engine.invalidateConversationContext()",
            "onCommitted?.invoke()"
        )
        assertTrue(persist.contains("restoreAfterConversationMutationFailure("))
        assertTrue(persist.contains("cancelPendingWorldBookCleanup("))
    }

    @Test
    fun regenerationStartsOnlyFromTheCommittedMutationCallback() {
        val body = functionBody(mainViewModelSource(), "regenerateLastResponse")
        assertTrue(body.contains("onCommitted = { startGeneration(kept.dropLast(1)) }"))
        assertFalse(body.contains("persistChatSessions("))
    }

    @Test
    fun mutationGateCoversCommandsThatCanReplaceTheVisibleConversation() {
        val source = mainViewModelSource()
        listOf(
            "sendMessage",
            "regenerateLastResponse",
            "deleteMessageAt",
            "deleteLastConversationTurn",
            "stopGeneration",
            "newChat",
            "selectChatSession",
            "deleteChatSession",
            "clearChatHistory"
        ).forEach { functionName ->
            assertTrue(
                "$functionName must reject interleaving with an active Room mutation",
                functionBody(source, functionName).contains("rejectWhileConversationMutationInProgress()")
            )
        }
    }

    private fun assertInOrder(source: String, vararg fragments: String) {
        var previous = -1
        fragments.forEach { fragment ->
            val index = source.indexOf(fragment)
            assertTrue("Missing fragment: $fragment", index >= 0)
            assertTrue("Out-of-order fragment: $fragment", index > previous)
            previous = index
        }
    }

    private fun functionBody(source: String, functionName: String): String {
        val declaration = source.indexOf("fun $functionName(")
        assertTrue("Missing function $functionName", declaration >= 0)
        val opening = source.indexOf('{', declaration)
        assertTrue("Missing body for $functionName", opening >= 0)
        var depth = 0
        for (index in opening until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(opening, index + 1)
                }
            }
        }
        error("Unterminated function $functionName")
    }

    private fun mainViewModelSource(): String {
        val root = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(root, "src/main/java/com/muyuchat/mca/MainViewModel.kt"),
            File(root, "app/src/main/java/com/muyuchat/mca/MainViewModel.kt")
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("MainViewModel.kt not found from $root")
    }
}
