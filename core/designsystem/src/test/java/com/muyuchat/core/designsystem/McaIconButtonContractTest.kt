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
 * 48 dp circular target with a 22 dp glyph, a required accessible name and the shared
 * [mcaPressScale] weight feedback. The content lambda is intentionally parameterless and the
 * accessible name is applied once on the button, so a caller cannot end up with two live
 * descriptions on the same node.
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
            "the default glyph box must stay 22 dp",
            Regex("""glyphSize: Dp = 22\.dp""").containsMatchIn(source)
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
        assertTrue(
            "the selected container must only paint while selected",
            Regex("""background\(if \(selected\) selectedContainer else Color\.Transparent\)""")
                .containsMatchIn(source)
        )
    }
}
