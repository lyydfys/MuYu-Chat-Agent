@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.chat

import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaIconButton
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIconSize

@Composable
internal fun ImageEditorScaffold(
    title: String,
    subtitle: String,
    saving: Boolean,
    confirmEnabled: Boolean,
    confirmLabel: String,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    controls: @Composable () -> Unit,
    error: String?,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    McaIconButton(
                        contentDescription = "返回",
                        onClick = onBack,
                        enabled = !saving,
                        modifier = Modifier.size(48.dp),
                        glyphSize = McaIconSize.L
                    ) {
                        Icon(McaIcons.Back, contentDescription = null)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                    McaButton(style = McaButtonStyle.Text,
                        onClick = onConfirm,
                        // A save in flight must not be re-submittable: the spinner describes the
                        // operation, and the button stays disabled until the caller clears `saving`.
                        enabled = confirmEnabled && !saving,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("保存中")
                        } else {
                            Text(confirmLabel)
                        }
                    }
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) { content() }
        Surface(tonalElevation = 2.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                controls()
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
internal fun CropOverlay(bitmap: Bitmap, canvasSize: IntSize, crop: NormalizedImageRect) {
    Canvas(Modifier.fillMaxSize()) {
        if (canvasSize == IntSize.Zero) return@Canvas
        val transform = ImageFitTransform.create(
            ImageEditingSize(size.width.toDouble(), size.height.toDouble()),
            bitmap.width,
            bitmap.height
        )
        val first = transform.normalizedToView(NormalizedImagePoint(crop.left, crop.top))
        val second = transform.normalizedToView(NormalizedImagePoint(crop.right, crop.bottom))
        val left = first.x.toFloat()
        val top = first.y.toFloat()
        val right = second.x.toFloat()
        val bottom = second.y.toFloat()
        val shade = Color.Black.copy(alpha = 0.58f)
        drawRect(shade, Offset(0f, 0f), Size(size.width, top.coerceAtLeast(0f)))
        drawRect(shade, Offset(0f, bottom), Size(size.width, (size.height - bottom).coerceAtLeast(0f)))
        drawRect(shade, Offset(0f, top), Size(left.coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)))
        drawRect(shade, Offset(right, top), Size((size.width - right).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f)))
        drawRect(
            color = Color.White,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = Stroke(width = 2.dp.toPx())
        )
        val gridColor = Color.White.copy(alpha = 0.55f)
        repeat(2) { index ->
            val fraction = (index + 1) / 3f
            val x = left + (right - left) * fraction
            val y = top + (bottom - top) * fraction
            drawLine(gridColor, Offset(x, top), Offset(x, bottom), 1.dp.toPx())
            drawLine(gridColor, Offset(left, y), Offset(right, y), 1.dp.toPx())
        }
    }
}
