package com.muyuchat.mca

import com.muyuchat.mca.presentation.ImageAssetDigestCacheState
import com.muyuchat.mca.presentation.ImageAssetDigestDecision
import com.muyuchat.mca.presentation.ImageAssetDigestPolicy
import com.muyuchat.mca.presentation.ImageAssetFileState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards for the digest-reuse decision that was extracted out of `observedLocalImageFileSha256`.
 *
 * The tests pin the *condition*, not an implementation: every clause of
 * `!force && changeKey.isNotEmpty() && bytes > 4 MiB && cached != null && size/mtime/changeKey equal`
 * gets its own assertion, including the two sides of the strict `> 4 MiB` boundary. A future edit that
 * relaxes the floor, drops a comparison, or lets `force` reuse is meant to fail here.
 *
 * One deliberate asymmetry is covered too: the extracted call site checks cancellation *before* the
 * reuse condition, so a cancelled call must never reach `Reuse`.
 */
class ImageAssetDigestPolicyTest {

    private companion object {
        /** Same literal as the extracted condition; kept expanded so the arithmetic is auditable. */
        const val FOUR_MB = 4L * 1_024L * 1_024L
        const val SHA = "6b3f2c0d9e4a17b58c6d0e2f3a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d"
        const val CHANGE_KEY = "ctime:1700000000,ino:4242"
    }

    private fun observedFile(
        sizeBytes: Long = FOUR_MB + 1L,
        modifiedAt: Long = 1_700_000_000_000L,
        changeKey: String = CHANGE_KEY
    ) = ImageAssetFileState(sizeBytes, modifiedAt, changeKey)

    private fun cachedEntry(
        sha256: String = SHA,
        sizeBytes: Long = FOUR_MB + 1L,
        modifiedAt: Long = 1_700_000_000_000L,
        changeKey: String = CHANGE_KEY
    ) = ImageAssetDigestCacheState(sha256, sizeBytes, modifiedAt, changeKey)

    private fun decide(
        file: ImageAssetFileState = observedFile(),
        cached: ImageAssetDigestCacheState? = cachedEntry(),
        force: Boolean = false,
        isCancelled: Boolean = false
    ) = ImageAssetDigestPolicy.decide(file, cached, force, isCancelled)

    // ---- the reuse branch ------------------------------------------------------------------

    @Test
    fun `a large asset whose cached size mtime and change key all match reuses the cached digest`() {
        val decision = decide()
        assertEquals(ImageAssetDigestDecision.Reuse(SHA), decision)
        assertTrue("the reuse branch must be reachable", decision is ImageAssetDigestDecision.Reuse)
    }

    @Test
    fun `the reuse floor is the four megabyte literal of the extracted condition`() {
        assertEquals(4L * 1_024L * 1_024L, ImageAssetDigestPolicy.REUSE_MIN_SIZE_BYTES)
        assertEquals("4 MiB is 4194304 bytes", 4_194_304L, ImageAssetDigestPolicy.REUSE_MIN_SIZE_BYTES)
    }

    // ---- the strict > 4 MiB boundary -------------------------------------------------------

    @Test
    fun `a file of exactly four megabytes is recomputed because the threshold is strict`() {
        assertEquals(
            "bytes > 4 MiB must reject exactly 4 MiB",
            ImageAssetDigestDecision.Recompute,
            decide(file = observedFile(sizeBytes = FOUR_MB), cached = cachedEntry(sizeBytes = FOUR_MB))
        )
    }

    @Test
    fun `one byte above four megabytes is the smallest reusable size`() {
        assertEquals(
            ImageAssetDigestDecision.Reuse(SHA),
            decide(file = observedFile(sizeBytes = FOUR_MB + 1L), cached = cachedEntry(sizeBytes = FOUR_MB + 1L))
        )
    }

    @Test
    fun `a file at or below four megabytes is never reused even when every cached field matches`() {
        for (size in listOf(FOUR_MB - 1L, 1_024L, 0L, -1L)) {
            assertEquals(
                "size=$size must be recomputed",
                ImageAssetDigestDecision.Recompute,
                decide(file = observedFile(sizeBytes = size), cached = cachedEntry(sizeBytes = size))
            )
        }
    }

