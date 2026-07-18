package com.muyuchat.mca

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Test

class ImageLibraryBackupManifestTest {
    private val id = "123e4567-e89b-12d3-a456-426614174000"

    @Test
    fun staleImportCleanupDoesNotFollowNestedSymbolicLinks() {
        val root = Files.createTempDirectory("image-backup-root").toFile()
        val outside = Files.createTempDirectory("image-backup-outside").toFile()
        try {
            val stale = File(root, ".backup-import-stale").apply { mkdirs() }
            val sentinel = File(outside, "sentinel.txt").apply { writeText("keep") }
            try {
                Files.createSymbolicLink(File(stale, "outside-link").toPath(), outside.toPath())
            } catch (error: Throwable) {
                assumeNoException(error)
            }

            assertTrue(deleteOwnedTreeWithoutFollowingSymbolicLinks(root, stale))
            assertFalse(stale.exists())
            assertTrue(sentinel.isFile)
            assertEquals("keep", sentinel.readText())
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun validManifestPreservesFavoriteAndSanitizesGenerationMetadata() {
        val metadata = ImageGenerationHistoryMetadata(
            backend = ImageBackend.LOCAL,
            modelId = "restored-model",
            modelName = "Restored Model",
            requestPrompt = "restore me",
            options = LocalImageGenerationOptions(
                taskMode = LocalImageTaskMode.IMG2IMG,
                strength = 0.7
            ),
            inputDraft = LocalImageInputDraft(
                taskMode = LocalImageTaskMode.IMG2IMG,
                inputImageReference = "content://private/input",
                strength = 0.7
            ),
            nativeExecutionJson = """{"modelPath":"/data/user/0/private/model"}"""
        )
        val manifest = manifestWith(
            item = validItem().put("generationMetadata", JSONObject(metadata.toJsonString()))
        )

        val parsed = parseImageLibraryBackupManifest(manifest)
        val item = parsed.items.single()

        assertEquals(0, parsed.invalidItems)
        assertTrue(item.favorite)
        assertEquals("restored-model", item.generationModelId)
        assertFalse(item.generationMetadataJson.contains("content://"))
        assertFalse(item.generationMetadataJson.contains("/data/user/"))
        assertFalse(item.generationMetadataJson.contains("nativeExecution"))
    }

    @Test
    fun traversalAndMismatchedIdsAreRejectedAsInvalidItems() {
        val traversal = validItem().put("entryPath", "images/../escape.png")
        val mismatched = validItem().put(
            "entryPath",
            "images/223e4567-e89b-12d3-a456-426614174000.png"
        )

        val parsed = parseImageLibraryBackupManifest(
            JSONObject()
                .put("format", "mca-image-library-backup")
                .put("version", 1)
                .put("items", JSONArray().put(traversal).put(mismatched))
        )

        assertTrue(parsed.items.isEmpty())
        assertEquals(2, parsed.invalidItems)
        assertEquals(
            setOf("images/223e4567-e89b-12d3-a456-426614174000.png"),
            parsed.declaredEntryPaths
        )
    }

    @Test
    fun invalidMetadataRetainsOnlySafeDeclaredArchivePathForBoundedSkipping() {
        val invalid = validItem()
            .put("favorite", "not-a-boolean")
            .put("entryPath", "images/$id.png")

        val parsed = parseImageLibraryBackupManifest(manifestWith(invalid))

        assertTrue(parsed.items.isEmpty())
        assertEquals(1, parsed.invalidItems)
        assertEquals(setOf("images/$id.png"), parsed.declaredEntryPaths)
        assertFalse("images/../escape.png" in parsed.declaredEntryPaths)
    }

    @Test
    fun newerBackupVersionFailsClosed() {
        try {
            parseImageLibraryBackupManifest(
                JSONObject()
                    .put("format", "mca-image-library-backup")
                    .put("version", 2)
                    .put("items", JSONArray())
            )
            fail("Expected a format exception")
        } catch (_: ImageLibraryBackupFormatException) {
            // Expected.
        }
    }

    @Test
    fun mobileSafetyLimitsRetainProductSizedImagesWithoutDesktopScaleBudgets() {
        assertEquals(4_096, ImageLibraryBackupLimits.MAX_IMAGE_SIDE)
        assertEquals(16_777_216L, ImageLibraryBackupLimits.MAX_IMAGE_PIXELS)
        assertTrue(ImageLibraryBackupLimits.MAX_IMAGE_BYTES >= 4_096L * 4_096L * 4L)
        assertTrue(ImageLibraryBackupLimits.MAX_IMAGE_BYTES < 256L * 1024L * 1024L)
        assertTrue(ImageLibraryBackupLimits.MAX_TOTAL_IMAGE_BYTES <= 1024L * 1024L * 1024L)
        assertTrue(ImageLibraryBackupLimits.MAX_MANIFEST_BYTES <= 8 * 1024 * 1024)
        assertTrue(ImageLibraryBackupLimits.MAX_BACKUP_ITEMS in 128..512)
        assertTrue(ImageLibraryBackupLimits.MAX_PROMPT_CHARS in 4_096..16_384)
    }

    @Test
    fun manifestRejectsImagesAndPromptsOutsideMobileSafetyContract() {
        val productBoundary = validItem()
            .put("width", ImageLibraryBackupLimits.MAX_IMAGE_SIDE)
            .put("height", ImageLibraryBackupLimits.MAX_IMAGE_SIDE)
        val oversizedImage = validItem().put("width", ImageLibraryBackupLimits.MAX_IMAGE_SIDE + 1)
        val oversizedPrompt = validItem().put(
            "prompt",
            "x".repeat(ImageLibraryBackupLimits.MAX_PROMPT_CHARS + 1)
        )

        assertEquals(
            1,
            parseImageLibraryBackupManifest(manifestWith(productBoundary)).items.size
        )
        val parsed = parseImageLibraryBackupManifest(
            JSONObject()
                .put("format", "mca-image-library-backup")
                .put("version", 1)
                .put("items", JSONArray().put(oversizedImage).put(oversizedPrompt))
        )

        assertTrue(parsed.items.isEmpty())
        assertEquals(2, parsed.invalidItems)
    }

    @Test
    fun manifestItemCeilingFailsBeforeParsingAnOversizedCollection() {
        val items = JSONArray()
        repeat(ImageLibraryBackupLimits.MAX_BACKUP_ITEMS + 1) { items.put(validItem()) }
        try {
            parseImageLibraryBackupManifest(
                JSONObject()
                    .put("format", "mca-image-library-backup")
                    .put("version", 1)
                    .put("items", items)
            )
            fail("Expected the mobile item ceiling to reject the manifest")
        } catch (_: ImageLibraryBackupFormatException) {
            // Expected.
        }
    }

    @Test
    fun compressedZipBombIsRejectedWhileOrdinaryArchiveRatioIsAccepted() {
        validateImageLibraryBackupCompressionBudget(
            compressedBytes = 1024L * 1024L,
            uncompressedBytes = 4L * 1024L * 1024L
        )
        try {
            validateImageLibraryBackupCompressionBudget(
                compressedBytes = 1024L,
                uncompressedBytes = ImageLibraryBackupLimits.MIN_RATIO_CHECK_BYTES + 1L
            )
            fail("Expected the compression-ratio guard to reject a bomb")
        } catch (_: ImageLibraryBackupFormatException) {
            // Expected.
        }
        try {
            validateImageLibraryBackupCompressionBudget(
                compressedBytes = ImageLibraryBackupLimits.MAX_COMPRESSED_ARCHIVE_BYTES + 1L,
                uncompressedBytes = 0L
            )
            fail("Expected the compressed-input byte cap to reject an oversized archive")
        } catch (_: ImageLibraryBackupFormatException) {
            // Expected.
        }

        val payload = ByteArray((ImageLibraryBackupLimits.MIN_RATIO_CHECK_BYTES + 64 * 1024L).toInt())
        val archive = compressedArchive(payload)
        try {
            ImageLibraryBackupZipInput(ByteArrayInputStream(archive)).use { zip ->
                check(zip.nextEntry()?.name == "payload.bin")
                val buffer = ByteArray(64 * 1024)
                while (zip.read(buffer) >= 0) {
                    // Consume until the bounded reader rejects the expansion ratio.
                }
            }
            fail("Expected bounded ZIP input to reject a compressed bomb")
        } catch (_: ImageLibraryBackupFormatException) {
            // Expected.
        }
    }

    @Test
    fun importSpaceBudgetRequiresHeadroomBeforeStaging() {
        val expected = 128L * 1024L * 1024L
        val required = requiredImageLibraryImportSpaceBytes(expected)

        assertEquals(expected + ImageLibraryBackupLimits.MIN_FREE_SPACE_RESERVE_BYTES, required)
        validateImageLibraryImportSpaceBudget(expected, required)
        try {
            validateImageLibraryImportSpaceBudget(expected, required - 1L)
            fail("Expected insufficient free space to fail before staging")
        } catch (_: java.io.IOException) {
            // Expected.
        }
    }

    @Test
    fun orphanSelectionDeletesOnlyUnreferencedGeneratedRestoreLeaves() {
        val root = Files.createTempDirectory("image-backup-orphans").toFile()
        try {
            val referenced = File(root, "restored-$id.png").apply { writeBytes(byteArrayOf(1)) }
            val orphan = File(
                root,
                "restored-223e4567-e89b-12d3-a456-426614174000-deadbeef.webp"
            ).apply { writeBytes(byteArrayOf(2)) }
            File(root, "local-image-unrelated.png").writeBytes(byteArrayOf(3))

            val selected = unreferencedRestoredImageFiles(
                root = root,
                referencedCanonicalPaths = setOf(referenced.canonicalPath)
            )

            assertEquals(listOf(orphan.canonicalPath), selected.map { it.canonicalPath })
        } finally {
            root.deleteRecursively()
        }
    }

    private fun manifestWith(item: JSONObject): JSONObject = JSONObject()
        .put("format", "mca-image-library-backup")
        .put("version", 1)
        .put("items", JSONArray().put(item))

    private fun validItem(): JSONObject = JSONObject()
        .put("id", id)
        .put("entryPath", "images/$id.png")
        .put("sha256", "0".repeat(64))
        .put("byteSize", 128)
        .put("name", "restored.png")
        .put("source", "generated:native")
        .put("prompt", "a restored prompt")
        .put("createdAt", 1_700_000_000_000L)
        .put("width", 512)
        .put("height", 512)
        .put("favorite", true)

    private fun compressedArchive(payload: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            zip.putNextEntry(ZipEntry("payload.bin"))
            zip.write(payload)
            zip.closeEntry()
        }
        return output.toByteArray()
    }
}
