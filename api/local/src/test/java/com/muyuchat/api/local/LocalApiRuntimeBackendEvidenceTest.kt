package com.muyuchat.api.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalApiRuntimeBackendEvidenceTest {
    @Test
    fun componentDefaultsRemainDistinctFromSelectedLlmAndActualExecution() {
        val metrics = metrics("""{
            "backend":"litert_lm","requestedBackend":"npu","selectedBackend":"npu","actualBackend":"unknown",
            "componentBackends":{
                "llm":{"requested":"npu","selected":"npu","selectionSource":"engine_config_submission","actual":"unknown"},
                "vision":{"requested":null,"selected":null,"selectionSource":"sdk_model_default","actual":"unknown"},
                "audio":{"requested":null,"selected":null,"selectionSource":"sdk_model_default","actual":"unknown"}
            }
        }""")
        assertEquals("npu", metrics.getString("requestedBackend"))
        assertEquals("npu", metrics.getString("selectedBackend"))
        assertEquals("unknown", metrics.getString("actualBackend"))
        val components = metrics.getJSONObject("componentBackends")
        assertEquals("npu", components.getJSONObject("llm").getString("selected"))
        for (component in listOf("vision", "audio")) {
            val evidence = components.getJSONObject(component)
            assertTrue(evidence.has("selected"))
            assertTrue(evidence.isNull("selected"))
            assertTrue(evidence.isNull("requested"))
            assertEquals("sdk_model_default", evidence.getString("selectionSource"))
            assertEquals("unknown", evidence.getString("actual"))
        }
    }

    @Test
    fun projectionOmitsMissingMalformedAndPrivateComponentFields() {
        assertFalse(metrics("{}").has("componentBackends"))
        val malformed = metrics("""{"requestedBackend":7,"selectedBackend":false,"actualBackend":null}""")
        for (field in listOf("requestedBackend", "selectedBackend", "actualBackend")) {
            assertFalse(malformed.has(field))
        }
        val metrics = metrics("""{
            "componentBackends":{
                "llm":{"selected":"NPU","actual":"unknown","modelPath":"/private/model","prompt":"secret"},
                "vision":{"selected":"/private/backend","requested":7,"actual":false},
                "audio":{"selected":"GPU","selectionSource":"/private/config"},
                "private":{"selected":"secret"}
            }
        }""")
        val components = metrics.getJSONObject("componentBackends")
        assertEquals(2, components.length())
        assertEquals(2, components.getJSONObject("llm").length())
        assertEquals("unknown", components.getJSONObject("llm").getString("actual"))
        assertEquals(1, components.getJSONObject("audio").length())
        assertFalse(components.has("vision"))
        assertFalse(components.has("private"))
        assertFalse(components.getJSONObject("audio").has("actual"))
    }

    @Test
    fun backendSelectionsCannotExposePathsOrUnboundedText() {
        for (value in listOf("/private", "\\private", "\\\\server\\private\\backend", "C:\\private\\backend", "n".repeat(129))) {
            val source = JSONObject()
            for (field in listOf("requestedBackend", "selectedBackend", "actualBackend")) {
                source.put(field, value)
            }
            val metrics = metrics(source.toString())
            for (field in listOf("requestedBackend", "selectedBackend", "actualBackend")) {
                assertFalse("$field must not expose $value", metrics.has(field))
            }
        }
    }

    private fun metrics(raw: String): JSONObject {
        val previousProvider = LocalApiRuntime.nativeStatsJsonProvider
        return try {
            LocalApiRuntime.nativeStatsJsonProvider = { raw }
            JSONObject(LocalApiRuntime.metricsJson())
        } finally {
            LocalApiRuntime.nativeStatsJsonProvider = previousProvider
        }
    }
}
