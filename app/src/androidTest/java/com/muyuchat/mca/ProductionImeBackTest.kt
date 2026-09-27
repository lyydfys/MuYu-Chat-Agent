package com.muyuchat.mca

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionImeBackTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun imeBackPreservesProductionComposerDraftThenAllowsNormalBack() {
        compose.waitUntil(30_000L) {
            compose.onNodeWithTag("chat.editor").runCatching { fetchSemanticsNode() }.isSuccess
        }
        val editor = compose.onNodeWithTag("chat.editor")
        val original = editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        val activity = compose.activity
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        try {
            editor.performClick().performTextReplacement("mca-ime-draft-check")
            compose.waitUntil(10_000L) {
                ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            device.pressBack()
            compose.waitUntil(10_000L) {
                ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) != true
            }
            editor.assertTextEquals("mca-ime-draft-check")
            assertFalse(activity.isFinishing)
            // Restore the user's existing draft before exercising normal navigation.
            editor.performTextReplacement(original)
            compose.runOnUiThread {
                ViewCompat.getWindowInsetsController(activity.window.decorView)
                    ?.hide(WindowInsetsCompat.Type.ime())
            }
            compose.waitUntil(10_000L) {
                ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) != true
            }
            device.pressBack()
            compose.waitUntil(10_000L) { !activity.window.decorView.hasWindowFocus() }
        } finally {
            if (activity.window.decorView.hasWindowFocus()) {
                editor.performTextReplacement(original)
            }
        }
    }
}
