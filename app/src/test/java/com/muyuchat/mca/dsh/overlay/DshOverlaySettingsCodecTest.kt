package com.muyuchat.mca.dsh.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence of the app-outside display preferences.
 *
 * After the mascot feature was removed the document holds only the DSH-ball switches plus the
 * machine-owned position; these cases pin the defaults, the clamps and — importantly — that a document
 * written by the previous mascot-era version still decodes instead of resetting the user's choices.
 */
class DshOverlaySettingsCodecTest {

    @Test
    fun roundTripsEveryPersistedField() {
        val original = DshOverlaySettings(
            enabled = true,
            bubbleLines = 4,
            notifyReplies = false,
            draggable = false,
            streamingEnabled = false,
            markSizeDp = 52,
            position = DshOverlayPosition(xFraction = 0.25f, yFraction = 0.8f)
        )

        val decoded = DshOverlaySettingsCodec.decode(DshOverlaySettingsCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun defaultsAreUsedForAnEmptyOrCorruptDocument() {
        val expected = DshOverlaySettings()

        assertEquals(expected, DshOverlaySettingsCodec.decode(null))
        assertEquals(expected, DshOverlaySettingsCodec.decode(""))
        assertEquals(expected, DshOverlaySettingsCodec.decode("{"))
        assertEquals(expected, DshOverlaySettingsCodec.decode("[]"))
        assertEquals(expected, DshOverlaySettingsCodec.decode("\"text\""))
    }

    @Test
    fun theDefaultsMatchTheDocumentedBallAndBubbleBehaviour() {
        val defaults = DshOverlaySettings()

        assertFalse("an unconfigured window never opens itself", defaults.enabled)
        assertTrue(defaults.notifyReplies)
        assertTrue(defaults.draggable)
        assertTrue(defaults.streamingEnabled)
        assertEquals(DshOverlaySettings.DEFAULT_MARK_SIZE_DP, defaults.markSizeDp)
        assertEquals(48, defaults.markSizeDp)
        assertEquals(DshOverlaySettings.DEFAULT_BUBBLE_LINES, defaults.bubbleLines)
    }

    @Test
    fun outOfRangeValuesAreClampedToTheDocumentedBounds() {
        val raw = """
            {"version":2,"enabled":true,"bubbleLines":99,"notifyReplies":true,
             "draggable":true,"x":-3.0,"y":7.5,"markSizeDp":900}
        """.trimIndent()

        val decoded = DshOverlaySettingsCodec.decode(raw)

        assertTrue(decoded.enabled)
        assertEquals(DshOverlaySettings.MAX_BUBBLE_LINES, decoded.bubbleLines)
        assertEquals(DshOverlaySettings.MAX_MARK_SIZE_DP, decoded.markSizeDp)
        assertEquals(0f, decoded.position.xFraction, 0.0001f)
        assertEquals(1f, decoded.position.yFraction, 0.0001f)
    }

    @Test
    fun tooSmallValuesAreClampedUp() {
        val raw = """{"bubbleLines":-2,"markSizeDp":4}"""

        val decoded = DshOverlaySettingsCodec.decode(raw)

        assertEquals(0, decoded.bubbleLines)
        assertEquals(DshOverlaySettings.MIN_MARK_SIZE_DP, decoded.markSizeDp)
    }

    @Test
    fun aMascotEraDocumentStillDecodesAndKeepsTheLiveSwitches() {
        // Written by the previous version: mascot/image/opacity keys exist and must simply be ignored.
        val raw = """
            {"version":1,"enabled":true,"mascot":"whale_girl_mature",
             "imagePath":"/data/user/0/com.muyuchat.mca/files/dsh-overlay/mascot-abc.png",
             "opacity":0.5,"markSizeDp":72,"bubbleLines":2,"notifyReplies":false,
             "draggable":false,"x":0.2,"y":0.3}
        """.trimIndent()

        val decoded = DshOverlaySettingsCodec.decode(raw)

        assertTrue(decoded.enabled)
        assertEquals(2, decoded.bubbleLines)
        assertFalse(decoded.notifyReplies)
        assertFalse(decoded.draggable)
        assertTrue("streaming defaults on for an upgraded document", decoded.streamingEnabled)
        assertEquals(0.2f, decoded.position.xFraction, 0.0001f)
        assertEquals(0.3f, decoded.position.yFraction, 0.0001f)
    }

    @Test
    fun theStreamingSwitchDefaultsOnAndSurvivesARoundTrip() {
        assertTrue(DshOverlaySettingsCodec.decode(null).streamingEnabled)
        assertTrue(DshOverlaySettingsCodec.decode("""{"enabled":true}""").streamingEnabled)
        assertFalse(
            DshOverlaySettingsCodec.decode("""{"enabled":true,"streaming":false}""").streamingEnabled
        )
        assertFalse(
            DshOverlaySettingsCodec.decode(
                DshOverlaySettingsCodec.encode(
                    DshOverlaySettings(enabled = true, streamingEnabled = false)
                )
            ).streamingEnabled
        )
    }

    @Test
    fun aNonNumericPositionFallsBackToTheDefaultInsteadOfPoisoningTheWindow() {
        val decoded = DshOverlaySettingsCodec.decode("""{"x":"NaN","y":0.5}""")

        // org.json rejects the non-numeric string, so the field keeps its default (right edge) rather
        // than ever handing a NaN to the window.
        assertEquals(
            DshOverlayPosition.DEFAULT_X_FRACTION,
            decoded.position.xFraction,
            0.0001f
        )
        assertEquals(0.5f, decoded.position.yFraction, 0.0001f)
        assertTrue(decoded.position.xFraction.isFinite())
    }
}
