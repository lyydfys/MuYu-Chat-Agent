package com.muyuchat.core.designsystem.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonSize
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaIconPlate
import com.muyuchat.core.designsystem.McaIconSize
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaPlateSize
import com.muyuchat.core.designsystem.McaShape

/** Horizontal page inset. One value, so every onboarding page aligns to the same text column. */
private val PageInset = 24.dp

/** Gap between the progress row, the copy block and the content slot. */
private val SectionGap = 20.dp

/** Height of the content area's minimum, so a short page does not jump when the action bar settles. */
private val MinContentHeight = 120.dp

/**
 * The one frame every first-run page sits in: progress row, title, subtitle, a content slot and the
 * action bar.
 *
 * Stateless. The caller owns the step, the answers and every callback, which is what lets a golden
 * screenshot drive each page without an Activity, a preference store or a running engine.
 *
 * The body scrolls and the action bar does not: at the largest accessibility font scale the copy can
 * exceed a small phone's viewport, and a primary action that scrolls off the bottom would strand the
 * user on the very first screen of a fresh install.
 */
@Composable
public fun McaOnboardingScaffold(
    step: McaOnboardingStep,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    primaryEnabled: Boolean = true,
    content: @Composable () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = PageInset)
    ) {
        Spacer(Modifier.height(PageInset))
        McaOnboardingProgressRow(step)
        Spacer(Modifier.height(SectionGap))
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SectionGap)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = step.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(modifier = Modifier.heightIn(min = MinContentHeight)) {
                content()
            }
        }
        Spacer(Modifier.height(SectionGap))
        McaButton(
            onClick = onPrimary,
            enabled = primaryEnabled,
            size = McaButtonSize.Regular,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(primaryLabel)
        }
        if (secondaryLabel != null && onSecondary != null) {
            Spacer(Modifier.height(8.dp))
            McaButton(
                onClick = onSecondary,
                style = McaButtonStyle.Text,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(secondaryLabel)
            }
        }
        Spacer(Modifier.height(PageInset))
    }
}

/**
 * One dot per step, the current one filled and wider.
 *
 * The row carries a content description naming the step out of the total, because a row of unlabelled
 * dots tells a screen-reader user nothing about how much of the flow is left.
 */
@Composable
private fun McaOnboardingProgressRow(step: McaOnboardingStep) {
    val total = McaOnboardingStep.entries.size
    val index = step.ordinal
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semanticsStepProgress(step, index, total),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { position ->
            val active = position <= index
            val current = position == index
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .width(if (current) 24.dp else 12.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
            )
        }
    }
}

/**
 * Announces "step N of M" for the progress row.
 *
 * Kept as an extension so the row itself stays a plain layout and the accessibility contract is testable
 * by name in a future screenshot/inspection pass.
 */
@Composable
private fun Modifier.semanticsStepProgress(
    step: McaOnboardingStep,
    index: Int,
    total: Int
): Modifier = this.then(
    Modifier.semantics {
        contentDescription = "第 ${index + 1} 步，共 $total 步：${step.title}"
    }
)

/**
 * Page one: what the app is and what the next three screens will ask.
 *
 * One primary action and nothing else. A welcome page with two equally weighted buttons makes a brand
 * new user choose before they know what the choice means.
 */
@Composable
public fun McaOnboardingWelcomePage(
    onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    McaOnboardingScaffold(
        step = McaOnboardingStep.WELCOME,
        primaryLabel = "开始",
        onPrimary = onStart,
        modifier = modifier
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OnboardingBullet("模型和对话都在手机本地处理", McaIcons.CheckCircle)
            OnboardingBullet("不需要注册账号就能开始", McaIcons.CheckCircle)
            OnboardingBullet("所有设置之后都能改", McaIcons.CheckCircle)
        }
    }
}

/** One "what you get" line on the welcome page. */
@Composable
private fun OnboardingBullet(text: String, glyph: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        McaIconPlate(size = McaPlateSize.Small) {
            Icon(
                imageVector = glyph,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(McaIconSize.Xs)
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Page two: the one decision that changes what the app installs.
 *
 * Both rows are rendered from [McaOnboardingPathChoice], so a future search index and this page read one
 * list. The recommended row is marked in text rather than by colour alone.
 */
@Composable
public fun McaOnboardingPathPage(
    selected: McaOnboardingPathChoice?,
    onSelect: (McaOnboardingPathChoice) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    McaOnboardingScaffold(
        step = McaOnboardingStep.PATH,
        primaryLabel = "继续",
        onPrimary = onContinue,
        primaryEnabled = selected != null,
        secondaryLabel = "返回",
        onSecondary = onBack,
        modifier = modifier
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            McaOnboardingPathChoice.entries.forEach { choice ->
                OnboardingPathRow(
                    choice = choice,
                    selected = choice == selected,
                    onClick = { onSelect(choice) }
                )
            }
        }
    }
}

@Composable
private fun OnboardingPathRow(
    choice: McaOnboardingPathChoice,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (selected) scheme.secondaryContainer else scheme.surface
    val content = if (selected) scheme.onSecondaryContainer else scheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(McaShape.Card)
            .background(container)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = choice.label,
                style = MaterialTheme.typography.titleSmall,
                color = content,
                modifier = Modifier.weight(1f)
            )
            if (choice == McaOnboardingPathChoice.Recommended) {
                Text(
                    text = "推荐",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary
                )
                Spacer(Modifier.width(8.dp))
            }
            // Second, non-colour signal for the current choice.
            if (selected) {
                Icon(
                    imageVector = McaIcons.Check,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(McaIconSize.S)
                )
            }
        }
        Text(
            text = choice.summary,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) content else scheme.onSurfaceVariant
        )
    }
}

/**
 * Page three: download a model now, or move on.
 *
 * The skip action is a real, equally visible exit rather than a hidden link: a user with a slow
 * connection must not be trapped behind a multi-hundred-megabyte download on their first launch.
 */
@Composable
public fun McaOnboardingModelPage(
    onDownloadRecommended: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    downloading: Boolean = false,
    progressPercent: Int? = null
) {
    val primaryLabel = when {
        downloading && progressPercent != null -> "正在下载 $progressPercent%"
        downloading -> "正在下载…"
        else -> "下载推荐模型"
    }
    McaOnboardingScaffold(
        step = McaOnboardingStep.MODEL,
        primaryLabel = primaryLabel,
        onPrimary = onDownloadRecommended,
        primaryEnabled = !downloading,
        secondaryLabel = if (downloading) "返回" else "跳过，稍后再说",
        onSecondary = if (downloading) onBack else onSkip,
        modifier = modifier
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = if (downloading) {
                    "下载会在后台继续，可以退出这一页。"
                } else {
                    "跳过也能用：聊天页会提示你去「模型管理」挑选。"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "推荐模型针对当前设备挑选，下载完成后会自动加载。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Page four: the flow is over.
 *
 * The primary action is the app itself; there is no second step hiding behind "完成", because a
 * completion screen that asks another question is a fifth page wearing a different title.
 */
@Composable
public fun McaOnboardingDonePage(
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
    skippedModel: Boolean = false
) {
    McaOnboardingScaffold(
        step = McaOnboardingStep.DONE,
        primaryLabel = "进入应用",
        onPrimary = onEnter,
        modifier = modifier
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (skippedModel) {
                Text(
                    text = "还没有可用模型。进入后到「模型管理」选一个，聊天页会一直提示你。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "模型已准备好，直接开始对话。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "外观、隐私、联网检索都在「系统设置」里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start
            )
        }
    }
}
