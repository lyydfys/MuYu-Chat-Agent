package com.muyuchat.feature.chat

// The application menu pages, moved verbatim out of ChatScreen.kt.
//
// ChatScreen.kt held four unrelated page domains. The image workspace and the assistant auxiliary
// screens have already left; this is the third -- the full-screen app menu, its cards and rows,
// the MCA logo mark, and the animated assistant mark that the chat header also uses. Nothing here
// is new code: one contiguous block was cut from ChatScreen.kt and pasted unchanged.
//
// Five declarations widened from private to internal because ChatScreen.kt still calls them, and
// two more widened the other way: displayModelName and ChatUiState.imageTaskSummary stayed in
// ChatScreen.kt but are called from here. Kotlin has no package-private scope, so either
// direction needs internal.
//
// This file is also where appMenuPageEnter lives, the slide-and-fade transition the menu pages
// use. It sat inside the block, so it travelled with it.
//
// The import list is the one from ChatScreen.kt, copied whole rather than trimmed: unused imports
// are warnings in Kotlin, and copying them keeps the move mechanical instead of making it a
// judgement call about which imports each declaration still needs.

import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkWifi
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.mcaPressScale

@Composable
internal fun McaAppMenuPage(
    onOpenQuickStart: () -> Unit,
    onOpenBackgroundSettings: () -> Unit,
    state: ChatUiState,
    onClose: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenApi: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestContextCompression: () -> Unit,
    onUndoContextSummary: () -> Unit,
    onSearchContextSummarySources: (String) -> List<ContextSummaryEvidenceUiItem>,
    onSetContextCompressionThreshold: (Int) -> Unit,
    onSetContextSummaryModel: (String) -> Unit,
    onOpenImages: () -> Unit,
    onOpenAssistants: () -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmClear by remember { mutableStateOf(false) }
    var chooseCompressionThreshold by remember { mutableStateOf(false) }
    var chooseSummaryModel by remember { mutableStateOf(false) }
    var showSummaryHistory by remember { mutableStateOf(false) }
    // The locale is read from the composition (not Locale.getDefault()) so a locale change is an
    // observable dependency and re-renders this page, and the formatter is allocated once per locale
    // instead of once per summary-history row. Same pattern as MainActivity's timestamp formatting.
    val summaryTimestampLocale = androidx.compose.ui.platform.LocalLocale.current.platformLocale
    val summaryDateFormat = remember(summaryTimestampLocale) {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", summaryTimestampLocale)
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .navigationBarsPadding()
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Shared icon button: same 48dp target and 24dp glyph as the previous IconButton,
            // now with the layer-phase press weight; the accessible name stays on the button.
            McaIconButton(
                contentDescription = "关闭",
                onClick = onClose,
                glyphSize = 24.dp
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
            }
            Spacer(modifier = Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Spacer(modifier = Modifier.height(22.dp))
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                McaLogoMark(size = 66.dp, cornerRadius = 18.dp)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "MuYu Chat Agent",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                    fontWeight = FontWeight.Black
                )
            }
            Spacer(modifier = Modifier.height(28.dp))

            Text(
                "MCA",
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppMenuCard {
                AppMenuRow(
                    icon = { Icon(Icons.Default.Psychology, null) },
                    title = "助手与角色",
                    subtitle = state.assistants.firstOrNull { it.selected }?.let { "${it.name} · 角色卡与能力" } ?: "当前助手、角色卡、提示词与能力",
                    onClick = onOpenAssistants
                )
                AppMenuRow(icon = { Icon(Icons.Default.Folder, null) }, title = "模型管理", subtitle = "本地高速/兼容引擎与魔塔下载", onClick = onOpenModels)
                AppMenuRow(icon = { McaLogoMark(size = 22.dp, cornerRadius = 7.dp) }, title = "智能调参", subtitle = "测速、推荐与高级参数", onClick = onOpenAgent)
                AppMenuRow(icon = { Icon(Icons.Default.NetworkWifi, null) }, title = "本地 API", subtitle = "接口地址、Key、API 使用文档", onClick = onOpenApi)
                 AppMenuRow(icon = { Icon(Icons.Default.Settings, null) }, title = "系统设置", subtitle = "运行、日志、诊断与高级设置", onClick = onOpenSettings)
                AppMenuRow(icon = { Icon(Icons.Default.Image, null) }, title = "聊天背景", subtitle = "设置全局、助手或当前会话背景图片", onClick = onOpenBackgroundSettings)
                AppMenuRow(icon = { Icon(Icons.Default.Info, null) }, title = "快速开始", subtitle = "聊天、生图与常见问题", onClick = onOpenQuickStart)
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                "本机状态",
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppMenuCard {
                AppMenuRow(
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (state.selectedModelName == null) Color(0xFF9AA0A6) else Color(0xFF34A853))
                        )
                    },
                    title = displayModelName(state.selectedModelName) ?: "未加载模型",
                    subtitle = "${"%.1f".format(state.stats.decodeTps)} token/s · ${state.stats.backend.uppercase()} 后端",
                    onClick = onOpenModels
                )
                AppMenuRow(
                    icon = { Icon(Icons.Default.NetworkWifi, null) },
                    title = "本地 API",
                    subtitle = if (state.apiEnabled || state.restEnabled) "已启用" else "未启用",
                    onClick = onOpenApi
                )
                AppMenuRow(
                    icon = { Icon(Icons.Default.Image, null) },
                    title = "图像任务",
                    subtitle = state.imageTaskSummary(),
                    onClick = onOpenImages
                )
                AppMenuRow(
                    icon = { Icon(Icons.Default.Psychology, null) },
                    title = state.assistants.firstOrNull { it.selected }?.name ?: "默认助手",
                    subtitle = state.assistants.firstOrNull { it.selected }?.let {
                        "记忆${if (it.memoryEnabled) "开" else "关"} · 检索${if (it.webSearchEnabled) "开" else "关"}"
                    } ?: "助手能力状态",
                    onClick = onOpenAssistants
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                "对话",
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppMenuCard {
                AppMenuRow(
                    icon = { Icon(Icons.Default.Replay, null) },
                    title = if (state.contextCompressionPending) "已安排压缩" else "下次发送时压缩",
                    subtitle = if (state.contextCompressionPending) {
                        "下一次发送时执行，保留角色设定和最近消息"
                    } else {
                        "压缩较早对话，减少长上下文占用"
                    },
                    enabled = !state.conversationReadOnly,
                    onClick = onRequestContextCompression
                )
                AppMenuRow(
                    icon = { Icon(Icons.Default.Settings, null) },
                    title = "自动压缩阈值 · ${state.contextCompressionThresholdPercent}%",
                    subtitle = "达到模型上下文窗口的该比例后自动压缩",
                    onClick = { chooseCompressionThreshold = true }
                )
                AppMenuRow(
                    icon = { Icon(Icons.Default.Folder, null) },
                    title = "摘要历史",
                    subtitle = if (state.contextSummaryHistory.isEmpty()) "当前对话尚无摘要" else
                        "${state.contextSummaryHistory.size} 个版本 · ${if (state.canUndoContextSummary) "有生效摘要" else "已撤销"}",
                    onClick = { showSummaryHistory = true }
                )
                AppMenuRow(
                    icon = { Icon(Icons.Default.Settings, null) },
                    title = "摘要模型",
                    subtitle = state.contextSummaryModels.firstOrNull {
                        it.id == state.contextSummaryModelSelection
                    }?.label ?: "所选模型当前不可用",
                    onClick = { chooseSummaryModel = true }
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                "历史",
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppMenuCard {
                AppMenuRow(
                    icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    title = "清空全部",
                    subtitle = "删除本机保存的全部聊天记录",
                    contentColor = MaterialTheme.colorScheme.error,
                    onClick = { confirmClear = true }
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部历史") },
            text = { Text("确定清空所有聊天记录吗？当前模型和模型文件不会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        confirmClear = false
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (chooseCompressionThreshold) {
        AlertDialog(
            onDismissRequest = { chooseCompressionThreshold = false },
            title = { Text("自动压缩阈值") },
            text = {
                // 2.0x font: keep the dialog's existing 420dp cap and make the option list
                // scrollable, so larger text cannot push the buttons off-screen.
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "阈值越低越早整理上下文；系统消息、角色设定和最近消息会保留。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    listOf(70, 80, 90).forEach { percent ->
                        TextButton(
                            onClick = {
                                onSetContextCompressionThreshold(percent)
                                chooseCompressionThreshold = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "$percent%${if (percent == state.contextCompressionThresholdPercent) "（当前）" else ""}",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { chooseCompressionThreshold = false }) {
                    Text("取消")
                }
            }
        )
    }
    if (chooseSummaryModel) {
        AlertDialog(
            onDismissRequest = { chooseSummaryModel = false },
            title = { Text("摘要模型") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    state.contextSummaryModels.forEach { option ->
                        val optionInteraction = remember { MutableInteractionSource() }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(
                                    interactionSource = optionInteraction,
                                    indication = LocalIndication.current,
                                    enabled = !state.isGenerating
                                ) {
                                    onSetContextSummaryModel(option.id)
                                    chooseSummaryModel = false
                                }
                                .mcaPressScale(optionInteraction, enabled = !state.isGenerating)
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = option.id == state.contextSummaryModelSelection,
                                onClick = null,
                                enabled = !state.isGenerating
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                option.label,
                                modifier = Modifier.weight(1f),
                                // The row is disabled while generating: dim its label to match
                                // the shared disabled treatment instead of looking available.
                                color = if (state.isGenerating) {
                                    LocalContentColor.current.copy(alpha = 0.38f)
                                } else {
                                    LocalContentColor.current
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { chooseSummaryModel = false }) { Text("关闭") }
            }
        )
    }
    if (showSummaryHistory) {
        var summarySourceQuery by rememberSaveable(state.activeConversationId) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSummaryHistory = false },
            title = { Text("摘要历史") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = summarySourceQuery,
                        onValueChange = { summarySourceQuery = it },
                        label = { Text("查找摘要事实或来源") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (summarySourceQuery.isNotBlank()) {
                        val sourceHits = onSearchContextSummarySources(summarySourceQuery)
                        if (sourceHits.isEmpty()) {
                            Text("当前有效摘要没有匹配的来源。", style = MaterialTheme.typography.bodySmall)
                        }
                        sourceHits.forEach { hit ->
                            SelectionContainer {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(hit.text, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${hit.kind} · 来源 ${hit.sourceMessageId} · 位置 ${hit.sourceOffset}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                    if (state.contextSummaryHistory.isEmpty()) {
                        Text("当前对话尚无摘要。", style = MaterialTheme.typography.bodyMedium)
                    }
                    state.contextSummaryHistory.sortedByDescending(ContextSummaryUiItem::version)
                        .forEach { summary ->
                            val date = summaryDateFormat.format(java.util.Date(summary.createdAt))
                            Text(
                                "版本 ${summary.version} · ${if (summary.active) "生效中" else "已撤销"} · $date",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                "来源：${when (summary.source) {
                                    "DETERMINISTIC" -> "规则整理"
                                    "LOCAL_MODEL" -> "本地模型"
                                    "CLOUD_MODEL" -> "云端模型"
                                    else -> summary.source
                                }} · " +
                                    "原始消息 ${summary.sourceMessageIds.size} 条",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            summary.modelIdentity?.let { Text("模型：$it", style = MaterialTheme.typography.bodySmall) }
                            summary.diagnostic?.let { Text("诊断：$it", style = MaterialTheme.typography.bodySmall) }
                            if (summary.coverageLimited) {
                                Text(
                                    "覆盖有限：保留 ${summary.evidenceCount} 条来源摘录，" +
                                        "未纳入 ${summary.omittedExcerptCount} 条片段；" +
                                        "另有 ${summary.omittedCharacterCount} 字符超出扫描上限。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SelectionContainer {
                                Text(summary.text, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (summary.sourceMessageIds.isNotEmpty()) {
                                SelectionContainer {
                                    Text(
                                        "来源消息 ID：${summary.sourceMessageIds.joinToString()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                }
            },
            confirmButton = {
                // 2.0x font: the two long actions no longer fit one dialog line; FlowRow keeps
                // the 1.0x single row and wraps only when the type scale needs it.
                FlowRow {
                    TextButton(
                        onClick = {
                            onUndoContextSummary()
                            showSummaryHistory = false
                        },
                        enabled = state.canUndoContextSummary && !state.isGenerating && !state.conversationReadOnly
                    ) { Text("撤销") }
                    TextButton(
                        onClick = {
                            onRequestContextCompression()
                            showSummaryHistory = false
                        },
                        enabled = state.activeConversationId != null && !state.isGenerating && !state.conversationReadOnly
                    ) { Text("下次发送时重建") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showSummaryHistory = false }) { Text("关闭") }
            }
        )
    }
}