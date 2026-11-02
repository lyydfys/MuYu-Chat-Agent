package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentModelLoadState
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRetryability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWorkspaceFormattingTest {

    @Test
    fun relativeTimeUsesHumanBucketsAndClampsFutureValues() {
        assertEquals("刚刚", formatRelativeTime(TEST_NOW, TEST_NOW))
        assertEquals("刚刚", formatRelativeTime(TEST_NOW, TEST_NOW + 5_000L))
        assertEquals("刚刚", formatRelativeTime(TEST_NOW, TEST_NOW - 59_999L))
        assertEquals("1 分钟前", formatRelativeTime(TEST_NOW, TEST_NOW - 60_000L))
        assertEquals("59 分钟前", formatRelativeTime(TEST_NOW, TEST_NOW - 3_599_999L))
        assertEquals("1 小时前", formatRelativeTime(TEST_NOW, TEST_NOW - 3_600_000L))
        assertEquals("23 小时前", formatRelativeTime(TEST_NOW, TEST_NOW - 86_399_999L))
        assertEquals("1 天前", formatRelativeTime(TEST_NOW, TEST_NOW - 86_400_000L))
        assertEquals("30 天前", formatRelativeTime(TEST_NOW, TEST_NOW - 30L * 86_400_000L))
    }

    @Test
    fun bytesFormattingHandlesBoundariesAndDoesNotGoNegative() {
        assertEquals("0 B", formatBytes(-1L))
        assertEquals("0 B", formatBytes(0L))
        assertEquals("1023 B", formatBytes(1_023L))
        assertEquals("1.0 KB", formatBytes(1_024L))
        assertEquals("1.5 KB", formatBytes(1_536L))
        assertEquals("1.0 MB", formatBytes(1_024L * 1_024L))
        assertEquals("1.00 GB", formatBytes(1_024L * 1_024L * 1_024L))
        assertEquals("1.50 GB", formatBytes(1_610_612_736L))
    }

    @Test
    fun shortHashIsBoundedAndBlankIsExplicit() {
        assertEquals("—", formatShortHash(null))
        assertEquals("—", formatShortHash("  "))
        assertEquals("0123456789ab", formatShortHash("0123456789ab"))
        val value = "abcdef123456" + "0".repeat(52)
        assertEquals("abcdef123456…", formatShortHash(value))
    }

    @Test
    fun durationFormattingKeepsMinuteAndHourBoundaries() {
        assertEquals("0 秒", formatDurationShort(-5L))
        assertEquals("59 秒", formatDurationShort(59_000L))
        assertEquals("1 分 0 秒", formatDurationShort(60_000L))
        assertEquals("59 分 59 秒", formatDurationShort(3_599_000L))
        assertEquals("1 时 0 分", formatDurationShort(3_600_000L))
    }

    @Test
    fun loadBadgeAndActionCoverBothLocalLoadStates() {
        assertEquals("已加载", modelLoadBadge(AgentModelLoadState.LOADED))
        assertEquals("未加载", modelLoadBadge(AgentModelLoadState.UNLOADED))
        assertEquals("去聊天页加载", MODEL_UNLOADED_ACTION)
        assertEquals("直接加载", MODEL_DIRECT_LOAD_ACTION)
        assertEquals("本地模型", LOCAL_MODEL_SECTION_TITLE)
        assertEquals("云端模型", CLOUD_MODEL_SECTION_TITLE)
        val hint = modelUnloadedHint("Qwen3 4B")
        assertTrue(hint.contains("Qwen3 4B"))
        assertTrue(hint.contains("未加载"))
        assertTrue(modelUnloadedPickerHint("Qwen3 4B").contains("直接加载"))
        assertEquals("模型未加载", statusLabel(AgentWorkspaceStatus.MODEL_UNLOADED))
    }

    @Test
    fun selectionReasonIsOneLineAndContractSourced() {
        assertEquals(
            "该协议暂不支持 Agent",
            modelSelectionReason(
                protocolMode = AgentProtocolMode.NONE,
                loadState = AgentModelLoadState.LOADED,
                availableOffline = true,
                networkEnabled = true
            )
        )
        assertEquals(
            MODEL_UNLOADED_SELECTION_REASON,
            modelSelectionReason(
                protocolMode = AgentProtocolMode.PROMPTED_V1,
                loadState = AgentModelLoadState.UNLOADED,
                availableOffline = true,
                networkEnabled = true
            )
        )
        assertEquals(
            MODEL_OFFLINE_SELECTION_REASON,
            modelSelectionReason(
                protocolMode = AgentProtocolMode.NATIVE,
                loadState = AgentModelLoadState.LOADED,
                availableOffline = false,
                networkEnabled = false
            )
        )
        assertNull(
            modelSelectionReason(
                protocolMode = AgentProtocolMode.PROMPTED_V1,
                loadState = AgentModelLoadState.LOADED,
                availableOffline = true,
                networkEnabled = false
            )
        )
    }

    @Test
    fun everyWorkspaceStatusHasANonBlankLabelAndTone() {
        AgentWorkspaceStatus.entries.forEach { status ->
            assertTrue("label for $status", statusLabel(status).isNotBlank())
            statusTone(status)
        }
        assertEquals("已完成", statusLabel(AgentWorkspaceStatus.SUCCEEDED))
        assertEquals("MCP 已断线", statusLabel(AgentWorkspaceStatus.MCP_OFFLINE))
        assertEquals("Skill 缺少工具", statusLabel(AgentWorkspaceStatus.SKILL_MISSING_TOOL))
        assertEquals("协议不支持", statusLabel(AgentWorkspaceStatus.PROTOCOL_UNSUPPORTED))
        assertEquals("已到运行上限", statusLabel(AgentWorkspaceStatus.BUDGET_LIMIT))
        assertEquals("没有可用模型", statusLabel(AgentWorkspaceStatus.NO_MODEL))
    }

    @Test
    fun protocolAndAvailabilityLabelsAreExplicit() {
        assertEquals("原生协议", protocolLabel(AgentProtocolMode.NATIVE))
        assertEquals("提示词协议 V1", protocolLabel(AgentProtocolMode.PROMPTED_V1))
        assertEquals("不支持 Agent 协议", protocolLabel(AgentProtocolMode.NONE))
        assertEquals("离线可用", offlineLabel(true))
        assertEquals("需要联网", offlineLabel(false))
        assertEquals("可安全重试", retryabilityLabel(AgentRetryability.SAFE_RETRY))
    }

    @Test
    fun boundedTextTruncatesWithAnExplicitMarker() {
        assertNull(boundedText(null, 10))
        assertNull(boundedText("   ", 10))
        assertEquals("short", boundedText("short", 10))
        val truncated = requireNotNull(boundedText("x".repeat(50), 10))
        assertTrue(truncated.startsWith("x".repeat(10)))
        assertTrue(truncated.endsWith("（内容已截断）"))
    }

    @Test
    fun clockTimeUsesAStableHourMinuteShape() {
        assertTrue(formatClockTime(TEST_NOW).matches(Regex("^\\d{2}:\\d{2}$")))
    }
}
