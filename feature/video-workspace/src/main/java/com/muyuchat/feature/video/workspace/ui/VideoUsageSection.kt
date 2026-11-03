package com.muyuchat.feature.video.workspace.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaHairline
import com.muyuchat.core.designsystem.McaIconSize
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.McaShape

/**
 * Onboarding surfaces of the video page: a collapsed usage guide and a compact list of example
 * prompts that write into the draft's text node.
 *
 * Both reuse the page's existing card language (surface fill, hairline border, 12dp in-card radius
 * from [McaShape.CardAction]) and the shared type scale, so nothing here introduces a second visual
 * system. Both start collapsed and keep their expanded state through [rememberSaveable]: two 56dp
 * headers keep the first screen on the engine state and the model card, and one tap opens either
 * list. The collapsed example header still states how many prompts are inside.
 */

@Composable
internal fun VideoUsageGuideCard(
    modifier: Modifier = Modifier,
    /**
     * First-composition default only; a later fold/unfold keeps the user's own expansion state.
     * The phone passes nothing, so it keeps the collapsed first screen documented above.
     */
    initiallyExpanded: Boolean = false
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    UsageCard(modifier = modifier.testTag("video.usage_guide")) {
        Column(Modifier.animateContentSize(McaMotion.sizeChangePx)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "收起使用教程" else "展开使用教程",
                        onClick = { expanded = !expanded },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("video.usage_guide_toggle")
                    .semantics {
                        contentDescription = "使用教程，${VideoUsageCopy.guideSteps.size} 步"
                        stateDescription = if (expanded) "已展开" else "已折叠"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "使用教程",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (expanded) {
                            "按顺序完成一次生成，随时可以收起"
                        } else {
                            "${VideoUsageCopy.guideSteps.size} 步完成第一个视频"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (expanded) McaIcons.ExpandLess else McaIcons.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(McaIconSize.S),
                )
            }
            if (expanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    thickness = McaHairline.Width,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    VideoUsageCopy.guideSteps.forEachIndexed { index, step ->
                        GuideStepRow(index = index + 1, step = step)
                    }
                }
            }
        }
    }
}

@Composable
internal fun VideoPromptExamplesCard(
    onUsePrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * First-composition default only. A wide window passes true for the same reason as the guide;
     * the phone keeps the collapsed default, so its first screen still ends at the model card.
     */
    initiallyExpanded: Boolean = false,
) {
    // Collapsed by default for the same reason as the guide: the page stays on the engine state
    // and the model card until the user asks for examples. The header names the count, so the
    // content is discoverable without opening it first.
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    UsageCard(modifier = modifier.testTag("video.prompt_examples")) {
        Column(Modifier.animateContentSize(McaMotion.sizeChangePx)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "收起示范提示词" else "展开示范提示词",
                        onClick = { expanded = !expanded },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("video.prompt_examples_toggle")
                    .semantics {
                        contentDescription =
                            "示范提示词，${VideoUsageCopy.promptExamples.size} 条示例"
                        stateDescription = if (expanded) "已展开" else "已折叠"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "示范提示词",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (expanded) {
                            "点任意一条就填入提示词"
                        } else {
                            "${VideoUsageCopy.promptExamples.size} 条可直接填入的示例"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (expanded) McaIcons.ExpandLess else McaIcons.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(McaIconSize.S),
                )
            }
            if (expanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    thickness = McaHairline.Width,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )
                Text(
                    VideoUsageCopy.PROMPT_HINT,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
                )
                Spacer(Modifier.height(4.dp))
                VideoUsageCopy.promptExamples.forEachIndexed { index, example ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            thickness = McaHairline.Width,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        )
                    }
                    PromptExampleRow(example = example, onUsePrompt = onUsePrompt)
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

/** One surface for every onboarding card so fill, edge and radius cannot drift apart. */
@Composable
private fun UsageCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = McaShape.CardAction,
        border = BorderStroke(McaHairline.Width, McaHairline.default()),
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun GuideStepRow(index: Int, step: VideoUsageCopy.GuideStep) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            index.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                step.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                step.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One tappable example. The whole row is the target (>=48dp for the two text lines plus padding);
 * the merged semantics read the title, the prompt and the trailing "填入" action label.
 */
@Composable
private fun PromptExampleRow(
    example: VideoUsageCopy.PromptExample,
    onUsePrompt: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = "填入提示词 ${example.title}",
                onClick = { onUsePrompt(example.prompt) },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("video.prompt_example"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                example.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                example.prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "填入",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
