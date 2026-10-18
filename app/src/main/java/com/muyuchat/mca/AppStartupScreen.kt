package com.muyuchat.mca

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.muyuchat.feature.chat.McaBrandLogo

/**
 * Launch surface shown until the first [MainViewModel] is ready.
 *
 * Performance contract:
 * - The infinite pulse/shimmer/sweep are held as `State<Float>` and read **inside**
 *   `graphicsLayer`, so a running animation never recomposes this screen.
 * - The entrance is a single [Animatable]; the staggered reveals are derived from it inside
 *   the same layer blocks.
 * - Standard Compose animations are used, so the system animator duration scale applies.
 *
 * Design contract:
 * - The skeleton mirrors the chat geometry the user is about to see (right-aligned user bubble
 *   with the 20/6 corner pair, full-width assistant lines) instead of a generic spinner.
 * - Colours, radii and type styles come from the existing theme; no new palette or type scale.
 */
@Composable
internal fun AppStartupScreen(state: AppStartupState, onRetry: () -> Unit, onSkipScan: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(1f, spring(dampingRatio = 0.92f, stiffness = 340f))
    }
    val reveal: (Float) -> Float = { delay ->
        ((entrance.value - delay) / (1f - delay)).coerceIn(0f, 1f)
    }

    val ambient = rememberInfiniteTransition(label = "startup-ambient")
    val breath = ambient.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "brand-breath",
    )
    val shimmer = ambient.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.62f,
        animationSpec = infiniteRepeatable(
            animation = tween(1150, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-shimmer",
    )
    val sweep = ambient.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "progress-sweep",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandBlock(breath = { breath.value }, reveal = reveal)
            Spacer(Modifier.height(22.dp))
            Text(
                text = "MCA",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onBackground,
                modifier = Modifier.graphicsLayer {
                    val p = reveal(0.12f)
                    alpha = p
                    translationY = (1f - p) * 10.dp.toPx()
                },
            )

            if (state.loading) {
                Spacer(Modifier.height(26.dp))
                StartupSkeleton(shimmer = { shimmer.value }, reveal = reveal)
                Spacer(Modifier.height(26.dp))
                StartupProgress(sweep = { sweep.value }, reveal = reveal)
                Spacer(Modifier.height(18.dp))
                StatusLine(reveal = reveal, label = "正在读取本地记录…")
                Spacer(Modifier.height(6.dp))
                CaptionLine(reveal = reveal, text = "大型模型的扫描将在后台继续。")

                if (state.slow) {
                    Spacer(Modifier.height(22.dp))
                    NoticeCard(
                        reveal = reveal,
                        delay = 0.66f,
                        text = "启动时间较长，可能正在整理聊天记录、知识库或模型目录。",
                        primaryLabel = "暂缓模型扫描，先进入应用",
                        onPrimary = onSkipScan,
                    )
                }
            } else {
                Spacer(Modifier.height(26.dp))
                NoticeCard(
                    reveal = reveal,
                    delay = 0.2f,
                    text = state.problem.orEmpty(),
                    primaryLabel = "暂缓模型扫描，继续启动",
                    onPrimary = onSkipScan,
                    secondaryLabel = "重新启动",
                    onSecondary = onRetry,
                    tertiaryLabel = "复制诊断信息",
                    onTertiary = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("MCA 启动诊断", state.diagnostic))
                    },
                    footnote = "无需卸载或清除应用数据。进入应用后可在模型页刷新本地模型。",
                )
            }
        }
    }
}

@Composable
private fun BrandBlock(breath: () -> Float, reveal: (Float) -> Float) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(116.dp)
            .graphicsLayer {
                val p = reveal(0f)
                alpha = p
                val scale = (0.92f + 0.08f * p) * breath()
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center,
    ) {
        // Soft halo behind the mark; drawn inside the layer, never read during composition.
        Canvas(Modifier.size(116.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors.primary.copy(alpha = 0.18f),
                        colors.primary.copy(alpha = 0.06f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = size.minDimension / 2f,
                ),
                radius = size.minDimension / 2f,
                center = center,
            )
        }
        McaBrandLogo(size = 72.dp, cornerRadius = 18.dp)
    }
}

