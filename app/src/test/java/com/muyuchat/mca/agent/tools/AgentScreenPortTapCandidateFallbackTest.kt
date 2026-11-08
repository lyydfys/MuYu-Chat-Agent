package com.muyuchat.mca.agent.tools

import com.muyuchat.core.dsh.a11y.policy.A11yCommand
import com.muyuchat.core.dsh.a11y.policy.A11yNode
import com.muyuchat.core.dsh.a11y.policy.A11yNodeRef
import com.muyuchat.core.dsh.a11y.policy.A11yOutcome
import com.muyuchat.core.dsh.a11y.policy.A11yRect
import com.muyuchat.core.dsh.a11y.policy.A11yRejection
import com.muyuchat.core.dsh.a11y.policy.A11yTapResult
import com.muyuchat.core.dsh.a11y.policy.A11yTreeSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `device.screen` tap self-rescue: a text tap that matches nothing reports candidates
 * instead of a dead end.
 *
 * Two promises are protected here. First, a *miss* must still be a miss — the fallback may never
 * tap something it merely considered close, because a wrong tap is an unapproved side effect on the
 * user's phone. Second, the fallback may not turn a genuine dead end into a success: with no
 * candidate at all the original failure stands.
 */
class AgentScreenPortTapCandidateFallbackTest {

    @Test
    fun `a text tap miss returns the ranked candidates instead of failing`() = runBlocking {
        val engine = ScriptedEngine(
            onTap = { tapped -> tapResult(found = false, error = "no clickable control matched") },
            onDump = { snapshot(listOf(node(0, "搜索联系人", clickable = true), node(1, "联系人"))) }
        )

        val outcome = port(engine).act(AgentScreenAction.Tap(x = -1, y = -1, text = "搜索"))

        assertTrue("a describable near miss is an observation, not a failure", outcome is AgentScreenOutcome.Observed)
        val text = (outcome as AgentScreenOutcome.Observed).text
        assertTrue("the model must be told nothing was tapped", text.contains("nothing was tapped"))
        assertTrue("the candidate list must be present", text.contains("搜索联系人"))
        assertTrue("the candidate must be addressable", text.contains("index=0"))
        assertTrue("bounds are required for a coordinate fallback", text.contains("bounds="))
    }

    @Test
    fun `a text tap miss with no resembling control still fails`() = runBlocking {
        val engine = ScriptedEngine(
            onTap = { tapped -> tapResult(found = false, error = "no clickable control matched") },
            onDump = { snapshot(listOf(node(0, "WLAN"), node(1, "蓝牙"))) }
        )

        val outcome = port(engine).act(AgentScreenAction.Tap(x = -1, y = -1, text = "微信"))

        assertTrue("no candidate means a real failure", outcome is AgentScreenOutcome.Failed)
        assertEquals(
            "no clickable control matched",
            (outcome as AgentScreenOutcome.Failed).reason
        )
    }

    @Test
    fun `the fallback never taps anything by itself`() = runBlocking {
        val engine = ScriptedEngine(
            onTap = { tapped -> tapResult(found = false, error = "no clickable control matched") },
            onDump = { snapshot(listOf(node(0, "搜索联系人", clickable = true))) }
        )

        port(engine).act(AgentScreenAction.Tap(x = -1, y = -1, text = "搜索"))

        val taps = engine.commands.count { it is A11yCommand.Tap }
        assertEquals("exactly the one tap the caller asked for", 1, taps)
    }

    @Test
    fun `a successful tap reports success and never dumps the tree`() = runBlocking {
        val engine = ScriptedEngine(
            onTap = { tapped -> tapResult(found = true, method = "text") },
            onDump = { snapshot(listOf(node(0, "搜索"))) }
        )

        val outcome = port(engine).act(AgentScreenAction.Tap(x = -1, y = -1, text = "搜索"))

        assertTrue(outcome is AgentScreenOutcome.Observed)
        assertFalse(
            "a hit must not pay for a second tree capture",
            engine.commands.any { it is A11yCommand.Dump }
        )
    }

    @Test
    fun `a coordinate tap miss keeps failing and never dumps`() = runBlocking {
        val engine = ScriptedEngine(
            onTap = { tapped -> tapResult(found = false, error = "point is outside the screen") },
            onDump = { snapshot(listOf(node(0, "搜索", clickable = true))) }
        )

        val outcome = port(engine).act(AgentScreenAction.Tap(x = 10, y = 20, text = ""))

        assertTrue("a coordinate miss has no text to rank", outcome is AgentScreenOutcome.Failed)
        assertFalse(engine.commands.any { it is A11yCommand.Dump })
    }

    @Test
    fun `an undumpable screen keeps the original failure rather than inventing candidates`() =
        runBlocking {
            val engine = ScriptedEngine(
                onTap = { tapped -> tapResult(found = false, error = "no clickable control matched") },
                onDump = { A11yOutcome.Rejected(A11yRejection.notEnabled()) }
            )

            val outcome = port(engine).act(AgentScreenAction.Tap(x = -1, y = -1, text = "搜索"))

            assertTrue(outcome is AgentScreenOutcome.Failed)
            assertEquals(
                "no clickable control matched",
                (outcome as AgentScreenOutcome.Failed).reason
            )
        }

    // ---------------------------------------------------------------- fixtures

    private fun port(engine: AgentScreenEngine) = AndroidAgentScreenPort(
        engineProvider = { engine },
        snapshotParser = { "screen" },
        encodeImage = { null },
        readScreenshot = { null }
    )

    private fun tapResult(
        found: Boolean,
        method: String = "none",
        error: String? = null
    ) = A11yOutcome.Tap(A11yTapResult(found = found, method = method, error = error))

    private fun snapshot(nodes: List<A11yNode>) = A11yOutcome.Dump(
        A11yTreeSnapshot(
            generation = 1L,
            packageName = "com.example",
            windowId = 1,
            nodes = nodes,
            truncated = false,
            textTruncated = false,
            redactedCount = 0
        )
    )

    private fun node(
        index: Int,
        text: String,
        desc: String = "",
        clickable: Boolean = false
    ) = A11yNode(
        index = index,
        depth = 0,
        text = text,
        desc = desc,
        cls = "android.widget.TextView",
        rect = A11yRect(index * 100, 0, 100, 50),
        clickable = clickable,
        input = false,
        checked = false,
        selected = false,
        scrollable = false,
        password = false,
        redacted = false,
        ref = A11yNodeRef(1L, index)
    )

    /** Engine whose tap and dump answers are scripted; records what was asked. */
    private class ScriptedEngine(
        private val onTap: (A11yCommand.Tap) -> A11yOutcome,
        private val onDump: () -> A11yOutcome
    ) : AgentScreenEngine {
        val commands = mutableListOf<A11yCommand>()

        override suspend fun execute(command: A11yCommand): A11yOutcome {
            commands += command
            return when (command) {
                is A11yCommand.Tap -> onTap(command)
                A11yCommand.Dump -> onDump()
                else -> A11yOutcome.Rejected(A11yRejection.invalid("unexpected command"))
            }
        }
    }
}
