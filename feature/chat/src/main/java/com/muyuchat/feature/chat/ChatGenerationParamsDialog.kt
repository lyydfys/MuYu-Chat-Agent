package com.muyuchat.feature.chat

import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaChip
import com.muyuchat.core.designsystem.McaTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog
import com.muyuchat.core.designsystem.McaChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
// P9/S14: private→internal（跨区入口：迁移的模型切换复合区引用）
internal fun GenerationParamsQuickDialog(
    params: GenerationParams,
    onDismiss: () -> Unit,
    onApply: (GenerationParams) -> Unit
) {
    var temperatureText by remember(params) { mutableStateOf(params.temperature.cleanParamText()) }
    var topPText by remember(params) { mutableStateOf(params.topP.cleanParamText()) }
    var topKText by remember(params) { mutableStateOf(params.topK.toString()) }
    var minPText by remember(params) { mutableStateOf(params.minP.cleanParamText()) }
    var repeatPenaltyText by remember(params) { mutableStateOf(params.repeatPenalty.cleanParamText()) }
    var nPredictText by remember(params) { mutableStateOf(params.nPredict.toString()) }
    var nCtxText by remember(params) { mutableStateOf(params.nCtx.toString()) }
    var reasoningMode by remember(params) { mutableStateOf(params.reasoningMode) }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun parseFloat(label: String, value: String, min: Float, max: Float): Float? {
        val parsed = value.trim().toFloatOrNull()
        if (parsed == null || !parsed.isFinite() || parsed !in min..max) {
            errorText = "$label 请输入 ${min.cleanParamText()} 到 ${max.cleanParamText()} 之间的数字"
            return null
        }
        return parsed
    }

    fun parseInt(label: String, value: String, min: Int, max: Int): Int? {
        val parsed = value.trim().toIntOrNull()
        if (parsed == null || parsed !in min..max) {
            errorText = "$label 请输入 $min 到 $max 之间的整数"
            return null
        }
        return parsed
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("当前模型参数") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "只影响当前模型，切换模型后会自动恢复该模型的设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                McaTextField(value = temperatureText, onValueChange = { temperatureText = it; errorText = null }, label = { Text("temperature") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                McaTextField(value = topPText, onValueChange = { topPText = it; errorText = null }, label = { Text("top_p") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                McaTextField(value = topKText, onValueChange = { topKText = it; errorText = null }, label = { Text("top_k") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                McaTextField(value = minPText, onValueChange = { minPText = it; errorText = null }, label = { Text("min_p") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                McaTextField(value = repeatPenaltyText, onValueChange = { repeatPenaltyText = it; errorText = null }, label = { Text("重复惩罚") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                McaTextField(value = nPredictText, onValueChange = { nPredictText = it; errorText = null }, label = { Text("最大输出 tokens") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                McaTextField(
                    value = nCtxText,
                    onValueChange = { nCtxText = it; errorText = null },
                    label = { Text("上下文 n_ctx") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "n_ctx 决定单次可用的上下文窗口，必须大于最大输出 tokens；修改后需重新加载模型。DSH 标准工具模式建议 12288 以上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("思考模式", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReasoningMode.entries.forEach { mode ->
                        McaChip(
                            selected = reasoningMode == mode,
                            onClick = { reasoningMode = mode },
                            label = { Text(mode.shortLabel()) }
                        )
                    }
                }
                errorText?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            McaButton(style = McaButtonStyle.Text,onClick = {
                val temperature = parseFloat("temperature", temperatureText, 0f, 2f) ?: return@McaButton
                val topP = parseFloat("top_p", topPText, 0f, 1f) ?: return@McaButton
                val topK = parseInt("top_k", topKText, 0, 1000) ?: return@McaButton
                val minP = parseFloat("min_p", minPText, 0f, 1f) ?: return@McaButton
                val repeatPenalty = parseFloat("重复惩罚", repeatPenaltyText, 0.5f, 2f) ?: return@McaButton
                val nPredict = parseInt("最大输出 tokens", nPredictText, 16, 65_536) ?: return@McaButton
                val nCtxError = validateChatContextLength(nCtxText, nPredict)
                if (nCtxError != null) {
                    errorText = nCtxError
                    return@McaButton
                }
                val nCtx = nCtxText.trim().toInt()
                onApply(params.copy(temperature = temperature, topP = topP, topK = topK, minP = minP, repeatPenalty = repeatPenalty, nPredict = nPredict, nCtx = nCtx, reasoningMode = reasoningMode, hideReasoning = reasoningMode == ReasoningMode.OFF))
            }) { Text("应用") }
        },
        dismissButton = { McaButton(style = McaButtonStyle.Text,onClick = onDismiss) { Text("取消") } }
    )
}
