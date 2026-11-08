package com.muyuchat.core.designsystem.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the appearance choice that the section renders.
 *
 * These are the assertions that keep a future search index and the rendered rows reading one source:
 * every label is unique (so a search hit is unambiguous), the wire names are stable and lower case (so a
 * stored preference keeps working across renames) and the fallback is the follow-the-system mode (so a
 * corrupt preference can never pin a user into a scheme they cannot read).
 */
class McaAppearanceThemeChoiceTest {

    @Test
    fun `every choice has a non blank label`() {
        McaAppearanceThemeChoice.entries.forEach { choice ->
            assertTrue("label of $choice must not be blank", choice.label.isNotBlank())
            assertEquals(
                "label of $choice must not carry surrounding whitespace",
                choice.label,
                choice.label.trim()
            )
        }
    }

    @Test
    fun `every choice has a distinct label`() {
        val labels = McaAppearanceThemeChoice.entries.map { it.label }

        assertEquals("two appearance rows would read identically", labels.size, labels.toSet().size)
    }

    @Test
    fun `every choice has a distinct lower case wire name`() {
        val names = McaAppearanceThemeChoice.entries.map { it.wireName }

        assertEquals("two choices share a wire name", names.size, names.toSet().size)
        names.forEach { name ->
            assertEquals("wire name $name must be normalised", name, name.lowercase())
            assertEquals("wire name $name must not need trimming", name, name.trim())
        }
    }

    @Test
    fun `the default choice follows the system`() {
        assertEquals(McaAppearanceThemeChoice.SYSTEM, McaAppearanceThemeChoice.Default)
        assertEquals(
            "the default must be the option that changes nothing for an existing user",
            McaAppearanceThemeChoice.SYSTEM,
            McaAppearanceThemeChoice.entries.first()
        )
    }

    @Test
    fun `unknown stored text falls back to the default`() {
        listOf(null, "", "  ", "purple", "darkk").forEach { dirty ->
            assertEquals(
                "stored value $dirty must not pin a mode",
                McaAppearanceThemeChoice.Default,
                McaAppearanceThemeChoice.fromWire(dirty)
            )
        }
    }

    @Test
    fun `stored text is matched case insensitively and trimmed`() {
        assertEquals(McaAppearanceThemeChoice.DARK, McaAppearanceThemeChoice.fromWire("Dark"))
        assertEquals(McaAppearanceThemeChoice.DARK, McaAppearanceThemeChoice.fromWire(" dark "))
        assertEquals(McaAppearanceThemeChoice.LIGHT, McaAppearanceThemeChoice.fromWire("LIGHT"))
    }

    @Test
    fun `every wire name round trips`() {
        McaAppearanceThemeChoice.entries.forEach { choice ->
            assertEquals(choice, McaAppearanceThemeChoice.fromWire(choice.wireName))
        }
    }

    @Test
    fun `exactly one choice is the follow the system mode`() {
        val systemLike = McaAppearanceThemeChoice.entries.filter {
            it.wireName == McaAppearanceThemeChoice.SYSTEM.wireName
        }

        assertEquals("the system-follow option must be unique", 1, systemLike.size)
    }
}
