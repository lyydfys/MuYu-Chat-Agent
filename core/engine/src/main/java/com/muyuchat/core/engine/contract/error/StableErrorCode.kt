package com.muyuchat.core.engine.contract.error

/**
 * High-stability error codes shared across Kotlin, JNI, C++, Local API, and Workers.
 *
 * Each code belongs to a distinct ErrorCategory and provides deterministic handling
 * semantics without relying on raw exception message string matching.
 */
enum class StableErrorCode {
    // --- Validation (VAL) ---
    VAL_INVALID_INPUT,
    VAL_MISSING_REQUIRED_FIELD,
    VAL_CORRUPT_PACKAGE,
    VAL_INVALID_FORMAT,
    VAL_ILLEGAL_ARGUMENT,

    // --- Capability (CAP) ---
    CAP_UNSUPPORTED_OPERATION,
    CAP_BACKEND_UNAVAILABLE,
    CAP_PRECISION_UNSUPPORTED,
    CAP_DEVICE_FALLBACK_REQUIRED,

    // --- Model (MDL) ---
    MDL_NOT_FOUND,
    MDL_CHECKSUM_MISMATCH,
    MDL_LOAD_FAILED,
    MDL_CORRUPT_BUNDLE,
    MDL_MISSING_REQUIRED_BINARY,
    MDL_INCOMPATIBLE_ARCHITECTURE,

    // --- Runtime (RT) ---
    RT_OUT_OF_MEMORY,
    RT_INITIALIZATION_FAILED,
    RT_CRASHED,
    RT_DEVICE_LOST,
    RT_UNHEALTHY,
    RT_CONCURRENT_LIMIT_EXCEEDED,

    // --- Native (NAT) ---
    NAT_DSO_LOAD_FAILED,
    NAT_SYMBOL_NOT_FOUND,
    NAT_EXECUTION_FAULT,
    NAT_ABORTED,
    NAT_JNI_CALL_FAILED,

    // --- Request (REQ) ---
    REQ_INVALID_STATE,
    REQ_DUPLICATE_TERMINAL,
    REQ_STALE_CALLBACK,
    REQ_REJECTED,
    REQ_NOT_FOUND,

    // --- Cancel (CNC) ---
    CNC_USER_CANCELLED,
    CNC_DRAIN_TIMEOUT,
    CNC_ALREADY_CANCELLED,

    // --- Timeout (TIM) ---
    TIM_LOAD_TIMEOUT,
    TIM_EXECUTION_TIMEOUT,
    TIM_DRAIN_TIMEOUT,
    TIM_HEARTBEAT_EXPIRED,

    // --- Storage (STR) ---
    STR_READ_ERROR,
    STR_WRITE_ERROR,
    STR_DISK_FULL,
    STR_PERMISSION_DENIED,

    // --- Network (NET) ---
    NET_CONNECT_FAILED,
    NET_TIMEOUT,
    NET_PROTOCOL_ERROR,

    // --- Auth (AUTH) ---
    AUTH_MISSING_KEY,
    AUTH_INVALID_KEY,
    AUTH_FORBIDDEN,

    // --- Internal (INT) ---
    INT_UNEXPECTED_EXCEPTION,
    INT_CORRUPTED_STATE
}
