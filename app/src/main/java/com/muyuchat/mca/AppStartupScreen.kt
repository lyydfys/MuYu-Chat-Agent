package com.muyuchat.mca

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.muyuchat.feature.chat.McaBrandLogo
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Launch surface shown until the first [MainViewModel] is ready.
 *
 * Performance contract:
 * - The infinite pulse/shimmer/orbit/sweep are held as `State<Float>` and read **inside**
 *   `graphicsLayer`/draw lambdas, so running animations never recompose this screen.
 * - The determinate bar animates through `animateFloatAsState` whose value is read by the
 *   indicator's progress lambda (draw phase), not during composition.
 * - Standard Compose animations are used, so the system animator duration scale applies.
 *
 * Honesty contract:
 * - The phase label and percentage come from real [AppStartupPhase] boundaries published by
 *   [AppStartupViewModel]; there is no synthetic timer.
 */
@Composable
internal fun AppStartupScreen(state: AppStartupState, onRetry: () -> Unit, onSkipScan: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.5f
    val shimmerHighlight = if (isDark) {
        colors.onSurface.copy(alpha = 0.10f)
    } else {
        Color.White.copy(alpha = 0.70f)
    }

    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(1f, spring(dampingRatio = 0.92f, stiffness = 340f))
    }
    val reveal: (Float) -> Float = { delay ->
        ((entrance.value - delay) / (1f - delay)).coerceIn(0f, 1f)
    }

    val ambient = rememberInfiniteTransition(label = "startup-ambient")
    // One looping signal only: the skeleton sweep. The brand mark is static and the progress bar
    // transitions on real phase changes, so the launch surface does not compete with itself.
    val shimmer = ambient.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeleton-sweep",
    )
    val animatedProgress = animateFloatAsState(
        targetValue = state.progress,
        animationSpec = spring(dampingRatio = 1f, stiffness = 90f),
        label = "startup-progress",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        // Ambient brand glow: one static radial, drawn once per size change.
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.width * 0.95f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors.primary.copy(alpha = if (isDark) 0.08f else 0.05f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width / 2f, size.height * 0.28f),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(size.width / 2f, size.height * 0.28f),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // Background stays full-bleed; only the readable content respects the system bars
                // (targetSdk 36 runs edge-to-edge on API 35+).
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandBlock(reveal = reveal)
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
                StartupSkeleton(
                    sweep = { shimmer.value },
                    highlight = shimmerHighlight,
                    reveal = reveal,
                )
                Spacer(Modifier.height(24.dp))
                StartupProgress(
                    progress = { animatedProgress.value },
                    reveal = reveal,
                )
                Spacer(Modifier.height(16.dp))
                StatusLine(
                    reveal = reveal,
                    label = state.phase.label,
                )
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
private fun BrandBlock(
    reveal: (Float) -> Float,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(124.dp)
            .graphicsLayer {
                val p = reveal(0f)
                alpha = p
                val scale = 0.92f + 0.08f * p
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(124.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f
            // Static halo: the launch surface keeps one moving signal (the skeleton sweep) instead
            // of layering breath, orbit and sweep at the same time.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors.primary.copy(alpha = 0.20f),
                        colors.primary.copy(alpha = 0.06f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
        McaBrandLogo(size = 72.dp, cornerRadius = 18.dp)
    }
}

@Composable
private fun StartupSkeleton(
    sweep: () -> Float,
    highlight: Color,
    reveal: (Float) -> Float,
) {
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
        SkeletonRow(reveal, sweep, highlight, 0.2f, 0.62f, listOf(0.84f, 0.6f, 0.38f), bubbleShape, colors)
        SkeletonRow(reveal, sweep, highlight, 0.28f, 0.46f, listOf(0.72f, 0.9f, 0.31f), bubbleShape, colors)
        SkeletonRow(reveal, sweep, highlight, 0.36f, 0.56f, listOf(0.66f), bubbleShape, colors)
    }
}

@Composable
private fun SkeletonRow(
    reveal: (Float) -> Float,
    sweep: () -> Float,
    highlight: Color,
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
            SkeletonBar(
                fraction = bubbleFraction,
                heightDp = 34,
                shape = bubbleShape,
                base = colors.surfaceVariant,
                highlight = highlight,
                sweep = sweep,
            )
        }
        lineFractions.forEach { fraction ->
            SkeletonBar(
                fraction = fraction,
                heightDp = 11,
                shape = RoundedCornerShape(6.dp),
                base = colors.surfaceVariant,
                highlight = highlight,
                sweep = sweep,
            )
        }
    }
}

@Composable
private fun SkeletonBar(
    fraction: Float,
    heightDp: Int,
    shape: RoundedCornerShape,
    base: Color,
    highlight: Color,
    sweep: () -> Float,
) {
    Box(
        Modifier
            .fillMaxWidth(fraction)
            .height(heightDp.dp)
            .clip(shape)
            .drawBehind {
                drawRect(base)
                // Highlight band travels across the bar; read at draw time only.
                val center = (sweep() * 1.8f - 0.4f) * size.width
                val half = size.width * 0.35f
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, highlight, Color.Transparent),
                        startX = center - half,
                        endX = center + half,
                    ),
                    size = size,
                )
            }
    )
}

@Composable
private fun StartupProgress(progress: () -> Float, reveal: (Float) -> Float) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth(0.86f)
            .height(4.dp)
            .graphicsLayer { alpha = reveal(0.42f) }
            .clip(CircleShape)
            .semantics { contentDescription = "启动进度" },
    ) {
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxSize(),
            color = colors.primary,
            trackColor = colors.surfaceVariant,
            strokeCap = StrokeCap.Round,
            // The default Material stop dot reads as a stray marker on a thin 4dp bar.
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun StatusLine(reveal: (Float) -> Float, label: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth(0.86f)
            .graphicsLayer { alpha = reveal(0.5f) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(colors.primary)
        )
        // No percentage: the phase weights are fixed and cannot honestly claim "x% of the work".
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
