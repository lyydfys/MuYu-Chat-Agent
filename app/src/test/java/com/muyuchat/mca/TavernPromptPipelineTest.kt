package com.muyuchat.mca

import com.muyuchat.feature.chat.tavern.TAVERN_REGEX_SCRIPTS_KEY
import com.muyuchat.feature.chat.tavern.TavernRegexPlacement
import com.muyuchat.feature.chat.tavern.TavernRegexScript
import com.muyuchat.feature.chat.tavern.TavernRegexScriptParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
