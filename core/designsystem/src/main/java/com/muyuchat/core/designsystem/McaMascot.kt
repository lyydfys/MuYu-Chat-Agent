package com.muyuchat.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The mascot that represents DSH outside the app.
 *
 * Why vector marks instead of bundled artwork: a floating window is shown over other apps and in
 * the notification-ish corner of the screen, so it must stay crisp at 32–96 dp, follow light/dark,
 * and be redistributable. Every mark here is drawn from primitives in this file, so no third-party
 * artwork licence is involved. A user-supplied image is supported separately by
 * [McaMascot.CUSTOM]; the renderer for that lives with the overlay owner because it needs a file.
 */
enum class McaMascot(val label: String, val description: String) {
    WHALE("小鲸鱼", "深蓝鲸鱼，尾巴会摆动"),
    FATTY_FISH("小肥鱼", "圆滚滚的胖鱼，会吐泡泡"),
    CAT("小猫", "带耳朵的圆脸，会眨眼"),
    CUSTOM("自定义", "使用你自己选的图片")
}

/** Which painted detail of the mark is currently animating; driven by the owner's state. */
enum class McaMascotMood {
    /** Idle breathe: very slow scale pulse, no motion that draws attention. */
    IDLE,

    /** DSH is working: faster pulse plus a small bob. */
    WORKING,

    /** A reply just arrived: one bounce is shown, then it falls back to idle. */
    REPLIED
}

/**
 * Paints one mascot mark with a small, cheap animation.
 *
 * The animation is a single [rememberInfiniteTransition]; no per-frame allocation beyond the two
 * drawn paths, so an always-on overlay does not wake the CPU every 16 ms with layout work.
 * [replied] is deliberately a *one-shot* cue owned by the caller (it passes [McaMascotMood.REPLIED]
 * for a few hundred milliseconds), because an overlay that keeps jumping is hostile.
 */
@Composable
fun McaMascotMark(
    mascot: McaMascot,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    mood: McaMascotMood = McaMascotMood.IDLE,
    tint: Color = MaterialTheme.colorScheme.primary,
    accent: Color = MaterialTheme.colorScheme.secondary
) {
    if (mascot == McaMascot.CUSTOM) {
        // The overlay owner supplies the bitmap; the design system only owns vector marks.
        McaPlaceholderMark(modifier = modifier.size(size), tint = tint)
        return
    }
    val transition = rememberInfiniteTransition(label = "mca-mascot")
    val breath by transition.animateFloat(
        initialValue = if (mood == McaMascotMood.IDLE) 0.985f else 1f,
        targetValue = if (mood == McaMascotMood.IDLE) 1.015f else 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (mood) {
                    McaMascotMood.IDLE -> 3_200
                    McaMascotMood.WORKING -> 900
                    McaMascotMood.REPLIED -> 520
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mca-mascot-breath"
    )
    Canvas(modifier = modifier.size(size)) {
        val pulse = breath * (if (mood == McaMascotMood.REPLIED) 1.08f else 1f)
        if (pulse == 1f) {
            paintMascot(mascot, tint, accent)
        } else {
            scale(
                scaleX = pulse,
                scaleY = pulse,
                pivot = Offset(this.size.width / 2f, this.size.height / 2f)
            ) {
                paintMascot(mascot, tint, accent)
            }
        }
    }
}

/** Paints one mark. Kept out of the inline lambda so the scale wrapper stays JVM-inlineable. */
private fun DrawScope.paintMascot(mascot: McaMascot, tint: Color, accent: Color) {
    when (mascot) {
        McaMascot.WHALE -> drawWhale(tint, accent)
        McaMascot.FATTY_FISH -> drawFattyFish(tint, accent)
        McaMascot.CAT -> drawCat(tint, accent)
        McaMascot.CUSTOM -> Unit
    }
}

@Composable
private fun McaPlaceholderMark(modifier: Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        drawCircle(color = tint.copy(alpha = 0.18f), radius = size.minDimension / 2f)
        drawCircle(
            color = tint.copy(alpha = 0.55f),
            radius = size.minDimension / 4f,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.minDimension / 22f)
        )
    }
}

