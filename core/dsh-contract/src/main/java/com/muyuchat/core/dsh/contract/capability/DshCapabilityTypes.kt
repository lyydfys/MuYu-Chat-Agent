package com.muyuchat.core.dsh.contract.capability

/**
 * Stable capability families of the DSH Android bridge.
 *
 * V1 was deliberately narrow. V2 (completion-v2 D15-D19) adds the five families of the pinned
 * mobile reference's official tool packages (`dsh-tool-android`, `dsh-tool-shizuku`,
 * `dsh-tool-accessibility`, `dsh-tool-vscreen`, `dsh-tool-browser`). Each family is still a typed
 * surface with a fixed action list; there is no generic exec, arbitrary Intent, reflection or
 * raw-file escape hatch beyond the explicitly privileged shell family, whose broad access is
 * reported to the user rather than hidden.
 */
enum class DshCapability {
    FILE_PICK,
    FILE_SAVE,
    NOTIFICATION,
    DEVICE_ACTION,

    /** `dsh-tool-android`: package/app/setting/input/screenshot/usage/overlay/clipboard. */
    ANDROID_SYSTEM,

    /** `dsh-tool-shizuku`: user-consented privileged shell through root or Shizuku. */
    PRIVILEGED_SHELL,

    /** `dsh-tool-accessibility`: read screen, gesture, input and screenshot through the a11y service. */
    ACCESSIBILITY,

    /** `dsh-tool-vscreen`: privileged virtual display lifecycle and frames. */
    VIRTUAL_SCREEN,

    /** `dsh-tool-browser`: independent browser process, structured DOM and stable refs. */
    BROWSER,

    /**
     * App-owned workspace terminal: the real kernel PTY (`libdshpty.so`) behind the official
     * `terminal_*` tools and the workspace shell.
     *
     * This is deliberately not [PRIVILEGED_SHELL]: a PTY child runs as the App uid through the
     * closure's shell, while `PRIVILEGED_SHELL` means root or a Shizuku `rish` channel. A family
     * that can only offer a pipe is reported NOT_ENABLED with its concrete reason, never granted.
     */
    WORKSPACE_TERMINAL,

    /**
     * Persistent SAF tree grant: the user picked one directory through the system
     * `OpenDocumentTree` picker and the app persisted it with `takePersistableUriPermission`.
     *
     * This family is separate from [FILE_PICK]/[FILE_SAVE] on purpose: those hand over one
     * document (or receive one saved file) without granting any directory. Only this family may
     * report [DshWorkspaceTreeGrantState.GRANTED], and DSH must not register a workspace tool from
     * a chooser pick or a download.
     */
    WORKSPACE_TREE
}

/** Why a capability is or is not usable right now. */
enum class DshCapabilityState {
    /** Declared, permitted and currently usable. DSH may register a tool only in this state. */
    GRANTED,

    /** The user or the app-level owner denied this capability for the current generation. */
    DENIED,

    /** The app does not declare the required platform capability. */
    NOT_DECLARED,

    /** Declared and permitted by the user, but the platform service is disabled. */
    NOT_ENABLED,

    /** This build or platform has no implementation for the capability. */
    UNSUPPORTED;

    val isUsable: Boolean
        get() = this == GRANTED
}

/** Prompt-free capability status. Never carries a token, a raw URI or an app-private path. */
data class DshCapabilityStatus(
    val capability: DshCapability,
    val state: DshCapabilityState,
    val detail: String? = null
) {
    /** True only when DSH may register/use the capability. */
    val isUsable: Boolean
        get() = state.isUsable

    init {
        require(detail == null || detail.length <= DshCapabilityLimits.MAX_DETAIL_CHARS) {
            "capability detail must be at most ${DshCapabilityLimits.MAX_DETAIL_CHARS} characters"
        }
        require(detail == null || detail.none(::isControlCharacter)) {
            "capability detail must be a prompt-free single line"
        }
    }
}

/**
 * Idempotency identity of one capability call inside one runtime generation.
 *
 * The server treats a request id as executed at most once per generation. A duplicate with a
 * terminal outcome replays that outcome; a duplicate with an unknown outcome is rejected as
 * `OUTCOME_UNKNOWN` and is never re-executed.
 */
@JvmInline
value class DshCapabilityRequestId(val value: String) {
    init {
        require(value.length in 16..64 && value.all(::isRequestIdCharacter)) {
            "DSH capability request id must be 16..64 URL-safe characters"
        }
    }
}

/**
 * Opaque identity of a SAF document. Never a `content://` URI, an absolute path or a file
 * descriptor; the broker keeps the real platform handle private.
 */
@JvmInline
value class DshDocumentRef(val value: String) {
    init {
        require(value.length in 8..128 && value.all(::isDocumentRefCharacter)) {
            "DSH document reference must be an opaque 8..128 character token"
        }
    }
}

/**
 * A file copied by the broker into the DSH workspace staging area, ready for Node tools to read.
 *
 * [workspaceRelativePath] is relative to `DSH_WORKSPACE` and must stay under
 * `inbox/<generation>/<requestId>/`. The broker remains responsible for the final canonical-path
 * and symlink checks against the real workspace root.
 */
data class DshStagedFile(
    val document: DshDocumentRef,
    val workspaceRelativePath: DshWorkspaceRelativePath,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String? = null
) {
    init {
        require(DshWorkspacePaths.isStagingPath(workspaceRelativePath)) {
            "staged file must stay under inbox/<generation>/<requestId>/"
        }
        require(DshWorkspacePaths.isValidDisplayName(displayName)) {
            "staged file display name is invalid"
        }
        require(DshMimeTypes.isConcrete(mimeType)) {
            "staged file MIME type must be a concrete type/subtype"
        }
        require(sizeBytes in 0L..DshCapabilityLimits.MAX_STAGED_FILE_BYTES) {
            "staged file size exceeds the contract limit"
        }
        require(sha256 == null || isSha256Hex(sha256)) {
            "staged file sha256 must be 64 lowercase hexadecimal characters"
        }
    }
}

