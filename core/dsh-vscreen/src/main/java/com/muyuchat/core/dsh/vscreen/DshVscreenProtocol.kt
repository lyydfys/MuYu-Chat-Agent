package com.muyuchat.core.dsh.vscreen

/**
 * Typed protocol layer for the eight official `dsh-tool-vscreen` actions.
 *
 * The pinned package speaks GET with a flat query string on its own bridge port:
 *
 * | action | route              | query                                   |
 * |--------|--------------------|-----------------------------------------|
 * | create | `/vscreen/create`  | `orientation,width,height,dpi`          |
 * | status | `/vscreen/status`  | -                                       |
 * | launch | `/vscreen/launch`  | `pkg`                                   |
 * | see    | `/vscreen/see`     | `waitMs,grid` (the package sends none)  |
 * | tap    | `/vscreen/tap`     | `x,y`                                   |
 * | swipe  | `/vscreen/swipe`   | `x1,y1,x2,y2,dur`                       |
 * | key    | `/vscreen/key`     | `keycode`                               |
 * | close  | `/vscreen/close`   | -                                       |
 *
 * Every parser below either returns one immutable request or throws
 * [DshVscreenValidationException] with a stable code. No parser echoes a caller-supplied string in
 * its failure message, so a malformed request can never smuggle a token into a log line.
 */
enum class DshVscreenAction(val route: String) {
    CREATE("/vscreen/create"),
    STATUS("/vscreen/status"),
    LAUNCH("/vscreen/launch"),
    SEE("/vscreen/see"),
    TAP("/vscreen/tap"),
    SWIPE("/vscreen/swipe"),
    KEY("/vscreen/key"),
    CLOSE("/vscreen/close");

    companion object {
        val routes: List<String> get() = entries.map { it.route }

        fun fromRoute(route: String): DshVscreenAction? = entries.firstOrNull { it.route == route }
    }
}

/** The only two aspect ratios the pinned server normalizes to; arbitrary sizes are not accepted. */
enum class DshVscreenOrientation(val wire: String) {
    PORTRAIT("portrait"),
    LANDSCAPE("landscape")
}

/** PNG and JPEG are the formats an ImageReader frame sink can produce without a MediaCodec. */
enum class DshVscreenFrameFormat(val mimeType: String, val extension: String) {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg")
}

/** Canonical, orientation-locked geometry of one virtual display. */
data class DshVscreenGeometry(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val orientation: DshVscreenOrientation
) {
    init {
        require(width in DshVscreenLimits.MIN_DIMENSION..DshVscreenLimits.MAX_DIMENSION) {
            "geometry width is out of range"
        }
        require(height in DshVscreenLimits.MIN_DIMENSION..DshVscreenLimits.MAX_DIMENSION) {
            "geometry height is out of range"
        }
        require(dpi in DshVscreenLimits.MIN_DPI..DshVscreenLimits.MAX_DPI) {
            "geometry dpi is out of range"
        }
        require((orientation == DshVscreenOrientation.LANDSCAPE) == (width > height)) {
            "geometry aspect does not match its orientation"
        }
    }

    companion object {
        /** 9:16 portrait or 16:9 landscape, matching the pinned package's own defaults. */
        fun canonical(orientation: DshVscreenOrientation, dpi: Int = DshVscreenLimits.DEFAULT_DPI): DshVscreenGeometry =
            when (orientation) {
                DshVscreenOrientation.PORTRAIT -> DshVscreenGeometry(
                    width = DshVscreenLimits.DEFAULT_PORTRAIT_WIDTH,
                    height = DshVscreenLimits.DEFAULT_PORTRAIT_HEIGHT,
                    dpi = dpi,
                    orientation = DshVscreenOrientation.PORTRAIT
                )

                DshVscreenOrientation.LANDSCAPE -> DshVscreenGeometry(
                    width = DshVscreenLimits.DEFAULT_LANDSCAPE_WIDTH,
                    height = DshVscreenLimits.DEFAULT_LANDSCAPE_HEIGHT,
                    dpi = dpi,
                    orientation = DshVscreenOrientation.LANDSCAPE
                )
            }
    }
}

