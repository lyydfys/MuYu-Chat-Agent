package com.muyuchat.core.dsh.maintenance

/**
 * Ports, value types and typed outcomes shared by every maintenance responsibility.
 *
 * The module is a pure-Kotlin maintenance policy layer. It owns no Android object, opens no
 * socket and never talks to the official DSH kernel directly; the app composition injects:
 *
 * - [DshMaintenanceAdmissionPort] — the single maintenance writer lease (owned by the platform
 *   lane's admission barrier; this module only consumes it),
 * - [DshMaintenanceRecoveryProbe] — an official-reader health check used after a data commit,
 * - [DshHostLifecyclePort] — the serial managed-host lifecycle used by the upgrade trial,
 * - a clock and an optional crash hook (tests only).
 *
 * Journal files keep stable, secret-free identifiers: owner names, operation ids, relative
 * paths, hashes and typed error codes. They never carry tokens, cookies or message bodies.
 */

/** One maintenance responsibility. Journal content is separate per kind (never one umbrella algorithm). */
enum class DshMaintenanceKind {
    BACKUP,
    RESTORE,
    SESSION_RECYCLE,
    SESSION_REPAIR,
    PATCH_APPLY,
    THEME_APPLY,
    UPGRADE,
    SAFE_MODE_ENTER,
    SAFE_MODE_EXIT,
    SELF_CHECK,
    DIAGNOSTICS
}

/**
 * Stable, secret-free failure families. Each one is surfaced to the console projection; the
 * HTTP/UI mapping is owned by the caller, not invented here.
 */
enum class DshMaintenanceError {
    /** Another owner holds the single maintenance lease. */
    ADMISSION_BUSY,
    /** The admission port refused this maintenance operation outright. */
    ADMISSION_REJECTED,
    /** No proof that the managed host and every home mutator are drained. */
    QUIESCENCE_UNPROVEN,
    /** A path resolved outside its declared root or used traversal syntax. */
    PATH_ESCAPE,
    /** A staged file/tree disappeared or is incomplete. */
    STAGING_MISSING,
    /** A real file-system operation failed. */
    IO_FAILURE,
    /** The official reader/healthy host did not accept the committed data. */
    HEALTH_FAILED,
    /** The target volume cannot hold the operation plus its restore point. */
    SPACE_INSUFFICIENT,
    /** The backup is structurally invalid (manifest/hash/size/version). */
    ARCHIVE_INVALID,
    /** The backup is actively hostile (zip-slip, symlink escape, zip bomb, oversize). */
    ARCHIVE_MALICIOUS,
    /** A secret-bearing path/content was excluded from an ordinary export. */
    SECRET_EXCLUDED,
    /** The archive/profile schema major version is not understood. */
    VERSION_UNSUPPORTED,
    /** Restore target already holds data that was not part of the plan. */
    RESTORE_CONFLICT,
    /** Session file format is unknown or undecodable; refusing to guess. */
    UNKNOWN_FORMAT,
    /** A session is running and must settle or be refused first. */
    SESSION_RUNNING,
    /** The requested session/attachment does not exist. */
    SESSION_NOT_FOUND,
    /** The session changed between plan and apply; the caller must re-plan. */
    SESSION_CHANGED,
    /** Permanent recycle-bin deletion requires an explicit confirmation flag. */
    PURGE_CONFIRMATION_REQUIRED,
    /** Recycle/restore name collision or a non-idempotent restore request. */
    RECYCLE_CONFLICT,
    /** Patch base hash does not match the current managed/user file. */
    PATCH_CONFLICT,
    /** Patch targets an executable/managed-upgrade path or escapes the console scope. */
    PATCH_FORBIDDEN,
    /** Theme document is invalid, unknown or over its size/depth budget. */
    THEME_INVALID,
    /** Candidate closure is mixed/incomplete or not ABI compatible with this build. */
    UPGRADE_INCOMPATIBLE,
    /** The managed host is still running; trial and production are strictly serial. */
    HOST_STILL_RUNNING,
    /** Candidate trial smoke failed. */
    TRIAL_FAILED,
    /** Self-check found a real runtime defect. */
    SELF_CHECK_FAILED,
    /** No safe automatic resume/abort exists; data is preserved for the rescue surface. */
    RECOVERY_REQUIRED,
    /** The diagnostic bundle would expose a secret and was refused. */
    DIAGNOSTICS_REDACTION_FAILED,
    /** Defect in this module or a port contract violation. */
    INTERNAL
}

