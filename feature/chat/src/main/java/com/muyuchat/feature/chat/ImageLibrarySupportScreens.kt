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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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

// P9/S26：从 ImageWorkspaceScreens.kt 等值移出的图库工具栏簇（工具栏 + 日期过滤对话框 + 日期范围格式化）。
// 原样逐字迁移，未做行为改动；跨区入口按协议提升 internal。
@Composable
// P9/S26: private→internal（跨文件被 ImageWorkspaceScreens 复合区引用）
internal fun ImageLibraryToolbar(
    query: String,
    resultCount: Int,
    totalCount: Int,
    favoritesOnly: Boolean,
    selectedModelId: String?,
    selectedTaskMode: String?,
    selectedOperation: String?,
    selectedDateRange: ImageLibraryDateRange?,
    selectedDateStartUtcMillis: Long?,
    selectedDateEndUtcMillis: Long?,
    selectedDimensionsKey: String?,
    selectedScheduler: String?,
    selectedRuntime: String?,
    selectedDevice: String?,
    newestFirst: Boolean,
    models: List<Pair<String, String>>,
    taskModes: List<String>,
    operations: List<String>,
    dimensions: List<ImageLibraryDimensions>,
    schedulers: List<String>,
    runtimes: List<String>,
    devices: List<String>,
    selectionMode: Boolean,
    selectedCount: Int,
    batchSaving: Boolean,
    allVisibleSelected: Boolean,
    onQueryChange: (String) -> Unit,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onModelChange: (String?) -> Unit,
    onTaskModeChange: (String?) -> Unit,
    onOperationChange: (String?) -> Unit,
    onDateRangeChange: (Long?, Long?) -> Unit,
    onDimensionsChange: (String?) -> Unit,
    onSchedulerChange: (String?) -> Unit,
    onRuntimeChange: (String?) -> Unit,
    onDeviceChange: (String?) -> Unit,
    onNewestFirstChange: (Boolean) -> Unit,
    onClearFilters: () -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onSelectAllVisible: () -> Unit,
    onBatchSave: () -> Unit,
    onBatchDelete: () -> Unit,
    onOpenBackup: () -> Unit
) {
    var showModelMenu by remember { mutableStateOf(false) }
    var showTaskMenu by remember { mutableStateOf(false) }
    var showOperationMenu by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showDimensionsMenu by remember { mutableStateOf(false) }
    var showSchedulerMenu by remember { mutableStateOf(false) }
    var showRuntimeMenu by remember { mutableStateOf(false) }
    var showDeviceMenu by remember { mutableStateOf(false) }
    val selectedModelLabel = models.firstOrNull { it.first == selectedModelId }?.second
    val selectedTaskLabel = when (selectedTaskMode) {
        "ultrafix" -> "UltraFix"
        else -> ImageGenerationUiTaskMode.entries
            .firstOrNull { it.wireName == selectedTaskMode }
            ?.label
            ?: selectedTaskMode
    }
    val selectedOperationLabel = ImageLibraryOperationFacet.labelFor(selectedOperation)
    val activeFilterCount = listOfNotNull(
        favoritesOnly.takeIf { it },
        selectedModelId,
        selectedTaskMode,
        selectedOperation,
        selectedDateRange,
        selectedDimensionsKey,
        selectedScheduler,
        selectedRuntime,
        selectedDevice,
        query.takeIf(String::isNotBlank)
    ).size
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("搜索图片、提示词或模型") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "清空搜索")
                    }
                }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = favoritesOnly,
                onClick = { onFavoritesOnlyChange(!favoritesOnly) },
                label = { Text("收藏") },
                leadingIcon = {
                    Icon(
                        if (favoritesOnly) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
            if (models.isNotEmpty()) {
                Box {
                    FilterChip(
                        selected = selectedModelId != null,
                        onClick = { showModelMenu = true },
                        label = { Text(selectedModelLabel ?: "模型") },
                        leadingIcon = {
                            Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    )
                    DropdownMenu(
                        expanded = showModelMenu,
                        onDismissRequest = { showModelMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部模型") },
                            onClick = {
                                onModelChange(null)
                                showModelMenu = false
                            }
                        )
                        models.forEach { (id, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onModelChange(id)
                                    showModelMenu = false
                                }
                            )
                        }
                    }
                }
            }
            if (taskModes.isNotEmpty()) {
                Box {
                    FilterChip(
                        selected = selectedTaskMode != null,
                        onClick = { showTaskMenu = true },
                        label = { Text(selectedTaskLabel ?: "模式") }
                    )
                    DropdownMenu(
                        expanded = showTaskMenu,
                        onDismissRequest = { showTaskMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部模式") },
                            onClick = {
                                onTaskModeChange(null)
                                showTaskMenu = false
                            }
                        )
                        taskModes.forEach { mode ->
                            val label = when (mode) {
                                "ultrafix" -> "UltraFix"
                                else -> ImageGenerationUiTaskMode.entries
                                    .firstOrNull { it.wireName == mode }
                                    ?.label
                                    ?: mode
                            }
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onTaskModeChange(mode)
                                    showTaskMenu = false
                                }
                            )
                        }
                    }
                }
            }
            if (operations.isNotEmpty() || selectedOperation != null) {
                Box {
                    FilterChip(
                        selected = selectedOperation != null,
                        onClick = { showOperationMenu = true },
                        label = { Text(selectedOperationLabel ?: "操作") }
                    )
                    DropdownMenu(
                        expanded = showOperationMenu,
                        onDismissRequest = { showOperationMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部操作") },
                            onClick = {
                                onOperationChange(null)
                                showOperationMenu = false
                            }
                        )
                        operations.forEach { operation ->
                            DropdownMenuItem(
                                text = { Text(ImageLibraryOperationFacet.labelFor(operation) ?: operation) },
                                onClick = {
                                    onOperationChange(operation)
                                    showOperationMenu = false
                                }
                            )
                        }
                    }
                }
            }
            FilterChip(
                selected = selectedDateRange != null,
                onClick = { showDatePicker = true },
                label = {
                    Text(selectedDateRange?.let(::formatImageLibraryDateRange) ?: "日期")
                }
            )
            if (dimensions.isNotEmpty() || selectedDimensionsKey != null) {
                Box {
                    FilterChip(
                        selected = selectedDimensionsKey != null,
                        onClick = { showDimensionsMenu = true },
                        label = {
                            Text(
                                dimensions.firstOrNull { it.key == selectedDimensionsKey }?.label
                                    ?: selectedDimensionsKey
                                    ?: "尺寸"
                            )
                        }
                    )
                    DropdownMenu(
                        expanded = showDimensionsMenu,
                        onDismissRequest = { showDimensionsMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部尺寸") },
                            onClick = {
                                onDimensionsChange(null)
                                showDimensionsMenu = false
                            }
                        )
                        dimensions.forEach { size ->
                            DropdownMenuItem(
                                text = { Text(size.label) },
                                onClick = {
                                    onDimensionsChange(size.key)
                                    showDimensionsMenu = false
                                }
                            )
                        }
                    }
                }
            }
            if (schedulers.isNotEmpty() || selectedScheduler != null) {
                Box {
                    FilterChip(
                        selected = selectedScheduler != null,
                        onClick = { showSchedulerMenu = true },
                        label = { Text(selectedScheduler ?: "Scheduler") }
                    )
                    DropdownMenu(
                        expanded = showSchedulerMenu,
                        onDismissRequest = { showSchedulerMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部 Scheduler") },
                            onClick = {
                                onSchedulerChange(null)
                                showSchedulerMenu = false
                            }
                        )
                        schedulers.forEach { scheduler ->
                            DropdownMenuItem(
                                text = { Text(scheduler) },
                                onClick = {
                                    onSchedulerChange(scheduler)
                                    showSchedulerMenu = false
                                }
                            )
                        }
                    }
                }
            }
            if (runtimes.isNotEmpty() || selectedRuntime != null) {
                Box {
                    FilterChip(
                        selected = selectedRuntime != null,
                        onClick = { showRuntimeMenu = true },
                        label = { Text(selectedRuntime ?: "Runtime") }
                    )
                    DropdownMenu(
                        expanded = showRuntimeMenu,
                        onDismissRequest = { showRuntimeMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部 Runtime") },
                            onClick = {
                                onRuntimeChange(null)
                                showRuntimeMenu = false
                            }
                        )
                        runtimes.forEach { runtime ->
                            DropdownMenuItem(
                                text = { Text(runtime) },
                                onClick = {
                                    onRuntimeChange(runtime)
                                    showRuntimeMenu = false
                                }
                            )
                        }
                    }
                }
            }
            if (devices.isNotEmpty() || selectedDevice != null) {
                Box {
                    FilterChip(
                        selected = selectedDevice != null,
                        onClick = { showDeviceMenu = true },
                        label = { Text(selectedDevice ?: "执行设备") }
                    )
                    DropdownMenu(
                        expanded = showDeviceMenu,
                        onDismissRequest = { showDeviceMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("全部执行设备") },
                            onClick = {
                                onDeviceChange(null)
                                showDeviceMenu = false
                            }
                        )
                        devices.forEach { device ->
                            DropdownMenuItem(
                                text = { Text(device) },
                                onClick = {
                                    onDeviceChange(device)
                                    showDeviceMenu = false
                                }
                            )
                        }
                    }
                }
            }
            FilterChip(
                selected = !newestFirst,
                onClick = { onNewestFirstChange(!newestFirst) },
                label = { Text(if (newestFirst) "最新优先" else "最早优先") }
            )
            if (activeFilterCount > 0) {
                FilterChip(
                    selected = false,
                    onClick = onClearFilters,
                    label = { Text("清除筛选 ($activeFilterCount)") },
                    leadingIcon = {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                )
            }
            FilterChip(
                selected = selectionMode,
                onClick = { onSelectionModeChange(!selectionMode) },
                label = { Text(if (selectionMode) "完成选择" else "选择") }
            )
        }
        if (selectionMode) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已选 $selectedCount 张",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = onSelectAllVisible, enabled = resultCount > 0 && !batchSaving) {
                        Text(if (allVisibleSelected) "取消全选" else "全选结果")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onBatchSave, enabled = selectedCount > 0 && !batchSaving) {
                        Text(if (batchSaving) "保存中…" else "保存")
                    }
                    TextButton(onClick = onBatchDelete, enabled = selectedCount > 0 && !batchSaving) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (resultCount == totalCount) "$totalCount 张图片" else "$resultCount / $totalCount 张图片",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onOpenBackup) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("备份与恢复")
            }
        }
    }
    if (showDatePicker) {
        ImageLibraryDateFilterDialog(
            selectedStartDateMillis = selectedDateStartUtcMillis,
            selectedEndDateMillis = selectedDateEndUtcMillis,
            onDismiss = { showDatePicker = false },
            onClear = {
                onDateRangeChange(null, null)
                showDatePicker = false
            },
            onConfirm = { start, end ->
                onDateRangeChange(start, end)
                showDatePicker = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageLibraryDateFilterDialog(
    selectedStartDateMillis: Long?,
    selectedEndDateMillis: Long?,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onConfirm: (Long, Long) -> Unit
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = selectedStartDateMillis,
        initialSelectedEndDateMillis = selectedEndDateMillis
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val start = state.selectedStartDateMillis
            TextButton(
                onClick = { start?.let { onConfirm(it, state.selectedEndDateMillis ?: it) } },
                enabled = start != null
            ) {
                Text("应用")
            }
        },
        dismissButton = {
            if (selectedStartDateMillis != null) {
                TextButton(onClick = onClear) { Text("清除") }
            }
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    ) {
        DateRangePicker(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp),
            showModeToggle = false
        )
    }
}

private fun formatImageLibraryDateRange(range: ImageLibraryDateRange): String {
    val zoneId = ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofPattern("MM/dd")
    val start = Instant.ofEpochMilli(range.startInclusiveMillis).atZone(zoneId).toLocalDate()
    val end = Instant.ofEpochMilli(range.endExclusiveMillis - 1L).atZone(zoneId).toLocalDate()
    return if (start == end) formatter.format(start) else "${formatter.format(start)}–${formatter.format(end)}"
}

// P9/S28：从 ImageWorkspaceScreens.kt 等值移出的图库首页（图片网格与空态）。
// 原样逐字迁移，未做行为改动；跨区入口按协议提升 internal。
@Composable
// P9/S28: private→internal（跨文件被 ImagesWorkspaceScreen 引用）
internal fun ImageGalleryHome(
    offlineTranslationStatus: String,
    offlineTranslationInstalled: Boolean,
    offlineTranslationInstalling: Boolean,
    offlineTranslationDownloading: Boolean,
    offlineTranslationDownloadProgress: Float?,
    onDownloadOfflineTranslation: () -> Unit,
    onCancelOfflineTranslationDownload: () -> Unit,
    revealParameters: Int,
    images: List<ImageAssetUiItem>,
    totalImageCount: Int,
    libraryQuery: String,
    favoritesOnly: Boolean,
    selectedLibraryModelId: String?,
    selectedLibraryTaskMode: String?,
    selectedLibraryOperation: String?,
    selectedLibraryDateRange: ImageLibraryDateRange?,
    selectedLibraryDateStartUtcMillis: Long?,
    selectedLibraryDateEndUtcMillis: Long?,
    selectedLibraryDimensionsKey: String?,
    selectedLibraryScheduler: String?,
    selectedLibraryRuntime: String?,
    selectedLibraryDevice: String?,
    newestFirst: Boolean,
    libraryModels: List<Pair<String, String>>,
    libraryTaskModes: List<String>,
    libraryOperations: List<String>,
    libraryDimensions: List<ImageLibraryDimensions>,
    librarySchedulers: List<String>,
    libraryRuntimes: List<String>,
    libraryDevices: List<String>,
    selectionMode: Boolean,
    selectedImageIds: Set<String>,
    batchSaving: Boolean,
    imageModels: List<ChatModelChoice>,
    imageModelSwitchEnabled: Boolean,
    selectedImageModelId: String?,
    selectedImageModelName: String?,
    selectedImageModelIsCloud: Boolean,
    supportedTaskModes: Set<ImageGenerationUiTaskMode>,
    supportsNegativePrompt: Boolean,
    supportsClipSkip: Boolean,
    supportsVaeTiling: Boolean,
    supportsTextualInversion: Boolean,
    supportsUltraFix: Boolean,
    supportsLora: Boolean,
    supportsLivePreview: Boolean,
    loras: List<ImageLoraUiItem>,
    selectedLoras: List<ImageGenerationUiLoraDraft>,
    loraRestoreWarning: String?,
    onAcceptLoraRestore: () -> Unit,
    loraImporting: Boolean,
    loraMessage: String,
    textualInversions: List<ImageTextualInversionUiItem>,
    selectedTextualInversionIds: List<String>,
    textualInversionImporting: Boolean,
    textualInversionMessage: String,
    batchCount: Int,
    maxBatchCount: Int,
    taskMode: ImageGenerationUiTaskMode,
    inputImageUri: String?,
    inputImageWidth: Int,
    inputImageHeight: Int,
    maskImageUri: String?,
    controlImageUri: String?,
    inputRestoreWarning: String?,
    strengthText: String,
    controlStrengthText: String,
    negativePrompt: String,
    disableModelNegativePrompt: Boolean,
    clipSkipText: String,
    vaeTilingEnabled: Boolean,
    ultraFixEnabled: Boolean,
    ultraFixStrengthText: String,
    ultraFixInversionStepsText: String,
    ultraFixRefinementStepsText: String,
    ultraFixTileSizeText: String,
    ultraFixOverlapText: String,
    livePreviewEnabled: Boolean,
    livePreviewInterval: Int,
    widthText: String,
    heightText: String,
    stepsText: String,
    cfgScaleText: String,
    seedText: String,
    sampler: String,
    onMeasureImagePromptTokens: (suspend (
        modelId: String,
        prompt: String,
    ) -> ImagePromptTokenMeasurement?)?,
    onBack: () -> Unit,
    onSelectImageModel: (String) -> Unit,
    onTaskModeChange: (ImageGenerationUiTaskMode) -> Unit,
    onPickImageRole: (String) -> Unit,
    onCropImageRole: (String) -> Unit,
    onDrawImageMask: () -> Unit,
    onClearImageRole: (String) -> Unit,
    onStrengthTextChange: (String) -> Unit,
    onControlStrengthTextChange: (String) -> Unit,
    onNegativePromptChange: (String) -> Unit,
    onDisableModelNegativePromptChange: (Boolean) -> Unit,
    onClipSkipTextChange: (String) -> Unit,
    onVaeTilingEnabledChange: (Boolean) -> Unit,
    onLivePreviewEnabledChange: (Boolean) -> Unit,
    onLivePreviewIntervalChange: (Int) -> Unit,
    onToggleLora: (String) -> Unit,
    onLoraMultiplierChange: (String, String) -> Unit,
    onImportLora: () -> Unit,
    onDownloadOfficialViggleLora: () -> Unit = {},
    onDeleteLora: (String) -> Unit,
    onToggleTextualInversion: (String) -> Unit,
    onImportTextualInversion: () -> Unit,
    onDeleteTextualInversion: (String) -> Unit,
    onUltraFixEnabledChange: (Boolean) -> Boolean,
     onResetUltraFix: () -> Unit,
     onUltraFixDenoisingStepsTextChange: (String) -> Unit,
    onUltraFixRefinementStepsTextChange: (String) -> Unit,
    onUltraFixTileSizeTextChange: (String) -> Unit,
    onUltraFixOverlapTextChange: (String) -> Unit,
    onBatchCountChange: (Int) -> Unit,
    onWidthTextChange: (String) -> Unit,
    onHeightTextChange: (String) -> Unit,
    onStepsTextChange: (String) -> Unit,
    onCfgScaleTextChange: (String) -> Unit,
    onSeedTextChange: (String) -> Unit,
    onSamplerChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onOpenImagePreview: (String) -> Unit,
    onDeleteImageAsset: (String) -> Unit,
    onSetImageAssetFavorite: (String, Boolean) -> Unit,
    onLibraryQueryChange: (String) -> Unit,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onLibraryModelChange: (String?) -> Unit,
    onLibraryTaskModeChange: (String?) -> Unit,
    onLibraryOperationChange: (String?) -> Unit,
    onLibraryDateRangeChange: (Long?, Long?) -> Unit,
    onLibraryDimensionsChange: (String?) -> Unit,
    onLibrarySchedulerChange: (String?) -> Unit,
    onLibraryRuntimeChange: (String?) -> Unit,
    onLibraryDeviceChange: (String?) -> Unit,
    onNewestFirstChange: (Boolean) -> Unit,
    onClearLibraryFilters: () -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    onToggleImageSelection: (String) -> Unit,
    onSelectAllVisible: () -> Unit,
    onBatchSave: () -> Unit,
    onBatchDelete: () -> Unit,
    onOpenBackup: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val titleColor = if (darkTheme) MaterialTheme.colorScheme.onBackground else Color(0xFF202124)
    LazyColumn(
        modifier = Modifier
            .testTag("image.gallery")
            .fillMaxSize()
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 118.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item { ImageGalleryTitleBar(onBack = onBack) }
        item {
            ImageEngineSwitcher(
                models = imageModels,
                selectedModelId = selectedImageModelId,
                selectedModelName = selectedImageModelName,
                selectedModelIsCloud = selectedImageModelIsCloud,
                enabled = imageModelSwitchEnabled,
                onSelectModel = onSelectImageModel
            )
        }
        item {
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
        item {
            ImageInputOptionsPanel(
                revealParameters = revealParameters,
                taskMode = taskMode,
                selectedModelIsCloud = selectedImageModelIsCloud,
                executionModel = imageModels.firstOrNull { it.id == selectedImageModelId },
                supportedTaskModes = supportedTaskModes,
                supportsNegativePrompt = supportsNegativePrompt,
                supportsClipSkip = supportsClipSkip,
                supportsVaeTiling = supportsVaeTiling,
                supportsTextualInversion = supportsTextualInversion,
                supportsUltraFix = supportsUltraFix,
                supportsLora = supportsLora,
                supportsLivePreview = supportsLivePreview,
                loras = loras,
                selectedLoras = selectedLoras,
                loraRestoreWarning = loraRestoreWarning,
                onAcceptLoraRestore = onAcceptLoraRestore,
                loraImporting = loraImporting,
                loraMessage = loraMessage,
                textualInversions = textualInversions,
                selectedTextualInversionIds = selectedTextualInversionIds,
                textualInversionImporting = textualInversionImporting,
                textualInversionMessage = textualInversionMessage,
                batchCount = batchCount,
                maxBatchCount = maxBatchCount,
                inputImageUri = inputImageUri,
                inputImageWidth = inputImageWidth,
                inputImageHeight = inputImageHeight,
                maskImageUri = maskImageUri,
                controlImageUri = controlImageUri,
                inputRestoreWarning = inputRestoreWarning,
                strengthText = strengthText,
                controlStrengthText = controlStrengthText,
                negativePrompt = negativePrompt,
                disableModelNegativePrompt = disableModelNegativePrompt,
                clipSkipText = clipSkipText,
                vaeTilingEnabled = vaeTilingEnabled,
                ultraFixEnabled = ultraFixEnabled,
                ultraFixStrengthText = ultraFixStrengthText,
                ultraFixInversionStepsText = ultraFixInversionStepsText,
                ultraFixRefinementStepsText = ultraFixRefinementStepsText,
                ultraFixTileSizeText = ultraFixTileSizeText,
                ultraFixOverlapText = ultraFixOverlapText,
                livePreviewEnabled = livePreviewEnabled,
                livePreviewInterval = livePreviewInterval,
                widthText = widthText,
                heightText = heightText,
                stepsText = stepsText,
                cfgScaleText = cfgScaleText,
                seedText = seedText,
                sampler = sampler,
                modelId = selectedImageModelId,
                onMeasureTokens = onMeasureImagePromptTokens,
                onPromptChange = onPromptChange,
                onTaskModeChange = onTaskModeChange,
                onPickImageRole = onPickImageRole,
                onCropImageRole = onCropImageRole,
                onDrawImageMask = onDrawImageMask,
                onClearImageRole = onClearImageRole,
                onStrengthTextChange = onStrengthTextChange,
                onControlStrengthTextChange = onControlStrengthTextChange,
                onNegativePromptChange = onNegativePromptChange,
                onDisableModelNegativePromptChange = onDisableModelNegativePromptChange,
                onClipSkipTextChange = onClipSkipTextChange,
                onVaeTilingEnabledChange = onVaeTilingEnabledChange,
                onLivePreviewEnabledChange = onLivePreviewEnabledChange,
                onLivePreviewIntervalChange = onLivePreviewIntervalChange,
                onToggleLora = onToggleLora,
                onLoraMultiplierChange = onLoraMultiplierChange,
                onImportLora = onImportLora,
                onDownloadOfficialViggleLora = onDownloadOfficialViggleLora,
                onDeleteLora = onDeleteLora,
                onToggleTextualInversion = onToggleTextualInversion,
                onImportTextualInversion = onImportTextualInversion,
                onDeleteTextualInversion = onDeleteTextualInversion,
                 onUltraFixEnabledChange = onUltraFixEnabledChange,
                 onResetUltraFix = onResetUltraFix,
                 onUltraFixDenoisingStepsTextChange = onUltraFixDenoisingStepsTextChange,
                onUltraFixRefinementStepsTextChange = onUltraFixRefinementStepsTextChange,
                onUltraFixTileSizeTextChange = onUltraFixTileSizeTextChange,
                onUltraFixOverlapTextChange = onUltraFixOverlapTextChange,
                onBatchCountChange = onBatchCountChange,
                onWidthTextChange = onWidthTextChange,
                onHeightTextChange = onHeightTextChange,
                onStepsTextChange = onStepsTextChange,
                onCfgScaleTextChange = onCfgScaleTextChange,
                onSeedTextChange = onSeedTextChange,
                onSamplerChange = onSamplerChange
            )
        }
        item {
            Text(
                "生成图片",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                fontWeight = FontWeight.Bold,
                color = titleColor
            )
        }
        item {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val cardWidth = ((maxWidth - 27.dp) / 4f).coerceIn(68.dp, 88.dp)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    contentPadding = PaddingValues(end = 18.dp)
                ) {
                    items(imagePromptTemplates, key = { it.title }) { template ->
                        ImageTemplateCard(
                            template = template,
                            cardWidth = cardWidth,
                            onClick = {
                                onPromptChange(template.prompt)
                                onNegativePromptChange(template.negativePrompt)
                                onDisableModelNegativePromptChange(false)
                            }
                        )
                    }
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(58.dp))
        }
        item {
            Text(
                "我的图片",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                fontWeight = FontWeight.Bold,
                color = titleColor
            )
        }
        item {
            ImageLibraryToolbar(
                query = libraryQuery,
                resultCount = images.size,
                totalCount = totalImageCount,
                favoritesOnly = favoritesOnly,
                selectedModelId = selectedLibraryModelId,
                selectedTaskMode = selectedLibraryTaskMode,
                selectedOperation = selectedLibraryOperation,
                selectedDateRange = selectedLibraryDateRange,
                selectedDateStartUtcMillis = selectedLibraryDateStartUtcMillis,
                selectedDateEndUtcMillis = selectedLibraryDateEndUtcMillis,
                selectedDimensionsKey = selectedLibraryDimensionsKey,
                selectedScheduler = selectedLibraryScheduler,
                selectedRuntime = selectedLibraryRuntime,
                selectedDevice = selectedLibraryDevice,
                newestFirst = newestFirst,
                models = libraryModels,
                taskModes = libraryTaskModes,
                operations = libraryOperations,
                dimensions = libraryDimensions,
                schedulers = librarySchedulers,
                runtimes = libraryRuntimes,
                devices = libraryDevices,
                selectionMode = selectionMode,
                selectedCount = selectedImageIds.size,
                batchSaving = batchSaving,
                allVisibleSelected = images.isNotEmpty() && images.all { it.id in selectedImageIds },
                onQueryChange = onLibraryQueryChange,
                onFavoritesOnlyChange = onFavoritesOnlyChange,
                onModelChange = onLibraryModelChange,
                onTaskModeChange = onLibraryTaskModeChange,
                onOperationChange = onLibraryOperationChange,
                onDateRangeChange = onLibraryDateRangeChange,
                onDimensionsChange = onLibraryDimensionsChange,
                onSchedulerChange = onLibrarySchedulerChange,
                onRuntimeChange = onLibraryRuntimeChange,
                onDeviceChange = onLibraryDeviceChange,
                onNewestFirstChange = onNewestFirstChange,
                onClearFilters = onClearLibraryFilters,
                onSelectionModeChange = onSelectionModeChange,
                onSelectAllVisible = onSelectAllVisible,
                onBatchSave = onBatchSave,
                onBatchDelete = onBatchDelete,
                onOpenBackup = onOpenBackup
            )
        }
        if (images.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (favoritesOnly) Icons.Default.FavoriteBorder else Icons.Default.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(30.dp)
                    )
                    Text(
                        if (totalImageCount == 0) "生成完成的图片会自动保存在这里" else "没有符合当前筛选条件的图片",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(images.chunked(3), key = { row -> row.joinToString("-") { it.id } }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { image ->
                        ImageAssetTile(
                            image = image,
                            selected = image.id in selectedImageIds,
                            selectionMode = selectionMode,
                            onOpen = {
                                if (selectionMode) onToggleImageSelection(image.id)
                                else onOpenImagePreview(image.id)
                            },
                            onLongPress = {
                                if (!selectionMode) onSelectionModeChange(true)
                                onToggleImageSelection(image.id)
                            },
                            onToggleFavorite = {
                                onSetImageAssetFavorite(image.id, !image.favorite)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                        )
                    }
                    repeat(3 - row.size) {
                        Spacer(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                        )
                    }
                }
            }
        }
    }
}
