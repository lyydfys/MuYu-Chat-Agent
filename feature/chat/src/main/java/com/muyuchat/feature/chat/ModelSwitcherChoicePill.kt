package com.muyuchat.feature.chat

import com.muyuchat.core.designsystem.McaIconSize
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaTextField
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaChip
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.muyuchat.core.designsystem.McaChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.designsystem.mcaPressScale
import com.muyuchat.core.designsystem.rememberMcaHaptics
import com.muyuchat.feature.chat.ImeAwareAlertDialog as AlertDialog

@Composable
internal fun ModelChoicePill(
    model: ChatModelChoice,
    enabled: Boolean,
    onClick: () -> Unit,
    onBackendChange: (String, String) -> Unit
) {
    val darkTheme = isSystemInDarkTheme()
    val selectedColor = if (darkTheme) MaterialTheme.colorScheme.primary else Color(0xFF1A73E8)
    val interactionSource = remember { MutableInteractionSource() }
    val haptics = rememberMcaHaptics()
    var customGpuDialogVisible by rememberSaveable(model.id) { mutableStateOf(false) }
    var customGpuLayersText by rememberSaveable(model.id, model.customGpuLayers) {
        mutableStateOf((model.customGpuLayers ?: 32).toString())
    }
    var customGpuLayerError by rememberSaveable(model.id) { mutableStateOf<String?>(null) }
    if (customGpuDialogVisible) {
        AlertDialog(
            onDismissRequest = {
                customGpuDialogVisible = false
                customGpuLayerError = null
            },
            title = { Text("自定义 GPU 层数") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "仅对当前 GGUF 模型生效。数值越大，尝试放到 GPU 的层越多；0 表示 CPU。" +
                            "设备或显存不足时，加载会给出具体错误，不会静默假装使用 GPU。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    McaTextField(
                        value = customGpuLayersText,
                        onValueChange = {
                            customGpuLayersText = it.filter(Char::isDigit).take(4)
                            customGpuLayerError = null
                        },
                        label = { Text("GPU 层数（0–4096）") },
                        singleLine = true,
                        isError = customGpuLayerError != null,
                        supportingText = customGpuLayerError?.let { error -> { Text(error) } }
                    )
                }
            },
            confirmButton = {
                McaButton(style = McaButtonStyle.Text, hapticsEnabled = false, onClick = {
                    val layers = customGpuLayersText.toIntOrNull()
                    if (layers == null || layers !in 0..4096) {
                        customGpuLayerError = "请输入 0 到 4096 之间的整数。"
                    } else {
                        haptics.confirm()
                        onBackendChange(model.id, "custom:$layers")
                        customGpuDialogVisible = false
                        customGpuLayerError = null
                    }
                }) { Text("保存并加载") }
            },
            dismissButton = {
                McaButton(style = McaButtonStyle.Text,onClick = {
                    customGpuDialogVisible = false
                    customGpuLayerError = null
                }) { Text("取消") }
            }
        )
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = {
                    haptics.tick()
                    onClick()
                }
            )
            .mcaPressScale(interactionSource, enabled = enabled),
        color = if (model.loaded) {
            if (darkTheme) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f) else Color(0xFFE8F0FE)
        } else {
            Color.Transparent
        },
        shape = RoundedCornerShape(22.dp),
        border = if (model.loaded) {
            BorderStroke(0.5.dp, selectedColor.copy(alpha = 0.45f))
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (model.loaded) {
                Icon(McaIcons.Check, contentDescription = null, modifier = Modifier.size(McaIconSize.Optical.Inline), tint = selectedColor)
            } else {
                Spacer(modifier = Modifier.size(McaIconSize.Optical.Inline))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = displayModelName(model.displayName) ?: model.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (model.loaded) selectedColor else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 16.sp),
                    fontWeight = if (model.loaded) FontWeight.SemiBold else FontWeight.Medium
                )
                Text(
                    text = buildList {
                        if (model.loaded) add("当前")
                        if (model.cloud) {
                            if (model.subtitle.isNotBlank()) add(model.subtitle)
                        } else {
                            if (model.subtitle.isNotBlank()) add(model.subtitle)
                            model.quant?.let(::add)
                            formatModelBytes(model.sizeBytes)?.let(::add)
                        }
                    }.joinToString(" · ").ifBlank { if (model.cloud) "云端模型" else "本地推理" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (model.loaded) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                    fontWeight = if (model.loaded) FontWeight.Medium else FontWeight.Normal
                )
                if (model.backendOptions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        model.backendOptions.forEach { option ->
                            McaChip(
                                selected = model.selectedBackendId == option.id,
                                onClick = {
                                    if (option.id == "custom") {
                                        customGpuLayersText = (model.customGpuLayers ?: 32).toString()
                                        customGpuDialogVisible = true
                                    } else {
                                        onBackendChange(model.id, option.id)
                                    }
                                },
                                enabled = option.enabled && enabled,
                                label = {
                                    Text(
                                        option.label,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp)
                                    )
                                },
                                modifier = Modifier.heightIn(min = 28.dp)
                            )
                        }
                    }
                    model.backendOptions
                        .firstOrNull { it.id == model.selectedBackendId }
                        ?.availabilityNote
                        ?.takeIf(String::isNotBlank)
                        ?.let { note ->
                            Text(
                                text = note,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp)
                            )
                        }
                }
            }
        }
    }
}
