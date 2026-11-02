package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentEventTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTimelineProjectionTest {

    @Test
    fun repeatedToolActivityCollapsesIntoOneCompactRow() {
        val events = (1L..4L).map { sequence ->
            testEvent(sequence, AgentEventTypes.TOOL_RESULT, "workspace.list: 3 个文件")
        }
        val rows = projectTimeline(events, emptySet())
        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals("workspace.list", row.title)
        assertEquals(4, row.repeatCount)
        assertEquals(4, row.eventIds.size)
        assertEquals(4, row.details.size)
        assertTrue(row.expandable)
        assertFalse(row.expanded)
        assertEquals("3 个文件", row.summary)
    }

    @Test
    fun eventsSharingCausationIdAreOneActivity() {
        val started = testEvent(
            sequence = 1,
            type = AgentEventTypes.TOOL_STARTED,
            summary = "workspace.read_text: inputs/notes.md",
            causationId = "invocation-1"
        )
        val result = testEvent(
            sequence = 2,
            type = AgentEventTypes.TOOL_RESULT,
            summary = "workspace.read_text: 已读取 2.1 KB",
            causationId = "invocation-1"
        )
        val rows = projectTimeline(listOf(started, result), emptySet())
        assertEquals(1, rows.size)
        assertEquals(listOf("event-1", "event-2"), rows.first().eventIds)
        assertEquals("workspace.read_text", rows.first().title)
    }

    @Test
    fun startResultPairWithoutCausationIdsIsStillOneActivity() {
        val started = testEvent(1, AgentEventTypes.TOOL_STARTED, "workspace.list: inputs/")
        val result = testEvent(2, AgentEventTypes.TOOL_RESULT, "workspace.list: 3 个文件")
        val rows = projectTimeline(listOf(started, result), emptySet())
        assertEquals(1, rows.size)
        assertEquals(2, rows.first().eventIds.size)
    }

    @Test
    fun stateChangeNoiseIsHiddenFromTheCompactTimeline() {
        val events = listOf(
            testEvent(1, AgentEventTypes.TASK_CREATED, "任务已创建"),
            testEvent(2, AgentEventTypes.RUN_STATE_CHANGED, "RUNNING -> WAITING_APPROVAL"),
            testEvent(3, AgentEventTypes.TOOL_RESULT, "workspace.list: 3 个文件")
        )
        val rows = projectTimeline(events, emptySet())
        assertEquals(1, rows.size)
        assertEquals("workspace.list", rows.first().title)
        assertTrue(rows.none { it.summary?.contains("RUNNING") == true })
    }

    @Test
    fun expandedDetailsAreBoundedAndMarkedTruncated() {
        val events = (1L..12L).map { sequence ->
            testEvent(
                sequence = sequence,
                type = AgentEventTypes.TOOL_RESULT,
                summary = "workspace.read_text: chunk $sequence",
                causationId = "invocation-collapsed"
            )
        }
        val rows = projectTimeline(events, emptySet())
        val row = rows.first()
        assertEquals(11, row.details.size)
        assertTrue(row.details.last().contains("还有 2 条活动未展开"))

        // The event contract itself caps summaries at 4 KiB; use a value that is still much
        // larger than the display cap so the bounded-detail path is exercised.
        val longSummary = "x".repeat(3_000)
        val longRow = projectTimeline(
            listOf(testEvent(99, AgentEventTypes.TOOL_RESULT, longSummary)),
            emptySet()
        ).first()
        assertTrue(longRow.details.first().length <= 900)
        assertTrue(longRow.details.first().contains("内容已截断"))
    }

    @Test
    fun expandedRowIsDrivenByTheStableRowKey() {
        val events = listOf(
            testEvent(1, AgentEventTypes.TOOL_RESULT, "workspace.list: a"),
            testEvent(2, AgentEventTypes.TOOL_RESULT, "workspace.list: b")
        )
        val collapsed = projectTimeline(events, emptySet()).first()
        assertFalse(collapsed.expanded)
        val expanded = projectTimeline(events, setOf(collapsed.key)).first()
        assertTrue(expanded.expanded)
        // The first event id is the stable key even after collapsing.
        assertEquals("event-1", expanded.key)
    }

    @Test
    fun previewSummaryIsBounded() {
        val events = listOf(
            testEvent(1, AgentEventTypes.TOOL_RESULT, "workspace.read_text: " + "y".repeat(500))
        )
        val row = projectTimeline(events, emptySet()).first()
        assertNotNull(row.summary)
        assertTrue(requireNotNull(row.summary).length <= 161)
    }

    @Test
    fun failureAndWarningSummariesCarryExplicitTone() {
        val failed = projectTimeline(
            listOf(testEvent(1, AgentEventTypes.TOOL_RESULT, "workspace.list: FAILED: 权限不足")),
            emptySet()
        ).first()
        assertEquals(AgentStatusTone.ERROR, failed.tone)

        val cancelled = projectTimeline(
            listOf(testEvent(1, AgentEventTypes.TOOL_STARTED, "workspace.list: 用户取消")),
            emptySet()
        ).first()
        assertEquals(AgentStatusTone.WARNING, cancelled.tone)
    }

    @Test
    fun finalAnswerComesFromTheLatestTerminalEventSummary() {
        val events = listOf(
            testEvent(1, AgentEventTypes.RUN_TERMINAL, "旧结果"),
            testEvent(2, AgentEventTypes.MODEL_ATTEMPT_SETTLED, "模型完成"),
            testEvent(3, AgentEventTypes.RUN_TERMINAL, "新结果")
        )
        assertEquals("新结果", extractFinalAnswer(events))
        assertNull(extractFinalAnswer(listOf(testEvent(1, AgentEventTypes.RUN_TERMINAL, null))))
    }

    @Test
    fun timelineHintDetectsMcpAndSkillMarkersInSafeSummaries() {
        assertEquals(
            AgentTimelineHint.MCP_OFFLINE,
            detectTimelineHint(
                listOf(testEvent(1, AgentEventTypes.TOOL_RESULT, "MCP_OFFLINE: docs 断线"))
            )
        )
        assertEquals(
            AgentTimelineHint.SKILL_MISSING_TOOL,
            detectTimelineHint(
                listOf(testEvent(1, AgentEventTypes.TOOL_RESULT, "SKILL_MISSING_TOOL: bash"))
            )
        )
        assertEquals(
            AgentTimelineHint.SKILL_MISSING_TOOL,
            detectTimelineHint(
                listOf(testEvent(1, AgentEventTypes.TOOL_RESULT, "SKILL foo: TOOL_NOT_FOUND"))
            )
        )
        assertEquals(
            AgentTimelineHint.NONE,
            detectTimelineHint(
                listOf(testEvent(1, AgentEventTypes.TOOL_RESULT, "已读取 1 KB"))
            )
        )
    }

    @Test
    fun approvalAndQuestionRowsUseTheirOwnKinds() {
        val rows = projectTimeline(
            listOf(
                testEvent(1, AgentEventTypes.TOOL_APPROVAL_REQUESTED, "artifact.write_text"),
                testEvent(2, AgentEventTypes.QUESTION_ASKED, "要覆盖文件吗？")
            ),
            emptySet()
        )
        assertTrue(rows.any { it.kind == AgentTimelineKind.APPROVAL })
        assertTrue(rows.any { it.kind == AgentTimelineKind.QUESTION })
    }

    @Test
    fun structuredLifecycleCollapsesOneInvocationIntoOneTerminalRow() {
        val events = listOf(
            testEvent(
                1,
                AgentEventTypes.TOOL_PROPOSED,
                """{"tool":"workspace.read_text","source":"builtin","state":"proposed"}""",
                causationId = "invocation-1"
            ),
            testEvent(
                2,
                AgentEventTypes.TOOL_APPROVAL_REQUESTED,
                """{"tool":"workspace.read_text","source":"builtin","state":"waiting_approval","action":"读取资料"}""",
                causationId = "invocation-1"
            ),
            testEvent(
                3,
                AgentEventTypes.TOOL_APPROVAL_RESOLVED,
                """{"tool":"workspace.read_text","source":"builtin","state":"approved","summary":"已允许一次"}""",
                causationId = "invocation-1"
            ),
            testEvent(
                4,
                AgentEventTypes.TOOL_STARTED,
                """{"tool":"workspace.read_text","source":"builtin","state":"running"}""",
                causationId = "invocation-1"
            ),
            testEvent(
                5,
                AgentEventTypes.TOOL_RESULT,
                """{"tool":"workspace.read_text","source":"builtin","state":"succeeded","summary":"已读取 2.1 KB"}""",
                causationId = "invocation-1"
            ),
            testEvent(
                6,
                AgentEventTypes.TOOL_RESULT,
                """{"tool":"workspace.read_text","source":"builtin","state":"succeeded","summary":"已读取 2.1 KB"}""",
                causationId = "invocation-1"
            )
        )
        val rows = projectTimeline(events, emptySet())
        assertEquals(1, rows.size)
        val row = rows.first()
        assertEquals(AgentTimelineKind.TOOL, row.kind)
        assertEquals("workspace.read_text", row.title)
        assertEquals("workspace.read_text", row.toolKey)
        assertEquals(AgentToolLifecycleState.SUCCEEDED, row.toolState)
        assertEquals(AgentToolSourceKind.BUILT_IN, row.source?.kind)
        assertEquals("内置工具", row.source?.label)
        assertEquals("已读取 2.1 KB", row.summary)
        assertEquals(1, row.repeatCount)
        assertEquals(6, row.eventIds.size)
        assertEquals(6, row.details.size)
    }

    @Test
    fun everyLifecyclePhaseMapsToAnExplicitHonestState() {
        fun stateOf(type: String, summary: String): AgentToolLifecycleState =
            requireNotNull(
                projectTimeline(listOf(testEvent(1, type, summary)), emptySet())
                    .first()
                    .toolState
            )

        assertEquals(
            AgentToolLifecycleState.PROPOSED,
            stateOf(
                AgentEventTypes.TOOL_PROPOSED,
                """{"tool":"workspace.list","source":"builtin","state":"proposed"}"""
            )
        )
        assertEquals(
            AgentToolLifecycleState.WAITING_APPROVAL,
            stateOf(
                AgentEventTypes.TOOL_APPROVAL_REQUESTED,
                """{"tool":"workspace.list","source":"builtin","state":"waiting_approval"}"""
            )
        )
        assertEquals(
            AgentToolLifecycleState.APPROVED,
            stateOf(
                AgentEventTypes.TOOL_APPROVAL_RESOLVED,
                """{"tool":"workspace.list","source":"builtin","state":"approved"}"""
            )
        )
        assertEquals(
            AgentToolLifecycleState.RUNNING,
            stateOf(
                AgentEventTypes.TOOL_STARTED,
                """{"tool":"workspace.list","source":"builtin","state":"running"}"""
            )
        )
        assertEquals(
            AgentToolLifecycleState.SUCCEEDED,
            stateOf(
                AgentEventTypes.TOOL_RESULT,
                """{"tool":"workspace.list","source":"builtin","state":"succeeded"}"""
            )
        )
        assertEquals(
            AgentToolLifecycleState.DENIED,
            stateOf(
                AgentEventTypes.TOOL_RESULT,
                """{"tool":"artifact.write_text","source":"builtin","state":"denied"}"""
            )
        )
        assertEquals(
            AgentToolLifecycleState.CANCELLED,
            stateOf(
                AgentEventTypes.TOOL_RESULT,
                """{"tool":"mcp:docs:search","source":"mcp:docs","state":"cancelled"}"""
            )
        )
        // A resolution without a decision token must not be assumed to be an approval.
        assertEquals(
            AgentToolLifecycleState.UNKNOWN,
            stateOf(
                AgentEventTypes.TOOL_APPROVAL_RESOLVED,
                """{"tool":"workspace.list","source":"builtin"}"""
            )
        )
    }

    @Test
    fun deniedAndCancelledStatesUseDistinctTones() {
        val denied = projectTimeline(
            listOf(
                testEvent(
                    1,
                    AgentEventTypes.TOOL_RESULT,
                    """{"tool":"artifact.write_text","source":"builtin","state":"denied","summary":"用户拒绝"}"""
                )
            ),
            emptySet()
        ).first()
        assertEquals(AgentToolLifecycleState.DENIED, denied.toolState)
        assertEquals(AgentStatusTone.ERROR, denied.tone)
        assertEquals("用户拒绝", denied.summary)

        val cancelled = projectTimeline(
            listOf(
                testEvent(
                    1,
                    AgentEventTypes.TOOL_RESULT,
                    """{"tool":"mcp:docs:search","source":"mcp:docs","state":"cancelled","summary":"用户取消"}"""
                )
            ),
            emptySet()
        ).first()
        assertEquals(AgentToolLifecycleState.CANCELLED, cancelled.toolState)
        assertEquals(AgentStatusTone.WARNING, cancelled.tone)
        assertEquals("用户取消", cancelled.summary)
    }

    @Test
    fun duplicateTerminalEventsStayOneRow() {
        val rows = projectTimeline(
            listOf(
                testEvent(
                    1,
                    AgentEventTypes.TOOL_APPROVAL_RESOLVED,
                    """{"tool":"artifact.write_text","source":"builtin","state":"denied","summary":"用户拒绝"}""",
                    causationId = "invocation-1"
                ),
                testEvent(
                    2,
                    AgentEventTypes.TOOL_RESULT,
                    """{"tool":"artifact.write_text","source":"builtin","state":"denied","summary":"未执行"}""",
                    causationId = "invocation-1"
                )
            ),
            emptySet()
        )
        assertEquals(1, rows.size)
        assertEquals(AgentToolLifecycleState.DENIED, rows.first().toolState)
        assertEquals(2, rows.first().eventIds.size)
        assertEquals(2, rows.first().details.size)
    }

    @Test
    fun sourceLabelsCoverBuiltInSkillPackageMcpAndUnknown() {
        fun sourceFor(source: String?, tool: String): AgentToolSourceUi = requireNotNull(
            projectTimeline(
                listOf(
                    testEvent(
                        1,
                        AgentEventTypes.TOOL_RESULT,
                        if (source == null) {
                            """{"tool":"$tool","state":"succeeded","summary":"完成"}"""
                        } else {
                            """{"tool":"$tool","source":"$source","state":"succeeded","summary":"完成"}"""
                        }
                    )
                ),
                emptySet()
            ).first().source
        )

        assertEquals(AgentToolSourceKind.BUILT_IN, sourceFor("builtin", "workspace.read_text").kind)
        assertEquals(AgentToolSourceKind.BUILT_IN, sourceFor("builtin:workspace", "workspace.read_text").kind)
        assertEquals(AgentToolSourceKind.SKILL, sourceFor("skill", "skill:summarize:condense").kind)
        assertEquals("Skill · summarize", sourceFor("skill:summarize", "skill:summarize:condense").label)
        assertEquals(
            "工具包 · research-basics @ 1.0.0",
            sourceFor("package:research-basics@1.0.0", "pkg.research-basics.read_brief").label
        )
        assertEquals("MCP · docs", sourceFor("mcp:docs", "mcp:docs:search").label)
        assertEquals(AgentToolSourceKind.MCP, sourceFor(null, "mcp:docs:search").kind)
        assertEquals(
            AgentToolSourceKind.UNKNOWN,
            sourceFor(null, "custom.do_thing").kind
        )
        assertEquals("来源未知", sourceFor("mystery", "custom.do_thing").label)
        assertTrue(sourceFor("package:" + "x".repeat(300), "workspace.list").label.length <= 80)
    }

    @Test
    fun unstructuredToolActivityKeepsAGenericTitleAndUnknownSource() {
        val row = projectTimeline(
            listOf(testEvent(1, AgentEventTypes.TOOL_RESULT, "MCP_OFFLINE: 断线")),
            emptySet()
        ).first()
        assertEquals("工具结果", row.title)
        assertNull(row.toolKey)
        assertEquals(AgentToolSourceKind.UNKNOWN, row.source?.kind)
        assertEquals("来源未知", row.source?.label)
        assertEquals(AgentToolLifecycleState.FAILED, row.toolState)
        assertTrue(requireNotNull(row.summary).contains("MCP_OFFLINE"))
    }

    @Test
    fun toolSummariesNeverRenderArgsOrSecrets() {
        val structured = projectTimeline(
            listOf(
                testEvent(
                    1,
                    AgentEventTypes.TOOL_RESULT,
                    """{"tool":"workspace.read_text","source":"builtin","state":"succeeded","summary":"读取完成","args":"{\"token\":\"sk-abcdefgh12345678\"}","token":"sk-abcdefgh12345678"}"""
                )
            ),
            emptySet()
        ).first()
        assertEquals("读取完成", structured.summary)
        assertTrue(structured.details.none { it.contains("sk-abcdefgh12345678") })
        assertTrue(structured.details.none { it.contains("\"args\"") })

        val argsOnly = projectTimeline(
            listOf(
                testEvent(
                    1,
                    AgentEventTypes.TOOL_RESULT,
                    """{"tool":"workspace.read_text","source":"builtin","state":"succeeded","args":"secret-value"}"""
                )
            ),
            emptySet()
        ).first()
        assertNull(argsOnly.summary)
        assertTrue(argsOnly.details.first().contains("参数已省略"))
        assertTrue(argsOnly.details.first().contains("原始参数"))

        val legacySecret = projectTimeline(
            listOf(
                testEvent(
                    1,
                    AgentEventTypes.TOOL_RESULT,
                    "workspace.read_text: token=sk-abcdefgh12345678"
                )
            ),
            emptySet()
        ).first()
        assertTrue(requireNotNull(legacySecret.summary).contains("token=***"))
        assertFalse(requireNotNull(legacySecret.summary).contains("sk-abcdefgh12345678"))

        val malformed = projectTimeline(
            listOf(
                testEvent(
                    1,
                    AgentEventTypes.TOOL_RESULT,
                    """{"tool":"workspace.read_text","args":"sk-abcdefgh12345678""""
                )
            ),
            emptySet()
        ).first()
        assertTrue(malformed.details.first().contains("摘要不可解析"))
        assertTrue(malformed.details.none { it.contains("sk-abcdefgh12345678") })
    }
}
