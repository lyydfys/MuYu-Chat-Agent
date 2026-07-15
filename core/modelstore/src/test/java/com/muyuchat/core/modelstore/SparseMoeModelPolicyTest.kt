package com.muyuchat.core.modelstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SparseMoeModelPolicyTest {
    @Test
    fun qwenArchitectureIsAuthoritative() {
        val info = SparseMoeModelPolicy.inspect(
            architecture = "qwen35moe",
            names = listOf("local-model.gguf")
        )

        assertTrue(info.isSparseMoe)
        assertEquals(SparseMoeEvidence.GGUF_ARCHITECTURE, info.evidence)
    }

    @Test
    fun explicitTotalActiveScaleWithoutArchitectureIsNotAdmissionEvidence() {
        val info = SparseMoeModelPolicy.inspect(
            architecture = null,
            names = listOf("Qwen3.6-35B-A3B-APEX-MTP-I-Nano.gguf")
        )

        assertFalse(info.isSparseMoe)
        assertEquals(SparseMoeEvidence.NONE, info.evidence)
    }

    @Test
    fun denseArchitectureCannotBeOverriddenByTotalActiveNameScale() {
        val info = SparseMoeModelPolicy.inspect(
            architecture = "qwen3",
            names = listOf("Qwen3.6-35B-A3B-renamed-dense.gguf")
        )

        assertFalse(info.isSparseMoe)
        assertEquals(SparseMoeEvidence.NONE, info.evidence)
    }

    @Test
    fun gemmaA4bScaleIsRecognized() {
        val info = SparseMoeModelPolicy.inspect(
            architecture = "gemma4_moe",
            names = listOf("google-gemma-4-26B-A4B-it-IQ2_XXS.gguf")
        )

        assertTrue(info.isSparseMoe)
        assertEquals(26.0, info.totalParametersB!!, 0.0)
        assertEquals(4.0, info.activeParametersB!!, 0.0)
    }

    @Test
    fun denseModelIsNotMisclassifiedBySizeOrNameFragment() {
        val dense = SparseMoeModelPolicy.inspect(
            architecture = "qwen3",
            names = listOf("some-model-35B-IQ2_XXS.gguf", "awesome-chat.gguf")
        )

        assertFalse(dense.isSparseMoe)
        assertEquals(SparseMoeEvidence.NONE, dense.evidence)
    }
}
