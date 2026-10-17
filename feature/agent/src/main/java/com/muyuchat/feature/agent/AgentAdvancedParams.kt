@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LlamaAdvancedParams
import kotlin.math.roundToInt

@Composable
internal fun AdvancedParamsCard(
    params: GenerationParams,
    onParamsChange: (GenerationParams) -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val advancedResult = remember(params.advancedJson) {
        LlamaAdvancedParams.parse(params.advancedJson)
    }
    val advanced = advancedResult.params
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("参数分层", fontWeight = FontWeight.Bold)
                    Text(
                        "模型执行配置与助手/会话生成配置独立保存，不再共用同一份参数。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起" else "展开")
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "执行：上下文 ${params.nCtx} · 线程 ${params.nThreads} · 模板 ${params.chatTemplateMode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "生成：最大输出 ${params.nPredict} · 温度 ${"%.2f".format(params.temperature)} · top_p ${"%.2f".format(params.topP)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!expanded) return@Column

            ParameterLayerTitle(
                title = "模型执行参数（加载 / 热执行）",
                description = "只属于当前模型、设备和 runtime。加载字段变化会创建待应用候选；热执行字段仅在 runtime 明确支持时原位生效。"
            )
            ParameterSubsectionTitle(
                title = "加载参数",
                description = "上下文、batch、KV、GPU/MoE、MTP、mmap/mlock 等会进入加载签名，修改后需要受控重载。"
            )
            ContextLengthEditor(params.nCtx) {
                onParamsChange(params.copy(nCtx = it))
            }
            Text(
                "下列结构化控件是 llama.cpp 专属字段；MNN、QAIRT 等 runtime 必须由各自适配器过滤，禁止跨 runtime 透传。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (advanced == null) {
                Text(
                    "高级 JSON 格式无效，请先在下方修正后再使用结构化控件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                val updateAdvanced: (LlamaAdvancedParams) -> Unit = { updated ->
                    onParamsChange(params.copy(advancedJson = updated.toJsonString()))
                }
                AdvancedChoiceChips(
                    title = "逻辑批大小",
                    selected = (advanced.nBatch ?: 512).toString(),
                    choices = listOf("256", "512", "1024", "2048")
                ) { raw ->
                    val batch = raw.toInt()
                    val currentUbatch = advanced.nUbatch ?: 256
                    updateAdvanced(
                        advanced.copy(
                            nBatch = batch,
                            nUbatch = currentUbatch.coerceAtMost(batch)
                        )
                    )
                }
                val effectiveBatch = advanced.nBatch ?: 512
                AdvancedChoiceChips(
                    title = "物理批大小",
                    selected = (advanced.nUbatch ?: 256).toString(),
                    choices = listOf(32, 64, 128, 256, 512, 1024, 2048)
                        .filter { it <= effectiveBatch && effectiveBatch % it == 0 }
                        .map(Int::toString)
                ) { raw -> updateAdvanced(advanced.copy(nUbatch = raw.toInt())) }
                AdvancedChoiceChips(
                    title = "K Cache",
                    selected = advanced.cacheTypeK ?: "f16",
                    choices = listOf("f16", "q8_0", "q4_0")
                ) { updateAdvanced(advanced.copy(cacheTypeK = it)) }
                AdvancedChoiceChips(
                    title = "V Cache",
                    selected = advanced.cacheTypeV ?: "f16",
                    choices = listOf("f16", "q8_0", "q4_0")
                ) { updateAdvanced(advanced.copy(cacheTypeV = it)) }
                AdvancedChoiceChips(
                    title = "Flash Attention",
                    selected = advanced.flashAttn ?: "auto",
                    choices = listOf("auto", "on", "off")
                ) { updateAdvanced(advanced.copy(flashAttn = it)) }
                AdvancedChoiceChips(
                    title = "推测解码",
                    selected = advanced.specType ?: "none",
                    choices = listOf("none", "draft-mtp")
                ) { updateAdvanced(advanced.copy(specType = it)) }
                if (advanced.specType == "draft-mtp") {
                    CompactIntSlider(
                        "MTP 最大草稿 token",
                        advanced.specDraftNMax ?: 2,
                        0f..8f
                    ) { updateAdvanced(advanced.copy(specDraftNMax = it)) }
                }
                AdvancedChoiceChips(
                    title = "并行序列",
                    selected = (advanced.nParallel ?: 1).toString(),
                    choices = listOf("1")
                ) { updateAdvanced(advanced.copy(nParallel = it.toInt())) }
                Text(
                    "当前 Android JNI 为单会话执行器，并行序列固定为 1。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AdvancedChoiceChips(
                    title = "GPU 层",
                    selected = (advanced.nGpuLayers ?: 0).toString(),
                    choices = listOf("0", "-1", "-2", "999"),
                    labels = mapOf("0" to "CPU", "-1" to "自动", "-2" to "全部", "999" to "999")
                ) { updateAdvanced(advanced.copy(nGpuLayers = it.toInt())) }
                CompactIntSlider(
                    "主 GPU",
                    advanced.mainGpu ?: 0,
                    0f..3f
                ) { updateAdvanced(advanced.copy(mainGpu = it)) }
                AdvancedChoiceChips(
                    title = "拆分模式",
                    selected = advanced.splitMode ?: "none",
                    choices = listOf("none", "layer", "row", "tensor")
                ) { updateAdvanced(advanced.copy(splitMode = it)) }
                CompactIntSlider(
                    "CPU MoE 层",
                    advanced.nCpuMoe ?: 0,
                    0f..64f
                ) { updateAdvanced(advanced.copy(nCpuMoe = it)) }
                Text(
                    "GPU/MoE 控件记录的是请求值；是否支持及最终有效值必须以当前 APK 注册的 backend 与 Native 回读为准。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AdvancedBooleanChips("性能统计", advanced.perf ?: true) {
                    updateAdvanced(advanced.copy(perf = it))
                }
                AdvancedBooleanChips("内存映射 mmap", advanced.mmap ?: true) {
                    updateAdvanced(advanced.copy(mmap = it))
                }
                AdvancedBooleanChips("锁定内存 mlock", advanced.mlock ?: false) {
                    updateAdvanced(advanced.copy(mlock = it))
                }
            }

            ParameterSubsectionTitle(
                title = "热执行参数",
                description = "线程与缓存复用只有在当前 runtime 策略声明 HOT_EXECUTION 时才可原位应用；否则仍会进入待重载候选。"
            )
            CompactIntSlider("运行线程 n_threads", params.nThreads, 1f..16f) {
                onParamsChange(params.copy(nThreads = it))
            }
            if (advanced != null) {
                val updateAdvanced: (LlamaAdvancedParams) -> Unit = { updated ->
                    onParamsChange(params.copy(advancedJson = updated.toJsonString()))
                }
                CompactIntSlider(
                    "批处理线程 n_threads_batch",
                    advanced.nThreadsBatch ?: params.nThreads,
                    1f..16f
                ) { updateAdvanced(advanced.copy(nThreadsBatch = it)) }
                AdvancedChoiceChips(
                    title = "前缀缓存复用 cache_reuse",
                    selected = (advanced.cacheReuse ?: 0).toString(),
                    choices = listOf("0", "128", "256", "512")
                ) { updateAdvanced(advanced.copy(cacheReuse = it.toInt())) }
            }

            ParameterSubsectionTitle(
                title = "模板与执行正确性 gate",
                description = "模板/Jinja 会改变 role、prompt 与特殊 token。每次变化必须重跑完整正确性验证，不能进入纯性能搜索。"
            )
            OutlinedTextField(
                value = params.chatTemplateMode,
                onValueChange = { onParamsChange(params.copy(chatTemplateMode = it.ifBlank { "auto" })) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("对话模板模式 chat_template_mode") }
            )
            if (advanced != null) {
                val updateAdvanced: (LlamaAdvancedParams) -> Unit = { updated ->
                    onParamsChange(params.copy(advancedJson = updated.toJsonString()))
                }
                AdvancedBooleanChips("Jinja 模板 use_jinja", advanced.useJinja ?: true) {
                    updateAdvanced(advanced.copy(useJinja = it))
                }
            }
            Text(
                "cache_reuse 同样影响正确性，候选必须验证缓存命中与未命中回答等价。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (advancedResult.issues.isNotEmpty()) {
                advancedResult.errorMessages.forEach { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            OutlinedTextField(
                value = params.advancedJson,
                onValueChange = { onParamsChange(params.copy(advancedJson = it.ifBlank { "{}" })) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("Runtime 高级 JSON（当前模型专属）") },
                supportingText = { Text("未知或不支持字段必须隔离，不得直接透传 native。") }
            )

            ParameterLayerTitle(
                title = "助手 / 会话生成参数",
                description = "采样参数和最大输出只影响生成，不触发模型重载。智能调参只能给出建议。"
            )
            CompactIntSlider("最大输出 n_predict", params.nPredict, 128f..65536f) {
                onParamsChange(params.copy(nPredict = it))
            }
            CompactFloatSlider("创造性 temperature", params.temperature, 0f..2f) {
                onParamsChange(params.copy(temperature = it))
            }
            CompactFloatSlider("回答聚焦度 top_p", params.topP, 0.1f..1f) {
                onParamsChange(params.copy(topP = it))
            }
            CompactIntSlider("候选数量 top_k", params.topK, 0f..200f) {
                onParamsChange(params.copy(topK = it))
            }
            CompactFloatSlider("最小概率 min_p", params.minP, 0f..1f) {
                onParamsChange(params.copy(minP = it))
            }
            CompactFloatSlider("重复惩罚", params.repeatPenalty, 1.0f..1.3f) {
                onParamsChange(params.copy(repeatPenalty = it))
            }
            CompactFloatSlider("存在惩罚", params.presencePenalty, -2f..2f) {
                onParamsChange(params.copy(presencePenalty = it))
            }
            CompactFloatSlider("频率惩罚", params.frequencyPenalty, -2f..2f) {
                onParamsChange(params.copy(frequencyPenalty = it))
            }
        }
    }
}

