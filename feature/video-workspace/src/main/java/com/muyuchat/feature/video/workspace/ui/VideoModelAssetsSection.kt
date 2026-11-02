package com.muyuchat.feature.video.workspace.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaHairline
import com.muyuchat.core.designsystem.McaShape
import com.muyuchat.core.video.contract.VideoModelAssetComponentState
import com.muyuchat.core.video.contract.VideoModelAssetPhase
import com.muyuchat.core.video.contract.VideoModelAssetState
import com.muyuchat.feature.video.workspace.VideoWorkspaceViewModel

/**
 * Production acquisition surface for the Wan2.2 TI2V-5B components. It shows the pinned roles,
 * exact bytes and the honest verification state; the process-level port owns the transfer, so
 * leaving the page does not cancel it and re-entering sees the same progress.
 */
@Composable
internal fun VideoModelAssetsSection(viewModel: VideoWorkspaceViewModel) {
    val wired = viewModel.modelAssetsWired
    val state by viewModel.modelAssets.collectAsState()
    val current = state
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = McaShape.Card,
        border = BorderStroke(McaHairline.Width, McaHairline.default()),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .testTag("video.model_assets")
            .semantics {
                contentDescription = buildString {
                    append("本地模型组件")
                    if (current != null) {
                        append("，")
                        append(phaseLabel(current.phase))
                        append("，已下载 ")
                        append(formatBytes(current.downloadedBytes))
                        append(" / ")
                        append(formatBytes(current.totalBytes))
                    }
                }
            },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(
                    "本地模型组件",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Wan2.2 TI2V-5B",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            if (!wired) {
                Text(
                    "组件下载端口未接入：当前版本无法在此获取模型组件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                return@Column
            }
            if (current == null) {
                Text(
                    "正在读取组件状态…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text(
                phaseLabel(current.phase),
                style = MaterialTheme.typography.bodySmall,
                color = when (current.phase) {
                    VideoModelAssetPhase.READY -> MaterialTheme.colorScheme.primary
                    VideoModelAssetPhase.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            current.message?.let { message ->
                Spacer(Modifier.height(2.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (current.phase == VideoModelAssetPhase.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.testTag("video.model_assets_message"),
                )
            }
            Spacer(Modifier.height(6.dp))
            current.components.forEach { component ->
                VideoModelAssetComponentRow(component)
            }
            // Byte progress only exists while downloading (or paused mid-download). During a local
            // integrity check `active` is also true, but nothing is transferring — showing a bar and
            // "0 B / 7.5 GB" there reads as a stalled download.
            val showsTransfer = current.phase == VideoModelAssetPhase.DOWNLOADING ||
                current.phase == VideoModelAssetPhase.CANCELLED
            if (showsTransfer && current.totalBytes > 0L) {
                Spacer(Modifier.height(8.dp))
                val fraction = if (current.totalBytes <= 0L) {
                    0f
                } else {
                    (current.downloadedBytes.toFloat() / current.totalBytes.toFloat()).coerceIn(0f, 1f)
                }
                if (current.phase == VideoModelAssetPhase.DOWNLOADING) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .testTag("video.model_assets_progress")
                            .semantics {
                                stateDescription = "已下载 ${formatBytes(current.downloadedBytes)}，共 ${formatBytes(current.totalBytes)}"
                            },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                } else if (current.active) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .testTag("video.model_assets_progress")
                            .semantics { stateDescription = phaseLabel(current.phase) },
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                Text(
                    "${formatBytes(current.downloadedBytes)} / ${formatBytes(current.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            current.errorCode?.let { code ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "错误码：$code",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!current.ready && current.canDownload) {
                    val downloadLabel =
                        if (current.phase == VideoModelAssetPhase.CANCELLED) "继续下载缺失组件" else "下载缺失组件"
                    A11yLabeledControl(
                        contentDescription = downloadLabel,
                        onClick = viewModel::downloadModelAssets,
                    ) {
                        McaButton(
                            onClick = viewModel::downloadModelAssets,
                            style = McaButtonStyle.Primary,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("video.model_assets_download"),
                        ) {
                            Text(if (current.phase == VideoModelAssetPhase.CANCELLED) "继续下载" else "下载缺失组件")
                        }
                    }
                }
                if (current.active) {
                    val checking = current.phase == VideoModelAssetPhase.CHECKING ||
                        current.phase == VideoModelAssetPhase.VERIFYING
                    val cancelLabel = if (checking) "取消校验" else "暂停下载"
                    A11yLabeledControl(
                        contentDescription = cancelLabel,
                        onClick = viewModel::cancelModelAssetDownload,
                    ) {
                        McaButton(
                            onClick = viewModel::cancelModelAssetDownload,
                            style = McaButtonStyle.Outlined,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("video.model_assets_cancel"),
                        ) {
                            Text(cancelLabel)
                        }
                    }
                }
                if (!current.active) {
                    A11yLabeledControl(
                        contentDescription = "重新检查本地模型组件",
                        onClick = viewModel::refreshModelAssets,
                    ) {
                        McaButton(
                            onClick = viewModel::refreshModelAssets,
                            style = McaButtonStyle.Text,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("video.model_assets_refresh"),
                        ) {
                            Text("重新检查")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoModelAssetComponentRow(component: VideoModelAssetComponentState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                component.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "${component.relativePath} · ${formatBytes(component.expectedBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            component.detail ?: if (component.verified) "已校验" else "未就绪",
            style = MaterialTheme.typography.labelSmall,
            color = when {
                component.verified -> MaterialTheme.colorScheme.primary
                component.present -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.error
            },
        )
    }
}

private fun phaseLabel(phase: VideoModelAssetPhase): String = when (phase) {
    VideoModelAssetPhase.UNKNOWN -> "未检查"
    VideoModelAssetPhase.CHECKING -> "正在校验本地组件…"
    VideoModelAssetPhase.MISSING -> "组件缺失或校验未通过"
    VideoModelAssetPhase.DOWNLOADING -> "正在下载"
    VideoModelAssetPhase.VERIFYING -> "正在复核 SHA-256…"
    VideoModelAssetPhase.READY -> "模型组件已就绪"
    VideoModelAssetPhase.FAILED -> "组件获取失败"
    VideoModelAssetPhase.CANCELLED -> "下载已暂停"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val gb = bytes / 1073741824.0
    val mb = bytes / 1048576.0
    return when {
        gb >= 1.0 -> "%.1f GB".format(gb)
        mb >= 1.0 -> "%.0f MB".format(mb)
        else -> "$bytes B"
    }
}
