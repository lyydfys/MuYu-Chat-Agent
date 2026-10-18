package com.muyuchat.feature.chat

import com.muyuchat.core.engine.ReasoningMode
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantDraftSaveContractTest {
    @Test
    fun missingSaveCapabilityFailsClosed() {
        val draft = AssistantEditorDraft(
            id = null,
            name = "草稿",
            avatar = "",
            tag = "",
            systemPrompt = "保留这段提示词",
            defaultModelMode = "follow_current",
            defaultModelId = null,
            temperature = 0.7f,
            topP = 0.9f,
            nPredict = 512,
            reasoningMode = ReasoningMode.OFF,
            memoryEnabled = false,
            webSearchEnabled = false,
            fileContextEnabled = true
        )

        assertFalse(ChatContentActions().onSaveAssistant(draft))
    }

    @Test
    fun saveCallbackPropagatesBooleanThroughTheEditorCallChain() {
        val actions = sourceFile("ChatScreenActions.kt")
        val viewModel = sourceFile("ChatViewModel.kt")
        val auxiliaryPages = sourceFile("ChatAuxiliaryPages.kt")
        val rolePages = sourceFile("ChatScreenAssistantRolePages.kt")

        assertTrue(actions.contains("val onSaveAssistant: (AssistantEditorDraft) -> Boolean = { false }"))
        assertTrue(viewModel.contains("fun saveAssistant(draft: AssistantEditorDraft): Boolean ="))
        assertTrue(viewModel.contains("capabilities.content.onSaveAssistant(draft)"))
        assertTrue(auxiliaryPages.contains("onSaveAssistant: (AssistantEditorDraft) -> Boolean"))
        assertTrue(rolePages.contains("onSaveAssistant: (AssistantEditorDraft) -> Boolean"))
        assertTrue(rolePages.split("onSave = onSaveAssistant").size == 3)
        assertFalse(rolePages.contains("onSaveAssistant(it)"))
    }

    @Test
    fun rejectedSavePreservesTheDraftAndShowsAnInlineError() {
        val source = sourceFile("AssistantEditorPage.kt")
        val submit = functionBody(source, "submitDraft")
        val failureBranch = submit.indexOf("if (!onSave(draft))")
        val successClose = submit.indexOf("if (closeOnSuccess) onBack()")

        assertTrue(failureBranch >= 0)
        assertTrue(successClose > failureBranch)
        assertTrue(submit.contains("saveError = ASSISTANT_SAVE_DRAFT_PRESERVED_MESSAGE"))
        assertTrue(submit.substring(failureBranch, successClose).contains("return"))
        assertTrue(source.contains("var saveError by rememberSaveable(assistant?.id) { mutableStateOf<String?>(null) }"))
        assertTrue(source.contains("saveError?.let { message ->"))
        assertTrue(source.contains("color = MaterialTheme.colorScheme.error"))
        assertTrue(source.contains("草稿已保留，请结束后重试"))

        listOf(
            "name =",
            "avatar =",
            "tag =",
            "prompt =",
            "roleplayPresetId =",
            "defaultModelMode =",
            "defaultModelId =",
            "temperatureText =",
            "topPText =",
            "topKText =",
            "minPText =",
            "repeatPenaltyText =",
            "presencePenaltyText =",
            "frequencyPenaltyText =",
            "nPredictText =",
            "stopWordsText =",
            "reasoningMode =",
            "memoryEnabled =",
            "memorySummaryIntervalText =",
            "webSearchEnabled =",
            "fileContextEnabled ="
        ).forEach { fieldAssignment ->
            assertFalse("Rejected save must not reset $fieldAssignment", submit.contains(fieldAssignment))
        }
    }

    @Test
    fun saveClosesOnlyOnSuccessWhileCopyUsesTheSamePreservedDraftPath() {
        val source = sourceFile("AssistantEditorPage.kt")

        assertTrue(source.contains("submitDraft(buildDraft(assistant?.id), closeOnSuccess = true)"))
        assertTrue(source.contains("submitDraft(buildDraft(null, copyName), closeOnSuccess = false)"))
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
        File("src/main/java/com/muyuchat/feature/chat/$name"),
        File("feature/chat/src/main/java/com/muyuchat/feature/chat/$name")
    ).firstOrNull(File::isFile)?.readText(Charsets.UTF_8)
        ?: error("Unable to locate $name")
}
