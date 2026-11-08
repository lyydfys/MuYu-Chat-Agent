package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.dsh.a11y.policy.A11yNode
import com.muyuchat.core.dsh.a11y.policy.A11yNodeRef
import com.muyuchat.core.dsh.a11y.policy.A11yRect
import com.muyuchat.core.dsh.a11y.policy.A11yTreePolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `device.screen` tool against a fake port.
 *
 * The port is where the real accessibility engine would be, so these tests describe the tool's
 * contract only: every action reaches the port as the right typed request, the reported outcome has
 * the right shape, and — the case that must never regress — a disabled service is reported as
 * `SCREEN_SERVICE_DISABLED` rather than a fabricated success.
 */
class AgentScreenToolTest {

    private val descriptor = AgentScreenTool.DESCRIPTOR

    private class RecordingPort(
        private val outcome: AgentScreenOutcome = AgentScreenOutcome.Observed("ok")
    ) : AgentScreenPort {
        val actions = mutableListOf<AgentScreenAction>()

        override suspend fun act(action: AgentScreenAction): AgentScreenOutcome {
            actions += action
            return outcome
        }
    }

    private fun command(json: String) = toolCommand(descriptor, json)

    private fun execute(port: AgentScreenPort, json: String) = runBlocking {
        AgentScreenTool(port).execute(command(json), toolContext())
    }

    @Test
    fun `read returns the screen text the port observed`() {
        val port = RecordingPort(AgentScreenOutcome.Observed("设置\n[0,0 1080x2400] WLAN (tap)"))
        val result = execute(port, """{"action":"read"}""")

        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertEquals(listOf(AgentScreenAction.ReadScreen), port.actions)
        assertEquals(
            "设置\n[0,0 1080x2400] WLAN (tap)",
            resultData(result).getString("text")
        )
    }

    @Test
    fun `tap by coordinates keeps both axes`() {
        val port = RecordingPort()
        execute(port, """{"action":"tap","x":320,"y":640}""")

        assertEquals(listOf(AgentScreenAction.Tap(x = 320, y = 640, text = "")), port.actions)
    }

    @Test
    fun `tap by text carries the control label and no coordinates`() {
        val port = RecordingPort()
        execute(port, """{"action":"tap","text":"确定"}""")

        assertEquals(listOf(AgentScreenAction.Tap(x = -1, y = -1, text = "确定")), port.actions)
    }

    @Test
    fun `input forwards the text verbatim`() {
        val port = RecordingPort()
        execute(port, """{"action":"input","text":"你好，世界"}""")

        assertEquals(listOf(AgentScreenAction.InputText("你好，世界")), port.actions)
    }

    @Test
    fun `scroll keeps its direction and distance`() {
        val forward = RecordingPort()
        execute(forward, """{"action":"scroll","forward":true,"distance":500}""")
        assertEquals(listOf(AgentScreenAction.Scroll(forward = true, distance = 500)), forward.actions)

        val backward = RecordingPort()
        execute(backward, """{"action":"scroll","forward":false}""")
        assertEquals(listOf(AgentScreenAction.Scroll(forward = false, distance = 0)), backward.actions)
    }

    @Test
    fun `back and home map to the two global actions`() {
        val back = RecordingPort()
        execute(back, """{"action":"back"}""")
        assertEquals(listOf<AgentScreenAction>(AgentScreenAction.GlobalBack), back.actions)

        val home = RecordingPort()
        execute(home, """{"action":"home"}""")
        assertEquals(listOf<AgentScreenAction>(AgentScreenAction.GlobalHome), home.actions)
    }

    @Test
    fun `a disabled service is reported as service disabled and never as success`() {
        val result = execute(RecordingPort(AgentScreenOutcome.ServiceDisabled), """{"action":"read"}""")

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals(AgentScreenErrors.SERVICE_DISABLED, result.errorCode)
        assertTrue("the user must be told what to enable", result.summary.contains("accessibility"))
    }

    @Test
    fun `a failed action keeps the port's reason under the screen unavailable code`() {
        val result = execute(
            RecordingPort(AgentScreenOutcome.Failed("no clickable control matched the request")),
            """{"action":"tap","text":"不存在的按钮"}"""
        )

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals(AgentScreenErrors.SCREEN_UNAVAILABLE, result.errorCode)
        assertEquals("no clickable control matched the request", result.summary)
    }

    @Test
    fun `an unknown action never reaches the port`() {
        val port = RecordingPort()
        val result = execute(port, """{"action":"reboot"}""")

        assertEquals(AgentToolResultStatus.FAILED, result.status)
        assertEquals("SCREEN_ARGUMENTS_INVALID", result.errorCode)
        assertTrue("the port must not be called for invalid arguments", port.actions.isEmpty())
    }

    @Test
    fun `the descriptor is the frozen device screen contract`() {
        assertEquals("device.screen", descriptor.toolKey)
        assertEquals(descriptor.schemaHash, AgentToolJson.sha256Hex(descriptor.schemaJson))
        assertTrue(descriptor.schemaJson.contains("\"additionalProperties\":false"))
        assertTrue(descriptor.schemaJson.contains("\"action\""))
        assertTrue(descriptor.schemaJson.contains("\"tap\""))
    }

    // ── the production renderer (pure) ─────────────────────────────────────────────────

    private fun node(
        index: Int,
        text: String,
        desc: String = "",
        x: Int = 0,
        y: Int = 0,
        w: Int = 100,
        h: Int = 50,
        clickable: Boolean = false,
        input: Boolean = false,
        depth: Int = 0
    ) = A11yNode(
        index = index,
        depth = depth,
        text = text,
        desc = desc,
        cls = "android.widget.TextView",
        rect = A11yRect(x, y, w, h),
        clickable = clickable,
        input = input,
        checked = false,
        selected = false,
        scrollable = false,
        password = false,
        redacted = false,
        ref = A11yNodeRef(1L, index)
    )

    @Test
    fun `the renderer keeps labels geometry and one compact flag suffix`() {
        val text = renderSnapshotText(
            listOf(
                node(0, "WLAN", x = 10, y = 20, w = 300, h = 80, clickable = true),
                node(1, "", desc = "搜索框", x = 10, y = 120, w = 900, h = 90, input = true),
                node(2, "") // a container with no label must not produce an empty line
            )
        )

        assertTrue(text.contains("[10,20 300x80] WLAN (tap)"))
        assertTrue(text.contains("[10,120 900x90] 搜索框 (input)"))
        assertEquals("one line per labelled node", 2, text.lines().size)
        assertFalse("password content is never rendered", text.contains("password"))
    }

    @Test
    fun `an unreadable screen returns the engine's own hint`() {
        assertEquals(A11yTreePolicy.EMPTY_SCREEN_HINT, renderSnapshotText(emptyList()))
        assertEquals(A11yTreePolicy.EMPTY_SCREEN_HINT, renderSnapshotText(listOf(node(0, ""))))
    }
}
