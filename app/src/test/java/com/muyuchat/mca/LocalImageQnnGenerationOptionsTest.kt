package com.muyuchat.mca

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
