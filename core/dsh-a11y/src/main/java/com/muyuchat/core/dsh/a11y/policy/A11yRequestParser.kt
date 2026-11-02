package com.muyuchat.core.dsh.a11y.policy

import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteRequest

/**
 * Syntax/bounds policy for the 14 accessibility routes of the official `dsh-tool-accessibility`
 * wire: `/status`, `/dump`, `/tap`, `/input`, `/scroll`, `/screenshot`, `/swipe`, `/hold`,
 * `/touch`, `/touch-release`, `/touch-status`, `/gesture`, `/back`, `/home`.
 *
 * This layer is pure Kotlin. It rejects malformed input before the Android adapter is touched, and
 * it deliberately keeps the typed text out of every message: a rejection may name a field, never
 * its content.
 */
object A11yRequestParser {

    /** Route paths owned by this module. The platform route table must not add a second owner. */
    val SUPPORTED_PATHS: List<String> = listOf(
        "/status",
        "/dump",
        "/tap",
        "/input",
        "/scroll",
        "/screenshot",
        "/swipe",
        "/hold",
        "/touch",
        "/touch-release",
        "/touch-status",
        "/gesture",
        "/back",
        "/home"
    )

    fun parse(request: DshCapabilityRouteRequest): A11yParseResult =
        parse(request.path, request.query, request.bodyJson)

    fun parse(path: String, query: Map<String, String>, bodyJson: String?): A11yParseResult {
        val base = path.substringBefore('?')
        return when (base) {
            "/status" -> command(A11yCommand.Status)
            "/dump" -> command(A11yCommand.Dump)
            "/touch-status" -> command(A11yCommand.TouchStatus)
            "/touch-release" -> command(A11yCommand.TouchRelease)
            "/back" -> command(A11yCommand.Back)
            "/home" -> command(A11yCommand.Home)
            "/tap" -> parseTap(query)
            "/input" -> parseInput(query)
            "/scroll" -> parseScroll(query)
            "/screenshot" -> parseScreenshot(query)
            "/swipe" -> parseSwipe(query)
            "/hold" -> parseHold(query)
            "/touch" -> parseTouch(query)
            "/gesture" -> parseGesture(query, bodyJson)
            else -> reject("unknown accessibility route: ${base.take(64)}")
        }
    }

    private fun parseTap(query: Map<String, String>): A11yParseResult {
        val text = query["text"]?.takeIf { it.isNotBlank() }
        val desc = query["desc"]?.takeIf { it.isNotBlank() }
        if (text != null && text.length > A11yLimits.MAX_MATCH_TEXT_CHARS) {
            return tooLarge("tap text exceeds ${A11yLimits.MAX_MATCH_TEXT_CHARS} characters")
        }
        if (desc != null && desc.length > A11yLimits.MAX_MATCH_TEXT_CHARS) {
            return tooLarge("tap desc exceeds ${A11yLimits.MAX_MATCH_TEXT_CHARS} characters")
        }
        val coordinate = when (val coord = readCoordPair(query::get, "x", "y", "fx", "fy", "tap")) {
            is CoordPair.Ok -> coord.coord
            is CoordPair.Rejected -> return A11yParseResult.Rejected(coord.rejection)
        }
        val rawRef = query["ref"]?.takeIf { it.isNotBlank() }
        val ref = if (rawRef == null) {
            null
        } else {
            A11yNodeRef.parse(rawRef)
                ?: return reject("tap ref must be '<generation>:<index>' from a recent /dump")
        }
        val expectedPackage = when (val result = readPackage(query)) {
            is PackageResult.Ok -> result.value
            is PackageResult.Rejected -> return A11yParseResult.Rejected(result.rejection)
        }
        if (text == null && desc == null && coordinate == null && ref == null) {
            return reject("tap needs text, desc, x/y, fx/fy or a node ref")
        }
        return command(A11yCommand.Tap(text, desc, coordinate, ref, expectedPackage))
    }

