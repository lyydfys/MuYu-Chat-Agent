package com.muyuchat.core.dsh.a11y.policy

import com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM behaviour tests for request validation. No Android class is loaded: the parser only sees the
 * path, the decoded query map and the POST body, exactly what the platform route table hands it.
 */
class A11yRequestParserTest {

    @Test
    fun statelessRoutesParseToCommands() {
        assertEquals(A11yCommand.Status, command(A11yRequestParser.parse("/status", emptyMap(), null)))
        assertEquals(A11yCommand.Dump, command(A11yRequestParser.parse("/dump", emptyMap(), null)))
        assertEquals(A11yCommand.Back, command(A11yRequestParser.parse("/back", emptyMap(), null)))
        assertEquals(A11yCommand.Home, command(A11yRequestParser.parse("/home", emptyMap(), null)))
        assertEquals(
            A11yCommand.TouchStatus,
            command(A11yRequestParser.parse("/touch-status", emptyMap(), null))
        )
        assertEquals(
            A11yCommand.TouchRelease,
            command(A11yRequestParser.parse("/touch-release", emptyMap(), null))
        )
    }

    @Test
    fun unknownRouteIsRejected() {
        val rejection = rejection(A11yRequestParser.parse("/not-a-route", emptyMap(), null))
        assertEquals(DshCapabilityErrorCode.INVALID_REQUEST, rejection.code)
    }

    @Test
    fun tapAcceptsTextDescAndCoordinates() {
        val text = command(A11yRequestParser.parse("/tap", mapOf("text" to "Settings"), null))
        assertTrue(text is A11yCommand.Tap)
        assertEquals("Settings", (text as A11yCommand.Tap).text)

        val fractional = command(
            A11yRequestParser.parse("/tap", mapOf("fx" to "0.5", "fy" to "0.25"), null)
        ) as A11yCommand.Tap
        assertEquals(A11yCoord.Fraction(0.5, 0.25), fractional.target)

        val pixels = command(
            A11yRequestParser.parse("/tap", mapOf("x" to "120.9", "y" to "340"), null)
        ) as A11yCommand.Tap
        assertEquals(A11yCoord.Pixels(120, 340), pixels.target)
    }

    @Test
    fun tapWithoutAnyTargetIsRejected() {
        val rejection = rejection(A11yRequestParser.parse("/tap", emptyMap(), null))
        assertEquals(DshCapabilityErrorCode.INVALID_REQUEST, rejection.code)
    }

    @Test
    fun tapRejectsOutOfRangeAndPartialCoordinates() {
        assertRejected("/tap", mapOf("fx" to "1.5", "fy" to "0.5"))
        assertRejected("/tap", mapOf("x" to "100001", "y" to "10"))
        assertRejected("/tap", mapOf("x" to "-1", "y" to "10"))
        assertRejected("/tap", mapOf("x" to "10"))
        assertRejected("/tap", mapOf("fx" to "0.5"))
        assertRejected("/tap", mapOf("x" to "ten", "y" to "10"))
    }

    @Test
    fun tapCapsTheMatchText() {
        val rejection = rejection(
            A11yRequestParser.parse("/tap", mapOf("text" to "a".repeat(1_500)), null)
        )
        assertEquals(DshCapabilityErrorCode.TOO_LARGE, rejection.code)
    }

    @Test
    fun tapRefIsParsedAndMalformedRefIsRejected() {
        val tap = command(
            A11yRequestParser.parse("/tap", mapOf("ref" to "3:17"), null)
        ) as A11yCommand.Tap
        assertEquals(A11yNodeRef(3L, 17), tap.ref)
        assertRejected("/tap", mapOf("ref" to "3"))
        assertRejected("/tap", mapOf("ref" to "-3:1"))
    }

    @Test
    fun targetPackageGuardIsValidated() {
        val tap = command(
            A11yRequestParser.parse(
                "/tap",
                mapOf("text" to "OK", "package" to "com.example.settings"),
                null
            )
        ) as A11yCommand.Tap
        assertEquals("com.example.settings", tap.expectedPackage)
        assertRejected("/tap", mapOf("text" to "OK", "package" to "not a package"))
    }

    @Test
    fun inputAllowsEmptyTextAndPasteMode() {
        val empty = command(A11yRequestParser.parse("/input", mapOf("text" to ""), null))
        assertTrue(empty is A11yCommand.Input)
        assertEquals("", (empty as A11yCommand.Input).text)

        val paste = command(
            A11yRequestParser.parse("/input", mapOf("text" to "hi", "mode" to "paste"), null)
        ) as A11yCommand.Input
        assertTrue(paste.paste)
    }

    @Test
    fun inputRejectsMissingOverlongAndUnknownMode() {
        assertRejected("/input", emptyMap())
        assertRejected("/input", mapOf("text" to "a".repeat(4_001)))
        assertRejected("/input", mapOf("text" to "hi", "mode" to "keyboard"))
    }

    @Test
    fun scrollRejectsUnknownDirectionAndAcceptsAllFour() {
        assertEquals(
            A11yScrollDirection.UP,
            (command(A11yRequestParser.parse("/scroll", mapOf("direction" to "up"), null)) as A11yCommand.Scroll).direction
        )
        assertEquals(
            A11yScrollDirection.DOWN,
            (command(A11yRequestParser.parse("/scroll", mapOf("direction" to "DOWN"), null)) as A11yCommand.Scroll).direction
        )
        assertRejected("/scroll", mapOf("direction" to "diagonal"))
        assertRejected("/scroll", emptyMap())
    }