@Composable
private fun ParameterLayerTitle(title: String, description: String) {
    Column(
        modifier = Modifier.padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ParameterSubsectionTitle(title: String, description: String) {
    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AdvancedChoiceChips(
    title: String,
    selected: String,
    choices: List<String>,
    labels: Map<String, String> = emptyMap(),
    onSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        choices.chunked(4).forEach { rowChoices ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowChoices.forEach { choice ->
                    FilterChip(
                        selected = selected == choice,
                        onClick = { onSelected(choice) },
                        label = { Text(labels[choice] ?: choice) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvancedBooleanChips(
    title: String,
    value: Boolean,
    onValue: (Boolean) -> Unit
) {
    AdvancedChoiceChips(
        title = title,
        selected = if (value) "on" else "off",
        choices = listOf("on", "off"),
        labels = mapOf("on" to "开启", "off" to "关闭")
    ) { onValue(it == "on") }
}

@Composable
private fun CompactIntSlider(
    title: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$title：$value", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Slider(
            value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = { onValue(it.toInt()) },
            valueRange = range
        )
    }
}

@Composable
private fun ContextLengthEditor(
    value: Int,
    onValue: (Int) -> Unit
) {
    var exactText by remember { mutableStateOf(value.toString()) }
    val validation = validateContextLengthInput(exactText)

    LaunchedEffect(value) {
        if (validation.value != value) exactText = value.toString()
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = exactText,
            onValueChange = { input ->
                exactText = input
                validateContextLengthInput(input).value?.let { parsed ->
                    if (parsed != value) onValue(parsed)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = validation.error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            label = { Text("上下文长度 n_ctx（精确值）") },
            supportingText = {
                Text(
                    validation.error
                        ?: "精确值会原样保存；llama.cpp 如需内部分配对齐，不会改写这里的逻辑值。"
                )
            }
        )
        Text(
            "快捷值：${contextLengthShortcutValue(nearestContextLengthShortcutStep(value))}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = nearestContextLengthShortcutStep(value).toFloat(),
            onValueChange = { position ->
                val shortcut = contextLengthShortcutValue(position.roundToInt())
                exactText = shortcut.toString()
                if (shortcut != value) onValue(shortcut)
            },
            valueRange = 0f..(CONTEXT_SHORTCUT_STEP_COUNT - 1).toFloat(),
            steps = CONTEXT_SHORTCUT_STEP_COUNT - 2
        )
        Text(
            "滑杆提供 128、256、512…1048576 的 2 倍快捷值；也可在上方输入范围内任意整数，例如 8190。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CompactFloatSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$title：${"%.2f".format(value)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValue,
            valueRange = range
        )
    }
}
