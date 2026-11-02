package com.muyuchat.mca.agent.toolpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * third-party import preview (PKG-01/PKG-03).
 *
 * Everything is JVM and in-memory: no device, no SAF, no store, no script execution. The malicious
 * ZIP case intentionally exercises the existing v2 archive layer instead of duplicating it.
 */
class ThirdPartyToolPackageImportAdapterTest {

    private val adapter = ThirdPartyToolPackageImportAdapter()

    @Test
    fun `valid JSON manifest produces a normalised descriptor and a classified report`() {
        val result = previewed(adapter.preview(thirdpartyManifestJson(), thirdpartyListing()))

        assertEquals(ThirdPartyManifestFormat.JSON, result.descriptor.manifestFormat)
        assertEquals("com.thirdparty.windows_bundle", result.descriptor.toolPkgId)
        assertEquals("com-thirdparty-windows-bundle", result.descriptor.normalizedPackageId)
        assertEquals("0.2.0", result.descriptor.version)
        assertEquals("1.0.1", result.descriptor.apiVersion)
        assertEquals("main.js", result.descriptor.mainEntry)
        assertEquals(1, result.descriptor.subpackages.size)
        assertEquals(2, result.descriptor.resources.size)
        assertEquals(1, result.descriptor.workflowTemplates.size)
        assertTrue(result.descriptor.previewOnly)
        assertFalse(result.descriptor.executable)
        assertEquals(64, result.descriptor.manifestSha256.length)
        assertEquals(64, result.descriptor.contentSha256.length)

        assertEquals(
            ThirdPartyCompatibilityStatus.SUPPORTED,
            result.report.findingsFor("schema_version=1").single().status
        )
        assertEquals(
            ThirdPartyCompatibilityStatus.TRANSLATED,
            result.report.findingsFor("toolpkg_id").single().status
        )
        assertEquals(
            ThirdPartyCompatibilityStatus.REQUIRES_HOST_CAPABILITY,
            result.report.findingsFor("main").single().status
        )
        assertEquals(
            ThirdPartyCompatibilityStatus.REQUIRES_HOST_CAPABILITY,
            result.report.findingsFor("quick_chat_workflow").single().status
        )
        assertEquals(4, result.report.contexts.size)
        assertTrue(result.report.context(ThirdPartyExecutionContext.MAIN).declared)
        assertTrue(result.report.context(ThirdPartyExecutionContext.SANDBOX).declared)
        assertFalse(result.report.context(ThirdPartyExecutionContext.UI).declared)
        assertFalse(result.report.runnable)
    }

    @Test
    fun `valid HJSON manifest resolves to the same canonical hashes as JSON`() {
        val json = previewed(adapter.preview(thirdpartyManifestJson(), thirdpartyListing()))
        val hjson = previewed(adapter.preview(thirdpartyHjsonManifest(), thirdpartyListing()))

        assertEquals(ThirdPartyManifestFormat.HJSON, hjson.descriptor.manifestFormat)
        assertEquals(json.descriptor.manifestSha256, hjson.descriptor.manifestSha256)
        assertEquals(json.descriptor.contentSha256, hjson.descriptor.contentSha256)
        assertEquals(json.descriptor.normalizedPackageId, hjson.descriptor.normalizedPackageId)
        assertEquals(
            json.report.findings.map { it.status },
            hjson.report.findings.map { it.status }
        )
    }

    @Test
    fun `missing required fields are rejected with the exact field path`() {
        rejected(
            adapter.preview(thirdpartyManifestJson(id = null), thirdpartyListing()),
            ThirdPartyImportErrorCode.MISSING_FIELD,
            "manifest.toolpkg_id"
        )
        rejected(
            adapter.preview(thirdpartyManifestJson(main = null), thirdpartyListing()),
            ThirdPartyImportErrorCode.MISSING_FIELD,
            "manifest.main"
        )
        rejected(
            adapter.preview(thirdpartyManifestJson(version = "1.0"), thirdpartyListing()),
            ThirdPartyImportErrorCode.INVALID_FIELD,
            "manifest.version"
        )
    }

    @Test
    fun `unknown API version and unknown context are stable typed rejections`() {
        rejected(
            adapter.preview(thirdpartyManifestJson(apiVersion = "9.9.9"), thirdpartyListing()),
            ThirdPartyImportErrorCode.UNSUPPORTED_API_VERSION,
            "manifest.api_version"
        )
        rejected(
            adapter.preview(
                thirdpartyManifestJson(),
                thirdpartyListing(),
                declaredContexts = listOf(ThirdPartyDeclaredContext("background", "contexts[0]"))
            ),
            ThirdPartyImportErrorCode.UNKNOWN_CONTEXT,
            "contexts[0]"
        )
    }

