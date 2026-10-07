package com.muyuchat.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ImageWorkspaceDeleteDialog(
    pendingDeleteIds: Set<String>,
    onConfirmDelete: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    if (pendingDeleteIds.isEmpty()) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (pendingDeleteIds.size == 1) "删除图片" else "批量删除") },
        text = {
            Text(
                if (pendingDeleteIds.size == 1) {
                    "确定删除这张图片吗？此操作无法撤销。"
                } else {
                    "确定删除选中的 ${pendingDeleteIds.size} 张图片吗？此操作无法撤销。"
                }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirmDelete(pendingDeleteIds.toList()) }
            ) { Text("删除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
internal fun ImageWorkspaceBackupDialogHost(
    show: Boolean,
    backupState: ImageLibraryBackupUiState,
    images: List<ImageAssetUiItem>,
    onExportBackup: (String, Boolean) -> Unit,
    onImportBackup: (String) -> Unit,
    onCancelBackup: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!show) return
    var backupFavoritesOnly by rememberSaveable { mutableStateOf(false) }
    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { onExportBackup(it.toString(), backupFavoritesOnly) }
    }
    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onImportBackup(it.toString()) }
    }

    ImageLibraryBackupDialog(
        state = backupState,
        totalCount = images.size,
        totalBytes = images.sumOf(ImageAssetUiItem::sizeBytes),
        favoriteCount = images.count(ImageAssetUiItem::favorite),
        favoriteBytes = images.asSequence()
            .filter(ImageAssetUiItem::favorite)
            .sumOf(ImageAssetUiItem::sizeBytes),
        favoritesOnly = backupFavoritesOnly,
        onFavoritesOnlyChange = { backupFavoritesOnly = it },
        onExport = {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            backupExportLauncher.launch("MCA_image_library_$timestamp.zip")
        },
        onImport = {
            backupImportLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        },
        onCancel = onCancelBackup,
        onDismiss = { if (!backupState.running) onDismiss() }
    )
}
