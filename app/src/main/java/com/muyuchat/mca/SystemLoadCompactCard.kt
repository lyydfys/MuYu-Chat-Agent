package com.muyuchat.mca

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.muyuchat.core.telemetry.NativeAcceleratorEvidence
import com.muyuchat.core.telemetry.SystemLoadReader
import com.muyuchat.core.telemetry.SystemLoadSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * A deliberately compact, evidence-based resource indicator for the chat surface.
 *
 * The reader is sampled at most once per second and only while the activity is resumed and
 * the chat surface is visible. GPU/NPU percentages are shown only when a trustworthy counter
 * exists; native execution evidence is reported as "已执行" rather than being converted into
 * a made-up utilization percentage.
 */
@Composable
internal fun SystemLoadCompactCard(
    visible: Boolean,
    nativeStatsJson: String,
    modifier: Modifier = Modifier
) {
    if (!visible) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val reader = remember(context) { SystemLoadReader(context.applicationContext) }
    var snapshot by remember { mutableStateOf(SystemLoadSnapshot()) }

    LaunchedEffect(reader, lifecycleOwner, visible) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                snapshot = withContext(Dispatchers.IO) { reader.read() }
                delay(1_000L)
            }
        }
    }

    val nativeGpuEvidence = remember(nativeStatsJson) {
        NativeAcceleratorEvidence.fromNativeStats(
            nativeStatsJson,
            NativeAcceleratorEvidence.Accelerator.GPU
        )
    }
    val nativeNpuEvidence = remember(nativeStatsJson) {
        NativeAcceleratorEvidence.fromNativeStats(
            nativeStatsJson,
            NativeAcceleratorEvidence.Accelerator.NPU
        )
    }
    val gpuLabel = acceleratorLabel(
        percent = snapshot.gpuPercent,
        executionObserved = snapshot.gpuExecutionObserved || nativeGpuEvidence.executionObserved
    )
    val npuLabel = acceleratorLabel(
        percent = snapshot.npuPercent,
        executionObserved = snapshot.npuExecutionObserved || nativeNpuEvidence.executionObserved
    )
    val memoryLabel = if (snapshot.memoryTotalBytes > 0L) {
        "${formatBytes(snapshot.memoryUsedBytes)}/${formatBytes(snapshot.memoryTotalBytes)}" +
            snapshot.processPssKb.takeIf { it > 0 }?.let { " · PSS ${formatBytes(it.toLong() * 1024L)}" }.orEmpty()
    } else {
        "不可用"
    }

    Card(
        modifier = modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth(),
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(PaddingValues(horizontal = 10.dp, vertical = 5.dp)),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricText("CPU ${snapshot.systemCpuPercent?.let { "$it%" } ?: "不可用"}")
            MetricText("进程 ${snapshot.processCpuPercent?.let { "$it%" } ?: "不可用"}")
            MetricText("内存 $memoryLabel")
            MetricText("GPU $gpuLabel")
            MetricText("NPU $npuLabel")
        }
    }
}

@Composable
private fun MetricText(value: String) {
    Text(
        text = value,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun acceleratorLabel(percent: Int?, executionObserved: Boolean): String = when {
    percent != null -> "$percent%"
    executionObserved -> "已执行"
    else -> "不可用"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    val rendered = if (value >= 10.0 || index == 0) {
        "%.0f".format(java.util.Locale.ROOT, value)
    } else {
        "%.1f".format(java.util.Locale.ROOT, value)
    }
    return "$rendered ${units[index]}"
}
