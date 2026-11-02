package com.muyuchat.core.agent.runtime.validation

import com.muyuchat.core.agent.runtime.AgentArgumentValidation
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.runtime.AgentSchemaValidation
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * K02 schema-subset and argument-validation tests (P06/P13 at the memory level).
 */
class AgentToolSchemaValidationTest {

    private val validator = SupportedToolSchemaValidator()
    private val codec = StrictAgentJsonCodec()
    private val descriptors = AgentV1ToolSpecs.descriptors(validator).associateBy { it.toolKey }

    @Test
    fun allFourBuiltInSchemasValidateWithCanonicalHash() {
        assertEquals(4, descriptors.size)
        for (toolKey in AgentV1ToolSpecs.TOOL_KEYS) {
            val descriptor = descriptors.getValue(toolKey)
            val validation = validator.validateToolSchema(descriptor.schemaJson)
            assertTrue("$toolKey must be supported", validation is AgentSchemaValidation.Supported)
            validation as AgentSchemaValidation.Supported
            assertTrue(validation.schemaHash.matches(Regex("^[0-9a-f]{64}$")))
            assertEquals(descriptor.schemaHash, validation.schemaHash)
            val canonical = codec.parse(validation.canonicalSchemaJson)
            assertTrue(canonical is AgentJsonParseResult.Parsed)
            assertTrue(
                "canonical schema must be sorted and compact",
                validation.canonicalSchemaJson.startsWith("{\"additionalProperties\":false,")
            )
            val again = validator.validateToolSchema(descriptor.schemaJson) as AgentSchemaValidation.Supported
            assertEquals(validation.schemaHash, again.schemaHash)
            assertTrue(validation.canonicalSchemaJson.contains("\"additionalProperties\":false"))
        }
    }

    @Test
    fun unsupportedSchemaFormsAreTypedAndNeverSilentlyIgnored() {
        val cases = listOf(
            """{"type":"object","additionalProperties":false,"properties":{"p":{"type":"string","pattern":"^a+"}}}""",
            """{"type":"object","additionalProperties":false,"properties":{"p":{"${'$'}ref":"#/definitions/x"}}}""",
            """{"type":"object","additionalProperties":false,"properties":{"p":{"anyOf":[{"type":"string"}]}}}""",
            """{"type":"object","additionalProperties":{},"properties":{}}""",
            """{"type":"object","properties":{}}""",
            """{"type":"object","additionalProperties":true,"properties":{}}""",
            """{"type":"string","additionalProperties":false}""",
            """{"type":"object","additionalProperties":false,"properties":{"p":{"type":"number","exclusiveMinimum":0}}}""",
            """{"type":"object","additionalProperties":false,"properties":{"p":{"type":"string","x-custom":1}}}""",
            """{"type":"object","additionalProperties":false,"required":["missing"],"properties":{}}""",
            """{"type":"object","additionalProperties":false,"properties":{"p":{"type":"string","enum":[1,2]}}}""",
            """{"type":"object","additionalProperties":false,"properties":{"p":{"type":["object","null"]}}}""",
            """{"type":"object","additionalProperties":false,"properties":{"p":{"type":"array","prefixItems":[{"type":"string"}]}}}"""
        )
        for (schema in cases) {
            when (val result = validator.validateToolSchema(schema)) {
                is AgentSchemaValidation.Supported -> fail("schema must be unsupported: $schema")
                is AgentSchemaValidation.Unsupported -> {
                    assertEquals(AgentErrorCodes.POLICY_SCHEMA_UNSUPPORTED, result.errorCode)
                    assertTrue(result.safeMessage.isNotBlank())
                }
            }
        }
    }

    @Test
    fun readTextAppliesArchitectureDefaultsAndCanonicalHash() {
        val result = valid(
            "workspace.read_text",
            """{"path":"inputs/brief.txt"}"""
        )
        val normalized = parseObject(result.normalizedArgumentsJson)
        assertEquals("inputs/brief.txt", string(normalized, "path"))
        assertEquals(0, int(normalized, "offset"))
        assertEquals(4096, int(normalized, "limit"))
        assertEquals(64, result.argumentsHash.length)
        assertTrue(result.normalizedArgumentsJson.matches(Regex("^\\{.*}$")))
    }

    @Test
    fun listAndSearchApplyDefaultsAndAllowExplicitNullCursor() {
        val list = valid("workspace.list", "{}")
        val listed = parseObject(list.normalizedArgumentsJson)
        assertEquals("inputs/", string(listed, "path"))
        assertEquals(100, int(listed, "limit"))
        assertTrue(listed["cursor"] is AgentJsonValue.Null)

        val listExplicit = valid("workspace.list", """{"path":"artifacts/","limit":25,"cursor":null}""")
        val explicit = parseObject(listExplicit.normalizedArgumentsJson)
        assertEquals("artifacts/", string(explicit, "path"))
        assertEquals(25, int(explicit, "limit"))

        val search = valid("workspace.search_text", """{"query":"report"}""")
        val searched = parseObject(search.normalizedArgumentsJson)
        assertEquals("inputs/", string(searched, "path"))
        assertEquals(20, int(searched, "maxMatches"))
    }

