package com.muyuchat.core.dsh.contract

/**
 * Typed trial-generation state reported by the single runtime owner (D24).
 *
 * A closure trial is a *non-publishing* generation inside the same `:dsh` owner lock and host
 * slot. It must never become the production READY generation, and [UNKNOWN] is the only honest
 * answer when the owner cannot prove an outcome. A client must never map a timeout, a binder
 * loss or an unreadable journal to [STOPPED].
 */
enum class DshTrialPhase {
    RUNNING,
    STOPPED,
    FAILED,
    UNKNOWN
}

/** One trial snapshot over the app/runtime-owner boundary. Secret-free and path-free. */
data class DshTrialSnapshot(
    val phase: DshTrialPhase,
    val pid: Long = 0L,
    val generation: Long = 0L,
    val detail: String = ""
)

/** Read-only trial smoke: host ready, isolated home readable, official session reader up. */
data class DshTrialHealthSnapshot(
    val hostReady: Boolean = false,
    val homeReadable: Boolean = false,
    val sessionReaderReady: Boolean = false,
    /**
     * The official session store exists and is readable. An empty store is a valid observation:
     * it is what a new user's first trial has to prove (the store can be initialized).
     */
    val sessionStoreInitialized: Boolean = false,
    /**
     * This trial was seeded from a production home with no committed session logs. Only then may
     * an initialized *empty* store stand in for a verified session container.
     */
    val newUserProfile: Boolean = false,
    val detail: String = ""
)

/**
 * App-process client of the runtime owner's isolated trial generation (D24).
 *
 * The owner must refuse [startTrial] while production is not confirmed stopped, must run inside
 * its existing operation mutex and stop fence, and must return [DshTrialPhase.STOPPED] from
 * [stopTrial] only with a real stop proof (SIGTERM/SIGKILL plus the survivor scan). The trial
 * home/workspace/tmp are app-private absolute paths under `files/dsh/trial`.
 */
interface DshTrialHostClient {
    suspend fun startTrial(
        candidateRoot: String,
        candidateFingerprint: String,
        homeRoot: String,
        workspaceRoot: String,
        tmpRoot: String
    ): DshTrialSnapshot

    suspend fun stopTrial(): DshTrialSnapshot

    suspend fun trialStatus(): DshTrialSnapshot

    suspend fun trialHealth(): DshTrialHealthSnapshot
}
