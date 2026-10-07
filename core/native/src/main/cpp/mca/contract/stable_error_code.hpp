#ifndef MCA_CONTRACT_STABLE_ERROR_CODE_HPP
#define MCA_CONTRACT_STABLE_ERROR_CODE_HPP

#include <cstdint>

namespace mca {
namespace contract {

/**
 * Standardized cross-layer error codes shared between C++, JNI, and Kotlin.
 * Mirrors com.muyuchat.core.engine.contract.error.StableErrorCode.
 */
enum class StableErrorCode : uint32_t {
    // Validation (100-199)
    VAL_INVALID_INPUT = 100,
    VAL_MISSING_REQUIRED_FIELD = 101,
    VAL_CORRUPT_PACKAGE = 102,
    VAL_INVALID_FORMAT = 103,
    VAL_ILLEGAL_ARGUMENT = 104,

    // Capability (200-299)
    CAP_UNSUPPORTED_OPERATION = 200,
    CAP_BACKEND_UNAVAILABLE = 201,
    CAP_PRECISION_UNSUPPORTED = 202,
    CAP_DEVICE_FALLBACK_REQUIRED = 203,

    // Model (300-399)
    MDL_NOT_FOUND = 300,
    MDL_CHECKSUM_MISMATCH = 301,
    MDL_LOAD_FAILED = 302,
    MDL_CORRUPT_BUNDLE = 303,
    MDL_MISSING_REQUIRED_BINARY = 304,
    MDL_INCOMPATIBLE_ARCHITECTURE = 305,

    // Runtime (400-499)
    RT_OUT_OF_MEMORY = 400,
    RT_INITIALIZATION_FAILED = 401,
    RT_CRASHED = 402,
    RT_DEVICE_LOST = 403,
    RT_UNHEALTHY = 404,
    RT_CONCURRENT_LIMIT_EXCEEDED = 405,

    // Native (500-599)
    NAT_DSO_LOAD_FAILED = 500,
    NAT_SYMBOL_NOT_FOUND = 501,
    NAT_EXECUTION_FAULT = 502,
    NAT_ABORTED = 503,
    NAT_JNI_CALL_FAILED = 504,

    // Request (600-699)
    REQ_INVALID_STATE = 600,
    REQ_DUPLICATE_TERMINAL = 601,
    REQ_STALE_CALLBACK = 602,
    REQ_REJECTED = 603,
    REQ_NOT_FOUND = 604,

    // Cancel (700-799)
    CNC_USER_CANCELLED = 700,
    CNC_DRAIN_TIMEOUT = 701,
    CNC_ALREADY_CANCELLED = 702,

    // Timeout (800-899)
    TIM_LOAD_TIMEOUT = 800,
    TIM_EXECUTION_TIMEOUT = 801,
    TIM_DRAIN_TIMEOUT = 802,
    TIM_HEARTBEAT_EXPIRED = 803,

    // Storage (900-999)
    STR_READ_ERROR = 900,
    STR_WRITE_ERROR = 901,
    STR_DISK_FULL = 902,
    STR_PERMISSION_DENIED = 903,

    // Network (1000-1099)
    NET_CONNECT_FAILED = 1000,
    NET_TIMEOUT = 1001,
    NET_PROTOCOL_ERROR = 1002,

    // Auth (1100-1199)
    AUTH_MISSING_KEY = 1100,
    AUTH_INVALID_KEY = 1101,
    AUTH_FORBIDDEN = 1102,