/**
 * Compact speech bubble used by the overlay when DSH has something to say.
 *
 * It clamps to [maxWidth] and [maxLines]; the full text stays one tap away in the app, so an
 * overlay never grows into a wall of text over someone else's screen.
 */
@Composable
fun McaMascotSpeechBubble(
    text: String,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 240.dp,
    maxLines: Int = 3
) {
    Surface(
        modifier = modifier.widthIn(max = maxWidth),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = maxLines,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

// ---- vector marks ---------------------------------------------------------------------------

private fun DrawScope.drawWhale(body: Color, tail: Color) {
    val w = size.width
    val h = size.height
    // Body: a rounded bean.
    drawOval(
        color = body,
        topLeft = Offset(w * 0.10f, h * 0.24f),
        size = Size(w * 0.68f, h * 0.52f)
    )
    // Tail: two fins meeting the body's right edge.
    val tailPath = Path().apply {
        moveTo(w * 0.74f, h * 0.50f)
        lineTo(w * 0.98f, h * 0.30f)
        lineTo(w * 0.94f, h * 0.52f)
        lineTo(w * 0.98f, h * 0.72f)
        close()
    }
    drawPath(tailPath, color = tail)
    // Belly highlight + eye + spout.
    drawCircle(
        color = Color.White.copy(alpha = 0.30f),
        radius = w * 0.14f,
        center = Offset(w * 0.34f, h * 0.58f)
    )
    drawCircle(color = Color.White, radius = w * 0.055f, center = Offset(w * 0.40f, h * 0.42f))
    drawCircle(color = body, radius = w * 0.028f, center = Offset(w * 0.40f, h * 0.42f))
    drawCircle(
        color = body.copy(alpha = 0.35f),
        radius = w * 0.035f,
        center = Offset(w * 0.22f, h * 0.16f)
    )
}

private fun DrawScope.drawFattyFish(body: Color, fin: Color) {
    val w = size.width
    val h = size.height
    drawCircle(color = body, radius = w * 0.34f, center = Offset(w * 0.44f, h * 0.54f))
    val finPath = Path().apply {
        moveTo(w * 0.76f, h * 0.52f)
        lineTo(w * 0.98f, h * 0.36f)
        lineTo(w * 0.94f, h * 0.56f)
        lineTo(w * 0.98f, h * 0.74f)
        close()
    }
    drawPath(finPath, color = fin)
    drawCircle(color = Color.White, radius = w * 0.06f, center = Offset(w * 0.34f, h * 0.46f))
    drawCircle(color = body, radius = w * 0.03f, center = Offset(w * 0.34f, h * 0.46f))
    // Two bubbles.
    drawCircle(
        color = body.copy(alpha = 0.32f),
        radius = w * 0.045f,
        center = Offset(w * 0.16f, h * 0.24f)
    )
    drawCircle(
        color = body.copy(alpha = 0.22f),
        radius = w * 0.03f,
        center = Offset(w * 0.26f, h * 0.14f)
    )
}

private fun DrawScope.drawCat(body: Color, ear: Color) {
    val w = size.width
    val h = size.height
    drawCircle(color = body, radius = w * 0.33f, center = Offset(w * 0.50f, h * 0.56f))
    val leftEar = Path().apply {
        moveTo(w * 0.26f, h * 0.34f)
        lineTo(w * 0.22f, h * 0.10f)
        lineTo(w * 0.44f, h * 0.28f)
        close()
    }
    val rightEar = Path().apply {
        moveTo(w * 0.74f, h * 0.34f)
        lineTo(w * 0.78f, h * 0.10f)
        lineTo(w * 0.56f, h * 0.28f)
        close()
    }
    drawPath(leftEar, color = ear)
    drawPath(rightEar, color = ear)
    drawCircle(color = Color.White, radius = w * 0.06f, center = Offset(w * 0.40f, h * 0.52f))
    drawCircle(color = Color.White, radius = w * 0.06f, center = Offset(w * 0.60f, h * 0.52f))
    drawCircle(color = body, radius = w * 0.028f, center = Offset(w * 0.40f, h * 0.52f))
    drawCircle(color = body, radius = w * 0.028f, center = Offset(w * 0.60f, h * 0.52f))
}
