package com.muyuchat.feature.video.workspace.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaHairline
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.McaIconSize
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaShape
import com.muyuchat.core.video.contract.VideoDeleteResult
import com.muyuchat.core.video.contract.VideoMediaProjection
import com.muyuchat.feature.video.workspace.VideoWorkspaceViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Published-video library (V1-05): a large dialog over the workspace that renders the owner's
 * media projection. Thumbnails come from MediaMetadataRetriever on an IO thread with a retryable
 * placeholder; playback opens the platform player; share/save-as only use the opaque content
 * URI. Deleting is confirmed first and only the port's Deleted result closes the confirmation —
 * failed/unknown results are shown with their real code and detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VideoLibraryHost(
    viewModel: VideoWorkspaceViewModel,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val media by viewModel.media.collectAsState()
    val loaded by viewModel.mediaLoaded.collectAsState()
    val loadError by viewModel.mediaError.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var notice by remember { mutableStateOf<String?>(null) }
    var playingMediaId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteMediaId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteBusy by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var savingMediaId by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf<VideoMediaProjection?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(VIDEO_SAVE_MIME_TYPE),
    ) { target ->
        val source = pendingSave
        pendingSave = null
        if (target == null || source == null) return@rememberLauncherForActivityResult
        savingMediaId = source.mediaId.value
        notice = null
        scope.launch {
            val outcome = runCatching {
                copyVideoToDocument(context, source.reference, target)
            }
            savingMediaId = null
            outcome.onSuccess { copied ->
                notice = "已另存 ${formatVideoBytes(copied)} 到所选位置。"
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                notice = "另存失败：${failure.message ?: failure.javaClass.simpleName}"
            }
        }
    }

    val playing = media.firstOrNull { it.mediaId.value == playingMediaId }
    val deleteTarget = media.firstOrNull { it.mediaId.value == deleteMediaId }
    LaunchedEffect(media, playingMediaId, deleteMediaId) {
        if (playingMediaId != null && playing == null) playingMediaId = null
        if (deleteMediaId != null && deleteTarget == null) deleteMediaId = null
    }

    VideoLibraryDialog(
        media = media,
        loaded = loaded,
        loadError = loadError,
        notice = notice,
        savingMediaId = savingMediaId,
        canRetry = viewModel.executionWired,
        onRetry = viewModel::refreshMedia,
        onPlay = { item -> playingMediaId = item.mediaId.value },
        onShare = { item -> notice = shareVideo(context, item) ?: "已打开分享面板。" },
        onSave = { item ->
            if (savingMediaId == null) {
                pendingSave = item
                try {
                    saveLauncher.launch(defaultVideoExportName())
                } catch (failure: Throwable) {
                    pendingSave = null
                    notice = "另存失败：${failure.message ?: failure.javaClass.simpleName}"
                }
            }
        },
        onDelete = { item ->
            deleteMediaId = item.mediaId.value
            deleteError = null
        },
        onDismiss = onDismiss,
    )

    if (playing != null) {
        VideoPlayerDialog(
            media = playing,
            onDismiss = { playingMediaId = null },
        )
    }

    if (deleteTarget != null) {
        VideoDeleteConfirmDialog(
            busy = deleteBusy,
            error = deleteError,
            onConfirm = {
                if (!deleteBusy) {
                    deleteBusy = true
                    deleteError = null
                    viewModel.deleteMedia(deleteTarget.mediaId) { result ->
                        deleteBusy = false
                        when (result) {
                            is VideoDeleteResult.Deleted -> {
                                deleteMediaId = null
                                deleteError = null
                                notice = "已删除 MCA 私有副本，不影响已导出副本或原始输入。"
                            }

                            is VideoDeleteResult.Unknown -> {
                                deleteError = "媒体记录不存在或已被移除（${result.mediaId.value}），列表以端口返回为准。"
                            }

                            is VideoDeleteResult.Failed -> {
                                deleteError = "删除失败：${result.code.name} — ${result.detail}"
                            }
                        }
                    }
                }
            },
            onDismiss = {
                if (!deleteBusy) {
                    deleteMediaId = null
                    deleteError = null
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoLibraryDialog(
    media: List<VideoMediaProjection>,
    loaded: Boolean,
    loadError: String?,
    notice: String?,
    savingMediaId: String?,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onPlay: (VideoMediaProjection) -> Unit,
    onShare: (VideoMediaProjection) -> Unit,
    onSave: (VideoMediaProjection) -> Unit,
    onDelete: (VideoMediaProjection) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 14.dp)
                .testTag("video.library"),
            color = MaterialTheme.colorScheme.background,
            shape = McaShape.Sheet,
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = { Text("作品", style = MaterialTheme.typography.titleMedium) },
                        navigationIcon = {
                            McaIconButton(
                                contentDescription = "关闭作品库",
                                onClick = onDismiss,
                                glyphSize = McaIconSize.M,
                                modifier = Modifier.testTag("video.library_close"),
                            ) {
                                Icon(McaIcons.Close, contentDescription = null)
                            }
                        },
                        actions = {
                            if (loaded && loadError == null && media.isNotEmpty()) {
                                Text(
                                    "${media.size} 个视频",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 16.dp),
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                },
            ) { innerPadding ->
                Column(Modifier.fillMaxSize().padding(innerPadding)) {
                    if (notice != null) {
                        Text(
                            notice,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (notice.contains("失败") || notice.contains("已阻止")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .testTag("video.library_notice"),
                        )
                    }
                    when {
                        loadError != null -> LibraryErrorState(loadError, canRetry, onRetry)
                        !loaded -> LibraryLoadingState()
                        media.isEmpty() -> LibraryEmptyState()
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize().testTag("video.library_list"),
                            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(media, key = { it.mediaId.value }) { item ->
                                VideoMediaCard(
                                    media = item,
                                    saving = savingMediaId == item.mediaId.value,
                                    deleteEnabled = savingMediaId != item.mediaId.value,
                                    onPlay = { onPlay(item) },
                                    onShare = { onShare(item) },
                                    onSave = { onSave(item) },
                                    onDelete = { onDelete(item) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryLoadingState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            "正在读取作品…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LibraryEmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).testTag("video.library_empty"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            McaIcons.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("还没有已发布的视频", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "完成一次视频生成并成功发布后，成片会出现在这里。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LibraryErrorState(message: String, canRetry: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).testTag("video.library_error"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            McaIcons.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text("作品读取失败", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        if (canRetry) {
            A11yLabeledControl(
                contentDescription = "重新读取作品库",
                onClick = onRetry,
            ) {
                McaButton(
                    onClick = onRetry,
                    style = McaButtonStyle.Outlined,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("video.library_retry"),
                ) {
                    Text("重试")
                }
            }
        } else {
            Text(
                "接入视频端口后可重新读取。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VideoMediaCard(
    media: VideoMediaProjection,
    saving: Boolean,
    deleteEnabled: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = McaShape.Card,
        border = BorderStroke(McaHairline.Width, McaHairline.default()),
        modifier = Modifier.fillMaxWidth().testTag("video.media_row"),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                VideoThumbnail(
                    media = media,
                    modifier = Modifier.width(132.dp).height(74.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        formatVideoDuration(media.durationUs),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "${formatVideoResolution(media.width, media.height)} · ${media.fps} fps",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatVideoBytes(media.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "创建于 ${formatVideoCreatedAt(media.createdAtMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 8.dp),
            )
            val mediaIdentity =
                "${formatVideoResolution(media.width, media.height)}，创建于 ${formatVideoCreatedAt(media.createdAtMs)}"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (saving) {
                    Text(
                        "正在另存…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                MediaAction(
                    label = "播放",
                    icon = McaIcons.PlayArrow,
                    contentDescription = "播放视频，$mediaIdentity",
                    testTag = "video.media_play",
                    onClick = onPlay,
                )
                MediaAction(
                    label = "分享",
                    icon = McaIcons.Share,
                    contentDescription = "分享视频，$mediaIdentity",
                    testTag = "video.media_share",
                    onClick = onShare,
                )
                MediaAction(
                    label = if (saving) "另存中" else "另存",
                    icon = McaIcons.FileDownload,
                    contentDescription = if (saving) "正在另存视频，$mediaIdentity" else "另存视频，$mediaIdentity",
                    testTag = "video.media_save",
                    onClick = onSave,
                    enabled = !saving,
                )
                MediaAction(
                    label = "删除",
                    icon = McaIcons.Delete,
                    contentDescription = "删除视频，$mediaIdentity",
                    testTag = "video.media_delete",
                    onClick = onDelete,
                    enabled = deleteEnabled,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun MediaAction(
    label: String,
    icon: ImageVector,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        McaIconButton(
            contentDescription = contentDescription,
            onClick = onClick,
            enabled = enabled,
            tint = tint,
            glyphSize = McaIconSize.M,
            modifier = Modifier.testTag(testTag),
        ) {
            Icon(icon, contentDescription = null)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) tint else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

