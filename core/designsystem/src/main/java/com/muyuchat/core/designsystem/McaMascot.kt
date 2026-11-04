package com.muyuchat.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The mascot that represents DSH outside the app.
 *
 * Shape: every mark is **a round badge with the character inside**, matching the in-app DSH floating
 * ball. A bare silhouette floating over someone else's screen reads as a smudge; a white disc with a
 * glyph reads as a deliberate control, keeps one silhouette at every size, and still shows the app
 * colour when the character itself is pale.
 *
 * Why vector marks instead of bundled artwork: a floating window must stay crisp at 32–96 dp, follow
 * light/dark, and be redistributable. Every mark here is drawn from primitives in this file, so no
 * third-party artwork licence is involved. A user-supplied image is supported separately by
 * [McaMascot.CUSTOM]; the bitmap renderer lives with the overlay owner because it needs a file.
 */
enum class McaMascot(val label: String, val description: String) {
    WHALE("小鲸鱼", "圆形徽章里的小鲸鱼，和 DSH 页面的悬浮球一致"),
    WHALE_GIRL_ART("鲸鱼娘（原图）", "官方鲸鱼娘立绘，圆形徽章内 1:1 显示"),
    WHALE_GIRL_Q("鲸鱼娘 · Q 版", "蓝发鲸鱼娘，Q 版比例，女仆头饰与鲸鱼耳鳍"),
    WHALE_GIRL_MATURE("鲸鱼娘 · 成熟版", "同一位鲸鱼娘，成熟比例，长发与围裙上的小鲸鱼"),
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
 * Paints one mascot inside its round badge with a small, cheap animation.
 *
 * The animation is a single [rememberInfiniteTransition] driving the breathing scale and a slow
 * blink; no per-frame allocation beyond the drawn paths, so an always-on overlay does not wake the
 * CPU every 16 ms with layout work. [mood] is a *one-shot* cue owned by the caller (it passes
 * [McaMascotMood.REPLIED] for a few hundred milliseconds), because an overlay that keeps jumping is
 * hostile.
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
    if (mascot == McaMascot.WHALE_GIRL_ART) {
        McaBundledArtMark(modifier = modifier.size(size), size = size, mood = mood)
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
    // A sawtooth clock: the eyes close only in the last ~4% of a cycle, i.e. one short blink.
    val blinkClock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "mca-mascot-blink"
    )
    val eyesClosed = blinkClock > 0.955f
    val badgeFill = MaterialTheme.colorScheme.surface
    val ring = tint.copy(alpha = 0.22f)
    val halo = tint.copy(alpha = 0.10f)
    Canvas(modifier = modifier.size(size)) {
        val pulse = breath * (if (mood == McaMascotMood.REPLIED) 1.08f else 1f)
        val radius = this.size.minDimension / 2f
        drawCircle(color = halo, radius = radius)
        drawCircle(color = badgeFill, radius = radius * 0.92f)
        drawCircle(color = ring, radius = radius * 0.92f, style = Stroke(width = radius * 0.045f))
        if (pulse == 1f) {
            paintMascot(mascot, tint, accent, eyesClosed)
        } else {
            scale(
                scaleX = pulse,
                scaleY = pulse,
                pivot = Offset(this.size.width / 2f, this.size.height / 2f)
            ) {
                paintMascot(mascot, tint, accent, eyesClosed)
            }
        }
    }
}

/** Paints one mark. Kept out of the inline lambda so the scale wrapper stays JVM-inlineable. */
private fun DrawScope.paintMascot(
    mascot: McaMascot,
    tint: Color,
    accent: Color,
    eyesClosed: Boolean
) {
    when (mascot) {
        McaMascot.WHALE -> drawWhale(tint, accent)
        McaMascot.WHALE_GIRL_ART -> Unit
        McaMascot.WHALE_GIRL_Q -> drawWhaleGirl(tint, accent, chibi = true, eyesClosed = eyesClosed)
        McaMascot.WHALE_GIRL_MATURE ->
            drawWhaleGirl(tint, accent, chibi = false, eyesClosed = eyesClosed)
        McaMascot.FATTY_FISH -> drawFattyFish(tint, accent)
        McaMascot.CAT -> drawCat(tint, accent, eyesClosed = eyesClosed)
        McaMascot.CUSTOM -> Unit
    }
}

@Composable
private fun McaPlaceholderMark(modifier: Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        drawCircle(color = tint.copy(alpha = 0.18f), radius = radius)
        drawCircle(
            color = tint.copy(alpha = 0.55f),
            radius = radius * 0.5f,
            style = Stroke(width = radius / 11f)
        )
    }
}

