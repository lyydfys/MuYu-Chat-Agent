package com.muyuchat.core.telemetry

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeMetricsJsonTest {
    @Test
    fun roundTripsRuntimeMetricsWithDetailedMemoryFields() {
        val metrics = RuntimeMetrics(
            time = 100,
            model = "/models/test.gguf",
            backend = "cpu",
            soc = "snapdragon",
            promptTokens = 12,
            genTokens = 34,
            loadMs = 56,
            ttftMs = 78,
            prefillMs = 90,
            decodeMs = 1234,
            decodeTps = 12.5,
            e2eTps = 10.0,
            nativePssKb = 111,
            processRssKb = 222,
            nativeHeapKb = 333,
            nativeHeapSizeKb = 444,
            javaHeapKb = 555,
            availMemKb = 666,
            params = """{"n_ctx":4096}""",
            error = "boom"
        )

        val parsed = RuntimeMetricsJson.fromJson(RuntimeMetricsJson.toJson(metrics))

        assertEquals(metrics, parsed)
    }

    @Test
    fun readsOldJsonlRecordsWithoutDetailedMemoryFields() {
        val parsed = RuntimeMetricsJson.fromJson(
            JSONObject(
                """
                {
                  "model": "old.gguf",
                  "decodeTps": 8.5,
                  "nativePssKb": 123,
                  "availMemKb": 456,
                  "params": {"n_threads": 4}
                }
                """.trimIndent()
            )
        )

        assertEquals("old.gguf", parsed.model)
        assertEquals(8.5, parsed.decodeTps, 0.001)
        assertEquals(123, parsed.nativePssKb)
        assertEquals(0, parsed.processRssKb)
        assertEquals("""{"n_threads":4}""", parsed.params)
        assertNull(parsed.error)
    }
}
