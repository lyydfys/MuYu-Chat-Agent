package com.muyuchat.feature.dsh.workspace.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.muyuchat.core.designsystem.McaChip
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaMascot
import com.muyuchat.core.designsystem.McaMascotMark
import com.muyuchat.core.designsystem.McaShape
import com.muyuchat.core.designsystem.McaSlider
import com.muyuchat.core.designsystem.McaSwitch
import com.muyuchat.core.dsh.contract.DshOverlayAppearance
import com.muyuchat.core.dsh.contract.MascotId
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The app-outside display settings surface.
 *
 * Opened from the DSH floating ball, so it is a dialog over the official page instead of a new
 * navigation destination: the official DSH Web UI keeps owning its own settings pages, and this panel
 * only edits the one thing MCA adds — the always-on mascot window.
 *
 * Every control writes through [DshOverlayUiPort]; the panel keeps no durable state of its own, so a
 * refused change (missing permission, failed image copy) is reported back and re-read from the port.
 * Sliders commit on release instead of on every frame, which keeps the preference write count low.
 */
@Composable
fun DshOverlayPanel(
    port: DshOverlayUiPort,
    onDismiss: () -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                port.importCustomImage(uri)?.let(onNotice)
                refresh()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 660.dp),
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
                        description = "开启后小鲸鱼会常驻系统悬浮窗；DSH 网关与会话在后台保持运行，" +
                            "回到 DSH 也不会重新启动。",
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
                    Text(
                        text = "悬浮窗形象",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    MascotPreview(appearance = appearance, customImageMissing = snapshot.customImageMissing)
                    // Two rows on purpose: six choices in one row clip at 320 dp, and the picker is
                    // the one place where a clipped label would hide a whole character.
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MascotChoice(
                                label = "小鲸鱼",
                                selected = selectedId(appearance) == MascotId.WHALE,
                                onClick = { commit(appearance.copy(mascotId = MascotId.WHALE.id)) }
                            )
                            MascotChoice(
                                label = "鲸鱼娘原图",
                                selected = selectedId(appearance) == MascotId.WHALE_GIRL_ART,
                                onClick = {
                                    commit(appearance.copy(mascotId = MascotId.WHALE_GIRL_ART.id))
                                }
                            )
                            MascotChoice(
                                label = "鲸鱼娘 Q",
                                selected = selectedId(appearance) == MascotId.WHALE_GIRL_Q,
                                onClick = {
                                    commit(appearance.copy(mascotId = MascotId.WHALE_GIRL_Q.id))
                                }
                            )
                            MascotChoice(
                                label = "鲸鱼娘 成熟",
                                selected = selectedId(appearance) == MascotId.WHALE_GIRL_MATURE,
                                onClick = {
                                    commit(
                                        appearance.copy(mascotId = MascotId.WHALE_GIRL_MATURE.id)
                                    )
                                }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MascotChoice(
                                label = "小肥鱼",
                                selected = selectedId(appearance) == MascotId.FATTY_FISH,
                                onClick = { commit(appearance.copy(mascotId = MascotId.FATTY_FISH.id)) }
                            )
                            MascotChoice(
                                label = "小猫",
                                selected = selectedId(appearance) == MascotId.CAT,
                                onClick = { commit(appearance.copy(mascotId = MascotId.CAT.id)) }
                            )
                            MascotChoice(
                                label = "自定义",
                                selected = selectedId(appearance) == MascotId.CUSTOM,
                                onClick = { commit(appearance.copy(mascotId = MascotId.CUSTOM.id)) }
                            )
                        }
                    }
                    if (selectedId(appearance) == MascotId.CUSTOM) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = when {
                                    snapshot.customImageMissing -> "所选图片不可用，将显示小鲸鱼"
                                    appearance.customImagePath != null -> "已使用自定义图片"
                                    else -> "还没有选择图片"
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            McaButton(style = McaButtonStyle.Text, onClick = {
                                imagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }) {
                                Text(if (appearance.customImagePath == null) "选择图片" else "更换图片")
                            }
                        }
                    }
                    LabeledSlider(
                        label = "尺寸 ${appearance.markSizeDp} dp",
                        value = appearance.markSizeDp.toFloat(),
                        valueRange = DshOverlayAppearance.MIN_MARK_SIZE_DP.toFloat()..
                            DshOverlayAppearance.MAX_MARK_SIZE_DP.toFloat(),
                        steps = DshOverlayAppearance.MAX_MARK_SIZE_DP - DshOverlayAppearance.MIN_MARK_SIZE_DP - 1,
                        onValueChange = {
                            appearance = appearance.copy(markSizeDp = it.roundToInt()).normalized()
                        },
                        onValueChangeFinished = { commit(appearance) }
                    )
                    LabeledSlider(
                        label = "不透明度 ${(appearance.opacity * 100).roundToInt()}%",
                        value = appearance.opacity,
                        valueRange = DshOverlayAppearance.MIN_OPACITY..1f,
                        steps = 12,
                        onValueChange = { appearance = appearance.copy(opacity = it).normalized() },
                        onValueChangeFinished = { commit(appearance) }
                    )
                    LabeledSlider(
                        label = if (appearance.bubbleLines == 0) {
                            "回复气泡：关闭"
                        } else {
                            "回复气泡最多 ${appearance.bubbleLines} 行"
                        },
                        value = appearance.bubbleLines.toFloat(),
                        valueRange = 0f..DshOverlayAppearance.MAX_BUBBLE_LINES.toFloat(),
                        steps = DshOverlayAppearance.MAX_BUBBLE_LINES - 1,
                        onValueChange = {
                            appearance = appearance.copy(bubbleLines = it.roundToInt()).normalized()
                        },
                        onValueChangeFinished = { commit(appearance) }
                    )
                    ToggleRow(
                        title = "流式上屏",
                        description = "DSH 生成时把正在写的内容实时显示在悬浮窗旁（最多三行）；关闭后只在回答结束时弹一次气泡。",
                        checked = snapshot.streamingEnabled,
                        onCheckedChange = { enabled ->
                            port.setStreamingEnabled(enabled)?.let(onNotice)
                            refresh()
                        }
                    )
                    ToggleRow(
                        title = "回复时弹出气泡",
                        description = "DSH 一轮回答结束后，在小鲸鱼旁显示开头几句；关闭后仍会用动效表示正在生成。",
                        checked = appearance.notifyReplies,
                        onCheckedChange = { commit(appearance.copy(notifyReplies = it)) }
                    )
                    ToggleRow(
                        title = "允许拖动",
                        description = "关闭后小鲸鱼固定在当前位置，避免误触移动。长按仍可查看运行状态。",
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
private fun MascotPreview(appearance: DshOverlayAppearance, customImageMissing: Boolean) {
    val mascot = mascotOf(appearance)
    if (mascot == McaMascot.CUSTOM && customImageMissing) {
        McaMascotMark(mascot = McaMascot.WHALE, size = 64.dp, modifier = Modifier.padding(4.dp))
    } else if (mascot == McaMascot.CUSTOM) {
        // The bitmap itself is painted by the overlay owner; the picker only states which mode is
        // active, so no second image decoder is introduced in the feature module.
        Icon(
            imageVector = McaIcons.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(12.dp).size(40.dp)
        )
    } else {
        McaMascotMark(mascot = mascot, size = 64.dp, modifier = Modifier.padding(4.dp))
    }
}

@Composable
private fun MascotChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    McaChip(selected = selected, onClick = onClick, label = { Text(label) })
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

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        McaSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps.coerceAtLeast(0),
            onValueChangeFinished = onValueChangeFinished,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun selectedId(appearance: DshOverlayAppearance): MascotId =
    MascotId.fromId(appearance.mascotId)

private fun mascotOf(appearance: DshOverlayAppearance): McaMascot = when (selectedId(appearance)) {
    MascotId.WHALE -> McaMascot.WHALE
    MascotId.WHALE_GIRL_ART -> McaMascot.WHALE_GIRL_ART
    MascotId.WHALE_GIRL_Q -> McaMascot.WHALE_GIRL_Q
    MascotId.WHALE_GIRL_MATURE -> McaMascot.WHALE_GIRL_MATURE
    MascotId.FATTY_FISH -> McaMascot.FATTY_FISH
    MascotId.CAT -> McaMascot.CAT
    MascotId.CUSTOM -> McaMascot.CUSTOM
}

/**
 * Opens the system "display over other apps" screen for this package. Both the per-app deep link and
 * the list fallback are attempted; a device without either leaves the panel's explanation in place
 * rather than pretending the grant was requested.
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
