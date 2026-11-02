package com.muyuchat.core.dsh.contract.capability

/**
 * One typed capability request.
 *
 * [generation] binds the request to a runtime epoch. A request from another generation is
 * rejected before any platform work starts. [reason] is a short user-facing explanation shown by
 * the app consent surface; it must never contain a token, a raw URI or private content.
 */
sealed interface DshCapabilityRequest {
    val requestId: DshCapabilityRequestId
    val generation: Long
    val reason: String
    val capability: DshCapability
}

/** Ask the user to select one or more documents through the system picker. */
data class DshPickFilesRequest(
    override val requestId: DshCapabilityRequestId,
    override val generation: Long,
    override val reason: String,
    val acceptMimeTypes: List<String> = listOf("*/*"),
    val allowMultiple: Boolean = false,
    val maxBytesPerFile: Long = DshCapabilityLimits.MAX_STAGED_FILE_BYTES
) : DshCapabilityRequest {
    init {
        requireCapabilityGeneration(generation)
        requireCapabilityReason(reason)
        require(acceptMimeTypes.isNotEmpty() && acceptMimeTypes.size <= DshCapabilityLimits.MAX_MIME_FILTERS) {
            "pick request must carry 1..${DshCapabilityLimits.MAX_MIME_FILTERS} MIME filters"
        }
        require(acceptMimeTypes.all { DshMimeTypes.isFilter(it) }) {
            "every pick MIME filter must be type/subtype, type/* or */*"
        }
        require(maxBytesPerFile in 1L..DshCapabilityLimits.MAX_STAGED_FILE_BYTES) {
            "maxBytesPerFile must be 1..${DshCapabilityLimits.MAX_STAGED_FILE_BYTES}"
        }
    }

    override val capability: DshCapability
        get() = DshCapability.FILE_PICK
}

/** Ask the user to save a workspace file or a small inline payload through the system picker. */
data class DshSaveFileRequest(
    override val requestId: DshCapabilityRequestId,
    override val generation: Long,
    override val reason: String,
    val source: DshSaveSource,
    val suggestedName: String,
    val mimeType: String
) : DshCapabilityRequest {
    init {
        requireCapabilityGeneration(generation)
        requireCapabilityReason(reason)
        require(DshWorkspacePaths.isValidDisplayName(suggestedName)) {
            "save suggestedName is invalid"
        }
        require(DshMimeTypes.isConcrete(mimeType)) {
            "save MIME type must be a concrete type/subtype"
        }
    }

    override val capability: DshCapability
        get() = DshCapability.FILE_SAVE
}

/** Source of a save. The broker never accepts an arbitrary absolute app-private path. */
sealed interface DshSaveSource {
    /** A file already inside the DSH workspace; the path is parsed by the strict path contract. */
    data class WorkspaceFile(val relativePath: DshWorkspaceRelativePath) : DshSaveSource

    /**
     * A small inline payload. The content is copied on construction and is deliberately absent
     * from [toString] so an accidental log line cannot dump user data.
     */
    class InlineBytes(bytes: ByteArray) : DshSaveSource {
        init {
            require(bytes.size.toLong() <= DshCapabilityLimits.MAX_INLINE_BYTES) {
                "inline save payload exceeds ${DshCapabilityLimits.MAX_INLINE_BYTES} bytes"
            }
        }

        private val content: ByteArray = bytes.copyOf()

        val sizeBytes: Int
            get() = content.size

        fun copyBytes(): ByteArray = content.copyOf()

        override fun equals(other: Any?): Boolean =
            other is InlineBytes && content.contentEquals(other.content)

        override fun hashCode(): Int = content.contentHashCode()

        override fun toString(): String = "InlineBytes(sizeBytes=$sizeBytes)"
    }
}

/** Ask the broker to post a bounded notification with an optional same-origin DSH deep link. */
data class DshNotifyRequest(
    override val requestId: DshCapabilityRequestId,
    override val generation: Long,
    override val reason: String,
    val title: String,
    val body: String,
    val ongoing: Boolean = false,
    /** Same-origin DSH path only, for example `/sessions/123`. The broker builds the PendingIntent. */
    val deepLinkPath: String? = null
) : DshCapabilityRequest {
    init {
        requireCapabilityGeneration(generation)
        requireCapabilityReason(reason)
        require(title.isNotBlank() && title.length <= DshCapabilityLimits.MAX_NOTIFICATION_TITLE_CHARS) {
            "notification title must be 1..${DshCapabilityLimits.MAX_NOTIFICATION_TITLE_CHARS} characters"
        }
        require(title.none(::isControlCharacter)) {
            "notification title must be a single prompt-free line"
        }
        require(body.length <= DshCapabilityLimits.MAX_NOTIFICATION_BODY_CHARS) {
            "notification body exceeds ${DshCapabilityLimits.MAX_NOTIFICATION_BODY_CHARS} characters"
        }
        require(body.none { it != '\n' && it != '\t' && isControlCharacter(it) }) {
            "notification body contains a forbidden control character"
        }
        require(deepLinkPath == null || isValidDeepLinkPath(deepLinkPath)) {
            "notification deep link must be a same-origin absolute DSH path"
        }
    }

    override val capability: DshCapability
        get() = DshCapability.NOTIFICATION
}

