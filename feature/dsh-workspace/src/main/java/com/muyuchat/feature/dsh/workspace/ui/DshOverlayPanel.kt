package com.muyuchat.feature.dsh.workspace.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaShape
import com.muyuchat.core.designsystem.McaSwitch
import com.muyuchat.core.dsh.contract.DshOverlayAppearance

/**
 * The app-outside display settings surface.
 *
 * Opened from the in-app DSH floating ball, so it is a dialog over the official page instead of a new
 * navigation destination. It edits only the window MCA adds — the DSH whale ball — and therefore keeps
 * just the switches that still mean something after the mascot feature was removed: on/off, reply
 * bubbles, live streaming, dragging, and stopping the background keep-alive.
 *
 * Every control writes through [DshOverlayUiPort]; the panel keeps no durable state of its own, so a
 * refused change (missing overlay permission) is reported back and re-read from the port. The same
 * switches are also reachable from the window itself (long press on the ball).
 */
@Composable
fun DshOverlayPanel(
    port: DshOverlayUiPort,
    onDismiss: () -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(port.snapshot()) }
    var appearance by remember { mutableStateOf(snapshot.appearance) }

    fun refresh() {
        snapshot = port.snapshot()
        appearance = snapshot.appearance
    }

    fun commit(next: DshOverlayAppearance) {
        appearance = next.normalized()
        port.saveAppearance(appearance)?.let(onNotice)
    }

    // The overlay grant lives in system settings; re-read it when the user comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp),
            shape = McaShape.Sheet,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp
        ) {
            Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "App 外显示",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    McaButton(style = McaButtonStyle.Text, onClick = onDismiss) { Text("完成") }
                }
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ToggleRow(
                        title = "在其他应用上层显示 DSH",
                        description = "开启后 DSH 在后台持续运行，回到 DSH 不会重启。",
                        checked = snapshot.enabled,
                        onCheckedChange = { enabled ->
                            port.setEnabled(enabled)?.let(onNotice)
                            refresh()
                        }
                    )
                    if (!snapshot.permissionGranted) {
                        PermissionCard(onOpenSettings = { openOverlaySettings(context, onNotice) })
                    }
                    if (snapshot.backgroundRunning) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "后台保持运行中",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            McaButton(style = McaButtonStyle.Text, onClick = {
                                port.stopBackground()?.let(onNotice)
                                refresh()
                            }) {
                                Text("停止后台运行")
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ToggleRow(
                        title = "回复时弹出气泡",
                        description = "DSH 一轮回答结束后，在悬浮球旁显示开头几句；关闭后只用动效表示正在生成。",
                        checked = appearance.notifyReplies,
                        onCheckedChange = { commit(appearance.copy(notifyReplies = it)) }
                    )
                    StepRow(
                        title = "回复气泡行数",
                        value = if (appearance.bubbleLines == 0) {
                            "关闭"
                        } else {
                            "${appearance.bubbleLines} 行"
                        },
                        canDecrease = appearance.bubbleLines > 0,
                        canIncrease = appearance.bubbleLines < DshOverlayAppearance.MAX_BUBBLE_LINES,
                        onDecrease = {
                            commit(appearance.copy(bubbleLines = appearance.bubbleLines - 1))
                        },
                        onIncrease = {
                            commit(appearance.copy(bubbleLines = appearance.bubbleLines + 1))
                        }
                    )
                    ToggleRow(
                        title = "流式上屏",
                        description = "DSH 生成时把正在写的内容实时显示在悬浮球旁（最多三行）；" +
                            "关闭后只在回答结束时弹一次气泡。",
                        checked = snapshot.streamingEnabled,
                        onCheckedChange = { enabled ->
                            port.setStreamingEnabled(enabled)?.let(onNotice)
                            refresh()
                        }
                    )
                    ToggleRow(
                        title = "允许拖动",
                        description = "关闭后悬浮球固定在当前位置，避免误触移动。",
                        checked = appearance.draggable,
                        onCheckedChange = { commit(appearance.copy(draggable = it)) }
                    )
                    Text(
                        text = "提示：官方 DeepSeek 账号直连的对话不经过 MCA 网关，因此不会弹出回复气泡；" +
                            "本机模型与云模型网关的对话会弹出。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        McaSwitch(checked = checked, onCheckedChange = onCheckedChange, contentDescription = title)
    }
}

/** A discrete value row with − / + controls; used for the bubble line count. */
@Composable
private fun StepRow(
    title: String,
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        StepButton(label = "－", enabled = canDecrease, onClick = onDecrease)
        Text(
            text = value,
            modifier = Modifier.padding(horizontal = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        StepButton(label = "＋", enabled = canIncrease, onClick = onIncrease)
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        }
    )
}

@Composable
private fun PermissionCard(onOpenSettings: () -> Unit) {
    Surface(
        shape = McaShape.Card,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "缺少「显示在其他应用上层」权限",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "没有这个权限，系统会拒绝悬浮窗。请在设置里为 MCA 打开该项后返回。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 2.dp)
            )
            McaButton(style = McaButtonStyle.Text, onClick = onOpenSettings) { Text("去授权") }
        }
    }
}

/**
 * Opens the system "display over other apps" screen for this package. Both the per-app deep link and the
 * list fallback are attempted; a device without either leaves the panel's explanation in place rather
 * than pretending the grant was requested.
 */
internal fun openOverlaySettings(context: Context, onFailure: (String) -> Unit = {}) {
    val appIntent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val started = runCatching { context.startActivity(appIntent) }.isSuccess ||
        runCatching { context.startActivity(fallback) }.isSuccess
    if (!started) onFailure("无法打开系统悬浮窗权限设置，请手动前往系统设置授权")
}