    @Test
    fun writeDefaultsNothingAndRequiresExpectedOldHash() {
        val created = valid(
            "artifact.write_text",
            """{"path":"artifacts/report.md","content":"# 报告","expectedOldHash":null}"""
        )
        val fields = parseObject(created.normalizedArgumentsJson)
        assertEquals("# 报告", string(fields, "content"))
        assertTrue(fields["expectedOldHash"] is AgentJsonValue.Null)

        val updated = valid(
            "artifact.write_text",
            """{"path":"artifacts/report.md","content":"x","expectedOldHash":"${"a".repeat(64)}"}"""
        )
        assertTrue(parseObject(updated.normalizedArgumentsJson)["expectedOldHash"] is AgentJsonValue.Str)

        invalid("artifact.write_text", """{"path":"artifacts/report.md","content":"x"}""")
        invalid(
            "artifact.write_text",
            """{"path":"artifacts/report.md","content":"x","expectedOldHash":"ABC"}"""
        )
        invalid(
            "artifact.write_text",
            """{"path":"artifacts/report.md","content":"x","expectedOldHash":"${"A".repeat(64)}"}"""
        )
    }

    @Test
    fun missingExtraAndWrongTypedArgumentsAreRejected() {
        invalid("workspace.read_text", "{}")
        invalid("workspace.read_text", """{"path":"inputs/brief.txt","extra":1}""")
        invalid("workspace.read_text", """{"path":"inputs/brief.txt","limit":"4096"}""")
        invalid("workspace.read_text", """{"path":"inputs/brief.txt","limit":1.0}""")
        invalid("workspace.read_text", """{"path":"inputs/brief.txt","offset":-1}""")
        invalid("workspace.read_text", """{"path":"inputs/brief.txt","limit":0}""")
        invalid("workspace.read_text", """{"path":"inputs/brief.txt","limit":16385}""")
        invalid("workspace.list", """{"limit":201}""")
        invalid("workspace.list", """{"limit":0}""")
        invalid("workspace.list", """{"path":null}""")
        invalid("workspace.search_text", """{"query":""}""")
        invalid("workspace.search_text", """{"query":"${"x".repeat(257)}"}""")
        invalid("workspace.search_text", """{"query":"x","maxMatches":101}""")
        invalid("workspace.read_text", """{"path":"/etc/passwd"}""")
        invalid("workspace.read_text", """{"path":"inputs/../secret"}""")
        invalid("workspace.read_text", """{"path":"inputs\\brief.txt"}""")
        invalid("workspace.read_text", """{"path":"other/brief.txt"}""")
        invalid("artifact.write_text", """{"path":"inputs/report.md","content":"x","expectedOldHash":null}""")
        invalid("artifact.write_text", """{"path":"artifacts/","content":"x","expectedOldHash":null}""")
        invalid("workspace.read_text", """{"path":"${"inputs/" + "a".repeat(600)}"}""")
        invalid("workspace.read_text", """{"path":"inputs/${(1..17).joinToString("/") { "d$it" }}"}""")
    }

    @Test
    fun utf8ByteBudgetsAreEnforcedSeparatelyFromCharacterLengths() {
        val hugeContent = "中".repeat(11_000) // 33,000 UTF-8 bytes but only 11,000 characters
        invalid(
            "artifact.write_text",
            """{"path":"artifacts/big.md","content":"$hugeContent","expectedOldHash":null}"""
        )
        val okContent = "中".repeat(10_000) // 30,000 bytes, within 32 KiB
        valid(
            "artifact.write_text",
            """{"path":"artifacts/ok.md","content":"$okContent","expectedOldHash":null}"""
        )
    }

