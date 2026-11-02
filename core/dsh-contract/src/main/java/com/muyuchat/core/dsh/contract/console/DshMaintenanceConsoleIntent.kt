package com.muyuchat.core.dsh.contract.console

/** Marker for intents the owner must see explicitly confirmed by the user. */
interface DshConfirmableMaintenanceIntent { val confirmed: Boolean }

sealed interface DshMaintenanceConsoleIntent {
    val kind: DshMaintenanceOperationKind

    /** True only for operations that mutate user data or executable configuration. */
    val requiresConfirmation: Boolean
        get() = this is DshConfirmableMaintenanceIntent

    data object ListBackups : DshMaintenanceConsoleIntent {
        override val kind = DshMaintenanceOperationKind.LIST_BACKUPS
    }

    data class CreateBackup(val label: String? = null) : DshMaintenanceConsoleIntent {
        override val kind = DshMaintenanceOperationKind.CREATE_BACKUP
        init { require(label == null || isMaintenanceText(label, 120)) }
    }

    data class RestoreBackup(
        val backupId: String,
        override val confirmed: Boolean = false
    ) : DshMaintenanceConsoleIntent, DshConfirmableMaintenanceIntent {
        override val kind = DshMaintenanceOperationKind.RESTORE_BACKUP
        init { require(isOpaqueMaintenanceId(backupId)) }
    }

    data object ListSessions : DshMaintenanceConsoleIntent {
        override val kind = DshMaintenanceOperationKind.LIST_SESSIONS
    }

    data class TrashSession(val sessionId: String) : DshMaintenanceConsoleIntent {
        override val kind = DshMaintenanceOperationKind.TRASH_SESSION
        init { require(isOpaqueMaintenanceId(sessionId)) }
    }

    data class RestoreTrashedSession(val sessionId: String) : DshMaintenanceConsoleIntent {
        override val kind = DshMaintenanceOperationKind.RESTORE_TRASHED_SESSION
        init { require(isOpaqueMaintenanceId(sessionId)) }
    }

    data class PurgeTrashedSession(
        val sessionId: String,
        override val confirmed: Boolean = false
    ) : DshMaintenanceConsoleIntent, DshConfirmableMaintenanceIntent {
        override val kind = DshMaintenanceOperationKind.PURGE_TRASHED_SESSION
        init { require(isOpaqueMaintenanceId(sessionId)) }
    }

    data class RepairSession(
        val sessionId: String,
        override val confirmed: Boolean = false
    ) : DshMaintenanceConsoleIntent, DshConfirmableMaintenanceIntent {
        override val kind = DshMaintenanceOperationKind.REPAIR_SESSION
        init { require(isOpaqueMaintenanceId(sessionId)) }
    }

    data object EnterSafeMode : DshMaintenanceConsoleIntent {
        override val kind = DshMaintenanceOperationKind.ENTER_SAFE_MODE
    }

    data object ExitSafeMode : DshMaintenanceConsoleIntent {
        override val kind = DshMaintenanceOperationKind.EXIT_SAFE_MODE
    }

    data class ApplyConsolePatch(
        /**
         * The user-selected patch document (JSON object with `files[]`). The owner validates every
         * base hash, path, size and content hash before anything is written; a missing payload is
         * refused instead of applying an implicit patch, so this action can never fake success.
         */
        val payload: String? = null,
        override val confirmed: Boolean = false
    ) : DshMaintenanceConsoleIntent, DshConfirmableMaintenanceIntent {
        override val kind = DshMaintenanceOperationKind.APPLY_CONSOLE_PATCH
        init { require(payload == null || payload.length <= DSH_MAINTENANCE_MAX_DOCUMENT_CHARS) }
    }

    data object RollbackConsolePatch : DshMaintenanceConsoleIntent {
        override val kind = DshMaintenanceOperationKind.ROLLBACK_CONSOLE_PATCH
    }

    data class ImportTheme(
        /** The user-selected theme document; the strict theme codec decides whether it is valid. */
        val payload: String? = null,
        override val confirmed: Boolean = false
    ) : DshMaintenanceConsoleIntent, DshConfirmableMaintenanceIntent {
        override val kind = DshMaintenanceOperationKind.IMPORT_THEME
        init { require(payload == null || payload.length <= DSH_MAINTENANCE_MAX_DOCUMENT_CHARS) }
    }

    data object ResetTheme : DshMaintenanceConsoleIntent {
        override val kind = DshMaintenanceOperationKind.RESET_THEME
    }

    /**
     * Imports a user-selected MCP declaration document (`mcp-servers.json` schema).
     *
     * The maintenance adapter validates the document with the production reader and publishes it
     * atomically only when every server is accepted; an invalid document changes nothing. The next
     * host start re-seeds the official `mcp-<name>` rows, so the effect is explicitly a
     * restart-pending one, never an immediate live mutation.
     */
    data class ImportMcpDeclaration(
        val payload: String? = null,
        override val confirmed: Boolean = false
    ) : DshMaintenanceConsoleIntent, DshConfirmableMaintenanceIntent {
        override val kind = DshMaintenanceOperationKind.IMPORT_MCP_DECLARATION
        init { require(payload == null || payload.length <= DSH_MAINTENANCE_MAX_DOCUMENT_CHARS) }
    }

    data class RunClosureUpgrade(
        override val confirmed: Boolean = false
    ) : DshMaintenanceConsoleIntent, DshConfirmableMaintenanceIntent {
        override val kind = DshMaintenanceOperationKind.RUN_CLOSURE_UPGRADE
    }

    data object ResumeClosureUpgrade : DshMaintenanceConsoleIntent {
        override val kind = DshMaintenanceOperationKind.RESUME_CLOSURE_UPGRADE
    }

    /**
     * Operator rescue for an upgrade that is stuck in RECOVERY_REQUIRED: release any surviving
     * trial and record ROLLED_BACK. It never touches the selector, so the active closure keeps
     * serving; it refuses while the interrupted trial cannot be proven stopped.
     */
    data class AbandonClosureUpgrade(
        override val confirmed: Boolean = false
    ) : DshMaintenanceConsoleIntent, DshConfirmableMaintenanceIntent {
        override val kind = DshMaintenanceOperationKind.ABANDON_CLOSURE_UPGRADE
    }

    data object ExportDiagnostics : DshMaintenanceConsoleIntent {
        override val kind = DshMaintenanceOperationKind.EXPORT_DIAGNOSTICS
    }

    /** Read-only self-check. It observes runtime artifacts and live state; it never mutates data. */
    data object RunSelfCheck : DshMaintenanceConsoleIntent {
        override val kind = DshMaintenanceOperationKind.RUN_SELF_CHECK
    }

    /**
     * Precise repair of the build's declared managed files (payload `criticalFiles`).
     *
     * Only files the build manifest declares, with a known expected hash and a matching verified
     * source, are replaced; the owner quarantines the old bytes and runs its health probe. User
     * sessions, the recycle bin, attachments, credentials and the tool workspace are never targets.
     */
    data class RepairManagedFiles(
        override val confirmed: Boolean = false
    ) : DshMaintenanceConsoleIntent, DshConfirmableMaintenanceIntent {
        override val kind = DshMaintenanceOperationKind.REPAIR_MANAGED_FILES
    }
}
