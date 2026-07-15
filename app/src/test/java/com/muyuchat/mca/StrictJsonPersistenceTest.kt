package com.muyuchat.mca

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictJsonPersistenceTest {
    @Test
    fun richDiagnosticPayloadReplacesNonFiniteNumbersWithNull() {
        val document = linkedMapOf<String, Any?>(
            "status" to "completed",
            "result" to linkedMapOf(
                "debug" to listOf(
                    linkedMapOf(
                        "scale" to Double.NaN,
                        "positive" to Double.POSITIVE_INFINITY,
                        "negative" to Float.NEGATIVE_INFINITY,
                        "finite" to 0.000202063
                    )
                )
            )
        )

        val encoded = strictJsonForPersistence(document, indentSpaces = 2)
        val parsed = JSONObject(encoded)
        val debug = parsed
            .getJSONObject("result")
            .getJSONArray("debug")
            .getJSONObject(0)

        assertTrue(debug.isNull("scale"))
        assertTrue(debug.isNull("positive"))
        assertTrue(debug.isNull("negative"))
        assertEquals(0.000202063, debug.getDouble("finite"), 0.0)
        assertFalse(encoded.contains(":NaN"))
        assertFalse(encoded.contains(":Infinity"))
        assertFalse(encoded.contains(":-Infinity"))
    }

    @Test
    fun jsonObjectsAndArraysKeepTheirShapeWhenPersisted() {
        val source = JSONObject()
            .put("message", "QNN graph \"model\" completed")
            .put("values", org.json.JSONArray().put(1).put(JSONObject.NULL).put(true))

        val encoded = strictJsonForPersistence(source)
        val parsed = JSONObject(encoded)

        assertEquals("QNN graph \"model\" completed", parsed.getString("message"))
        assertEquals(3, parsed.getJSONArray("values").length())
        assertTrue(parsed.getJSONArray("values").isNull(1))
    }
}
