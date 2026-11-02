package com.muyuchat.feature.video.workspace.ui

import com.muyuchat.core.video.contract.VideoImportDiagnostic
import com.muyuchat.core.video.contract.VideoImportSeverity
import com.muyuchat.core.video.contract.VideoNode
import com.muyuchat.core.video.contract.VideoNodeTypeId
import com.muyuchat.core.video.contract.VideoNodeValue
import com.muyuchat.core.video.contract.VideoResourceState
import com.muyuchat.core.video.contract.VideoRunStage
import com.muyuchat.core.video.contract.VideoRunState
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure display mappings of the V1-05 library/run/import surfaces (no Android rendering). */
class VideoWorkspacePresentationTest {

    @Test
    fun `durations format as clock text without inventing hours`() {
        assertEquals("00:00", formatVideoDuration(0L))
        assertEquals("00:05", formatVideoDuration(5_000_000L))
        assertEquals("01:05", formatVideoDuration(65_000_000L))
        assertEquals("59:59", formatVideoDuration(3_599_000_000L))
        assertEquals("1:00:00", formatVideoDuration(3_600_000_000L))
        assertEquals("1:02:03", formatVideoDuration(3_723_000_000L))
        // Negative/unknown durations never render a negative clock.
        assertEquals("00:00", formatVideoDuration(-1L))
    }

    @Test
    fun `progress shows a percentage only when the owner reported both step and total`() {
        assertNull(videoRunProgress(null, 20L))
        assertNull(videoRunProgress(null, null))
        val countOnly = videoRunProgress(5L, null)
        assertEquals("步骤 5", countOnly)
        assertTrue(countOnly!!.contains("%").not())
        assertEquals("步骤 10 / 20（50%）", videoRunProgress(10L, 20L))
        assertEquals("步骤 21 / 20（100%）", videoRunProgress(21L, 20L))
    }

    @Test
    fun `run state stage and resource map to Chinese text labels`() {
        assertEquals("排队中", videoRunStateLabel(VideoRunState.QUEUED))
        assertEquals("采样中", videoRunStateLabel(VideoRunState.SAMPLING))
        assertEquals("已请求取消", videoRunStateLabel(VideoRunState.CANCEL_REQUESTED))
        assertEquals("已完成", videoRunStateLabel(VideoRunState.COMPLETED))
        assertEquals("失败", videoRunStateLabel(VideoRunState.FAILED))
        assertEquals("状态未知", videoRunStateLabel(VideoRunState.UNKNOWN))
        assertEquals("采样", videoRunStageLabel(VideoRunStage.SAMPLE))
        assertEquals("资源已释放", videoResourceStateLabel(VideoResourceState.RELEASED))
    }

    @Test
    fun `import diagnostics summarize severity counts`() {
        val diagnostics = listOf(
            VideoImportDiagnostic(VideoImportSeverity.ERROR, "3", "inputs.width", "宽度必须是 32 的倍数"),
            VideoImportDiagnostic(VideoImportSeverity.WARNING, "7", null, "缺少可选节点"),
            VideoImportDiagnostic(VideoImportSeverity.INFO, null, null, "已忽略注释"),
        )
        assertEquals("3 条诊断：1 个错误、1 个警告、1 个提示", importDiagnosticSummary(diagnostics))
        assertEquals("无诊断", importDiagnosticSummary(emptyList()))
    }

    @Test
    fun `diagnostic lines keep severity node and path anchors`() {
        val anchored = VideoImportDiagnostic(
            VideoImportSeverity.ERROR,
            "12",
            "inputs.seed",
            "种子超出范围",
        )
        assertEquals("[错误] 节点 12 · 路径 inputs.seed：种子超出范围", importDiagnosticLine(anchored))
        val bare = VideoImportDiagnostic(VideoImportSeverity.WARNING, null, " ", "工作流缺少编码节点")
        assertEquals("[警告] 工作流缺少编码节点", importDiagnosticLine(bare))
    }

    @Test
    fun `prompt summary keeps the full text for semantics and the sheet`() {
        val prompt = "a red ball bouncing on a wooden table, cinematic light"
        val node = VideoNode(
            nodeId = "node-1",
            typeId = VideoNodeTypeId.INPUT_TEXT.typeId,
            typeVersion = 1,
            params = mapOf("prompt" to VideoNodeValue.TextValue(prompt)),
        )
        assertEquals(prompt, VideoNodePresentations.summaryOf(node))
    }

    @Test
    fun `bytes and created time render fixed readable text`() {
        assertEquals("0 B", formatVideoBytes(0L))
        assertEquals("1.5 KB", formatVideoBytes(1_536L))
        assertEquals("2.0 MB", formatVideoBytes(2L * 1024L * 1024L))
        assertEquals(
            "2026-10-18 14:05",
            formatVideoCreatedAt(
                1_792_303_500_000L,
                ZoneId.of("Asia/Shanghai"),
            ),
        )
        assertEquals("480 × 832", formatVideoResolution(480L, 832L))
    }
}
