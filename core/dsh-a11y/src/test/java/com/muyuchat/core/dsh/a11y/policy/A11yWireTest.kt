package com.muyuchat.core.dsh.a11y.policy

import com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-shape tests: every document must stay parseable by the official package and must never
 * carry typed text that policy redacted.
 */
class A11yWireTest {

    @Test
    fun statusDocumentCarriesTheReferenceFields() {
        val json = JSONObject(
            A11yWire.encode(
                A11yOutcome.Status(
                    A11yServiceStatus(
                        running = true,
                        packageName = "com.example.app",
                        nodeCount = 12,
                        apiLevel = 34,
                        canScreenshot = true,
                        windowId = 8
                    )
                )
            )
        )
        assertTrue(json.getBoolean("ok"))
        assertTrue(json.getBoolean("running"))
        assertEquals("com.example.app", json.getString("package"))
        assertEquals(12, json.getInt("nodeCount"))
        assertEquals(34, json.getInt("apiLevel"))
        assertTrue(json.getBoolean("canScreenshot"))
    }

    @Test
    fun dumpRedactsPasswordTextBeforeItReachesTheWire() {
        val snapshot = A11yTreePolicy.buildSnapshot(
            generation = 4L,
            packageName = "com.example.app",
            windowId = 2,
            drafts = listOf(
                A11yNodeDraft(
                    depth = 0,
                    text = "Login",
                    desc = "",
                    cls = "android.widget.TextView",
                    rect = A11yRect(0, 0, 100, 40),
                    clickable = false,
                    input = false,
                    checked = false,
                    selected = false,
                    scrollable = false,
                    password = false
                ),
                A11yNodeDraft(
                    depth = 1,
                    text = "hunter2",
                    desc = "password",
                    cls = "android.widget.EditText",
                    rect = A11yRect(0, 50, 100, 40),
                    clickable = false,
                    input = true,
                    checked = false,
                    selected = false,
                    scrollable = false,
                    password = true
                )
            ),
            walkTruncated = false
        )
        val document = A11yWire.encode(A11yOutcome.Dump(snapshot))
        assertFalse("password value must never be encoded", document.contains("hunter2"))
        val json = JSONObject(document)
        assertTrue(json.getBoolean("ok"))
        assertEquals(2, json.getInt("count"))
        assertEquals(4L, json.getLong("treeGeneration"))
        assertEquals(1, json.getInt("redactedCount"))
        val nodes = json.getJSONArray("nodes")
        assertEquals("", nodes.getJSONObject(1).getString("text"))
        assertEquals("", nodes.getJSONObject(1).getString("desc"))
        assertTrue(nodes.getJSONObject(1).getBoolean("redacted"))
        assertEquals("4:1", nodes.getJSONObject(1).getString("ref"))
        assertEquals("4:0", nodes.getJSONObject(0).getString("ref"))
    }

    @Test
    fun emptyDumpCarriesTheReferenceHint() {
        val snapshot = A11yTreePolicy.buildSnapshot(1L, "com.game", 1, emptyList(), false)
        val json = JSONObject(A11yWire.encode(A11yOutcome.Dump(snapshot)))
        assertEquals(0, json.getInt("count"))
        assertTrue(json.has("hint"))
        assertFalse(json.getBoolean("truncated"))
    }

    @Test
    fun rejectionUsesTheOfficialErrorFieldPlusTheMcaCode() {
        val json = JSONObject(A11yWire.failure(A11yRejection.notEnabled()))
        assertFalse(json.getBoolean("ok"))
        assertTrue(json.getString("error").contains("无障碍服务"))
        assertEquals(DshCapabilityErrorCode.NOT_ENABLED.name, json.getString("code"))
    }

    @Test
    fun inputFailureIsNotReportedAsSuccess() {
        val failed = JSONObject(
            A11yWire.encode(
                A11yOutcome.Input(A11yInputResult(focused = false, method = "none", error = "rejected"))
            )
        )
        assertFalse("a rejected input must not claim ok", failed.getBoolean("ok"))
        assertEquals("rejected", failed.getString("error"))

        val accepted = JSONObject(
            A11yWire.encode(A11yOutcome.Input(A11yInputResult(true, "paste", null)))
        )
        assertTrue(accepted.getBoolean("ok"))
        assertEquals("paste", accepted.getString("method"))
    }

    @Test
    fun tapNotFoundStaysOkTrueSoTheOfficialPackageCanListCandidates() {
        val json = JSONObject(
            A11yWire.encode(
                A11yOutcome.Tap(A11yTapResult(found = false, method = "node-text", error = "no match"))
            )
        )
        assertTrue(json.getBoolean("ok"))
        assertFalse(json.getBoolean("found"))
        assertEquals("no match", json.getString("error"))
    }

    @Test
    fun scrollFailureIsOkFalseAndDirectionsAreLowercase() {
        val json = JSONObject(
            A11yWire.encode(
                A11yOutcome.Scroll(A11yScrollResult(A11yScrollDirection.LEFT, "none", "no scroller"))
            )
        )
        assertFalse(json.getBoolean("ok"))
        assertEquals("left", json.getString("direction"))
        assertEquals("none", json.getString("method"))
    }

    @Test
    fun touchStatusListsHeldFingers() {
        val held = listOf(
            A11yHeldFinger(finger = 0, x = 10, y = 20, fx = 0.5, fy = 0.25, elapsedMs = 800L)
        )
        val json = JSONObject(
            A11yWire.encode(
                A11yOutcome.TouchStatus(
                    A11yTouchReport(1080, 1920, A11yLimits.MAX_FINGERS, A11yLimits.HOLD_TIMEOUT_MILLIS, held)
                )
            )
        )
        assertTrue(json.getBoolean("ok"))
        assertEquals(1080, json.getInt("screenW"))
        assertEquals(8, json.getInt("maxFingers"))
        val fingers = json.getJSONArray("held")
        assertEquals(1, fingers.length())
        assertEquals(800L, fingers.getJSONObject(0).getLong("elapsedMs"))
    }

    @Test
    fun screenshotDocumentCarriesScaleAndRedactionCount() {
        val json = JSONObject(
            A11yWire.encode(
                A11yOutcome.Screenshot(
                    A11yScreenshot(
                        path = "/data/user/0/com.example/files/dsh-a11y/screenshots/screen-1.png",
                        screenW = 1080,
                        screenH = 2400,
                        imageW = 540,
                        imageH = 1200,
                        width = 540,
                        height = 1200,
                        scaleX = 2.0,
                        scaleY = 2.0,
                        grid = 4,
                        bytes = 123_456L,
                        redactedRegions = 1,
                        hint = "masked"
                    )
                )
            )
        )
        assertTrue(json.getBoolean("ok"))
        assertEquals(2.0, json.getDouble("scaleX"), 0.0001)
        assertEquals(4, json.getInt("grid"))
        assertEquals(1, json.getInt("redactedRegions"))
        assertEquals("masked", json.getString("hint"))
    }
}
