package com.muyuchat.mca.agent.extensions.toolpkg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolPackageManifestParserTest {
    private val parser = AgentToolPackageManifestParser(AgentToolPackageFixtures.hostCatalog())

    @Test
    fun `parses a valid read-only manifest and binds trusted host metadata`() {
        val manifest = parser.parse(AgentToolPackageFixtures.manifest())

        assertEquals(1, manifest.schemaVersion)
        assertEquals(1, manifest.apiVersion)
        assertEquals("research-basics", manifest.packageId)
        assertEquals("1.0.0", manifest.packageVersion)
        assertEquals(1, manifest.tools.size)
        assertEquals(1, manifest.resources.size)
        val tool = manifest.tools.single()
        assertEquals("read_brief", tool.name)
        assertEquals("workspace.read_text", tool.hostOperation)
        assertEquals(listOf("path"), tool.presetKeys)
        assertEquals("""{"path":"inputs/brief.txt"}""", tool.presetJson)
        assertEquals(listOf("limit", "offset"), tool.exposedParameters)
        assertEquals(10_000L, tool.timeoutMillis)
        assertEquals("NOTES.md", manifest.resources.single().path)
        assertEquals("text/markdown", manifest.resources.single().mediaType)
        assertEquals(64, manifest.manifestSha256.length)
        assertTrue(manifest.manifestSha256.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun `defaults timeout to the host operation maximum`() {
        val manifest = parser.parse(
            AgentToolPackageFixtures.manifest(
                toolsJson = "[${AgentToolPackageFixtures.toolEntry(timeoutMillis = null)}]"
            )
        )
        assertEquals(30_000L, manifest.tools.single().timeoutMillis)
    }

    @Test
    fun `canonical hash ignores object key order but changes with content`() {
        val first = parser.parse(AgentToolPackageFixtures.manifest())
        val reordered = parser.parse(
            """{
              "resources":[${AgentToolPackageFixtures.resourceEntry("NOTES.md", "text/markdown", AgentToolPackageFixtures.notes())}],
              "tools":[${AgentToolPackageFixtures.toolEntry()}],
              "requires":[],
              "description":"Curated read-only workspace tools.",
              "displayName":"Research basics",
              "apiVersion":1,
              "packageVersion":"1.0.0",
              "packageId":"research-basics",
              "schemaVersion":1
            }"""
        )
        assertEquals(first.manifestSha256, reordered.manifestSha256)
        assertEquals(first.canonicalJson, reordered.canonicalJson)

        val changed = parser.parse(AgentToolPackageFixtures.manifest(description = "Different description."))
        assertNotEquals(first.manifestSha256, changed.manifestSha256)
    }

    @Test
    fun `rejects unknown root and tool fields`() {
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_UNKNOWN_FIELD) {
            parser.parse(AgentToolPackageFixtures.manifest(extraRoot = ""","unexpected":"value""""))
        }
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_UNKNOWN_FIELD) {
            parser.parse(
                AgentToolPackageFixtures.manifest(
                    toolsJson = "[${AgentToolPackageFixtures.toolEntry(extra = ""","unexpected":"value"""")}]"
                )
            )
        }
    }

    @Test
    fun `rejects every executable or self-authorizing manifest key`() {
        val forbidden = listOf("script", "js", "wasm", "dex", "hooks", "executor", "network")
        for (key in forbidden) {
            AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_EXECUTABLE_FIELD) {
                parser.parse(AgentToolPackageFixtures.manifest(extraRoot = ""","$key":"value""""))
            }
            AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_EXECUTABLE_FIELD) {
                parser.parse(
                    AgentToolPackageFixtures.manifest(
                        toolsJson = "[${AgentToolPackageFixtures.toolEntry(extra = ""","$key":"value"""")}]"
                    )
                )
            }
        }
    }

    @Test
    fun `rejects duplicate JSON keys`() {
        val duplicateKey = """{
          "schemaVersion":1,"packageId":"research-basics","packageId":"other",
          "packageVersion":"1.0.0","apiVersion":1,"displayName":"x","tools":[],"resources":[]
        }"""
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_DUPLICATE_KEY) {
            parser.parse(duplicateKey)
        }
    }

    @Test
    fun `rejects invalid ids versions and API versions`() {
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_INVALID_ID) {
            parser.parse(AgentToolPackageFixtures.manifest(packageId = "Research Basics"))
        }
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_INVALID_VERSION) {
            parser.parse(AgentToolPackageFixtures.manifest(packageVersion = "1.0"))
        }
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_UNSUPPORTED_SCHEMA_VERSION) {
            parser.parse(AgentToolPackageFixtures.manifest(schemaVersion = 2))
        }
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_UNSUPPORTED_API_VERSION) {
            parser.parse(AgentToolPackageFixtures.manifest(apiVersion = 2))
        }
    }

    @Test
    fun `rejects an unknown or write host operation`() {
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_HOST_OPERATION_NOT_ALLOWED) {
            parser.parse(
                AgentToolPackageFixtures.manifest(
                    toolsJson = "[${AgentToolPackageFixtures.toolEntry(hostOperation = "network.fetch")}]"
                )
            )
        }
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_HOST_OPERATION_NOT_ALLOWED) {
            parser.parse(
                AgentToolPackageFixtures.manifest(
                    toolsJson = "[${AgentToolPackageFixtures.toolEntry(hostOperation = "artifact.write_text")}]"
                )
            )
        }
    }

    @Test
    fun `rejects preset and expose widening`() {
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_PRESET_INVALID) {
            parser.parse(
                AgentToolPackageFixtures.manifest(
                    toolsJson = "[${AgentToolPackageFixtures.toolEntry(preset = """{"unknown":"x"}""")}]"
                )
            )
        }
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_PRESET_INVALID) {
            parser.parse(
                AgentToolPackageFixtures.manifest(
                    toolsJson = "[${AgentToolPackageFixtures.toolEntry(preset = """{"path":42}""")}]"
                )
            )
        }
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_EXPOSE_INVALID) {
            parser.parse(
                AgentToolPackageFixtures.manifest(
                    toolsJson = "[${AgentToolPackageFixtures.toolEntry(expose = """["path"]""")}]"
                )
            )
        }
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_EXPOSE_INVALID) {
            parser.parse(
                AgentToolPackageFixtures.manifest(
                    toolsJson = "[${AgentToolPackageFixtures.toolEntry(expose = """["mode"]""")}]"
                )
            )
        }
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_EXPOSE_INVALID) {
            parser.parse(
                AgentToolPackageFixtures.manifest(
                    toolsJson = "[${AgentToolPackageFixtures.toolEntry(
                        preset = "{}",
                        expose = """["offset"]"""
                    )}]"
                )
            )
        }
    }

    @Test
    fun `rejects a timeout above the host operation limit`() {
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_TIMEOUT_INVALID) {
            parser.parse(
                AgentToolPackageFixtures.manifest(
                    toolsJson = "[${AgentToolPackageFixtures.toolEntry(timeoutMillis = "30001")}]"
                )
            )
        }
    }

    @Test
    fun `rejects duplicate tool names`() {
        val tools = "[${AgentToolPackageFixtures.toolEntry()},${AgentToolPackageFixtures.toolEntry()}]"
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_DUPLICATE_TOOL) {
            parser.parse(AgentToolPackageFixtures.manifest(toolsJson = tools))
        }
    }

    @Test
    fun `rejects path traversal and duplicate normalized resource paths`() {
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.PATH_TRAVERSAL) {
            parser.parse(
                AgentToolPackageFixtures.manifest(
                    resourcesJson = AgentToolPackageFixtures.resourceEntries(
                        AgentToolPackageFixtures.resourceEntry(
                            "../NOTES.md",
                            "text/markdown",
                            AgentToolPackageFixtures.notes()
                        )
                    )
                )
            )
        }
        val duplicate = AgentToolPackageFixtures.resourceEntries(
            AgentToolPackageFixtures.resourceEntry("NOTES.md", "text/markdown", AgentToolPackageFixtures.notes()),
            AgentToolPackageFixtures.resourceEntry("./NOTES.md", "text/markdown", AgentToolPackageFixtures.notes())
        )
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_DUPLICATE_RESOURCE) {
            parser.parse(AgentToolPackageFixtures.manifest(resourcesJson = duplicate))
        }
    }

    @Test
    fun `rejects executable resource types and media type mismatches`() {
        val executable = AgentToolPackageFixtures.resourceEntries(
            AgentToolPackageFixtures.resourceEntry("payload.js", "text/plain", "alert(1)".toByteArray())
        )
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_RESOURCE_TYPE_UNSUPPORTED) {
            parser.parse(AgentToolPackageFixtures.manifest(resourcesJson = executable))
        }
        val mismatched = AgentToolPackageFixtures.resourceEntries(
            AgentToolPackageFixtures.resourceEntry("NOTES.md", "application/octet-stream", AgentToolPackageFixtures.notes())
        )
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_RESOURCE_TYPE_UNSUPPORTED) {
            parser.parse(AgentToolPackageFixtures.manifest(resourcesJson = mismatched))
        }
    }

    @Test
    fun `rejects duplicate and self dependencies`() {
        val duplicate = """[{"packageId":"base-tools","version":">=1.0.0"},{"packageId":"base-tools","version":"*"}]"""
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_DEPENDENCY_DUPLICATE) {
            parser.parse(AgentToolPackageFixtures.manifest(requiresJson = duplicate))
        }
        val self = """[{"packageId":"research-basics","version":"*"}]"""
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_DEPENDENCY_SELF) {
            parser.parse(AgentToolPackageFixtures.manifest(requiresJson = self))
        }
    }

    @Test
    fun `rejects an oversized manifest before parsing it`() {
        val oversized = AgentToolPackageFixtures.manifest(
            description = "d".repeat(AgentToolPackageLimits.MAX_MANIFEST_BYTES.toInt())
        )
        AgentToolPackageFixtures.assertPackageFailure(AgentToolPackageErrorCode.MANIFEST_TOO_LARGE) {
            parser.parse(oversized)
        }
    }

    @Test
    fun `accepts a manifest with no resources and only exposed required parameters`() {
        val manifest = parser.parse(
            AgentToolPackageFixtures.manifest(
                resourcesJson = "[]",
                toolsJson = "[${AgentToolPackageFixtures.toolEntry(
                    hostOperation = "workspace.list",
                    preset = "{}",
                    expose = """["path","limit"]"""
                )}]"
            )
        )
        val tool = manifest.tools.single()
        assertEquals("workspace.list", tool.hostOperation)
        assertTrue(tool.presetKeys.isEmpty())
        assertEquals(listOf("limit", "path"), tool.exposedParameters)
        assertTrue(manifest.resources.isEmpty())
        assertFalse(tool.presetJson.contains("null"))
    }
}
