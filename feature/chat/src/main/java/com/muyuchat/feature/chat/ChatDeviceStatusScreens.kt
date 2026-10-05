package com.muyuchat.feature.chat

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.ActivityManager
import android.os.BatteryManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import kotlinx.coroutines.delay
import java.io.File
import org.json.JSONObject
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// P9/S16：从 ChatChromeScreens.kt 等值移出的状态栏与设备状态簇。
// 原样逐字迁移，未做行为改动；无新增提升（入口原即 internal，双向扫描零跨区）。
@Composable
internal fun ChatStatusBar(
    state: ChatUiState,
    onOpenHistory: () -> Unit,
    onNewConversation: () -> Unit,
    onLoadModel: (String) -> Unit,
    onModelBackendChange: (String, String) -> Unit,
    generationParams: GenerationParams,
    onGenerationParamsChange: (GenerationParams) -> Unit,
    onOpenModels: () -> Unit,
    onReasoningModeChange: (ReasoningMode) -> Unit,
    onCloudReasoningModeLocked: () -> Unit,
    onOpenAppMenu: () -> Unit
) {
    val apiActive = state.selectedModelIsCloud || state.apiEnabled || state.restEnabled
    var modelMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val cacheEvidence = cacheEvidenceLabel(state.stats).takeIf { state.hasReadyLocalModelForHeader() }
    val modelSubtitle = state.modelHeaderSubtitle()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = CircleShape,
            shadowElevation = 6.dp
        ) {
            IconButton(onClick = onOpenHistory) {
                Icon(Icons.Default.Menu, contentDescription = "打开历史", modifier = Modifier.size(24.dp))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(modifier = Modifier.weight(1f)) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = "选择模型"
                        role = SemanticsRole.Button
                    }
                    .clickable {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        modelMenuExpanded = true
                    },
                color = MaterialTheme.colorScheme.surface,
                shape = CircleShape,
                shadowElevation = 5.dp
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    McaLogoMark(size = 24.dp, cornerRadius = 8.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayModelName(state.selectedModelName) ?: "选择模型",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 15.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = modelSubtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        cacheEvidence?.let { evidence ->
                            Text(
                                text = evidence,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                            )
                        }
                    }
                    Surface(
                        color = if (apiActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    ) {
                        Text(
                            text = "API",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = if (apiActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(15.dp)
                    )
                }
            }
            ModelSwitcherDropdown(
                expanded = modelMenuExpanded,
                models = state.localModels,
                selectedModelIsCloud = state.selectedModelIsCloud,
                isGenerating = state.isGenerating,
                reasoningMode = state.reasoningMode,
                onDismiss = { modelMenuExpanded = false },
                onLoadModel = { id ->
                    modelMenuExpanded = false
                    onLoadModel(id)
                },
                onModelBackendChange = onModelBackendChange,
                generationParams = generationParams,
                onGenerationParamsChange = onGenerationParamsChange,
                onReasoningModeChange = onReasoningModeChange,
                onCloudReasoningModeLocked = onCloudReasoningModeLocked,
                onOpenModels = {
                    modelMenuExpanded = false
                    onOpenModels()
                }
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = CircleShape,
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNewConversation, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "新建聊天", modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onOpenAppMenu, modifier = Modifier.size(38.dp)) {
                    McaLogoMark(size = 28.dp, cornerRadius = 10.dp)
                }
            }
        }
    }
}

internal data class ChatDeviceStatusItem(
    val key: String,
    val label: String,
    val value: String,
    val detail: String,
    val active: Boolean,
    val known: Boolean = true,
    val tone: ChatDeviceStatusTone =
        if (known) ChatDeviceStatusTone.NEUTRAL else ChatDeviceStatusTone.UNKNOWN
)

/**
 * How loudly one status row is drawn.
 *
 * Deliberately separate from [ChatDeviceStatusItem.active]: `active` records whether an
 * accelerator is executing *with native proof*, while `tone` decides emphasis.  Keeping
 * them apart is what lets unprobed evidence be drawn as unknown instead of being painted
 * like a reading, so the panel cannot dress configuration up as execution.
 */
internal enum class ChatDeviceStatusTone { NEUTRAL, ACTIVE, WARN, CRITICAL, UNKNOWN }

