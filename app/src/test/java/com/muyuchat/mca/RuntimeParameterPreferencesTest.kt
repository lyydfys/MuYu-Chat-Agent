package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeParameterPreferencesTest {
    @Test
    fun runtimeContextSurvivesAssistantSemanticPersistenceBoundary() {
        val requested = GenerationParams(
            nCtx = 8192,
            nThreads = 6,
            nPredict = 1024,
            temperature = 0.42f,
            advancedJson = """{"perf":true,"n_batch":1024}"""
        )

        val restored = restoreGenerationParams(
            semanticJson = requested.toAssistantGenerationJson(),
            runtimeJson = runtimeParameterDocument(requested).toString()
        )

        assertEquals(8192, restored.nCtx)
        assertEquals(6, restored.nThreads)
        assertEquals(1024, restored.nPredict)
        assertEquals(0.42f, restored.temperature)
        assertTrue(JSONObject(restored.advancedJson).getBoolean("perf"))
        assertEquals(1024, JSONObject(restored.advancedJson).getInt("n_batch"))
    }

    @Test
    fun contextOnlyEditDoesNotMarkPerfAsChanged() {
        val before = GenerationParams(
            nCtx = 4096,
            advancedJson = """{"perf":true,"n_batch":1024}"""
        )
        val after = before.copy(nCtx = 8192)

        val changed = runtimeParameterChanges(before, after)

        assertEquals(setOf("n_ctx"), changed)
        assertFalse("perf" in changed)
    }

    @Test
    fun contextAndThreadsAreBothRuntimeChanges() {
        val before = GenerationParams(nCtx = 4096, nThreads = 4)
        val after = before.copy(nCtx = 16_384, nThreads = 8)

        assertEquals(
            setOf("n_ctx", "n_threads"),
            runtimeParameterChanges(before, after)
        )
    }
}
