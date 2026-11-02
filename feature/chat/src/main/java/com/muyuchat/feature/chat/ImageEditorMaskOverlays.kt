@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.chat
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaChip
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaIconButton

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import com.muyuchat.core.designsystem.McaDialog
import com.muyuchat.core.designsystem.McaChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.muyuchat.core.designsystem.McaIconSize

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
    McaDialog(
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
                        McaChip(
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
                    McaIconButton(
                        contentDescription = "光标向上移动",
                        onClick = { onMove(0, -1) },
                        modifier = Modifier.size(48.dp),
                        glyphSize = McaIconSize.L
                    ) { Icon(McaIcons.KeyboardArrowUp, contentDescription = null) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    McaIconButton(
                        contentDescription = "光标向左移动",
                        onClick = { onMove(-1, 0) },
                        modifier = Modifier.size(48.dp),
                        glyphSize = McaIconSize.L
                    ) {
                        Icon(McaIcons.Previous, contentDescription = null)
                    }
                    Spacer(Modifier.size(48.dp))
                    McaIconButton(
                        contentDescription = "光标向右移动",
                        onClick = { onMove(1, 0) },
                        modifier = Modifier.size(48.dp),
                        glyphSize = McaIconSize.L
                    ) {
                        Icon(McaIcons.Forward, contentDescription = null)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    McaIconButton(
                        contentDescription = "光标向下移动",
                        onClick = { onMove(0, 1) },
                        modifier = Modifier.size(48.dp),
                        glyphSize = McaIconSize.L
                    ) { Icon(McaIcons.KeyboardArrowDown, contentDescription = null) }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            McaButton(style = McaButtonStyle.Primary,
                onClick = onApply,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("在当前位置应用") }
        },
        dismissButton = {
            McaButton(style = McaButtonStyle.Text,
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("关闭") }
        }
    )
}
