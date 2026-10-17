@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun PositionedMaskDrawingDialog(
    cursor: NormalizedImagePoint,
    mode: ImageMaskStrokeMode,
    radius: Double,
    stepFraction: Double,
    error: String?,
    onStepFractionChange: (Double) -> Unit,
    onMove: (horizontalDirection: Int, verticalDirection: Int) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定位绘制") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "位置 X ${(cursor.x * 100).roundToInt()}% · Y ${(cursor.y * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${if (mode == ImageMaskStrokeMode.BRUSH) "画笔（白色重绘）" else "橡皮（黑色保留）"}" +
                        " · 大小 ${(radius * 100).roundToInt()}% 短边",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.05 to "步长 5%", 0.10 to "步长 10%").forEach { (step, label) ->
                        FilterChip(
                            selected = stepFraction == step,
                            onClick = { onStepFractionChange(step) },
                            label = { Text(label) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(
                        onClick = { onMove(0, -1) },
                        modifier = Modifier.size(48.dp)
                    ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "光标向上移动") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onMove(-1, 0) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "光标向左移动")
                    }
                    Spacer(Modifier.size(48.dp))
                    IconButton(
                        onClick = { onMove(1, 0) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "光标向右移动")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(
                        onClick = { onMove(0, 1) },
                        modifier = Modifier.size(48.dp)
                    ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "光标向下移动") }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onApply,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("在当前位置应用") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("关闭") }
        }
    )
}