@Composable
private fun StartupSkeleton(shimmer: () -> Float, reveal: (Float) -> Float) {
    val colors = MaterialTheme.colorScheme
    val bubbleShape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomEnd = 6.dp,
        bottomStart = 20.dp,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { },
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SkeletonRow(
            reveal = reveal,
            shimmer = shimmer,
            delay = 0.2f,
            bubbleFraction = 0.62f,
            lineFractions = listOf(0.84f, 0.6f, 0.38f),
            bubbleShape = bubbleShape,
            colors = colors,
        )
        SkeletonRow(
            reveal = reveal,
            shimmer = shimmer,
            delay = 0.28f,
            bubbleFraction = 0.46f,
            lineFractions = listOf(0.72f, 0.9f, 0.31f),
            bubbleShape = bubbleShape,
            colors = colors,
        )
        SkeletonRow(
            reveal = reveal,
            shimmer = shimmer,
            delay = 0.36f,
            bubbleFraction = 0.56f,
            lineFractions = listOf(0.66f),
            bubbleShape = bubbleShape,
            colors = colors,
        )
    }
}

@Composable
private fun SkeletonRow(
    reveal: (Float) -> Float,
    shimmer: () -> Float,
    delay: Float,
    bubbleFraction: Float,
    lineFractions: List<Float>,
    bubbleShape: RoundedCornerShape,
    colors: androidx.compose.material3.ColorScheme,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val p = reveal(delay)
                alpha = p
                translationY = (1f - p) * 12.dp.toPx()
            },
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .fillMaxWidth(bubbleFraction)
                    .height(34.dp)
                    .graphicsLayer { alpha = shimmer() }
                    .background(colors.surfaceVariant, bubbleShape)
            )
        }
        lineFractions.forEach { fraction ->
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(11.dp)
                    .graphicsLayer { alpha = shimmer() }
                    .background(colors.surfaceVariant, RoundedCornerShape(6.dp))
            )
        }
    }
}

@Composable
private fun StartupProgress(sweep: () -> Float, reveal: (Float) -> Float) {
    val colors = MaterialTheme.colorScheme
    var trackWidth by remember { mutableIntStateOf(0) }
    Box(
        modifier = Modifier
            .fillMaxWidth(0.72f)
            .height(3.dp)
            .graphicsLayer { alpha = reveal(0.42f) }
            .clip(CircleShape)
            .background(colors.surfaceVariant)
            .onSizeChanged { trackWidth = it.width }
            .semantics { contentDescription = "正在读取本地记录" },
    ) {
        Box(
            Modifier
                .fillMaxWidth(0.34f)
                .height(3.dp)
                .graphicsLayer {
                    val widthPx = trackWidth.toFloat()
                    translationX = widthPx * (sweep() * 1.6f - 0.5f)
                }
                .background(colors.primary, CircleShape)
        )
    }
}

@Composable
private fun StatusLine(reveal: (Float) -> Float, label: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.graphicsLayer { alpha = reveal(0.5f) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(colors.primary)
        )
        Text(text = label, style = MaterialTheme.typography.bodyLarge, color = colors.onBackground)
    }
}

@Composable
private fun CaptionLine(reveal: (Float) -> Float, text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.graphicsLayer { alpha = reveal(0.58f) },
    )
}

@Composable
private fun NoticeCard(
    reveal: (Float) -> Float,
    delay: Float,
    text: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    tertiaryLabel: String? = null,
    onTertiary: (() -> Unit)? = null,
    footnote: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val p = reveal(delay)
                alpha = p
                translationY = (1f - p) * 14.dp.toPx()
            },
        shape = RoundedCornerShape(20.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            ) {
                TextButton(onClick = onPrimary, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text(primaryLabel)
                }
                if (secondaryLabel != null && onSecondary != null) {
                    TextButton(onClick = onSecondary, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text(secondaryLabel)
                    }
                }
                if (tertiaryLabel != null && onTertiary != null) {
                    TextButton(onClick = onTertiary, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text(tertiaryLabel)
                    }
                }
            }
            if (footnote != null) {
                Text(
                    text = footnote,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
