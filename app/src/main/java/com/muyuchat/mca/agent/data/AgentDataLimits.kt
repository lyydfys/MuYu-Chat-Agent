package com.muyuchat.mca.agent.data

/**
 * Bounded sizes for the Agent persistence and workspace layer.
 *
 * These mirror the frozen architecture limits (9.1 / 10.1 / 10.5). They are duplicated here as
 * data-layer guards so a caller that skips tool-layer validation can never issue an unbounded
 * query, read or write. The data layer may enforce a tighter bound than a caller, never a looser
 * one.
 */
object AgentDataLimits {
    // --- Paging (architecture 10.5: cursor/sequence paging) -------------------------------
    const val MIN_PAGE_LIMIT: Int = 1
    const val MAX_TASK_PAGE_LIMIT: Int = 200
    const val MAX_EVENT_PAGE_LIMIT: Int = 200
    const val MAX_CHILD_PAGE_LIMIT: Int = 200
    const val MAX_STEP_PAGE_LIMIT: Int = 100
    const val MAX_ARTIFACT_PAGE_LIMIT: Int = 100

    // --- Text tools (architecture 9.1) -----------------------------------------------------
    const val MAX_READ_BYTES: Int = 16 * 1024
    const val MAX_SEARCH_MATCHES: Int = 100
    const val MAX_SEARCH_QUERY_CHARS: Int = 256
    const val SEARCH_TOTAL_SCAN_BYTES: Long = 32L * 1024 * 1024
    const val SEARCH_PER_FILE_BYTES: Long = 8L * 1024 * 1024
    const val MAX_ARTIFACT_WRITE_BYTES: Int = 32 * 1024
    const val MAX_LIST_SCAN_ENTRIES: Int = 10_000

    // --- Logical paths (architecture 8.4 / 9.1) -------------------------------------------
    const val MAX_LOGICAL_PATH_CHARS: Int = 512
    const val MAX_LOGICAL_PATH_DEPTH: Int = 16
    const val MAX_CURSOR_CHARS: Int = 1_024

    // --- Payloads (architecture 10.1 / 10.5) ----------------------------------------------
    const val MAX_EVENT_SUMMARY_BYTES: Int = 4 * 1024
    const val MAX_EVENT_PAYLOAD_BYTES: Int = 64 * 1024
    const val MAX_TOOL_RESULT_PAYLOAD_BYTES: Int = 64 * 1024
    const val MAX_PAYLOAD_BYTES: Int = 4 * 1024 * 1024
    const val MAX_IMPORT_BYTES: Long = 64L * 1024 * 1024

    // --- Quota soft thresholds (architecture 10.5) ----------------------------------------
    const val TASK_SOFT_QUOTA_BYTES: Long = 256L * 1024 * 1024
    const val TOTAL_SOFT_QUOTA_BYTES: Long = 1024L * 1024 * 1024
    const val DISK_FREE_SAFETY_MARGIN_BYTES: Long = 32L * 1024 * 1024

    // --- Journal ---------------------------------------------------------------------------
    const val MAX_EVENTS_PER_TRANSITION: Int = 32
    const val MAX_COMMAND_RESULT_BYTES: Int = 8 * 1024
    const val MAX_IDEMPOTENCY_KEY_CHARS: Int = 256
}