    @Test
    fun `WASM modules are declared but unsupported because no runtime is wired`() {
        val wasm = """[{"id":"core","path":"modules/core.wasm","exports":["isPrime"],"abi":"assemblyscript"}]"""
        val result = previewed(
            adapter.preview(
                thirdpartyManifestJson(wasm = wasm),
                thirdpartyListing(extra = listOf(ThirdPartyListedEntry("modules/core.wasm", sizeBytes = 16)))
            )
        )

        val finding = result.report.findingsFor("core").single()
        assertEquals(ThirdPartyCompatibilityCategory.WASM, finding.category)
        assertEquals(ThirdPartyCompatibilityStatus.UNSUPPORTED, finding.status)
        assertTrue(finding.alternative!!.isNotBlank())
        assertFalse(result.report.runnable)

        rejected(
            adapter.preview(thirdpartyManifestJson(wasm = wasm), thirdpartyListing()),
            ThirdPartyImportErrorCode.ENTRY_MISSING,
            "manifest.wasm_modules[0].path"
        )
    }

    @Test
    fun `unsupported or missing main entries are rejected instead of guessed`() {
        for (main in listOf("main.ts", "../main.js", "/main.js", "main.exe")) {
            rejected(
                adapter.preview(thirdpartyManifestJson(main = main), thirdpartyListing()),
                ThirdPartyImportErrorCode.UNSUPPORTED_ENTRY,
                "manifest.main"
            )
        }
        rejected(
            adapter.preview(thirdpartyManifestJson(main = "missing.js"), thirdpartyListing()),
            ThirdPartyImportErrorCode.ENTRY_MISSING,
            "manifest.main"
        )
    }

    @Test
    fun `unknown and self-authorizing manifest fields are rejected with their path`() {
        rejected(
            adapter.preview(thirdpartyManifestJson(extra = "\"unexpected\":1"), thirdpartyListing()),
            ThirdPartyImportErrorCode.UNKNOWN_FIELD,
            "manifest.unexpected"
        )
        rejected(
            adapter.preview(thirdpartyManifestJson(extra = "\"shell\":\"sh\""), thirdpartyListing()),
            ThirdPartyImportErrorCode.FORBIDDEN_FIELD,
            "manifest.shell"
        )
        rejected(
            adapter.preview(
                """{"schema_version":1,"schema_version":1,"toolpkg_id":"com.thirdparty.x","main":"main.js"}""",
                thirdpartyListing()
            ),
            ThirdPartyImportErrorCode.MANIFEST_DUPLICATE_KEY,
            "manifest"
        )
        // An MCA executable v2 manifest must not be guessed as a third-party manifest.
        rejected(
            adapter.preview(
                """{"schemaVersion":2,"packageId":"research-basics","packageVersion":"1.0.0"}""",
                thirdpartyListing()
            ),
            ThirdPartyImportErrorCode.UNKNOWN_FIELD,
            "manifest.schemaVersion"
        )
    }

    @Test
    fun `UI and provider contexts are detected but never enabled`() {
        val result = previewed(
            adapter.preview(
                thirdpartyManifestJson(),
                thirdpartyListing(
                    extra = listOf(
                        ThirdPartyListedEntry("ui/panel/index.ui.js"),
                        ThirdPartyListedEntry("provider/agent.js")
                    )
                )
            )
        )

        assertTrue(result.report.context(ThirdPartyExecutionContext.UI).declared)
        assertTrue(result.report.context(ThirdPartyExecutionContext.PROVIDER).declared)
        assertEquals(
            ThirdPartyCompatibilityStatus.UNSUPPORTED,
            result.report.context(ThirdPartyExecutionContext.UI).status
        )
        assertEquals(
            ThirdPartyCompatibilityStatus.UNSUPPORTED,
            result.report.context(ThirdPartyExecutionContext.PROVIDER).status
        )
        assertEquals(ThirdPartyCompatibilityStatus.UNSUPPORTED, result.report.summaryStatus)
        assertFalse(result.report.runnable)
    }

