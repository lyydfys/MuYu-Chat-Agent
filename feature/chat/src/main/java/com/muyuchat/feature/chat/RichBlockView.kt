package com.muyuchat.feature.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaHairline
import com.muyuchat.core.designsystem.McaIconSize
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.McaShape

/**
 * 安全富文本块的 Compose 渲染。
 *
 * 只消费 [RichBlockSanitizer] 产出的纯数据模型：没有 WebView、没有 HTML 解析、没有脚本、没有
 * 样式/链接。排版沿用聊天页现有体系（surface 卡 + hairline 边 + bodyMedium/bodySmall），
 * 未知标签以可展开的小字块保留原文，绝不静默丢内容。
 */
@Composable
internal fun RichBlockView(
    blocks: List<RichBlock>,
    truncated: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().testTag("rich.blocks"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is RichBlock.Section -> RichSection(block = block, key = index)
                is RichBlock.Text -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("rich.text"),
                )

                is RichBlock.KeyValue -> RichKeyValueRow(block)
                is RichBlock.Table -> RichTable(block)
                is RichBlock.Unknown -> RichUnknownBlock(block)
            }
        }
        if (truncated) {
            Text(
                "内容过长，已截断显示",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.testTag("rich.truncated"),
            )
        }
    }
}

@Composable
private fun RichSection(block: RichBlock.Section, key: Int) {
    var expanded by rememberSaveable("rich.section.$key.${block.title}") {
        mutableStateOf(block.initiallyExpanded)
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = McaShape.CardAction,
        border = BorderStroke(McaHairline.Width, McaHairline.default()),
        modifier = Modifier.fillMaxWidth().testTag("rich.section"),
    ) {
        Column(
            Modifier
                .animateContentSize(McaMotion.sizeChangePx)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "收起${block.title}" else "展开${block.title}",
                        onClick = { expanded = !expanded },
                    )
                    .semantics {
                        contentDescription = block.title
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    block.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) McaIcons.ExpandLess else McaIcons.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(McaIconSize.S),
                )
            }
            if (expanded && block.body.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                RichBlockView(block.body)
            }
        }
    }
}

@Composable
private fun RichKeyValueRow(block: RichBlock.KeyValue) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("rich.key_value"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            block.key,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 64.dp, max = 128.dp),
        )
        Text(
            block.value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RichTable(block: RichBlock.Table) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("rich.table"),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        block.rows.forEachIndexed { index, row ->
            if (index == 1) {
                HorizontalDivider(
                    thickness = McaHairline.Width,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                row.forEach { cell ->
                    Text(
                        cell,
                        style = if (index == 0) {
                            MaterialTheme.typography.labelMedium
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        fontWeight = if (index == 0) FontWeight.Medium else null,
                        color = if (index == 0) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.widthIn(min = 72.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RichUnknownBlock(block: RichBlock.Unknown) {
    if (block.tagName == "hr") {
        HorizontalDivider(
            thickness = McaHairline.Width,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        )
        return
    }
    var expanded by rememberSaveable("rich.unknown.${block.tagName}") { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().testTag("rich.unknown"),
    ) {
        Column(
            Modifier
                .animateContentSize(McaMotion.sizeChangePx)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "收起原始文本" else "展开原始文本",
                        onClick = { expanded = !expanded },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "未识别块 · ${block.tagName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) McaIcons.ExpandLess else McaIcons.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(McaIconSize.Xs),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    block.rawText.ifBlank { "（空）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
