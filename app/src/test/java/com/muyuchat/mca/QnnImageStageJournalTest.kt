package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
