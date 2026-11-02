package com.muyuchat.core.video.contract

/**
 * Stable identity types of the video workflow domain (proposal mca-native-video-workflow-v1 §4).
 *
 * Each id is an opaque token created exactly once by its single authority:
 * - [VideoWorkflowId]/[VideoRevisionId] are minted by the workflow document repository. A
 *   revision is immutable once saved; edits produce a new revision id.
 * - [VideoRunId]/[VideoAttemptId]/[VideoRequestId] are minted by VideoGenerationOwner only.
 *   A retry explicitly creates a NEW attempt (and request); ids are never reused.
 *
 * The ids cross processes (Room rows, worker parcels, journals), so the textual format is a
 * frozen contract: `kind-` followed by a non-empty run of `[a-z0-9]` and `-`. Nothing may parse
 * semantic meaning out of the random part.
 */
@JvmInline
value class VideoWorkflowId(val value: String) {
    init {
        require(isWellFormedId(value)) { "malformed VideoWorkflowId: $value" }
    }

    override fun toString(): String = value
}

@JvmInline
value class VideoRevisionId(val value: String) {
    init {
        require(isWellFormedId(value)) { "malformed VideoRevisionId: $value" }
    }

    override fun toString(): String = value
}

/** One submitted generation job. Retries create a new run attempt, not a new run id. */
@JvmInline
value class VideoRunId(val value: String) {
    init {
        require(isWellFormedId(value)) { "malformed VideoRunId: $value" }
    }

    override fun toString(): String = value
}

/**
 * One physical execution attempt of a run. Progress events carry the attempt id; only the
 * current attempt may advance state (stale-attempt callbacks are dropped by the owner).
 */
@JvmInline
value class VideoAttemptId(val value: String) {
    init {
        require(isWellFormedId(value)) { "malformed VideoAttemptId: $value" }
    }

    override fun toString(): String = value
}

/** The owner-side request identity used for admission, leases and callback gating. */
@JvmInline
value class VideoRequestId(val value: String) {
    init {
        require(isWellFormedId(value)) { "malformed VideoRequestId: $value" }
    }

    override fun toString(): String = value
}

/** Published, verified output media identity (Room `video_media_assets` row key). */
@JvmInline
value class VideoMediaAssetId(val value: String) {
    init {
        require(isWellFormedId(value)) { "malformed VideoMediaAssetId: $value" }
    }

    override fun toString(): String = value
}

/**
 * Id charset check shared by every video identity above. `[a-z0-9-]`, non-empty, no leading or
 * trailing `-`. Deliberately conservative: lowercase hex/random tokens and prefixed kinds all
 * fit; anything a user typed with spaces or uppercase is rejected at the boundary.
 */
fun isWellFormedId(value: String): Boolean =
    value.isNotEmpty() &&
        !value.startsWith("-") &&
        !value.endsWith("-") &&
        value.all { ch -> ch == '-' || ch in 'a'..'z' || ch in '0'..'9' }
