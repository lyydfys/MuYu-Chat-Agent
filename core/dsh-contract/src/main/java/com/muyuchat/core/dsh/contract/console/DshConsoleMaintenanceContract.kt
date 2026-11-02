package com.muyuchat.core.dsh.contract.console

/**
 * D23 maintenance console seam (also used by D21 sessions, D22 backup/safe mode and D24 closure
 * upgrade). It carries bounded metadata only — opaque ids, timestamps, counts and stable state
 * tokens — never paths, URIs, tokens, credentials, prompts or session content. The console feature
 * talks only to [DshMaintenanceConsolePort]; every effect stays with the owning implementation.
 */
enum class DshMaintenanceConsoleAvailability { AVAILABLE, BUSY, UNAVAILABLE, USER_PRESENCE_REQUIRED }

enum class DshMaintenanceOperationKind {
    LIST_BACKUPS, CREATE_BACKUP, RESTORE_BACKUP, LIST_SESSIONS, TRASH_SESSION,
    RESTORE_TRASHED_SESSION, PURGE_TRASHED_SESSION, REPAIR_SESSION, ENTER_SAFE_MODE,
    EXIT_SAFE_MODE, APPLY_CONSOLE_PATCH, ROLLBACK_CONSOLE_PATCH, IMPORT_THEME, RESET_THEME,
    RUN_CLOSURE_UPGRADE, RESUME_CLOSURE_UPGRADE, ABANDON_CLOSURE_UPGRADE, EXPORT_DIAGNOSTICS, RUN_SELF_CHECK,
    REPAIR_MANAGED_FILES, IMPORT_MCP_DECLARATION
}

/** One backup as listed by the maintenance owner. [backupId] is opaque, never a path. */
data class DshMaintenanceBackup(
    val backupId: String,
    val createdAtMillis: Long,
    val label: String? = null,
    val sizeBytes: Long = 0L,
    val entryCount: Int = 0,
    val verified: Boolean = true
) {
    init {
        require(isOpaqueMaintenanceId(backupId)) { "backup id must be an opaque bounded id" }
        require(createdAtMillis >= 0L) { "backup timestamp must be non-negative" }
        require(label == null || isMaintenanceText(label, 120)) { "backup label must be prompt-free" }
        require(sizeBytes >= 0L) { "backup size must be non-negative" }
        require(entryCount >= 0) { "backup entry count must be non-negative" }
    }
}

/** Session metadata for the console; no content, only an owner-provided bounded label. */
data class DshMaintenanceSession(
    val sessionId: String,
    val updatedAtMillis: Long,
    val messageCount: Int = 0,
    /** False when the owner cannot decode the session (e.g. no codec) and the count is unknown. */
    val messageCountKnown: Boolean = true,
    val trashed: Boolean = false,
    val label: String? = null
) {
    init {
        require(isOpaqueMaintenanceId(sessionId)) { "session id must be an opaque bounded id" }
        require(updatedAtMillis >= 0L) { "session timestamp must be non-negative" }
        require(messageCount >= 0) { "session message count must be non-negative" }
        require(label == null || isMaintenanceText(label, 120)) { "session label must be prompt-free" }
    }
}

enum class DshMaintenancePatchLayer { MANAGED, USER }

data class DshMaintenancePatchState(
    val activeLayer: DshMaintenancePatchLayer? = null,
    val userLayerPresent: Boolean = false,
    val managedLayerPresent: Boolean = false,
    val conflict: Boolean = false
)

data class DshMaintenanceThemeState(
    val themeId: String? = null,
    val revision: Int = 0,
    val customized: Boolean = false
) {
    init {
        require(themeId == null || isOpaqueMaintenanceId(themeId)) { "theme id must be opaque" }
        require(revision >= 0) { "theme revision must be non-negative" }
    }
}

enum class DshMaintenanceUpgradePhase {
    IDLE, TRIAL_RUNNING, HEALTH_PENDING, COMMITTED, ROLLED_BACK, RECOVERY_REQUIRED
}

data class DshMaintenanceUpgradeState(
    val phase: DshMaintenanceUpgradePhase = DshMaintenanceUpgradePhase.IDLE,
    val candidateVersion: String? = null,
    val progressPercent: Int? = null
) {
    init {
        require(candidateVersion == null || isMaintenanceText(candidateVersion, 64)) {
            "upgrade candidate version must be a short prompt-free token"
        }
        require(progressPercent == null || progressPercent in 0..100) { "upgrade progress is out of range" }
    }
}

/** Outcome of the last on-device self-check; the console keeps it as a persistent row. */
enum class DshSelfCheckDegree { HEALTHY, NEEDS_REPAIR, ATTENTION }

data class DshSelfCheckSummary(
    val degree: DshSelfCheckDegree,
    val checkedCount: Int,
    val issueCount: Int,
    val capturedAtMillis: Long,
    val headline: String
) {
    init {
        require(checkedCount >= 0) { "self-check checked count must be non-negative" }
        require(issueCount >= 0) { "self-check issue count must be non-negative" }
        require(issueCount <= checkedCount) { "self-check issues cannot exceed checks" }
        require(capturedAtMillis >= 0L) { "self-check capture time must be non-negative" }
        require(isMaintenanceText(headline, 120)) { "self-check headline must be bounded plain text" }
    }
}

/**
 * Read-only maintenance projection. [availableOperations] is the owner's honest capability list;
 * absent kinds disable their console buttons instead of pretending the feature exists.
 */
data class DshMaintenanceConsoleSnapshot(
    val revision: Long,
    val capturedAtMillis: Long,
    val availability: DshMaintenanceConsoleAvailability,
    val availableOperations: Set<DshMaintenanceOperationKind>,
    val busyOperation: DshMaintenanceOperationKind? = null,
    val backups: List<DshMaintenanceBackup> = emptyList(),
    val sessions: List<DshMaintenanceSession> = emptyList(),
    val trashCount: Int = 0,
    val safeModeActive: Boolean = false,
    val patch: DshMaintenancePatchState = DshMaintenancePatchState(),
    val theme: DshMaintenanceThemeState = DshMaintenanceThemeState(),
    val upgrade: DshMaintenanceUpgradeState = DshMaintenanceUpgradeState(),
    val diagnosticsReady: Boolean = false,
    val lastSelfCheck: DshSelfCheckSummary? = null
) {
    init {
        require(revision >= 0L) { "maintenance revision must be non-negative" }
        require(capturedAtMillis >= 0L) { "maintenance capture time must be non-negative" }
        require(backups.size <= 64) { "too many backups for one projection" }
        require(sessions.size <= 200) { "too many sessions for one projection" }
        require(backups.distinctBy { it.backupId }.size == backups.size) { "duplicate backup id" }
        require(sessions.distinctBy { it.sessionId }.size == sessions.size) { "duplicate session id" }
        require(trashCount >= 0) { "trash count must be non-negative" }
        require((availability == DshMaintenanceConsoleAvailability.BUSY) == (busyOperation != null)) {
            "only a BUSY projection carries a busy operation"
        }
    }
}

/**
 * Upper bound for a user-selected patch/theme document crossing the console seam. It is a contract
 * limit so a caller cannot push an unbounded string into the maintenance adapter; the owner's own
 * per-file and total budgets are stricter and still apply.
 */
const val DSH_MAINTENANCE_MAX_DOCUMENT_CHARS = 512 * 1024

/** Model-visible tool budget shared by built-in DSH tools and every `mcp__*` tool. */
const val DSH_MAX_MODEL_VISIBLE_TOOLS = 128
