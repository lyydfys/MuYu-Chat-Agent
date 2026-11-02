package com.muyuchat.mca.agent.toolpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolpackManifestTest {

    private val parser = ExecutableToolPackageManifestParser()

    @Test
    fun `parses the executable manifest and freezes canonical identity`() {
        val manifest = parser.parse(ToolpackFixtures.manifestJson())

        assertEquals(2, manifest.formatVersion)
        assertEquals("research-basics", manifest.packageId)
        assertEquals("1.0.0", manifest.packageVersion)
        assertEquals(1, manifest.hostApiVersion)
        assertEquals("main.js", manifest.entry)
        assertEquals(listOf("workspace.read_text"), manifest.hostApis)
        assertEquals(1, manifest.tools.size)
        val tool = manifest.tools.single()
        assertEquals("read_brief", tool.name)
        assertEquals("tools/read_brief.js", tool.entry)
        assertEquals(10_000L, tool.timeoutMillis)
        assertEquals(64, manifest.manifestSha256.length)
        assertTrue(manifest.manifestSha256.matches(Regex("^[0-9a-f]{64}$")))
        assertTrue(tool.inputSchemaJson.startsWith("""{"additionalProperties":false"""))
    }

    @Test
    fun `canonical hash ignores object key order and changes with content`() {
        val first = parser.parse(ToolpackFixtures.manifestJson())
        val reordered = parser.parse(
            """
            {
              "tools":[${ToolpackFixtures.toolJson()}],
              "hostApis":["workspace.read_text"],
              "entry":"main.js",
              "description":"Read-only workspace and memory tools.",
              "displayName":"Research basics",
              "hostApiVersion":1,
              "packageVersion":"1.0.0",
              "packageId":"research-basics",
              "schemaVersion":2
            }
            """.trimIndent()
        )
        assertEquals(first.manifestSha256, reordered.manifestSha256)
        assertEquals(first.canonicalJson, reordered.canonicalJson)

        val changed = parser.parse(ToolpackFixtures.manifestJson(description = "Different description."))
        assertNotEquals(first.manifestSha256, changed.manifestSha256)
    }

    @Test
    fun `rejects the inert v1 format and an unsupported host API version`() {
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_UNSUPPORTED_FORMAT) {
            parser.parse(ToolpackFixtures.manifestJson(schemaVersion = 1))
        }
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_UNSUPPORTED_HOST_API_VERSION) {
            parser.parse(ToolpackFixtures.manifestJson(hostApiVersion = 2))
        }
    }

    @Test
    fun `rejects executable and self-authorizing manifest fields`() {
        for (key in listOf("network", "shell", "native", "hooks", "permissions", "filesystem", "wasm")) {
            ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_FORBIDDEN_FIELD) {
                parser.parse(ToolpackFixtures.manifestJson(extraRoot = mapOf(key to "value")))
            }
        }
    }

    @Test
    fun `rejects unknown root and tool fields`() {
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_UNKNOWN_FIELD) {
            parser.parse(ToolpackFixtures.manifestJson(extraRoot = mapOf("unexpected" to "value")))
        }
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_UNKNOWN_FIELD) {
            parser.parse(
                ToolpackFixtures.manifestJson(
                    tools = listOf(ToolpackFixtures.toolJson().put("unexpected", "value"))
                )
            )
        }
    }

    @Test
    fun `rejects invalid ids versions and entry paths`() {
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_INVALID_ID) {
            parser.parse(ToolpackFixtures.manifestJson(packageId = "Bad Id"))
        }
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_INVALID_VERSION) {
            parser.parse(ToolpackFixtures.manifestJson(packageVersion = "1.0"))
        }
        for (entry in listOf("../escape.js", "/abs.js", "tools\\read.js", "tools/read.ts", "tools//read.js")) {
            ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_INVALID_ENTRY) {
                parser.parse(ToolpackFixtures.manifestJson(entry = entry))
            }
        }
    }

    @Test
    fun `rejects unknown duplicated and oversized host capabilities`() {
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_HOST_API_UNKNOWN) {
            parser.parse(ToolpackFixtures.manifestJson(hostApis = listOf("network.fetch")))
        }
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_HOST_API_DUPLICATE) {
            parser.parse(
                ToolpackFixtures.manifestJson(
                    hostApis = listOf(ToolPackageHostApis.MEMORY_SEARCH, ToolPackageHostApis.MEMORY_SEARCH)
                )
            )
        }
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_HOST_API_TOO_MANY) {
            parser.parse(
                ToolpackFixtures.manifestJson(
                    hostApis = List(ToolPackageLimits.MAX_HOST_APIS + 1) { ToolPackageHostApis.MEMORY_SEARCH }
                )
            )
        }
    }

    @Test
    fun `rejects duplicate tools malformed schemas and invalid timeouts`() {
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_DUPLICATE_TOOL) {
            parser.parse(
                ToolpackFixtures.manifestJson(
                    tools = listOf(ToolpackFixtures.toolJson(), ToolpackFixtures.toolJson())
                )
            )
        }
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_INVALID_SCHEMA) {
            parser.parse(
                ToolpackFixtures.manifestJson(
                    tools = listOf(
                        ToolpackFixtures.toolJson(
                            inputSchema = """{"type":"object","properties":{"x":{"type":"string"}}}"""
                        )
                    )
                )
            )
        }
        for (timeout in listOf(0L, ToolPackageLimits.MAX_TOOL_TIMEOUT_MILLIS + 1)) {
            ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_TOOL_TIMEOUT_INVALID) {
                parser.parse(
                    ToolpackFixtures.manifestJson(tools = listOf(ToolpackFixtures.toolJson(timeoutMillis = timeout)))
                )
            }
        }
    }

    @Test
    fun `rejects malformed duplicate-key and oversized manifests`() {
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_MALFORMED) {
            parser.parse("""{"schemaVersion":2,""")
        }
        val duplicate = """{"schemaVersion":2,"schemaVersion":2,"packageId":"research-basics"}"""
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_DUPLICATE_KEY) {
            parser.parse(duplicate)
        }
        val oversized = """{"padding":"${"a".repeat(ToolPackageLimits.MAX_MANIFEST_BYTES.toInt())}"}"""
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.MANIFEST_TOO_LARGE) {
            parser.parse(oversized)
        }
    }

    @Test
    fun `bundle requires every declared entry and hashes deterministically`() {
        val manifest = parser.parse(ToolpackFixtures.manifestJson())
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.SCRIPT_MISSING) {
            ExecutableToolPackage.load(manifest, mapOf(ToolpackFixtures.MAIN_ENTRY to "main"))
        }
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.SCRIPT_INVALID_PATH) {
            ExecutableToolPackage.load(
                manifest,
                ToolpackFixtures.scripts() + ("../evil.js" to "x")
            )
        }

        val first = ExecutableToolPackage.load(manifest, ToolpackFixtures.scripts())
        val reordered = ExecutableToolPackage.load(
            manifest,
            ToolpackFixtures.scripts().entries.reversed().associate { it.key to it.value }
        )
        assertEquals(first.contentHash, reordered.contentHash)
        val changed = ExecutableToolPackage.load(
            manifest,
            ToolpackFixtures.scripts(ToolpackFixtures.TOOL_ENTRY to "// changed\n")
        )
        assertNotEquals(first.contentHash, changed.contentHash)
        assertEquals(64, first.contentHash.length)
    }

    @Test
    fun `runtime tool key keeps a hyphenated package id descriptor-safe`() {
        assertEquals(
            "pkg.research_basics.read_brief",
            ToolPackageLimits.runtimeToolKey("research-basics", "read_brief")
        )
        assertEquals(
            "pkg.other_pkg.tool",
            ToolPackageLimits.runtimeToolKey("other-pkg", "tool")
        )
        assertNotEquals(
            ToolPackageLimits.runtimeToolKey("a-b", "tool"),
            ToolPackageLimits.runtimeToolKey("c-d", "tool")
        )
    }
}
