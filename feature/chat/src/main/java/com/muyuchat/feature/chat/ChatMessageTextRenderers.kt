package com.muyuchat.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

// P9/S9：从 ChatChromeScreens.kt 尾部等值移出的分页纯文本与角色 HTML 块渲染器。
// 原样逐字迁移，未做行为改动；跨区入口 @Composable 按协议提升为 internal。
@Composable
// P9/S9: private→internal（跨文件被 ChatChromeScreens 复合区引用的渲染器入口）
internal fun PagedPlainMessageText(
    content: String,
    color: Color,
    style: TextStyle
) {
    var visibleCharacters by rememberSaveable { mutableStateOf(MESSAGE_RENDER_PAGE_CHARS) }
    val visibleContent = remember(content, visibleCharacters) {
        content.safePrefix(visibleCharacters)
    }
    val displayContent = remember(visibleContent) { wrapForDisplay(visibleContent) }
    Column {
        Text(
            text = displayContent,
            color = color,
            style = style,
            softWrap = true
        )
        if (visibleContent.length < content.length) {
            TextButton(
                onClick = {
                    visibleCharacters = (visibleCharacters + MESSAGE_RENDER_PAGE_CHARS)
                        .coerceAtMost(content.length)
                }
            ) {
                Text("显示更多（${visibleContent.length}/${content.length}）")
            }
        }
    }
}

@Composable
// P9/S9: private→internal（跨文件被 ChatChromeScreens 复合区引用的渲染器入口）
internal fun CharacterHtmlBlocksContent(blocks: List<CharacterHtmlBlock>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is CharacterHtmlBlock.Text -> {
                    if (block.content.isNotBlank()) {
                        PagedAssistantRichText(block.content)
                    }
                }
                is CharacterHtmlBlock.Details -> CharacterHtmlDetails(
                    block = block,
                    key = index
                )
            }
        }
    }
}

@Composable
private fun CharacterHtmlDetails(
    block: CharacterHtmlBlock.Details,
    key: Int
) {
    var expanded by rememberSaveable(key, block.title) { mutableStateOf(block.initiallyOpen) }
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = colors.surfaceVariant.copy(alpha = 0.28f),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics(mergeDescendants = true) {
                        stateDescription = if (expanded) "已展开" else "已收起"
                    }
                    .clickable(
                        role = SemanticsRole.Button,
                        onClickLabel = if (expanded) "收起状态栏" else "展开状态栏"
                    ) { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = block.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起状态栏" else "展开状态栏",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(100)) + expandVertically(tween(160)),
                exit = fadeOut(tween(80)) + shrinkVertically(tween(120))
            ) {
                Column {
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.55f))
                    if (block.content.isNotBlank()) {
                        PagedAssistantRichText(
                            content = block.content,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}