/** Typed, log-safe failure description. Never carries a token, cookie, message body or path outside the app sandbox. */
data class DshMaintenanceFailure(
    val code: DshMaintenanceError,
    val message: String,
    val retryable: Boolean = false,
    /** Bounded engineering detail; must stay secret-free. */
    val detail: String? = null
)

/** Who asks for the single maintenance lease. */
data class DshMaintenanceRequester(
    val owner: String,
    val operationId: String,
    val reason: String
) {
    init {
        require(owner.isNotBlank()) { "dsh: maintenance requester owner must not be blank" }
        require(operationId.isNotBlank()) { "dsh: maintenance operation id must not be blank" }
    }
}

/** Admission request. [waitMillis] 0 asks for a non-blocking try-acquire. */
data class DshMaintenanceAdmissionRequest(
    val requester: DshMaintenanceRequester,
    val kind: DshMaintenanceKind,
    val waitMillis: Long = 0L
)

/** Opaque granted lease. Only the admission owner may release it; the token is never journaled. */
interface DshMaintenanceLease {
    val leaseId: String
    val owner: String
    fun release()
}

sealed interface DshMaintenanceAdmissionOutcome {
    data class Granted(val lease: DshMaintenanceLease) : DshMaintenanceAdmissionOutcome
    data class Busy(val holders: List<String>, val retryAfterMillis: Long) : DshMaintenanceAdmissionOutcome
    data class Rejected(val reason: String) : DshMaintenanceAdmissionOutcome
}

/**
 * The single-writer maintenance admission barrier.
 *
 * The platform lane owns the real implementation (generation authority, drain of in-flight
 * mutations and the D07m online barrier). This module consumes one lease per transaction and
 * never assumes that stopping the UI means the home is quiet.
 */
fun interface DshMaintenanceAdmissionPort {
    fun acquire(request: DshMaintenanceAdmissionRequest): DshMaintenanceAdmissionOutcome
}

/**
 * Evidence that the official writers cannot touch the home while this transaction runs.
 *
 * Either the online barrier covered every mutator family, or the managed host and its writable
 * child tree are confirmed stopped. A residual UNKNOWN process means maintenance stays refused.
 */
data class DshQuiescenceEvidence(
    val hostGenerationStopped: Boolean,
    val onlineBarrierCovered: Boolean,
    val allMutatorsDrained: Boolean,
    val detail: String = ""
) {
    val satisfiesMaintenance: Boolean
        get() = (hostGenerationStopped || onlineBarrierCovered) && allMutatorsDrained

    companion object {
        /** Explicit stopped-host evidence: Node and every writable child tree confirmed gone. */
        fun hostStopped(detail: String = "managed host stopped") = DshQuiescenceEvidence(
            hostGenerationStopped = true,
            onlineBarrierCovered = false,
            allMutatorsDrained = true,
            detail = detail
        )
    }
}

/** Named durability boundary for crash-injection tests. Fires only after the journal line is forced. */
data class DshMaintenanceBoundary(
    val name: String,
    val transactionId: String,
    val stepIndex: Int = -1
) {
    companion object {
        const val PREPARED = "PREPARED"
        const val SNAPSHOT_READY = "SNAPSHOT_READY"
        const val APPLYING = "APPLYING"
        const val STEP_APPLIED = "STEP_APPLIED"
        const val HEALTH_PENDING = "HEALTH_PENDING"
        const val COMMITTED = "COMMITTED"
        const val ROLLED_BACK = "ROLLED_BACK"
        const val RECOVERY_REQUIRED = "RECOVERY_REQUIRED"
    }
}

/** Test seam: throws to simulate process death at an exact durable boundary. */
fun interface DshMaintenanceCrashHook {
    fun onBoundary(boundary: DshMaintenanceBoundary)

    companion object {
        val NONE = DshMaintenanceCrashHook { }
    }
}

/** Result of the official-reader / host health probe after data is committed. */
data class DshMaintenanceProbeResult(val healthy: Boolean, val detail: String = "") {
    companion object {
        val HEALTHY = DshMaintenanceProbeResult(true)
        fun unhealthy(detail: String) = DshMaintenanceProbeResult(false, detail)
    }
}

/**
 * Post-commit health probe. Production injects the official session reader / real host health;
 * a probe must never replay a user turn, log in or perform any side effect.
 */