    @Test
    fun screenshotGridAndDisplayAreBounded() {
        val grid4 = command(A11yRequestParser.parse("/screenshot", mapOf("grid" to "4"), null))
        assertEquals(4, (grid4 as A11yCommand.Screenshot).grid)
        val grid8 = command(A11yRequestParser.parse("/screenshot", mapOf("grid" to "8"), null))
        assertEquals(8, (grid8 as A11yCommand.Screenshot).grid)
        val defaults = command(A11yRequestParser.parse("/screenshot", emptyMap(), null))
        assertEquals(0, (defaults as A11yCommand.Screenshot).grid)
        assertRejected("/screenshot", mapOf("grid" to "16"))
        assertRejected("/screenshot", mapOf("display" to "-1"))
        assertRejected("/screenshot", mapOf("display" to "main"))
    }

    @Test
    fun swipeRequiresBothEndpointsAndBoundsDuration() {
        assertRejected("/swipe", mapOf("x1" to "10", "y1" to "10", "x2" to "20"))
        assertRejected("/swipe", emptyMap())

        val swipe = command(
            A11yRequestParser.parse(
                "/swipe",
                mapOf("fx1" to "0.5", "fy1" to "0.8", "fx2" to "0.5", "fy2" to "0.2"),
                null
            )
        ) as A11yCommand.Swipe
        assertEquals(A11yLimits.DEFAULT_SWIPE_MILLIS, swipe.durationMs)
        assertNull(swipe.finger)

        assertRejected(
            "/swipe",
            mapOf("x1" to "1", "y1" to "1", "x2" to "2", "y2" to "2", "duration" to "10001")
        )
        assertRejected(
            "/swipe",
            mapOf("x1" to "1", "y1" to "1", "x2" to "2", "y2" to "2", "finger" to "8")
        )
    }

    @Test
    fun holdDefaultsTo500msAndValidatesFinger() {
        val hold = command(A11yRequestParser.parse("/hold", mapOf("x" to "10", "y" to "20"), null))
            as A11yCommand.Hold
        assertEquals(A11yLimits.DEFAULT_HOLD_MILLIS, hold.durationMs)
        assertNull(hold.finger)

        val withFinger = command(
            A11yRequestParser.parse(
                "/hold",
                mapOf("x" to "10", "y" to "20", "duration" to "1200", "finger" to "2"),
                null
            )
        ) as A11yCommand.Hold
        assertEquals(1_200L, withFinger.durationMs)
        assertEquals(2, withFinger.finger)

        assertRejected("/hold", mapOf("fx" to "0.5"))
        assertRejected("/hold", mapOf("x" to "10", "y" to "20", "finger" to "-1"))
    }

    @Test
    fun touchRequiresActionFingerAndCoordinatesForDown() {
        val down = command(
            A11yRequestParser.parse(
                "/touch",
                mapOf("action" to "down", "finger" to "0", "fx" to "0.25", "fy" to "0.5"),
                null
            )
        ) as A11yCommand.Touch
        assertEquals(A11yTouchAction.DOWN, down.action)
        assertEquals(0, down.finger)

        val release = command(
            A11yRequestParser.parse("/touch", mapOf("action" to "release", "finger" to "1"), null)
        ) as A11yCommand.Touch
        assertEquals(A11yTouchAction.RELEASE, release.action)
        assertNull(release.target)

        assertRejected("/touch", mapOf("action" to "down", "finger" to "0"))
        assertRejected("/touch", mapOf("action" to "wiggle", "finger" to "0"))
        assertRejected("/touch", mapOf("action" to "down", "x" to "10", "y" to "10"))
    }

    @Test
    fun gestureBodyFormatsAndValidation() {
        val arrayBody = """[{"kind":"tap","x":10,"y":20}]"""
        val parsed = command(A11yRequestParser.parse("/gesture", emptyMap(), arrayBody))
        assertTrue(parsed is A11yCommand.Gesture)
        assertEquals(1, (parsed as A11yCommand.Gesture).ops.size)

        val wrapped = command(
            A11yRequestParser.parse("/gesture", emptyMap(), """{"strokes":[{"kind":"wait","ms":50}]}""")
        )
        assertTrue(wrapped is A11yCommand.Gesture)

        assertRejectedBody("/gesture", "")
        assertRejectedBody("/gesture", "{not json")
        assertRejectedBody("/gesture", "[]")
        assertRejectedBody("/gesture", """[{"kind":"explode"}]""")
        assertRejectedBody("/gesture", """[{"kind":"down","x":1,"y":1}]""")
        assertRejectedBody("/gesture", """[{"kind":"wait","ms":20000}]""")
        assertRejectedBody("/gesture", """[{"kind":"tap","fx":2,"fy":0.5}]""")
    }

    @Test
    fun gestureStrokeCountIsBounded() {
        val strokes = (0 until 11).joinToString(",") { """{"kind":"wait","ms":1}""" }
        assertRejectedBody("/gesture", "[$strokes]")
    }

    // ------------------------------------------------------------------ helpers

    private fun command(result: A11yParseResult): A11yCommand = when (result) {
        is A11yParseResult.Command -> result.command
        is A11yParseResult.Rejected -> throw AssertionError("expected a command, got ${result.rejection}")
    }

    private fun rejection(result: A11yParseResult): A11yRejection = when (result) {
        is A11yParseResult.Rejected -> result.rejection
        is A11yParseResult.Command -> throw AssertionError("expected a rejection, got ${result.command}")
    }

    private fun assertRejected(path: String, query: Map<String, String>) {
        rejection(A11yRequestParser.parse(path, query, null))
    }

    private fun assertRejectedBody(path: String, body: String) {
        rejection(A11yRequestParser.parse(path, emptyMap(), body))
    }
}
