package com.muyuchat.api.dsh.gateway

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshGatewayJsonTest {

    @Test
    fun canonicalSortsObjectKeysAtEveryDepth() {
        val schema = JSONObject(
            """{"type":"object","required":["b","a"],"properties":{"z":{"type":"string"},"a":{"type":"integer"}}}"""
        )
        assertEquals(
            """{"properties":{"a":{"type":"integer"},"z":{"type":"string"}},"required":["b","a"],"type":"object"}""",
            DshGatewayJson.canonical(schema)
        )
    }

    @Test
    fun canonicalEscapesStringsAndHandlesNull() {
        val value = JSONObject()
            .put("text", "line1\nline2\t\"quoted\"\\")
            .put("nothing", JSONObject.NULL)
        assertEquals("""{"nothing":null,"text":"line1\nline2\t\"quoted\"\\"}""", DshGatewayJson.canonical(value))
    }

    @Test
    fun canonicalHandlesArraysAndNumbers() {
        val value = JSONArray().put(1).put(1.5).put(true).put("x")
        assertEquals("""[1,1.5,true,"x"]""", DshGatewayJson.canonical(value))
    }

    @Test
    fun singleJsonValueRejectsTrailingGarbageAndBlank() {
        assertTrue(DshGatewayJson.isSingleJsonValue("""{"a":1}"""))
        assertTrue(DshGatewayJson.isSingleJsonValue("""[1,2]"""))
        assertFalse(DshGatewayJson.isSingleJsonValue("""{"a":1} trailing"""))
        assertFalse(DshGatewayJson.isSingleJsonValue(""))
        assertFalse(DshGatewayJson.isSingleJsonValue("{"))
    }

    @Test
    fun strictJsonRejectsOrgJsonLeniency() {
        // org.json's tokener accepts unquoted strings; the protocol boundary must not.
        assertFalse(DshGatewayJson.isSingleJsonValue("not json"))
        assertFalse(DshGatewayJson.isSingleJsonValue("""{"a":not json}"""))
        assertFalse(DshGatewayJson.isStrictJson("""{"a":01}"""))
        assertFalse(DshGatewayJson.isStrictJson("""{"a":1,}"""))
        assertFalse(DshGatewayJson.isStrictJson("""{"a":"unterminated}"""))
        assertTrue(DshGatewayJson.isStrictJsonObject("""{"a":[1,true,null],"b":{"c":-1.5e2}}"""))
        assertFalse(DshGatewayJson.isStrictJsonObject("[1]"))
    }

    @Test
    fun parseObjectRequiresAnObjectRoot() {
        assertEquals("x", DshGatewayJson.parseObject("""{"a":"x"}""")?.optString("a"))
        assertNull(DshGatewayJson.parseObject("[1,2]"))
        assertNull(DshGatewayJson.parseObject("not json"))
    }
}
