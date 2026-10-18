package com.muyuchat.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

private const val MAX_ASSISTANT_SYSTEM_PROMPT_CHARS = 12_000
private const val ASSISTANT_SAVE_DRAFT_PRESERVED_MESSAGE =
    "当前有角色请求或图片任务进行中，草稿已保留，请结束后重试"

@Composable
internal fun AssistantEditorPage(
    assistant: AssistantUiItem?,
    memories: List<AssistantMemoryUiItem> = emptyList(),
    onBack: () -> Unit,
    selectedModelName: String?,
    selectedModelId: String?,
    selectedModelIsCloud: Boolean,
    onDelete: (String) -> Unit,
    onSave: (AssistantEditorDraft) -> Boolean,
    onUpsertMemory: (assistantId: String, id: String?, scope: String, content: String) -> Deferred<Boolean> =
        { _, _, _, _ -> CompletableDeferred(false) },
    onDeleteMemory: (assistantId: String, id: String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var name by remember(assistant?.id) { mutableStateOf(assistant?.name ?: "") }
    var avatar by remember(assistant?.id) { mutableStateOf(assistant?.avatar ?: "") }
    var tag by remember(assistant?.id) { mutableStateOf(assistant?.tag ?: "") }
    var prompt by remember(assistant?.id) {
        mutableStateOf((assistant?.systemPrompt ?: "").take(MAX_ASSISTANT_SYSTEM_PROMPT_CHARS))
    }
    var roleplayPresetId by remember(assistant?.id) {
        mutableStateOf(assistant?.let { RoleplayStylePresets.selectedPresetId(it.systemPrompt) })
    }
    var defaultModelMode by remember(assistant?.id) { mutableStateOf(assistant?.defaultModelMode ?: "follow_current") }
    var defaultModelId by remember(assistant?.id) { mutableStateOf(assistant?.defaultModelId) }
    var temperatureText by remember(assistant?.id) { mutableStateOf((assistant?.temperature ?: GenerationParams().temperature).cleanParamText()) }
    var topPText by remember(assistant?.id) { mutableStateOf((assistant?.topP ?: GenerationParams().topP).cleanParamText()) }
    var topKText by remember(assistant?.id) { mutableStateOf((assistant?.topK ?: GenerationParams().topK).toString()) }
    var minPText by remember(assistant?.id) { mutableStateOf((assistant?.minP ?: GenerationParams().minP).cleanParamText()) }
    var repeatPenaltyText by remember(assistant?.id) { mutableStateOf((assistant?.repeatPenalty ?: GenerationParams().repeatPenalty).cleanParamText()) }
    var presencePenaltyText by remember(assistant?.id) { mutableStateOf((assistant?.presencePenalty ?: GenerationParams().presencePenalty).cleanParamText()) }
    var frequencyPenaltyText by remember(assistant?.id) { mutableStateOf((assistant?.frequencyPenalty ?: GenerationParams().frequencyPenalty).cleanParamText()) }
    var nPredictText by remember(assistant?.id) { mutableStateOf((assistant?.nPredict ?: GenerationParams().nPredict).toString()) }
    var stopWordsText by remember(assistant?.id) { mutableStateOf(JSONArray(assistant?.stopWords.orEmpty()).toString()) }
    var reasoningMode by remember(assistant?.id) { mutableStateOf(assistant?.reasoningMode ?: GenerationParams().reasoningMode) }
    var memoryEnabled by remember(assistant?.id) { mutableStateOf(assistant?.memoryEnabled ?: false) }
    var memorySummaryIntervalText by remember(assistant?.id) {
        mutableStateOf((assistant?.memorySummaryInterval ?: 12).coerceIn(2, 100).toString())
    }
    var showMemoryManager by remember(assistant?.id) { mutableStateOf(false) }
    var webSearchEnabled by remember(assistant?.id) { mutableStateOf(assistant?.webSearchEnabled ?: false) }
    var fileContextEnabled by remember(assistant?.id) { mutableStateOf(assistant?.fileContextEnabled ?: true) }
    var roleplayPresetMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var presetImportMessage by rememberSaveable(assistant?.id) { mutableStateOf<String?>(null) }
    var presetImporting by rememberSaveable(assistant?.id) { mutableStateOf(false) }
    var advancedSamplingExpanded by rememberSaveable(assistant?.id) { mutableStateOf(false) }
    var saveError by rememberSaveable(assistant?.id) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val presetImportScope = rememberCoroutineScope()
    val presetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            presetImporting = true
            presetImportMessage = "正在读取酒馆预设…"
            presetImportScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val json = requireNotNull(context.contentResolver.openInputStream(uri)) {
                            "无法读取所选文件。"
                        }.use { stream -> readSillyTavernPresetJson(stream) }
                        SillyTavernPresetCodec.parse(
                            rawJson = json,
                            fallbackName = uri.lastPathSegment?.substringAfterLast('/') ?: "Tavern 预设"
                        )
                    }
                }
                result.onSuccess { preset ->
                    preset.temperature?.let { temperatureText = it.cleanParamText() }
                    preset.topP?.let { topPText = it.cleanParamText() }
                    preset.topK?.let { topKText = it.toString() }
                    preset.minP?.let { minPText = it.cleanParamText() }
                    preset.repeatPenalty?.let { repeatPenaltyText = it.cleanParamText() }
                    preset.presencePenalty?.let { presencePenaltyText = it.cleanParamText() }
                    preset.frequencyPenalty?.let { frequencyPenaltyText = it.cleanParamText() }
                    preset.maxTokens?.let { nPredictText = it.coerceIn(16, 65_536).toString() }
                    preset.stopWords?.let { stopWordsText = JSONArray(it).toString() }
                    preset.promptAppendix?.let { appendix ->
                        prompt = SillyTavernPresetCodec.mergePromptAppendix(prompt, appendix)
                    }
                    advancedSamplingExpanded = true
                    presetImportMessage = buildString {
                        append("已导入酒馆采样参数：").append(preset.name)
                        if (preset.promptAppendix != null) {
                            append("；已合并纯文本提示内容")
                        } else if (preset.ignoredPromptFields.isNotEmpty()) {
                            append("；模板字段未应用，继续使用模型原生聊天模板")
                        }
                    }
                }.onFailure { error ->
                    presetImportMessage = "酒馆预设导入失败：${error.message ?: "请确认选择的是生成参数 JSON 文件。"}"
                }
                presetImporting = false
            }
        }
    }
    fun buildDraft(id: String?, draftName: String = name): AssistantEditorDraft =
        AssistantEditorDraft(
            id = id,
            name = draftName,
            avatar = avatar,
            tag = tag,
            systemPrompt = prompt,
            defaultModelMode = defaultModelMode,
            defaultModelId = defaultModelId,
            temperature = temperatureText.toAssistantFloat(assistant?.temperature ?: GenerationParams().temperature, 0f, 2f),
            topP = topPText.toAssistantFloat(assistant?.topP ?: GenerationParams().topP, 0f, 1f),
            nPredict = nPredictText.toAssistantInt(assistant?.nPredict ?: GenerationParams().nPredict, 16, 65_536),
            reasoningMode = reasoningMode,
            memoryEnabled = memoryEnabled,
            memorySummaryInterval = memorySummaryIntervalText.toAssistantInt(assistant?.memorySummaryInterval ?: 12, 2, 100),
            webSearchEnabled = webSearchEnabled,
            fileContextEnabled = fileContextEnabled,
            topK = topKText.toAssistantInt(assistant?.topK ?: GenerationParams().topK, 0, 1000),
            minP = minPText.toAssistantFloat(assistant?.minP ?: GenerationParams().minP, 0f, 1f),
            repeatPenalty = repeatPenaltyText.toAssistantFloat(assistant?.repeatPenalty ?: GenerationParams().repeatPenalty, 0.5f, 2f),
            presencePenalty = presencePenaltyText.toAssistantFloat(assistant?.presencePenalty ?: GenerationParams().presencePenalty, -2f, 2f),
            frequencyPenalty = frequencyPenaltyText.toAssistantFloat(assistant?.frequencyPenalty ?: GenerationParams().frequencyPenalty, -2f, 2f),
            stopWords = stopWordsText.toAssistantStopWords(assistant?.stopWords.orEmpty())
        )

    fun submitDraft(draft: AssistantEditorDraft, closeOnSuccess: Boolean) {
        saveError = null
        if (!onSave(draft)) {
            saveError = ASSISTANT_SAVE_DRAFT_PRESERVED_MESSAGE
            return
        }
        if (closeOnSuccess) onBack()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回助手列表")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(if (assistant == null) "新建助手" else "编辑助手", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("角色卡、提示词、默认模型与生成参数", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
                item {
                    Text("基础信息", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = avatar,
                            onValueChange = { avatar = it.take(4) },
                            label = { Text("头像") },
                            singleLine = true,
                            modifier = Modifier.weight(0.72f)
                        )
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("助手名称") },
                            singleLine = true,
                            modifier = Modifier.weight(1.28f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tag,
                        onValueChange = { tag = it.take(24) },
                        label = { Text("标签") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("角色演绎风格", style = MaterialTheme.typography.labelMedium)
                            Text(
                                "风格说明会加入可编辑提示词，并设置较短的移动端输出上限。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box {
                            TextButton(onClick = { roleplayPresetMenuExpanded = true }) {
                                Text(
                                    RoleplayStylePresets.all.firstOrNull { it.id == roleplayPresetId }?.name
                                        ?: "选择预设"
                                )
                            }
                            DropdownMenu(
                                expanded = roleplayPresetMenuExpanded,
                                onDismissRequest = { roleplayPresetMenuExpanded = false }
                            ) {
                                RoleplayStylePresets.all.forEach { preset ->
                                    DropdownMenuItem(
                                        text = { Text(preset.name) },
                                        onClick = {
                                            roleplayPresetMenuExpanded = false
                                            roleplayPresetId = preset.id
                                            prompt = RoleplayStylePresets.withPresetPrompt(prompt, preset)
                                            temperatureText = preset.temperature.cleanParamText()
                                            topPText = preset.topP.cleanParamText()
                                            topKText = preset.topK.toString()
                                            repeatPenaltyText = preset.repeatPenalty.cleanParamText()
                                            nPredictText = preset.maxTokens.toString()
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("移除风格说明") },
                                    onClick = {
                                        roleplayPresetMenuExpanded = false
                                        roleplayPresetId = null
                                        prompt = RoleplayStylePresets.withPresetPrompt(prompt, null)
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = {
                            prompt = it.take(MAX_ASSISTANT_SYSTEM_PROMPT_CHARS)
                            roleplayPresetId = RoleplayStylePresets.selectedPresetId(prompt)
                        },
                        label = { Text("系统提示词") },
                        minLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = {
                        prompt = GenerationParams().systemPrompt
                        roleplayPresetId = null
                    }) {
                        Text("恢复默认提示词")
                    }
                }
                item {
                    Text("默认模型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = defaultModelMode == "follow_current",
                            onClick = {
                                defaultModelMode = "follow_current"
                                defaultModelId = null
                            },
                            label = { Text("跟随当前") }
                        )
                        FilterChip(
                            selected = defaultModelMode != "follow_current",
                            enabled = selectedModelId != null,
                            onClick = {
                                defaultModelMode = if (selectedModelIsCloud) "cloud" else "local"
                                defaultModelId = selectedModelId
                            },
                            label = {
                                Text(
                                    when (defaultModelMode) {
                                        "cloud" -> "已绑定云端"
                                        "local" -> "已绑定本地"
                                        else -> if (selectedModelIsCloud) "绑定当前云端" else "绑定当前本地"
                                    }
                                )
                            }
                        )
                    }
                    Text(
                        when {
                            defaultModelMode == "follow_current" -> "切换到该助手时继续使用聊天页当前模型。"
                            defaultModelId.isNullOrBlank() -> "当前绑定缺少模型，请先在聊天页选择可用模型。"
                            else -> "切换到该助手时优先使用：${assistant?.modelSummary ?: selectedModelName ?: "已绑定模型"}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    Text("能力", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(selected = memoryEnabled, onClick = { memoryEnabled = !memoryEnabled }, label = { Text("记忆") })
                        FilterChip(selected = webSearchEnabled, onClick = { webSearchEnabled = !webSearchEnabled }, label = { Text("联网检索") })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(selected = fileContextEnabled, onClick = { fileContextEnabled = !fileContextEnabled }, label = { Text("文件上下文") })
                        FilterChip(selected = false, onClick = {}, enabled = false, label = { Text("本地工具预留") })
                    }
                    if (memoryEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = memorySummaryIntervalText,
                            onValueChange = { value -> memorySummaryIntervalText = value.filter(Char::isDigit).take(3) },
                            label = { Text("每几轮总结一次") },
                            supportingText = { Text("2 - 100 轮") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (assistant != null) {
                        TextButton(onClick = { showMemoryManager = true }) {
                            Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("管理记忆")
                        }
                    }
                }
                item {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "生成参数",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(
                            onClick = { presetPicker.launch(arrayOf("*/*")) },
                            enabled = !presetImporting,
                            modifier = Modifier.height(40.dp)
                        ) {
                            if (presetImporting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(if (presetImporting) "读取中" else "导入酒馆预设")
                        }
                    }
                    presetImportMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (message.contains("失败")) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        "支持 temperature、top_p/top_k、min_p、rep_pen、penalties、max_length/max_tokens 和 stop；模型模板、prompt_order、宏及其他未支持字段会忽略。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = temperatureText,
                            onValueChange = { temperatureText = it },
                            label = { Text("温度") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = topPText,
                            onValueChange = { topPText = it },
                            label = { Text("top_p") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nPredictText,
                        onValueChange = { nPredictText = it },
                        label = { Text("输出长度") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { advancedSamplingExpanded = !advancedSamplingExpanded }) {
                        Text(if (advancedSamplingExpanded) "收起高级采样参数" else "高级采样参数")
                    }
                    if (advancedSamplingExpanded) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = topKText,
                                onValueChange = { topKText = it },
                                label = { Text("top_k") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = minPText,
                                onValueChange = { minPText = it },
                                label = { Text("min_p") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = repeatPenaltyText,
                                onValueChange = { repeatPenaltyText = it },
                                label = { Text("重复惩罚") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = presencePenaltyText,
                                onValueChange = { presencePenaltyText = it },
                                label = { Text("presence") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = frequencyPenaltyText,
                                onValueChange = { frequencyPenaltyText = it },
                                label = { Text("frequency") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = stopWordsText,
                                onValueChange = { stopWordsText = it.take(4096) },
                                label = { Text("停止词 JSON 数组") },
                                placeholder = { Text("[\"<END>\", \"\\nUser:\"]") },
                                minLines = 2,
                                maxLines = 4,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ReasoningMode.entries.forEach { mode ->
                            FilterChip(
                                selected = reasoningMode == mode,
                                onClick = { reasoningMode = mode },
                                label = { Text(mode.label) }
                            )
                        }
                    }
                    Text(
                        "助手只保存生成参数；上下文、线程和 native 加载参数属于当前模型配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    Text("角色卡", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            onClick = {
                                val copyName = name.trim().ifBlank { assistant?.name ?: "未命名助手" }.let { "$it 副本" }
                                submitDraft(buildDraft(null, copyName), closeOnSuccess = false)
                            },
                            enabled = assistant != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("复制为新助手")
                        }
                        TextButton(
                            onClick = { assistant?.id?.let(onDelete) },
                            enabled = assistant != null && assistant.id != "default",
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                "删除助手",
                                color = if (assistant != null && assistant.id != "default") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                saveError?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = { submitDraft(buildDraft(assistant?.id), closeOnSuccess = true) },
                        modifier = Modifier.weight(1.45f).height(48.dp),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text("保存")
                    }
                }
            }
        }
        if (showMemoryManager && assistant != null) {
            AssistantMemoryManagerDialog(
                assistant = assistant,
                memories = memories,
                onDismiss = { showMemoryManager = false },
                onUpsert = onUpsertMemory,
                onDelete = onDeleteMemory
            )
        }
}
