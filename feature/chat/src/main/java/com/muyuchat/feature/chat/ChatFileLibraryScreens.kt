package com.muyuchat.feature.chat
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaChip
import com.muyuchat.core.designsystem.McaIcons

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.muyuchat.core.designsystem.McaChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.McaIconSize

// P9/S12：从 ChatChromeScreens.kt 等值移出的文件库页面簇。
// 原样逐字迁移，未做行为改动；无新增提升（FileLibraryPage 原即 internal）。
private enum class FileLibraryFilter(val label: String) {
    ALL("全部"),
    TEXT("文本"),
    CODE("代码"),
    DATA("数据")
}

@Composable
internal fun FileLibraryPage(
    files: List<FileAssetUiItem>,
    onInsert: (String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(FileLibraryFilter.ALL) }
    val darkTheme = isSystemInDarkTheme()
    val libraryInputFieldColor = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else McaInputField
    val filtered = remember(files, query, filter) {
        files.filter { file ->
            val queryMatched = query.isBlank() ||
                file.name.contains(query, ignoreCase = true) ||
                file.preview.contains(query, ignoreCase = true)
            queryMatched && file.matchesFilter(filter)
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("文件库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "${files.size} 个本机文件索引",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                McaIconButton(
                    onClick = onBack,
                    contentDescription = "关闭文件库",
                    glyphSize = McaIconSize.L,
                    tint = LocalContentColor.current
                ) {
                    Icon(McaIcons.Close, contentDescription = null, modifier = Modifier.size(McaIconSize.L))
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜索文件名或内容预览") },
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = libraryInputFieldColor,
                    unfocusedContainerColor = libraryInputFieldColor,
                    focusedBorderColor = McaPrimaryBlue.copy(alpha = 0.42f),
                    unfocusedBorderColor = Color.Transparent
                )
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                FileLibraryFilter.entries.forEach { item ->
                    McaChip(
                        selected = filter == item,
                        onClick = { filter = item },
                        label = { Text(item.label) }
                    )
                }
            }
            if (filtered.isEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 22.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            if (files.isEmpty()) "文件库为空" else "没有匹配文件",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (files.isEmpty()) "从输入框上传文本、Markdown、JSON、代码文件后，会自动出现在这里。"
                            else "换个关键词或切换筛选类型试试。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { file ->
                        FileLibraryRow(
                            file = file,
                            onInsert = { onInsert(file.id) },
                            onDelete = { onDelete(file.id) }
                        )
                    }
                }
            }
        }
    }
@Composable
private fun FileLibraryRow(
    file: FileAssetUiItem,
    onInsert: () -> Unit,
    onDelete: () -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(36.dp),
                    color = if (darkTheme) MaterialTheme.colorScheme.surfaceVariant else McaInputIconSurface,
                    shape = CircleShape
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            McaIcons.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(McaIconSize.M),
                            tint = McaPrimaryBlue
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        file.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${file.sizeText} · ${file.createdAtText}${if (file.truncated) " · 已截取" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                file.preview,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                McaButton(style = McaButtonStyle.Text,onClick = onDelete) {
                    Text("删除")
                }
                McaButton(style = McaButtonStyle.Primary,onClick = onInsert) {
                    Text("插入当前聊天")
                }
            }
        }
    }
}

private fun FileAssetUiItem.matchesFilter(filter: FileLibraryFilter): Boolean {
    if (filter == FileLibraryFilter.ALL) return true
    val lowerName = name.lowercase()
    val lowerMime = mimeType.lowercase()
    return when (filter) {
        FileLibraryFilter.ALL -> true
        FileLibraryFilter.TEXT -> lowerMime.startsWith("text/") &&
            !lowerName.endsWith(".kt") &&
            !lowerName.endsWith(".java") &&
            !lowerName.endsWith(".py") &&
            !lowerName.endsWith(".js") &&
            !lowerName.endsWith(".ts")
        FileLibraryFilter.CODE -> lowerMime.contains("code") ||
            lowerName.endsWith(".kt") ||
            lowerName.endsWith(".java") ||
            lowerName.endsWith(".py") ||
            lowerName.endsWith(".js") ||
            lowerName.endsWith(".ts")
        FileLibraryFilter.DATA -> lowerMime.contains("json") ||
            lowerMime.contains("xml") ||
            lowerName.endsWith(".json") ||
            lowerName.endsWith(".jsonl") ||
            lowerName.endsWith(".xml") ||
            lowerName.endsWith(".csv")
    }
}
