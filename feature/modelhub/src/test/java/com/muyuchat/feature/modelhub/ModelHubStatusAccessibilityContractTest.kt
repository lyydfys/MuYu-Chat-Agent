package com.muyuchat.feature.modelhub

import com.muyuchat.core.download.DownloadStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelHubStatusAccessibilityContractTest {

    @Test
    fun downloadFileNameDoesNotHideTemporaryStatus() {
        val state = completedState("后续状态：已切换到新模型")
        val presentation = modelHubHeaderPresentation(state)

        assertNotNull("完成摘要应继续可见", presentation.downloadSummary)
        assertEquals("后续状态：已切换到新模型", presentation.statusMessage)
        assertNull(
            "下载摘要不得继续借用临时 status，否则后续状态会被误当成下载明细",
            presentation.downloadSummary?.statusMessage
        )
        assertEquals("后续状态：已切换到新模型", state.statusMessage)
        assertEquals(DownloadStatus.DONE, state.downloadStatus)
    }

    @Test
    fun completedSummaryCanBeDismissedWithoutClearingAuthoritativeDownloadState() {
        val state = completedState("模型已保存")
        val presentation = modelHubHeaderPresentation(
            state = state,
            dismissedCompletedDownloadKey = "task-1"
        )

        assertNull("仅当前完成任务的摘要应被页面局部收起", presentation.downloadSummary)
        assertEquals("模型已保存", presentation.statusMessage)
        assertEquals("task-1", state.downloadTaskId)
        assertEquals("model.gguf", state.downloadFileName)
        assertEquals(DownloadStatus.DONE, state.downloadStatus)
        assertEquals(ModelHubDownloadPhase.COMPLETED, state.downloadPhase)
        assertEquals(10L, state.downloadedBytes)
    }

    @Test
    fun failedSummaryStaysVisibleAndFollowUpStatusUsesItsOwnSeverity() {
        val state = completedState("已删除云端模型：Alpha").copy(
            downloadStatus = DownloadStatus.FAILED,
            downloadPhase = ModelHubDownloadPhase.EXECUTION_CHECK
        )
        val presentation = modelHubHeaderPresentation(
            state = state,
            dismissedCompletedDownloadKey = "task-1"
        )

        assertNotNull("失败摘要不能被完成态 dismiss 隐藏", presentation.downloadSummary)
        assertFalse(presentation.canDismissDownloadSummary)
        assertEquals("已删除云端模型：Alpha", presentation.statusMessage)
        assertFalse("后续成功状态不能被旧下载失败染红", presentation.statusIsError)
        assertTrue(modelHubStatusMessageIsError("本机执行检查失败：缺少组件"))
    }

    @Test
    fun deleteActionNamesIncludeTheCloudTargetName() {
        val model = CloudModelUiItem(
            id = "cloud-1",
            kind = "CHAT",
            displayName = "Alpha 云端推理",
            providerName = "OpenAI-compatible",
            protocolLabel = "OpenAI",
            modelName = "alpha-model",
            baseUrl = "https://example.test/v1"
        )

        assertTrue(cloudModelDeleteActionLabel(model).contains("Alpha 云端推理"))
    }

    @Test
    fun deleteActionNamesIncludeLocalTargetNames() {
        assertTrue(localModelDeleteActionLabel("Local Alpha").contains("Local Alpha"))
        assertTrue(localImageModelDeleteActionLabel("Image Alpha").contains("Image Alpha"))
    }

    @Test
    fun touchedSourcesUseTargetAwareDeleteNamesAndKeepDecorativeIconsSilent() {
        val cloud = sourceFile("CloudModelsSection.kt")
        val local = sourceFile("LocalModelsSection.kt")

        assertTrue(cloud.contains("Icon(Icons.Rounded.Delete, contentDescription = cloudModelDeleteActionLabel(model))"))
        assertTrue(cloud.contains("Icon(Icons.Rounded.Edit, contentDescription = null"))
        assertFalse(cloud.contains("contentDescription = \"删除云端模型\""))

        // The local delete icon now also carries an 18dp box and a muted tint, so the assertion
        // checks the pieces instead of freezing one formatting of the call.
        assertTrue(local.contains("Icons.Rounded.Delete,"))
        assertTrue(local.contains("contentDescription = localModelDeleteActionLabel(model.displayName)"))
        assertTrue(local.contains("modifier = Modifier.size(18.dp)"))
        assertTrue(local.contains("tint = MaterialTheme.colorScheme.onSurfaceVariant"))
        assertTrue(local.contains("Icon(Icons.Rounded.Delete, contentDescription = localImageModelDeleteActionLabel(model.displayName))"))
        assertTrue(local.contains("Icon(Icons.Rounded.CheckCircle, contentDescription = null"))
        assertFalse(local.contains("contentDescription = \"删除本地模型\""))
        assertFalse(local.contains("contentDescription = \"删除本地图像生成引擎\""))
    }

    @Test
    fun touchedSourcesDoNotIntroduceEmptyClickHandlers() {
        val screen = sourceFile("ModelHubScreen.kt")
        val sources = listOf(
            screen,
            sourceFile("CloudModelsSection.kt"),
            sourceFile("LocalModelsSection.kt"),
            sourceFile("MarketAndDownloads.kt")
        )
        val emptyClick = Regex("""onClick\s*=\s*\{\s*\}""")

        sources.forEach { source ->
            assertFalse("受控写集内不得新增空点击", emptyClick.containsMatchIn(source))
        }
        assertTrue(screen.contains("dismissedCompletedDownloadKey = it"))
        assertTrue(screen.contains("presentation.statusMessage?.let"))
        assertFalse(
            Regex("""if\s*\(\s*state\.downloadFileName\s*==\s*null\s*\)\s*\{""")
                .containsMatchIn(screen)
        )
        assertTrue(screen.contains("StatusMessageCard(message = message, isError = presentation.statusIsError)"))
    }

    @Test
    fun marketTagsUseStaticLabelsWithoutReplacingRealActions() {
        val source = sourceFile("MarketAndDownloads.kt")

        assertFalse("Market tags must not use a clickable AssistChip", source.contains("AssistChip"))
        assertTrue(source.contains("tags.forEach { tag -> StaticStatusTag(tag.substringAfter(':')) }"))

        val tagBody = source
            .substringAfter("private fun StaticStatusTag")
            .substringBefore("@Composable")
        assertFalse("StaticStatusTag must not accept an onClick", tagBody.contains("onClick"))
        assertFalse("StaticStatusTag must not add a card layer", tagBody.contains("Card("))
        assertFalse("StaticStatusTag must not add a shadow", tagBody.contains("shadowElevation"))
        assertFalse("StaticStatusTag must not introduce an accent color", tagBody.contains("primary"))
        assertFalse("StaticStatusTag must not introduce an accent color", tagBody.contains("secondary"))
        assertTrue(tagBody.contains("MaterialTheme.colorScheme.surfaceVariant"))
        assertTrue(tagBody.contains("heightIn(min = 32.dp)"))
        assertTrue(tagBody.contains("padding(horizontal = 16.dp"))
        assertFalse(tagBody.contains("heightIn(min = 48.dp)"))
        assertTrue(tagBody.contains("MaterialTheme.typography.labelLarge"))

        assertTrue(source.contains("Button(onClick = onShowFiles, enabled = enabled"))
        assertTrue(source.contains("OutlinedButton(onClick = onOpenPage"))
        assertTrue(source.contains("Button(onClick = onDownload, enabled = enabled && isDownloadableModel"))
    }

    private fun completedState(statusMessage: String): ModelHubUiState = ModelHubUiState(
        downloadTaskId = "task-1",
        downloadFileName = "model.gguf",
        downloadedBytes = 10L,
        downloadTotalBytes = 10L,
        downloadStatus = DownloadStatus.DONE,
        downloadPhase = ModelHubDownloadPhase.COMPLETED,
        statusMessage = statusMessage
    )

    private fun sourceFile(fileName: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (directory != null) {
            val candidate = File(
                directory,
                "feature/modelhub/src/main/java/com/muyuchat/feature/modelhub/$fileName"
            )
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate modelhub source file: $fileName")
    }
}
