package com.muyuchat.mca.agent.security

import com.muyuchat.core.agent.contract.AgentEffectKind
import com.muyuchat.core.agent.contract.AgentResourceAccess
import com.muyuchat.core.agent.contract.AgentResourceScope
import com.muyuchat.core.agent.contract.AgentScopePrecondition
import com.muyuchat.core.agent.contract.AgentScopePreconditionCodec
import com.muyuchat.core.agent.contract.AgentToolSideEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic JVM tests for the frozen-command contract (K07, architecture 8.2 / 9.1).
 *
 * The golden vector pins the exact preimage bytes. It is intentionally independent of how tests
 * build arguments: any change to the domain, field order, length prefixes, canonical JSON or
 * default injection must break this test.
 */
class AgentFrozenCommandFactoryTest {
    @Test
    fun goldenVectorReadTextPinsExactPreimageAndHash() {
        val descriptor = AgentSecurityFixtures.descriptor()
        val frozen = AgentSecurityFixtures.freeze(
            descriptor = descriptor,
            rawArgumentsJson = """{"path": "inputs/brief.txt", "offset": 0, "limit": 4096}"""
        )

        assertEquals(
            """{"limit":4096,"offset":0,"path":"inputs/brief.txt"}""",
            frozen.command.normalizedArgumentsJson
        )
        assertEquals(
            """{"expectedOldHash":null,"logicalPaths":["inputs/brief.txt"],"taskRoot":"ws-1/tasks/task-1"}""",
            frozen.command.resourcePreconditionsJson
        )
        assertEquals("6c9423a3d889f1dcebd19b64a5e5eda7911182a973c6192a2ce0009bd8b17aaa", frozen.command.argumentsHash)
        assertEquals("2b0f5ef5a251cde5ea6100b8738e50b964fcf0f4f9ab43be8be08b3777606e2c", frozen.command.resourceFingerprint)
        assertEquals(GOLDEN_PREIMAGE_HEX, frozen.hashPreimageHex)
        assertTrue(frozen.preimageCopy().contentEquals(hexToBytes(GOLDEN_PREIMAGE_HEX)))
        assertEquals(316, frozen.hashPreimage.size)
        assertEquals(1_300_000L, frozen.command.expiresAtMillis)
        assertEquals(listOf("inputs/brief.txt"), frozen.scope.logicalPaths)
        assertEquals(null, frozen.scope.expectedOldHash)
        assertTrue(frozen.command.networkDestinations.isEmpty())
    }

    @Test
    fun goldenVectorWriteTextEscapesContentAndPinsCreateOnlyPrecondition() {
        val descriptor = AgentSecurityFixtures.descriptor(
            toolKey = AgentBuiltInToolArgumentNormalizer.WRITE_TEXT,
            sideEffect = AgentToolSideEffect.WORKSPACE_WRITE
        )
        val frozen = AgentSecurityFixtures.freeze(
            descriptor = descriptor,
            rawArgumentsJson = """{"path":"artifacts/report.md","content":"line1\nline2","expectedOldHash":null}"""
        )

        assertEquals(
            """{"content":"line1\nline2","expectedOldHash":null,"path":"artifacts/report.md"}""",
            frozen.command.normalizedArgumentsJson
        )
        assertEquals("b6acaecef55183f6f8a7a01b04cd3373c671cc6b6230135f7c47bc8808a5291d", frozen.command.argumentsHash)
        assertEquals(null, frozen.scope.expectedOldHash)
        assertEquals(listOf("artifacts/report.md"), frozen.scope.logicalPaths)
    }

