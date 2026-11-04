package com.muyuchat.mca

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Batch import for world books and generation presets.
 *
 * Both workflows used to require one picker round-trip per file. The batch entry points exist so a
 * user with ten exports does not tap ten times; these assertions pin the wiring that makes the
 * one-shot path actually reach them, and that a partial failure is reported rather than silent.
 */
class BatchImportContractTest {

    @Test
    fun `world book picker is multi select and reaches the batch entry`() {
        val activity = source("app/src/main/java/com/muyuchat/mca/MainActivity.kt")
        assertTrue(activity.contains("registerForActivityResult(OpenAnyDocumentsContract())"))
        assertTrue(activity.contains("it.importWorldBookFiles(uris.map(Uri::toString), scope, owner)"))
        // The single-file path stays available (character cards, other callers).
        assertTrue(activity.contains("onImportWorldBookFile"))
    }

    @Test
    fun `world book batch imports each file and reports partial failures`() {
        val source = source("app/src/main/java/com/muyuchat/mca/KnowledgeBaseManagementOperations.kt")
        assertTrue(source.contains("internal fun importWorldBookFilesFor("))
        assertTrue(source.contains("for (uriString in targets)"))
        // One unreadable export must not abort the rest of the batch.
        assertTrue(source.contains("continue"))
        // The picker result is read once; a per-file commit id replaces per-file preview staging,
        // which is what used to make the import "jump" and keep only the last book.
        assertTrue(source.contains("WorldBookDuplicateGuard.bookIdFor(fingerprint)"))
        assertTrue(source.contains("importWorldBookOwned("))
        // Duplicates are detected from content and counted, not silently dropped.
        assertTrue(source.contains("WorldBookDuplicateGuard.fingerprints(existingWorldBooks())"))
        assertTrue(source.contains("skippedDuplicates++"))
        assertTrue(source.contains("WorldBookBatchImportSummary(imported, skippedDuplicates, failures)"))
        assertTrue(source.contains("showStatus(summary.statusMessage())"))
    }

    @Test
    fun `world book batch summary names every count and caps the failure list`() {
        val guard = source("app/src/main/java/com/muyuchat/mca/WorldBookDuplicateGuard.kt")
        assertTrue(guard.contains("已导入 "))
        assertTrue(guard.contains(" 本世界书"))
        assertTrue(guard.contains("跳过 "))
        assertTrue(guard.contains(" 个重复"))
        assertTrue(guard.contains(" 本失败："))
        assertTrue(guard.contains("MAX_REPORTED_FAILURES"))
    }

    @Test
    fun `preset picker is multi select and imports in one store write`() {
        val section = source("feature/modelhub/src/main/java/com/muyuchat/feature/modelhub/GenerationPresetManagerSection.kt")
        assertTrue(section.contains("ActivityResultContracts.OpenMultipleDocuments()"))
        assertTrue(section.contains("onImportPresets(targets)"))

        val source = source("app/src/main/java/com/muyuchat/mca/GenerationPresetOperations.kt")
        assertTrue(source.contains("internal fun importGenerationPresetsFor("))
        assertTrue(source.contains("已导入 \${imported.size} 个模型预设"))
        // The batch import must do exactly one read-modify-write for the whole selection. Count
        // only the import block: delete and binding saves live in their own functions.
        val importBlock = source.substringAfter("internal fun importGenerationPresetsFor(")
            .substringBefore("internal fun deleteGenerationPresetFor(")
        val saves = Regex("presetStore\\.save\\(").findAll(importBlock).count()
        assertTrue("expected a single store write for the batch, found $saves", saves == 1)
        assertTrue(importBlock.contains("presetStore.save(presetStore.load() + imported)"))
    }

    @Test
    fun `preset delete and binding writes leave the main thread`() {
        val source = source("app/src/main/java/com/muyuchat/mca/MainViewModelGenerationPresetDelegates.kt")
        assertTrue(source.contains("viewModelScope.launch(Dispatchers.IO)"))
        // Two of the three delegates (delete + bindings) must be dispatch-wrapped.
        val launches = Regex("launch\\(Dispatchers\\.IO\\)").findAll(source).count()
        assertTrue("expected delete and bindings to dispatch off main, found $launches", launches >= 2)
    }

    private fun source(relativePath: String) =
        File(projectRoot(), relativePath).readText(Charsets.UTF_8)

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("project root not found")
    }
}
