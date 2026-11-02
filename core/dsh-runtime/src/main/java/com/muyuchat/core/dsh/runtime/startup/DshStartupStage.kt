package com.muyuchat.core.dsh.runtime.startup

/**
 * Ordered DSHA-style startup boundaries of one Android DSH host generation.
 *
 * This is a separate, additive model next to the frozen
 * [com.muyuchat.core.dsh.contract.DshRuntimePhase]. That phase describes the host lifecycle
 * (`STARTING` -> `READY` -> ...); `READY` means the authenticated official HTTP surface is
 * stable, NOT that the WebView rendered a page. Keeping the two models apart is deliberate:
 * changing [com.muyuchat.core.dsh.contract.DshRuntimeSnapshot] would change the frozen
 * app-facing contract, while the startup timeline only adds observability.
 *
 * The declaration order is the only order in which a generation may advance. The recorder
 * rejects stale generations, duplicates and backwards transitions, so "the port answered" can
 * never be observed as "the page is ready".
 */
enum class DshStartupStage {
    /** The packaged Node executable is running as the Android child process. */
    NODE_PROCESS_ALIVE,

    /**
     * A loopback HTTP request received a response. Any status proves a listener exists; the
     * status itself belongs to the following stages, so this stage is never readiness.
     */
    PORT_LISTENING,

    /**
     * The launch-token exchange returned the HttpOnly session cookie and the cookie-authenticated
     * index was served as HTML. The readiness gate still requires a second authenticated index
     * request (plus the unauthenticated 401 fence) before it publishes `DshRuntimePhase.READY`.
     */
    HTTP_AUTHENTICATED,

    /**
     * The official boot-readiness marker is injected into the index and the client-modules
     * bootstrap asset is served as JavaScript. This is the last startup stage the runtime host
     * can verify on its own.
     */
    PLUGINS_LOADED,

    /**
     * The owning WebView rendered its first frame for this generation.
     *
     * The runtime host cannot observe a frame: the WebView lives in the app process, not in the
     * `:dsh` process. Only an explicit report from that page owner may advance the timeline
     * here; it is never inferred from HTTP success, plugin registration or
     * `DshRuntimePhase.READY`. Anyone wiring that report must keep it epoch-scoped.
     */
    FIRST_FRAME_RENDERED
}

/** One typed startup transition. [atMillis] comes from the recorder clock. */
data class DshStartupEvent(
    val epoch: Long,
    val stage: DshStartupStage,
    val atMillis: Long
)

/**
 * Immutable startup timeline of one host generation.
 *
 * [events] is append-only within a generation and [stage] is its latest entry. The default value
 * means "no host generation has started yet".
 */
data class DshStartupProgress(
    val epoch: Long = 0L,
    val stage: DshStartupStage? = null,
    val changedAtMillis: Long = 0L,
    val events: List<DshStartupEvent> = emptyList()
) {
    fun hasReached(stage: DshStartupStage): Boolean = events.any { it.stage == stage }

    val reachedStages: List<DshStartupStage> get() = events.map { it.stage }
}
