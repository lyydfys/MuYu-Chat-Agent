package com.muyuchat.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level contract for [McaTextField]: the clear button is a property of the control, so the
 * properties that would silently break it are pinned here rather than re-verified per screen.
 *
 * A JVM unit test cannot compose, so these assertions read the component source. They are structural
 * on purpose: the behavioural half (tapping actually empties the field) needs an instrumented test,
 * and this file states that gap instead of implying coverage it does not have.
 */
class McaTextFieldContractTest {

    private val source: String by lazy { designSystemSource("McaTextField.kt") }

    @Test
    fun `the clear button is gated on content and interactivity`() {
        assertTrue(
            "the clear affordance must hide when there is nothing to clear, and stay hidden for " +
                "a disabled or read-only field",
            source.contains("val showClear = showClearButton && value.isNotEmpty() && enabled && !readOnly")
        )
    }

    @Test
    fun `clearing goes through onValueChange instead of writing state directly`() {
        assertTrue(
            "the clear action must use the same channel as the keyboard",
            source.contains("onClick = { onValueChange(\"\") }")
        )
        assertFalse(
            "the component must not own mutable text state",
            source.contains("remember { mutableStateOf(")
        )
    }

    @Test
    fun `a caller-supplied trailing icon wins over the clear button`() {
        // A password reveal toggle or unit suffix must not be replaced by a clear button.
        assertTrue(
            "trailingIcon!=null must be resolved before the clear slot",
            source.contains("trailingIcon != null -> trailingIcon")
        )
    }

    @Test
    fun `the clear action carries the shared accessible name and haptic path`() {
        assertTrue(
            "clear must be announced with the shared verb",
            source.contains("contentDescription = McaActionLabels.CLEAR")
        )
        assertTrue(
            "the clear affordance is an McaIconButton so it inherits the shared haptic and ripple",
            source.contains("McaIconButton(")
        )
    }

    @Test
    fun `read-only fields deliberately have no clear button`() {
        val readOnlyBlock = source.substringAfter("public fun McaReadOnlyTextField(")
            .substringBefore("public fun McaFieldCaption(")
        assertTrue(
            "read-only wrapper must delegate with readOnly = true",
            readOnlyBlock.contains("readOnly = true")
        )
        assertTrue(
            "a read-only field never shows the clear button (its own contract explains why)",
            readOnlyBlock.contains("enabled = true") && !readOnlyBlock.contains("showClearButton = true")
        )
    }

    @Test
    fun `the wrapper mirrors the parameter names of OutlinedTextField`() {
        // Migrating a call site is then a symbol rename, not a re-shaping of the arguments.
        val expected = listOf(
            "value: String",
            "onValueChange: (String) -> Unit",
            "enabled: Boolean",
            "readOnly: Boolean",
            "label:",
            "placeholder:",
            "leadingIcon:",
            "trailingIcon:",
            "supportingText:",
            "isError: Boolean",
            "singleLine: Boolean",
            "minLines: Int",
            "maxLines: Int",
            "visualTransformation: VisualTransformation",
            "keyboardOptions: KeyboardOptions",
            "shape:",
            "colors:"
        )
        expected.forEach { fragment ->
            assertTrue("McaTextField must expose '$fragment'", source.contains(fragment))
        }
    }

    @Test
    fun `the shared clear verb exists and is not duplicated per screen`() {
        val labels = designSystemSource("McaAccessibilityLabels.kt")
        assertTrue(labels.contains("public const val CLEAR: String = \"清空输入\""))
        assertEquals(
            "the verb must be declared exactly once in the shared vocabulary",
            1,
            Regex("""const val CLEAR: String""").findAll(labels).count()
        )
    }
}