    @Test
    fun chineseLogicalPathsAndEnumAndArraySubsetWork() {
        val validatorResult = valid("workspace.read_text", """{"path":"inputs/报告.txt"}""")
        assertEquals("inputs/报告.txt", string(parseObject(validatorResult.normalizedArgumentsJson), "path"))

        val enumSchema = """
            {"type":"object","additionalProperties":false,
             "properties":{"mode":{"type":"string","enum":["fast","safe"]},
                           "tags":{"type":"array","items":{"type":"string","minLength":1},"minItems":1,"maxItems":2},
                           "nullableCount":{"type":["integer","null"],"minimum":0,"maximum":10}},
             "required":["mode"]}
        """.trimIndent()
        val supported = validator.validateToolSchema(enumSchema) as AgentSchemaValidation.Supported
        val descriptor = AgentToolDescriptor(
            toolKey = "custom.echo",
            version = "1",
            displayName = "Echo",
            description = "fixture",
            schemaJson = enumSchema,
            schemaHash = supported.schemaHash,
            sideEffect = com.muyuchat.core.agent.contract.AgentToolSideEffect.READ_ONLY,
            dataClass = com.muyuchat.core.agent.contract.AgentDataClass.PRIVATE
        )
        validate(descriptor, """{"mode":"fast","tags":["a"],"nullableCount":5}""")
        invalid(descriptor, """{"mode":"slow"}""")
        invalid(descriptor, """{"mode":"fast","tags":[]}""")
        invalid(descriptor, """{"mode":"fast","tags":["a","b","c"]}""")
        invalid(descriptor, """{"mode":"fast","nullableCount":null,"extra":1}""")
        validate(descriptor, """{"mode":"safe","nullableCount":null}""")
        invalid(descriptor, """{"mode":"safe","nullableCount":1.0}""")
        invalid(descriptor, """{"mode":"safe","nullableCount":11}""")
    }

    @Test
    fun descriptorHashMismatchIsAUnsupportedPolicyError() {
        val base = descriptors.getValue("workspace.list")
        val mismatched = base.copy(schemaHash = "deadbeef")
        val result = validator.validateArguments(mismatched, "{}")
        assertTrue(result is AgentArgumentValidation.Invalid)
        assertEquals(
            AgentErrorCodes.POLICY_SCHEMA_UNSUPPORTED,
            (result as AgentArgumentValidation.Invalid).errorCode
        )
    }

    @Test
    fun malformedArgumentsJsonIsTOOLInputInvalid() {
        val descriptor = descriptors.getValue("workspace.list")
        for (arguments in listOf("", "[]", "not json", """{"path":}""", "{} {}")) {
            val result = validator.validateArguments(descriptor, arguments)
            assertTrue("$arguments must be rejected", result is AgentArgumentValidation.Invalid)
            assertEquals(
                AgentErrorCodes.TOOL_INPUT_INVALID,
                (result as AgentArgumentValidation.Invalid).errorCode
            )
        }
    }

    @Test
    fun explicitNullIsNeverReplacedByADefault() {
        val result = validator.validateArguments(
            descriptors.getValue("workspace.list"),
            """{"path":null}"""
        )
        assertTrue(result is AgentArgumentValidation.Invalid)
        assertNotEquals(
            "null should not be defaulted",
            AgentErrorCodes.POLICY_SCHEMA_UNSUPPORTED,
            (result as AgentArgumentValidation.Invalid).errorCode
        )
    }

    private fun valid(toolKey: String, argumentsJson: String): AgentArgumentValidation.Valid {
        val result = validator.validateArguments(descriptors.getValue(toolKey), argumentsJson)
        if (result is AgentArgumentValidation.Invalid) {
            fail("$toolKey arguments must be valid but got ${result.errorCode}: ${result.safeMessage}")
        }
        return result as AgentArgumentValidation.Valid
    }

    private fun invalid(toolKey: String, argumentsJson: String) {
        invalid(descriptors.getValue(toolKey), argumentsJson)
    }

    private fun invalid(descriptor: AgentToolDescriptor, argumentsJson: String) {
        val result = validator.validateArguments(descriptor, argumentsJson)
        assertTrue(
            "${descriptor.toolKey} arguments must be rejected: $argumentsJson",
            result is AgentArgumentValidation.Invalid
        )
        assertEquals(
            AgentErrorCodes.TOOL_INPUT_INVALID,
            (result as AgentArgumentValidation.Invalid).errorCode
        )
        assertTrue(result.safeMessage.isNotBlank())
    }

    private fun validate(descriptor: AgentToolDescriptor, argumentsJson: String) {
        val result = validator.validateArguments(descriptor, argumentsJson)
        assertTrue(
            "arguments must be valid: $argumentsJson -> $result",
            result is AgentArgumentValidation.Valid
        )
    }

    private fun parseObject(json: String): Map<String, AgentJsonValue> {
        val parsed = codec.parse(json)
        assertTrue(parsed is AgentJsonParseResult.Parsed)
        return ((parsed as AgentJsonParseResult.Parsed).value as AgentJsonValue.Obj).fields.toMap()
    }

    private fun string(fields: Map<String, AgentJsonValue>, key: String): String =
        (fields.getValue(key) as AgentJsonValue.Str).value

    private fun int(fields: Map<String, AgentJsonValue>, key: String): Int =
        (fields.getValue(key) as AgentJsonValue.Num).literal.toInt()
}
