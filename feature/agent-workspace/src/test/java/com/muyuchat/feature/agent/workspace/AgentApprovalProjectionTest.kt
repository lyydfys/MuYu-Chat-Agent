package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentEventTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentApprovalProjectionTest {

    @Test
    fun keyValueSummaryIsParsedIntoStructuredFields() {
        val summary = """
            tool=artifact.write_text
            action=覆盖已有概要文件
            read=inputs/notes.md, inputs/guide.md
            write=artifacts/summary.md
            network=https://api.example.com/v1
            args={"path":"artifacts/summary.md","content":"..."}
            path=artifacts/summary.md
            oldHash=${"d".repeat(64)}
            newHash=${"e".repeat(64)}
        """.trimIndent()
        val parsed = parseApprovalSummary(summary)
        assertEquals("artifact.write_text", parsed.tool)
        assertEquals("覆盖已有概要文件", parsed.action)
        assertEquals(listOf("inputs/notes.md", "inputs/guide.md"), parsed.readTargets)
        assertEquals(listOf("artifacts/summary.md"), parsed.writeTargets)
        assertEquals(listOf("https://api.example.com/v1"), parsed.networkTargets)
        assertTrue(requireNotNull(parsed.argumentsSummary).contains("content"))
        assertEquals("artifacts/summary.md", parsed.path)
        assertEquals(64, parsed.oldHash?.length)
        assertEquals(64, parsed.newHash?.length)
    }

    @Test
    fun deviceScopeSummaryIsProjectedForTheSheet() {
        val summary =
            """{"tool":"device.info","action":"READ_ONLY","scope":"读取设备型号（只读）","args":"{}"}"""
        assertEquals("读取设备型号（只读）", parseApprovalSummary(summary).scopeSummary)

        val ui = projectApprovalUi(testApproval(), listOf(approvalRequestEvent(summary)), TEST_NOW)
        assertEquals("读取设备型号（只读）", ui.scopeSummary)
        assertTrue(ui.hasStructuredDetails)
    }

    @Test
    fun flatJsonSummarySupportsStringArrays() {
        val parsed = parseApprovalSummary(
            """{"tool":"workspace.read_text","write":["artifacts/a.md","artifacts/b.md"],"args":"x","oldHash":"h1","newHash":"h2"}"""
        )
        assertEquals("workspace.read_text", parsed.tool)
        assertEquals(listOf("artifacts/a.md", "artifacts/b.md"), parsed.writeTargets)
        assertEquals("x", parsed.argumentsSummary)
        assertEquals("h1", parsed.oldHash)
    }

    @Test
    fun malformedJsonFallsBackToBoundedFreeTextInsteadOfGuessing() {
        val parsed = parseApprovalSummary("{not-json")
        assertNull(parsed.tool)
        assertEquals("{not-json", parsed.argumentsSummary)
    }

    @Test
    fun blankSummaryProducesEmptyProjection() {
        val parsed = parseApprovalSummary(null)
        assertNull(parsed.tool)
        assertTrue(parsed.readTargets.isEmpty())
        assertNull(parsed.argumentsSummary)
    }

    @Test
    fun approvalUiShowsTargetsOverwriteAndExpiry() {
        val summary = """
            tool=artifact.write_text
            action=覆盖已有概要文件
            read=inputs/notes.md
            write=artifacts/summary.md
            network=https://api.example.com/v1
            args=path=artifacts/summary.md; content=32 KiB
            path=artifacts/summary.md
            oldHash=${"d".repeat(64)}
            newHash=${"e".repeat(64)}
        """.trimIndent()
        val approval = testApproval(expiresInMillis = 240_000L)
        val ui = projectApprovalUi(approval, listOf(approvalRequestEvent(summary)), TEST_NOW)

        assertTrue(ui.pending)
        assertTrue(ui.hasStructuredDetails)
        assertEquals("artifact.write_text · 覆盖已有概要文件", ui.toolAction)
        assertEquals(listOf("inputs/notes.md"), ui.readTargets)
        assertEquals(listOf("artifacts/summary.md"), ui.writeTargets)
        assertEquals(listOf("https://api.example.com/v1"), ui.networkTargets)
        assertEquals("artifacts/summary.md", ui.overwrite?.path)
        assertEquals("d".repeat(64), ui.overwrite?.beforeHash)
        assertEquals("e".repeat(64), ui.overwrite?.afterHash)
        assertTrue(ui.expiresText.contains("剩余约 4 分钟"))
        assertFalse(ui.expired)
        assertEquals("待授权", ui.statusLabel)
        assertTrue(ui.argsHashShort.endsWith("…"))
    }

    @Test
    fun expiredApprovalIsMarkedAndDoesNotClaimRemainingTime() {
        val approval = testApproval(expiresInMillis = -5_000L)
        val ui = projectApprovalUi(approval, emptyList(), TEST_NOW)
        assertTrue(ui.expired)
        assertTrue(ui.expiresText.contains("过期"))
        assertFalse(ui.hasStructuredDetails)
        assertEquals("工具调用", ui.toolAction)
    }

    @Test
    fun targetsAreBoundedToEightEntries() {
        val writes = (1..12).joinToString(",") { "\"artifacts/file-$it.md\"" }
        val parsed = parseApprovalSummary("""{"tool":"artifact.write_text","write":[$writes]}""")
        assertEquals(12, parsed.writeTargets.size)
        val ui = projectApprovalUi(
            testApproval(),
            listOf(approvalRequestEvent("""{"tool":"artifact.write_text","write":[$writes]}""")),
            TEST_NOW
        )
        assertEquals(8, ui.writeTargets.size)
    }

    @Test
    fun longArgumentsAreTruncatedAndFlagged() {
        val longArgs = "a".repeat(3_000)
        val summary = """{"tool":"workspace.read_text","args":"$longArgs"}"""
        val ui = projectApprovalUi(
            testApproval(),
            listOf(approvalRequestEvent(summary)),
            TEST_NOW
        )
        assertTrue(ui.argumentsTruncated)
        assertTrue(requireNotNull(ui.argumentsSummary).endsWith("（内容已截断）"))
    }

    @Test
    fun approvalWithoutAnyRequestDoesNotFabricateToolOrTargets() {
        val ui = projectApprovalUi(testApproval(), emptyList(), TEST_NOW)
        assertEquals("工具调用", ui.toolAction)
        assertNull(ui.toolLabel)
        assertNull(ui.actionLabel)
        assertFalse(ui.hasStructuredDetails)
        assertEquals(AgentToolSourceKind.UNKNOWN, ui.source.kind)
        assertEquals("来源未知", ui.source.label)
        assertTrue(ui.readTargets.isEmpty())
        assertTrue(ui.writeTargets.isEmpty())
        assertTrue(ui.networkTargets.isEmpty())
        // The frozen record still binds this one-shot approval to a real identity.
        assertEquals("local-user", ui.principal)
        assertEquals("approval-1", ui.approvalIdShort)
        assertEquals("invocation-1", ui.invocationIdShort)
    }

    @Test
    fun foreignApprovalEventIsNeverUsedForThisApproval() {
        val requestForOtherInvocation = testEvent(
            sequence = 1,
            type = AgentEventTypes.TOOL_APPROVAL_REQUESTED,
            summary = """{"tool":"workspace.list"}""",
            causationId = "other-invocation"
        )
        val ui = projectApprovalUi(
            testApproval(invocationId = "invocation-1"),
            listOf(requestForOtherInvocation),
            TEST_NOW
        )
        assertNull("another call's tool must not appear here", ui.toolLabel)
        assertEquals("工具调用", ui.toolAction)
        assertFalse(ui.hasStructuredDetails)
        assertEquals(AgentToolSourceKind.UNKNOWN, ui.source.kind)
    }

    @Test
    fun approvalSourceActionAndBoundIdentityAreProjected() {
        val summary = """
            {"tool":"mcp:docs:search","source":"mcp:docs","action":"搜索文档",
             "read":["inputs/brief.md"],"write":["artifacts/outline.md"],
             "network":["https://docs.example.com/mcp"],"principal":"local-user",
             "state":"waiting_approval"}
        """.trimIndent()
        // A crafted event principal must not override the frozen approval record's identity.
        val ui = projectApprovalUi(testApproval(), listOf(approvalRequestEvent(summary)), TEST_NOW)

        assertEquals("mcp:docs:search", ui.toolLabel)
        assertEquals("搜索文档", ui.actionLabel)
        assertEquals("mcp:docs:search · 搜索文档", ui.toolAction)
        assertEquals(AgentToolSourceKind.MCP, ui.source.kind)
        assertEquals("MCP · docs", ui.source.label)
        assertEquals(listOf("inputs/brief.md"), ui.readTargets)
        assertEquals(listOf("artifacts/outline.md"), ui.writeTargets)
        assertEquals(listOf("https://docs.example.com/mcp"), ui.networkTargets)
        assertEquals("local-user", ui.principal)
        assertEquals("approval-1", ui.approvalIdShort)
        assertEquals("invocation-1", ui.invocationIdShort)
        assertTrue(ui.actionable)
    }

    @Test
    fun packageAndSkillSourcesAreBoundedAndExplicit() {
        fun sourceOf(summary: String): AgentToolSourceUi {
            val ui = projectApprovalUi(testApproval(), listOf(approvalRequestEvent(summary)), TEST_NOW)
            return ui.source
        }

        assertEquals(
            AgentToolSourceKind.PACKAGE,
            sourceOf("""{"tool":"pkg.research-basics.read_brief","source":"package:research-basics@1.0.0"}""").kind
        )
        assertEquals(
            "工具包 · research-basics @ 1.0.0",
            sourceOf("""{"tool":"pkg.research-basics.read_brief","source":"package:research-basics@1.0.0"}""").label
        )
        assertEquals(
            "Skill · summarize",
            sourceOf("""{"tool":"skill.summarize.condense","source":"skill:summarize"}""").label
        )
        assertEquals(
            "内置工具",
            sourceOf("""{"tool":"workspace.read_text"}""").label
        )
        assertEquals(
            "来源未知",
            sourceOf("""{"tool":"custom.do_thing","source":"not-a-known-source"}""").label
        )
    }

    @Test
    fun expiredApprovalDisablesActionsAndDoesNotClaimPending() {
        val ui = projectApprovalUi(
            testApproval(expiresInMillis = -5_000L),
            listOf(approvalRequestEvent("""{"tool":"workspace.list"}""")),
            TEST_NOW
        )
        assertTrue(ui.expired)
        assertFalse(ui.actionable)
        assertEquals("已过期", ui.statusLabel)
        assertTrue(ui.expiresText.contains("过期"))
    }

    @Test
    fun approvalDecisionsStayOneShotOnly() {
        assertEquals(
            listOf("ALLOW_ONCE", "DENY"),
            AgentApprovalDecision.entries.map { it.name }
        )
    }
}