    @Test
    fun defaultsAreInjectedBeforeApprovalAndBecomePartOfTheHash() {
        val descriptor = AgentSecurityFixtures.descriptor(AgentBuiltInToolArgumentNormalizer.LIST)
        val frozen = AgentSecurityFixtures.freeze(descriptor, "{}")

        assertEquals("""{"cursor":null,"limit":100,"path":"inputs"}""", frozen.command.normalizedArgumentsJson)
        assertTrue(frozen.command.normalizedArgumentsJson.contains(""""limit":100"""))
        assertTrue(frozen.command.normalizedArgumentsJson.contains(""""cursor":null"""))

        val explicit = AgentSecurityFixtures.freeze(
            descriptor,
            """{"path":"inputs/docs","limit":7,"cursor":"abc"}""",
            invocationId = "inv-2"
        )
        assertNotEquals(frozen.command.argumentsHash, explicit.command.argumentsHash)
        assertTrue(explicit.command.normalizedArgumentsJson.contains(""""path":"inputs/docs"""))
        assertTrue(explicit.command.normalizedArgumentsJson.contains(""""cursor":"abc""""))
    }

    @Test
    fun freezeIsStableAndChangingRawArgumentsChangesTheHashOnly() {
        val descriptor = AgentSecurityFixtures.descriptor()
        val first = AgentSecurityFixtures.freeze(
            descriptor,
            """{"path":"inputs/brief.txt","offset":0,"limit":4096}"""
        )
        val originalJson = first.command.normalizedArgumentsJson
        val originalHash = first.command.argumentsHash

        val second = AgentSecurityFixtures.freeze(
            descriptor,
            """{"path":"inputs/brief.txt","offset":0,"limit":8192}""",
            invocationId = "inv-2"
        )
        assertEquals(originalJson, first.command.normalizedArgumentsJson)
        assertEquals(originalHash, first.command.argumentsHash)
        assertNotEquals(originalHash, second.command.argumentsHash)
        assertNotEquals(first.command.normalizedArgumentsJson, second.command.normalizedArgumentsJson)
    }

    @Test
    fun writePreconditionChangesResourceFingerprintAndArgumentsHash() {
        val descriptor = AgentSecurityFixtures.descriptor(
            toolKey = AgentBuiltInToolArgumentNormalizer.WRITE_TEXT,
            sideEffect = AgentToolSideEffect.WORKSPACE_WRITE
        )
        val createOnly = AgentSecurityFixtures.freeze(
            descriptor,
            """{"path":"artifacts/report.md","content":"hello","expectedOldHash":null}"""
        )
        val overwrite = AgentSecurityFixtures.freeze(
            descriptor,
            """{"path":"artifacts/report.md","content":"hello","expectedOldHash":"$OLD_HASH"}""",
            invocationId = "inv-2"
        )
        assertNotEquals(createOnly.command.resourceFingerprint, overwrite.command.resourceFingerprint)
        assertNotEquals(createOnly.command.argumentsHash, overwrite.command.argumentsHash)
        assertEquals(OLD_HASH, overwrite.scope.expectedOldHash)
        assertTrue(overwrite.command.resourcePreconditionsJson.contains(OLD_HASH))
    }

    @Test
    fun toolIdentityVersionSchemaAndPolicyVersionAllChangeTheHash() {
        val baseDescriptor = AgentSecurityFixtures.descriptor()
        val args = """{"path":"inputs/brief.txt"}"""
        val base = AgentSecurityFixtures.freeze(baseDescriptor, args)

        val versionBump = AgentSecurityFixtures.freeze(
            AgentSecurityFixtures.descriptor(version = "1.0.1"),
            args,
            invocationId = "inv-2"
        )
        val schemaBump = AgentSecurityFixtures.freeze(
            AgentSecurityFixtures.descriptor(schemaHash = AgentSecurityFixtures.OTHER_SCHEMA_HASH),
            args,
            invocationId = "inv-3"
        )
        val policyBump = AgentSecurityFixtures.freeze(
            baseDescriptor,
            args,
            invocationId = "inv-4",
            policyVersion = 8L
        )
        assertNotEquals(base.command.argumentsHash, versionBump.command.argumentsHash)
        assertNotEquals(base.command.argumentsHash, schemaBump.command.argumentsHash)
        assertNotEquals(base.command.argumentsHash, policyBump.command.argumentsHash)
        assertEquals(base.command.resourceFingerprint, policyBump.command.resourceFingerprint)
        assertEquals(base.command.resourceFingerprint, versionBump.command.resourceFingerprint)
    }

    @Test
    fun logicalPathsAreCanonicalizedBeforeHashing() {
        val descriptor = AgentSecurityFixtures.descriptor(AgentBuiltInToolArgumentNormalizer.LIST)
        val frozen = AgentSecurityFixtures.freeze(descriptor, """{"path":"inputs/docs/"}""")
        assertEquals("""{"cursor":null,"limit":100,"path":"inputs/docs"}""", frozen.command.normalizedArgumentsJson)
        assertEquals(listOf("inputs/docs"), frozen.scope.logicalPaths)
    }

    @Test
    fun unsafeLogicalPathsAreTypedRejections() {
        val descriptor = AgentSecurityFixtures.descriptor(AgentBuiltInToolArgumentNormalizer.LIST)
        val deep = "inputs/" + (1..16).joinToString("/") { "d$it" }
        val invalidPaths = listOf(
            "",
            " ",
            "/abs/path",
            "C:/windows",
            "inputs\\windows",
            "inputs/../secret",
            "inputs/./same",
            "inputs//double",
            "payloads/other",
            "inputs/\u0000evil",
            "inputs/" + "x".repeat(600),
            deep
        )
        invalidPaths.forEachIndexed { index, path ->
            val result = AgentSecurityFixtures.factory.freeze(
                AgentSecurityFixtures.request(descriptor, """{"path":"${jsonEscape(path)}"}""", invocationId = "inv-$index")
            )
            val error = AgentSecurityFixtures.rejection(result)
            assertEquals("path[$index] must be rejected as invalid input", "TOOL_INPUT_INVALID", error.code)
            assertEquals(com.muyuchat.core.agent.contract.AgentErrorCategory.TOOL, error.category)
        }
    }

    @Test
    fun writeTargetsMustBeFilesBelowArtifacts() {
        val descriptor = AgentSecurityFixtures.descriptor(
            toolKey = AgentBuiltInToolArgumentNormalizer.WRITE_TEXT,
            sideEffect = AgentToolSideEffect.WORKSPACE_WRITE
        )
        listOf("inputs/report.md", "artifacts", "artifacts/").forEachIndexed { index, path ->
            val result = AgentSecurityFixtures.factory.freeze(
                AgentSecurityFixtures.request(
                    descriptor,
                    """{"path":"$path","content":"x","expectedOldHash":null}""",
                    invocationId = "inv-$index"
                )
            )
            assertEquals("TOOL_INPUT_INVALID", AgentSecurityFixtures.rejection(result).code)
        }
        val valid = AgentSecurityFixtures.freeze(
            descriptor,
            """{"path":"artifacts/nested/report.md","content":"x","expectedOldHash":null}"""
        )
        assertEquals(listOf("artifacts/nested/report.md"), valid.scope.logicalPaths)
    }

    @Test
    fun unknownToolsAndExtraFieldsAreRejected() {
        val unknown = AgentSecurityFixtures.factory.freeze(
            AgentSecurityFixtures.request(
                AgentSecurityFixtures.descriptor(toolKey = "workspace.delete"),
                """{"path":"inputs/x"}"""
            )
        )
        assertEquals("TOOL_NOT_FOUND", AgentSecurityFixtures.rejection(unknown).code)

        val extra = AgentSecurityFixtures.factory.freeze(
            AgentSecurityFixtures.request(
                AgentSecurityFixtures.descriptor(),
                """{"path":"inputs/x","force":true}"""
            )
        )
        assertEquals("TOOL_INPUT_INVALID", AgentSecurityFixtures.rejection(extra).code)

        val approvedField = AgentSecurityFixtures.factory.freeze(
            AgentSecurityFixtures.request(
                AgentSecurityFixtures.descriptor(),
                """{"path":"inputs/x","approved":true}"""
            )
        )
        assertEquals("TOOL_INPUT_INVALID", AgentSecurityFixtures.rejection(approvedField).code)
    }

    @Test
    fun v1BoundsAreEnforcedBeforeApproval() {
        val list = AgentSecurityFixtures.descriptor(AgentBuiltInToolArgumentNormalizer.LIST)
        assertEquals("TOOL_INPUT_INVALID", rejectCode(list, """{"limit":0}"""))
        assertEquals("TOOL_INPUT_INVALID", rejectCode(list, """{"limit":201}"""))

        val read = AgentSecurityFixtures.descriptor()
        assertEquals("TOOL_INPUT_INVALID", rejectCode(read, """{"path":"inputs/x","limit":0}"""))
        assertEquals("TOOL_INPUT_INVALID", rejectCode(read, """{"path":"inputs/x","limit":16385}"""))
        assertEquals("TOOL_INPUT_INVALID", rejectCode(read, """{"path":"inputs/x","offset":-1}"""))
        assertEquals("TOOL_INPUT_INVALID", rejectCode(read, """{"path":"inputs/x","offset":1.5}"""))

        val search = AgentSecurityFixtures.descriptor(AgentBuiltInToolArgumentNormalizer.SEARCH_TEXT)
        assertEquals("TOOL_INPUT_INVALID", rejectCode(search, """{"query":"","maxMatches":20}"""))
        assertEquals("TOOL_INPUT_INVALID", rejectCode(search, """{"query":"${"q".repeat(257)}"}"""))
        assertEquals("TOOL_INPUT_INVALID", rejectCode(search, """{"query":"q","maxMatches":101}"""))

        val write = AgentSecurityFixtures.descriptor(
            toolKey = AgentBuiltInToolArgumentNormalizer.WRITE_TEXT,
            sideEffect = AgentToolSideEffect.WORKSPACE_WRITE
        )
        val tooLarge = "a".repeat(32 * 1024 + 1)
        assertEquals(
            "TOOL_INPUT_INVALID",
            rejectCode(write, """{"path":"artifacts/x.txt","content":"$tooLarge","expectedOldHash":null}""")
        )
        val maxSize = AgentSecurityFixtures.freeze(
            write,
            """{"path":"artifacts/x.txt","content":"${"a".repeat(32 * 1024)}","expectedOldHash":null}"""
        )
        assertTrue(maxSize.command.normalizedArgumentsJson.length > 32 * 1024)
    }

    @Test
    fun expectedOldHashMustBeExplicitAndCanonical() {
        val write = AgentSecurityFixtures.descriptor(
            toolKey = AgentBuiltInToolArgumentNormalizer.WRITE_TEXT,
            sideEffect = AgentToolSideEffect.WORKSPACE_WRITE
        )
        assertEquals("TOOL_INPUT_INVALID", rejectCode(write, """{"path":"artifacts/x","content":"x"}"""))
        assertEquals("TOOL_INPUT_INVALID", rejectCode(write, """{"path":"artifacts/x","content":"x","expectedOldHash":"ABC"}"""))
        assertEquals(
            "TOOL_INPUT_INVALID",
            rejectCode(write, """{"path":"artifacts/x","content":"x","expectedOldHash":"${"A".repeat(64)}"}""")
        )
        val explicitNull = AgentSecurityFixtures.freeze(
            write,
            """{"path":"artifacts/x","content":"x","expectedOldHash":null}"""
        )
        assertEquals(null, explicitNull.scope.expectedOldHash)
    }

    @Test
    fun networkDestinationsAreValidatedCanonicalizedAndSorted() {
        val networkTool = AgentSecurityFixtures.descriptor(
            toolKey = "mcp.echo",
            sideEffect = AgentToolSideEffect.NETWORK
        )
        val passthrough = object : AgentToolArgumentNormalizer {
            override fun normalize(
                descriptor: com.muyuchat.core.agent.contract.AgentToolDescriptor,
                rawArgumentsJson: String
            ): AgentArgumentNormalization =
                AgentArgumentNormalization.Normalized("{}", listOf("inputs"), null)
        }
        val factory = AgentFrozenCommandFactory(passthrough)
        val result = factory.freeze(
            AgentSecurityFixtures.request(
                networkTool,
                "{}",
                networkDestinations = listOf("https://B.example.com:443", "https://a.example.com", "https://a.example.com")
            )
        )
        assertTrue("expected frozen command, got $result", result is AgentFreezeResult.Frozen)
        assertEquals(
            listOf("https://a.example.com", "https://b.example.com"),
            (result as AgentFreezeResult.Frozen).command.networkDestinations
        )

        val invalid = factory.freeze(
            AgentSecurityFixtures.request(networkTool, "{}", networkDestinations = listOf("http://insecure.example.com"))
        )
        val error = AgentSecurityFixtures.rejection(invalid)
        assertEquals("POLICY_EGRESS_DENIED", error.code)
    }

    @Test
    fun rejectionsAndFrozenToStringNeverLeakArgumentsOrContent() {
        val secret = "SECRET-CONTENT-9f3c2a1b"
        val descriptor = AgentSecurityFixtures.descriptor(
            toolKey = AgentBuiltInToolArgumentNormalizer.WRITE_TEXT,
            sideEffect = AgentToolSideEffect.WORKSPACE_WRITE
        )
        val rejection = AgentSecurityFixtures.factory.freeze(
            AgentSecurityFixtures.request(
                descriptor,
                """{"path":"artifacts/x","content":"$secret","expectedOldHash":"not-a-hash"}"""
            )
        )
        val error = AgentSecurityFixtures.rejection(rejection)
        assertFalse(error.toString().contains(secret))
        assertFalse(error.safeMessage.contains(secret))
        assertFalse(error.safeMessage.contains("not-a-hash"))

        val frozen = AgentSecurityFixtures.freeze(
            descriptor,
            """{"path":"artifacts/x","content":"$secret","expectedOldHash":null}"""
        )
        assertFalse(frozen.toString().contains(secret))
        assertTrue(frozen.toString().contains(frozen.command.argumentsHash))
        assertFalse(frozen.hashPreimageHex.contains(secret))
    }

    @Test
    fun strictJsonRejectsDuplicateKeysCommentsAndTrailingContent() {
        val descriptor = AgentSecurityFixtures.descriptor()
        listOf(
            """{"path":"inputs/a","path":"inputs/b"}""",
            """{"path":"inputs/a"} {}""",
            """{"path":"inputs/a",}""",
            """{path:"inputs/a"}""",
            """{"path":"inputs/a","offset":1e3}""",
            """{"path":"inputs/a","offset":NaN}""",
            """{"path":"inputs/a","offset":01}""",
            """{"path":"inputs/a","nested":{"a":1}}""",
            """// comment
            {"path":"inputs/a"}"""
        ).forEach { raw ->
            assertEquals("TOOL_INPUT_INVALID", rejectCode(descriptor, raw))
        }
    }

    @Test
    fun writeContentRoundTripsThroughCanonicalEscaping() {
        val write = AgentSecurityFixtures.descriptor(
            toolKey = AgentBuiltInToolArgumentNormalizer.WRITE_TEXT,
            sideEffect = AgentToolSideEffect.WORKSPACE_WRITE
        )
        val raw = "{\"path\":\"artifacts/x.txt\",\"content\":" +
            "\"line1\\nline2 \\\"quoted\\\" \\\\slash \\u0041 \\uD83D\\uDE00\"," +
            "\"expectedOldHash\":null}"
        val frozen = AgentSecurityFixtures.freeze(write, raw)
        assertEquals(
            """{"content":"line1\nline2 \"quoted\" \\slash A 😀","expectedOldHash":null,"path":"artifacts/x.txt"}""",
            frozen.command.normalizedArgumentsJson
        )
    }

    @Test
    fun tamperedFrozenCommandIsNeverTrusted() {
        val frozen = AgentSecurityFixtures.freeze(
            AgentSecurityFixtures.descriptor(),
            """{"path":"inputs/brief.txt"}"""
        )
        val tampered = frozen.command.copy(argumentsHash = "0".repeat(64))
        assertNotEquals(frozen.command.argumentsHash, tampered.argumentsHash)
    }

    @Test
    fun explicitNullsOnOptionalFieldsAreNotSilentlyDefaulted() {
        val list = AgentSecurityFixtures.descriptor(AgentBuiltInToolArgumentNormalizer.LIST)
        assertEquals("TOOL_INPUT_INVALID", rejectCode(list, """{"path":null}"""))
        assertEquals("TOOL_INPUT_INVALID", rejectCode(list, """{"limit":null}"""))
        val read = AgentSecurityFixtures.descriptor()
        assertEquals("TOOL_INPUT_INVALID", rejectCode(read, """{"path":"inputs/x","offset":null}"""))
    }

    @Test
    fun equivalentRawFormattingFreezesToIdenticalBytes() {
        val descriptor = AgentSecurityFixtures.descriptor()
        val compact = AgentSecurityFixtures.freeze(
            descriptor,
            """{"path":"inputs/x","offset":0,"limit":4096}"""
        )
        val spaced = AgentSecurityFixtures.freeze(
            descriptor,
            """  {  "limit" : 4096 ,  "path" : "inputs/x" , "offset" : 0 }  """,
            invocationId = "inv-2"
        )
        assertEquals(compact.command.normalizedArgumentsJson, spaced.command.normalizedArgumentsJson)
        assertEquals(compact.command.argumentsHash, spaced.command.argumentsHash)
        assertEquals(compact.command.resourceFingerprint, spaced.command.resourceFingerprint)
        assertEquals(compact.command.resourcePreconditionsJson, spaced.command.resourcePreconditionsJson)
    }

    /**
     * N02c: a typed scope replaces the workspace-path shape. The persisted precondition is the
     * canonical typed document, the approval fingerprint is computed over it, and verification
     * re-derives both from the trusted scope. A legacy pseudo-path `memory` scope can never verify.
     */
    @Test
    fun typedMemoryScopeFreezesToCanonicalV2Precondition() {
        val descriptor = AgentSecurityFixtures.descriptor(
            toolKey = "memory.search",
            sideEffect = AgentToolSideEffect.READ_ONLY
        )
        val typed = AgentScopePrecondition(
            scopes = listOf(
                AgentResourceScope.Memory(
                    spaceIds = listOf("space-b", "space-a"),
                    access = AgentResourceAccess.READ
                )
            ),
            effects = setOf(AgentEffectKind.LOCAL_READ)
        )
        val frozen = AgentSecurityFixtures.freeze(
            descriptor = descriptor,
            rawArgumentsJson = """{"query":"计划"}""",
            typedScope = typed,
            factory = AgentSecurityFixtures.passthroughFactory
        )

        val canonical = AgentScopePreconditionCodec.canonicalJson(typed)
        assertEquals(canonical, frozen.command.resourcePreconditionsJson)
        assertEquals(
            AgentScopePreconditionCodec.fingerprintJson(canonical),
            frozen.command.resourceFingerprint
        )
        assertTrue(frozen.scope.logicalPaths.isEmpty())
        assertTrue(AgentFrozenIntegrity.matches(frozen.command, descriptor, frozen.scope))
        assertFalse(
            AgentFrozenIntegrity.matches(
                frozen.command,
                descriptor,
                AgentResolvedToolScope(
                    taskRoot = "ws-1/tasks/task-1",
                    logicalPaths = listOf("memory"),
                    expectedOldHash = null,
                    networkDestinations = emptyList()
                )
            )
        )
    }

    private fun rejectCode(
        descriptor: com.muyuchat.core.agent.contract.AgentToolDescriptor,
        rawArgumentsJson: String
    ): String = AgentSecurityFixtures.rejection(
        AgentSecurityFixtures.factory.freeze(
            AgentSecurityFixtures.request(descriptor, rawArgumentsJson)
        )
    ).code

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\u0000", "\\u0000")

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index ->
            ((Character.digit(hex[index * 2], 16) shl 4) or Character.digit(hex[index * 2 + 1], 16)).toByte()
        }

    private companion object {
        const val OLD_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val GOLDEN_PREIMAGE_HEX =
            "00000000000000156d63612e66726f7a656e2d636f6d6d616e642e7631" +
                "00000000000000337b226c696d6974223a343039362c226f6666736574223a302c2270617468223a22696e707574732f62726965662e747874227d" +
                "00000000000000700000000000000013776f726b73706163652e726561645f746578740000000000000005312e302e30000000000000004065336230633434323938666331633134396166626634633839393666623932343237616534316534363439623933346361343935393931623738353262383535" +
                "000000000000005b7b2265787065637465644f6c6448617368223a6e756c6c2c226c6f676963616c5061746873223a5b22696e707574732f62726965662e747874225d2c227461736b526f6f74223a2277732d312f7461736b732f7461736b2d31227d" +
                "000000000000000137"
    }
}
