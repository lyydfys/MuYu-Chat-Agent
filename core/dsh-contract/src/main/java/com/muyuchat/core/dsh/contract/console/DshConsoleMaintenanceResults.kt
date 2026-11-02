package com.muyuchat.core.dsh.contract.console

enum class DshMaintenanceConsoleRefusal {
    UNAVAILABLE, BUSY, CONFIRMATION_REQUIRED, NOT_FOUND, NOT_ELIGIBLE, SAFE_MODE_ACTIVE,
    UPGRADE_IN_PROGRESS, NO_ACTIVE_UPGRADE, PATCH_CONFLICT, STORAGE_INSUFFICIENT,
    USER_PRESENCE_REQUIRED, TRASH_EMPTY, SESSION_RUNNING
}

/** Typed result of one maintenance intent. Only [Applied] and [Pending] mean the owner accepted it. */
sealed interface DshMaintenanceConsoleResult {
    val kind: DshMaintenanceOperationKind

    data class Applied(
        override val kind: DshMaintenanceOperationKind,
        val message: String? = null
    ) : DshMaintenanceConsoleResult {
        init { require(message == null || isMaintenanceText(message, 200)) }
    }

    data class Pending(
        override val kind: DshMaintenanceOperationKind,
        val message: String? = null
    ) : DshMaintenanceConsoleResult {
        init { require(message == null || isMaintenanceText(message, 200)) }
    }

    data class Refused(
        override val kind: DshMaintenanceOperationKind,
        val reason: DshMaintenanceConsoleRefusal,
        val message: String? = null
    ) : DshMaintenanceConsoleResult {
        init { require(message == null || isMaintenanceText(message, 200)) }
    }

    data class Failed(
        override val kind: DshMaintenanceOperationKind,
        val code: String,
        val retryable: Boolean,
        val message: String? = null
    ) : DshMaintenanceConsoleResult {
        init {
            require(isMaintenanceToken(code, 64)) { "failure code must be a stable token" }
            require(message == null || isMaintenanceText(message, 200))
        }
    }
}

/** The only way the console talks to the maintenance/session/upgrade owners. */
interface DshMaintenanceConsolePort {
    suspend fun snapshot(): DshMaintenanceConsoleSnapshot
    suspend fun execute(intent: DshMaintenanceConsoleIntent): DshMaintenanceConsoleResult
}

internal fun isOpaqueMaintenanceId(value: String): Boolean =
    value.length in 1..128 && value != "." && value != ".." && value.all {
        it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-'
    }

internal fun isMaintenanceText(value: String, maxChars: Int): Boolean =
    value.isNotEmpty() && value.length <= maxChars && value.none(::isMaintenanceControl)

internal fun isMaintenanceToken(value: String, maxChars: Int): Boolean =
    isMaintenanceText(value, maxChars) && value.none { it == ' ' }

internal fun isMaintenanceControl(character: Char): Boolean =
    character.code < 0x20 || character.code in 0x7F..0x9F
