package com.muyuchat.mca;

/**
 * Narrow bound-worker protocol of the independent :local_video process (proposal §7.3, V1-02b).
 *
 * The worker executes ONE physical video attempt at a time; it never decides Room state (the
 * process owner is the only terminal-state writer). Files travel via staged paths inside the
 * request, never as Binder payloads — no frames, no MP4 bytes cross this interface.
 */
interface ILocalVideoWorker {

    /**
     * Begin one attempt. Fields are explicit and typed at the boundary; snapshotJson is the
     * frozen VideoRunSnapshot codec payload (metadata only) and executionJson carries the
     * transient executable payload (prompt text, staged file paths, model paths). Neither carries
     * frames or MP4 bytes, and executionJson is never persisted in Room.
     * Returns false when another attempt is already active (explicit single-active refusal).
     */
    boolean begin(
        in String runId,
        in String attemptId,
        in String requestId,
        in String snapshotJson,
        in String executionJson,
        in String backend,
        in com.muyuchat.mca.ILocalVideoWorkerCallback callback);

    /** Request cooperative stop of the current attempt (idempotent, identity-checked). */
    void cancel(in String requestId);

    /** Escalate: stop the attempt AND exit the worker process (watchdog path). */
    void cancelAndExit(in String requestId);

    /** Owner-initiated orderly shutdown of the whole worker. */
    void shutdown(in String reason);
}
