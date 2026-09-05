package com.muyuchat.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiteRtLmChatRunnerTest {
    @Test
    fun assistantAndModelRolesShareTheLiteRtHistorySpelling() {
        assertEquals("model", canonicalLiteRtMessageRole(" assistant "))
        assertEquals("model", canonicalLiteRtMessageRole("MODEL"))
        assertEquals(
            canonicalLiteRtMessageRole("assistant"),
            canonicalLiteRtMessageRole("model")
        )
    }

    @Test
    fun unknownRolesAreIgnoredInsteadOfEnteringTheConversationHistory() {
        assertNull(canonicalLiteRtMessageRole("developer"))
        assertNull(canonicalLiteRtMessageRole(""))
    }

    @Test
    fun cpuAndGpuSamplerSettingsAreForwardedAndSanitized() {
        val params = GenerationParams(
            topK = 0,
            topP = 1.5f,
            temperature = -1.0f,
            seed = 123
        )

        val config = requireNotNull(liteRtSamplerValuesFor(params, "gpu"))

        assertEquals(1, config.topK)
        assertEquals(1.0, config.topP, 0.0)
        assertEquals(0.0, config.temperature, 0.0)
        assertEquals(123, config.seed)
    }

    @Test
    fun acceleratorSamplersUseLiteRtDefaultsWhenDelegateDoesNotSupportOverrides() {
        val params = GenerationParams(topK = 7, topP = 0.4f, temperature = 0.2f, seed = 9)

        assertNull(liteRtSamplerValuesFor(params, "npu"))
        assertNull(liteRtSamplerValuesFor(params, "google_tensor"))
    }
}