    // ---- each clause of the condition ------------------------------------------------------

    @Test
    fun `force recomputes even when every cached field matches`() {
        assertEquals(
            "!force must gate the whole reuse branch",
            ImageAssetDigestDecision.Recompute,
            decide(force = true)
        )
    }

    @Test
    fun `an empty change key always recomputes`() {
        assertEquals(
            "changeKey.isNotEmpty() must gate the whole reuse branch",
            ImageAssetDigestDecision.Recompute,
            decide(file = observedFile(changeKey = ""), cached = cachedEntry(changeKey = ""))
        )
        assertEquals(
            "an empty observed key cannot match a non-empty cached key either",
            ImageAssetDigestDecision.Recompute,
            decide(file = observedFile(changeKey = ""), cached = cachedEntry())
        )
    }

    @Test
    fun `a missing cache entry recomputes`() {
        assertEquals(
            "cached != null must gate the whole reuse branch",
            ImageAssetDigestDecision.Recompute,
            decide(cached = null)
        )
        assertEquals(
            "a missing cache entry stays a recompute on a small file too",
            ImageAssetDigestDecision.Recompute,
            decide(file = observedFile(sizeBytes = 2_048L), cached = null)
        )
    }

    @Test
    fun `a cached entry whose size differs recomputes`() {
        assertEquals(
            "cached.sizeBytes == bytes must be compared",
            ImageAssetDigestDecision.Recompute,
            decide(file = observedFile(sizeBytes = FOUR_MB + 1L), cached = cachedEntry(sizeBytes = FOUR_MB + 2L))
        )
        assertEquals(
            "a cached 4 MiB entry cannot describe a 4 MiB + 1 file",
            ImageAssetDigestDecision.Recompute,
            decide(file = observedFile(sizeBytes = FOUR_MB + 1L), cached = cachedEntry(sizeBytes = FOUR_MB))
        )
    }

    @Test
    fun `a cached entry whose modification time differs recomputes`() {
        assertEquals(
            "cached.modifiedAt == modified must be compared",
            ImageAssetDigestDecision.Recompute,
            decide(cached = cachedEntry(modifiedAt = 1_700_000_000_001L))
        )
    }

    @Test
    fun `a cached entry whose change key differs recomputes`() {
        assertEquals(
            "cached.changeKey == changeKey must be compared",
            ImageAssetDigestDecision.Recompute,
            decide(cached = cachedEntry(changeKey = "ctime:1700000001,ino:4242"))
        )
        assertEquals(
            "an empty cached key cannot match a non-empty observed key",
            ImageAssetDigestDecision.Recompute,
            decide(cached = cachedEntry(changeKey = ""))
        )
    }

    @Test
    fun `each cached field is compared on its own, not only collectively`() {
        val file = observedFile()
        assertEquals(ImageAssetDigestDecision.Reuse(SHA), decide(file = file, cached = cachedEntry()))
        assertEquals(
            ImageAssetDigestDecision.Recompute,
            decide(file = file, cached = cachedEntry(sizeBytes = file.sizeBytes + 1L))
        )
        assertEquals(
            ImageAssetDigestDecision.Recompute,
            decide(file = file, cached = cachedEntry(modifiedAt = file.modifiedAt + 1L))
        )
        assertEquals(
            ImageAssetDigestDecision.Recompute,
            decide(file = file, cached = cachedEntry(changeKey = file.changeKey + " "))
        )
    }

    // ---- cancellation ----------------------------------------------------------------------

    @Test
    fun `cancellation is rejected before the cache is considered`() {
        assertEquals(
            "checkCancelled() runs before the reuse condition, so a cancelled call may not reuse",
            ImageAssetDigestDecision.Reject(ImageAssetDigestPolicy.REASON_CANCELLED),
            decide(isCancelled = true)
        )
    }

    @Test
    fun `cancellation outranks force and every other input`() {
        assertEquals(
            ImageAssetDigestDecision.Reject(ImageAssetDigestPolicy.REASON_CANCELLED),
            decide(force = true, isCancelled = true)
        )
        assertEquals(
            ImageAssetDigestDecision.Reject(ImageAssetDigestPolicy.REASON_CANCELLED),
            decide(file = observedFile(changeKey = ""), cached = null, isCancelled = true)
        )
    }