fun interface DshMaintenanceRecoveryProbe {
    fun verify(kind: DshMaintenanceKind, targetRoot: java.io.File, transactionId: String): DshMaintenanceProbeResult

    companion object {
        /** Conservative default: without a real probe nothing is automatically declared healthy. */
        val ALWAYS_UNHEALTHY = DshMaintenanceRecoveryProbe { _, _, _ ->
            DshMaintenanceProbeResult.unhealthy("no health probe configured")
        }
    }
}

/** Managed host state as reported by the lifecycle owner. */
enum class DshHostState { STOPPED, STOPPING, RUNNING, UNKNOWN, FAILED }

data class DshHostStatus(val state: DshHostState, val generation: Long = 0L, val detail: String = "")

/**
 * Read-only production health facts. Every field is a positive observation; a missing
 * observation is `false`, never "assume ready".
 */
data class DshHostHealth(
    val hostReady: Boolean = false,
    val homeReadable: Boolean = false,
    val sessionReaderReady: Boolean = false,
    /** The official session store exists and is readable (an empty new-user store is valid). */
    val sessionStoreInitialized: Boolean = false,
    /** The trial was seeded from a production home with no committed sessions. */
    val newUserProfile: Boolean = false,
    val providerConfigured: Boolean = false,
    val authReady: Boolean = false,
    val detail: String = ""
) {
    val healthy: Boolean
        get() = hostReady && homeReadable && sessionReaderReady && providerConfigured && authReady
}

/**
 * Serial managed-host lifecycle used by the closure upgrade.
 *
 * Implementations must enforce one managed host at any instant: [startTrial] and
 * [startProduction] fail while the previous host is not confirmed stopped, and a stopped
 * result means the process and its writable child tree are gone.
 */
interface DshHostLifecyclePort {
    fun status(): DshHostStatus

    /** Stops the current managed host and waits for confirmed release. Idempotent when stopped. */
    fun stopAndConfirm(timeoutMillis: Long): DshHostStatus

    /** Starts the isolated trial host on [candidateRoot]. Must refuse while any host is running. */
    fun startTrial(candidateRoot: java.io.File, candidateFingerprint: String): DshHostStatus

    /** Read-only trial smoke: host ready, isolated home readable, official session reader up. */
    fun probeTrialHealth(): DshHostHealth

    /** Stops the trial host and confirms release before the real home is touched. */
    fun stopTrial(timeoutMillis: Long): DshHostStatus

    /** Starts the production host on the real home after the selector commits. */
    fun startProduction(activeRoot: java.io.File, signature: String): DshHostStatus

    /** Read-only production health; never logs in, replays a turn or performs a device action. */
    fun probeReadOnlyHealth(): DshHostHealth

    /**
     * Startup recovery of an isolated trial left behind by a crash. Implementations without a
     * trial generation return [DshTrialRecovery.None]; this must run before any production start.
     */
    fun recoverInterruptedTrial(): DshTrialRecovery = DshTrialRecovery.None
}

/**
 * Bounded outcome of one maintenance transaction.
 *
 * `Committed` is the only success. `RolledBack` means the original data is authoritative again.
 * `RecoveryRequired` means no safe automatic decision exists: data and evidence stay for the
 * rescue surface and the automatic maintenance loop stops.
 */
sealed interface DshMaintenanceOutcome {
    data class Committed(
        val transactionId: String,
        val journalPath: String,
        val committedAtMillis: Long
    ) : DshMaintenanceOutcome

    data class RolledBack(
        val transactionId: String,
        val failure: DshMaintenanceFailure,
        val journalPath: String
    ) : DshMaintenanceOutcome

    data class Rejected(
        val failure: DshMaintenanceFailure,
        val transactionId: String? = null
    ) : DshMaintenanceOutcome

    data class RecoveryRequired(
        val transactionId: String,
        val failure: DshMaintenanceFailure,
        val journalPath: String
    ) : DshMaintenanceOutcome
}

/** Outcome of the next-start journal recovery sweep. */
sealed interface DshMaintenanceRecoveryResult {
    data class Aborted(val transactionId: String, val kind: DshMaintenanceKind, val detail: String) :
        DshMaintenanceRecoveryResult

    data class RolledBack(val transactionId: String, val kind: DshMaintenanceKind, val detail: String) :
        DshMaintenanceRecoveryResult

    data class Completed(val transactionId: String, val kind: DshMaintenanceKind, val detail: String) :
        DshMaintenanceRecoveryResult

    data class RequiresAttention(
        val transactionId: String,
        val kind: DshMaintenanceKind,
        val failure: DshMaintenanceFailure
    ) : DshMaintenanceRecoveryResult
}
