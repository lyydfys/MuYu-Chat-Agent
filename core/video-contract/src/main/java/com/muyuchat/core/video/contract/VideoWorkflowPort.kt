package com.muyuchat.core.video.contract

import kotlinx.coroutines.flow.Flow

/**
 * The single typed entry point into video generation (proposal §3.1/§7.1).
 *
 * UI, Local API and workers all go through this port; the process-level VideoGenerationOwner is
 * its only production implementation. The port deliberately exposes five capabilities and no
 * engine/runtime access: pages send intents and observe read-only projections. Leaving a page
 * cancels nothing; re-entering must reuse the same owner (never a second runtime).
 */
interface VideoWorkflowPort : VideoMediaLibraryPort {

    /** Read-only projection of every visible run, ordered newest first. */
    fun observeRuns(): Flow<List<VideoRunProjection>>

    /** Read-only projection of one run (null while/after pruning). */
    fun observeRun(runId: VideoRunId): Flow<VideoRunProjection?>

    /**
     * Freezes the current draft as one immutable revision in the owner's store. Running freezes
     * the revision FIRST (§9.4.1): a run always binds a persisted snapshot, and editing the
     * draft afterwards can never reach it. Re-saving byte-identical content is idempotent; a
     * changed graph under the same revision id is rejected (mint a new revision instead).
     */
    suspend fun saveRevision(document: VideoWorkflowDocument): VideoRevisionResult

    /**
     * Admits a new run for an immutable revision. The owner freezes the snapshot (revision
     * digest, resolved inputs, seed, resolved params) BEFORE persisting the QUEUED row;
     * a submit that fails validation or admission returns a typed rejection, never a silent
     * queue drop.
     */
    suspend fun submit(command: VideoSubmitCommand): VideoSubmitResult

    /**
     * Requests cancel of the current attempt. This records CANCEL_REQUESTED only; the separate
     * stop/release confirmations arrive via the projection's [VideoRunProjection.resourceState].
     */
    suspend fun requestCancel(runId: VideoRunId): VideoCancelResult

    /**
     * Explicit retry: creates a NEW attempt (new request id) bound to the same snapshot, or to a
     * new snapshot when [VideoRetryCommand.newSeed] is set. Never mutates a terminal run.
     */
    suspend fun retry(runId: VideoRunId, command: VideoRetryCommand = VideoRetryCommand()): VideoSubmitResult
}

/** Submit intent: which frozen revision to run and which inputs to bind. */
data class VideoSubmitCommand(
    val revisionId: VideoRevisionId,
    /** Overrides/confirmations for inputs that the revision references (image assets). */
    val inputBindings: Map<String, VideoInputBinding> = emptyMap(),
    val backend: VideoBackendKind = VideoBackendKind.LOCAL_NATIVE,
    /** Required for [VideoBackendKind.REMOTE_COMFY]; forbidden for local/native backends. */
    val remoteBinding: VideoRemoteEndpointBinding? = null,
    /** Retry-only: run with the recorded (or explicitly replaced) seed instead of a new draw. */
    val seedOverride: Long? = null,
    /**
     * Local execution engine frozen into the snapshot. [VideoLocalEngine.DEFAULT] is the built-in
     * MCA pipeline and stays byte-identical to a request that never mentions an engine.
     */
    val engine: String = VideoLocalEngine.DEFAULT,
    /**
     * External-engine backend frozen into the snapshot (see [VideoLocalBackend]). It is only sent
     * to the native layer for a non-default engine, so it never changes the built-in request.
     */
    val externalBackend: String = VideoLocalBackend.HTP,
    /** LoRA weights frozen into the snapshot; only meaningful with the external engine. */
    val loraSpecs: List<VideoLoraBinding> = emptyList(),
)

/** Retry intent. A new seed requires a new, explicitly recorded snapshot. */
data class VideoRetryCommand(
    val newSeed: Long? = null,
)

sealed class VideoSubmitResult {
    data class Admitted(val runId: VideoRunId, val attemptId: VideoAttemptId) : VideoSubmitResult()

    data class Rejected(val code: VideoErrorCode, val detail: String) : VideoSubmitResult()
}

sealed class VideoRevisionResult {
    /** The stored (or already-identical) revision id. */
    data class Saved(val revisionId: VideoRevisionId) : VideoRevisionResult()

    /** The same id carries different content: mint a new revision and retry. */
    data class Conflict(val revisionId: VideoRevisionId) : VideoRevisionResult()
}

sealed class VideoCancelResult {
    /** Cancel accepted and persisted (CANCEL_REQUESTED). */
    data class Accepted(val runId: VideoRunId) : VideoCancelResult()

    /** The run already reached a terminal state; nothing was cancelled. */
    data class AlreadyTerminal(val runId: VideoRunId, val state: VideoRunState) : VideoCancelResult()

    data class UnknownRun(val runId: VideoRunId) : VideoCancelResult()
}

/**
 * Read-only run projection. Immutable view of one attempt; progress events carry the attempt id
 * and only the current attempt's events are reflected here (stale ones are dropped by the owner
 * before they can touch state).
 */
data class VideoRunProjection(
    val runId: VideoRunId,
    val attemptId: VideoAttemptId,
    val workflowId: VideoWorkflowId,
    val revisionId: VideoRevisionId,
    val state: VideoRunState,
    val stage: VideoRunStage,
    /** Step progress within the stage when genuinely measured (e.g. sampling step/total); null otherwise. */
    val step: Long? = null,
    val stepTotal: Long? = null,
    val resourceState: VideoResourceState,
    val errorCode: VideoErrorCode? = null,
    val errorDetail: String? = null,
    val outputMediaId: VideoMediaAssetId? = null,
    /** Non-secret remote identity persisted for queue/history reconciliation (V3). */
    val remoteEndpointId: String? = null,
    val remotePromptId: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val terminalAtMs: Long? = null,
) {
    val isTerminal: Boolean get() = VideoRunTransitions.isTerminal(state)
}