/** Ask for one narrowly typed accessibility/device action. */
data class DshDeviceActionRequest(
    override val requestId: DshCapabilityRequestId,
    override val generation: Long,
    override val reason: String,
    val action: DshDeviceAction,
    /** Optional target check; an action on another package/window is rejected as TARGET_CHANGED. */
    val expectedPackage: String? = null,
    val expectedWindowId: Int? = null
) : DshCapabilityRequest {
    init {
        requireCapabilityGeneration(generation)
        requireCapabilityReason(reason)
        require(expectedPackage == null || isValidAndroidPackageName(expectedPackage)) {
            "expectedPackage is not a valid Android package name"
        }
        require(expectedWindowId == null || expectedWindowId >= 0) {
            "expectedWindowId must be non-negative"
        }
    }

    override val capability: DshCapability
        get() = DshCapability.DEVICE_ACTION
}

/** The closed set of V1 device actions. No generic gesture, shell or Intent dispatch. */
sealed interface DshDeviceAction {
    data object ReadScreen : DshDeviceAction

    data class Tap(val x: Int, val y: Int) : DshDeviceAction {
        init {
            requireCoordinates(x, y)
        }
    }

    data class Swipe(
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int,
        val durationMillis: Long
    ) : DshDeviceAction {
        init {
            requireCoordinates(fromX, fromY)
            requireCoordinates(toX, toY)
            require(durationMillis in 0L..DshCapabilityLimits.MAX_GESTURE_DURATION_MILLIS) {
                "swipe duration exceeds the contract limit"
            }
        }
    }

    data class PressKey(val key: DshDeviceKey) : DshDeviceAction

    /** Typed text is retained for the adapter but deliberately redacted from [toString]. */
    class TypeText(value: String) : DshDeviceAction {
        init {
            require(value.length in 1..DshCapabilityLimits.MAX_TYPE_TEXT_CHARS) {
                "typed text must be 1..${DshCapabilityLimits.MAX_TYPE_TEXT_CHARS} characters"
            }
            require(value.none { it == '\u0000' }) {
                "typed text contains a NUL character"
            }
        }

        val text: String = value

        override fun equals(other: Any?): Boolean = other is TypeText && text == other.text

        override fun hashCode(): Int = text.hashCode()

        override fun toString(): String = "TypeText(length=${text.length}, redacted=true)"
    }

    data object Screenshot : DshDeviceAction
}

enum class DshDeviceKey {
    BACK,
    HOME,
    RECENTS,
    ENTER,
    MENU
}

/** Cancels one pending request id inside one generation. */
data class DshCapabilityCancelRequest(
    val requestId: DshCapabilityRequestId,
    val generation: Long
) {
    init {
        requireCapabilityGeneration(generation)
    }
}

internal fun requireCapabilityGeneration(generation: Long) {
    require(generation >= 0L) {
        "capability generation must be non-negative"
    }
}

internal fun requireCapabilityReason(reason: String) {
    require(reason.isNotBlank() && reason.length <= DshCapabilityLimits.MAX_REASON_CHARS) {
        "capability reason must be 1..${DshCapabilityLimits.MAX_REASON_CHARS} characters"
    }
    require(reason.none(::isControlCharacter)) {
        "capability reason must be a prompt-free single line"
    }
}

internal fun isValidDeepLinkPath(path: String): Boolean {
    if (path.length > DshCapabilityLimits.MAX_DEEP_LINK_CHARS) return false
    if (!path.startsWith("/") || path.startsWith("//")) return false
    if (path.contains('\\') || path.contains("://")) return false
    if (path.any(::isControlCharacter)) return false
    return path.split('/').none { it == "." || it == ".." }
}

internal fun isValidAndroidPackageName(value: String): Boolean {
    if (value.length > DshCapabilityLimits.MAX_PACKAGE_NAME_CHARS) return false
    val segments = value.split('.')
    if (segments.size < 2) return false
    return segments.all { segment ->
        segment.isNotEmpty() &&
            (segment.first() in 'A'..'Z' || segment.first() in 'a'..'z' || segment.first() == '_') &&
            segment.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' }
    }
}

private fun requireCoordinates(x: Int, y: Int) {
    require(x in 0..DshCapabilityLimits.MAX_COORDINATE && y in 0..DshCapabilityLimits.MAX_COORDINATE) {
        "device action coordinates exceed the contract limit"
    }
}
