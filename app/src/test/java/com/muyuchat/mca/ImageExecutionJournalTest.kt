package com.muyuchat.mca

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImageExecutionJournalTest {
    @Test
    fun `journal atomically round trips lifecycle and native evidence`() = withTempDirectory { root ->
        var now = 1000L
        val store = ImageExecutionJournalStore(File(root, "journal")) { ++now }
        val initial = entry(
            requestId = "request-1",
            createdAtMs = now,
            steps = 20,
            requested = JSONObject()
                .put("seed", 42)
                .put("negativePromptSpecified", true)
                .put("negativePrompt", "")
                .toString()
        )

        store.create(initial)
        val sampling = store.update(
            initial.copy(
                phase = ImageExecutionPhase.SAMPLING,
                step = 3,
                nativeStageMask = 0b111,
                nativeGenerationSequence = 9L,
                workerPid = 4321,
                updatedAtMs = ++now
            )
        )
        val decoding = store.update(
            sampling.copy(
                phase = ImageExecutionPhase.DECODING,
                step = 20,
                nativeStageMask = 0b1111,
                updatedAtMs = ++now
            )
        )
        val publishing = store.update(
            decoding.copy(
                phase = ImageExecutionPhase.PUBLISHING,
                updatedAtMs = ++now
            )
        )
        val completed = store.markTerminal(
            requestId = publishing.requestId,
            phase = ImageExecutionPhase.COMPLETED
        )
        val restored = store.read(initial.requestId)

        assertNotNull(restored)
        assertEquals(ImageExecutionPhase.COMPLETED, restored?.phase)
        assertEquals(20, restored?.step)
        assertEquals(0b1111L, restored?.nativeStageMask)
        assertEquals(9L, restored?.nativeGenerationSequence)
        assertEquals(4321, restored?.workerPid)
        assertTrue(JSONObject(restored?.requestedSummaryJson).getBoolean("negativePromptSpecified"))
        assertEquals("", JSONObject(restored?.requestedSummaryJson).getString("negativePrompt"))
        assertTrue(completed.phase.terminal)
        assertTrue(File(root, "journal").listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `cancellation cleans only allowlisted transient files and becomes terminal`() = withTempDirectory { root ->
        var now = 2000L
        val journalRoot = File(root, "journal")
        val cleanupRoot = File(root, "transient").apply { mkdirs() }
        val outsideRoot = File(root, "outside").apply { mkdirs() }
        val latent = File(cleanupRoot, "request.latent.tmp").apply { writeText("latent") }
        val outside = File(outsideRoot, "must-remain.tmp").apply { writeText("outside") }
        val store = ImageExecutionJournalStore(journalRoot) { ++now }
        store.create(
            entry(
                requestId = "cancel-me",
                createdAtMs = now,
                latentTempPath = latent.absolutePath,
                outputTempPath = outside.absolutePath
            )
        )

        val result = store.finishCancelled("cancel-me", cleanupRoots = listOf(cleanupRoot))

        assertEquals(ImageExecutionPhase.CANCELLED, result.entry.phase)
        assertTrue(result.entry.cancellationRequested)
        assertEquals("CANCELLED", result.entry.errorCode)
        assertFalse(latent.exists())
        assertTrue(outside.exists())
        assertTrue(result.cleanup.deletedPaths.contains(latent.canonicalPath))
        assertTrue(result.cleanup.skippedPaths.contains(outside.canonicalPath))
        assertTrue(store.deleteTerminal("cancel-me"))
    }

    @Test
    fun `recovery marks dead workers interrupted and leaves live workers untouched`() = withTempDirectory { root ->
        var now = 3000L
        val journalRoot = File(root, "journal")
        val cleanupRoot = File(root, "transient").apply { mkdirs() }
        val deadOutput = File(cleanupRoot, "dead.tmp.png").apply { writeText("partial") }
        val liveOutput = File(cleanupRoot, "live.tmp.png").apply { writeText("partial") }
        val store = ImageExecutionJournalStore(journalRoot) { ++now }
        val deadInitial = store.create(
            entry(
                requestId = "dead",
                createdAtMs = now,
                steps = 20,
                outputTempPath = deadOutput.absolutePath,
                requested = JSONObject().put("seed", 20260717).toString()
            )
        )
        store.update(
            deadInitial.copy(
                phase = ImageExecutionPhase.SAMPLING,
                step = 7,
                workerPid = 111,
                updatedAtMs = ++now
            )
        )
        val liveInitial = store.create(
            entry(
                requestId = "live",
                createdAtMs = ++now,
                steps = 20,
                outputTempPath = liveOutput.absolutePath
            )
        )
        val liveSampling = store.update(
            liveInitial.copy(
                phase = ImageExecutionPhase.SAMPLING,
                step = 20,
                workerPid = 222,
                updatedAtMs = ++now
            )
        )
        store.update(
            liveSampling.copy(
                phase = ImageExecutionPhase.DECODING,
                updatedAtMs = ++now
            )
        )

        val report = store.recoverInterrupted(cleanupRoots = listOf(cleanupRoot)) { pid ->
            pid == 222
        }

        assertEquals(listOf("dead"), report.interrupted.map { it.requestId })
        assertEquals(listOf("live"), report.stillRunning.map { it.requestId })
        assertEquals(ImageExecutionPhase.INTERRUPTED, store.read("dead")?.phase)
        assertEquals("WORKER_INTERRUPTED", store.read("dead")?.errorCode)
        assertEquals(20260717, JSONObject(store.read("dead")?.requestedSummaryJson).getInt("seed"))
        assertEquals(ImageExecutionPhase.DECODING, store.read("live")?.phase)
        assertFalse(deadOutput.exists())
        assertTrue(liveOutput.exists())
        assertTrue(report.invalidJournalFiles.isEmpty())
    }

    @Test
    fun `terminal and regressive transitions fail closed`() = withTempDirectory { root ->
        var now = 4000L
        val store = ImageExecutionJournalStore(File(root, "journal")) { ++now }
        val initial = entry(requestId = "strict", createdAtMs = now, steps = 4)
        store.create(initial)
        val sampling = store.update(
            initial.copy(
                phase = ImageExecutionPhase.SAMPLING,
                step = 2,
                nativeStageMask = 0b11,
                updatedAtMs = ++now
            )
        )

        assertInvalid {
            store.update(
                sampling.copy(
                    phase = ImageExecutionPhase.CONDITIONING,
                    step = 1,
                    nativeStageMask = 0b1,
                    updatedAtMs = ++now
                )
            )
        }

        val failed = store.markTerminal("strict", ImageExecutionPhase.FAILED, "NATIVE_FAILED", "failed")
        assertEquals(ImageExecutionPhase.FAILED, failed.phase)
        assertInvalid { store.requestCancellation("strict") }
    }

    private fun entry(
        requestId: String,
        createdAtMs: Long,
        phase: ImageExecutionPhase = ImageExecutionPhase.PREPARING,
        step: Int = 0,
        steps: Int = 0,
        workerPid: Int = -1,
        latentTempPath: String = "",
        outputTempPath: String = "",
        requested: String = "{}"
    ): ImageExecutionJournalEntry = ImageExecutionJournalEntry(
        requestId = requestId,
        modelFingerprint = "model-sha256",
        profileFingerprint = "profile-sha256",
        requestedSummaryJson = requested,
        resolvedSummaryJson = JSONObject().put("profileId", "profile.image.v1").toString(),
        phase = phase,
        step = step,
        steps = steps,
        workerPid = workerPid,
        createdAtMs = createdAtMs,
        latentTempPath = latentTempPath,
        outputTempPath = outputTempPath
    )

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid journal operation")
        } catch (_: IllegalArgumentException) {
            // Expected.
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val root = Files.createTempDirectory("image-execution-journal-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
