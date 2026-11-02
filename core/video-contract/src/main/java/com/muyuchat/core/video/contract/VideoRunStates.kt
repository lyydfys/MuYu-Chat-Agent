package com.muyuchat.core.video.contract

/**
 * Video run states (proposal §4.3) and the legal-transition table (§4.3.1), frozen verbatim.
 *
 * VideoGenerationOwner is the ONLY decision maker for these transitions; the repository applies
 * them via conditional transactions keyed on `runId + attemptId + expected stateVersion`, and
 * the UI observes read-only projections. A rejected transition is a bug signal, not a retry
 * input — every update path must be able to name the row it is writing and the attempt it
 * belongs to.
 */
enum class VideoRunState {
    /** Editable document reference; not an admitted job yet. */
    DRAFT,

    /** Admitted and durably persisted; no worker/resources assigned yet. */
    QUEUED,

    /** Inputs/model snapshot staged and verified under a valid lease. */
    PREPARING,

    /** Text/image conditioning through the model's encoder role. */
    CONDITIONING,

    /** Latent sampling in progress. Progress never regresses within an attempt. */
    SAMPLING,

    /** Latent → RGB frames through the model's VAE role. */
    DECODING,

    /** RGB frames → staged MP4 (MediaCodec/MediaMuxer, EOS + container checks pending). */
    ENCODING,

    /** Container verified; atomic file publish + Room transaction in flight. */
    PUBLISHING,

    /** Terminal success. Later facts (media missing, remote late arrival) are separate records. */
    COMPLETED,

    /** User cancel accepted and persisted; native stop and release not yet confirmed. */
    CANCEL_REQUESTED,

    /** Terminal cancel: user stop AND native stop AND resource release all confirmed. */
    CANCELLED,

    /** Terminal failure. A still-running worker must still be handled before this is written. */
    FAILED,

    /** Terminal interruption (process death / unrecoverable worker loss). */
    INTERRUPTED,

    /** Persisted evidence insufficient to decide; never auto-resubmitted. */
    UNKNOWN,

    /** Startup/owner reconciliation in progress for this run. */
    RECONCILING,
}

/**
 * Physical resource side of a run, tracked separately from the business state (§4.3.1): a FAILED
 * run whose worker still holds native memory must not be described as released.
 */
enum class VideoResourceState {
    /** Native context/worker actively held for the current attempt. */
    HELD,

    /** Stop requested; waiting for native/worker confirmation. */
    STOPPING,

    /** Stop confirmed and resources freed (idempotent terminal). */
    RELEASED,

    /** Owner lost confirmation (process death); reconcile must decide. */
    UNCONFIRMED,
}

/** Which stage a run is reporting; orthogonal to [VideoRunState] coarse phases. */
enum class VideoRunStage {
    NONE,
    PREPARE_INPUTS,
    LOAD_MODEL,
    TEXT_ENCODE,
    SAMPLE,
    DECODE,
    ENCODE,
    VERIFY,
    PUBLISH,
    FINALIZE,
}

/**
 * The frozen transition table. Sources: proposal §4.3.1 exactly — no additional edges are
 * invented here. UNKNOWN/RECONCILING recover into a running phase or a terminal state based on
 * persisted evidence for the SAME attempt; that evidence duty is the owner's, the table only
 * admits the edges.
 */
object VideoRunTransitions {

    private val runningPhases = setOf(
        VideoRunState.PREPARING,
        VideoRunState.CONDITIONING,
        VideoRunState.SAMPLING,
        VideoRunState.DECODING,
        VideoRunState.ENCODING,
        VideoRunState.PUBLISHING,
    )

    private val terminalStates = setOf(
        VideoRunState.COMPLETED,
        VideoRunState.CANCELLED,
        VideoRunState.FAILED,
        VideoRunState.INTERRUPTED,
    )

    private val table: Map<VideoRunState, Set<VideoRunState>> = buildMap {
        put(VideoRunState.DRAFT, setOf(VideoRunState.QUEUED))
        put(
            VideoRunState.QUEUED,
            setOf(VideoRunState.PREPARING, VideoRunState.CANCELLED, VideoRunState.FAILED, VideoRunState.INTERRUPTED),
        )
        put(
            VideoRunState.PREPARING,
            setOf(VideoRunState.CONDITIONING, VideoRunState.CANCEL_REQUESTED, VideoRunState.FAILED, VideoRunState.INTERRUPTED),
        )
        put(
            VideoRunState.CONDITIONING,
            setOf(VideoRunState.SAMPLING, VideoRunState.CANCEL_REQUESTED, VideoRunState.FAILED, VideoRunState.INTERRUPTED),
        )
        put(
            VideoRunState.SAMPLING,
            setOf(VideoRunState.DECODING, VideoRunState.CANCEL_REQUESTED, VideoRunState.FAILED, VideoRunState.INTERRUPTED),
        )
        put(
            VideoRunState.DECODING,
            setOf(VideoRunState.ENCODING, VideoRunState.CANCEL_REQUESTED, VideoRunState.FAILED, VideoRunState.INTERRUPTED),
        )
        put(
            VideoRunState.ENCODING,
            setOf(VideoRunState.PUBLISHING, VideoRunState.CANCEL_REQUESTED, VideoRunState.FAILED, VideoRunState.INTERRUPTED),
        )
        put(
            VideoRunState.PUBLISHING,
            setOf(VideoRunState.COMPLETED, VideoRunState.CANCEL_REQUESTED, VideoRunState.FAILED, VideoRunState.RECONCILING),
        )
        put(
            VideoRunState.CANCEL_REQUESTED,
            setOf(VideoRunState.CANCELLED, VideoRunState.INTERRUPTED, VideoRunState.RECONCILING),
        )
        // Recovery edges: only from persisted evidence, same attempt (§4.3.1 last data rows).
        put(VideoRunState.UNKNOWN, runningPhases + terminalStates)
        put(VideoRunState.RECONCILING, runningPhases + terminalStates)
        // Terminal states have no outgoing business transitions.
        terminalStates.forEach { put(it, emptySet()) }
    }

    val terminals: Set<VideoRunState> = terminalStates

    fun isTerminal(state: VideoRunState): Boolean = state in terminalStates

    fun allowed(from: VideoRunState): Set<VideoRunState> = table.getValue(from)

    fun allows(from: VideoRunState, to: VideoRunState): Boolean = to in table.getValue(from)
}
