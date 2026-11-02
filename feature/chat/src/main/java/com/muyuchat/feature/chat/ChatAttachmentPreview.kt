package com.muyuchat.feature.chat
import com.muyuchat.core.designsystem.McaIcons

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import com.muyuchat.core.designsystem.McaIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIconSize

// P9/S17：从 ChatChromeScreens.kt 等值移出的消息气泡/参数弹窗/助手块簇。
// 原样逐字迁移，未做行为改动；入口原即 internal，双向扫描按协议处理。
@Composable
// P9/S13: private→internal（跨区入口：迁移的输入区复合区引用）
internal fun AttachmentPreview(
    input: String,
    visionCapabilityLabel: String,
    visionCapabilityDetail: String,
    visionCapabilityReady: Boolean,
    onRemove: () -> Unit
) {
    val name = remember(input) { extractAttachmentName(input) } ?: return
    val isImageAttachment = remember(input) { hasImageAttachmentMarker(input) }
    val imageAttachmentCount = remember(input) { chatImageAttachmentMarkerCount(input) }
    val removeInteraction = remember { MutableInteractionSource() }
    val statusColor = if (visionCapabilityReady) McaPrimaryBlue else MaterialTheme.colorScheme.error
    val containerColor = if (isImageAttachment) {
        if (visionCapabilityReady) McaInputIconSurface.copy(alpha = 0.92f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.44f)
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    }
    val contentColor = if (isImageAttachment) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        color = containerColor,
        shape = RoundedCornerShape(16.dp),
        border = if (isImageAttachment) BorderStroke(1.dp, statusColor.copy(alpha = 0.22f)) else null
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .heightIn(min = 30.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isImageAttachment) McaIcons.Image else McaIcons.Folder,
                        contentDescription = null,
                        tint = if (isImageAttachment) statusColor else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isImageAttachment) "识图" else "文件",
                        color = if (isImageAttachment) statusColor else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isImageAttachment && imageAttachmentCount > 1) {
                            "$name · 共 $imageAttachmentCount 张图片"
                        } else {
                            name
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                if (isImageAttachment) {
                    Text(
                        text = "$visionCapabilityLabel · $visionCapabilityDetail",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                    extractAttachmentMeta(input)?.let { meta ->
                        Text(
                            text = meta,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            McaIconButton(
                contentDescription = "移除附件",
                onClick = onRemove,
                size = 30.dp,
                glyphSize = McaIconSize.Xs,
                interactionSource = removeInteraction
            ) {
                Icon(
                    McaIcons.Close,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.74f)
                )
            }
        }
    }
}
