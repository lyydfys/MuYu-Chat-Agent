package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.runtime.journal.AgentJournalRejectedException

/** Stable error codes raised by the Agent data layer. Values are persisted/compared, never renamed. */
object AgentDataErrorCodes {
    const val COMMAND_IDEMPOTENCY_CONFLICT = AgentErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT
    const val COMMAND_REVISION_CONFLICT = AgentErrorCodes.COMMAND_REVISION_CONFLICT
    const val COMMAND_INVALID_STATE = AgentErrorCodes.COMMAND_INVALID_STATE
    const val COMMAND_NOT_FOUND = AgentErrorCodes.COMMAND_NOT_FOUND
    const val PERMISSION_EXPIRED = AgentErrorCodes.PERMISSION_EXPIRED
    const val STORAGE_CORRUPTION = AgentErrorCodes.STORAGE_CORRUPTION
    const val STORAGE_PRECONDITION_CHANGED = AgentErrorCodes.STORAGE_PRECONDITION_CHANGED
    const val STORAGE_ARTIFACT_COMMIT = AgentErrorCodes.STORAGE_ARTIFACT_COMMIT
    const val STORAGE_NO_SPACE = AgentErrorCodes.STORAGE_NO_SPACE
    const val STORAGE_MIGRATION = AgentErrorCodes.STORAGE_MIGRATION
    const val INPUT_INVALID = AgentErrorCodes.TOOL_INPUT_INVALID
    const val NOT_FOUND = AgentErrorCodes.COMMAND_NOT_FOUND
}

/**
 * Typed failure from the Room journal or artifact commit ledger.
 *
 * Data-layer code and its JVM tests use this type. The public Room journal/ledger boundaries
 * translate it into the runtime's [AgentJournalRejectedException] via [translatingJournalErrors], so
 * K03's owner can map the typed [code] without string matching.
 */
class AgentJournalException(
    val code: String,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/** Runs [block], converting data-layer journal failures into the runtime's typed rejection. */
internal suspend fun <T> translatingJournalErrors(block: suspend () -> T): T = try {
    block()
} catch (error: AgentJournalException) {
    throw AgentJournalRejectedException(
        errorCode = error.code,
        message = error.message ?: "journal rejected with ${error.code}"
    )
}

/**
 * Typed failure while reading/writing the private workspace or reconciling an artifact commit.
 * The workspace ports translate this into `AgentFileResult.Failure(code, safeMessage)`.
 */
class AgentStorageException(
    val code: String,
    message: String,
    val possibleSideEffects: Boolean = false,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/** Persisted rows that cannot be decoded into the frozen contract. */
class AgentDataIntegrityException(
    val code: String,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)
