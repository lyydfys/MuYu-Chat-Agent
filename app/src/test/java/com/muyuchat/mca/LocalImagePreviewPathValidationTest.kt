package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalImagePreviewPathValidationTest {
    @Test
    fun `qnn sequential batch path matches low 32 bit child revision`() = withCacheRoot { root ->
        val previewDirectory = qnnPreviewDirectory(root)
        val childRevision = 3L
        val coordinatedRevision = (1L shl 32) or childRevision
        val source = File(previewDirectory, "preview-$childRevision.png").apply {
            writeBytes(byteArrayOf(1))
        }

        assertEquals(
            source.canonicalFile,
            validatedLocalImagePreviewSource(root, source.path, coordinatedRevision)
        )

        val forgedParentRevision = File(
            previewDirectory,
            "preview-$coordinatedRevision.png"
        ).apply { writeBytes(byteArrayOf(1)) }
        assertRejected(root, forgedParentRevision, coordinatedRevision)
    }

    @Test
    fun `split SDXL projection paths are rejected while preview is disabled`() =
        withCacheRoot { root ->
            val splitRoot = File(root, SDXL_TWO_PHASE_DIRECTORY).apply { mkdirs() }
            val directory = File(
                splitRoot,
                "qnn-htp-123-00000000-0000-4000-8000-000000000000.unet-stage.json.previews"
            ).apply { mkdirs() }
            val source = File(directory, "preview-2.png").apply { writeBytes(byteArrayOf(1)) }

            assertRejected(root, source, 2L)
            val wrongRoot = File(
                File(root, "local_image_outputs").apply { mkdirs() },
                directory.name
            ).apply { mkdirs() }
            assertRejected(
                root,
                File(wrongRoot, "preview-2.png").apply { writeBytes(byteArrayOf(1)) },
                2L
            )
        }

    @Test
    fun `stable two slot preview remains direct child and follows child revision`() =
        withCacheRoot { root ->
            val outputRoot = File(root, "local_image_outputs").apply { mkdirs() }
            val coordinatedRevision = (2L shl 32) or 5L
            val source = File(outputRoot, "sdcpp-123.png.preview-1.png").apply {
                writeBytes(byteArrayOf(1))
            }

            assertEquals(
                source.canonicalFile,
                validatedLocalImagePreviewSource(root, source.path, coordinatedRevision)
            )

            val wrongSlot = File(outputRoot, "sdcpp-123.png.preview-0.png").apply {
                writeBytes(byteArrayOf(1))
            }
            assertRejected(root, wrongSlot, coordinatedRevision)
        }

    @Test
    fun `qnn preview rejects lookalikes and non direct children`() = withCacheRoot { root ->
        val previewDirectory = qnnPreviewDirectory(root)
        val revision = 7L
        val lookalike = File(previewDirectory, "preview-$revision.png.part").apply {
            writeBytes(byteArrayOf(1))
        }
        assertRejected(root, lookalike, revision)

        val nested = File(previewDirectory, "nested").apply { mkdirs() }
        val nestedPreview = File(nested, "preview-$revision.png").apply {
            writeBytes(byteArrayOf(1))
        }
        assertRejected(root, nestedPreview, revision)

        val wrongDirectory = File(
            File(root, "local_image_outputs"),
            "not-a-journal.previews"
        ).apply { mkdirs() }
        val wrongDirectoryPreview = File(wrongDirectory, "preview-$revision.png").apply {
            writeBytes(byteArrayOf(1))
        }
        assertRejected(root, wrongDirectoryPreview, revision)
    }

    private fun qnnPreviewDirectory(root: File): File = File(
        File(root, "local_image_outputs").apply { mkdirs() },
        "qnn-htp-123-00000000-0000-4000-8000-000000000000.qnn-stage.json.previews"
    ).apply { mkdirs() }

    private fun assertRejected(root: File, source: File, revision: Long) {
        assertTrue(
            runCatching {
                validatedLocalImagePreviewSource(root, source.path, revision)
            }.isFailure
        )
    }

    private inline fun withCacheRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("local-image-preview-path").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
