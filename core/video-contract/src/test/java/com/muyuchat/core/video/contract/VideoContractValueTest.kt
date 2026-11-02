package com.muyuchat.core.video.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Identity format rules and snapshot invariants of the shared contract. */
class VideoContractValueTest {

    @Test
    fun `well-formed ids are accepted`() {
        for (good in listOf("run-1", "wf-a1b2c3", "attempt-9f", "x", "a-b-c")) {
            assertTrue(good, isWellFormedId(good))
        }
    }

    @Test
    fun `malformed ids are rejected`() {
        for (bad in listOf("", "-leading", "trailing-", "Upper", "with space", "dot.id", "斜杠/")) {
            assertFalse(bad, isWellFormedId(bad))
        }
    }

    @Test
    fun `id value classes enforce their format`() {
        assertTrue(runCatching { VideoRunId("run ok") }.isFailure)
        assertTrue(runCatching { VideoRevisionId("") }.isFailure)
        assertEquals("run-1", VideoRunId("run-1").value)
    }

    @Test
    fun `resolved params enforce geometry frame and sampling invariants`() {
        val base = resolvedParams()
        assertTrue(runCatching { base }.isSuccess)

        assertTrue(runCatching { base.copy(width = 830) }.isFailure)
        assertTrue(runCatching { base.copy(height = 478) }.isFailure)
        assertTrue(runCatching { base.copy(frames = 6) }.isFailure)
        assertTrue(runCatching { base.copy(frames = 5) }.isSuccess)
        assertTrue(runCatching { base.copy(fps = 0) }.isFailure)
        assertTrue(runCatching { base.copy(steps = 0) }.isFailure)
    }

    private fun resolvedParams(): VideoResolvedParams = VideoResolvedParams(
        width = 832L,
        height = 480L,
        frames = 81L,
        fps = 16L,
        steps = 20L,
        sampler = "euler",
        guidance = VideoNodeValue.DecimalValue(50, 1),
        flowShift = VideoNodeValue.DecimalValue(30, 1),
        seed = 42L,
        promptSha256 = "0".repeat(64),
        negativePromptSha256 = null,
        imageRef = null,
    )
}
