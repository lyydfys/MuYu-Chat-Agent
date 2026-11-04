package com.muyuchat.mca

import com.muyuchat.feature.chat.tavern.TavernMacroContext
import com.muyuchat.feature.chat.tavern.TavernMacroEngine
import com.muyuchat.feature.chat.tavern.TavernRegexPostProcessor

/**
 * Thin application-side adapter between a bound generation preset and the pure Tavern pipeline.
 *
 * Gate: the current chat model must be bound to a preset whose `paramsJson` contains
 * `tavern_regex_scripts`. A preset without that key, or a model with no bound preset, returns the
 * input unchanged — the old behavior is therefore byte-identical for existing installs.
 *
 * User input: `regex_scripts(placement=1)` then macro expansion, because Tavern edits the typed text
 * before it reaches the prompt. Assistant output: macro expansion then `regex_scripts(placement=2)`,
 * matching Tavern's "format the model's reply" order.
 *
 * The macro engine is process-scoped so `setvar`/`getvar` survive later turns in the same session;
 * the variable store itself is in-memory and is cleared by an app restart (documented in
 * [com.muyuchat.feature.chat.tavern.InMemoryTavernVariableStore]).
 */
internal object TavernPromptPipeline {
    private val macroEngine = TavernMacroEngine()

    fun transformUserInput(state: MainUiState, text: String): String =
        transform(state, text, userInput = true)

    fun transformAssistantOutput(state: MainUiState, text: String): String =
        transform(state, text, userInput = false)

    /** The preset whose model binding matches the current chat model, if any. */
    internal fun activePreset(state: MainUiState): GenerationPresetRecord? {
        val modelId = state.currentChatModelId() ?: return null
        return when (state.selectedChatBackend) {
            ChatBackend.LOCAL -> state.generationPresets.firstOrNull {
                modelId in it.boundLocalModelIds
            }

            ChatBackend.CLOUD -> state.generationPresets.firstOrNull {
                modelId in it.boundCloudModelIds
            }
        }
    }

    private fun transform(state: MainUiState, text: String, userInput: Boolean): String {
        if (text.isEmpty()) return text
        val preset = activePreset(state) ?: return text
        val processor = TavernRegexPostProcessor.fromPresetParams(preset.paramsJson)
        if (!processor.hasPresetKey) return text
        val context = macroContext(state)
        return if (userInput) {
            val regexed = processor.applyToUserInput(text)
            macroEngine.expand(regexed, context)
        } else {
            val macroed = macroEngine.expand(text, context)
            processor.applyToAssistantOutput(macroed)
        }
    }

    internal fun macroContext(state: MainUiState): TavernMacroContext {
        val snapshot = state.activeAssistantSnapshot()
        val assistant = snapshot?.let { frozen ->
            state.assistants.firstOrNull { it.id == frozen.assistantId }
        } ?: state.selectedAssistant()
        val card = assistant?.characterCardJson?.let { raw ->
            (runCatching { CharacterCardCodec.parseJson(raw) }.getOrNull()
                as? CharacterCardParseResult.Success)?.card
        }
        return TavernMacroContext(
            sessionId = state.activeChatSessionId.orEmpty(),
            // MCA has no user-persona settings yet; the empty persona and "User" name are explicit
            // placeholders so a later settings surface can supply real values without changing the
            // macro engine.
            userName = DEFAULT_USER_NAME,
            charName = card?.name?.takeIf(String::isNotBlank)
                ?: snapshot?.name
                ?: assistant?.name.orEmpty(),
            description = card?.description.orEmpty(),
            personality = card?.personality.orEmpty(),
            scenario = card?.scenario.orEmpty(),
            persona = ""
        )
    }

    private const val DEFAULT_USER_NAME = "User"
}
