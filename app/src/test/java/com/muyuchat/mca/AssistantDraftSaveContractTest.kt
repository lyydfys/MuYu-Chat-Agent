package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantDraftSaveContractTest {
    @Test
    fun rejectedSaveReturnsFalseBeforeStateOrPersistenceWrites() {
        val source = sourceFile("AssistantProfileOperations.kt")
        val body = functionBody(source, "saveAssistantProfileFor")
        val rejection = body.indexOf("return false")
        val stateRead = body.indexOf("val state = store.state.value")
        val stateWrite = body.indexOf("store.update {")
        val assistantPersistence = body.indexOf("persistAssistantsAsync()")

        assertTrue(stateRead >= 0)
        assertTrue(rejection > stateRead)
        assertTrue(stateWrite > rejection)
        assertTrue(assistantPersistence > rejection)
        assertFalse(body.substring(0, rejection).contains("store.update"))
        assertFalse(body.substring(0, rejection).contains("persistAssistantsAsync()"))
    }

    @Test
    fun acceptedSaveReportsSuccessAfterTheExistingCommitPath() {
        val source = sourceFile("AssistantProfileOperations.kt")
        val declaration = source.indexOf("internal fun saveAssistantProfileFor(")
        val body = functionBody(source, "saveAssistantProfileFor")

        assertTrue(declaration >= 0)
        assertTrue(source.substring(declaration, source.indexOf('{', declaration)).contains("): Boolean"))
        assertTrue(body.trimEnd().endsWith("return true"))
    }

    @Test
    fun onSaveAssistantReturnsTheBusinessOutcomeToTheEditor() {
        val screenActions = sourceFile("MainChatScreenActions.kt")
        val viewModelDelegate = sourceFile("MainViewModelAssistantAndKnowledgeDelegates.kt")
        val coordinator = sourceFile("AssistantProfileCoordinator.kt")

        assertTrue(screenActions.contains("onSaveAssistant = { draft: AssistantEditorDraft ->"))
        assertTrue(screenActions.contains("viewModel.saveAssistantProfile("))
        assertTrue(viewModelDelegate.contains("): Boolean = assistantProfileCoordinator.saveAssistantProfile("))
        assertTrue(coordinator.contains("): Boolean = saveAssistantProfileFor("))
    }

    private fun functionBody(source: String, name: String): String {
        val declaration = source.indexOf("fun $name")
        require(declaration >= 0) { "Missing function $name" }
        val openingBrace = source.indexOf('{', declaration)
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(openingBrace + 1, index)
                }
            }
        }
        error("Unterminated function $name")
    }

    private fun sourceFile(name: String): String = sequenceOf(
        File("src/main/java/com/muyuchat/mca/$name"),
        File("app/src/main/java/com/muyuchat/mca/$name")
    ).firstOrNull(File::isFile)?.readText(Charsets.UTF_8)
        ?: error("Unable to locate $name")
}