/** A prompt-free, secret-free failure. Never carries the capability token or a raw URI. */
data class DshCapabilityFailure(
    val code: DshCapabilityErrorCode,
    val capability: DshCapability,
    val message: String,
    val retryable: Boolean = false
) {
    init {
        require(message.isNotBlank() && message.length <= DshCapabilityLimits.MAX_FAILURE_MESSAGE_CHARS) {
            "capability failure message must be 1..${DshCapabilityLimits.MAX_FAILURE_MESSAGE_CHARS} characters"
        }
        require(message.none(::isControlCharacter)) {
            "capability failure message must be a prompt-free single line"
        }
    }
}

/**
 * Stable failure families of the capability bridge.
 *
 * [OUTCOME_UNKNOWN] is fail-closed: a request whose terminal outcome was lost must not be
 * replayed, because replay could post a second notification or repeat a device action.
 */
enum class DshCapabilityErrorCode {
    /** The request belongs to an older runtime generation; its token is dead. */
    STALE_GENERATION,

    /** The presented `dshc_` credential did not match the active generation. */
    UNAUTHORIZED,

    /** The request is malformed or violates a contract limit. */
    INVALID_REQUEST,

    /** The user dismissed the system picker or cancelled the action. */
    USER_CANCELLED,

    /** A user-mediated capability needs visible app UI, but none is available. */
    USER_PRESENCE_REQUIRED,

    /** The user or the platform denied the required runtime permission. */
    PERMISSION_DENIED,

    /** The platform service exists but is disabled (for example an accessibility service). */
    NOT_ENABLED,

    /** The capability is not granted or not implemented for this generation. */
    CAPABILITY_UNAVAILABLE,

    /** A per-generation rate or concurrency limit was reached. */
    RATE_LIMITED,

    /** Input or output exceeds a contract size limit. */
    TOO_LARGE,

    /** The bounded interaction timeout elapsed. */
    TIMEOUT,

    /** The target package, window or screen changed before the action could run. */
    TARGET_CHANGED,

    /** A duplicate request is still pending; the original call owns the result. */
    REQUEST_IN_PROGRESS,

    /** No terminal outcome is known. The request must not be replayed. */
    OUTCOME_UNKNOWN,

    /** A defect in the platform adapter. */
    INTERNAL
}

/** Frozen V1 limits shared by the contract, the broker and the DSH-side adapter. */
object DshCapabilityLimits {
    const val MAX_INLINE_BYTES = 1_048_576L
    const val MAX_STAGED_FILE_BYTES = 67_108_864L
    const val MAX_FILES_PER_PICK = 8
    const val MAX_REASON_CHARS = 200
    const val MAX_NOTIFICATION_TITLE_CHARS = 120
    const val MAX_NOTIFICATION_BODY_CHARS = 500
    const val MAX_SCREEN_TEXT_CHARS = 20_000
    const val MAX_MIME_FILTERS = 16
    const val MAX_MIME_TYPE_CHARS = 128
    const val MAX_DISPLAY_NAME_CHARS = 128
    const val MAX_DETAIL_CHARS = 300
    const val MAX_FAILURE_MESSAGE_CHARS = 300
    const val MAX_WORKSPACE_RELATIVE_PATH_CHARS = 240
    const val MAX_WORKSPACE_SEGMENT_CHARS = 80
    const val MAX_DEEP_LINK_CHARS = 512
    const val MAX_PACKAGE_NAME_CHARS = 200
    const val MAX_TYPE_TEXT_CHARS = 4_000
    const val MAX_COORDINATE = 100_000
    const val MAX_GESTURE_DURATION_MILLIS = 10_000L
}

/**
 * Marker implemented by every operation result. Each operation has its own sealed subtype, so a
 * wire codec or an idempotency cache can retain results without losing the typed hierarchy.
 */
interface DshCapabilityResult

internal fun isControlCharacter(character: Char): Boolean =
    character.code <= 0x1F || character.code in 0x7F..0x9F

internal fun isRequestIdCharacter(character: Char): Boolean =
    character in 'A'..'Z' ||
        character in 'a'..'z' ||
        character in '0'..'9' ||
        character == '-' ||
        character == '_'

private fun isDocumentRefCharacter(character: Char): Boolean =
    character in 'A'..'Z' ||
        character in 'a'..'z' ||
        character in '0'..'9' ||
        character == '-' ||
        character == '_'

private fun isSha256Hex(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

/** Internal MIME validation shared by requests, results and staged files. */
internal object DshMimeTypes {
    private val CONCRETE = Regex("""[A-Za-z0-9][A-Za-z0-9._+-]{0,63}/[A-Za-z0-9][A-Za-z0-9._+-]{0,63}""")
    private val FILTER = Regex("""(\*|[A-Za-z0-9][A-Za-z0-9._+-]{0,63})/(\*|[A-Za-z0-9][A-Za-z0-9._+-]{0,63})""")

    fun isConcrete(value: String): Boolean =
        value.length <= DshCapabilityLimits.MAX_MIME_TYPE_CHARS && CONCRETE.matches(value)

    fun isFilter(value: String): Boolean =
        value.length <= DshCapabilityLimits.MAX_MIME_TYPE_CHARS && FILTER.matches(value)
}
