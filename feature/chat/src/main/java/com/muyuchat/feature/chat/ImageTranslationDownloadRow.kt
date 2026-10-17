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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
