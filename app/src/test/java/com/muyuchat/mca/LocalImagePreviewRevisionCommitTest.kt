package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LocalImagePreviewRevisionCommitTest {
    @Test
    fun `revision two is not overwritten by a late revision one`() {
        val revisionTwo = activeJob().commit(preview(revision = 2L, marker = "two"))

        val afterLateRevisionOne = revisionTwo.commit(
            preview(revision = 1L, marker = "one")
        )

        assertPreview(afterLateRevisionOne, revision = 2L, marker = "two")
    }

    @Test
    fun `duplicate revision cannot replace committed preview fields`() {
        val firstRevisionTwo = activeJob().commit(preview(revision = 2L, marker = "first"))

        val afterDuplicate = firstRevisionTwo.commit(
            preview(revision = 2L, marker = "duplicate")
        )

        assertPreview(afterDuplicate, revision = 2L, marker = "first")
    }

    @Test
    fun `null publication preserves the last committed preview`() {
        val revisionTwo = activeJob().commit(preview(revision = 2L, marker = "two"))

        val afterFailedCopyOrValidation = revisionTwo.commit(preview = null)

        assertPreview(afterFailedCopyOrValidation, revision = 2L, marker = "two")
    }

    @Test
    fun `terminal update clears preview and late callback cannot revive it`() {
        val revisionTwo = activeJob().commit(preview(revision = 2L, marker = "two"))
        val terminal = revisionTwo.withCommittedImageJobUpdate(
            status = ImageGenerationStatusRecord.DONE,
            message = "done",
            preview = preview(revision = 3L, marker = "terminal")
        )

        assertEquals(ImageGenerationStatusRecord.DONE, terminal.status)
        assertPreviewCleared(terminal)

        val afterLateCallback = terminal.withCommittedImageJobUpdate(
            status = ImageGenerationStatusRecord.GENERATING,
            message = "late",
            preview = preview(revision = 4L, marker = "late")
        )

        assertEquals(ImageGenerationStatusRecord.DONE, afterLateCallback.status)
        assertEquals("done", afterLateCallback.message)
        assertPreviewCleared(afterLateCallback)
    }

    @Test
    fun `preview failure is user visible without leaking the internal code`() {
        val base = "本地生图 第 4/20 步 · 12s"
        assertEquals(
            base,
            appendLocalImagePreviewDegradationMessage(base, previewFailureCode = "  ")
        )

        val degraded = appendLocalImagePreviewDegradationMessage(
            message = base,
            previewFailureCode = "PREVIEW_VAE_EXECUTE_FAILED"
        )

        assertEquals("$base · $LOCAL_IMAGE_PREVIEW_DEGRADED_MESSAGE", degraded)
        assertFalse(degraded.contains("PREVIEW_VAE_EXECUTE_FAILED"))
    }

    private fun ImageGenerationJobRecord.commit(
        preview: PublishedLocalImagePreview?
    ): ImageGenerationJobRecord = withCommittedImageJobUpdate(
        status = ImageGenerationStatusRecord.GENERATING,
        message = "generating",
        preview = preview
    )

    private fun activeJob(): ImageGenerationJobRecord = ImageGenerationJobRecord(
        id = "job",
        prompt = "prompt",
        status = ImageGenerationStatusRecord.GENERATING,
        backend = ImageBackend.LOCAL,
        message = "generating"
    )

    private fun preview(revision: Long, marker: String): PublishedLocalImagePreview =
        PublishedLocalImagePreview(
            uriString = "file:///preview-$marker.png",
            mode = "mode-$marker",
            step = marker.length,
            revision = revision,
            width = 100 + marker.length,
            height = 200 + marker.length
        )

    private fun assertPreview(
        job: ImageGenerationJobRecord,
        revision: Long,
        marker: String
    ) {
        assertEquals("file:///preview-$marker.png", job.previewUriString)
        assertEquals("mode-$marker", job.previewMode)
        assertEquals(marker.length, job.previewStep)
        assertEquals(revision, job.previewRevision)
        assertEquals(100 + marker.length, job.previewWidth)
        assertEquals(200 + marker.length, job.previewHeight)
    }

    private fun assertPreviewCleared(job: ImageGenerationJobRecord) {
        assertNull(job.previewUriString)
        assertEquals("", job.previewMode)
        assertEquals(0, job.previewStep)
        assertEquals(0L, job.previewRevision)
        assertEquals(0, job.previewWidth)
        assertEquals(0, job.previewHeight)
    }
}
