@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.modelhub

import com.muyuchat.core.designsystem.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest

@Composable
internal fun ModelImportProgressCard(
    task: ModelImportTaskUi,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    CardBox(modifier = modifier) {
        Text(task.message, style = MaterialTheme.typography.bodyMedium)
        task.file?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        if (task.active) {
            if (task.totalBytes > 0) {
                LinearProgressIndicator(progress = { (task.bytes.toDouble() / task.totalBytes).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
            } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (task.active || task.resumable) {
            McaButton(style = McaButtonStyle.Text, onClick = { if (task.active) onPause(task.id) else onResume(task.id) }) {
                Text(if (task.active) "暂停导入" else "继续 / 重试导入")
            }
            Text("已完成组件经校验后可复用；未完成或源信息不明的组件重新复制。若源文件权限失效，请重新选择。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun StatusMessageCard(message: String, isError: Boolean = false) {
    val background = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = background,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = foreground,
            lineHeight = 18.sp
        )
    }
}

internal fun chatBaseUrlPlaceholder(format: String): String =
    when (format) {
        "ANTHROPIC" -> "例如 https://api.anthropic.com/v1"
        "OPENAI_RESPONSES" -> "例如 https://api.example.com/v1 或完整 /responses 地址"
        else -> "例如 https://api.example.com/v1"
    }

internal fun chatModelPlaceholder(format: String): String =
    when (format) {
        "ANTHROPIC" -> "输入模型名，例如 your-anthropic-model"
        else -> "输入模型名，例如 your-chat-model"
    }

internal fun imageBaseUrlPlaceholder(format: String): String =
    when (format) {
        "DASHSCOPE_IMAGE" -> "例如 https://dashscope.aliyuncs.com"
        "CUSTOM_PATH" -> "例如 https://api.example.com/v1"
        else -> "例如 https://api.example.com/v1"
    }

internal fun imageEndpointPlaceholder(format: String): String =
    when (format) {
        "DASHSCOPE_IMAGE" -> "api/v1/services/aigc/multimodal-generation/generation"
        "CUSTOM_PATH" -> "例如 images/generations 或自建 image 路径"
        else -> "images/generations"
    }

internal fun imageModelPlaceholder(format: String): String =
    when (format) {
        "DASHSCOPE_IMAGE" -> "输入生图模型名，例如 your-image-model"
        else -> "输入生图模型名，例如 your-image-model"
    }

internal fun imageSizePlaceholder(format: String): String =
    when (format) {
        "DASHSCOPE_IMAGE" -> "例如 1024*1024 或 1024x1024"
        else -> "例如 1024x1024"
    }

@Composable
internal fun EmptyCard(title: String, body: String) {
    CardBox {
        Text(title, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CardBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

internal fun formatBytes(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    val mb = bytes / 1024.0 / 1024.0
    return if (gb >= 1.0) "%.2f GB".format(gb) else "%.1f MB".format(mb)
}

internal fun shortName(value: String): String =
    value.substringAfterLast('/')
        .substringAfterLast('\\')
        .removeSuffix(".gguf")
        .removeSuffix(".mnn")
        .let { if (it.length > 36) it.take(33) + "..." else it }

internal fun localVisionStatusText(model: ModelManifest, isLoaded: Boolean): String =
    when {
        model.runtime == ChatModelRuntime.MNN ->
            if (isLoaded) {
                "\u672c\u5730\u8bc6\u56fe\uff1aMNN \u591a\u6a21\u6001\u5305\u5df2\u52a0\u8f7d\uff1b\u5982\u5305\u5185\u542b visual.mnn\uff0c\u53ef\u76f4\u63a5\u53d1\u9001\u56fe\u7247\u3002"
            } else {
                "\u672c\u5730\u8bc6\u56fe\uff1aMNN \u591a\u6a21\u6001\u5305\u52a0\u8f7d\u540e\u53ef\u542f\u7528 visual.mnn\uff1b\u7eaf\u6587\u672c MNN \u4ecd\u53ea\u652f\u6301\u804a\u5929\u3002"
            }
        model.hasVisionProjector && isLoaded ->
            "\u672c\u5730\u8bc6\u56fe\uff1a\u5df2\u7ed1\u5b9a ${model.visionProjectorFileName ?: "mmproj"}\uff0c\u5982\u804a\u5929\u9875\u4ecd\u63d0\u793a\u672a\u5c31\u7eea\uff0c\u8bf7\u91cd\u65b0\u52a0\u8f7d\u6a21\u578b\u3002"
        model.hasVisionProjector ->
            "\u672c\u5730\u8bc6\u56fe\uff1a\u5df2\u7ed1\u5b9a ${model.visionProjectorFileName ?: "mmproj"}\uff0c\u52a0\u8f7d\u8be5\u6a21\u578b\u540e\u53ef\u53d1\u9001\u56fe\u7247\u3002"
        else ->
            "\u672c\u5730\u8bc6\u56fe\uff1a\u7eaf\u6587\u672c\u6a21\u578b\u4e0d\u80fd\u76f4\u63a5\u8bc6\u56fe\uff1b\u8bf7\u4f7f\u7528 MNN \u591a\u6a21\u6001\u5305\u6216\u7ed1\u5b9a\u5339\u914d mmproj\u3002"
    }

internal fun sourceLabel(value: String): String = when (value.lowercase()) {
    "modelscope" -> "魔塔"
    "hugging_face", "huggingface", "hugging-face" -> "Hugging Face"
    "local" -> "本地"
    else -> "本机"
}

internal fun totalRamGb(bytes: Long): Double = bytes / 1024.0 / 1024.0 / 1024.0

@Composable
internal fun ModelStoragePath(path: String) {
    if (path.isBlank()) return
    var expanded by rememberSaveable(path) { mutableStateOf(false) }
    McaButton(style = McaButtonStyle.Text, onClick = { expanded = !expanded }) { Text(if (expanded) "收起保存位置" else "查看保存位置") }
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(animationSpec = McaMotion.fade()) +
            expandVertically(animationSpec = McaMotion.sizeChangePx),
        exit = fadeOut(animationSpec = McaMotion.fade()) +
            shrinkVertically(animationSpec = McaMotion.sizeChangePx)
    ) {
        SelectionContainer {
            Text(path, style = MaterialTheme.typography.bodySmall)
        }
    }
}
