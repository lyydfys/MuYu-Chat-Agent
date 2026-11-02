package com.muyuchat.mca.agent.data.export

import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.mca.agent.data.AgentTestFixtures
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.coroutines.runBlocking

class AgentTaskPackageValidationTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var importStaging: File

    @Before
    fun setUp() {
        importStaging = temporary.newFolder("import-staging")
    }

    @Test
    fun `path traversal absolute backslash and control paths are rejected`() {
        expectFailure(
            AgentPackageTestFixtures.zip(listOf("../escape.txt" to byteArrayOf(1))),
            AgentPackageErrorCode.UNSAFE_PATH
        )
        expectFailure(
            AgentPackageTestFixtures.zip(listOf("/etc/passwd" to byteArrayOf(1))),
            AgentPackageErrorCode.UNSAFE_PATH
        )
        expectFailure(
            AgentPackageTestFixtures.zip(listOf("artifacts\\evil.txt" to byteArrayOf(1))),
            AgentPackageErrorCode.UNSAFE_PATH
        )
        expectFailure(
            AgentPackageTestFixtures.zip(listOf("artifacts/../../evil.txt" to byteArrayOf(1))),
            AgentPackageErrorCode.UNSAFE_PATH
        )
        assertNull(
            AgentPackagePathGuard.validateArtifactPath(
                "artifacts/\u0000evil.txt",
                AgentTaskPackageLimits()
            )
        )
        assertNull(
            AgentPackagePathGuard.validateArtifactPath(
                "artifacts/.versions/v1.blob",
                AgentTaskPackageLimits()
            )
        )
        val deep = "artifacts/" + (1..17).joinToString("/") { "d$it" }
        assertNull(AgentPackagePathGuard.validateArtifactPath(deep, AgentTaskPackageLimits()))
    }

    @Test
    fun `duplicate normalized entry is rejected`() {
        val bytes = AgentPackageTestFixtures.zip(
            listOf(
                "manifest.json" to AgentPackageTestFixtures.manifestFor(emptyList()),
                "artifacts/A.txt" to byteArrayOf(1),
                "artifacts/a.txt" to byteArrayOf(2)
            )
        )
        expectFailure(bytes, AgentPackageErrorCode.DUPLICATE_ENTRY)
    }

    @Test
    fun `entry count size ratio and total limits are enforced`() {
        expectFailure(
            AgentPackageTestFixtures.validPackageBytes(),
            AgentPackageErrorCode.TOO_MANY_ENTRIES,
            AgentTaskPackageLimits(maxEntries = 1)
        )
        expectFailure(
            AgentPackageTestFixtures.validPackageBytes(listOf("artifacts/a.txt" to ByteArray(64) { 1 })),
            AgentPackageErrorCode.ENTRY_TOO_LARGE,
            AgentTaskPackageLimits(maxEntryBytes = 4)
        )
        expectFailure(
            AgentPackageTestFixtures.validPackageBytes(listOf("artifacts/a.txt" to ByteArray(4096) { 0 })),
            AgentPackageErrorCode.ENTRY_RATIO_EXCEEDED,
            AgentTaskPackageLimits(maxCompressionRatio = 1)
        )
        expectFailure(
            AgentPackageTestFixtures.validPackageBytes(listOf("artifacts/a.txt" to ByteArray(64) { 1 })),
            AgentPackageErrorCode.PACKAGE_TOO_LARGE,
            AgentTaskPackageLimits(maxTotalExpandedBytes = 10, maxEntryBytes = 1024)
        )
    }

    @Test
    fun `missing manifest and unknown major are rejected`() {
        expectFailure(
            AgentPackageTestFixtures.zip(listOf("artifacts/a.txt" to byteArrayOf(1))),
            AgentPackageErrorCode.MISSING_MANIFEST
        )
        val unknownMajor = """
            {"format":"mca.agent.task-package.v2","version":2}
        """.trimIndent().toByteArray()
        expectFailure(
            AgentPackageTestFixtures.zip(listOf("manifest.json" to unknownMajor)),
            AgentPackageErrorCode.UNSUPPORTED_VERSION
        )
        expectFailure(
            AgentPackageTestFixtures.zip(listOf("manifest.json" to "{not json".toByteArray())),
            AgentPackageErrorCode.MANIFEST_INVALID
        )
    }

    @Test
    fun `manifest path mismatch missing and unreferenced entries are rejected`() {
        val artifactBytes = "alpha".toByteArray()
        val manifest = AgentPackageTestFixtures.manifestFor(listOf("artifacts/a.txt" to artifactBytes))
        expectFailure(
            AgentPackageTestFixtures.zip(
                listOf(
                    "manifest.json" to manifest,
                    "artifacts/b.txt" to artifactBytes
                )
            ),
            AgentPackageErrorCode.MANIFEST_PATH_MISMATCH
        )
        expectFailure(
            AgentPackageTestFixtures.zip(
                listOf(
                    "manifest.json" to manifest,
                    "artifacts/a.txt" to artifactBytes,
                    "artifacts/extra.txt" to "extra".toByteArray()
                )
            ),
            AgentPackageErrorCode.MANIFEST_PATH_MISMATCH
        )
        expectFailure(
            AgentPackageTestFixtures.zip(listOf("manifest.json" to manifest)),
            AgentPackageErrorCode.MANIFEST_PATH_MISMATCH
        )
    }

    @Test
    fun `hash and size mismatches are rejected and staging is cleaned`() {
        val artifactBytes = "alpha".toByteArray()
        val wrongSha = AgentTaskPackageManifest(
            exportedAtMillis = 1_000L,
            task = AgentTaskDisplaySummary(
                displayTitle = "title",
                displayStatus = AgentDisplayStatus.SUCCEEDED,
                createdAtMillis = 1L,
                updatedAtMillis = 2L
            ),
            artifacts = listOf(
                AgentPackageArtifactEntry("artifacts/a.txt", "text/plain", artifactBytes.size.toLong(), "0".repeat(64))
            )
        )
        val hashMismatch = AgentPackageTestFixtures.zip(
            listOf(
                "manifest.json" to AgentTaskPackageManifestCodec.encode(wrongSha, AgentTaskPackageLimits()),
                "artifacts/a.txt" to artifactBytes
            )
        )
        expectFailure(hashMismatch, AgentPackageErrorCode.HASH_MISMATCH)
        assertTrue(importStaging.listFiles().orEmpty().isEmpty())

        val wrongSize = wrongSha.copy(
            artifacts = listOf(
                AgentPackageArtifactEntry(
                    "artifacts/a.txt",
                    "text/plain",
                    artifactBytes.size - 1L,
                    AgentPackageTestFixtures.sha256(artifactBytes)
                )
            )
        )
        expectFailure(
            AgentPackageTestFixtures.zip(
                listOf(
                    "manifest.json" to AgentTaskPackageManifestCodec.encode(wrongSize, AgentTaskPackageLimits()),
                    "artifacts/a.txt" to artifactBytes
                )
            ),
            AgentPackageErrorCode.SIZE_MISMATCH
        )
    }

    @Test
    fun `truncated archive is rejected`() {
        val bytes = AgentPackageTestFixtures.validPackageBytes()
        expectFailure(
            AgentPackageTestFixtures.truncate(bytes, 9),
            AgentPackageErrorCode.TRUNCATED_ARCHIVE
        )
    }

    @Test
    fun `unknown optional manifest fields are preserved as inert metadata`() {
        val contents = "alpha".toByteArray()
        val manifest = AgentPackageTestFixtures.manifestFor(listOf("artifacts/a.txt" to contents))
        val json = org.json.JSONObject(String(manifest, Charsets.UTF_8))
        json.put("futureField", "ignored")
        val bytes = AgentPackageTestFixtures.zip(
            listOf(
                "manifest.json" to json.toString().toByteArray(),
                "artifacts/a.txt" to contents
            )
        )
        val result = runBlocking {
            importer(AgentTaskPackageLimits()).import(ByteArrayInputStream(bytes))
        }
        assertTrue(result is AgentPackageResult.Success)
        val imported = (result as AgentPackageResult.Success).value
        assertTrue(imported.unknownManifestJson!!.contains("futureField"))
    }

    private fun expectFailure(
        bytes: ByteArray,
        expected: AgentPackageErrorCode,
        limits: AgentTaskPackageLimits = AgentTaskPackageLimits()
    ) {
        val result = runBlocking {
            importer(limits).import(ByteArrayInputStream(bytes))
        }
        assertTrue("expected failure ${expected.name} but got $result", result is AgentPackageResult.Failure)
        assertEquals(expected, (result as AgentPackageResult.Failure).code)
        assertTrue("failed import must not leave staging files", importStaging.listFiles().orEmpty().isEmpty())
    }

    private fun importer(limits: AgentTaskPackageLimits): AgentTaskPackageImporter =
        AgentTaskPackageImporter(
            stagingRoot = importStaging,
            idGenerator = AgentTestFixtures.SequentialIds(500),
            limits = limits
        )
}
