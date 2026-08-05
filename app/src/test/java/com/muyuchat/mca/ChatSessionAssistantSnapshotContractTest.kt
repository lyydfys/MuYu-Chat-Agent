package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionAssistantSnapshotContractTest {
    @Test
    fun snapshotIsReadFromLegacyAndRoomPathsAndWrittenBackToRoom() {
        val source = sourceFile("ChatSessionStore.kt")

        assertTrue(source.contains("optString(\"assistantSnapshotJson\")"))
        assertTrue(source.contains("optJSONObject(\"assistantSnapshot\")?.toString()"))
        assertTrue(source.contains("session.assistantSnapshotJson"))
        assertTrue(source.contains("val assistantSnapshotJson: String? = null"))
        assertTrue(source.contains("assistantSnapshotJson = assistantSnapshot?.toJsonString()"))
    }

    @Test
    fun activeConversationUsesItsSnapshotForTheRequestAndPrefixCache() {
        val source = sourceFile("MainViewModel.kt")
        val startGeneration = functionBody(source, "startGeneration")
        val sendMessage = functionBody(source, "sendMessage")

        assertTrue(startGeneration.contains("val assistantSnapshot = initialState.activeAssistantSnapshot()"))
        assertTrue(startGeneration.contains("val requestParams = assistantSnapshot?.applyTo(baseParams) ?: baseParams"))
        assertTrue(startGeneration.contains("assistantId = assistantSnapshot?.assistantId"))
        assertTrue(startGeneration.contains("persistentLlamaPrefix"))
        assertTrue(sendMessage.contains("val assistantSnapshot = state.activeAssistantSnapshot()"))
        assertTrue(sendMessage.contains("assistantSnapshot = assistantSnapshot"))
    }

    @Test
    fun replacingAnActivePersonaInvalidatesTheLocalConversationKv() {
        val source = sourceFile("MainViewModel.kt")

        assertTrue(functionBody(source, "selectAssistant")
            .contains("markLocalConversationContextInvalid()"))
        assertTrue(functionBody(source, "finishCharacterCardImport")
            .contains("markLocalConversationContextInvalid()"))
    }

    @Test
    fun characterCardImportCommitsItsAssistantBeforePublishingEmbeddedWorldBook() {
        val body = functionBody(sourceFile("MainViewModel.kt"), "finishCharacterCardImport")

        val assistantCommit = body.indexOf("assistantStore.saveAssistants(updatedAssistants)")
        val worldBookPublish = body.indexOf("embeddedWorldBook?.let(worldBookStore::upsert)")
        val rollback = body.indexOf("assistantStore.saveAssistants(state.assistants)")

        assertTrue(assistantCommit >= 0)
        assertTrue(worldBookPublish > assistantCommit)
        assertTrue(rollback > worldBookPublish)
    }

    @Test
    fun openingAConversationPrefersItsCapturedAssistantIdentity() {
        val source = sourceFile("MainViewModel.kt")

        assertTrue(functionBody(source, "selectChatSession")
            .contains("session.assistantSnapshot?.assistantId ?: session.assistantId"))
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
