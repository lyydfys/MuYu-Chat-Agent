package com.muyuchat.mca.video.workflow.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ComfyApiJsonTest {

    @Test
    fun `parses numbers without floating point loss`() {
        val root = ComfyApiJson.parse("""{"cfg":6.0005,"width":480.0,"seed":9223372036854775807}""")
            as ComfyJsonValue.ObjectValue
        val cfg = root["cfg"] as ComfyJsonValue.NumberValue
        assertEquals(60005L, cfg.unscaled)
        assertEquals(4, cfg.scale)
        val width = root["width"] as ComfyJsonValue.NumberValue
        assertEquals(480L, width.unscaled)
        assertEquals(0, width.scale)
        val seed = root["seed"] as ComfyJsonValue.NumberValue
        assertEquals(Long.MAX_VALUE, seed.unscaled)
    }

    @Test
    fun `rejects duplicate object keys`() {
        expect(ComfyJsonErrorReason.DUPLICATE_KEY) {
            ComfyApiJson.parse("""{"a":1,"a":2}""")
        }
    }

    @Test
    fun `rejects nesting beyond depth limit`() {
        val json = "[".repeat(ComfyApiJson.MAX_DEPTH + 1) + "]".repeat(ComfyApiJson.MAX_DEPTH + 1)
        expect(ComfyJsonErrorReason.DEPTH_LIMIT) { ComfyApiJson.parse(json) }
    }

    @Test
    fun `rejects trailing content`() {
        expect(ComfyJsonErrorReason.TRAILING_CONTENT) { ComfyApiJson.parse("{}[]") }
    }

    @Test
    fun `rejects unterminated objects`() {
        expect(ComfyJsonErrorReason.SYNTAX) { ComfyApiJson.parse("""{"a":1""") }
    }

    @Test
    fun `rejects numeric overflow`() {
        expect(ComfyJsonErrorReason.NUMBER_OUT_OF_RANGE) {
            ComfyApiJson.parse("9223372036854775808")
        }
        expect(ComfyJsonErrorReason.NUMBER_OUT_OF_RANGE) {
            ComfyApiJson.parse("1e999")
        }
    }

    @Test
    fun `rejects leading zeros and non-finite literals`() {
        expect(ComfyJsonErrorReason.SYNTAX) { ComfyApiJson.parse("01") }
        expect(ComfyJsonErrorReason.SYNTAX) { ComfyApiJson.parse("NaN") }
        expect(ComfyJsonErrorReason.SYNTAX) { ComfyApiJson.parse("Infinity") }
    }

    @Test
    fun `rejects strings over 64 KiB`() {
        val json = "\"" + "a".repeat(ComfyApiJson.MAX_STRING_BYTES + 1) + "\""
        expect(ComfyJsonErrorReason.STRING_LIMIT) { ComfyApiJson.parse(json) }
    }

    @Test
    fun `rejects input over 4 MiB`() {
        val json = "\"" + "a".repeat(ComfyApiJson.MAX_INPUT_BYTES + 1) + "\""
        expect(ComfyJsonErrorReason.INPUT_TOO_LARGE) { ComfyApiJson.parse(json) }
    }

    @Test
    fun `decodes escapes and keeps unicode`() {
        val value = ComfyApiJson.parse("\"a\\n\\u4e2d\\\\b\"") as ComfyJsonValue.StringValue
        assertEquals("a\n中\\b", value.value)
    }

    @Test
    fun `rejects unknown escapes`() {
        expect(ComfyJsonErrorReason.SYNTAX) { ComfyApiJson.parse("\"\\q\"") }
    }

    private fun expect(reason: ComfyJsonErrorReason, block: () -> Unit) {
        try {
            block()
            fail("expected ComfyJsonException($reason)")
        } catch (error: ComfyJsonException) {
            assertEquals(reason, error.reason)
            assertTrue("path must be anchored", error.path.isNotBlank())
        }
    }
}
