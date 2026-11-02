package com.muyuchat.core.dsh.vscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure protocol parsing: aliases, defaults, bounds and rejection branches. No Android code. */
class DshVscreenValidationTest {

    private fun codeOf(block: () -> Unit): DshVscreenErrorCode {
        val error = assertThrows(DshVscreenValidationException::class.java) { block() }
        return error.code
    }

    @Test
    fun createWithoutParametersUsesThePinnedPortraitDefault() {
        val request = DshVscreenCreateRequest.parse(emptyMap())
        assertEquals(DshVscreenOrientation.PORTRAIT, request.geometry.orientation)
        assertEquals(DshVscreenLimits.DEFAULT_PORTRAIT_WIDTH, request.geometry.width)
        assertEquals(DshVscreenLimits.DEFAULT_PORTRAIT_HEIGHT, request.geometry.height)
        assertEquals(DshVscreenLimits.DEFAULT_DPI, request.geometry.dpi)
        assertFalse(request.explicitOrientation)
    }

    @Test
    fun orientationAliasesMatchThePinnedPackage() {
        val portrait = listOf("0", "portrait", "Portrait", "vertical", "v", "p", " 0 ")
        val landscape = listOf("1", "landscape", "LANDSCAPE", "horizontal", "h", "l", " 1 ")
        portrait.forEach { raw ->
            val request = DshVscreenCreateRequest.parse(mapOf("orientation" to raw))
            assertEquals("alias=$raw", DshVscreenOrientation.PORTRAIT, request.geometry.orientation)
            assertTrue(request.explicitOrientation)
        }
        landscape.forEach { raw ->
            val request = DshVscreenCreateRequest.parse(mapOf("orientation" to raw))
            assertEquals("alias=$raw", DshVscreenOrientation.LANDSCAPE, request.geometry.orientation)
        }
    }

    @Test
    fun orientationRejectsUnknownValuesWithoutEchoingThem() {
        val error = assertThrows(DshVscreenValidationException::class.java) {
            DshVscreenCreateRequest.parse(mapOf("orientation" to "diagonal"))
        }
        assertEquals(DshVscreenErrorCode.UNSUPPORTED_ORIENTATION, error.code)
        assertFalse(error.message.orEmpty().contains("diagonal"))
    }

    @Test
    fun widthAndHeightOnlyCarryTheDirection() {
        val landscape = DshVscreenCreateRequest.parse(mapOf("width" to "1792", "height" to "1008"))
        assertEquals(DshVscreenOrientation.LANDSCAPE, landscape.geometry.orientation)
        assertEquals(1792, landscape.geometry.width)

        val portrait = DshVscreenCreateRequest.parse(mapOf("width" to "720", "height" to "1520"))
        assertEquals(DshVscreenOrientation.PORTRAIT, portrait.geometry.orientation)
        assertEquals(DshVscreenLimits.DEFAULT_PORTRAIT_WIDTH, portrait.geometry.width)
        assertEquals(DshVscreenLimits.DEFAULT_PORTRAIT_HEIGHT, portrait.geometry.height)
    }

    @Test
    fun explicitOrientationWinsOverRequestedDimensions() {
        val request = DshVscreenCreateRequest.parse(
            mapOf("orientation" to "landscape", "width" to "1008", "height" to "1792")
        )
        assertEquals(DshVscreenOrientation.LANDSCAPE, request.geometry.orientation)
        assertEquals(1792, request.geometry.width)
        assertEquals(1008, request.geometry.height)
    }

    @Test
    fun dimensionsRejectNonIntegersNegativesAndOversize() {
        listOf("-1", "4097", "abc", "1.5", "999999999").forEach { raw ->
            assertEquals(
                "width=$raw",
                DshVscreenErrorCode.INVALID_DIMENSION,
                codeOf { DshVscreenCreateRequest.parse(mapOf("width" to raw)) }
            )
        }
        assertEquals(
            DshVscreenErrorCode.INVALID_DIMENSION,
            codeOf { DshVscreenCreateRequest.parse(mapOf("height" to "999999999")) }
        )
    }

    @Test
    fun dpiDefaultsAndBounds() {
        assertEquals(DshVscreenLimits.DEFAULT_DPI, DshVscreenCreateRequest.parse(mapOf("dpi" to "0")).geometry.dpi)
        assertEquals(320, DshVscreenCreateRequest.parse(mapOf("dpi" to "320")).geometry.dpi)
        listOf("71", "961", "high").forEach { raw ->
            assertEquals(
                "dpi=$raw",
                DshVscreenErrorCode.INVALID_DPI,
                codeOf { DshVscreenCreateRequest.parse(mapOf("dpi" to raw)) }
            )
        }
    }

