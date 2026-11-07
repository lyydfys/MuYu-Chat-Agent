package com.muyuchat.feature.chat
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaChip
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaTextField
import com.muyuchat.core.designsystem.McaIconButton

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.muyuchat.core.designsystem.McaChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.muyuchat.core.designsystem.McaIconSize

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
        McaTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("搜索图片、提示词或模型") },
            leadingIcon = { Icon(McaIcons.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                {
                    McaIconButton(contentDescription = "清空搜索", onClick = { onQueryChange("") }, glyphSize = McaIconSize.L) {
                        Icon(McaIcons.Close, contentDescription = null)
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
            McaChip(
                selected = favoritesOnly,
                onClick = { onFavoritesOnlyChange(!favoritesOnly) },
                label = { Text("收藏") },
                leadingIcon = {
                    Icon(
                        if (favoritesOnly) McaIcons.Favorite else McaIcons.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(McaIconSize.S)
                    )
                }
            )
            if (models.isNotEmpty()) {
                Box {
                    McaChip(
                        selected = selectedModelId != null,
                        onClick = { showModelMenu = true },
                        label = { Text(selectedModelLabel ?: "模型") },
                        leadingIcon = {
                            Icon(McaIcons.FilterList, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
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
                    McaChip(
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
                    McaChip(
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
            McaChip(
                selected = selectedDateRange != null,
                onClick = { showDatePicker = true },
                label = {
                    Text(selectedDateRange?.let(::formatImageLibraryDateRange) ?: "日期")
                }
            )
            if (dimensions.isNotEmpty() || selectedDimensionsKey != null) {
                Box {
                    McaChip(
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
                    McaChip(
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
                    McaChip(
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
                    McaChip(
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
            McaChip(
                selected = !newestFirst,
                onClick = { onNewestFirstChange(!newestFirst) },
                label = { Text(if (newestFirst) "最新优先" else "最早优先") }
            )
            if (activeFilterCount > 0) {
                McaChip(
                    selected = false,
                    onClick = onClearFilters,
                    label = { Text("清除筛选 ($activeFilterCount)") },
                    leadingIcon = {
                        Icon(McaIcons.Close, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
                    }
                )
            }
            McaChip(
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
                    McaButton(style = McaButtonStyle.Text,onClick = onSelectAllVisible, enabled = resultCount > 0 && !batchSaving) {
                        Text(if (allVisibleSelected) "取消全选" else "全选结果")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    McaButton(style = McaButtonStyle.Text,onClick = onBatchSave, enabled = selectedCount > 0 && !batchSaving) {
                        Text(if (batchSaving) "保存中…" else "保存")
                    }
                    McaButton(
                        style = McaButtonStyle.Text,
                        onClick = onBatchDelete,
                        enabled = selectedCount > 0 && !batchSaving,
                        contentColor = MaterialTheme.colorScheme.error
                    ) {
                        Text("删除")
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
            McaButton(style = McaButtonStyle.Text,onClick = onOpenBackup) {
                Icon(McaIcons.Download, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
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
