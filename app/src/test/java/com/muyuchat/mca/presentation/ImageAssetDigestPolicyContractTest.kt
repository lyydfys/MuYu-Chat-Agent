package com.muyuchat.mca.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural contract for [ImageAssetDigestPolicy.decide]: which observation may reuse a cached
 * digest, which must recompute, and which must do no work at all.
 *
 * The decision is pure, so every case is an input/output pair: the observed file state, the cached
 * entry, `force` / `isCancelled`, and the decision.
 */
class ImageAssetDigestPolicyContractTest {

    @Test
    fun `a matching observation above the floor reuses the cached digest`() {
        val file = state(sizeBytes = FLOOR + 1L, modifiedAt = 100L, changeKey = "key-1")
        val cached = cache(sha256 = "digest-1", file = file)

        assertEquals(
            ImageAssetDigestDecision.Reuse("digest-1"),
            ImageAssetDigestPolicy.decide(file, cached)
        )
    }

    @Test
    fun `the reuse floor is a strict lower bound`() {
        assertEquals(4L * 1_024L * 1_024L, ImageAssetDigestPolicy.REUSE_MIN_SIZE_BYTES)

        val atFloor = state(sizeBytes = FLOOR, modifiedAt = 100L, changeKey = "key-1")
        assertEquals(
            ImageAssetDigestDecision.Recompute,
            ImageAssetDigestPolicy.decide(atFloor, cache(sha256 = "digest-1", file = atFloor))
        )

        val belowFloor = state(sizeBytes = FLOOR - 1L, modifiedAt = 100L, changeKey = "key-1")
        assertEquals(
            ImageAssetDigestDecision.Recompute,
            ImageAssetDigestPolicy.decide(belowFloor, cache(sha256 = "digest-1", file = belowFloor))
        )
    }

    @Test
    fun `an empty change key always recomputes even when every other field matches`() {
        val file = state(sizeBytes = FLOOR + 1L, modifiedAt = 100L, changeKey = "")
        val cached = ImageAssetDigestCacheState(
            sha256 = "digest-1",
            sizeBytes = file.sizeBytes,
            modifiedAt = file.modifiedAt,
            changeKey = ""
        )

        assertEquals(
            ImageAssetDigestDecision.Recompute,
            ImageAssetDigestPolicy.decide(file, cached)
        )
    }

    @Test
    fun `a missing cache entry means recompute rather than a rejection`() {
        val file = state(sizeBytes = FLOOR + 1L, modifiedAt = 100L, changeKey = "key-1")

        assertEquals(
            ImageAssetDigestDecision.Recompute,
            ImageAssetDigestPolicy.decide(file, cached = null)
        )
    }

    @Test
    fun `every mismatching cached field forces a recompute`() {
        val file = state(sizeBytes = FLOOR + 1L, modifiedAt = 100L, changeKey = "key-1")

        assertEquals(
            ImageAssetDigestDecision.Recompute,
            ImageAssetDigestPolicy.decide(file, cache(sha256 = "digest-1", file = file).copy(sizeBytes = file.sizeBytes + 1L))
        )
        assertEquals(
            ImageAssetDigestDecision.Recompute,
            ImageAssetDigestPolicy.decide(file, cache(sha256 = "digest-1", file = file).copy(modifiedAt = file.modifiedAt + 1L))
        )
        assertEquals(
            ImageAssetDigestDecision.Recompute,
            ImageAssetDigestPolicy.decide(file, cache(sha256 = "digest-1", file = file).copy(changeKey = "key-2"))
        )
    }

    @Test
    fun `force wins over a fully matching cache entry`() {
        val file = state(sizeBytes = FLOOR + 1L, modifiedAt = 100L, changeKey = "key-1")
        val cached = cache(sha256 = "digest-1", file = file)

        assertEquals(
            ImageAssetDigestDecision.Recompute,
            ImageAssetDigestPolicy.decide(file, cached, force = true)
        )
        assertEquals(
            ImageAssetDigestDecision.Recompute,
            ImageAssetDigestPolicy.decide(file, cached = null, force = true)
        )
    }

    @Test
    fun `cancellation outranks force and every reuse argument`() {
        val file = state(sizeBytes = FLOOR + 1L, modifiedAt = 100L, changeKey = "key-1")
        val cached = cache(sha256 = "digest-1", file = file)

        val cancelled = ImageAssetDigestPolicy.decide(file, cached, isCancelled = true)
        assertEquals(ImageAssetDigestDecision.Reject(ImageAssetDigestPolicy.REASON_CANCELLED), cancelled)
        assertEquals(
            ImageAssetDigestDecision.Reject(ImageAssetDigestPolicy.REASON_CANCELLED),
            ImageAssetDigestPolicy.decide(file, cached, force = true, isCancelled = true)
        )
        assertTrue(ImageAssetDigestPolicy.REASON_CANCELLED.isNotBlank())
    }

    @Test
    fun `the raw field overload treats a missing digest as no cache entry`() {
        val file = state(sizeBytes = FLOOR + 1L, modifiedAt = 100L, changeKey = "key-1")

        assertEquals(
            ImageAssetDigestDecision.Recompute,
            ImageAssetDigestPolicy.decide(
                sizeBytes = file.sizeBytes,
                modifiedAt = file.modifiedAt,
                changeKey = file.changeKey,
                cachedSha256 = null,
                // These are "absent" and must not be consulted while no digest was cached.
                cachedSizeBytes = file.sizeBytes,
                cachedModifiedAt = file.modifiedAt,
                cachedChangeKey = file.changeKey
            )
        )
        assertEquals(
            ImageAssetDigestDecision.Reuse("digest-1"),
            ImageAssetDigestPolicy.decide(
                sizeBytes = file.sizeBytes,
                modifiedAt = file.modifiedAt,
                changeKey = file.changeKey,
                cachedSha256 = "digest-1",
                cachedSizeBytes = file.sizeBytes,
                cachedModifiedAt = file.modifiedAt,
                cachedChangeKey = file.changeKey
            )
        )
    }

    @Test
    fun `the two outcomes carry the reason and the digest a caller needs`() {
        val file = state(sizeBytes = FLOOR + 1L, modifiedAt = 100L, changeKey = "key-1")

        val reuse = ImageAssetDigestPolicy.decide(file, cache(sha256 = "digest-1", file = file))
        assertEquals("digest-1", (reuse as ImageAssetDigestDecision.Reuse).sha256)

        val recompute = ImageAssetDigestPolicy.decide(file, cached = null)
        assertNotEquals(reuse, recompute)
        assertEquals(ImageAssetDigestDecision.Recompute, recompute)
    }

    private fun state(sizeBytes: Long, modifiedAt: Long, changeKey: String): ImageAssetFileState =
        ImageAssetFileState(sizeBytes = sizeBytes, modifiedAt = modifiedAt, changeKey = changeKey)

    private fun cache(sha256: String, file: ImageAssetFileState): ImageAssetDigestCacheState =
        ImageAssetDigestCacheState(
            sha256 = sha256,
            sizeBytes = file.sizeBytes,
            modifiedAt = file.modifiedAt,
            changeKey = file.changeKey
        )

    private companion object {
        const val FLOOR = 4L * 1_024L * 1_024L
    }
}
