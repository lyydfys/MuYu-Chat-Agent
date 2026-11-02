package com.muyuchat.mca.agent.toolpack

import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageFixtures
import com.muyuchat.mca.agent.extensions.toolpkg.AgentToolPackageManifestParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PKG-01: the three legal package samples each declare their own format, and nothing is guessed. */
class ToolPackageFormatClassifierTest {

    @Test
    fun theThreeLegalSamplesDeclareTheirOwnFormat() {
        val legacyManifest = AgentToolPackageFixtures.manifest()
        val legacy = ToolPackageFormatClassifier.classify(AgentToolPackageFixtures.validZip(legacyManifest))
        val legacyClassified = legacy as ToolPackageFormatClassifier.Result.Classified
        assertEquals(ToolPackageFormatClassifier.Format.INERT_V1, legacyClassified.format)
        assertTrue(legacyClassified.declaration.contains("schemaVersion 1"))
        // The legacy package stays readable by its own parser; classification did not replace it.
        val parsedLegacy = AgentToolPackageManifestParser(AgentToolPackageFixtures.hostCatalog())
            .parse(legacyManifest.toByteArray(Charsets.UTF_8))
        assertEquals(1, parsedLegacy.schemaVersion)

        val executableManifest = ToolpackFixtures.manifestJson()
        val executable = ToolPackageFormatClassifier.classify(
            AgentToolPackageFixtures.zip(
                listOf(ToolPackageArchive.MANIFEST_FILE_NAME to executableManifest.toByteArray(Charsets.UTF_8))
            )
        )
        val executableClassified = executable as ToolPackageFormatClassifier.Result.Classified
        assertEquals(ToolPackageFormatClassifier.Format.EXECUTABLE_V2, executableClassified.format)
        assertTrue(executableClassified.declaration.contains("schemaVersion 2"))
        val parsedExecutable = ExecutableToolPackageManifestParser().parse(executableManifest)
        assertEquals(ToolpackFixtures.PACKAGE_ID, parsedExecutable.packageId)

        val thirdpartyJson = thirdpartyZip(thirdpartyPackageEntries("manifest.json", thirdpartyManifestJson()))
        val jsonClassified = ToolPackageFormatClassifier.classify(thirdpartyJson)
                as ToolPackageFormatClassifier.Result.Classified
        assertEquals(ToolPackageFormatClassifier.Format.THIRD_PARTY, jsonClassified.format)
        assertEquals("root manifest.json", jsonClassified.declaration)
        val preview = ThirdPartyToolPackageImportAdapter().previewArchive(thirdpartyJson)
        val previewed = preview as ThirdPartyToolPackageImportResult.Previewed
        assertEquals("com-thirdparty-windows-bundle", previewed.descriptor.normalizedPackageId)
        assertFalse("a third-party package is never presented as runnable in this slice", previewed.report.runnable)
        assertFalse(previewed.descriptor.executable)

        val thirdpartyHjson = thirdpartyZip(thirdpartyPackageEntries("manifest.hjson", thirdpartyHjsonManifest()))
        val hjsonClassified = ToolPackageFormatClassifier.classify(thirdpartyHjson)
                as ToolPackageFormatClassifier.Result.Classified
        assertEquals(ToolPackageFormatClassifier.Format.THIRD_PARTY, hjsonClassified.format)
        assertEquals("root manifest.hjson", hjsonClassified.declaration)
    }

    @Test
    fun aManifestWithoutAnExplicitSchemaVersionIsRejectedNotGuessed() {
        // Every field of this object is a v2 field except the one that declares the format.
        val guessed = ToolPackageFormatClassifier.classify(
            manifestArchive("""{"packageId":"research-basics","packageVersion":"1.0.0","hostApiVersion":1}""")
        )
        assertEquals(
            ToolPackageErrorCode.MANIFEST_INVALID_FIELD,
            (guessed as ToolPackageFormatClassifier.Result.Rejected).error.code
        )

        val unsupported = ToolPackageFormatClassifier.classify(manifestArchive("""{"schemaVersion":3}"""))
        assertEquals(
            ToolPackageErrorCode.MANIFEST_UNSUPPORTED_FORMAT,
            (unsupported as ToolPackageFormatClassifier.Result.Rejected).error.code
        )

        val notJson = ToolPackageFormatClassifier.classify(manifestArchive("schemaVersion: 2"))
        assertEquals(
            ToolPackageErrorCode.MANIFEST_MALFORMED,
            (notJson as ToolPackageFormatClassifier.Result.Rejected).error.code
        )
    }

    @Test
    fun anArchiveThatDeclaresTwoFormatsIsRejected() {
        val both = AgentToolPackageFixtures.zip(
            listOf(
                ToolPackageArchive.MANIFEST_FILE_NAME to AgentToolPackageFixtures.manifest().toByteArray(),
                "manifest.json" to thirdpartyManifestJson().toByteArray()
            )
        )
        assertEquals(
            ToolPackageErrorCode.MANIFEST_FORMAT_AMBIGUOUS,
            (ToolPackageFormatClassifier.classify(both) as ToolPackageFormatClassifier.Result.Rejected).error.code
        )

        val twoThirdPartyManifests = thirdpartyZip(
            thirdpartyPackageEntries("manifest.json", thirdpartyManifestJson()) +
                ("manifest.hjson" to thirdpartyHjsonManifest().toByteArray())
        )
        assertEquals(
            ToolPackageErrorCode.MANIFEST_FORMAT_AMBIGUOUS,
            (ToolPackageFormatClassifier.classify(twoThirdPartyManifests) as ToolPackageFormatClassifier.Result.Rejected)
                .error.code
        )

        val noManifest = AgentToolPackageFixtures.zip(listOf("notes.txt" to "hi".toByteArray()))
        assertEquals(
            ToolPackageErrorCode.ARCHIVE_MISSING_MANIFEST,
            (ToolPackageFormatClassifier.classify(noManifest) as ToolPackageFormatClassifier.Result.Rejected).error.code
        )

        val empty = ToolPackageFormatClassifier.classify(ByteArray(0))
        assertEquals(
            ToolPackageErrorCode.ARCHIVE_INVALID,
            (empty as ToolPackageFormatClassifier.Result.Rejected).error.code
        )
    }

    private fun manifestArchive(manifestJson: String): ByteArray = AgentToolPackageFixtures.zip(
        listOf(ToolPackageArchive.MANIFEST_FILE_NAME to manifestJson.toByteArray(Charsets.UTF_8))
    )
}
