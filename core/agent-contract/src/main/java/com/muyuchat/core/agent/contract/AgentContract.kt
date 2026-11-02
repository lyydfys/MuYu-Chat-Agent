package com.muyuchat.core.agent.contract

/**
 * Versioned, platform-free constants shared by the Agent contract, runtime, persistence and UI.
 *
 * Only values that are part of the accepted Agent V1 contract live here. Presentation limits,
 * Room column names and provider quirks belong to their own layers.
 */
object AgentContract {
    /** Every persisted or cross-module record carries this version. Bump only with a migration. */
    const val SCHEMA_VERSION: Int = 1

    /** The only PROMPTED_V1 control protocol name accepted by the strict decoder. */
    const val PROTOCOL_NAME: String = "mca.agent.v1"

    // --- Bounded protocol inputs (architecture 6.3 / 9.2) ---------------------------------
    const val MAX_CONTROL_RESPONSE_BYTES: Int = 64 * 1024
    const val MAX_TOOL_RESULT_TO_MODEL_BYTES: Int = 16 * 1024
    const val MAX_TOOL_RESULT_PERSIST_BYTES: Int = 64 * 1024
    const val MAX_FINAL_TEXT_BYTES: Int = 32 * 1024
    const val MAX_FINAL_ARTIFACTS: Int = 20
    const val MAX_PARAMETER_STRING_BYTES: Int = 32 * 1024
    const val MAX_JSON_DEPTH: Int = 16

    // --- Ownership / concurrency (architecture 2.4 / 5.4 / 7.2) ---------------------------
    const val MAX_ACTIVE_RUNS_PER_PROCESS: Int = 1
    const val MAX_QUEUED_RUNS: Int = 5
    const val MAX_PENDING_COMMANDS: Int = 64
    const val APPROVAL_TTL_MILLIS: Long = 5 * 60 * 1000L
    const val RESOURCE_WAIT_TIMEOUT_MILLIS: Long = 120 * 1000L

    private val idPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

    /** Runtime-generated ids are UUID strings; persisted ids must not be free-form user text. */
    fun isValidId(value: String): Boolean = idPattern.matches(value)

    fun requireId(value: String, field: String): String {
        require(isValidId(value)) { "$field is not a stable Agent id: '$value'" }
        return value
    }

    fun requireNotBlank(value: String, field: String): String {
        require(value.isNotBlank()) { "$field must not be blank" }
        return value
    }
}
