package com.muyuchat.mca

import com.muyuchat.feature.chat.tavern.TAVERN_REGEX_SCRIPTS_KEY
import com.muyuchat.feature.chat.tavern.TavernRegexPlacement
import com.muyuchat.feature.chat.tavern.TavernRegexPostProcessor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationPresetTavernAdapterRegexTest {

    private fun tavernPreset(scripts: JSONArray): String = JSONObject()
        .put("preset_name", "Regex Tavern")
        .put("temperature", 0.7)
        .put("extensions", JSONObject().put("regex_scripts", scripts))
        .toString()

    @Test
    fun `tavern import keeps extensions regex scripts in the mapping`() {
        val raw = tavernPreset(
            JSONArray().put(
                JSONObject()
                    .put("findRegex", "<StatusBlock/>")
                    .put("replaceString", "")
                    .put("placement", JSONArray().put(1).put(2))
            )
        )
        val result = SillyTavernPresetAdapter.adapt(raw)
        val mapping = (result as GenerationPresetParseResult.Tavern).mapping

        assertEquals(1, mapping.regexScripts.size)
        assertEquals(
            setOf(TavernRegexPlacement.USER_INPUT, TavernRegexPlacement.ASSISTANT_OUTPUT),
            mapping.regexScripts.single().placements
        )
        assertFalse("extensions is now a handled section", "extensions" in mapping.ignoredKeys)
    }

    @Test
    fun `stored params carry the normalized regex key`() {
        val raw = tavernPreset(
            JSONArray().put(
                JSONObject()
                    .put("find_regex", "foo")
                    .put("replace_string", "bar")
                    .put("placement", 2)
            )
        )
        val mapping = (SillyTavernPresetAdapter.adapt(raw) as GenerationPresetParseResult.Tavern).mapping
        val params = generationPresetParamsJsonFor(mapping)

        assertTrue(params.contains(TAVERN_REGEX_SCRIPTS_KEY))
        val processor = TavernRegexPostProcessor.fromPresetParams(params)
        assertTrue(processor.hasPresetKey)
        assertEquals("bar", processor.applyToAssistantOutput("foo"))
    }

    @Test
    fun `a malformed regex entry does not reject the preset`() {
        val raw = tavernPreset(
            JSONArray()
                .put(JSONObject().put("replaceString", "no find regex"))
                .put(JSONObject().put("findRegex", "ok").put("replaceString", "fine"))
        )
        val mapping = (SillyTavernPresetAdapter.adapt(raw) as GenerationPresetParseResult.Tavern).mapping

        assertEquals(1, mapping.regexScripts.size)
        assertTrue(mapping.regexWarnings.any { it.contains("缺少 findRegex") })
        val processor = TavernRegexPostProcessor.fromPresetParams(generationPresetParamsJsonFor(mapping))
        assertEquals("fine", processor.applyToAssistantOutput("ok"))
    }

    @Test
    fun `an empty source regex array still opts into the Tavern pipeline`() {
        val raw = tavernPreset(JSONArray())
        val mapping = (SillyTavernPresetAdapter.adapt(raw) as GenerationPresetParseResult.Tavern).mapping
        assertTrue(mapping.hasRegexScriptsKey)
        assertTrue(generationPresetParamsJsonFor(mapping).contains(TAVERN_REGEX_SCRIPTS_KEY))
    }

    @Test
    fun `a tavern preset without regex scripts never gains the tavern key`() {
        val raw = JSONObject()
            .put("temperature", 0.7)
            .put("top_p", 0.9)
            .put("max_tokens", 512)
            .toString()
        val mapping = (SillyTavernPresetAdapter.adapt(raw) as GenerationPresetParseResult.Tavern).mapping
        assertFalse(generationPresetParamsJsonFor(mapping).contains(TAVERN_REGEX_SCRIPTS_KEY))
    }
}
