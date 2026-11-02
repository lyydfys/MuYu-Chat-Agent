package com.muyuchat.core.video.contract

/**
 * Geometry and frame-count rules of the pinned Wan TI2V-5B native baseline (proposal §6.5).
 *
 * Both rules are derived from the actual native code at third_party/stable-diffusion.cpp
 * revision 16b3846 (internal patches noted in V0-01):
 * - `align_video_frames` maps every request down onto `4k+1` (`stable-diffusion.cpp` lines
 *   3285–3310): `video_frames_to_latent_frames(f) = ((f-1)/4)+1`, then
 *   `latent_frames_to_video_frames(l) = (l-1)*4+1`. The fixed points are exactly `f ≡ 1 (mod 4)`.
 * - TI2V VAE spatial scale 16 with DiT down factor 2 makes legal edge lengths multiples of 32.
 *
 * These are baseline facts, not universal video-model constants: a different model or revision
 * re-derives its own rule set and must not silently reuse these.
 */
const val WAN_GEOMETRY_MULTIPLE = 32L
private const val WAN_FRAME_STRIDE = 4L

/** True when native would keep the requested frame count unchanged (`4k+1`, k >= 0). */
fun isWanAlignedFrameCount(frames: Long): Boolean =
    frames >= 1 && (frames - 1) % WAN_FRAME_STRIDE == 0L

/**
 * The value native would actually produce for any requested count — exposed so editors and the
 * compiler offer the resolved value explicitly instead of letting a request shrink silently.
 */
fun resolveWanAlignedFrameCount(requestedFrames: Long): Long {
    if (requestedFrames < 1) return 1L
    val latentFrames = ((requestedFrames - 1) / WAN_FRAME_STRIDE) + 1
    return (latentFrames - 1) * WAN_FRAME_STRIDE + 1
}
