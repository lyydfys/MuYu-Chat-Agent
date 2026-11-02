package com.muyuchat.feature.video.workspace.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaIconSize
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.McaSwitch
import com.muyuchat.core.video.contract.VideoNode
import com.muyuchat.core.video.contract.VideoSubmitResult
import com.muyuchat.core.video.contract.VideoWorkflowIssue
import com.muyuchat.feature.video.workspace.ImportUiState
import com.muyuchat.feature.video.workspace.VideoWorkspaceViewModel
import com.muyuchat.feature.video.workspace.readComfyApiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The video workspace page (proposal §9.2). Simplified mode shows the executable chain of the
 * real draft — rows come from the document, values from the document, issues from the shared
 * validator; tapping a row opens the parameter sheet. Advanced mode swaps in the node canvas
 * and the accessible node list. The primary action is honest: unwired execution is refused with
 * a typed reason, never simulated.
 *
 * V1-05 adds the published-video library (playback/share/save-as/delete) through the top-bar
 * "作品" entry and the ComfyUI API-format import entry. Both are driven by the injected ports;
 * the page never fabricates a media row, a run stage or an import success.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoWorkspaceScreen(
    viewModel: VideoWorkspaceViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode by viewModel.mode.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val selectedNodeId by viewModel.selectedNodeId.collectAsState()
    val connectRejection by viewModel.lastConnectRejection.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val remoteState by viewModel.remoteState.collectAsState()
    var submitNotice by remember { mutableStateOf<String?>(null) }
    var showLibrary by rememberSaveable { mutableStateOf(false) }
    val document = viewModel.document()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            var fileName: String? = null
            val read = runCatching {
                withContext(Dispatchers.IO) {
                    fileName = queryDocumentDisplayName(context.contentResolver, uri)
                    val input = context.contentResolver.openInputStream(uri)
                        ?: error("无法打开所选文件")
                    input.use { stream -> readComfyApiJson(stream) }
                }
            }
            read.onSuccess { json ->
                viewModel.previewComfyApi(json, fileName)
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                viewModel.failImportedFile(
                    fileName,
                    "读取导入文件失败：${failure.message ?: failure.javaClass.simpleName}",
                )
            }
        }
    }

    val inputImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModel.setInputImage(uri.toString())
    }
    val inputImageUri by viewModel.inputImageUri.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("视频", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    McaIconButton(
                        contentDescription = "返回聊天",
                        onClick = onExit,
                        glyphSize = McaIconSize.M,
                    ) {
                        Icon(McaIcons.Back, contentDescription = null)
                    }
                },
                actions = {
                    McaIconButton(
                        contentDescription = "作品库",
                        onClick = { showLibrary = true },
                        glyphSize = McaIconSize.M,
                        modifier = Modifier.testTag("video.library_open"),
                        onClickLabel = "打开已发布的视频列表",
                    ) {
                        Icon(McaIcons.PlayArrow, contentDescription = null)
                    }
                    val simplifiedView = viewMode == VideoWorkspaceViewModel.ViewMode.SIMPLIFIED
                    val toggleView: () -> Unit = {
                        viewModel.setViewMode(
                            if (simplifiedView) {
                                VideoWorkspaceViewModel.ViewMode.ADVANCED
                            } else {
                                VideoWorkspaceViewModel.ViewMode.SIMPLIFIED
                            },
                        )
                    }
                    A11yLabeledControl(
                        contentDescription = if (simplifiedView) "切换到高级视图" else "切换到简化视图",
                        stateDescription = if (simplifiedView) "当前为简化视图" else "当前为高级视图",
                        onClick = toggleView,
                    ) {
                        McaButton(
                            onClick = toggleView,
                            style = McaButtonStyle.Text,
                            shape = ButtonDefaults.textShape,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(if (simplifiedView) "高级" else "简化")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            Column(Modifier.imePadding()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (viewMode == VideoWorkspaceViewModel.ViewMode.ADVANCED) {
                        McaIconButton(
                            contentDescription = "撤销",
                            onClick = viewModel::undo,
                            glyphSize = McaIconSize.M,
                        ) {
                            Icon(McaIcons.Undo, contentDescription = null)
                        }
                        McaIconButton(
                            contentDescription = "重做",
                            onClick = viewModel::redo,
                            glyphSize = McaIconSize.M,
                        ) {
                            Icon(McaIcons.Redo, contentDescription = null)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    val generate: () -> Unit = {
                        viewModel.submit { result ->
                            submitNotice = when (result) {
                                is VideoSubmitResult.Admitted -> "已提交任务"
                                is VideoSubmitResult.Rejected ->
                                    "无法提交：${result.code.name} — ${result.detail}"
                            }
                        }
                    }
                    A11yLabeledControl(
                        contentDescription =
                            if (viewModel.executionWired) "生成视频" else "生成视频（执行尚未接入）",
                        stateDescription =
                            if (viewModel.executionWired) null else "执行尚未接入，提交会被明确拒绝",
                        onClick = generate,
                    ) {
                        McaButton(
                            onClick = generate,
                            style = McaButtonStyle.Primary,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(if (viewModel.executionWired) "生成" else "生成（未接入）")
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .testTag("video.workspace")
                .padding(horizontal = 16.dp),
        ) {
            TemplateModeRow(mode, viewModel)
            Text(
                "默认走高性能引擎（sdcpp_external）；内置 MCA 回退路径已实测 64×64×9×1（约 4–6 分钟），480×832 超过 20 分钟。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .testTag("video.experimental_notice"),
            )
            ExternalEngineRow(
                enabled = viewModel.externalEngineEnabled.collectAsState().value,
                onCheckedChange = viewModel::setExternalEngineEnabled,
            )
            FullVaeRow(
                enabled = viewModel.fullVaeDecode.collectAsState().value,
                onCheckedChange = viewModel::setFullVaeDecode,
            )
            VideoModelAssetsSection(viewModel)
            VideoRecentRunsSection(viewModel)
            if (mode == VideoWorkspaceViewModel.TemplateMode.IMAGE_TO_VIDEO) {
                InputImageRow(
                    uri = inputImageUri,
                    onPick = { inputImagePicker.launch(arrayOf("image/*")) },
                    onClear = { viewModel.setInputImage(null) },
                )
            }
            ComfyImportRow(
                importWired = viewModel.importWired,
                onImport = { importPicker.launch(arrayOf("application/json", "text/json")) },
            )
            submitNotice?.let { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            connectRejection?.let { rejection ->
                Text(
                    "连线被拒绝：$rejection",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (viewMode == VideoWorkspaceViewModel.ViewMode.SIMPLIFIED) {
                SimplifiedChainSection(viewModel)
            } else {
                AdvancedSection(viewModel)
            }

            VideoRunStatusSection(viewModel)
            RemoteComfySection(
                wired = viewModel.remoteWired,
                state = remoteState,
                onProbe = viewModel::probeRemote,
                onSubmit = { baseUrl, apiKey, targetedCancel ->
                    viewModel.submitRemote(baseUrl, apiKey, targetedCancel) { result ->
                        submitNotice = when (result) {
                            is VideoSubmitResult.Admitted -> "已提交远端任务"
                            is VideoSubmitResult.Rejected -> "无法提交远端：${result.code.name} ${result.detail}"
                        }
                    }
                },
            )
            Spacer(Modifier.height(20.dp))
        }
    }

    val sheetNode = selectedNodeId?.let { id -> document.node(id) }
    if (sheetNode != null) {
        VideoNodeSheet(
            node = sheetNode,
            issues = viewModel.issues().filter { it.nodeId == sheetNode.nodeId },
            viewModel = viewModel,
            onDismiss = { viewModel.selectNode(null) },
        )
    }

    VideoLibraryHost(
        viewModel = viewModel,
        visible = showLibrary,
        onDismiss = { showLibrary = false },
    )

    if (importState !is ImportUiState.Idle) {
        VideoImportDialog(
            state = importState,
            onSave = viewModel::saveImportedRevision,
            onDismiss = viewModel::dismissImport,
        )
    }
}

@Composable
private fun ComfyImportRow(importWired: Boolean, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        A11yLabeledControl(
            contentDescription = "导入 ComfyUI（API 格式）",
            stateDescription = if (!importWired) "导入端口尚未接入" else null,
            enabled = importWired,
            onClick = onImport,
            modifier = Modifier.fillMaxWidth(),
        ) {
            McaButton(
                onClick = onImport,
                style = McaButtonStyle.Outlined,
                enabled = importWired,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("video.import_open"),
                leadingIcon = {
                    Icon(
                        McaIcons.UploadFile,
                        contentDescription = null,
                        modifier = Modifier.size(McaIconSize.S),
                    )
                },
            ) {
                Text("导入 ComfyUI（API 格式）")
            }
        }
        if (!importWired) {
            Text(
                "ComfyUI 导入尚未接入：预览与保存需要注入导入端口。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun TemplateModeRow(
    mode: VideoWorkspaceViewModel.TemplateMode,
    viewModel: VideoWorkspaceViewModel,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModeChip(
            label = "文生视频",
            selected = mode == VideoWorkspaceViewModel.TemplateMode.TEXT_TO_VIDEO,
            onClick = { viewModel.switchTemplate(VideoWorkspaceViewModel.TemplateMode.TEXT_TO_VIDEO) },
        )
        ModeChip(
            label = "图生视频",
            selected = mode == VideoWorkspaceViewModel.TemplateMode.IMAGE_TO_VIDEO,
            onClick = { viewModel.switchTemplate(VideoWorkspaceViewModel.TemplateMode.IMAGE_TO_VIDEO) },
        )
    }
}

@Composable
private fun InputImageRow(
    uri: String?,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            A11yLabeledControl(
                contentDescription = if (uri == null) "选择输入图片" else "更换输入图片",
                onClick = onPick,
                modifier = Modifier.weight(1f),
            ) {
                McaButton(
                    onClick = onPick,
                    style = McaButtonStyle.Outlined,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("video.input_image_pick"),
                ) {
                    Text(if (uri == null) "选择输入图片" else "更换输入图片")
                }
            }
            if (uri != null) {
                A11yLabeledControl(
                    contentDescription = "移除已选输入图片",
                    onClick = onClear,
                ) {
                    McaButton(
                        onClick = onClear,
                        style = McaButtonStyle.Text,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("video.input_image_clear"),
                    ) {
                        Text("移除")
                    }
                }
            }
        }
        Text(
            if (uri == null) {
                "图生视频需要一张本地图片；未选择时提交会被明确拒绝。"
            } else {
                "已选择输入图片（图片会在提交时复制到本任务私有暂存区）。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
internal fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.testTag("video.mode_chip"),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.Unspecified,
            modifier = Modifier
                .clickable(onClick = onClick)
                .semantics {
                    contentDescription = if (selected) "$label（已选）" else label
                    role = Role.Button
                    this.selected = selected
                }
                .padding(horizontal = 14.dp, vertical = 14.dp),
        )
    }
}

/**
 * Decode-quality switch. Default ON: toggling only changes the decoder frozen into FUTURE submits;
 * existing runs and retries keep the decoder recorded in their snapshot.
 */
@Composable
private fun FullVaeRow(enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(top = 4.dp, bottom = 4.dp)
            .testTag("video.full_vae_toggle")
            .semantics {
                contentDescription = "完整 Wan VAE 解码（空间分块）"
                stateDescription = if (enabled) "已启用（默认）" else "已关闭"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "完整 Wan VAE 解码",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                if (enabled) {
                    "已启用（默认）：完整 VAE + 空间分块，画质更好；480p 一段约 30–40 分钟"
                } else {
                    "关闭：TAE 解码，约 6 分钟；切换只影响之后的提交"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        McaSwitch(
            checked = enabled,
            onCheckedChange = null,
            modifier = Modifier.testTag("video.full_vae_switch"),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun ExternalEngineRow(enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(top = 8.dp, bottom = 4.dp)
            .testTag("video.external_engine_toggle")
            .semantics {
                contentDescription = "高性能视频引擎（sdcpp_external）"
                stateDescription = if (enabled) "已启用（默认）" else "已关闭"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "高性能视频引擎",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                if (enabled) {
                    "已启用（默认）：之后的提交使用 sdcpp_external（stable-diffusion.cpp 内核）"
                } else {
                    "关闭：使用内置 MCA 引擎；切换只影响之后的提交"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        McaSwitch(
            checked = enabled,
            onCheckedChange = null,
            modifier = Modifier.testTag("video.external_engine_switch"),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun SimplifiedChainSection(viewModel: VideoWorkspaceViewModel) {
    val document = viewModel.document()
    val issues = viewModel.issues()
    Column(Modifier.padding(top = 8.dp)) {
        VideoNodePresentations.simplifiedChainOrder.forEach { type ->
            document.nodes.filter { it.typeId == type.typeId }.forEach { node ->
                NodeChainRow(
                    node = node,
                    issues = issues.filter { it.nodeId == node.nodeId },
                    onClick = { viewModel.selectNode(node.nodeId) },
                )
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun NodeChainRow(
    node: VideoNode,
    issues: List<VideoWorkflowIssue>,
    onClick: () -> Unit,
) {
    val hasIssues = issues.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
            .testTag("video.chain_row")
            .semantics {
                contentDescription = buildString {
                    append(VideoNodePresentations.labelOf(node.typeId))
                    append("，")
                    append(VideoNodePresentations.summaryOf(node))
                    if (hasIssues) append("，有 ${issues.size} 个问题")
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    VideoNodePresentations.labelOf(node.typeId),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (hasIssues) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "!",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                VideoNodePresentations.summaryOf(node),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            McaIcons.Forward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun AdvancedSection(viewModel: VideoWorkspaceViewModel) {
    VideoGraphCanvas(
        viewModel = viewModel,
        modifier = Modifier.fillMaxWidth().height(520.dp),
    )
}
