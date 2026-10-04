package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the image-persistence write path (P5 kickoff, ledger slice).
 *
 * The ledger records where `chatSessionStore` image writers are reachable from, when this record
 * was last re-derived:
 *
 *  - `ChatImageRetryAndCleanupOperations.kt` and `ImageUpscaleOwner.kt` route through their ports
 *    (2 sites each);
 *  - `ImageLibraryCommitOperations.kt` holds the image-library commit transaction, which took the
 *    atomic save, its gallery fallback and the cancelled-publish rollback (3 sites, P5 step 3);
 *  - `MainViewModel.kt` still carries four direct sites (startup/generation placeholder, batch
 *    delete, favourite, clear) -- the remaining convergence debt recorded in
 *    `P5-00-image-domain-map.md`.
 *
 * Any new direct site fails here and forces a deliberate decision -- converge it into a named
 * owner, or extend this record with the reason it must stay.
 */
class ImageWritePathContractTest {
    @Test
    fun theDirectImageWriterSitesAreTheRecordedOnes() {
        assertEquals(
            "A new direct chatSessionStore image-writer call needs a deliberate owner decision; update this record's KDoc.",
            mapOf(
                "MainViewModel.kt" to 4,
                "ChatImageRetryAndCleanupOperations.kt" to 2,
                "ImageUpscaleOwner.kt" to 2,
                "ImageLibraryCommitOperations.kt" to 3
            ),
            imageWriterSites()
        )
    }

    @Test
    fun theViewModelDirectSitesKeepTheirExactShapes() {
        val vm = sourceFile("MainViewModel.kt")

        listOf(
            "chatSessionStore.upsertImages(listOf(image))",
            "chatSessionStore.deleteImages(removedIds)",
            "chatSessionStore.setImageFavorite(imageId, favorite)",
            "chatSessionStore.clearImages()"
        ).forEach { literal ->
            assertEquals(
                "Missing or duplicated direct site: $literal",
                1,
                Regex(Regex.escape(literal)).findAll(vm).count()
            )
        }

        val commitOwner = sourceFile("ImageLibraryCommitOperations.kt")
        listOf(
            "chatSessionStore.saveGeneratedImageResult(snapshot.sessions, generatedImages)",
            "chatSessionStore.upsertImages(generatedImages)",
            "chatSessionStore.deleteImages(generatedIds)"
        ).forEach { literal ->
            assertEquals(
                "Missing or duplicated owner site: $literal",
                1,
                Regex(Regex.escape(literal)).findAll(commitOwner).count()
            )
        }
    }

    private fun imageWriterSites(): Map<String, Int> {
        val dir = packageDirectory()
        val pattern = Regex("""chatSessionStore\.(saveGeneratedImageResult|upsertImages|deleteImages|setImageFavorite|clearImages)\(""")
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val count = pattern.findAll(file.readText(Charsets.UTF_8)).count()
                if (count > 0) file.name to count else null
            }
            .toMap()
    }

    private fun packageDirectory(): File {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            val candidates = listOf(
                File(root, "src/main/java/com/muyuchat/mca"),
                File(root, "app/src/main/java/com/muyuchat/mca")
            )
            candidates.firstOrNull(File::isDirectory)?.let { return it }
            directory = root.parentFile
        }
        error("com/muyuchat/mca not found from ${System.getProperty("user.dir")}")
    }

    private fun sourceFile(name: String): String {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val root = directory ?: return@repeat
            val candidates = listOf(
                File(root, "src/main/java/com/muyuchat/mca/$name"),
                File(root, "app/src/main/java/com/muyuchat/mca/$name")
            )
            candidates.firstOrNull { it.isFile }?.let { return it.readText(Charsets.UTF_8) }
            directory = root.parentFile
        }
        error("$name not found from ${System.getProperty("user.dir")}")
    }
}