/**
 * The bundled whale-girl artwork inside the same round badge.
 *
 * Two crops ship together: the full figure for the normal badge and a head close-up used below
 * 52 dp, where a whole-body drawing would be an unreadable smudge. The disc is always light — the
 * artwork was produced on white and its apron/headband are white too, so a themed dark disc would
 * show a white rectangle behind the character.
 */
@Composable
private fun McaBundledArtMark(modifier: Modifier, size: Dp, mood: McaMascotMood) {
    val transition = rememberInfiniteTransition(label = "mca-mascot-art")
    val breath by transition.animateFloat(
        initialValue = 0.99f,
        targetValue = if (mood == McaMascotMood.IDLE) 1.01f else 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (mood == McaMascotMood.IDLE) 4_000 else 1_100,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mca-mascot-art-breath"
    )
    val head = size <= 52.dp
    val painter = painterResource(
        if (head) {
            com.muyuchat.core.designsystem.R.drawable.mca_mascot_whale_girl_head
        } else {
            com.muyuchat.core.designsystem.R.drawable.mca_mascot_whale_girl_art
        }
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = this.size.minDimension / 2f
            drawCircle(color = Color(0xFF2F6FD0).copy(alpha = 0.10f), radius = radius)
            drawCircle(color = Color.White, radius = radius * 0.92f)
            drawCircle(
                color = Color(0xFF2F6FD0).copy(alpha = 0.18f),
                radius = radius * 0.92f,
                style = Stroke(width = radius * 0.045f)
            )
        }
        Image(
            painter = painter,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize(0.86f)
                .graphicsLayer {
                    scaleX = breath
                    scaleY = breath
                },
            contentScale = if (head) ContentScale.Crop else ContentScale.Fit
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
//
// Everything is expressed as fractions of the badge box and centred on it, so one painter covers the
// whole 40–96 dp range the overlay allows without size-specific variants.

private val FACE_TONE = Color(0xFFFFE7DC)
private val DRESS_NAVY = Color(0xFF1F3A63)
private val APRON_WHITE = Color(0xFFF7F4EE)
private val EYE_DARK = Color(0xFF2B4A78)
private val EYE_MID = Color(0xFF5B8FD6)
private val MOUTH_OPEN = Color(0xFFE07A7A)
private val MOUTH_LINE = Color(0xFFB4635F)
private val BOW_BLUE = Color(0xFF4A9BE8)

private fun DrawScope.drawWhale(body: Color, tail: Color) {
    val w = size.width
    val h = size.height
    // Body sits low-left inside the disc; the tail flicks to the right, like the in-app ball glyph.
    drawOval(
        color = body,
        topLeft = Offset(w * 0.17f, h * 0.32f),
        size = Size(w * 0.52f, h * 0.40f)
    )
    drawPath(
        Path().apply {
            moveTo(w * 0.66f, h * 0.52f)
            lineTo(w * 0.85f, h * 0.36f)
            lineTo(w * 0.80f, h * 0.52f)
            lineTo(w * 0.85f, h * 0.68f)
            close()
        },
        color = tail
    )
    drawCircle(color = Color.White.copy(alpha = 0.30f), radius = w * 0.09f, center = Offset(w * 0.37f, h * 0.58f))
    drawCircle(color = Color.White, radius = w * 0.040f, center = Offset(w * 0.41f, h * 0.45f))
    drawCircle(color = body, radius = w * 0.021f, center = Offset(w * 0.41f, h * 0.45f))
    drawCircle(color = body.copy(alpha = 0.38f), radius = w * 0.028f, center = Offset(w * 0.29f, h * 0.25f))
}

private fun DrawScope.drawFattyFish(body: Color, fin: Color) {
    val w = size.width
    val h = size.height
    drawCircle(color = body, radius = w * 0.25f, center = Offset(w * 0.45f, h * 0.55f))
    drawPath(
        Path().apply {
            moveTo(w * 0.68f, h * 0.53f)
            lineTo(w * 0.82f, h * 0.41f)
            lineTo(w * 0.79f, h * 0.55f)
            lineTo(w * 0.82f, h * 0.69f)
            close()
        },
        color = fin
    )
    drawCircle(color = Color.White, radius = w * 0.046f, center = Offset(w * 0.37f, h * 0.49f))
    drawCircle(color = body, radius = w * 0.023f, center = Offset(w * 0.37f, h * 0.49f))
    drawCircle(color = body.copy(alpha = 0.30f), radius = w * 0.034f, center = Offset(w * 0.27f, h * 0.33f))
    drawCircle(color = body.copy(alpha = 0.20f), radius = w * 0.022f, center = Offset(w * 0.35f, h * 0.25f))
}

private fun DrawScope.drawCat(body: Color, ear: Color, eyesClosed: Boolean) {
    val w = size.width
    val h = size.height
    drawPath(
        Path().apply {
            moveTo(w * 0.31f, h * 0.41f)
            lineTo(w * 0.28f, h * 0.24f)
            lineTo(w * 0.44f, h * 0.36f)
            close()
        },
        color = ear
    )
    drawPath(
        Path().apply {
            moveTo(w * 0.69f, h * 0.41f)
            lineTo(w * 0.72f, h * 0.24f)
            lineTo(w * 0.56f, h * 0.36f)
            close()
        },
        color = ear
    )
    drawCircle(color = body, radius = w * 0.25f, center = Offset(w * 0.50f, h * 0.56f))
    listOf(-1f, 1f).forEach { side ->
        val eyeX = w * 0.50f + side * w * 0.10f
        if (eyesClosed) {
            drawArc(
                color = body,
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(eyeX - w * 0.05f, h * 0.50f),
                size = Size(w * 0.10f, h * 0.08f),
                style = Stroke(width = w * 0.022f)
            )
        } else {
            drawCircle(color = Color.White, radius = w * 0.048f, center = Offset(eyeX, h * 0.53f))
            drawCircle(color = body, radius = w * 0.023f, center = Offset(eyeX, h * 0.53f))
        }
    }
    drawPath(
        Path().apply {
            moveTo(w * 0.46f, h * 0.63f)
            lineTo(w * 0.54f, h * 0.63f)
            lineTo(w * 0.50f, h * 0.67f)
            close()
        },
        color = ear
    )
}

/**
 * The whale-girl: blue hair, whale-fin ears, maid headband with a blue bow, navy dress and the white
 * apron carrying a small whale.
 *
 * `chibi = true` keeps the Q 版 proportions (big head, short body, open smile); the mature variant
 * uses a smaller head, a long hair fall and a slimmer dress. These are **original simplified
 * painters** rather than a copy of any illustration: they carry the recognisable features at badge
 * size, and a user who wants the exact artwork can import it through [McaMascot.CUSTOM].
 */
private fun DrawScope.drawWhaleGirl(
    hair: Color,
    accent: Color,
    chibi: Boolean,
    eyesClosed: Boolean
) {
    val w = size.width
    val h = size.height
    val headY = if (chibi) h * 0.45f else h * 0.41f
    val headR = if (chibi) w * 0.25f else w * 0.215f
    val bodyTop = if (chibi) h * 0.60f else h * 0.57f

    // Back hair: one shape behind head and body, wider and longer in the mature variant.
    drawPath(
        Path().apply {
            moveTo(w * 0.50f - headR * 1.30f, headY - headR * 0.30f)
            cubicTo(
                w * 0.50f - headR * 1.75f, h * (if (chibi) 0.78f else 0.88f),
                w * 0.50f - headR * 0.55f, h * (if (chibi) 0.92f else 1.0f),
                w * 0.50f, h * (if (chibi) 0.90f else 0.97f)
            )
            cubicTo(
                w * 0.50f + headR * 0.55f, h * (if (chibi) 0.92f else 1.0f),
                w * 0.50f + headR * 1.75f, h * (if (chibi) 0.78f else 0.88f),
                w * 0.50f + headR * 1.30f, headY - headR * 0.30f
            )
            close()
        },
        color = hair.copy(alpha = 0.92f)
    )

    // Dress + apron.
    drawOval(
        color = DRESS_NAVY,
        topLeft = Offset(w * 0.50f - headR * (if (chibi) 0.95f else 0.82f), bodyTop),
        size = Size(headR * (if (chibi) 1.90f else 1.64f), h * (if (chibi) 0.34f else 0.38f))
    )
    drawOval(
        color = APRON_WHITE,
        topLeft = Offset(w * 0.50f - headR * (if (chibi) 0.55f else 0.48f), bodyTop + h * 0.07f),
        size = Size(headR * (if (chibi) 1.10f else 0.96f), h * 0.22f)
    )
    // The apron's whale: the one detail that ties her back to DSH.
    val apronWhaleY = bodyTop + h * 0.17f
    drawOval(
        color = accent,
        topLeft = Offset(w * 0.50f - w * 0.055f, apronWhaleY - h * 0.022f),
        size = Size(w * 0.075f, h * 0.045f)
    )
    drawPath(
        Path().apply {
            moveTo(w * 0.50f + w * 0.020f, apronWhaleY)
            lineTo(w * 0.50f + w * 0.050f, apronWhaleY - h * 0.020f)
            lineTo(w * 0.50f + w * 0.042f, apronWhaleY)
            lineTo(w * 0.50f + w * 0.050f, apronWhaleY + h * 0.020f)
            close()
        },
        color = accent
    )

    // Head, then the fringe on top of it.
    drawCircle(color = FACE_TONE, radius = headR, center = Offset(w * 0.50f, headY))
    drawPath(
        Path().apply {
            moveTo(w * 0.50f - headR * 1.04f, headY + headR * 0.05f)
            cubicTo(
                w * 0.50f - headR * 1.10f, headY - headR * 1.22f,
                w * 0.50f + headR * 1.10f, headY - headR * 1.22f,
                w * 0.50f + headR * 1.04f, headY + headR * 0.05f
            )
            cubicTo(
                w * 0.50f + headR * 0.70f, headY - headR * 0.42f,
                w * 0.50f - headR * 0.70f, headY - headR * 0.42f,
                w * 0.50f - headR * 1.04f, headY + headR * 0.05f
            )
            close()
        },
        color = hair
    )

    // Whale-fin ears: the silhouette that makes her readable at 40 dp.
    listOf(-1f, 1f).forEach { side ->
        val earX = w * 0.50f + side * headR * 0.98f
        drawPath(
            Path().apply {
                moveTo(earX, headY - headR * 0.30f)
                lineTo(earX + side * w * 0.085f, headY + headR * 0.10f)
                lineTo(earX, headY + headR * 0.28f)
                close()
            },
            color = hair.copy(alpha = 0.95f)
        )
    }

    // Maid headband arc + blue bow on the right.
    drawArc(
        color = Color.White,
        startAngle = 205f,
        sweepAngle = 130f,
        useCenter = false,
        topLeft = Offset(w * 0.50f - headR * 1.06f, headY - headR * 1.10f),
        size = Size(headR * 2.12f, headR * 1.70f),
        style = Stroke(width = w * 0.042f)
    )
    val bowX = w * 0.50f + headR * 0.72f
    val bowY = headY - headR * 0.78f
    drawPath(
        Path().apply {
            moveTo(bowX, bowY)
            lineTo(bowX + w * 0.062f, bowY - w * 0.040f)
            lineTo(bowX + w * 0.062f, bowY + w * 0.040f)
            close()
        },
        color = BOW_BLUE
    )
    drawPath(
        Path().apply {
            moveTo(bowX, bowY)
            lineTo(bowX - w * 0.062f, bowY - w * 0.040f)
            lineTo(bowX - w * 0.062f, bowY + w * 0.040f)
            close()
        },
        color = BOW_BLUE
    )
    drawCircle(color = BOW_BLUE, radius = w * 0.022f, center = Offset(bowX, bowY))

    // Eyes + mouth.
    val eyeY = headY + headR * 0.14f
    listOf(-1f, 1f).forEach { side ->
        val eyeX = w * 0.50f + side * headR * 0.40f
        if (eyesClosed) {
            drawArc(
                color = hair.copy(alpha = 0.85f),
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(eyeX - headR * 0.20f, eyeY - headR * 0.08f),
                size = Size(headR * 0.40f, headR * 0.30f),
                style = Stroke(width = headR * 0.09f)
            )
        } else {
            drawCircle(color = EYE_DARK, radius = headR * 0.17f, center = Offset(eyeX, eyeY))
            drawCircle(color = EYE_MID, radius = headR * 0.10f, center = Offset(eyeX, eyeY + headR * 0.05f))
            drawCircle(
                color = Color.White,
                radius = headR * 0.055f,
                center = Offset(eyeX - headR * 0.06f, eyeY - headR * 0.07f)
            )
        }
    }
    if (chibi) {
        drawCircle(color = MOUTH_OPEN, radius = headR * 0.10f, center = Offset(w * 0.50f, eyeY + headR * 0.36f))
    } else {
        drawArc(
            color = MOUTH_LINE,
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(w * 0.50f - headR * 0.16f, eyeY + headR * 0.20f),
            size = Size(headR * 0.32f, headR * 0.22f),
            style = Stroke(width = headR * 0.07f)
        )
    }
}
