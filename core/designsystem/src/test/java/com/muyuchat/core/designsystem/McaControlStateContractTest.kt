package com.muyuchat.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI state guarantees that JVM detent tests cannot exercise without a device composition. */
class McaControlStateContractTest {
    @Test
    fun `all button styles disable the Material interaction while loading`() {
        val source = designSystemSource("McaButton.kt")
        assertTrue(source.contains("val actionable = enabled && !loading"))
        assertEquals(5, Regex("""enabled = actionable,""").findAll(source).count())
        assertFalse(source.contains("enabled = enabled,"))
        assertTrue(source.contains("mcaPressScale(source, enabled = actionable)"))
        assertTrue(source.contains("if (actionable)"))
        assertTrue(source.contains("stateDescription = \"处理中\""))
    }

    @Test
    fun `loading overlays feedback without adding width or losing the action name`() {
        val source = designSystemSource("McaButton.kt")
        val loadingBody = source.substringAfter("val loadingBody:").substringBefore("val body =")
        assertTrue(loadingBody.contains("content = buttonContent"))
        assertTrue(loadingBody.contains("drawWithContent { if (!loading) drawContent() }"))
        assertTrue(loadingBody.contains("Modifier.matchParentSize().wrapContentSize().size(McaIconSize.Xs)"))
        assertTrue(loadingBody.contains("clearAndSetSemantics { }"))
        assertFalse(loadingBody.contains("Spacer("))
        assertFalse(loadingBody.contains("alpha ="))
        assertTrue(source.contains("val body = if (loading) loadingBody else buttonContent"))
        assertTrue(source.contains("disabledContainerColor = styleColors.containerColor"))
        assertTrue(source.contains("disabledContentColor = styleColors.contentColor"))
    }

    @Test
    fun `callback owned switches can carry an action name without creating another click owner`() {
        val source = designSystemSource("McaControls.kt")
            .substringAfter("public fun McaSwitch(").substringBefore("public fun McaSlider(")
        assertTrue(source.contains("contentDescription: String? = null"))
        assertTrue(source.contains("modifier.semantics { this.contentDescription = contentDescription }"))
        assertTrue(source.contains("onCheckedChange = onCheckedChange?.let"))
        assertTrue(source.contains("haptics.toggle(value)"))
        assertFalse(source.contains(".clickable("))
        assertFalse(source.contains(".toggleable("))
    }

    @Test
    fun `loading indicator can preserve its previous color without changing disabled content`() {
        val source = designSystemSource("McaButton.kt")
        assertTrue(source.contains("loadingIndicatorColor: Color = Color.Unspecified"))
        assertTrue(source.contains("color = loadingIndicatorColor.orElse(androidx.compose.material3.LocalContentColor.current)"))
        // An indicator override belongs only to the overlay, never to normal button colors.
        val styles = source.substringAfter("val styleColors =").substringBefore("val pressModifier =")
        assertFalse(styles.contains("loadingIndicatorColor"))
        assertTrue(source.contains("enabled = actionable,"))
    }
}
