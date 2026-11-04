package com.muyuchat.mca.dsh.overlay

import com.muyuchat.core.designsystem.McaMascot
import com.muyuchat.core.dsh.contract.DshOverlayAppearance
import com.muyuchat.core.dsh.contract.MascotId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence of the app-outside display preferences.
 *
 * The window must start from defaults on a corrupted document and must never store a value outside the
 * contract's bounds, so these are the two contracts every decode/encode path is checked against.
 */
class DshOverlaySettingsCodecTest {

    @Test
    fun roundTripsEveryPersistedField() {
        val original = DshOverlaySettings(
            enabled = true,
            appearance = DshOverlayAppearance(
                mascotId = MascotId.FATTY_FISH.id,
                customImagePath = "/data/user/0/com.muyuchat.mca/files/dsh-overlay/mascot-abc.png",
                opacity = 0.6f,
                markSizeDp = 72,
                bubbleLines = 4,
                notifyReplies = false,
                draggable = false
            ),
            position = DshOverlayPosition(xFraction = 0.25f, yFraction = 0.8f),
            streamingEnabled = false
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
    fun outOfRangeValuesAreClampedToTheContractBounds() {
        val raw = """
            {"version":1,"enabled":true,"mascot":"unknown-creature","imagePath":null,
             "opacity":4.5,"markSizeDp":900,"bubbleLines":99,"notifyReplies":true,
             "draggable":true,"x":-3.0,"y":7.5}
        """.trimIndent()

        val decoded = DshOverlaySettingsCodec.decode(raw)

        assertTrue(decoded.enabled)
        // Unknown mascot ids fall back to the whale instead of producing an unpaintable mark.
        assertEquals(MascotId.WHALE.id, decoded.appearance.mascotId)
        assertNull(decoded.appearance.customImagePath)
        assertEquals(1f, decoded.appearance.opacity, 0.0001f)
        assertEquals(DshOverlayAppearance.MAX_MARK_SIZE_DP, decoded.appearance.markSizeDp)
        assertEquals(DshOverlayAppearance.MAX_BUBBLE_LINES, decoded.appearance.bubbleLines)
        assertEquals(0f, decoded.position.xFraction, 0.0001f)
        assertEquals(1f, decoded.position.yFraction, 0.0001f)
    }

    @Test
    fun tooSmallValuesAreClampedUp() {
        val raw = """{"opacity":0.01,"markSizeDp":4,"bubbleLines":-2}"""

        val decoded = DshOverlaySettingsCodec.decode(raw)

        assertEquals(DshOverlayAppearance.MIN_OPACITY, decoded.appearance.opacity, 0.0001f)
        assertEquals(DshOverlayAppearance.MIN_MARK_SIZE_DP, decoded.appearance.markSizeDp)
        assertEquals(0, decoded.appearance.bubbleLines)
    }

    @Test
    fun notANumberOpacityDecodesToTheDefaultInsteadOfPoisoningTheWindow() {
        val decoded = DshOverlaySettingsCodec.decode("""{"opacity":"NaN"}""")

        assertEquals(DshOverlayAppearance().opacity, decoded.appearance.opacity, 0.0001f)
    }

    @Test
    fun aJsonNullImagePathIsNotTheStringNull() {
        val decoded = DshOverlaySettingsCodec.decode("""{"imagePath":null}""")

        assertNull(decoded.appearance.customImagePath)
    }

    @Test
    fun disabledDocumentKeepsTheUserOffAndPositionAtTheStoredFraction() {
        val raw = """{"enabled":false,"x":0.0,"y":0.0}"""

        val decoded = DshOverlaySettingsCodec.decode(raw)

        assertFalse(decoded.enabled)
        assertEquals(0f, decoded.position.xFraction, 0.0001f)
        assertEquals(0f, decoded.position.yFraction, 0.0001f)
    }

    @Test
    fun theStreamingSwitchDefaultsOnAndSurvivesARoundTrip() {
        assertEquals(
            DshOverlaySettings.DEFAULT_STREAMING_ENABLED,
            DshOverlaySettingsCodec.decode(null).streamingEnabled
        )
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
    fun mascotIdsMapOntoDesignSystemMarksInBothDirections() {
        MascotId.entries.forEach { id ->
            assertEquals(id, id.toMcaMascot().toMascotId())
        }
        assertEquals(McaMascot.WHALE, MascotId.fromId("WHALE").toMcaMascot())
        assertEquals(McaMascot.CUSTOM, MascotId.fromId("custom").toMcaMascot())
    }
}
