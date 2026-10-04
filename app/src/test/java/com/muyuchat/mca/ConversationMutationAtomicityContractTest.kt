package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.muyuchat.mca.presentation.restoreAfterConversationMutationFailure

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
            // `stopGeneration` now delegates: its orchestration moved to its own operations file
            // during R1 (item 2), so the durable-boundary contract is read there as well.
            val body = functionBody(source, functionName) + movedOperationSource(functionName)
            assertTrue("$functionName must use the durable mutation boundary", body.contains("persistConversationMutation("))
            assertFalse("$functionName must not independently invalidate KV", body.contains("markLocalConversationContextInvalid()"))
        }

        // The stop-port object now carries an override with the same name; the durable boundary
        // itself lives in the private function, so it is located by its full declaration.
        // P4: the boundary moved to ChatSessionPersistenceOperations; the assertions are unchanged
        // -- the body is read where it now lives.
        val persist = functionBodyOf(
            chatSessionPersistenceSource(),
            "fun runPersistConversationMutation("
        )
        assertInOrder(
            persist,
            "chatSessionStore.save(snapshot, knowledgeBindingsForSave, removedChatOwners)",
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
        assertTrue(body.contains("val generationReservation = reserveUiGenerationStart() ?: return"))
        assertTrue(
            body.contains(
                "onCommitted = { startGeneration(kept.dropLast(1), generationReservation) }"
            )
        )
        assertTrue(
            body.contains(
                "onCommitFailed = { chatRuns.cancelPending(generationReservation) }"
            )
        )
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
            // `selectChatSession` and `stopGeneration` now delegate: the wrappers they leave behind
            // only forward, while the guard lives in the moved bodies. Both sources are searched so
            // the contract is still checked, without weakening the assertion.
            val body = functionBody(source, functionName) + movedOperationSource(functionName)
            assertTrue(
                "$functionName must reject interleaving with an active Room mutation",
                body.contains("rejectWhileConversationMutationInProgress()")
            )
        }
    }

    /**
     * Source of a function body that was moved out of the ViewModel during R1, keyed by the
     * delegating wrapper's name.
     *
     * The unit-test working directory is the app module, so the moved file is addressed
     * module-relatively. Both forms are tried so a repo-root working directory keeps working too.
     */
    private fun movedOperationSource(functionName: String): String {
        val relative = when (functionName) {
            "selectChatSession" -> "src/main/java/com/muyuchat/mca/SelectChatSessionOperations.kt"
            "stopGeneration" -> "src/main/java/com/muyuchat/mca/ChatStopGenerationOperations.kt"
            else -> return ""
        }
        return listOf(File(relative), File("app/$relative"))
            .firstOrNull(File::isFile)?.readText(Charsets.UTF_8).orEmpty()
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

    private fun functionBody(source: String, functionName: String): String =
        functionBodyOf(source, "fun $functionName(")

    private fun functionBodyOf(source: String, declaration: String): String {
        val declarationIndex = source.indexOf(declaration)
        assertTrue("Missing declaration $declaration", declarationIndex >= 0)
        val opening = source.indexOf('{', declarationIndex)
        assertTrue("Missing body for $declaration", opening >= 0)
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
        error("Unterminated function $declaration")
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

    private fun chatSessionPersistenceSource(): String {
        val root = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(root, "src/main/java/com/muyuchat/mca/ChatSessionPersistenceOperations.kt"),
            File(root, "app/src/main/java/com/muyuchat/mca/ChatSessionPersistenceOperations.kt")
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("ChatSessionPersistenceOperations.kt not found from $root")
    }
}