    private fun parseInput(query: Map<String, String>): A11yParseResult {
        val text = query["text"] ?: return reject("input needs text (an empty value clears the field)")
        if (text.length > A11yLimits.MAX_INPUT_TEXT_CHARS) {
            return tooLarge("input text exceeds ${A11yLimits.MAX_INPUT_TEXT_CHARS} characters")
        }
        val mode = query["mode"].orEmpty()
        if (mode.isNotEmpty() && mode != "set" && mode != "paste") {
            return reject("input mode must be 'set' or 'paste'")
        }
        val pasteFlag = query["paste"]?.lowercase()
        val paste = mode == "paste" || pasteFlag == "true" || pasteFlag == "1"
        val expectedPackage = when (val result = readPackage(query)) {
            is PackageResult.Ok -> result.value
            is PackageResult.Rejected -> return A11yParseResult.Rejected(result.rejection)
        }
        return command(A11yCommand.Input(text, paste, expectedPackage))
    }

    private fun parseScroll(query: Map<String, String>): A11yParseResult {
        val direction = when (query["direction"]?.lowercase()) {
            "up" -> A11yScrollDirection.UP
            "down" -> A11yScrollDirection.DOWN
            "left" -> A11yScrollDirection.LEFT
            "right" -> A11yScrollDirection.RIGHT
            else -> return reject("scroll direction must be up, down, left or right")
        }
        val expectedPackage = when (val result = readPackage(query)) {
            is PackageResult.Ok -> result.value
            is PackageResult.Rejected -> return A11yParseResult.Rejected(result.rejection)
        }
        return command(A11yCommand.Scroll(direction, expectedPackage))
    }

    private fun parseScreenshot(query: Map<String, String>): A11yParseResult {
        val grid = when (val raw = query["grid"]?.trim()?.lowercase()) {
            null, "", "0", "false" -> 0
            "4", "true" -> 4
            "8" -> 8
            else -> return reject("screenshot grid must be 0, 4 or 8")
        }
        val displayId = when (val raw = query["display"]?.trim()) {
            null, "" -> 0
            else -> {
                val parsed = raw.toIntOrNull()
                    ?: return reject("screenshot display must be an integer display id")
                if (parsed !in 0..A11yLimits.MAX_DISPLAY_ID) {
                    return reject("screenshot display id is out of range")
                }
                parsed
            }
        }
        return command(A11yCommand.Screenshot(grid, displayId))
    }

    private fun parseSwipe(query: Map<String, String>): A11yParseResult {
        val from = when (val coord = readCoordPair(query::get, "x1", "y1", "fx1", "fy1", "swipe start")) {
            is CoordPair.Ok -> coord.coord ?: return reject("swipe needs a start (x1/y1 or fx1/fy1)")
            is CoordPair.Rejected -> return A11yParseResult.Rejected(coord.rejection)
        }
        val to = when (val coord = readCoordPair(query::get, "x2", "y2", "fx2", "fy2", "swipe end")) {
            is CoordPair.Ok -> coord.coord ?: return reject("swipe needs an end (x2/y2 or fx2/fy2)")
            is CoordPair.Rejected -> return A11yParseResult.Rejected(coord.rejection)
        }
        val duration = when (val parsed = readDuration(query["duration"], A11yLimits.DEFAULT_SWIPE_MILLIS, "swipe")) {
            is DurationResult.Ok -> parsed.value
            is DurationResult.Rejected -> return A11yParseResult.Rejected(parsed.rejection)
        }
        val finger = when (val parsed = readOptionalFinger(query["finger"], "swipe")) {
            is FingerResult.Ok -> parsed.value
            is FingerResult.Rejected -> return A11yParseResult.Rejected(parsed.rejection)
        }
        val expectedPackage = when (val result = readPackage(query)) {
            is PackageResult.Ok -> result.value
            is PackageResult.Rejected -> return A11yParseResult.Rejected(result.rejection)
        }
        return command(A11yCommand.Swipe(from, to, duration, finger, expectedPackage))
    }

    private fun parseHold(query: Map<String, String>): A11yParseResult {
        val target = when (val coord = readCoordPair(query::get, "x", "y", "fx", "fy", "hold")) {
            is CoordPair.Ok -> coord.coord ?: return reject("hold needs x/y or fx/fy")
            is CoordPair.Rejected -> return A11yParseResult.Rejected(coord.rejection)
        }
        val duration = when (val parsed = readDuration(query["duration"], A11yLimits.DEFAULT_HOLD_MILLIS, "hold")) {
            is DurationResult.Ok -> parsed.value
            is DurationResult.Rejected -> return A11yParseResult.Rejected(parsed.rejection)
        }
        val finger = when (val parsed = readOptionalFinger(query["finger"], "hold")) {
            is FingerResult.Ok -> parsed.value
            is FingerResult.Rejected -> return A11yParseResult.Rejected(parsed.rejection)
        }
        val expectedPackage = when (val result = readPackage(query)) {
            is PackageResult.Ok -> result.value
            is PackageResult.Rejected -> return A11yParseResult.Rejected(result.rejection)
        }
        return command(A11yCommand.Hold(target, duration, finger, expectedPackage))
    }

