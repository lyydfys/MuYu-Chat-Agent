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
 *
 * Fail-open on a destroyed message: an imported preset can carry a `placement=1` script whose
 * pattern matches ordinary typed text and whose replacement is empty (`/^\d+$/ -> ""` is a common
 * "strip bare numbers" rule). Applying it literally deletes what the user just typed, and the old
 * send guard then refused the turn with "消息为空" — the user cannot send and cannot tell why. When
 * the *regex* stage alone empties a non-empty input, the original text is kept and the turn still
 * sends, with [TavernUserInputTransform.clearedByPreset] so the UI can say what happened. A blank
 * result that survives the regex stage (a pure `{{setvar::…}}` command) is left blank: that one is
 * the requested behavior, not damage.
 */
internal object TavernPromptPipeline {
    private val macroEngine = TavernMacroEngine()

    fun transformUserInput(state: MainUiState, text: String): String =
        transformUserInputFor(state, text).text

    /**
     * Same transform as [transformUserInput], plus whether a preset script — not the user's
     * macros — is what emptied the text.
     */
    fun transformUserInputFor(state: MainUiState, text: String): TavernUserInputTransform {
        if (text.isEmpty()) return TavernUserInputTransform(text, clearedByPreset = false)
        val preset = activePreset(state)
            ?: return TavernUserInputTransform(text, clearedByPreset = false)
        val processor = TavernRegexPostProcessor.fromPresetParams(preset.paramsJson)
        if (!processor.hasPresetKey) return TavernUserInputTransform(text, clearedByPreset = false)
        val context = macroContext(state)
        val regexed = processor.applyToUserInput(text)
        if (regexed.isBlank() && text.isNotBlank()) {
            // Keep what the user typed rather than refusing to send.
            return TavernUserInputTransform(text, clearedByPreset = true)
        }
        return TavernUserInputTransform(
            text = macroEngine.expand(regexed, context),
            clearedByPreset = false
        )
    }

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
            val contract = processor.applyToAssistantOutput(macroed)
            // A reply is never deleted on the model's behalf: an output script that matches the
            // whole reply and replaces it with nothing would otherwise persist an empty assistant
            // message and wipe the answer the user just waited for.
            if (contract.isBlank() && macroed.isNotBlank()) macroed else contract
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

/** Result of the user-input transform: the text to send and where a blank came from. */
internal data class TavernUserInputTransform(
    val text: String,
    /** True when a preset `placement=1` script emptied the typed text and it was kept as-is. */
    val clearedByPreset: Boolean
)
