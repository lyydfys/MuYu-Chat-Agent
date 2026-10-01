package com.muyuchat.api.local

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the aliased boolean lookup now that the runtime state endpoint and the loopback server
 * share it.
 *
 * The two previous copies were byte-identical, so this does not cover behaviour that was
 * untested before. It matters because both callers read the same JSON fields, so a divergence
 * would make two endpoints of one API disagree about the same payload -- and because the
 * difference between null and false is load-bearing here: a caller uses null to mean "not
 * stated" and falls back or probes deeper, while false is a definite answer.
 */
class JsonBooleanFieldTest {

    @Test
    fun `the first present key wins`() {
        val json = JSONObject("""{"visionReady":true,"supportsVision":false}""")
        assertEquals(true, json.firstBoolean("visionReady", "supportsVision"))
        assertEquals(false, json.firstBoolean("supportsVision", "visionReady"))
    }

    @Test
    fun `absent keys fall through to later aliases`() {
        val json = JSONObject("""{"supports_vision":true}""")
        assertEquals(true, json.firstBoolean("visionReady", "supports_vision"))
    }

    @Test
    fun `an absent field is null, not false`() {
        // This is the distinction callers depend on: visionReadyFlag treats null as "keep
        // looking" and recurses into nested objects.
        assertNull(JSONObject("{}").firstBoolean("visionReady", "supportsVision"))
        assertNull(JSONObject("""{"other":1}""").firstBoolean("visionReady"))
    }

    @Test
    fun `a json null counts as absent and falls through`() {
        val json = JSONObject("""{"visionReady":null,"supports_vision":true}""")
        assertEquals(true, json.firstBoolean("visionReady", "supports_vision"))
    }

    @Test
    fun `a present non-boolean does not fall through to the next alias`() {
        // optBoolean is applied to whichever key is present first, so a present-but-unusable
        // value answers false rather than continuing to the next alias. Both originals behaved
        // this way and the shared copy is unchanged; recorded because "it read the wrong alias"
        // would otherwise look like a bug in the aliasing rather than the intended rule.
        val json = JSONObject("""{"visionReady":"yes","supports_vision":true}""")
        assertEquals(false, json.firstBoolean("visionReady", "supports_vision"))
    }

    @Test
    fun `boolean-like strings are read as booleans`() {
        assertEquals(true, JSONObject("""{"v":"true"}""").firstBoolean("v"))
        assertEquals(false, JSONObject("""{"v":"false"}""").firstBoolean("v"))
    }
}
