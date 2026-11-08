package com.muyuchat.mca.agent.projection

import com.muyuchat.core.engine.AgentTurnTrace
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import com.muyuchat.mca.agent.data.AgentWorkspacePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the second half of "the agent can see the conversation": the seed has to reach disk in the
 * task's `inputs/` area, because the goal hint is worthless if the file is not there.
 *
 * [ChatHistorySeedTest] already covers what the seed *contains*; these cases cover the write, which is
 * where a task would silently run without history.
 */
class ChatHistorySeedWriterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun message(role: Role, content: String, trace: AgentTurnTrace? = null) = ChatMessage(
        role = role,
        content = content,
        agentTrace = trace
    )

    @Test
    fun `the seed lands under the task inputs directory`() {
        val root = temp.newFolder("agent")
        val seed = buildSeed(listOf(message(Role.USER, "把上一段改成正式语气")))

        val logicalPath = ChatHistorySeedWriter.write(root, "ws-1", "task-1", seed)

        assertEquals("inputs/chat-history.txt", logicalPath)
        val written = File(root, "workspaces/ws-1/tasks/task-1/inputs/chat-history.txt")
        assertTrue("seed file must exist at the logical path", written.isFile)
        assertTrue(written.readText(Charsets.UTF_8).contains("把上一段改成正式语气"))
    }

    @Test
    fun `the written bytes are exactly the seed text`() {
        // The builder enforces a UTF-8 byte budget; writing a re-encoded string would break that
        // guarantee for CJK content, where chars and bytes differ.
        val root = temp.newFolder("agent")
        val seed = buildSeed(listOf(message(Role.USER, "中文内容需要按字节计算")))

        ChatHistorySeedWriter.write(root, "ws-1", "task-1", seed)

        val written = File(root, "workspaces/ws-1/tasks/task-1/inputs/chat-history.txt")
        assertEquals(seed.seedText.toByteArray(Charsets.UTF_8).size, written.length().toInt())
        assertEquals(seed.seedText, written.readText(Charsets.UTF_8))
    }

    @Test
    fun `an empty seed writes nothing and reports null`() {
        // Without this guard the caller would append a goal hint pointing at a file that does not
        // exist, costing the agent a wasted read on its first step.
        val root = temp.newFolder("agent")
        val seed = buildSeed(emptyList())

        val logicalPath = ChatHistorySeedWriter.write(root, "ws-1", "task-1", seed)

        assertNull(logicalPath)
        assertTrue(!File(root, "workspaces/ws-1/tasks/task-1/inputs").exists())
    }

    @Test
    fun `agent transcript messages never enter the seed file`() {
        val root = temp.newFolder("agent")
        val seed = buildSeed(
            listOf(
                message(Role.USER, "真正的用户消息"),
                message(
                    Role.ASSISTANT,
                    "Agent 自己的协议噪声",
                    trace = AgentTurnTrace(turnId = "t1", kind = "step", finalSummary = "")
                )
            )
        )

        ChatHistorySeedWriter.write(root, "ws-1", "task-1", seed)

        val text = File(root, "workspaces/ws-1/tasks/task-1/inputs/chat-history.txt").readText(Charsets.UTF_8)
        assertTrue(text.contains("真正的用户消息"))
        assertTrue("agent's own transcript must not be replayed", !text.contains("协议噪声"))
    }

    @Test
    fun `a custom seed file name is honoured`() {
        val root = temp.newFolder("agent")
        val seed = buildSeed(
            history = listOf(message(Role.USER, "hello")),
            inputFileName = "chat-history-1762560000000.txt"
        )

        val logicalPath = ChatHistorySeedWriter.write(root, "ws-1", "task-1", seed)

        assertEquals("inputs/chat-history-1762560000000.txt", logicalPath)
        assertNotNull(File(root, "workspaces/ws-1/tasks/task-1/inputs/chat-history-1762560000000.txt").takeIf { it.isFile })
    }

    @Test
    fun `the logical path is workspace relative and uses the shared inputs constant`() {
        // The agent's read tool resolves logical paths, so an absolute path or a different folder name
        // would make the hint unusable.
        val root = temp.newFolder("agent")
        val seed = buildSeed(listOf(message(Role.USER, "hi")))

        val logicalPath = ChatHistorySeedWriter.write(root, "ws-1", "task-1", seed)

        assertNotNull(logicalPath)
        assertTrue(logicalPath!!.startsWith(AgentWorkspacePaths.INPUTS + "/"))
        assertTrue(!logicalPath.startsWith("/"))
    }

    @Test
    fun `two tasks never share a seed file`() {
        val root = temp.newFolder("agent")
        val first = buildSeed(listOf(message(Role.USER, "任务一的历史")))
        val second = buildSeed(listOf(message(Role.USER, "任务二的历史")))

        ChatHistorySeedWriter.write(root, "ws-1", "task-a", first)
        ChatHistorySeedWriter.write(root, "ws-1", "task-b", second)

        val a = File(root, "workspaces/ws-1/tasks/task-a/inputs/chat-history.txt").readText(Charsets.UTF_8)
        val b = File(root, "workspaces/ws-1/tasks/task-b/inputs/chat-history.txt").readText(Charsets.UTF_8)
        assertTrue(a.contains("任务一的历史"))
        assertTrue(b.contains("任务二的历史"))
        assertTrue("each task owns its own snapshot", !a.contains("任务二的历史"))
    }
}
