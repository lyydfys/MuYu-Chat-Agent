package com.muyuchat.mca

import com.muyuchat.api.local.imagePromptExecutionSha256
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImageQnnGenerationOptionsTest {
    @Test
    fun `explicit qnn controls survive product worker resolution`() {
        val contract = resolveQnnImageGenerationContract(
            family = LocalImageModelFamily.SD15,
            defaultWidth = 512,
            defaultHeight = 512,
            defaultThreads = 4,
            fallbackSeed = 1,
            options = LocalImageGenerationOptions(
                width = 512,
                height = 512,
                steps = 20,
                threads = 4,
                seed = 42,
                cfgScale = 7.0,
                sampleMethod = "pndm",
                backendMode = "cpu",
                tokenEmbeddingMode = "auto",
                memoryMode = 0,
                useCfg = true
            )
        )

        assertEquals(20, contract.steps)
        assertEquals(42, contract.seed)
        assertEquals(7.0, contract.cfgScale, 0.0)
        assertEquals("pndm", contract.sampleMethod)
        assertEquals("cpu", contract.backendMode)
        assertEquals(20, contract.auditJson.getInt("steps"))
    }

    @Test
    fun `qnn rejects silent dimension and unknown sampler fallback`() {
        assertInvalid {
            resolveQnnImageGenerationContract(
                family = LocalImageModelFamily.SD15,
                defaultWidth = 512,
                defaultHeight = 512,
                defaultThreads = 4,
                fallbackSeed = 1,
                options = LocalImageGenerationOptions(width = 384, height = 384)
            )
        }
        assertInvalid {
            resolveQnnImageGenerationContract(
                family = LocalImageModelFamily.SD15,
                defaultWidth = 512,
                defaultHeight = 512,
                defaultThreads = 4,
                fallbackSeed = 1,
                options = LocalImageGenerationOptions(sampleMethod = "not_a_real_sampler")
            )
        }
        assertInvalid {
            resolveQnnImageGenerationContract(
                family = LocalImageModelFamily.SD15,
                defaultWidth = 512,
                defaultHeight = 512,
                defaultThreads = 4,
                fallbackSeed = 1,
                options = LocalImageGenerationOptions(distilledGuidance = 3.5)
            )
        }
        assertInvalid {
            resolveQnnImageGenerationContract(
                family = LocalImageModelFamily.SD15,
                defaultWidth = 512,
                defaultHeight = 512,
                defaultThreads = 4,
                fallbackSeed = 1,
                options = LocalImageGenerationOptions(flowShift = 3.0)
            )
        }
    }

    @Test
    fun `qnn conditional only execution rejects a nonempty negative prompt`() {
        assertEquals(
            "",
            resolveQnnExecutedNegativePrompt(
                useCfg = false,
                effectiveNegativePrompt = ""
            )
        )
        assertEquals(
            "blur, artifacts",
            resolveQnnExecutedNegativePrompt(
                useCfg = true,
                effectiveNegativePrompt = "blur, artifacts"
            )
        )
        val rejected = try {
            resolveQnnExecutedNegativePrompt(
                useCfg = false,
                effectiveNegativePrompt = "blur, artifacts"
            )
            null
        } catch (error: LocalImageProductContractException) {
            error
        }
        assertEquals("execution_contract_unsupported", requireNotNull(rejected).code)
    }

    @Test
    fun `qnn prompt preparation ignores only an unexecuted model default when cfg is off`() {
        val ignoredDefault = resolveQnnFinalNegativePromptForExecution(
            finalNegativePrompt = LocalImageFinalNegativePrompt(
                value = "人物，文字",
                source = LocalImageNegativePromptSource.MODEL_DEFAULT
            ),
            useCfg = false
        )
        assertEquals("", ignoredDefault.value)
        assertEquals(LocalImageNegativePromptSource.EMPTY, ignoredDefault.source)

        val explicitEmpty = resolveQnnFinalNegativePromptForExecution(
            finalNegativePrompt = LocalImageFinalNegativePrompt(
                value = "",
                source = LocalImageNegativePromptSource.USER
            ),
            useCfg = false
        )
        assertEquals(LocalImageNegativePromptSource.USER, explicitEmpty.source)

        val retainedDefault = resolveQnnFinalNegativePromptForExecution(
            finalNegativePrompt = LocalImageFinalNegativePrompt(
                value = "people, text",
                source = LocalImageNegativePromptSource.MODEL_DEFAULT
            ),
            useCfg = true
        )
        assertEquals("people, text", retainedDefault.value)
        assertEquals(LocalImageNegativePromptSource.MODEL_DEFAULT, retainedDefault.source)

        val rejected = runCatching {
            resolveQnnFinalNegativePromptForExecution(
                finalNegativePrompt = LocalImageFinalNegativePrompt(
                    value = "people, text",
                    source = LocalImageNegativePromptSource.USER
                ),
                useCfg = false
            )
        }.exceptionOrNull() as? LocalImageProductContractException
        assertEquals("execution_contract_unsupported", requireNotNull(rejected).code)
    }

    @Test
    fun `qnn prompt evidence binds ordered positive and actually executed negative prompts`() {
        val prompt = "one red cup on a blue table"
        val negativePrompt = "people, text"
        val cfgEvidence = promptEvidence(
            imagePromptExecutionSha256(prompt, negativePrompt)
        )
        requireNativePromptEncodingEvidence(
            source = cfgEvidence,
            prompt = prompt,
            negativePrompt = resolveQnnExecutedNegativePrompt(
                useCfg = true,
                effectiveNegativePrompt = negativePrompt
            )
        )
        assertInvalid {
            requireNativePromptEncodingEvidence(
                source = cfgEvidence,
                prompt = negativePrompt,
                negativePrompt = prompt
            )
        }

        val actualNegativePrompt = resolveQnnExecutedNegativePrompt(
            useCfg = false,
            effectiveNegativePrompt = ""
        )
        requireNativePromptEncodingEvidence(
            source = promptEvidence(
                imagePromptExecutionSha256(prompt, actualNegativePrompt)
            ),
            prompt = prompt,
            negativePrompt = actualNegativePrompt
        )
        assertInvalid {
            requireNativePromptEncodingEvidence(
                source = promptEvidence(
                    imagePromptExecutionSha256(prompt, negativePrompt)
                ),
                prompt = prompt,
                negativePrompt = actualNegativePrompt
            )
        }
    }

    private fun promptEvidence(promptExecutionSha256: String): JSONObject = JSONObject()
        .put("promptWeightingApplied", false)
        .put("positiveWeightedTokenCount", 0)
        .put("negativeWeightedTokenCount", 0)
        .put("promptWeightFingerprint", "a".repeat(64))
        .put("nativePromptExecutionSha256", promptExecutionSha256)
        .put("nativePromptBindingStage", "conditioning_encoded")

    private fun assertInvalid(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }
}