    private fun parseTouch(query: Map<String, String>): A11yParseResult {
        val action = when (query["action"]?.lowercase()) {
            "down" -> A11yTouchAction.DOWN
            "move" -> A11yTouchAction.MOVE
            "up" -> A11yTouchAction.UP
            "release" -> A11yTouchAction.RELEASE
            else -> return reject("touch action must be down, move, up or release")
        }
        val finger = when (val parsed = readOptionalFinger(query["finger"], "touch")) {
            is FingerResult.Ok -> parsed.value
                ?: return reject("touch needs finger 0..${A11yLimits.MAX_FINGERS - 1}")
            is FingerResult.Rejected -> return A11yParseResult.Rejected(parsed.rejection)
        }
        val coordinate = when (val coord = readCoordPair(query::get, "x", "y", "fx", "fy", "touch")) {
            is CoordPair.Ok -> coord.coord
            is CoordPair.Rejected -> return A11yParseResult.Rejected(coord.rejection)
        }
        if (coordinate == null && (action == A11yTouchAction.DOWN || action == A11yTouchAction.MOVE)) {
            return reject("touch ${action.name.lowercase()} needs x/y or fx/fy")
        }
        val expectedPackage = when (val result = readPackage(query)) {
            is PackageResult.Ok -> result.value
            is PackageResult.Rejected -> return A11yParseResult.Rejected(result.rejection)
        }
        return command(A11yCommand.Touch(action, finger, coordinate, expectedPackage))
    }

    private fun parseGesture(query: Map<String, String>, bodyJson: String?): A11yParseResult {
        val expectedPackage = when (val result = readPackage(query)) {
            is PackageResult.Ok -> result.value
            is PackageResult.Rejected -> return A11yParseResult.Rejected(result.rejection)
        }
        return when (val parsed = A11yGestureCodec.parse(bodyJson)) {
            is A11yGestureParse.Parsed -> command(A11yCommand.Gesture(parsed.ops, expectedPackage))
            is A11yGestureParse.Rejected -> A11yParseResult.Rejected(parsed.rejection)
        }
    }

    /**
     * Reads a coordinate pair from a query-like lookup. A complete fraction pair wins over pixels
     * (the reference order); a lone half of either pair is malformed.
     */
    private fun readCoordPair(
        get: (String) -> String?,
        pxX: String,
        pxY: String,
        fractionX: String,
        fractionY: String,
        what: String
    ): CoordPair {
        val fxRaw = get(fractionX)?.takeIf { it.isNotBlank() }
        val fyRaw = get(fractionY)?.takeIf { it.isNotBlank() }
        val xRaw = get(pxX)?.takeIf { it.isNotBlank() }
        val yRaw = get(pxY)?.takeIf { it.isNotBlank() }

        if ((fxRaw == null) != (fyRaw == null)) {
            return CoordPair.Rejected(A11yRejection.invalid("$what needs both $fractionX and $fractionY"))
        }
        if (fxRaw != null && fyRaw != null) {
            val fx = fxRaw.toDoubleOrNull()
            val fy = fyRaw.toDoubleOrNull()
            if (fx == null || fy == null || !fx.isFinite() || !fy.isFinite()) {
                return CoordPair.Rejected(A11yRejection.invalid("$what fractional coordinates must be numbers"))
            }
            if (fx !in 0.0..1.0 || fy !in 0.0..1.0) {
                return CoordPair.Rejected(
                    A11yRejection.invalid("$what fractional coordinates must be in 0..1")
                )
            }
            return CoordPair.Ok(A11yCoord.Fraction(fx, fy))
        }
        if ((xRaw == null) != (yRaw == null)) {
            return CoordPair.Rejected(A11yRejection.invalid("$what needs both $pxX and $pxY"))
        }
        if (xRaw != null && yRaw != null) {
            val x = xRaw.toDoubleOrNull()
            val y = yRaw.toDoubleOrNull()
            if (x == null || y == null || !x.isFinite() || !y.isFinite()) {
                return CoordPair.Rejected(A11yRejection.invalid("$what pixel coordinates must be numbers"))
            }
            if (x < 0.0 || y < 0.0) {
                return CoordPair.Rejected(
                    A11yRejection.invalid("$what pixel coordinates must be non-negative")
                )
            }
            if (x > A11yLimits.MAX_COORDINATE || y > A11yLimits.MAX_COORDINATE) {
                return CoordPair.Rejected(
                    A11yRejection.invalid("$what pixel coordinates exceed ${A11yLimits.MAX_COORDINATE}")
                )
            }
            return CoordPair.Ok(A11yCoord.Pixels(x.toInt(), y.toInt()))
        }
        return CoordPair.Ok(null)
    }