/** `android_vscreen_create`. */
data class DshVscreenCreateRequest(
    val geometry: DshVscreenGeometry,
    val explicitOrientation: Boolean
) {
    companion object {
        val allowedKeys: Set<String> = setOf("orientation", "width", "height", "dpi")

        fun parse(query: Map<String, String>): DshVscreenCreateRequest {
            rejectUnknownKeys(query, allowedKeys)
            val orientationRaw = query["orientation"]
            val explicit = !orientationRaw.isNullOrBlank()
            val explicitOrientation = if (explicit) parseOrientation(orientationRaw) else null
            val width = parseOptionalDimension(query["width"], "width")
            val height = parseOptionalDimension(query["height"], "height")
            val dpi = parseDpi(query["dpi"])
            val derived = explicitOrientation ?: when {
                width > 0 && height > 0 && width > height -> DshVscreenOrientation.LANDSCAPE
                width > 0 && height > 0 && height > width -> DshVscreenOrientation.PORTRAIT
                else -> DshVscreenOrientation.PORTRAIT
            }
            return DshVscreenCreateRequest(
                geometry = DshVscreenGeometry.canonical(derived, dpi),
                explicitOrientation = explicit
            )
        }
    }
}

/** `android_vscreen_tap`; the owner additionally checks the point against the live display. */
data class DshVscreenTapRequest(val x: Int, val y: Int) {
    init {
        require(x in 0..DshVscreenLimits.MAX_COORDINATE) { "tap x is out of range" }
        require(y in 0..DshVscreenLimits.MAX_COORDINATE) { "tap y is out of range" }
    }

    companion object {
        val allowedKeys: Set<String> = setOf("x", "y")

        fun parse(query: Map<String, String>): DshVscreenTapRequest {
            rejectUnknownKeys(query, allowedKeys)
            return DshVscreenTapRequest(
                x = parseCoordinate(query["x"], "x"),
                y = parseCoordinate(query["y"], "y")
            )
        }
    }
}

/** `android_vscreen_swipe`. */
data class DshVscreenSwipeRequest(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val durationMillis: Int
) {
    init {
        require(x1 in 0..DshVscreenLimits.MAX_COORDINATE) { "swipe x1 is out of range" }
        require(y1 in 0..DshVscreenLimits.MAX_COORDINATE) { "swipe y1 is out of range" }
        require(x2 in 0..DshVscreenLimits.MAX_COORDINATE) { "swipe x2 is out of range" }
        require(y2 in 0..DshVscreenLimits.MAX_COORDINATE) { "swipe y2 is out of range" }
        require(
            durationMillis in DshVscreenLimits.MIN_SWIPE_DURATION_MILLIS..DshVscreenLimits.MAX_SWIPE_DURATION_MILLIS
        ) { "swipe duration is out of range" }
    }

    companion object {
        val allowedKeys: Set<String> = setOf("x1", "y1", "x2", "y2", "dur")

        fun parse(query: Map<String, String>): DshVscreenSwipeRequest {
            rejectUnknownKeys(query, allowedKeys)
            return DshVscreenSwipeRequest(
                x1 = parseCoordinate(query["x1"], "x1"),
                y1 = parseCoordinate(query["y1"], "y1"),
                x2 = parseCoordinate(query["x2"], "x2"),
                y2 = parseCoordinate(query["y2"], "y2"),
                durationMillis = parseDuration(query["dur"])
            )
        }
    }
}

/**
 * `android_vscreen_key`.
 *
 * Normalization mirrors the pinned package exactly: trim, lowercase, strip a `KEYCODE_` prefix,
 * collapse spaces/hyphens to `_`, then match the allowed names or map a numeric keycode.
 */
enum class DshVscreenKey(val wire: String, val keycode: Int) {
    BACK("back", 4),
    HOME("home", 3),
    ENTER("enter", 66),
    MENU("menu", 82),
    VOLUME_UP("volume_up", 24),
    VOLUME_DOWN("volume_down", 25),
    APP_SWITCH("app_switch", 187);

