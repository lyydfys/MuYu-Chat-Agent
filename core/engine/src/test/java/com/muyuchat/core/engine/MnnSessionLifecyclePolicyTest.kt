package com.muyuchat.core.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MnnSessionLifecyclePolicyTest {
    @Test
    fun gemma4TextIsolationRequiresFreshSuccessfulTextTurns() {
        val bundle = bundle(
            rootConfig = """{"is_visual":false,"llm_config":"metadata/llm.json"}""",
            llmConfigPath = "metadata/llm.json",
            llmConfig = """{"model_type":"gemma4","is_visual":false}"""
        )

        assertTrue(
            MnnSessionLifecyclePolicy.requiresFreshSessionAfterSuccessfulTextTurn(
                File(bundle, "config.json").absolutePath
            )
        )
    }

    @Test
    fun qwenAndGemmaVisualBundlesKeepTheDefaultPolicy() {
        val qwen = bundle(
            rootConfig = """{"is_visual":false}""",
            llmConfig = """{"model_type":"qwen3_5","is_visual":false}"""
        )
        val gemmaVisual = bundle(
            rootConfig = """{"is_visual":true,"visual_model":"visual.mnn"}""",
            llmConfig = """{"model_type":"gemma4","is_visual":true}"""
        )

        assertFalse(MnnSessionLifecyclePolicy.requiresFreshSessionAfterSuccessfulTextTurn(qwen.absolutePath))
        assertFalse(
            MnnSessionLifecyclePolicy.requiresFreshSessionAfterSuccessfulTextTurn(gemmaVisual.absolutePath)
        )
    }

    @Test
    fun malformedOrEscapingMetadataNeverEnablesTheCompatibilityPolicy() {
        val malformed = bundle(
            rootConfig = """{"is_visual":false}""",
            llmConfig = "not-json"
        )
        val escaping = bundle(
            rootConfig = """{"is_visual":false,"llm_config":"../outside.json"}""",
            llmConfig = """{"model_type":"gemma4","is_visual":false}"""
        )
        File(escaping.parentFile, "outside.json").writeText(
            """{"model_type":"gemma4","is_visual":false}"""
        )

        assertFalse(MnnSessionLifecyclePolicy.requiresFreshSessionAfterSuccessfulTextTurn(malformed.absolutePath))
        assertFalse(MnnSessionLifecyclePolicy.requiresFreshSessionAfterSuccessfulTextTurn(escaping.absolutePath))
    }

    private fun bundle(
        rootConfig: String,
        llmConfigPath: String = "llm_config.json",
        llmConfig: String
    ): File = Files.createTempDirectory("mnn-session-policy").toFile().also { root ->
        File(root, "config.json").writeText(rootConfig)
        File(root, llmConfigPath).apply {
            parentFile?.mkdirs()
            writeText(llmConfig)
        }
    }
}
