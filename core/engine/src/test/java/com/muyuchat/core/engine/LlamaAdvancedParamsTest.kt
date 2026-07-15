package com.muyuchat.core.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaAdvancedParamsTest {
    @Test
    fun recognizedFieldsAreFlattenedWhileUnknownJsonIsPreserved() {
        val params = GenerationParams(
            advancedJson = """{
                "n_threads_batch":6,
                "n_batch":2048,
                "n_ubatch":256,
                "n_gpu_layers":0,
                "main_gpu":0,
                "split_mode":"none",
                "n_cpu_moe":0,
                "cache_type_k":"q4_0",
                "cache_type_v":"q4_0",
                "flash_attn":"on",
                "cache_reuse":256,
                "spec_type":"draft-mtp",
                "spec_draft_n_max":2,
                "n_parallel":1,
                "perf":true,
                "use_jinja":true,
                "mmap":true,
                "mlock":false,
                "future_native":{"mode":"kept"}
            }""".trimIndent()
        )

        val root = JSONObject(params.toJson())
        val advanced = root.getJSONObject("advanced_json")

        assertEquals(6, root.getInt("n_threads_batch"))
        assertEquals(2048, root.getInt("n_batch"))
        assertEquals(256, root.getInt("n_ubatch"))
        assertEquals(0, root.getInt("n_gpu_layers"))
        assertEquals("none", root.getString("split_mode"))
        assertEquals("q4_0", root.getString("cache_type_v"))
        assertEquals("on", root.getString("flash_attn"))
        assertEquals("draft-mtp", root.getString("spec_type"))
        assertEquals(2, root.getInt("spec_draft_n_max"))
        assertEquals(1, root.getInt("n_parallel"))
        assertTrue(root.getBoolean("perf"))
        assertTrue(root.getBoolean("use_jinja"))
        assertEquals("kept", advanced.getJSONObject("future_native").getString("mode"))
        assertEquals(LlamaAdvancedParams.CURRENT_SCHEMA_VERSION, advanced.getInt("schema_version"))
        assertTrue(params.advancedValidationErrors().isEmpty())
    }

    @Test
    fun invalidAdvancedJsonIsPreservedAndReportedInsteadOfBecomingEmptyObject() {
        val params = GenerationParams(advancedJson = "{broken-json")

        val root = JSONObject(params.toJson())

        assertEquals("{broken-json", root.getString("advanced_json"))
        assertTrue(params.advancedValidationErrors().isNotEmpty())
    }

    @Test
    fun rootCanonicalFieldsAreCollectedBackIntoAdvancedJson() {
        val generation = GenerationParams.fromJson(
            JSONObject()
                .put("n_batch", 2048)
                .put("cache_type_k", "q8_0")
                .put("advanced_json", JSONObject().put("future_native", 7))
        )
        val load = LoadParams.fromJson(
            JSONObject()
                .put("n_ctx", 4096)
                .put("n_threads", 4)
                .put("n_ubatch", 256)
                .put("advanced_json", JSONObject().put("future_load", true))
                .toString()
        )

        val generationAdvanced = JSONObject(generation.advancedJson)
        val loadAdvanced = JSONObject(load.advancedJson)
        assertEquals(2048, generationAdvanced.getInt("n_batch"))
        assertEquals("q8_0", generationAdvanced.getString("cache_type_k"))
        assertEquals(7, generationAdvanced.getInt("future_native"))
        assertEquals(256, loadAdvanced.getInt("n_ubatch"))
        assertTrue(loadAdvanced.getBoolean("future_load"))
    }

    @Test
    fun validAdvancedMmapAndMlockOverrideLoadDefaultsAtNativeRoot() {
        val root = JSONObject(
            LoadParams(
                mmap = true,
                mlock = false,
                advancedJson = """{"mmap":false,"mlock":true,"n_batch":512}"""
            ).toJson()
        )

        assertFalse(root.getBoolean("mmap"))
        assertTrue(root.getBoolean("mlock"))
        assertEquals(512, root.getInt("n_batch"))
        assertFalse(root.getJSONObject("advanced_json").getBoolean("mmap"))
        assertTrue(root.getJSONObject("advanced_json").getBoolean("mlock"))
    }

    @Test
    fun unsupportedParallelAndDraftSizesAreReportedAndDoNotReachNative() {
        val params = GenerationParams(
            advancedJson = """{"n_parallel":2,"spec_draft_n_max":9,"future":true}"""
        )

        val root = JSONObject(params.toJson())

        assertFalse(root.has("n_parallel"))
        assertFalse(root.has("spec_draft_n_max"))
        assertFalse(root.getJSONObject("advanced_json").has("n_parallel"))
        assertFalse(root.getJSONObject("advanced_json").has("spec_draft_n_max"))
        assertTrue(root.getJSONObject("advanced_json").getBoolean("future"))
        assertTrue(params.advancedValidationErrors().size >= 2)
    }

    @Test
    fun copyNullClearsCanonicalValueFromAdvancedAndFlattenedNativeJson() {
        val parsed = LlamaAdvancedParams.parse("""{"n_batch":2048,"future":true}""").params!!
        val cleared = parsed.copy(nBatch = null)
        val advanced = cleared.toJsonObject()
        val native = JSONObject().also(cleared::putCanonicalFields)

        assertEquals(LlamaAdvancedParams.CURRENT_SCHEMA_VERSION, advanced.getInt("schema_version"))
        assertFalse(advanced.has("n_batch"))
        assertTrue(advanced.getBoolean("future"))
        assertFalse(native.has("n_batch"))
    }
}
