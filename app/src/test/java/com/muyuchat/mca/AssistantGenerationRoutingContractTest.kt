package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantGenerationRoutingContractTest {
    @Test
    fun chatParameterEditsWriteTheModelPresetRatherThanTheRoleCard() {
        val body = functionBody(sourceFile("MainViewModelParamDelegates.kt"), "updateParams")

        assertTrue(body.contains("persistModelGenerationParams("))
        assertTrue(body.contains("effectiveParams"))
        assertTrue(body.contains("assistantGenerationOverrideFieldsOrNull"))
        assertFalse(body.contains("updatedAssistantsWithParams"))
        assertFalse(body.contains("persistAssistantsAsync"))
    }

    @Test
    fun savingARoleCardWritesSparseOverridesWithoutOverwritingTheModelPreset() {
        val body = functionBody(sourceFile("AssistantProfileOperations.kt"), "saveAssistantProfileFor")

        assertTrue(body.contains("toAssistantGenerationJsonWithOverrides(generationOverrideFields)"))
        assertTrue(body.contains("modelGenerationProfileStore.loadOrCreate"))
        assertFalse(body.contains("persistModelGenerationParams"))
    }

    @Test
    fun assistantAndSessionSwitchesResolveOverridesOnTopOfTheModelPreset() {
        val assistantSwitch = functionBody(
            sourceFile("AssistantModelSelectionOperations.kt"),
            "finishSelectingNewAssistantFor"
        )
        val sessionSwitch = functionBody(
            sourceFile("SelectChatSessionOperations.kt"),
            "selectChatSession"
        )

        assertTrue(assistantSwitch.contains("modelGenerationProfileStore.loadOrCreate"))
        assertTrue(assistantSwitch.contains("assistant.toGenerationParams(modelPreset)"))
        assertTrue(sessionSwitch.contains("port.loadModelGenerationParams("))
        assertTrue(
            sessionSwitch.indexOf("port.loadModelGenerationParams(") <
                sessionSwitch.indexOf("port.toGenerationParams(record, modelPreset)")
        )
    }

    private fun functionBody(source: String, name: String): String {
        val declaration = source.indexOf("fun $name")
            .takeIf { it >= 0 }
            ?: source.indexOf(".$name(")
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
