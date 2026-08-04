package com.muyuchat.mca

import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnImageStageJournalTest {
    @Test
    fun `sidecar preserves detailed trace without a JNI poll`() {
        val file = File.createTempFile("qnn-stage", ".json")
        try {
            file.writeText(
                """{"active":true,"phase":"context_release","message":"release","step":1,"steps":1,"elapsedMs":42,"stageTrace":["unet_graph_retrieve_after","vae_context_release_before","runtime_unload_before"]}"""
            )
            val progress = QnnImageStageJournal.readOrPrevious(
                file = file,
                previous = null,
                threads = 4,
                width = 1024,
                height = 1024
            )

            assertNotNull(progress)
            assertEquals("context_release", progress?.phase)
            assertEquals(1024, progress?.width)
            assertEquals(
                listOf(
                    "unet_graph_retrieve_after",
                    "vae_context_release_before",
                    "runtime_unload_before"
                ),
                progress?.stageTrace
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `partial sidecar never erases the last observable trace`() {
        val previous = LocalImageProgress(
            phase = "context_release",
            message = "release",
            step = 1,
            steps = 1,
            elapsedMs = 42,
            secondsPerStep = 0.0,
            threads = 4,
            width = 1024,
            height = 1024,
            cancelRequested = false,
            stageTrace = listOf("vae_context_release_before")
        )
        val file = File.createTempFile("qnn-stage-partial", ".json")
        try {
            file.writeText("{\"active\":true")
            val progress = QnnImageStageJournal.readOrPrevious(
                file,
                previous,
                threads = 4,
                width = 1024,
                height = 1024
            )
            assertEquals(previous, progress)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `shared preview accepts only monotonic request scoped immutable revisions`() {
        val journal = File.createTempFile("qnn-preview", ".qnn-stage.json")
        val directory = File(journal.canonicalPath + ".previews")
        val preview = File(directory, "preview-1.png")
        try {
            assertEquals(true, directory.mkdir())
            preview.writeBytes(validPngHeader(512, 512))
            journal.writeText(
                """{"active":true,"phase":"sampling","step":4,"steps":20,"stageTrace":["preview_vae_graph_execute"],"previewPath":${JSONObject.quote(preview.canonicalPath)},"previewMimeType":"image/png","previewMode":"vae","previewStep":4,"previewRevision":1,"previewWidth":512,"previewHeight":512,"previewFrameCount":1,"previewNoisy":false,"previewVaeExecutionAttemptCount":1,"previewVaeExecutionCount":1,"previewVaeExecutionMsTotal":37,"previewPublicationCount":1,"previewLastStep":4,"previewLastRevision":1,"previewFailureCode":""}"""
            )

            val progress = requireNotNull(
                QnnImageStageJournal.readOrPrevious(journal, null, 4, 512, 512)
            )
            assertEquals(preview.canonicalPath, progress.previewPath)
            assertEquals(1L, progress.previewRevision)
            assertEquals(1, progress.previewVaeExecutionAttemptCount)
            assertEquals(1, progress.previewVaeExecutionCount)
            assertEquals(37L, progress.previewVaeExecutionMsTotal)
            assertEquals(1, progress.previewPublicationCount)
            assertEquals("", progress.previewFailureCode)

            preview.writeBytes(validPngHeader(256, 512))
            assertEquals(
                progress,
                QnnImageStageJournal.readOrPrevious(journal, progress, 4, 512, 512)
            )
            preview.writeBytes(validPngHeader(512, 512).also { it[0] = 0 })
            assertEquals(
                progress,
                QnnImageStageJournal.readOrPrevious(journal, progress, 4, 512, 512)
            )
            preview.writeBytes(validPngHeader(512, 512))

            val partial = File(directory, "preview-1.png.part")
            partial.writeBytes(validPngHeader(512, 512))
            journal.writeText(
                """{"active":true,"phase":"sampling","step":4,"steps":20,"stageTrace":["preview_vae_graph_execute"],"previewPath":${JSONObject.quote(partial.canonicalPath)},"previewMimeType":"image/png","previewMode":"vae","previewStep":4,"previewRevision":1,"previewWidth":512,"previewHeight":512,"previewFrameCount":1,"previewNoisy":false,"previewVaeExecutionAttemptCount":1,"previewVaeExecutionCount":1,"previewVaeExecutionMsTotal":37,"previewPublicationCount":1,"previewLastStep":4,"previewLastRevision":1,"previewFailureCode":""}"""
            )
            assertEquals(
                progress,
                QnnImageStageJournal.readOrPrevious(journal, progress, 4, 512, 512)
            )
            partial.delete()

            journal.writeText(
                """{"active":true,"phase":"sampling","step":4,"steps":20,"stageTrace":["preview_vae_graph_execute"],"previewPath":${JSONObject.quote(preview.canonicalPath)},"previewMimeType":"image/png","previewMode":"vae","previewStep":4,"previewRevision":1,"previewWidth":512,"previewHeight":512,"previewFrameCount":1,"previewNoisy":false,"previewVaeExecutionAttemptCount":1,"previewVaeExecutionCount":1,"previewVaeExecutionMsTotal":37,"previewPublicationCount":1,"previewLastStep":4,"previewLastRevision":1,"previewFailureCode":""}"""
            )
            RandomAccessFile(preview, "rw").use { it.setLength(32L * 1024L * 1024L + 1L) }
            assertEquals(
                progress,
                QnnImageStageJournal.readOrPrevious(journal, progress, 4, 512, 512)
            )
            preview.writeBytes(validPngHeader(512, 512))

            journal.writeText(
                """{"active":true,"phase":"sampling","step":2,"steps":20,"stageTrace":["preview_vae_graph_execute"],"previewPath":${JSONObject.quote(preview.canonicalPath)},"previewMimeType":"image/png","previewMode":"vae","previewStep":2,"previewRevision":0,"previewWidth":512,"previewHeight":512,"previewFrameCount":0,"previewNoisy":false,"previewVaeExecutionAttemptCount":1,"previewVaeExecutionCount":1,"previewVaeExecutionMsTotal":37,"previewPublicationCount":0,"previewLastStep":0,"previewLastRevision":0,"previewFailureCode":""}"""
            )
            assertEquals(
                progress,
                QnnImageStageJournal.readOrPrevious(journal, progress, 4, 512, 512)
            )
        } finally {
            preview.delete()
            directory.delete()
            journal.delete()
        }
    }

    @Test
    fun `split projection sidecar is ignored and legacy artifacts are cleaned up`() {
        val root = Files.createTempDirectory("qnn-projection-preview").toFile()
        val request = "qnn-htp-1-12345678-1234-1234-1234-123456789abc"
        val journal = File(root, "$request.unet-stage.json")
        val directory = File(journal.path + ".previews")
        val preview = File(directory, "preview-1.png")
        val sidecar = QnnImageStageJournal.sdxlProjectionPreviewJournalFile(journal)
        try {
            assertTrue(directory.mkdir())
            preview.writeBytes(validPngHeader(128, 128))
            journal.writeText(
                """{"active":true,"phase":"sampling","step":4,"steps":20,"stageTrace":["unet_graph_execute"]}"""
            )
            sidecar.writeText(projectionJournal(preview, width = 128, height = 128).toString())

            val progress = requireNotNull(
                QnnImageStageJournal.readOrPrevious(journal, null, 0, 1024, 1024)
            )
            assertEquals("sampling", progress.phase)
            assertEquals("", progress.previewPath)
            assertEquals("", progress.previewMode)
            assertEquals(0L, progress.previewRevision)
            assertEquals(0, progress.previewPublicationCount)
            assertNull(
                QnnImageStageJournal.readSdxlProjectionPreviewAuditOrNull(
                    journal,
                    width = 1024,
                    height = 1024
                )
            )

            sidecar.writeText("{not-a-supported-projection-sidecar")
            assertEquals(
                progress,
                QnnImageStageJournal.readOrPrevious(journal, progress, 0, 1024, 1024)
            )
            assertEquals(
                progress,
                QnnImageStageJournal.readOrPrevious(sidecar, progress, 0, 1024, 1024)
            )
            assertNull(
                QnnImageStageJournal.readSdxlProjectionPreviewAuditOrNull(
                    journal,
                    width = 1024,
                    height = 1024
                )
            )

            File(sidecar.path + ".part").writeText("partial")
            assertTrue(QnnImageStageJournal.cleanupSdxlProjectionPreview(journal))
            assertFalse(directory.exists())
            assertFalse(sidecar.exists())
            assertFalse(File(sidecar.path + ".part").exists())

            val guardedJournal = File(root, "qnn-htp-2-12345678-1234-1234-1234-123456789abc.unet-stage.json")
            val guardedDirectory = File(guardedJournal.path + ".previews")
            assertTrue(guardedDirectory.mkdir())
            File(guardedDirectory, "keep.txt").writeText("keep")
            assertFalse(QnnImageStageJournal.cleanupSdxlProjectionPreview(guardedJournal))
            assertTrue(guardedDirectory.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `preview path traversal is rejected and terminal cleanup retains only audit`() {
        val journal = File.createTempFile("qnn-preview-safe", ".qnn-stage.json")
        val escaped = File.createTempFile("escaped-preview", ".png")
        try {
            journal.writeText(
                """{"active":true,"phase":"sampling","step":4,"steps":20,"stageTrace":["preview_vae_graph_execute"],"previewPath":${JSONObject.quote(escaped.canonicalPath)},"previewMimeType":"image/png","previewMode":"vae","previewStep":4,"previewRevision":1,"previewWidth":512,"previewHeight":512,"previewFrameCount":1,"previewNoisy":false,"previewVaeExecutionAttemptCount":1,"previewVaeExecutionCount":1,"previewVaeExecutionMsTotal":10,"previewPublicationCount":1,"previewLastStep":4,"previewLastRevision":1,"previewFailureCode":""}"""
            )
            assertNull(QnnImageStageJournal.readOrPrevious(journal, null, 4, 512, 512))

            journal.writeText(
                """{"active":false,"phase":"sampling","step":20,"steps":20,"stageTrace":["preview_vae_graph_execute","vae_graph_execute"],"previewPath":"","previewMimeType":"","previewMode":"vae","previewStep":0,"previewRevision":0,"previewWidth":0,"previewHeight":0,"previewFrameCount":1,"previewNoisy":false,"previewVaeExecutionAttemptCount":2,"previewVaeExecutionCount":1,"previewVaeExecutionMsTotal":10,"previewPublicationCount":1,"previewLastStep":4,"previewLastRevision":1,"previewFailureCode":"PREVIEW_VAE_EXECUTE_FAILED"}"""
            )
            val terminal = requireNotNull(
                QnnImageStageJournal.readOrPrevious(journal, null, 4, 512, 512)
            )
            assertEquals("", terminal.previewPath)
            assertEquals(0L, terminal.previewRevision)
            assertEquals(1L, terminal.previewLastRevision)
            assertEquals("PREVIEW_VAE_EXECUTE_FAILED", terminal.previewFailureCode)
        } finally {
            escaped.delete()
            journal.delete()
        }
    }

    @Test
    fun `oversized stage journal falls back without reading payload`() {
        val file = File.createTempFile("qnn-stage-oversized", ".json")
        val previous = LocalImageProgress(
            phase = "sampling",
            message = "previous",
            step = 1,
            steps = 20,
            elapsedMs = 1L,
            secondsPerStep = 0.0,
            threads = 4,
            width = 512,
            height = 512,
            cancelRequested = false
        )
        try {
            RandomAccessFile(file, "rw").use { it.setLength(256L * 1024L + 1L) }
            assertEquals(previous, QnnImageStageJournal.readOrPrevious(file, previous, 4, 512, 512))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `bounded journal stream rejects max plus one independently of file length precheck`() {
        assertEquals(
            "{}",
            QnnImageStageJournal.readBoundedUtf8(ByteArrayInputStream("{}".toByteArray()))
        )
        assertNull(
            QnnImageStageJournal.readBoundedUtf8(
                ByteArrayInputStream(ByteArray(256 * 1024 + 1))
            )
        )
    }

    @Test
    fun `request cleanup and stale sweep stay bounded to direct named preview directories`() {
        val root = Files.createTempDirectory("qnn-preview-cleanup").toFile()
        val uuid = "12345678-1234-1234-1234-123456789abc"
        try {
            val journal = File(root, "qnn-htp-1-$uuid.qnn-stage.json")
            val requestDirectory = File(root, journal.name + ".previews").apply { mkdir() }
            listOf(
                "preview-1.png",
                "preview-2.png.tmp",
                "preview-3.png.part"
            ).forEach { name -> File(requestDirectory, name).writeBytes(validPngHeader(16, 16)) }
            assertTrue(QnnImageStageJournal.cleanupRequestPreviewDirectory(journal))
            assertFalse(requestDirectory.exists())

            val guardedDirectory = File(root, journal.name + ".previews").apply { mkdir() }
            val unknown = File(guardedDirectory, "keep.txt").apply { writeText("keep") }
            assertFalse(QnnImageStageJournal.cleanupRequestPreviewDirectory(journal))
            assertTrue(unknown.isFile)

            val staleJournal = File(root, "qnn-htp-2-$uuid.qnn-stage.json")
            val staleDirectory = File(root, staleJournal.name + ".previews").apply { mkdir() }
            File(staleDirectory, "preview-1.png").writeBytes(validPngHeader(16, 16))
            val freshJournal = File(root, "qnn-htp-3-$uuid.qnn-stage.json")
            val freshDirectory = File(root, freshJournal.name + ".previews").apply { mkdir() }
            File(freshDirectory, "preview-1.png").writeBytes(validPngHeader(16, 16))
            val unknownDirectory = File(root, "arbitrary.qnn-stage.json.previews").apply { mkdir() }
            File(unknownDirectory, "preview-1.png").writeBytes(validPngHeader(16, 16))
            val now = 3L * 24L * 60L * 60L * 1_000L
            staleDirectory.setLastModified(1L)
            unknownDirectory.setLastModified(1L)
            freshDirectory.setLastModified(now)

            assertEquals(1, QnnImageStageJournal.sweepStalePreviewDirectories(root, now))
            assertFalse(staleDirectory.exists())
            assertTrue(freshDirectory.isDirectory)
            assertTrue(unknownDirectory.isDirectory)
            assertTrue(guardedDirectory.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun validPngHeader(width: Int, height: Int): ByteArray = ByteArray(29).apply {
        byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        ).copyInto(this, destinationOffset = 0)
        writePngU32(8, 13)
        byteArrayOf(0x49, 0x48, 0x44, 0x52).copyInto(this, destinationOffset = 12)
        writePngU32(16, width)
        writePngU32(20, height)
        this[24] = 8.toByte()
        this[25] = 2.toByte()
        this[26] = 0.toByte()
        this[27] = 0.toByte()
        this[28] = 0.toByte()
    }

    private fun projectionJournal(
        preview: File,
        width: Int = 128,
        height: Int = 128
    ): JSONObject = JSONObject()
        .put("schema", "mca.sdxl.projection-preview.v1")
        .put("committed", true)
        .put("previewRequested", true)
        .put("previewMode", "projection")
        .put("previewInterval", 4)
        .put("previewPath", preview.canonicalPath)
        .put("previewMimeType", "image/png")
        .put("previewStep", 4)
        .put("previewRevision", 1)
        .put("previewWidth", width)
        .put("previewHeight", height)
        .put("previewFrameCount", 1)
        .put("previewNoisy", false)
        .put("previewVaeExecutionAttemptCount", 0)
        .put("previewVaeExecutionCount", 0)
        .put("previewVaeExecutionMsTotal", 0)
        .put("previewPublicationCount", 1)
        .put("previewLastStep", 4)
        .put("previewLastRevision", 1)
        .put("previewFailureCode", "")
        .put("projectionPreviewAttemptCount", 1)
        .put("projectionPreviewPublicationCount", 1)
        .put("projectionPreviewProjectionMsTotal", 2)
        .put("projectionPreviewLastStep", 4)
        .put("projectionPreviewLastRevision", 1)
        .put("projectionPreviewFailureCode", "")
        .put("previewDegraded", false)
        .put("steps", 20)

    private fun ByteArray.writePngU32(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }
}
