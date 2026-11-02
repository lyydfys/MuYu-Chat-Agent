package com.muyuchat.mca.composition

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Security contract of the DSH diagnostics share/save entry.
 *
 * `src/test` has no Android runtime, so the FileProvider grant cannot be exercised here. What can be
 * pinned is what actually leaked in review before: the provider stays unexported, the paths file
 * exposes exactly one narrow directory, and the sharer hands out a read-only URI of one `.json`
 * file. The export itself is already redacted by `DshDiagnosticsExporter` (it fails closed when a
 * secret or absolute path survives), so the share entry must not widen that surface.
 */
class DshDiagnosticsShareContractTest {

    private val manifest = sourceFile("src/main/AndroidManifest.xml")
    private val paths = sourceFile("src/main/res/xml/image_share_file_paths.xml")
    private val sharer = sourceFile(
        "src/main/java/com/muyuchat/mca/composition/DshDiagnosticsSharing.kt"
    )

    @Test
    fun theFileProviderIsUnexportedAndGrantsOnlyPerUriReadAccess() {
        val providerBlock = manifest.substringAfter("androidx.core.content.FileProvider")
            .substringBefore("</provider>")
        assertTrue(providerBlock.contains("""android:authorities="${'$'}{applicationId}.fileprovider""""))
        assertTrue(providerBlock.contains("""android:exported="false""""))
        assertTrue(providerBlock.contains("""android:grantUriPermissions="true""""))
    }

    @Test
    fun thePathsFileExposesTheDiagnosticsDirectoryAndNothingBroad() {
        assertTrue(paths.contains("""path="${DshDiagnosticsShareContract.PROVIDER_PATH_ENTRY}/""""))
        // No root/external path may ever appear: those would expose unrelated app data.
        assertFalse(paths.contains("<root-path"))
        assertFalse(paths.contains("<external-path"))
        assertFalse(paths.contains("<external-files-path"))
        assertFalse(paths.contains("<external-cache-path"))
        assertFalse(paths.contains("path=\".\""))
        assertFalse(paths.contains("""path="/""""))
    }

    @Test
    fun sharingUsesAReadOnlySendChooserAndTheNarrowProvider() {
        assertTrue(sharer.contains("Intent.ACTION_SEND"))
        assertTrue(sharer.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"))
        assertTrue(sharer.contains("FileProvider.getUriForFile"))
        assertTrue(sharer.contains("DshDiagnosticsShareContract.FILE_PROVIDER_SUFFIX"))
        assertTrue(sharer.contains("Intent.createChooser"))
        // The sharer must never expose a path, a token or a file:// URI.
        assertFalse(sharer.contains("file://"))
        assertFalse(sharer.contains("EXTRA_TEXT"))
        assertFalse(sharer.contains("dataRoot"))
    }

    @Test
    fun onlyANonEmptyJsonBundleIsShareable() {
        val directory = File(System.getProperty("java.io.tmpdir"), "mca-diagnostics-share-test")
            .also { it.mkdirs() }
        try {
            val json = File(directory, "diagnostics-1.json").apply { writeText("{}") }
            assertTrue(DshDiagnosticsShareContract.isShareable(json))

            val empty = File(directory, "diagnostics-empty.json").apply { writeText("") }
            assertFalse(DshDiagnosticsShareContract.isShareable(empty))

            val other = File(directory, "backup.zip").apply { writeText("PK") }
            assertFalse(DshDiagnosticsShareContract.isShareable(other))

            assertFalse(DshDiagnosticsShareContract.isShareable(File(directory, "absent.json")))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun sourceFile(relative: String): String {
        val candidates = sequenceOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull(File::isFile) ?: error("Unable to locate $relative")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }
}