    private fun readDuration(raw: String?, default: Long, what: String): DurationResult {
        val value = raw?.takeIf { it.isNotBlank() } ?: return DurationResult.Ok(default)
        val parsed = value.toDoubleOrNull()
            ?: return DurationResult.Rejected(A11yRejection.invalid("$what duration must be a number"))
        if (!parsed.isFinite() || parsed < 0.0) {
            return DurationResult.Rejected(A11yRejection.invalid("$what duration must be non-negative"))
        }
        if (parsed > A11yLimits.MAX_GESTURE_DURATION_MILLIS) {
            return DurationResult.Rejected(
                A11yRejection.invalid("$what duration exceeds ${A11yLimits.MAX_GESTURE_DURATION_MILLIS} ms")
            )
        }
        return DurationResult.Ok(if (parsed <= 0.0) default else parsed.toLong())
    }

    private fun readOptionalFinger(raw: String?, what: String): FingerResult {
        val value = raw?.takeIf { it.isNotBlank() } ?: return FingerResult.Ok(null)
        val parsed = value.toDoubleOrNull()
            ?: return FingerResult.Rejected(A11yRejection.invalid("$what finger must be a number"))
        val finger = parsed.toInt()
        if (!parsed.isFinite() || finger.toDouble() != parsed || finger !in 0 until A11yLimits.MAX_FINGERS) {
            return FingerResult.Rejected(
                A11yRejection.invalid("$what finger must be an integer in 0..${A11yLimits.MAX_FINGERS - 1}")
            )
        }
        return FingerResult.Ok(finger)
    }

    private fun readPackage(query: Map<String, String>): PackageResult {
        val raw = query["package"]?.takeIf { it.isNotBlank() } ?: return PackageResult.Ok(null)
        if (raw.length > A11yLimits.MAX_PACKAGE_NAME_CHARS) {
            return PackageResult.Rejected(
                A11yRejection.invalid("target package name exceeds ${A11yLimits.MAX_PACKAGE_NAME_CHARS} characters")
            )
        }
        if (!PACKAGE_NAME.matches(raw)) {
            return PackageResult.Rejected(A11yRejection.invalid("target package name is not a valid Android package"))
        }
        return PackageResult.Ok(raw)
    }

    private fun command(command: A11yCommand): A11yParseResult = A11yParseResult.Command(command)

    private fun reject(message: String): A11yParseResult =
        A11yParseResult.Rejected(A11yRejection.invalid(message))

    private fun tooLarge(message: String): A11yParseResult =
        A11yParseResult.Rejected(A11yRejection.tooLarge(message))

    private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    private sealed interface CoordPair {
        data class Ok(val coord: A11yCoord?) : CoordPair
        data class Rejected(val rejection: A11yRejection) : CoordPair
    }

    private sealed interface DurationResult {
        data class Ok(val value: Long) : DurationResult
        data class Rejected(val rejection: A11yRejection) : DurationResult
    }

    private sealed interface FingerResult {
        data class Ok(val value: Int?) : FingerResult
        data class Rejected(val rejection: A11yRejection) : FingerResult
    }

    private sealed interface PackageResult {
        data class Ok(val value: String?) : PackageResult
        data class Rejected(val rejection: A11yRejection) : PackageResult
    }
}