    @Test
    fun createRejectsUnknownQueryKeys() {
        assertEquals(
            DshVscreenErrorCode.INVALID_REQUEST,
            codeOf { DshVscreenCreateRequest.parse(mapOf("orientation" to "portrait", "fps" to "60")) }
        )
    }

    @Test
    fun keyAliasesAndNumericKeycodesNormalizeLikeThePinnedPackage() {
        assertEquals(DshVscreenKey.BACK, DshVscreenKey.parse("back"))
        assertEquals(DshVscreenKey.BACK, DshVscreenKey.parse(" BACK "))
        assertEquals(DshVscreenKey.BACK, DshVscreenKey.parse("KEYCODE_BACK"))
        assertEquals(DshVscreenKey.BACK, DshVscreenKey.parse("4"))
        assertEquals(DshVscreenKey.APP_SWITCH, DshVscreenKey.parse("187"))
        assertEquals(DshVscreenKey.VOLUME_UP, DshVscreenKey.parse("volume-up"))
        assertEquals(DshVscreenKey.HOME, DshVscreenKey.parse("3"))
    }

    @Test
    fun unknownKeyListsAcceptedNamesAndNeverEchoesTheInput() {
        val error = assertThrows(DshVscreenValidationException::class.java) {
            DshVscreenKey.parse("launch_missiles")
        }
        assertEquals(DshVscreenErrorCode.UNKNOWN_KEY, error.code)
        assertTrue(error.message.orEmpty().contains("back"))
        assertFalse(error.message.orEmpty().contains("launch_missiles"))
        assertEquals(DshVscreenErrorCode.UNKNOWN_KEY, codeOf { DshVscreenKey.parse(null) })
    }

    @Test
    fun tapCoordinatesAreBoundedAndRequired() {
        val tap = DshVscreenTapRequest.parse(mapOf("x" to "10", "y" to "20"))
        assertEquals(10, tap.x)
        assertEquals(20, tap.y)
        assertEquals(
            DshVscreenErrorCode.INVALID_COORDINATE,
            codeOf { DshVscreenTapRequest.parse(mapOf("y" to "20")) }
        )
        assertEquals(
            DshVscreenErrorCode.INVALID_COORDINATE,
            codeOf { DshVscreenTapRequest.parse(mapOf("x" to "-1", "y" to "20")) }
        )
        assertEquals(
            DshVscreenErrorCode.INVALID_COORDINATE,
            codeOf { DshVscreenTapRequest.parse(mapOf("x" to "100001", "y" to "20")) }
        )
        assertEquals(
            DshVscreenErrorCode.INVALID_COORDINATE,
            codeOf { DshVscreenTapRequest.parse(mapOf("x" to "1.5", "y" to "20")) }
        )
    }

    @Test
    fun swipeDefaultsDurationAndRejectsOutOfRangeValues() {
        val swipe = DshVscreenSwipeRequest.parse(
            mapOf("x1" to "0", "y1" to "0", "x2" to "10", "y2" to "10")
        )
        assertEquals(DshVscreenLimits.DEFAULT_SWIPE_DURATION_MILLIS, swipe.durationMillis)

        val explicit = DshVscreenSwipeRequest.parse(
            mapOf("x1" to "0", "y1" to "0", "x2" to "10", "y2" to "10", "dur" to "0")
        )
        assertEquals(0, explicit.durationMillis)

        listOf("-1", "10001", "soon").forEach { raw ->
            assertEquals(
                "dur=$raw",
                DshVscreenErrorCode.INVALID_DURATION,
                codeOf {
                    DshVscreenSwipeRequest.parse(
                        mapOf("x1" to "0", "y1" to "0", "x2" to "10", "y2" to "10", "dur" to raw)
                    )
                }
            )
        }
    }

    @Test
    fun launchAcceptsPackageNamesAndLabelsButRejectsShellFragments() {
        assertEquals("com.tencent.mm", DshVscreenLaunchRequest.parse(mapOf("pkg" to " com.tencent.mm ")).packageName)
        assertEquals("微信", DshVscreenLaunchRequest.parse(mapOf("pkg" to "微信")).packageName)
        listOf("", "com.exa;rm -rf /", "com.exa`id`", "app'name", "a\nb", "x".repeat(201)).forEach { raw ->
            assertEquals(
                "pkg=$raw",
                DshVscreenErrorCode.INVALID_PACKAGE,
                codeOf { DshVscreenLaunchRequest.parse(mapOf("pkg" to raw)) }
            )
        }
    }

