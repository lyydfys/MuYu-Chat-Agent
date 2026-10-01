package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflinePromptTranslationFileSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun unchangedPackageKeepsItsInstalledIdentityWithoutRereadingWeightContents() {
        val files = packageFiles()
        val snapshot = requireNotNull(OfflinePromptTranslationFileSnapshot.capture(files))
        assertTrue(snapshot.isCurrent())
        assertTrue(snapshot.isCurrent())
    }

    @Test
    fun missingModelManifestOrNoticeInvalidatesInstalledState() {
        for (name in listOf("model.gguf", "manifest.json", "NOTICE.txt")) {
            val files = packageFiles()
            val snapshot = requireNotNull(OfflinePromptTranslationFileSnapshot.capture(files))
            assertTrue(files.first { it.name == name }.delete())
            assertFalse("Missing $name must restore installation actions", snapshot.isCurrent())
        }
    }

    @Test
    fun sameLengthRewriteWithChangedMetadataRequiresFullReverification() {
        val files = packageFiles()
        val snapshot = requireNotNull(OfflinePromptTranslationFileSnapshot.capture(files))
        val model = files.first { it.name == "model.gguf" }
        val modified = Files.getLastModifiedTime(model.toPath())
        model.writeText("changed")
        Files.setLastModifiedTime(model.toPath(), FileTime.fromMillis(modified.toMillis() + 2_000L))
        assertFalse(snapshot.isCurrent())
    }

    @Test
    fun layoutChangesCannotKeepThePreviousVerifiedPackageCached() {
        val files = packageFiles()
        val snapshot = requireNotNull(OfflinePromptTranslationFileSnapshot.capture(files))
        File(files[1], "unexpected.bin").writeText("extra")
        assertFalse(snapshot.isCurrent())
    }

    @Test
    fun incompletePackageCannotBeCapturedAsThePreviousTrustedFiles() {
        val root = temporary.newFolder().canonicalFile
        assertTrue(OfflinePromptTranslationFileSnapshot.capture(listOf(root, File(root, "missing.gguf"))) == null)
    }

    private fun packageFiles(): List<File> {
        val root = temporary.newFolder().canonicalFile
        val translation = File(root, "translation").also { check(it.mkdir()) }
        val model = File(translation, "model.gguf").also { it.writeText("initial") }
        val manifest = File(translation, "manifest.json").also { it.writeText("manifest") }
        val notice = File(translation, "NOTICE.txt").also { it.writeText("license") }
        assertNotNull(root.list())
        return listOf(root, translation, model, manifest, notice)
    }
}
