package com.muyuchat.mca.presentation

/**
 * The decision "may I reuse the digest I already computed for this image asset?".
 *
 * This is a pure extraction of one condition that today lives inline in
 * `observedLocalImageFileSha256` (`LocalImageModelIdentity.kt`):
 *
 * ```
 * if (!force && changeKey.isNotEmpty() && bytes > 4L * 1_024L * 1_024L && cached != null &&
 *     cached.sizeBytes == bytes && cached.modifiedAt == modified && cached.changeKey == changeKey) return cached.sha256
 * ```
 *
 * Invariants of this slice:
 * - **Same thresholds, same order, same outcome.** `4L * 1_024L * 1_024L` stays a *strict* lower bound,
 *   `changeKey` must be non-empty, `force` wins over every reuse argument, and all three cached fields
 *   must match. Only the *shape* changed (one `&&` chain -> early returns), never a comparison.
 * - **Pure.** No file, no `java.io`, no `Files`, no Android/Compose, no clock, no cache lookup. The
 *   caller observes the file and looks up the cache; this file only decides.
 * - **NotFound is not a rejection.** Nothing here inspects the device, the chipset, a profile, or an
 *   allowlist; a missing cache entry simply means "recompute".
 *
 * Cancellation is modelled as a third outcome because the extracted call site checks cancellation
 * *before* the reuse condition (`checkCancelled()` at the top of `observedLocalImageFileSha256`), so a
 * cancelled call may never take the reuse branch. A caller that already threw on cancellation can pass
 * the default `isCancelled = false`; a caller that defers the throw must not.
 */

/** What one `stat` observation of the asset file says. `changeKey` is empty when no ctime/inode is available. */
internal data class ImageAssetFileState(
    val sizeBytes: Long,
    val modifiedAt: Long,
    val changeKey: String
)

/** A digest already held by the process-local cache, plus the observation it was computed from. */
internal data class ImageAssetDigestCacheState(
    val sha256: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val changeKey: String
)

/** The outcome of [ImageAssetDigestPolicy.decide]. */
internal sealed interface ImageAssetDigestDecision {
    /** Return this digest without reading the file again. */
    data class Reuse(val sha256: String) : ImageAssetDigestDecision

    /** Hash the file again. */
    data object Recompute : ImageAssetDigestDecision

    /** Do no work: the call was cancelled. */
    data class Reject(val reason: String) : ImageAssetDigestDecision
}

internal object ImageAssetDigestPolicy {

    /**
     * The reuse floor, verbatim from the extracted condition: only files **strictly larger** than
     * 4 MiB may be reused, so a file of exactly `4L * 1_024L * 1_024L` bytes is recomputed.
     */
    const val REUSE_MIN_SIZE_BYTES: Long = 4L * 1_024L * 1_024L

    /** Reason carried by [ImageAssetDigestDecision.Reject] when the call is already cancelled. */
    const val REASON_CANCELLED: String = "生图资产摘要计算已取消"

    /**
     * Decides reuse for an asset whose file state has already been observed.
     *
     * @param file the observed file state (`sizeBytes`, `modifiedAt`, `changeKey`).
     * @param cached the cached entry for this path, or `null` when this process has none.
     * @param force recompute even when every cached field matches.
     * @param isCancelled the call is already cancelled; this outranks every other input.
     */
    fun decide(
        file: ImageAssetFileState,
        cached: ImageAssetDigestCacheState?,
        force: Boolean = false,
        isCancelled: Boolean = false
    ): ImageAssetDigestDecision {
        if (isCancelled) return ImageAssetDigestDecision.Reject(REASON_CANCELLED)
        if (force) return ImageAssetDigestDecision.Recompute
        if (file.changeKey.isEmpty()) return ImageAssetDigestDecision.Recompute
        if (file.sizeBytes <= REUSE_MIN_SIZE_BYTES) return ImageAssetDigestDecision.Recompute
        if (cached == null) return ImageAssetDigestDecision.Recompute
        if (cached.sizeBytes != file.sizeBytes) return ImageAssetDigestDecision.Recompute
        if (cached.modifiedAt != file.modifiedAt) return ImageAssetDigestDecision.Recompute
        if (cached.changeKey != file.changeKey) return ImageAssetDigestDecision.Recompute
        return ImageAssetDigestDecision.Reuse(cached.sha256)
    }

    /**
     * Same decision for call sites that hold the raw fields instead of a cache object -- typically the
     * private `ImageFileDigestCacheEntry`. `cachedSha256 == null` means "no cache entry for this path";
     * the three `cached*` fields are then read as "absent" and are not consulted, exactly like the
     * `cached != null` guard they replace.
     */
    fun decide(
        sizeBytes: Long,
        modifiedAt: Long,
        changeKey: String,
        cachedSha256: String? = null,
        cachedSizeBytes: Long = 0L,
        cachedModifiedAt: Long = 0L,
        cachedChangeKey: String = "",
        force: Boolean = false,
        isCancelled: Boolean = false
    ): ImageAssetDigestDecision = decide(
        file = ImageAssetFileState(sizeBytes, modifiedAt, changeKey),
        cached = cachedSha256?.let {
            ImageAssetDigestCacheState(it, cachedSizeBytes, cachedModifiedAt, cachedChangeKey)
        },
        force = force,
        isCancelled = isCancelled
    )
}