    companion object {
        val acceptedNames: List<String> = entries.map { it.wire }
        val acceptedKeycodes: List<Int> = entries.map { it.keycode }

        fun parse(raw: String?): DshVscreenKey {
            val normalized = normalize(raw)
            if (normalized.isEmpty()) {
                throw DshVscreenValidationException(
                    DshVscreenErrorCode.UNKNOWN_KEY,
                    "key is required; accepted: " + acceptedNames.joinToString("/")
                )
            }
            entries.firstOrNull { it.wire == normalized }?.let { return it }
            if (normalized.all { it in '0'..'9' }) {
                val code = normalized.toIntOrNull()
                entries.firstOrNull { it.keycode == code }?.let { return it }
            }
            throw DshVscreenValidationException(
                DshVscreenErrorCode.UNKNOWN_KEY,
                "unknown key; accepted names: " + acceptedNames.joinToString("/") +
                    "; accepted keycodes: " + acceptedKeycodes.joinToString("/")
            )
        }

        private fun normalize(raw: String?): String {
            var text = raw?.trim()?.lowercase().orEmpty()
            if (text.startsWith("keycode_")) text = text.substring("keycode_".length)
            return text.replace(Regex("[\\s-]+"), "_")
        }
    }
}

data class DshVscreenKeyRequest(val key: DshVscreenKey) {
    companion object {
        val allowedKeys: Set<String> = setOf("keycode")

        fun parse(query: Map<String, String>): DshVscreenKeyRequest {
            rejectUnknownKeys(query, allowedKeys)
            return DshVscreenKeyRequest(DshVscreenKey.parse(query["keycode"]))
        }
    }
}

/** `android_vscreen_launch`. A package name or a plain app label; never a shell fragment. */
data class DshVscreenLaunchRequest(val packageName: String) {
    init {
        require(packageName.isNotBlank()) { "app is required" }
        require(packageName.length <= DshVscreenLimits.MAX_PACKAGE_CHARS) { "app is too long" }
        require(packageName.all { isSafeAppCharacter(it) }) { "app contains unsupported characters" }
    }

    companion object {
        val allowedKeys: Set<String> = setOf("pkg")

        fun parse(query: Map<String, String>): DshVscreenLaunchRequest {
            rejectUnknownKeys(query, allowedKeys)
            val raw = query["pkg"]?.trim().orEmpty()
            if (raw.isEmpty()) {
                throw DshVscreenValidationException(
                    DshVscreenErrorCode.INVALID_PACKAGE,
                    "app is required"
                )
            }
            if (raw.length > DshVscreenLimits.MAX_PACKAGE_CHARS) {
                throw DshVscreenValidationException(
                    DshVscreenErrorCode.INVALID_PACKAGE,
                    "app must be at most ${DshVscreenLimits.MAX_PACKAGE_CHARS} characters"
                )
            }
            if (!raw.all { isSafeAppCharacter(it) }) {
                throw DshVscreenValidationException(
                    DshVscreenErrorCode.INVALID_PACKAGE,
                    "app must contain only letters, digits, spaces, '.', '_' or '-'"
                )
            }
            return DshVscreenLaunchRequest(raw)
        }

        private fun isSafeAppCharacter(character: Char): Boolean =
            character.isLetterOrDigit() || character == '.' || character == '_' ||
                character == '-' || character == ' '
    }
}

/** `android_vscreen_see`; `waitMs` is clamped to the package's own 0..5000 window. */
data class DshVscreenSeeRequest(val waitMs: Long, val grid: Boolean) {
    init {
        require(waitMs in 0..DshVscreenLimits.MAX_WAIT_MS) { "waitMs is out of range" }
    }

