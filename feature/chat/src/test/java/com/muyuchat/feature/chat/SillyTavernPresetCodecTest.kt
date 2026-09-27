package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SillyTavernPresetCodecTest {
    @Test
    fun importsSamplerFieldsAndReportsUnsupportedPromptTemplates() {
        val preset = SillyTavernPresetCodec.parse(
            """
            {
              "name": "角色对话",
              "temperature": 0.82,
              "top_p": 0.94,
              "top_k": 40,
              "min_p": 0.05,
              "rep_pen": 1.12,
              "presence_penalty": 0.1,
              "frequency_penalty": 0.2,
              "max_length": 640,
              "stop": ["<END>", "\nUser:"],
              "prompts": [{"identifier": "jailbreak", "content": "ignored prompt body"}],
              "prompt_order": [{"identifier": "main", "enabled": true}],
              "max_context_length": 8192
            }
            """.trimIndent()
        )

        assertEquals("角色对话", preset.name)
        assertEquals(0.82f, preset.temperature!!, 0.0001f)
        assertEquals(0.94f, preset.topP!!, 0.0001f)
        assertEquals(40, preset.topK)
        assertEquals(0.05f, preset.minP!!, 0.0001f)
        assertEquals(1.12f, preset.repeatPenalty!!, 0.0001f)
        assertEquals(640, preset.maxTokens)
        assertEquals(listOf("<END>", "\nUser:"), preset.stopWords)
        assertTrue(preset.ignoredPromptFields.contains("max_context_length"))
        assertTrue(preset.promptAppendix!!.contains("ignored prompt body"))
        assertEquals(listOf("jailbreak"), preset.promptIdentifiers)
    }

    @Test
    fun importsNestedSamplerSettingsAndAliases() {
        val preset = SillyTavernPresetCodec.parse(
            """{"name":"nested","settings":{"temp":"0.7","topP":0.9,"topK":24,"repetition_penalty":1.08,"max_tokens":320}}"""
        )

        assertEquals(0.7f, preset.temperature!!, 0.0001f)
        assertEquals(0.9f, preset.topP!!, 0.0001f)
        assertEquals(24, preset.topK)
        assertEquals(1.08f, preset.repeatPenalty!!, 0.0001f)
        assertEquals(320, preset.maxTokens)
    }

    @Test
    fun importsPromptOnlyFilesAndRejectsOutOfRangeSamplingValues() {
        val promptOnly = SillyTavernPresetCodec.parse(
            """{"prompts":[{"content":"template only"}]}"""
        )
        assertEquals("template only", promptOnly.promptAppendix)

        val invalid = runCatching {
            SillyTavernPresetCodec.parse("""{"temperature":8,"top_p":0.9}""")
        }
        assertTrue(invalid.isFailure)
    }

    @Test
    fun importsPromptMapUsedByOlderTavernExports() {
        val preset = SillyTavernPresetCodec.parse(
            """{"temperature":0.8,"prompts":{"main":{"content":"system rules"},"jailbreak":"stay in character"}}"""
        )

        assertTrue(preset.promptAppendix!!.contains("system rules"))
        assertTrue(preset.promptAppendix!!.contains("stay in character"))
        assertEquals(listOf("main", "jailbreak"), preset.promptIdentifiers)
    }

    @Test
    fun roleplayStyleChangesKeepCharacterPromptAndReplaceThePreviousStyle() {
        val characterPrompt = "角色基础设定：说话温和。"
        val immersive = requireNotNull(RoleplayStylePresets.all.firstOrNull { it.id == "immersive" })
        val concise = requireNotNull(RoleplayStylePresets.all.firstOrNull { it.id == "concise" })

        val first = RoleplayStylePresets.withPresetPrompt(characterPrompt, immersive)
        val second = RoleplayStylePresets.withPresetPrompt(first, concise)

        assertTrue(second.startsWith(characterPrompt))
        assertTrue(second.contains(concise.instruction))
        assertTrue(!second.contains(immersive.instruction))
        assertEquals("concise", RoleplayStylePresets.selectedPresetId(second))
    }

    @Test
    fun promptAppendixMergesOnceAndKeepsCharacterPrompt() {
        val first = SillyTavernPresetCodec.mergePromptAppendix("角色基础设定", "说话简洁")
        val second = SillyTavernPresetCodec.mergePromptAppendix(first, "新的内容")
        assertTrue(second.startsWith("角色基础设定"))
        assertTrue(!second.contains("说话简洁"))
        assertTrue(second.contains("新的内容"))
    }
}
