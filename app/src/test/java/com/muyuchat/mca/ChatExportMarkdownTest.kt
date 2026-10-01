package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Markdown body written when a conversation is exported, and proves the P2
 * extraction changed nothing.
 *
 * This is an output the user keeps, so its shape is a contract. It had no test. The
 * reasoning trace being kept in its own section, separate from the final answer, is
 * user-visible behaviour that a refactor could easily flatten.
 *
 * The export timestamp is read from the clock at call time, so comparisons normalise that
 * one line and assert its shape separately.
 */
class ChatExportMarkdownTest {

    @Test
    fun `role sections use the labels users see`() {
        assertEquals("系统", Role.SYSTEM.displayLabel())
        assertEquals("用户", Role.USER.displayLabel())
        assertEquals("MCA", Role.ASSISTANT.displayLabel())
    }

    @Test
    fun `a simple conversation renders title metadata and one section per message`() {
        val session = ChatSessionRecord(
            id = "s1",
            title = "Title",
            messages = listOf(ChatMessage(Role.USER, "hi"))
        )

        assertEquals(
            """
            # Title

            - 导出时间：<ts>
            - 消息数量：1
            - 会话 ID：s1

            ## 用户

            hi

            """.trimIndent() + "\n",
            session.toMarkdown().withoutExportTime()
        )
    }

    @Test
    fun `the export line carries the current time in a stable shape`() {
        val markdown = ChatSessionRecord(
            id = "s1",
            title = "T",
            messages = emptyList()
        ).toMarkdown()

        assertTrue(
            "the export time line must keep its yyyy-MM-dd HH:mm:ss shape",
            EXPORT_TIME.containsMatchIn(markdown)
        )
    }

    @Test
    fun `an empty conversation still renders its header`() {
        val markdown = ChatSessionRecord(id = "s2", title = "Empty", messages = emptyList())
            .toMarkdown()
            .withoutExportTime()

        assertEquals(
            """
            # Empty

            - 导出时间：<ts>
            - 消息数量：0
            - 会话 ID：s2

            """.trimIndent() + "\n",
            markdown
        )
    }

    @Test
    fun `the reasoning trace keeps its own section before the final answer`() {
        val session = ChatSessionRecord(
            id = "s3",
            title = "Reasoning",
            messages = listOf(
                ChatMessage(
                    role = Role.ASSISTANT,
                    content = "answer",
                    reasoningContent = "thinking",
                    reasoningDurationMs = 5_000L
                )
            )
        )

        assertEquals(
            "## MCA\n\n### 思考过程（用时 5 秒）\n\nthinking\n\n### 最终回答\n\nanswer\n\n",
            session.toMarkdown().substringAfter("- 会话 ID：s3\n\n")
        )
    }

    @Test
    fun `a reasoning duration of zero is omitted`() {
        val session = ChatSessionRecord(
            id = "s4",
            title = "NoDuration",
            messages = listOf(
                ChatMessage(Role.ASSISTANT, "answer", reasoningContent = "thinking", reasoningDurationMs = 0L)
            )
        )

        val body = session.toMarkdown().substringAfter("- 会话 ID：s4\n\n")
        assertEquals("## MCA\n\n### 思考过程\n\nthinking\n\n### 最终回答\n\nanswer\n\n", body)
    }

    @Test
    fun `a blank reasoning trace adds no section`() {
        val session = ChatSessionRecord(
            id = "s5",
            title = "NoTrace",
            messages = listOf(ChatMessage(Role.ASSISTANT, "answer", reasoningContent = "   "))
        )

        val body = session.toMarkdown().substringAfter("- 会话 ID：s5\n\n")
        assertEquals("## MCA\n\nanswer\n\n", body)
    }

    @Test
    fun `reasoning is only rendered for assistant messages`() {
        // A user turn carrying reasoning content must not gain a trace section.
        val session = ChatSessionRecord(
            id = "s6",
            title = "UserTrace",
            messages = listOf(ChatMessage(Role.USER, "question", reasoningContent = "thinking"))
        )

        val body = session.toMarkdown().substringAfter("- 会话 ID：s6\n\n")
        assertEquals("## 用户\n\nquestion\n\n", body)
    }

    @Test
    fun `a blank message becomes a placeholder rather than an empty section`() {
        val session = ChatSessionRecord(
            id = "s7",
            title = "Blank",
            messages = listOf(ChatMessage(Role.USER, "   "))
        )

        val body = session.toMarkdown().substringAfter("- 会话 ID：s7\n\n")
        assertEquals("## 用户\n\n（空消息）\n\n", body)
    }

