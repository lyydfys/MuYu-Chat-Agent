package com.muyuchat.mca

import android.content.Context
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionFloatingStatusTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun slowDragMovesBallAndTapTogglesPanelOnce() {
        val preferences = compose.activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val savedX = preferences.getFloat("x_fraction", 1f)
        val savedY = preferences.getFloat("y_fraction", 0.70f)
        try {
            waitForBall()
            val ball = compose.onNodeWithTag(BALL_TAG)
            val before = ball.fetchSemanticsNode().boundsInRoot
            val root = compose.activity.window.decorView
            val direction = if (before.center.x > root.width / 2f) -1f else 1f
            val distance = before.width * 1.4f
            ball.performTouchInput {
                swipe(center, center + Offset(direction * distance, -before.height * 0.5f), durationMillis = 2_000L)
            }
            val after = ball.fetchSemanticsNode().boundsInRoot
            assertTrue("Slow drag must move the ball", abs(after.left - before.left) > before.width * 0.5f)
            assertInsideWindow(after, root.width.toFloat(), root.height.toFloat())
            assertFalse(compose.activity.isFinishing)
            ball.performClick()
            compose.waitUntil(5_000L) {
                compose.onNodeWithTag(PANEL_TAG).runCatching { fetchSemanticsNode() }.isSuccess
            }
            ball.performClick()
            compose.waitUntil(5_000L) {
                compose.onNodeWithTag(PANEL_TAG).runCatching { fetchSemanticsNode() }.isFailure
            }
            writeDiagnostic("slow-drag", JSONObject()
                .put("before", boundsJson(before))
                .put("after", boundsJson(after))
                .put("tapExpandedOnce", true)
                .put("mainAlive", !compose.activity.isFinishing))
        } finally {
            restorePosition(preferences, savedX, savedY)
        }
    }

    @Test
    fun fastDragClampsToAllWindowEdgesAndDoesNotStick() {
        val preferences = compose.activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val savedX = preferences.getFloat("x_fraction", 1f)
        val savedY = preferences.getFloat("y_fraction", 0.70f)
        try {
            waitForBall()
            val ball = compose.onNodeWithTag(BALL_TAG)
            val root = compose.activity.window.decorView
            val initial = ball.fetchSemanticsNode().boundsInRoot
            val rootBounds = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val safeTopLeftInRoot = Offset(rootBounds.left + 8f, rootBounds.top + 8f)
            val safeBottomRightInRoot = Offset(rootBounds.right - 8f, rootBounds.bottom - 8f)

            // Gesture coordinates are local to the ball. Convert actual root
            // endpoints to that local space and keep the finger eight pixels
            // inside the root, including on devices with a different screen size.
            ball.performTouchInput {
                swipe(center, safeTopLeftInRoot - initial.topLeft, durationMillis = 120L)
            }
            val topLeft = awaitBounds("top-left drag")
            assertInsideWindow(topLeft, root.width.toFloat(), root.height.toFloat())
            assertTrue("Fast drag must reach the left/top clamp", topLeft.left <= root.width * 0.25f &&
                topLeft.top <= root.height * 0.25f)

            ball.performTouchInput {
                swipe(center, safeBottomRightInRoot - topLeft.topLeft, durationMillis = 120L)
            }
            val bottomRight = awaitBounds("bottom-right drag")
            assertInsideWindow(bottomRight, root.width.toFloat(), root.height.toFloat())
            assertTrue("Fast drag must reach the right/bottom clamp", bottomRight.right >= root.width * 0.75f &&
                bottomRight.bottom >= root.height * 0.75f)
            assertTrue("Fast drag must move horizontally instead of sticking",
                abs(bottomRight.left - topLeft.left) > bottomRight.width * 0.8f)
            assertTrue("Fast drag must move vertically instead of sticking",
                abs(bottomRight.top - topLeft.top) > bottomRight.height * 0.8f)
            assertFalse("Fast drag must not finish the Activity", compose.activity.isFinishing)

            // Opening the panel after a corner drag verifies that the same ball
            // remains the interactive anchor and that drag did not leave a stuck
            // gesture or an invisible overlay behind it.
            ball.performClick()
            compose.waitUntil(5_000L) {
                compose.onNodeWithTag(PANEL_TAG).runCatching { fetchSemanticsNode() }.isSuccess
            }
            val panel = compose.onNodeWithTag(PANEL_TAG).fetchSemanticsNode().boundsInRoot
            assertInsideWindow(panel, root.width.toFloat(), root.height.toFloat())
            ball.performClick()
            compose.waitUntil(5_000L) {
                compose.onNodeWithTag(PANEL_TAG).runCatching { fetchSemanticsNode() }.isFailure
            }
            writeDiagnostic("fast-drag", JSONObject()
                .put("initial", boundsJson(initial))
                .put("topLeft", boundsJson(topLeft))
                .put("bottomRight", boundsJson(bottomRight))
                .put("panel", boundsJson(panel))
                .put("mainAlive", !compose.activity.isFinishing))
        } finally {
            restorePosition(preferences, savedX, savedY)
        }
    }

    private fun waitForBall() {
        compose.waitUntil(45_000L) {
            compose.onNodeWithTag(BALL_TAG).runCatching { fetchSemanticsNode() }.isSuccess
        }
    }

    private fun awaitBounds(label: String): androidx.compose.ui.geometry.Rect {
        var latest: androidx.compose.ui.geometry.Rect? = null
        compose.waitUntil(2_000L) {
            latest = compose.onNodeWithTag(BALL_TAG).fetchSemanticsNode().boundsInRoot
            latest!!.width > 0f && latest!!.height > 0f
        }
        return requireNotNull(latest) { "No bounds after $label" }
    }

    private fun assertInsideWindow(bounds: androidx.compose.ui.geometry.Rect, width: Float, height: Float) {
        assertTrue("left edge escaped window: $bounds", bounds.left >= -1f)
        assertTrue("top edge escaped window: $bounds", bounds.top >= -1f)
        assertTrue("right edge escaped window: $bounds", bounds.right <= width + 1f)
        assertTrue("bottom edge escaped window: $bounds", bounds.bottom <= height + 1f)
    }

    private fun restorePosition(preferences: android.content.SharedPreferences, x: Float, y: Float) {
        preferences.edit().putFloat("x_fraction", x).putFloat("y_fraction", y).commit()
    }

    private fun boundsJson(bounds: androidx.compose.ui.geometry.Rect): JSONObject = JSONObject()
        .put("left", bounds.left).put("top", bounds.top)
        .put("right", bounds.right).put("bottom", bounds.bottom)

    private fun writeDiagnostic(name: String, payload: JSONObject) {
        val file = File(compose.activity.filesDir, "diagnostics/repair-floating-status-$name.json")
        file.parentFile?.mkdirs()
        val packageInfo = compose.activity.packageManager.getPackageInfo(compose.activity.packageName, 0)
        file.writeText(JSONObject()
            .put("test", name)
            .put("apkVersion", packageInfo.versionName.orEmpty())
            .put("apkVersionCode", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong())
            .put("deviceModel", Build.MODEL)
            .put("payload", payload)
            .toString(2))
    }

    companion object {
        private const val PREFERENCES = "chat_device_status_overlay"
        private const val BALL_TAG = "chat-device-status-ball"
        private const val PANEL_TAG = "chat-device-status-panel"
    }
}