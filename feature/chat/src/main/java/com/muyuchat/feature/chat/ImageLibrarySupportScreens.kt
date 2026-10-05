package com.muyuchat.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Stop
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// P9/S24：从 ImageWorkspaceScreens.kt 等值移出的图库支撑簇（备份对话框 + 体积格式化 + 工作态扩展 + 翻译下载行）。
// 原样逐字迁移，未做行为改动；3 个跨区入口按协议提升 internal。
@Composable
// P9/S24: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun ImageLibraryBackupDialog(
    state: ImageLibraryBackupUiState,
    totalCount: Int,
    totalBytes: Long,
    favoriteCount: Int,
    favoriteBytes: Long,
    favoritesOnly: Boolean,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.running) onDismiss() },
        title = { Text("图片库备份与恢复") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "导出会保存图片、生成参数和收藏状态；恢复会与当前图片库合并，不会覆盖未知设备或未安装模型的历史。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "全部：$totalCount 张 · ${formatImageLibraryBytes(totalBytes)}\n" +
                        "收藏：$favoriteCount 张 · ${formatImageLibraryBytes(favoriteBytes)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!state.running) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !favoritesOnly,
                            onClick = { onFavoritesOnlyChange(false) },
                            label = { Text("导出全部") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = favoritesOnly,
                            onClick = { onFavoritesOnlyChange(true) },
                            label = { Text("仅导出收藏") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (state.running) {
                    if (state.total > 0) {
                        LinearProgressIndicator(
                            progress = { state.done.coerceIn(0, state.total).toFloat() / state.total },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Text(
                        if (state.importing) {
                            "正在恢复 ${state.done}/${state.total}"
                        } else {
                            "正在导出 ${state.done}/${state.total}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    if (state.message.isNotBlank()) {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onExport,
                            enabled = if (favoritesOnly) favoriteCount > 0 else totalCount > 0,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("导出 ZIP")
                        }
                        Button(
                            onClick = onImport,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("合并恢复")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state.running) {
                TextButton(onClick = onCancel) { Text("取消") }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

private fun formatImageLibraryBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) {
        "${value.toLong()} ${units[index]}"
    } else {
        "${"%.1f".format(value)} ${units[index]}"
    }
}

// P9/S24: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用）
internal val ImageGenerationUiJob.isWorking: Boolean
    get() = !terminal

@Composable
// P9/S24: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun ImageTranslationDownloadRow(
    status: String,
    installed: Boolean,
    installing: Boolean,
    downloading: Boolean,
    progress: Float?,
    onDownload: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("image.translation.status"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("图片提示词翻译 · Hy-MT2", style = MaterialTheme.typography.titleSmall)
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (installed && !downloading) {
                Text(
                    "已安装",
                    modifier = Modifier.testTag("image.translation.installed"),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge
                )
            } else {
                OutlinedButton(
                    onClick = if (downloading) onCancel else onDownload,
                    enabled = downloading || (!installing && !installed),
                    modifier = Modifier.testTag(if (downloading) "image.translation.download.cancel" else "image.translation.download")
                ) {
                    Icon(
                        if (downloading) Icons.Default.Stop else Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (downloading) "取消" else "下载")
                }
            }
        }
        if (downloading) {
            val boundedProgress = progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            if (boundedProgress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { boundedProgress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        HorizontalDivider()
    }
}