@Composable
private fun chatDeviceStatusToneColor(tone: ChatDeviceStatusTone): Color = when (tone) {
    ChatDeviceStatusTone.ACTIVE -> McaPrimaryBlue
    ChatDeviceStatusTone.WARN -> McaStatusAmber
    ChatDeviceStatusTone.CRITICAL -> MaterialTheme.colorScheme.error
    ChatDeviceStatusTone.UNKNOWN -> MaterialTheme.colorScheme.outline
    ChatDeviceStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Normalizes a user/native backend label to the accelerator family it names. */
internal fun normalizeChatAcceleratorBackend(raw: String?): String? {
    val value = raw?.trim()?.lowercase().orEmpty()
    if (value.isBlank() || value == "unknown" || value == "unavailable" || value == "none") return null
    return when {
        value == "cpu" || value.contains("cpu") -> "cpu"
        value == "gpu" || value.contains("gpu") || value.contains("opencl") ||
            value.contains("adreno") -> "gpu"
        value == "npu" || value.contains("npu") || value.contains("htp") ||
            value.contains("qnn") || value.contains("qairt") || value.contains("qualcomm") -> "npu"
        else -> value
    }
}

/**
 * Pure projection for one accelerator row.  `supported`, `selectedBackend`,
 * and `actualBackend` are intentionally independent so a configured delegate
 * cannot be presented as an executing delegate without native proof.
 */
internal fun chatAcceleratorStatusItem(
    key: String,
    label: String,
    accelerator: String,
    cloud: Boolean,
    supported: Boolean?,
    selectedBackend: String?,
    actualBackend: String?,
    executionObserved: Boolean,
    unavailableValue: String = "不可用",
    unknownValue: String = "待检测",
    idleValue: String = "未使用",
    extraDetail: String? = null
): ChatDeviceStatusItem {
    val selected = normalizeChatAcceleratorBackend(selectedBackend)
    val actual = normalizeChatAcceleratorBackend(actualBackend)
    val running = !cloud && executionObserved && actual == accelerator
    val selectedHere = !cloud && selected == accelerator
    val value = when {
        cloud -> idleValue
        running -> "运行中"
        supported == false -> unavailableValue
        selectedHere -> "已选择"
        supported == true -> "可用"
        else -> unknownValue
    }
    val supportLabel = when (supported) {
        true -> "支持：可用"
        false -> "支持：不可用"
        null -> "支持：未探测"
    }
    val selectedLabel = when {
        cloud -> "已选：云端"
        selectedHere -> "已选：${selectedBackend?.trim().orEmpty().ifBlank { accelerator }}"
        selected != null -> "已选：${selectedBackend?.trim().orEmpty()}"
        else -> "已选：未指定"
    }
    val actualLabel = when {
        cloud -> "实际：未使用本机"
        running -> "实际：${actualBackend?.trim().orEmpty().ifBlank { accelerator }}（原生证据）"
        actual != null -> "实际：${actualBackend?.trim().orEmpty()}（未确认执行）"
        else -> "实际：未确认"
    }
    // Proof first.  This line is the narrowest thing in the panel, so whichever fragment
    // survives must be the one that says whether execution was actually proven.
    val details = buildList {
        add(actualLabel)
        add(supportLabel)
        add(selectedLabel)
        extraDetail?.takeIf(String::isNotBlank)?.let(::add)
    }.joinToString(" · ")
    val known = cloud || supported != null || selected != null || actual != null || executionObserved
    return ChatDeviceStatusItem(
        key = key,
        label = label,
        value = value,
        detail = details,
        active = running,
        known = known,
        tone = when {
            running -> ChatDeviceStatusTone.ACTIVE
            !known -> ChatDeviceStatusTone.UNKNOWN
            else -> ChatDeviceStatusTone.NEUTRAL
        }
    )
}

internal data class ChatDeviceMemorySample(
    val totalKb: Long,
    val availableKb: Long,
    val lowMemory: Boolean
)

internal data class ChatDeviceTemperatureSample(
    val celsius: Float,
    val source: String
)

/**
 * Absolute ball-size presets.
 *
 * The chosen size is stored as an absolute dp value rather than a fraction of the screen.
 * A screen fraction made the same setting produce 21.6dp on a 360dp phone and 26.9dp on a
 * 448dp one, and let the soft keyboard resize the ball whenever it changed the overlay
 * host's height.  An absolute value means the number the user picks is the size they get,
 * on every device, in every orientation.
 */
internal enum class FloatingDeviceStatusSize(val dp: Float, val label: String) {
    SMALL(28f, "小"),
    MEDIUM(36f, "中"),
    LARGE(44f, "大"),
    EXTRA_LARGE(52f, "特大");

    companion object {
        fun nearest(storedDp: Float): FloatingDeviceStatusSize =
            entries.minByOrNull { kotlin.math.abs(it.dp - storedDp) } ?: LARGE
    }
}

internal const val DEFAULT_FLOATING_DEVICE_STATUS_SIZE_DP = 44f

/** Ceiling on how much of the short side the ball may take on an unusually small host. */
private const val MAX_FLOATING_DEVICE_STATUS_SHORT_SIDE_SHARE = 0.16f
private const val MIN_FLOATING_DEVICE_STATUS_SIZE_DP = 16f

/**
 * Resolves the drawn size from the user's absolute choice.
 *
 * The available area only acts as a safety clamp for very small or split-screen hosts.
 * There is deliberately no fixed lower/upper bound inside the selectable range: the old
 * `coerceIn(18f, 56f)` around a screen fraction made the first 12.5% of the slider dead
 * on a 360dp phone and left the 56dp ceiling unreachable on every phone.
 */
internal fun floatingDeviceStatusBallSize(
    chosenDp: Float,
    availableShortSideDp: Float
): Dp {
    val ceiling = (availableShortSideDp * MAX_FLOATING_DEVICE_STATUS_SHORT_SIDE_SHARE)
        .takeIf { it.isFinite() && it > 0f }
        ?: chosenDp
    return chosenDp.coerceAtMost(ceiling).coerceAtLeast(MIN_FLOATING_DEVICE_STATUS_SIZE_DP).dp
}

/**
 * Content tiers, one per preset.
 *
 * The previous single `ballSize >= 32.dp` branch made the ball's contents reflow all at
 * once part-way through the range.  Each tier now has a designed layout, so growing the
 * ball adds detail progressively instead of jumping.
 */
internal enum class FloatingDeviceStatusBallTier { COMPACT, STANDARD, FULL }

internal fun floatingDeviceStatusBallTier(ballDp: Float): FloatingDeviceStatusBallTier = when {
    ballDp < 40f -> FloatingDeviceStatusBallTier.COMPACT
    ballDp < 48f -> FloatingDeviceStatusBallTier.STANDARD
    else -> FloatingDeviceStatusBallTier.FULL
}

/**
 * Ring weight scales with the ball so the small presets stay legible and the large ones
 * stay elegant.  The old fixed 2.5dp stroke was invisible on a 52dp ball.
 */
internal fun floatingDeviceStatusRingStrokeDp(ballDp: Float): Float =
    (ballDp * 0.085f).coerceIn(2.5f, 4.5f)

/**
 * "4.7 GB" -> "4.7G".  The compact form is what lets the 28dp and 36dp presets render the
 * value inside the ring without clipping; the full form measured ~24dp against ~21.6dp of
 * available width at the old default size.
 */
internal fun compactMemoryLabel(label: String): String =
    label.replace(" ", "").replace("GB", "G").replace("MB", "M")

internal fun chatTemperatureTone(celsius: Float): ChatDeviceStatusTone = when {
    celsius >= 65f -> ChatDeviceStatusTone.CRITICAL
    celsius >= 45f -> ChatDeviceStatusTone.WARN
    else -> ChatDeviceStatusTone.NEUTRAL
}

internal fun chatTemperatureBandLabel(celsius: Float): String = when {
    celsius >= 65f -> "偏热，可能接近降频"
    celsius >= 45f -> "偏热"
    else -> "正常"
}

internal fun memoryUsedFraction(totalKb: Long, availableKb: Long): Float? =
    if (totalKb > 0L) {
        (1f - availableKb.coerceIn(0L, totalKb).toFloat() / totalKb.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }

/**
 * Converts the native runtime evidence already present in [ChatUiState] into
 * concise status rows. Unknown GPU/NPU evidence stays visibly unknown; it is
 * never presented as a device rejection.
 */
internal fun ChatUiState.deviceStatusItems(
    memorySample: ChatDeviceMemorySample? = null,
    temperatureSample: ChatDeviceTemperatureSample? = null
): List<ChatDeviceStatusItem> {
    val backend = stats.backend.trim().lowercase()
    val selectedLocalModel = localModels.firstOrNull { it.id == selectedModelId }
    val selectedBackend = if (selectedModelIsCloud) {
        null
    } else {
        selectedLocalModel?.selectedBackendId
            ?: runCatching { JSONObject(generationParams.advancedJson.ifBlank { "{}" }) }
                .getOrNull()
                ?.let { root ->
                    root.optString("backend")
                        .ifBlank { root.optString("backend_type") }
                        .takeIf(String::isNotBlank)
                }
    }
    val nativeActualBackend = stats.actualBackend
    val gpuExecutionObserved = stats.hasVerifiedGpuExecution ||
        (stats.backendExecutionObserved &&
            normalizeChatAcceleratorBackend(nativeActualBackend) == "gpu")
    val npuExecutionObserved = stats.npuExecutionObserved ||
        (stats.backendExecutionObserved &&
            normalizeChatAcceleratorBackend(nativeActualBackend) == "npu")
    val gpuActualBackend = if (gpuExecutionObserved) {
        nativeActualBackend?.takeIf(String::isNotBlank) ?: "gpu"
    } else {
        nativeActualBackend
    }
    val npuActualBackend = if (npuExecutionObserved) {
        nativeActualBackend?.takeIf(String::isNotBlank) ?: "npu"
    } else {
        nativeActualBackend
    }
    val gpuExtraDetail = when {
        stats.hasVerifiedGpuExecution && stats.gpuOffloadLayersKnown ->
            "${stats.gpuOffloadLayers} 层已验证"
        stats.hasVerifiedGpuExecution -> "已完成原生执行验证"
        stats.backendExecutionEvidence?.isNotBlank() == true -> stats.backendExecutionEvidence
        else -> null
    }
    val gpuItem = chatAcceleratorStatusItem(
        key = "gpu",
        label = "GPU",
        accelerator = "gpu",
        cloud = selectedModelIsCloud,
        supported = stats.gpuOffloadSupported,
        selectedBackend = selectedBackend,
        actualBackend = gpuActualBackend,
        executionObserved = gpuExecutionObserved,
        extraDetail = gpuExtraDetail
    )
    val npuItem = chatAcceleratorStatusItem(
        key = "npu",
        label = "NPU",
        accelerator = "npu",
        cloud = selectedModelIsCloud,
        supported = stats.npuSupported,
        selectedBackend = selectedBackend,
        actualBackend = npuActualBackend,
        executionObserved = npuExecutionObserved,
        // Without an explicit capability probe or execution proof, NPU is
        // idle/unknown.  A configured LiteRT NPU delegate is not execution.
        unknownValue = "未使用",
        extraDetail = stats.backendExecutionEvidence
    )
    val freshMemory = memorySample?.takeIf { it.totalKb > 0L }
    val memoryTotal = freshMemory?.totalKb ?: stats.totalMemKb
    val memoryAvailable = (freshMemory?.availableKb ?: stats.availMemKb)
        .coerceIn(0L, memoryTotal.coerceAtLeast(0L))
    val memoryUsed = (memoryTotal - memoryAvailable).coerceAtLeast(0L)
    val memoryValue = if (memoryTotal > 0L) {
        "${formatMemoryMb(memoryUsed)} / ${formatMemoryMb(memoryTotal)}"
    } else {
        "待检测"
    }
    val memoryDetail = if (memoryTotal > 0L) {
        "可用 ${formatMemoryMb(memoryAvailable)}"
    } else {
        "系统内存数据尚未返回"
    }
    return listOf(
        ChatDeviceStatusItem(
            key = "cpu",
            label = "CPU",
            value = if (!selectedModelIsCloud && stats.loaded && backend.contains("cpu")) "运行中" else "在线",
            // CPU previously carried an unconditional "系统线程与回退执行" blurb, so the one
            // backend that is always available was the only one with no proof statement.
            detail = when {
                selectedModelIsCloud -> "实际：未使用本机 · 支持：可用"
                !selectedModelIsCloud && stats.loaded && backend.contains("cpu") ->
                    "实际：本机 CPU 执行（原生证据） · 支持：可用"
                else -> "支持：可用 · 实际：未确认"
            },
            active = !selectedModelIsCloud && stats.loaded && backend.contains("cpu"),
            tone = if (!selectedModelIsCloud && stats.loaded && backend.contains("cpu")) {
                ChatDeviceStatusTone.ACTIVE
            } else {
                ChatDeviceStatusTone.NEUTRAL
            }
        ),
        gpuItem,
        npuItem,
        ChatDeviceStatusItem(
            key = "memory",
            label = "内存",
            value = memoryValue,
            detail = memoryDetail,
            active = freshMemory?.lowMemory ?: stats.isLowMemory,
            known = memoryTotal > 0L,
            tone = when {
                memoryTotal <= 0L -> ChatDeviceStatusTone.UNKNOWN
                freshMemory?.lowMemory ?: stats.isLowMemory -> ChatDeviceStatusTone.CRITICAL
                else -> ChatDeviceStatusTone.NEUTRAL
            }
        ),
        ChatDeviceStatusItem(
            key = "temperature",
            label = "温度",
            value = temperatureSample?.let { "%.1f°C".format(java.util.Locale.US, it.celsius) } ?: "不可用",
            detail = temperatureSample?.let {
                "传感器：${it.source} · ${chatTemperatureBandLabel(it.celsius)}"
            } ?: "未检测到可读的系统温度传感器",
            active = temperatureSample?.celsius?.let { it >= 45f } ?: false,
            known = temperatureSample != null,
            tone = temperatureSample?.let { chatTemperatureTone(it.celsius) }
                ?: ChatDeviceStatusTone.UNKNOWN
        )
    )
}

private fun formatMemoryMb(kb: Long): String {
    val mb = kb / 1024.0
    return if (mb >= 1024.0) {
        "%.1f GB".format(java.util.Locale.US, mb / 1024.0)
    } else {
        "%.0f MB".format(java.util.Locale.US, mb)
    }
}

internal fun moveFloatingDeviceStatusFraction(current: Float, deltaPx: Float, travelPx: Float): Float =
    if (travelPx > 0f && travelPx.isFinite()) {
        (current.coerceIn(0f, 1f) + deltaPx / travelPx).coerceIn(0f, 1f)
    } else {
        0f
    }

private fun Context.readChatDeviceMemorySample(): ChatDeviceMemorySample? = runCatching {
    val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return@runCatching null
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    if (info.totalMem <= 0L) null else ChatDeviceMemorySample(
        totalKb = info.totalMem / 1024L,
        availableKb = info.availMem / 1024L,
        lowMemory = info.lowMemory
    )
}.getOrNull()

/** Reads whichever real thermal sensor is readable; zone numbers are never assumed. */
internal fun readChatDeviceTemperatureSample(
    thermalRoot: File = File("/sys/class/thermal")
): ChatDeviceTemperatureSample? = runCatching {
    if (!thermalRoot.isDirectory) return@runCatching null
    val zones = thermalRoot.listFiles { file ->
        file.isDirectory && file.name.startsWith("thermal_zone")
    }.orEmpty()
    val candidates = zones.mapNotNull { zone ->
        val raw = zone.resolve("temp").readText().trim().toFloatOrNull() ?: return@mapNotNull null
        if (!raw.isFinite()) return@mapNotNull null
        val celsius = if (kotlin.math.abs(raw) > 200f) raw / 1000f else raw
        if (!celsius.isFinite() || celsius !in -40f..150f) return@mapNotNull null
        val type = zone.resolve("type").takeIf { it.isFile }?.readText()?.trim().orEmpty()
        val normalized = type.lowercase()
        val priority = when {
            normalized.contains("soc") -> 0
            normalized.contains("cpu") -> 1
            normalized.contains("gpu") -> 2
            normalized.contains("tsens") -> 3
            normalized.isNotBlank() -> 4
            else -> 5
        }
        Triple(priority, celsius, type.ifBlank { zone.name })
    }
    candidates.minWithOrNull(compareBy<Triple<Int, Float, String>> { it.first }.thenBy { it.third })
        ?.let { ChatDeviceTemperatureSample(it.second, it.third) }
}.getOrNull()

/** Battery temperature is the fallback exposed by Android when vendor thermal zones are hidden. */
internal fun batteryTemperatureSample(rawTenthsCelsius: Int): ChatDeviceTemperatureSample? {
    if (rawTenthsCelsius == Int.MIN_VALUE) return null
    val celsius = rawTenthsCelsius / 10f
    return celsius.takeIf { it.isFinite() && it in -40f..100f }
        ?.let { ChatDeviceTemperatureSample(it, "battery") }
}

internal fun readChatDeviceTemperatureSample(
    context: Context,
    thermalRoot: File = File("/sys/class/thermal")
): ChatDeviceTemperatureSample? = readChatDeviceTemperatureSample(thermalRoot) ?: runCatching {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    batteryTemperatureSample(
        battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
    )
}.getOrNull()

@Composable
internal fun ChatDeviceStatusOverlay(
    state: ChatUiState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current.applicationContext
    val preferences = remember(context) {
        context.getSharedPreferences("chat_device_status_overlay", Context.MODE_PRIVATE)
    }
    var horizontalFraction by rememberSaveable {
        mutableStateOf(preferences.getFloat("x_fraction", 1f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f)
    }
    var verticalFraction by rememberSaveable {
        mutableStateOf(preferences.getFloat("y_fraction", 0.70f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.70f)
    }
    var sizeDp by rememberSaveable {
        mutableStateOf(
            preferences.getFloat("ball_size_dp", DEFAULT_FLOATING_DEVICE_STATUS_SIZE_DP)
                .takeIf { it.isFinite() && it > 0f }
                ?: DEFAULT_FLOATING_DEVICE_STATUS_SIZE_DP
        )
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var memorySample by remember { mutableStateOf<ChatDeviceMemorySample?>(null) }
    var temperatureSample by remember { mutableStateOf<ChatDeviceTemperatureSample?>(null) }
    var lastSampleAtMs by remember { mutableStateOf(0L) }
    var clockMs by remember { mutableStateOf(0L) }
    LaunchedEffect(context) {
        while (true) {
            memorySample = context.readChatDeviceMemorySample()
            temperatureSample = readChatDeviceTemperatureSample(context)
            lastSampleAtMs = System.currentTimeMillis()
            delay(5_000L)
        }
    }
    // Only ticks while the panel is open, and only the panel reads this clock, so the
    // closed ball never re-composes.
    LaunchedEffect(expanded) {
        if (!expanded) return@LaunchedEffect
        clockMs = System.currentTimeMillis()
        while (true) {
            clockMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val items = state.deviceStatusItems(memorySample, temperatureSample)
    val memoryItem = items.first { it.key == "memory" }
    val memoryTotalKb = memorySample?.totalKb?.takeIf { it > 0L } ?: state.stats.totalMemKb
    val memoryAvailableKb = (memorySample?.availableKb ?: state.stats.availMemKb)
        .coerceIn(0L, memoryTotalKb.coerceAtLeast(0L))
    val usedFraction = memoryUsedFraction(memoryTotalKb, memoryAvailableKb)
    val ballMemoryLabel = if (memoryTotalKb > 0L) formatMemoryMb(memoryAvailableKb) else "--"
    val ballCompactMemoryLabel = compactMemoryLabel(ballMemoryLabel)
    val hasActive = items.any { it.key != "memory" && it.active }
    val ringTrackColor = MaterialTheme.colorScheme.surfaceVariant
    // The ring and the bar plot *used* memory, so they stay blue in normal conditions and
    // only take a warning tone when the reading is actually remarkable.
    val memoryAccent = when (memoryItem.tone) {
        ChatDeviceStatusTone.WARN -> McaStatusAmber
        ChatDeviceStatusTone.CRITICAL -> MaterialTheme.colorScheme.error
        else -> McaPrimaryBlue
    }
    val memoryHeadlineColor = when (memoryItem.tone) {
        ChatDeviceStatusTone.WARN, ChatDeviceStatusTone.CRITICAL -> chatDeviceStatusToneColor(memoryItem.tone)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val headerTone = when {
        items.any { it.tone == ChatDeviceStatusTone.CRITICAL } -> ChatDeviceStatusTone.CRITICAL
        items.any { it.tone == ChatDeviceStatusTone.WARN } -> ChatDeviceStatusTone.WARN
        items.any { it.tone == ChatDeviceStatusTone.ACTIVE } -> ChatDeviceStatusTone.ACTIVE
        else -> ChatDeviceStatusTone.NEUTRAL
    }
    val freshnessLabel = if (lastSampleAtMs <= 0L || clockMs <= lastSampleAtMs) {
        "刚刚更新"
    } else {
        "更新于 ${(clockMs - lastSampleAtMs) / 1000L}s 前"
    }
    val statusSummary = items.joinToString("，") { "${it.label}${it.value}" }

    BoxWithConstraints(modifier = modifier) {
        val shortSide = minOf(maxWidth, maxHeight)
        val ballSize = floatingDeviceStatusBallSize(sizeDp, shortSide.value)
        val ballTier = floatingDeviceStatusBallTier(ballSize.value)
        val edge = 12.dp
        if (maxWidth < ballSize + edge * 2 || maxHeight < ballSize + edge * 2) return@BoxWithConstraints
        val density = LocalDensity.current
        val horizontalTravel = (maxWidth - ballSize - edge * 2).coerceAtLeast(0.dp)
        val verticalTravel = (maxHeight - ballSize - edge * 2).coerceAtLeast(0.dp)
        val horizontalTravelPx = with(density) { horizontalTravel.toPx() }
        val verticalTravelPx = with(density) { verticalTravel.toPx() }
        val edgePx = with(density) { edge.toPx() }

        fun savePosition() {
            preferences.edit()
                .putFloat("x_fraction", horizontalFraction)
                .putFloat("y_fraction", verticalFraction)
                .apply()
        }

        fun saveSize() {
            preferences.edit().putFloat("ball_size_dp", sizeDp).apply()
        }

        fun moveBy(x: Float, y: Float): Boolean {
            horizontalFraction = (horizontalFraction + x).coerceIn(0f, 1f)
            verticalFraction = (verticalFraction + y).coerceIn(0f, 1f)
            savePosition()
            return true
        }

        if (expanded) {
            val ballX = edge + horizontalTravel * horizontalFraction
            val ballY = edge + verticalTravel * verticalFraction
            val panelWidth = (maxWidth - edge * 2).coerceAtMost(324.dp)
            val spaceAbove = (ballY - edge - 10.dp).coerceAtLeast(0.dp)
            val spaceBelow = (maxHeight - ballY - ballSize - edge - 10.dp).coerceAtLeast(0.dp)
            val panelBelow = spaceBelow >= spaceAbove
            // Height is content-driven with a cap, instead of the old fixed 236dp that
            // clipped the accelerator rows on every device.
            val panelHeight = maxOf(spaceAbove, spaceBelow).coerceAtMost(560.dp)
            val panelX = (ballX + ballSize - panelWidth)
                .coerceIn(edge, (maxWidth - panelWidth - edge).coerceAtLeast(edge))
            val panelY = if (panelBelow) {
                ballY + ballSize + 10.dp
            } else {
                (ballY - panelHeight - 10.dp).coerceAtLeast(edge)
            }

            Surface(
                modifier = Modifier
                    .offset(x = panelX, y = panelY)
                    .width(panelWidth)
                    .heightIn(max = panelHeight)
                    .testTag("chat-device-status-panel"),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.97f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(chatDeviceStatusToneColor(headerTone))
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text(
                                "MCA 设备状态",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                freshnessLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { expanded = false }) {
                            Icon(Icons.Default.Close, contentDescription = "收起设备状态", modifier = Modifier.size(20.dp))
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier = Modifier.testTag("chat-device-status-memory"),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("内存", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (memoryTotalKb > 0L) "可用 $ballMemoryLabel" else "内存待检测",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = memoryHeadlineColor
                            )
                        }
                        usedFraction?.let { fraction ->
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = memoryAccent,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                        // The bar plots *used* memory, so it is captioned in used terms.
                        // Labelling the row "可用" directly above a 68%-filled bar made the
                        // two read as opposites.
                        Text(
                            if (memoryTotalKb > 0L) {
                                "已用 ${formatMemoryMb((memoryTotalKb - memoryAvailableKb).coerceAtLeast(0L))} / 共 ${formatMemoryMb(memoryTotalKb)}"
                            } else {
                                "系统内存数据尚未返回"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    items.filter { it.key != "memory" }.forEach { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chat-device-status-${item.key}"),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(6.dp).clip(CircleShape)
                                        .background(chatDeviceStatusToneColor(item.tone))
                                )
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    item.value,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = chatDeviceStatusToneColor(item.tone),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            // Full-width rows give the evidence line roughly 296dp instead of
                            // the ~135dp a two-column cell allowed, so 支持/已选/实际 is no
                            // longer guaranteed to collapse into an ellipsis.
                            Text(
                                item.detail,
                                modifier = Modifier.padding(start = 13.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // The size control used to be a continuous screen-fraction slider sitting
                    // in the middle of the readout, with a dead first eighth on 360dp phones.
                    // It is now a labelled absolute-dp preset row at the bottom of the panel.
                    Column(
                        modifier = Modifier.testTag("chat-device-status-size"),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("悬浮球大小", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Text("${ballSize.value.toInt()} dp", style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FloatingDeviceStatusSize.entries.forEach { preset ->
                                val selected = preset == FloatingDeviceStatusSize.nearest(sizeDp)
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(40.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .clickable {
                                            sizeDp = preset.dp
                                            saveSize()
                                        },
                                    shape = RoundedCornerShape(11.dp),
                                    color = if (selected) McaPrimaryBlue else MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(
                                        1.dp,
                                        if (selected) McaPrimaryBlue else MaterialTheme.colorScheme.outlineVariant
                                    )
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            "${preset.label} ${preset.dp.toInt()}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                // Read drag position during placement so pointer updates do not recompose the overlay.
                .offset {
                    IntOffset(
                        (edgePx + horizontalTravelPx * horizontalFraction).roundToInt(),
                        (edgePx + verticalTravelPx * verticalFraction).roundToInt()
                    )
                }
                .size(ballSize)
                // The open panel already shows these numbers, so the ball steps back
                // instead of competing with it, while staying usable as the drag anchor.
                .alpha(if (expanded) 0.62f else 1f)
                .testTag("chat-device-status-ball")
                .semantics(mergeDescendants = true) {
                    contentDescription = "MCA 设备状态，可用内存 $ballMemoryLabel。点击${if (expanded) "收起" else "展开"}；可拖动位置。$statusSummary"
                    role = SemanticsRole.Button
                    stateDescription = if (expanded) "已展开" else "已收起"
                    customActions = listOf(
                        CustomAccessibilityAction("向左移动悬浮球") { moveBy(-0.15f, 0f) },
                        CustomAccessibilityAction("向右移动悬浮球") { moveBy(0.15f, 0f) },
                        CustomAccessibilityAction("向上移动悬浮球") { moveBy(0f, -0.15f) },
                        CustomAccessibilityAction("向下移动悬浮球") { moveBy(0f, 0.15f) }
                    )
                }
                .pointerInput(horizontalTravelPx, verticalTravelPx, ballSize) {
                    // Keep tap and drag in one pointer scope.  A sibling clickable modifier can
                    // consume the initial down event before detectDragGestures reaches touch
                    // slop, which made the ball feel immovable on some Compose/MIUI builds.
                    // This state machine only toggles on a real tap and consumes movement after
                    // a small slop, so every pixel of a drag updates the persisted position.
                    val touchSlopPx = with(density) { 8.dp.toPx() }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var dragged = false
                        var distance = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (change.changedToUp()) {
                                change.consume()
                                if (!dragged) expanded = !expanded
                                savePosition()
                                break
                            }
                            if (!change.pressed) {
                                savePosition()
                                break
                            }
                            val delta = change.positionChange()
                            if (delta != Offset.Zero) {
                                distance += delta.getDistance()
                                if (!dragged && distance >= touchSlopPx) {
                                    dragged = true
                                    // A drag should never leave the details panel open over the
                                    // moving ball.
                                    expanded = false
                                }
                                if (dragged) {
                                    change.consume()
                                    horizontalFraction = moveFloatingDeviceStatusFraction(
                                        horizontalFraction, delta.x, horizontalTravelPx
                                    )
                                    verticalFraction = moveFloatingDeviceStatusFraction(
                                        verticalFraction, delta.y, verticalTravelPx
                                    )
                                }
                            }
                        }
                    }
                },
            color = MaterialTheme.colorScheme.surface,
            shape = CircleShape,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Box(contentAlignment = Alignment.Center) {
                val ringStroke = floatingDeviceStatusRingStrokeDp(ballSize.value).dp
                usedFraction?.let { fraction ->
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val inset = ringStroke.toPx() / 2f + 1.dp.toPx()
                        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                        drawArc(
                            color = ringTrackColor,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = ringStroke.toPx(), cap = StrokeCap.Round)
                        )
                        drawArc(
                            color = memoryAccent,
                            startAngle = -90f,
                            sweepAngle = fraction * 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = ringStroke.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
                // Designed per preset instead of one hard `ballSize >= 32.dp` branch, so
                // resizing adds detail progressively rather than reflowing mid-range.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when (ballTier) {
                        FloatingDeviceStatusBallTier.FULL -> {
                            McaLogoMark(size = (ballSize * 0.30f).coerceIn(14.dp, 22.dp), cornerRadius = 6.dp)
                            Text(if (memoryTotalKb > 0L) "可用" else "内存",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(ballMemoryLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp),
                                fontWeight = FontWeight.SemiBold, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                        FloatingDeviceStatusBallTier.STANDARD -> {
                            McaLogoMark(size = (ballSize * 0.28f).coerceIn(12.dp, 18.dp), cornerRadius = 5.dp)
                            Text(ballCompactMemoryLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 10.sp),
                                fontWeight = FontWeight.SemiBold, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                        FloatingDeviceStatusBallTier.COMPACT -> {
                            // The compact label is what keeps this readable: the full
                            // "4.7 GB" measured wider than the whole ball at 28dp.
                            Text(ballCompactMemoryLabel,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, lineHeight = 9.sp),
                                fontWeight = FontWeight.SemiBold, maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                if (hasActive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 7.dp, end = 7.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(McaPrimaryBlue)
                    )
                }
            }
        }
    }
}