    @Test
    fun `the rejection carries the documented reason constant`() {
        val decision = decide(isCancelled = true)
        assertTrue("cancellation must be a Reject", decision is ImageAssetDigestDecision.Reject)
        assertEquals(
            ImageAssetDigestPolicy.REASON_CANCELLED,
            (decision as ImageAssetDigestDecision.Reject).reason
        )
    }

    // ---- no extra conditions were added ----------------------------------------------------

    @Test
    fun `the cached digest is returned verbatim without extra validation`() {
        assertEquals(
            "the extracted condition returns cached.sha256 unexamined; no new guard may be added",
            ImageAssetDigestDecision.Reuse(""),
            decide(cached = cachedEntry(sha256 = ""))
        )
        assertEquals(
            ImageAssetDigestDecision.Reuse("not-a-sha"),
            decide(cached = cachedEntry(sha256 = "not-a-sha"))
        )
    }

    // ---- the field-level entry point -------------------------------------------------------

    private data class Scenario(
        val name: String,
        val file: ImageAssetFileState,
        val cached: ImageAssetDigestCacheState?,
        val force: Boolean,
        val isCancelled: Boolean
    )

    private fun scenarios() = listOf(
        Scenario("matching", observedFile(), cachedEntry(), force = false, isCancelled = false),
        Scenario("exactly four megabytes", observedFile(sizeBytes = FOUR_MB), cachedEntry(sizeBytes = FOUR_MB), false, false),
        Scenario("four megabytes plus one", observedFile(sizeBytes = FOUR_MB + 1L), cachedEntry(sizeBytes = FOUR_MB + 1L), false, false),
        Scenario("small file", observedFile(sizeBytes = 512L), cachedEntry(sizeBytes = 512L), false, false),
        Scenario("empty change key", observedFile(changeKey = ""), cachedEntry(changeKey = ""), false, false),
        Scenario("size drift", observedFile(), cachedEntry(sizeBytes = FOUR_MB + 9L), false, false),
        Scenario("mtime drift", observedFile(), cachedEntry(modifiedAt = 7L), false, false),
        Scenario("change key drift", observedFile(), cachedEntry(changeKey = "ctime:1,ino:2"), false, false),
        Scenario("missing cache", observedFile(), null, false, false),
        Scenario("forced", observedFile(), cachedEntry(), true, false),
        Scenario("cancelled", observedFile(), cachedEntry(), false, true),
        Scenario("cancelled and forced", observedFile(), cachedEntry(), true, true)
    )

    @Test
    fun `the field level entry point makes exactly the same decision as the typed one`() {
        for (scenario in scenarios()) {
            val typed = ImageAssetDigestPolicy.decide(
                scenario.file, scenario.cached, scenario.force, scenario.isCancelled
            )
            val field = ImageAssetDigestPolicy.decide(
                sizeBytes = scenario.file.sizeBytes,
                modifiedAt = scenario.file.modifiedAt,
                changeKey = scenario.file.changeKey,
                cachedSha256 = scenario.cached?.sha256,
                cachedSizeBytes = scenario.cached?.sizeBytes ?: 0L,
                cachedModifiedAt = scenario.cached?.modifiedAt ?: 0L,
                cachedChangeKey = scenario.cached?.changeKey.orEmpty(),
                force = scenario.force,
                isCancelled = scenario.isCancelled
            )
            assertEquals("entry points disagree for scenario: ${scenario.name}", typed, field)
        }
    }

    @Test
    fun `the scenario table exercises all three outcomes`() {
        val outcomes = scenarios().map {
            ImageAssetDigestPolicy.decide(it.file, it.cached, it.force, it.isCancelled)
        }
        assertTrue("no scenario reuses", outcomes.any { it is ImageAssetDigestDecision.Reuse })
        assertTrue("no scenario recomputes", outcomes.any { it == ImageAssetDigestDecision.Recompute })
        assertTrue("no scenario is rejected", outcomes.any { it is ImageAssetDigestDecision.Reject })
    }
}