    companion object {
        val allowedKeys: Set<String> = setOf("waitMs", "grid")

        fun parse(query: Map<String, String>): DshVscreenSeeRequest {
            rejectUnknownKeys(query, allowedKeys)
            return DshVscreenSeeRequest(
                waitMs = parseWaitMs(query["waitMs"]),
                grid = parseBoolean(query["grid"])
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Normalization helpers
// ---------------------------------------------------------------------------------------------

fun parseOrientation(raw: String?): DshVscreenOrientation {
    val text = raw?.trim()?.lowercase().orEmpty()
    return when (text) {
        "0", "portrait", "vertical", "v", "p" -> DshVscreenOrientation.PORTRAIT
        "1", "landscape", "horizontal", "h", "l" -> DshVscreenOrientation.LANDSCAPE
        else -> throw DshVscreenValidationException(
            DshVscreenErrorCode.UNSUPPORTED_ORIENTATION,
            "orientation must be portrait/landscape or 0/1"
        )
    }
}

private val INTEGER_PATTERN = Regex("-?[0-9]{1,9}")

private fun parseOptionalDimension(raw: String?, field: String): Int {
    if (raw.isNullOrBlank()) return 0
    val text = raw.trim()
    if (!INTEGER_PATTERN.matches(text)) {
        throw DshVscreenValidationException(
            DshVscreenErrorCode.INVALID_DIMENSION,
            "$field must be an integer"
        )
    }
    val value = text.toLong()
    if (value !in 0L..DshVscreenLimits.MAX_DIMENSION.toLong()) {
        throw DshVscreenValidationException(
            DshVscreenErrorCode.INVALID_DIMENSION,
            "$field must be between 0 and ${DshVscreenLimits.MAX_DIMENSION}"
        )
    }
    return value.toInt()
}

private fun parseDpi(raw: String?): Int {
    if (raw.isNullOrBlank() || raw.trim() == "0") return DshVscreenLimits.DEFAULT_DPI
    val text = raw.trim()
    if (!INTEGER_PATTERN.matches(text)) {
        throw DshVscreenValidationException(DshVscreenErrorCode.INVALID_DPI, "dpi must be an integer")
    }
    val value = text.toLong()
    if (value !in DshVscreenLimits.MIN_DPI.toLong()..DshVscreenLimits.MAX_DPI.toLong()) {
        throw DshVscreenValidationException(
            DshVscreenErrorCode.INVALID_DPI,
            "dpi must be between ${DshVscreenLimits.MIN_DPI} and ${DshVscreenLimits.MAX_DPI}"
        )
    }
    return value.toInt()
}

private fun parseCoordinate(raw: String?, field: String): Int {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) {
        throw DshVscreenValidationException(
            DshVscreenErrorCode.INVALID_COORDINATE,
            "$field is required"
        )
    }
    if (!INTEGER_PATTERN.matches(text)) {
        throw DshVscreenValidationException(
            DshVscreenErrorCode.INVALID_COORDINATE,
            "$field must be an integer"
        )
    }
    val value = text.toLong()
    if (value !in 0L..DshVscreenLimits.MAX_COORDINATE.toLong()) {
        throw DshVscreenValidationException(
            DshVscreenErrorCode.INVALID_COORDINATE,
            "$field must be between 0 and ${DshVscreenLimits.MAX_COORDINATE}"
        )
    }
    return value.toInt()
}

private fun parseDuration(raw: String?): Int {
    if (raw.isNullOrBlank()) return DshVscreenLimits.DEFAULT_SWIPE_DURATION_MILLIS
    val text = raw.trim()
    if (!INTEGER_PATTERN.matches(text)) {
        throw DshVscreenValidationException(
            DshVscreenErrorCode.INVALID_DURATION,
            "dur must be an integer"
        )
    }
    val value = text.toLong()
    if (value !in DshVscreenLimits.MIN_SWIPE_DURATION_MILLIS.toLong()..DshVscreenLimits.MAX_SWIPE_DURATION_MILLIS.toLong()) {
        throw DshVscreenValidationException(
            DshVscreenErrorCode.INVALID_DURATION,
            "dur must be between ${DshVscreenLimits.MIN_SWIPE_DURATION_MILLIS} and ${DshVscreenLimits.MAX_SWIPE_DURATION_MILLIS} milliseconds"
        )
    }
    return value.toInt()
}

private fun parseWaitMs(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0L
    val text = raw.trim()
    if (!INTEGER_PATTERN.matches(text)) {
        throw DshVscreenValidationException(DshVscreenErrorCode.INVALID_REQUEST, "waitMs must be an integer")
    }
    val value = text.toLong()
    if (value <= 0L) return 0L
    return value.coerceAtMost(DshVscreenLimits.MAX_WAIT_MS)
}

private fun parseBoolean(raw: String?): Boolean {
    val text = raw?.trim()?.lowercase().orEmpty()
    return text == "true" || text == "1"
}

internal fun rejectUnknownKeys(query: Map<String, String>, allowed: Set<String>) {
    if (query.keys.any { it !in allowed }) {
        throw DshVscreenValidationException(
            DshVscreenErrorCode.INVALID_REQUEST,
            "unsupported query parameter for this action"
        )
    }
}