    @Test
    fun `message content is written as is, including markdown`() {
        val content = "**bold**\n\n```kotlin\nval x = 1\n```"
        val session = ChatSessionRecord(
            id = "s8",
            title = "Raw",
            messages = listOf(ChatMessage(Role.ASSISTANT, content))
        )

        assertTrue(
            "exported content must not be rewritten",
            session.toMarkdown().contains(content)
        )
    }

    @Test
    fun `every message appears in order`() {
        val session = ChatSessionRecord(
            id = "s9",
            title = "Order",
            messages = listOf(
                ChatMessage(Role.SYSTEM, "sys"),
                ChatMessage(Role.USER, "u1"),
                ChatMessage(Role.ASSISTANT, "a1"),
                ChatMessage(Role.USER, "u2"),
            )
        )

        val body = session.toMarkdown()
        val positions = listOf("## 系统", "## 用户", "## MCA", "u1", "a1", "u2", "sys")
            .map { body.indexOf(it) }
        assertEquals("every marker must be present", true, positions.all { it >= 0 })
        assertTrue("the first user turn must follow the system section", positions[1] > positions[0])
        assertTrue("messages must keep their order", positions[5] > positions[3])
    }

    // ---- extraction equivalence ------------------------------------------------------

    @Test
    fun `the extraction matches the original rendering on every sample`() {
        val samples = listOf(
            ChatSessionRecord(id = "a", title = "Plain", messages = listOf(ChatMessage(Role.USER, "hi"))),
            ChatSessionRecord(id = "b", title = "Empty", messages = emptyList()),
            ChatSessionRecord(
                id = "c",
                title = "Trace",
                messages = listOf(
                    ChatMessage(Role.ASSISTANT, "answer", reasoningContent = "thinking", reasoningDurationMs = 5_000L)
                )
            ),
            ChatSessionRecord(
                id = "d",
                title = "Zero",
                messages = listOf(
                    ChatMessage(Role.ASSISTANT, "answer", reasoningContent = "t", reasoningDurationMs = 0L)
                )
            ),
            ChatSessionRecord(
                id = "e",
                title = "Blank",
                messages = listOf(ChatMessage(Role.USER, "  "))
            ),
            ChatSessionRecord(
                id = "f",
                title = "Mixed",
                messages = listOf(
                    ChatMessage(Role.SYSTEM, "sys"),
                    ChatMessage(Role.USER, "u"),
                    ChatMessage(Role.ASSISTANT, "a", reasoningContent = "r", reasoningDurationMs = 1_999L),
                    ChatMessage(Role.ASSISTANT, "", reasoningContent = ""),
                )
            ),
            ChatSessionRecord(id = "g", title = "# hash and **markers**", messages = emptyList()),
        )

        samples.forEach { session ->
            assertEquals(
                "markdown diverged for session ${session.id}",
                originalToMarkdown(session).withoutExportTime(),
                session.toMarkdown().withoutExportTime()
            )
        }

        Role.entries.forEach { role ->
            assertEquals(
                "role label diverged for $role",
                originalDisplayLabel(role),
                role.displayLabel()
            )
        }
    }

    /** Verbatim copy of the pre-extraction `MainViewModel.ChatSessionRecord.toMarkdown`. */
    private fun originalToMarkdown(session: ChatSessionRecord): String = buildString {
        append("# ").append(session.title).append("\n\n")
        append("- 导出时间：").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())).append("\n")
        append("- 消息数量：").append(session.messages.size).append("\n")
        append("- 会话 ID：").append(session.id).append("\n\n")
        session.messages.forEach { message ->
            append("## ").append(originalDisplayLabel(message.role)).append("\n\n")
            if (message.role == Role.ASSISTANT && message.reasoningContent.isNotBlank()) {
                append("### 思考过程")
                if (message.reasoningDurationMs > 0L) {
                    append("（用时 ").append(message.reasoningDurationMs / 1000).append(" 秒）")
                }
                append("\n\n")
                append(message.reasoningContent).append("\n\n")
                append("### 最终回答\n\n")
            }
            append(message.content.ifBlank { "（空消息）" }).append("\n\n")
        }
    }

    /** Verbatim copy of the pre-extraction `MainViewModel.Role.displayLabel`. */
    private fun originalDisplayLabel(role: Role): String = when (role) {
        Role.SYSTEM -> "系统"
        Role.USER -> "用户"
        Role.ASSISTANT -> "MCA"
    }

    /** Replaces the wall-clock line so two renders of the same record can be compared. */
    private fun String.withoutExportTime(): String =
        EXPORT_TIME.replace(this, "- 导出时间：<ts>")

    private companion object {
        val EXPORT_TIME = Regex("""- 导出时间：\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")
    }
}