    @Test
    fun seeWaitIsClampedToThePackageWindow() {
        assertEquals(0L, DshVscreenSeeRequest.parse(emptyMap()).waitMs)
        assertEquals(0L, DshVscreenSeeRequest.parse(mapOf("waitMs" to "-5")).waitMs)
        assertEquals(1_200L, DshVscreenSeeRequest.parse(mapOf("waitMs" to "1200")).waitMs)
        assertEquals(DshVscreenLimits.MAX_WAIT_MS, DshVscreenSeeRequest.parse(mapOf("waitMs" to "60000")).waitMs)
        assertTrue(DshVscreenSeeRequest.parse(mapOf("grid" to "true")).grid)
        assertFalse(DshVscreenSeeRequest.parse(mapOf("grid" to "0")).grid)
        assertEquals(
            DshVscreenErrorCode.INVALID_REQUEST,
            codeOf { DshVscreenSeeRequest.parse(mapOf("waitMs" to "soon")) }
        )
    }

    @Test
    fun geometryRejectsInconsistentOrientation() {
        assertThrows(IllegalArgumentException::class.java) {
            DshVscreenGeometry(
                width = 1792,
                height = 1008,
                dpi = 420,
                orientation = DshVscreenOrientation.PORTRAIT
            )
        }
    }

    @Test
    fun framePolicyRejectsOutOfRangeConfiguration() {
        assertThrows(IllegalArgumentException::class.java) { DshVscreenFramePolicy(maxFps = 0) }
        assertThrows(IllegalArgumentException::class.java) { DshVscreenFramePolicy(maxFps = 121) }
        assertThrows(IllegalArgumentException::class.java) { DshVscreenFramePolicy(maxFrameBytes = 0) }
        assertEquals(34L, DshVscreenFramePolicy(maxFps = 30).minIntervalMillis)
        assertEquals(1_000L, DshVscreenFramePolicy(maxFps = 1).minIntervalMillis)
        assertEquals(9L, DshVscreenFramePolicy(maxFps = 120).minIntervalMillis)
    }

    @Test
    fun framePolicyAdmitsFirstFrameThenDropsRapidFramesAndRejectsOversize() {
        val policy = DshVscreenFramePolicy(maxFps = 30, maxFrameBytes = 32)
        assertEquals(DshVscreenFrameAdmission.Deliver, policy.admit(testFrame(), nowMillis = 1_000L, lastDeliveredAtMillis = null))
        assertTrue(policy.admit(testFrame(), nowMillis = 1_010L, lastDeliveredAtMillis = 1_000L) is DshVscreenFrameAdmission.Drop)
        assertEquals(DshVscreenFrameAdmission.Deliver, policy.admit(testFrame(), nowMillis = 1_034L, lastDeliveredAtMillis = 1_000L))

        val tooLarge = policy.admit(testFrame(sizeBytes = 33), nowMillis = 2_000L, lastDeliveredAtMillis = null)
        assertTrue(tooLarge is DshVscreenFrameAdmission.Reject)
        assertEquals(DshVscreenErrorCode.FRAME_TOO_LARGE, (tooLarge as DshVscreenFrameAdmission.Reject).code)

        val wrongGeometry = policy.admit(testFrame(width = 0), nowMillis = 2_000L, lastDeliveredAtMillis = null)
        assertTrue(wrongGeometry is DshVscreenFrameAdmission.Reject)
        assertEquals(DshVscreenErrorCode.INVALID_FRAME, (wrongGeometry as DshVscreenFrameAdmission.Reject).code)

        val empty = policy.admit(testFrame(sizeBytes = 0), nowMillis = 2_000L, lastDeliveredAtMillis = null)
        assertTrue(empty is DshVscreenFrameAdmission.Reject)
        assertEquals(DshVscreenErrorCode.INVALID_FRAME, (empty as DshVscreenFrameAdmission.Reject).code)
    }

    @Test
    fun frameCacheTracksAgeAndClears() {
        val cache = DshVscreenFrameCache()
        assertEquals(null, cache.last(1_000L))
        val frame = testFrame()
        cache.store(frame, 1_000L)
        assertEquals(frame, cache.last(1_100L)?.frame)
        assertEquals(100L, cache.last(1_100L)?.ageMillis)
        cache.clear()
        assertEquals(null, cache.last(1_100L))
    }
}