    // Internal (1200-1299)
    INT_UNEXPECTED_EXCEPTION = 1200,
    INT_CORRUPTED_STATE = 1201
};

[[nodiscard]] constexpr const char* stable_error_code_to_string(StableErrorCode code) noexcept {
    switch (code) {
        case StableErrorCode::VAL_INVALID_INPUT: return "VAL_INVALID_INPUT";
        case StableErrorCode::VAL_MISSING_REQUIRED_FIELD: return "VAL_MISSING_REQUIRED_FIELD";
        case StableErrorCode::VAL_CORRUPT_PACKAGE: return "VAL_CORRUPT_PACKAGE";
        case StableErrorCode::VAL_INVALID_FORMAT: return "VAL_INVALID_FORMAT";
        case StableErrorCode::VAL_ILLEGAL_ARGUMENT: return "VAL_ILLEGAL_ARGUMENT";

        case StableErrorCode::CAP_UNSUPPORTED_OPERATION: return "CAP_UNSUPPORTED_OPERATION";
        case StableErrorCode::CAP_BACKEND_UNAVAILABLE: return "CAP_BACKEND_UNAVAILABLE";
        case StableErrorCode::CAP_PRECISION_UNSUPPORTED: return "CAP_PRECISION_UNSUPPORTED";
        case StableErrorCode::CAP_DEVICE_FALLBACK_REQUIRED: return "CAP_DEVICE_FALLBACK_REQUIRED";

        case StableErrorCode::MDL_NOT_FOUND: return "MDL_NOT_FOUND";
        case StableErrorCode::MDL_CHECKSUM_MISMATCH: return "MDL_CHECKSUM_MISMATCH";
        case StableErrorCode::MDL_LOAD_FAILED: return "MDL_LOAD_FAILED";
        case StableErrorCode::MDL_CORRUPT_BUNDLE: return "MDL_CORRUPT_BUNDLE";
        case StableErrorCode::MDL_MISSING_REQUIRED_BINARY: return "MDL_MISSING_REQUIRED_BINARY";
        case StableErrorCode::MDL_INCOMPATIBLE_ARCHITECTURE: return "MDL_INCOMPATIBLE_ARCHITECTURE";

        case StableErrorCode::RT_OUT_OF_MEMORY: return "RT_OUT_OF_MEMORY";
        case StableErrorCode::RT_INITIALIZATION_FAILED: return "RT_INITIALIZATION_FAILED";
        case StableErrorCode::RT_CRASHED: return "RT_CRASHED";
        case StableErrorCode::RT_DEVICE_LOST: return "RT_DEVICE_LOST";
        case StableErrorCode::RT_UNHEALTHY: return "RT_UNHEALTHY";
        case StableErrorCode::RT_CONCURRENT_LIMIT_EXCEEDED: return "RT_CONCURRENT_LIMIT_EXCEEDED";

        case StableErrorCode::NAT_DSO_LOAD_FAILED: return "NAT_DSO_LOAD_FAILED";
        case StableErrorCode::NAT_SYMBOL_NOT_FOUND: return "NAT_SYMBOL_NOT_FOUND";
        case StableErrorCode::NAT_EXECUTION_FAULT: return "NAT_EXECUTION_FAULT";
        case StableErrorCode::NAT_ABORTED: return "NAT_ABORTED";
        case StableErrorCode::NAT_JNI_CALL_FAILED: return "NAT_JNI_CALL_FAILED";

        case StableErrorCode::REQ_INVALID_STATE: return "REQ_INVALID_STATE";
        case StableErrorCode::REQ_DUPLICATE_TERMINAL: return "REQ_DUPLICATE_TERMINAL";
        case StableErrorCode::REQ_STALE_CALLBACK: return "REQ_STALE_CALLBACK";
        case StableErrorCode::REQ_REJECTED: return "REQ_REJECTED";
        case StableErrorCode::REQ_NOT_FOUND: return "REQ_NOT_FOUND";

        case StableErrorCode::CNC_USER_CANCELLED: return "CNC_USER_CANCELLED";
        case StableErrorCode::CNC_DRAIN_TIMEOUT: return "CNC_DRAIN_TIMEOUT";
        case StableErrorCode::CNC_ALREADY_CANCELLED: return "CNC_ALREADY_CANCELLED";

        case StableErrorCode::TIM_LOAD_TIMEOUT: return "TIM_LOAD_TIMEOUT";
        case StableErrorCode::TIM_EXECUTION_TIMEOUT: return "TIM_EXECUTION_TIMEOUT";
        case StableErrorCode::TIM_DRAIN_TIMEOUT: return "TIM_DRAIN_TIMEOUT";
        case StableErrorCode::TIM_HEARTBEAT_EXPIRED: return "TIM_HEARTBEAT_EXPIRED";

        case StableErrorCode::STR_READ_ERROR: return "STR_READ_ERROR";
        case StableErrorCode::STR_WRITE_ERROR: return "STR_WRITE_ERROR";
        case StableErrorCode::STR_DISK_FULL: return "STR_DISK_FULL";
        case StableErrorCode::STR_PERMISSION_DENIED: return "STR_PERMISSION_DENIED";

        case StableErrorCode::NET_CONNECT_FAILED: return "NET_CONNECT_FAILED";
        case StableErrorCode::NET_TIMEOUT: return "NET_TIMEOUT";
        case StableErrorCode::NET_PROTOCOL_ERROR: return "NET_PROTOCOL_ERROR";

        case StableErrorCode::AUTH_MISSING_KEY: return "AUTH_MISSING_KEY";
        case StableErrorCode::AUTH_INVALID_KEY: return "AUTH_INVALID_KEY";
        case StableErrorCode::AUTH_FORBIDDEN: return "AUTH_FORBIDDEN";

        case StableErrorCode::INT_UNEXPECTED_EXCEPTION: return "INT_UNEXPECTED_EXCEPTION";
        case StableErrorCode::INT_CORRUPTED_STATE: return "INT_CORRUPTED_STATE";
    }
    return "UNKNOWN_ERROR";
}

} // namespace contract
} // namespace mca

#endif // MCA_CONTRACT_STABLE_ERROR_CODE_HPP
