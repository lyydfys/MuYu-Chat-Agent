package com.muyuchat.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class GuidanceAction(val label: String) {
    MODELS("选择模型"), EDIT("返回修改"), DETAILS("查看详情")
}

internal data class UserNotice(val problem: String, val nextStep: String, val action: GuidanceAction)

// Legacy messages remain available in details. Only recognized failures get specific advice.
internal fun userNotice(message: String): UserNotice? = when {
    message.startsWith("请先在模型") || message.contains("未加载模型") ->
        UserNotice("尚未准备好模型", "选择已有模型，或下载一个推荐模型。", GuidanceAction.MODELS)
    message.contains("失败") || message.contains("不完整") || message.contains("不可用") ||
        message.contains("不支持") || message.contains("无效") || message.contains("必须") ||
        message.contains("请先选择") || message.contains("超过") ||
        message.contains("请使用英文") || message.startsWith("当前模型仅支持英文") -> when {
            message.contains("不完整") || message.contains("组件校验失败") ->
                UserNotice("模型文件不完整或校验失败", "在模型管理中检查缺失组件，再重新下载或导入。", GuidanceAction.MODELS)
            message.startsWith("请使用英文") || message.startsWith("当前模型仅支持英文") ->
                UserNotice("当前提示词不符合模型要求", "请使用英文提示词，原输入已保留。", GuidanceAction.EDIT)
            message.contains("尺寸") || message.contains("CFG") || message.contains("Seed") ||
                message.contains("采样器") || message.contains("强度") || message.contains("参数") ->
                UserNotice("生成设置需要调整", "返回生成页查看参数，使用当前模型支持的值。", GuidanceAction.EDIT)
            message.contains("请先选择") || message.contains("不支持此生成方式") ->
                UserNotice("生成方式或输入图片尚未就绪", "选择支持的生成方式，并补齐原图、蒙版或控制图。", GuidanceAction.EDIT)
            else -> UserNotice("本次操作未完成", "查看错误详情后重试；持续失败时可复制详情反馈。", GuidanceAction.DETAILS)
        }
    else -> null
}

@Composable
internal fun GuidanceNotice(
    message: String,
    onModels: () -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val notice = userNotice(message) ?: return
    var showDetails by rememberSaveable(message) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(notice.problem, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
        Text(notice.nextStep, style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = {
                when (notice.action) {
                    GuidanceAction.MODELS -> onModels()
                    GuidanceAction.EDIT -> onEdit()
                    GuidanceAction.DETAILS -> showDetails = true
                }
            }) { Text(notice.action.label) }
            if (notice.action != GuidanceAction.DETAILS) {
                TextButton(onClick = { showDetails = true }) { Text("详情") }
            }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("错误详情") },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(message, modifier = Modifier.verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("返回") } }
        )
    }
}

@Composable
internal fun QuickStartDialog(onDismiss: () -> Unit, onChooseModel: () -> Unit) {
    var images by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("快速开始") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row {
                    androidx.compose.material3.FilterChip(selected = !images, onClick = { images = false }, label = { Text("聊天") })
                    androidx.compose.material3.FilterChip(selected = images, onClick = { images = true }, label = { Text("生图") })
                }
                Text(if (images) "1. 在推荐页下载生图模型，或导入完整模型包。\n\n2. 在图片页选择模型，保留模型默认参数。\n\n3. 输入图片描述并生成，结果保存到图片库。"
                    else "1. 在推荐页下载聊天模型，或导入已有模型。\n\n2. 从此入口下载推荐模型会自动加载；实验模型或已有模型可在本地页手动加载。\n\n3. 等待加载完成，返回聊天发送问题。")
                Text(if (images) "部分模型需要英文提示词；尺寸按模型设定。生成失败时，输入会保留，可从错误提示继续处理。"
                    else "已有云端服务账号时，也可在模型管理的云端页配置连接。下载、加载和生成是不同阶段。")
            }
        },
        confirmButton = { TextButton(onClick = onChooseModel) { Text("打开推荐页") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