    @Test
    fun `previewing identical input is idempotent and listing order does not change hashes`() {
        val first = previewed(adapter.preview(thirdpartyManifestJson(), thirdpartyListing()))
        val second = previewed(adapter.preview(thirdpartyManifestJson(), thirdpartyListing()))
        val reversed = previewed(
            adapter.preview(
                thirdpartyManifestJson(),
                ThirdPartyPackageListing(thirdpartyListing().entries.reversed())
            )
        )

        assertEquals(first.descriptor, second.descriptor)
        assertEquals(first.report, second.report)
        assertEquals(first.descriptor.manifestSha256, reversed.descriptor.manifestSha256)
        assertEquals(first.descriptor.contentSha256, reversed.descriptor.contentSha256)
        assertNotEquals(
            first.descriptor.contentSha256,
            previewed(adapter.preview(thirdpartyManifestJson(description = "changed"), thirdpartyListing()))
                .descriptor.contentSha256
        )
    }

    @Test
    fun `archive preview reads JSON and HJSON manifests without extracting anything`() {
        val jsonArchive = thirdpartyZip(thirdpartyPackageEntries("manifest.json", thirdpartyManifestJson()))
        val hjsonArchive = thirdpartyZip(thirdpartyPackageEntries("manifest.hjson", thirdpartyHjsonManifest()))

        val json = previewed(adapter.previewArchive(jsonArchive))
        val hjson = previewed(adapter.previewArchive(hjsonArchive))

        assertEquals(ThirdPartyManifestFormat.JSON, json.descriptor.manifestFormat)
        assertEquals(ThirdPartyManifestFormat.HJSON, hjson.descriptor.manifestFormat)
        assertEquals(json.descriptor.manifestSha256, hjson.descriptor.manifestSha256)
        assertEquals(json.descriptor.contentSha256, hjson.descriptor.contentSha256)
        assertEquals(
            json.descriptor.manifestSha256,
            previewed(adapter.previewArchive(jsonArchive)).descriptor.manifestSha256
        )
        rejected(
            adapter.previewArchive(thirdpartyZip(thirdpartyPackageEntries("manifest.hjson", "{"))),
            ThirdPartyImportErrorCode.MANIFEST_MALFORMED,
            "manifest"
        )
        rejected(
            adapter.previewArchive(
                thirdpartyZip(
                    thirdpartyPackageEntries("manifest.json", thirdpartyManifestJson()) +
                        ("manifest.hjson" to thirdpartyHjsonManifest().toByteArray())
                )
            ),
            ThirdPartyImportErrorCode.ARCHIVE_INVALID,
            "manifest"
        )
    }

    @Test
    fun `executable artifacts in a third-party archive are reported and never loaded`() {
        val archive = thirdpartyZip(
            thirdpartyPackageEntries("manifest.json", thirdpartyManifestJson()) +
                ("classes.dex" to byteArrayOf(1, 2, 3))
        )
        val result = previewed(adapter.previewArchive(archive))

        val finding = result.report.findingsFor("classes.dex").single()
        assertEquals(ThirdPartyCompatibilityStatus.UNSUPPORTED, finding.status)
        assertEquals("listing[4]", finding.fieldPath)
        assertFalse(result.report.runnable)
    }

    @Test
    fun `zip traversal is rejected by the existing archive layer and surfaced by the adapter`() {
        val v2Archive = thirdpartyZip(
            listOf(
                "mca-tool-package.json" to thirdpartyManifestJson().toByteArray(),
                "../escape.js" to "x".toByteArray()
            )
        )
        ToolpackFixtures.assertPackageError(ToolPackageErrorCode.ARCHIVE_INVALID) {
            ToolPackageArchive.read(v2Archive)
        }

        val thirdpartyArchive = thirdpartyZip(
            listOf(
                "manifest.json" to thirdpartyManifestJson().toByteArray(),
                "../escape.js" to "x".toByteArray()
            )
        )
        rejected(
            adapter.previewArchive(thirdpartyArchive),
            ThirdPartyImportErrorCode.ARCHIVE_INVALID,
            "archive"
        )
    }
}

private fun previewed(result: ThirdPartyToolPackageImportResult): ThirdPartyToolPackageImportResult.Previewed {
    assertTrue("expected Previewed, got $result", result is ThirdPartyToolPackageImportResult.Previewed)
    return result as ThirdPartyToolPackageImportResult.Previewed
}

private fun rejected(
    result: ThirdPartyToolPackageImportResult,
    code: ThirdPartyImportErrorCode,
    fieldPath: String
): ThirdPartyToolPackageImportResult.Rejected {
    assertTrue("expected Rejected, got $result", result is ThirdPartyToolPackageImportResult.Rejected)
    val rejection = result as ThirdPartyToolPackageImportResult.Rejected
    assertEquals(code, rejection.code)
    assertEquals(fieldPath, rejection.fieldPath)
    assertTrue(rejection.safeMessage.isNotBlank())
    return rejection
}
