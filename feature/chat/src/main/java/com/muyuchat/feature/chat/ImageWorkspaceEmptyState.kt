package com.muyuchat.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ImageWorkspaceEmptyState(
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onImportModel: () -> Unit,
    offlineTranslationStatus: String,
    offlineTranslationInstalled: Boolean,
    offlineTranslationInstalling: Boolean,
    offlineTranslationDownloading: Boolean,
    offlineTranslationDownloadProgress: Float?,
    onDownloadOfflineTranslation: () -> Unit,
    onCancelOfflineTranslationDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ImageGalleryTitleBar(onBack = onBack)
        Text("还没有可用的生图模型", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onOpenModels) { Text("选择推荐模型") }
        OutlinedButton(onClick = onImportModel) { Text("导入已有模型") }
        ImageTranslationDownloadRow(
            status = offlineTranslationStatus,
            installed = offlineTranslationInstalled,
            installing = offlineTranslationInstalling,
            downloading = offlineTranslationDownloading,
            progress = offlineTranslationDownloadProgress,
            onDownload = onDownloadOfflineTranslation,
            onCancel = onCancelOfflineTranslationDownload
        )
    }
}
