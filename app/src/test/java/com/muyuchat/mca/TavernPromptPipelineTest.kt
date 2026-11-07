package com.muyuchat.mca

import com.muyuchat.feature.chat.tavern.TAVERN_REGEX_SCRIPTS_KEY
import com.muyuchat.feature.chat.tavern.TavernRegexPlacement
import com.muyuchat.feature.chat.tavern.TavernRegexScript
import com.muyuchat.feature.chat.tavern.TavernRegexScriptParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernPromptPipelineTest {
    private fun state(
        paramsJson: String,
        boundLocalModelIds: Set<String> = setOf("local-1"),
        loadedModelId: String = "local-1"
    ): MainUiState {
        val preset = GenerationPresetRecord(
            id = "preset-1",
            name = "Tavern",
            paramsJson = paramsJson,
            boundLocalModelIds = boundLocalModelIds
        )
        return MainUiState(
            activeChatSessionId = "session-1",
            selectedChatBackend = ChatBackend.LOCAL,
            loadedModelId = loadedModelId,
            generationPresets = listOf(preset)
        )
    }

    private fun params(
        vararg scripts: TavernRegexScript
    ): String = JSONObject()
        .put("temperature", 0.7)
        .put(TAVERN_REGEX_SCRIPTS_KEY, TavernRegexScriptParser.toJsonArray(scripts.toList()))
        .toString()

    @Test
    fun `user input runs regex then macro expansion`() {
        val state = state(
            params(
                TavernRegexScript(
                    name = "input",
                    findRegex = "foo",
                    replaceString = "bar",
                    placements = setOf(TavernRegexPlacement.USER_INPUT)
                )
            )
        )
        assertEquals("bar User", TavernPromptPipeline.transformUserInput(state, "foo {{user}}"))
    }

    @Test
    fun `assistant output runs macro expansion then regex`() {
        val state = state(
            params(
                TavernRegexScript(
                    name = "output",
                    findRegex = "raw",
                    replaceString = "clean",
                    placements = setOf(TavernRegexPlacement.ASSISTANT_OUTPUT)
                )
            )
        )
        assertEquals(
            "User clean",
            TavernPromptPipeline.transformAssistantOutput(state, "{{user}} raw")
        )
    }

    @Test
    fun `a preset without the regex key leaves both directions untouched`() {
        val state = state("""{"temperature":0.7}""")
        assertEquals("{{user}} foo", TavernPromptPipeline.transformUserInput(state, "{{user}} foo"))
        assertEquals("{{user}} foo", TavernPromptPipeline.transformAssistantOutput(state, "{{user}} foo"))
    }

    @Test
    fun `an unbound model leaves both directions untouched`() {
        val state = state(
            paramsJson = params(
                TavernRegexScript(
                    findRegex = "foo",
                    replaceString = "bar",
                    placements = setOf(TavernRegexPlacement.USER_INPUT, TavernRegexPlacement.ASSISTANT_OUTPUT)
                )
            ),
            boundLocalModelIds = setOf("another-model")
        )
        assertEquals("foo {{user}}", TavernPromptPipeline.transformUserInput(state, "foo {{user}}"))
        assertEquals("foo {{user}}", TavernPromptPipeline.transformAssistantOutput(state, "foo {{user}}"))
    }

    @Test
    fun `a present empty script array still enables macros`() {
        val state = state(params())
        assertEquals("User", TavernPromptPipeline.transformUserInput(state, "{{user}}"))
    }

    @Test
    fun `cloud binding is resolved for the selected cloud model`() {
        val preset = GenerationPresetRecord(
            id = "preset-cloud",
            name = "Cloud Tavern",
            paramsJson = params(
                TavernRegexScript(
                    findRegex = "foo",
                    replaceString = "bar",
                    placements = setOf(TavernRegexPlacement.USER_INPUT)
                )
            ),
            boundCloudModelIds = setOf("cloud-1")
        )
        val state = MainUiState(
            activeChatSessionId = "session-1",
            selectedChatBackend = ChatBackend.CLOUD,
            selectedCloudChatModelId = "cloud-1",
            generationPresets = listOf(preset)
        )
        assertEquals("bar", TavernPromptPipeline.transformUserInput(state, "foo"))
    }

    @Test
    fun `an input script that empties ordinary text keeps the typed text`() {
        // Real-world case from an imported preset: a "strip bare numbers" rule written as
        // ^[0-9]+$ -> "" also swallows a user who types just "4", and the old guard then refused
        // the turn with "消息为空" — the user could neither send nor understand why.
        val state = state(
            params(
                TavernRegexScript(
                    name = "strip digits",
                    findRegex = "^[0-9]+$",
                    replaceString = "",
                    placements = setOf(TavernRegexPlacement.USER_INPUT)
                )
            )
        )
        val transform = TavernPromptPipeline.transformUserInputFor(state, "4")
        assertEquals("4", transform.text)
        assertTrue(transform.clearedByPreset)
        assertEquals("4", TavernPromptPipeline.transformUserInput(state, "4"))
    }

    @Test
    fun `an input script still rewrites text it does not empty`() {
        val state = state(
            params(
                TavernRegexScript(
                    name = "strip digits",
                    findRegex = "^[0-9]+$",
                    replaceString = "",
                    placements = setOf(TavernRegexPlacement.USER_INPUT)
                )
            )
        )
        val transform = TavernPromptPipeline.transformUserInputFor(state, "你好 4")
        assertEquals("你好 4", transform.text)
        assertFalse(transform.clearedByPreset)
    }

    @Test
    fun `an output script that empties the whole reply keeps the reply`() {
        // Symmetric hazard on the assistant side: applying it literally would persist an empty
        // message and discard the answer the user waited for.
        val state = state(
            params(
                TavernRegexScript(
                    name = "wipe",
                    findRegex = "(?s).*",
                    replaceString = "",
                    placements = setOf(TavernRegexPlacement.ASSISTANT_OUTPUT)
                )
            )
        )
        assertEquals("答案", TavernPromptPipeline.transformAssistantOutput(state, "答案"))
    }

    @Test
    fun `an empty assistant output script is not treated as damage`() {
        // A script that legitimately removes only part of the reply must keep working.
        val state = state(
            params(
                TavernRegexScript(
                    name = "drop tag",
                    findRegex = "<StatusBlock/>",
                    replaceString = "",
                    placements = setOf(TavernRegexPlacement.ASSISTANT_OUTPUT)
                )
            )
        )
        assertEquals("正文", TavernPromptPipeline.transformAssistantOutput(state, "正文<StatusBlock/>"))
    }
}
