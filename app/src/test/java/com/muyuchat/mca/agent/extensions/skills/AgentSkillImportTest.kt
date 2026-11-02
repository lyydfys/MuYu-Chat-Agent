package com.muyuchat.mca.agent.extensions.skills

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentSkillImportTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var snapshots = FakeSnapshotRepository()
    private val importer: AgentSkillImporter = AgentSkillImporter(snapshots, clock = { 7L })

    private fun packageFile(bytes: ByteArray, name: String = "skill.zip"): File =
        temp.newFile(name).apply { writeBytes(bytes) }

    @Test
    fun `imports a zip into an immutable snapshot and keeps scripts preview only`() {
        val zip = AgentSkillFixtures.zipSkill(
            markdown = AgentSkillFixtures.validMarkdown(
                extraFrontmatter = "x-harness: other\n"
            ),
            extraEntries = listOf(
                "docs/a.md" to "resource".toByteArray(),
                "cover.png" to byteArrayOf(1, 2, 3),
                "scripts/run.sh" to "echo SHOULD_NOT_RUN".toByteArray()
            )
        )
        val file = packageFile(zip)
        val manifest = importer.importZip("content://skill/one", file)

        assertEquals("Test Skill", manifest.name)
        assertEquals(1L, manifest.version)
        assertFalse(manifest.enabled)
        assertEquals(1, manifest.schemaVersion)
        assertEquals("SKILL.md", manifest.entryPath)
        assertEquals(listOf("cover.png", "docs/a.md"), manifest.resourcePaths)
        assertEquals(AgentSkillMetadataValue.Text("other"), manifest.metadata["x-harness"])
        assertTrue(manifest.source.fingerprint.startsWith("zip:"))
        assertEquals("content://skill/one", manifest.source.uri)
        assertEquals(setOf("SKILL.md", "cover.png", "docs/a.md"),
            snapshots.created.getValue(manifest.snapshotId).files.map { it.relativePath }.toSet())

        val preview = importer.inspectZip("content://skill/one", file)
        assertEquals(listOf("scripts/run.sh"), preview.previewOnlyScripts)
        assertTrue(preview.files.none { it.path.startsWith("scripts/") })
        assertEquals(manifest.sha256, preview.contentSha256)
    }

    @Test
    fun `imports a tree snapshot and installs resources`() {
        val manifest = importer.importTree(
            "content://tree/one",
            AgentSkillFixtures.treeSkill(
                extraRootChildren = listOf(
                    AgentSkillFixtures.treeDirectory(
                        "docs",
                        listOf(AgentSkillFixtures.treeFile("guide.md", "# Guide"))
                    )
                )
            ),
            enable = true
        )
        assertTrue(manifest.enabled)
        assertEquals(listOf("docs/guide.md"), manifest.resourcePaths)
        assertTrue(manifest.source.fingerprint.startsWith("tree:"))
        assertEquals(setOf("SKILL.md", "docs/guide.md"),
            snapshots.created.getValue(manifest.snapshotId).files.map { it.relativePath }.toSet())
    }

    @Test
    fun `rejects path traversal absolute paths and normalized duplicates`() {
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.PATH_TRAVERSAL) {
            importer.importZip("u", packageFile(AgentSkillFixtures.zipSkill(
                extraEntries = listOf("../evil.txt" to "x".toByteArray())
            ), "traversal.zip"))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.PATH_TRAVERSAL) {
            importer.importZip("u", packageFile(AgentSkillFixtures.zipSkill(
                extraEntries = listOf("..\\evil.txt" to "x".toByteArray())
            ), "traversal2.zip"))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.ABSOLUTE_PATH) {
            importer.importZip("u", packageFile(AgentSkillFixtures.zipSkill(
                extraEntries = listOf("/evil.txt" to "x".toByteArray())
            ), "absolute.zip"))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.DUPLICATE_PATH) {
            importer.importZip("u", packageFile(AgentSkillFixtures.zip(listOf(
                "SKILL.md" to AgentSkillFixtures.validMarkdown(),
                "docs/./a.md" to "one".toByteArray(),
                "docs/a.md" to "two".toByteArray()
            )), "duplicate.zip"))
        }
        val deep = (1..17).joinToString("/") { "d" } + "/f.txt"
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.PATH_DEPTH_EXCEEDED) {
            importer.importZip("u", packageFile(AgentSkillFixtures.zipSkill(
                extraEntries = listOf(deep to "x".toByteArray())
            ), "deep.zip"))
        }
    }

    @Test
    fun `rejects symlink encrypted and unknown-compression zip entries`() {
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.SYMLINK_REJECTED) {
            importer.importZip("u", packageFile(AgentSkillFixtures.customZip(listOf(
                AgentSkillFixtures.RawZipEntry("SKILL.md", AgentSkillFixtures.validMarkdown()),
                AgentSkillFixtures.RawZipEntry(
                    name = "link",
                    externalAttributes = 0xA1FFL shl 16,
                    versionMadeBy = (3 shl 8) or 20
                )
            )), "symlink.zip"))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.ENCRYPTED_ENTRY) {
            importer.importZip("u", packageFile(AgentSkillFixtures.customZip(listOf(
                AgentSkillFixtures.RawZipEntry("SKILL.md", AgentSkillFixtures.validMarkdown()),
                AgentSkillFixtures.RawZipEntry("secret.txt", "x".toByteArray(), flags = 1)
            )), "encrypted.zip"))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.UNSUPPORTED_COMPRESSION) {
            importer.importZip("u", packageFile(AgentSkillFixtures.customZip(listOf(
                AgentSkillFixtures.RawZipEntry("SKILL.md", AgentSkillFixtures.validMarkdown()),
                AgentSkillFixtures.RawZipEntry("data.txt", "x".toByteArray(), method = 12)
            )), "unknown-method.zip"))
        }
    }

    @Test
    fun `rejects entry count expanded size single size and compression ratio limits`() {
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.ENTRY_COUNT_EXCEEDED) {
            val entries = (1..2001).map { "f$it.txt" to "x".toByteArray() }
            importer.importZip("u", packageFile(AgentSkillFixtures.zip(
                listOf("SKILL.md" to AgentSkillFixtures.validMarkdown()) + entries
            ), "many.zip"))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.ENTRY_SIZE_EXCEEDED) {
            importer.importZip("u", packageFile(AgentSkillFixtures.customZip(listOf(
                AgentSkillFixtures.RawZipEntry("SKILL.md", AgentSkillFixtures.validMarkdown()),
                AgentSkillFixtures.RawZipEntry(
                    "big.bin",
                    uncompressedSizeOverride = AgentSkillLimits.MAX_SINGLE_ENTRY_BYTES + 1,
                    compressedSizeOverride = AgentSkillLimits.MAX_SINGLE_ENTRY_BYTES + 1
                )
            )), "big.zip"))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.EXPANDED_SIZE_EXCEEDED) {
            importer.importZip("u", packageFile(AgentSkillFixtures.customZip(listOf(
                AgentSkillFixtures.RawZipEntry("SKILL.md", AgentSkillFixtures.validMarkdown())
            ) + (1..3).map { index ->
                AgentSkillFixtures.RawZipEntry(
                    "data$index.txt",
                    uncompressedSizeOverride = 200L * 1024 * 1024,
                    compressedSizeOverride = 200L * 1024 * 1024
                )
            }), "expanded.zip"))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.COMPRESSION_RATIO_EXCEEDED) {
            importer.importZip("u", packageFile(AgentSkillFixtures.zipSkill(
                extraEntries = listOf("data.txt" to ByteArray(1024 * 1024))
            ), "bomb.zip"))
        }
    }

    @Test
    fun `rejects missing skill md oversized skill md unsupported resources and oversized packages`() {
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.MISSING_SKILL_MD) {
            importer.importZip("u", packageFile(AgentSkillFixtures.zip(
                listOf("docs/a.md" to "x".toByteArray())
            ), "missing.zip"))
        }
        val oversized = ByteArray(64 * 1024 + 1) { index -> (index % 251).toByte() }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.SKILL_MD_TOO_LARGE) {
            importer.importZip("u", packageFile(AgentSkillFixtures.customZip(listOf(
                AgentSkillFixtures.RawZipEntry("SKILL.md", oversized)
            )), "oversized-md.zip"))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.UNSUPPORTED_RESOURCE) {
            importer.importZip("u", packageFile(AgentSkillFixtures.zipSkill(
                extraEntries = listOf("tool.exe" to "MZ".toByteArray())
            ), "exe.zip"))
        }
        val huge = temp.newFile("huge.zip")
        RandomAccessFile(huge, "rw").use { it.setLength(AgentSkillLimits.MAX_PACKAGE_BYTES + 1) }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.PACKAGE_TOO_LARGE) {
            importer.importZip("u", huge)
        }
    }

    @Test
    fun `tree import rejects loops symlinks duplicates and missing skill md`() {
        lateinit var loopDirectory: FakeTreeDocument
        loopDirectory = FakeTreeDocument(
            documentId = "loop",
            displayName = "loop",
            type = AgentSkillDocumentType.DIRECTORY,
            childProvider = { listOf(loopDirectory) }
        )
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.LOOP_DOCUMENT_ID) {
            importer.importTree("u", AgentSkillFixtures.treeSkill(
                extraRootChildren = listOf(loopDirectory)
            ))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.SYMLINK_REJECTED) {
            importer.importTree("u", AgentSkillFixtures.treeSkill(
                extraRootChildren = listOf(FakeTreeDocument(
                    documentId = "link",
                    displayName = "link",
                    type = AgentSkillDocumentType.SYMLINK
                ))
            ))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.DUPLICATE_PATH) {
            importer.importTree("u", AgentSkillFixtures.treeSkill(
                extraRootChildren = listOf(
                    AgentSkillFixtures.treeFile("same.md", "a", id = "same-1"),
                    AgentSkillFixtures.treeFile("same.md", "b", id = "same-2")
                )
            ))
        }
        AgentSkillFixtures.assertSkillFailure(AgentSkillErrorCode.MISSING_SKILL_MD) {
            importer.importTree("u", AgentSkillFixtures.treeDirectory(
                "root", listOf(AgentSkillFixtures.treeFile("docs.md", "x"))
            ))
        }
    }

    @Test
    fun `tree import never opens or installs script documents`() {
        val script = AgentSkillFixtures.treeFile("run.sh", "echo SHOULD_NOT_RUN", id = "script-1")
        val manifest = importer.importTree(
            "content://tree/scripts",
            AgentSkillFixtures.treeSkill(
                extraRootChildren = listOf(
                    AgentSkillFixtures.treeDirectory("scripts", listOf(script), id = "scripts-dir")
                )
            )
        )
        assertEquals(0, script.openCount)
        assertTrue(manifest.resourcePaths.isEmpty())
        assertEquals(setOf("SKILL.md"),
            snapshots.created.getValue(manifest.snapshotId).files.map { it.relativePath }.toSet())
    }
}
