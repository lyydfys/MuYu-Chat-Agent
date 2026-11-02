package com.muyuchat.core.video.contract

/**
 * Error code catalogue of the video domain (proposal §4.4). Codes are a frozen wire/log
 * contract: user-facing copy may improve, but logs must keep the code, the originating stage,
 * the request identity and the original cause. Codes never carry credentials, prompts or
 * private URIs.
 */
enum class VideoErrorCode {
    // Inputs
    INPUT_INVALID,
    INPUT_MISSING,
    INPUT_TOO_LARGE,

    // Workflow document
    WORKFLOW_INVALID,
    WORKFLOW_UNSUPPORTED_NODE,
    WORKFLOW_CYCLE,
    WORKFLOW_TYPE_MISMATCH,

    // Model assets
    MODEL_MISSING,
    MODEL_CHECKSUM_MISMATCH,
    MODEL_ROLE_MISMATCH,
    MODEL_LOAD_FAILED,

    // Backend / native
    BACKEND_UNAVAILABLE,
    NATIVE_EXECUTION_FAILED,
    ACCELERATION_UNAVAILABLE,

    // Media pipeline
    ENCODER_UNAVAILABLE,
    MEDIA_VERIFY_FAILED,
    DISK_FULL,
    PUBLISH_FAILED,

    // Cancellation / interruption
    CANCEL_REQUESTED,
    CANCELLED_AFTER_NATIVE_STOP,
    INTERRUPTED_PROCESS_DEATH,

    // Remote ComfyUI backend (V3)
    REMOTE_AUTH_FAILED,
    REMOTE_TIMEOUT,
    REMOTE_UNKNOWN,
    REMOTE_CANCEL_UNSUPPORTED,
}
