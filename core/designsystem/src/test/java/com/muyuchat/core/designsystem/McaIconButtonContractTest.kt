package com.muyuchat.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structure contract for [McaIconButton], the only shared clickable component in
 * `core:designsystem`.
 *
 * The task is deliberately not to build a new button: the existing glyph surface must stay a
 * 48 dp circular target with a tokenized 20 dp glyph, a required accessible name and the shared
 * [mcaPressScale] weight feedback. The content lambda is intentionally parameterless and the
 * accessible name is applied once on the button, so a caller cannot end up with two live
 * descriptions on the same node. Disabled buttons must downgrade both their content and any
 * selected container instead of looking available.
 *
 * Behaviour on device is covered by the app-level interaction tests once callers adopt it.
 */
class McaIconButtonContractTest {

    @Test
    fun `shared icon button uses the shared press scale with its clickable interaction source`() {
        val source = designSystemSource("McaButtons.kt")

        assertTrue(
            "McaIconButton must use the shared mcaPressScale primitive",
            Regex("""\.mcaPressScale\(interaction,\s*enabled = enabled\)""")
                .containsMatchIn(source)
        )
        assertTrue(
            "the clickable must observe the same interaction source as the press scale",
            Regex("""\.clickable\([\s\S]*?interactionSource = interaction""")
                .containsMatchIn(source)
        )
        assertTrue(
            "the ripple must be bounded to the circular button",
            Regex("""ripple\(bounded = true\)""").containsMatchIn(source)
        )
    }

    @Test
    fun `shared icon button keeps the touch target and accessible contract`() {
        val source = designSystemSource("McaButtons.kt")

        assertTrue(
            "the accessible name must be a required String, not an optional one",
            Regex("""contentDescription: String,\s*\n\s*onClick: \(\) -> Unit,""")
                .containsMatchIn(source)
        )
        assertTrue(
            "the default touch target must stay 48 dp",
            Regex("""size: Dp = 48\.dp""").containsMatchIn(source)
        )
        assertTrue(
            "the default glyph box must use the medium icon-size token",
            Regex("""glyphSize: Dp = McaIconSize\.M""").containsMatchIn(source)
        )
        assertTrue(
            "the button role must be explicit",
            Regex("""role = Role\.Button""").containsMatchIn(source)
        )
        assertTrue(
            "the accessible name must be applied on the button itself",
            Regex("""semantics\s*\{\s*this\.contentDescription = contentDescription\s*\}""")
                .containsMatchIn(source)
        )
        assertEquals(
            "the accessible name must be applied exactly once, on the button",
            1,
            Regex("""this\.contentDescription = contentDescription""").findAll(source).count()
        )

        assertTrue(
            "the content slot must not take a second description",
            Regex("""content: @Composable \(\) -> Unit""").containsMatchIn(source)
        )
        assertFalse(
            "the content lambda must not receive contentDescription again",
            source.contains("content(contentDescription")
        )
        assertTrue(
            "the shared component must delegate the glyph through content()",
            Regex("""Box\(Modifier\.size\(glyphSize\)[\s\S]*?content\(\)""")
                .containsMatchIn(source)
        )
    }

    @Test
    fun `selected state uses the existing primary palette without a new colour`() {
        val source = designSystemSource("McaButtons.kt")

        assertTrue(
            "selected container must derive from primary at 10% alpha",
            Regex(
                """selectedContainer: Color = MaterialTheme\.colorScheme\.primary\.copy\(alpha = 0\.10f\)"""
            ).containsMatchIn(source)
        )
        assertTrue(
            "selected tint must reuse the primary role",
            Regex("""selectedTint: Color = MaterialTheme\.colorScheme\.primary""")
                .containsMatchIn(source)
        )
        val compact = source.replace(Regex("""\s+"""), "")
        assertTrue(
            "the selected container must only paint while enabled and selected",
            compact.contains("selected&&enabled->selectedContainer")
        )
        assertTrue(
            "a selected tint must not survive into the disabled state",
            compact.contains("!enabled->disabledTint")
        )
        assertTrue(
            "the selected tint is still applied when enabled",
            compact.contains("selected->selectedTint")
        )
    }

    @Test
    fun `disabled state visibly degrades content and neutralises a selected container`() {
        val source = designSystemSource("McaButtons.kt")

        assertTrue(
            "disabled content must use the Material3 38% content alpha",
            Regex(
                """disabledTint: Color = MaterialTheme\.colorScheme\.onSurface\.copy\(alpha = 0\.38f\)"""
            ).containsMatchIn(source)
        )
        assertTrue(
            "disabled container must use the Material3 12% container alpha",
            Regex(
                """disabledContainer: Color = MaterialTheme\.colorScheme\.onSurface\.copy\(alpha = 0\.12f\)"""
            ).containsMatchIn(source)
        )

        val compact = source.replace(Regex("""\s+"""), "")
        assertTrue(
            "disabled must win over selected for the container",
            Regex(
                """val containerColor = when \{[\s\S]*?selected && enabled -> selectedContainer""" +
                    """[\s\S]*?selected -> disabledContainer[\s\S]*?else -> Color\.Transparent"""
            ).containsMatchIn(source)
        )
        assertTrue(
            "disabled content must replace the normal and selected tint",
            Regex(
                """val contentColor = when \{[\s\S]*?!enabled -> disabledTint""" +
                    """[\s\S]*?selected -> selectedTint[\s\S]*?else -> tint"""
            ).containsMatchIn(source)
        )

        // The two overrides are additive and sit before the trailing content slot so existing
        // named/trailing-lambda call sites keep compiling.
        val disabledTintIndex = compact.indexOf("disabledTint:Color")
        val disabledContainerIndex = compact.indexOf("disabledContainer:Color")
        val contentIndex = compact.indexOf("content:@Composable()->Unit")
        assertTrue(disabledTintIndex in 0 until contentIndex)
        assertTrue(disabledContainerIndex in 0 until contentIndex)
    }
}
