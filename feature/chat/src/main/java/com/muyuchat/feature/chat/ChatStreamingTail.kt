package com.muyuchat.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextLayoutResult
import kotlin.coroutines.coroutineContext

private const val TailDurationNanos = 160_000_000L

/** Frame timestamps have no required relationship to System.nanoTime's epoch. */
internal class StreamingTailClock {
    var nowNanos: Long = 0L
        private set
    private var previousFrame: Long? = null

    fun advance(frameNanos: Long, scale: Float) {
        previousFrame?.let { previous ->
            if (scale > 0f) nowNanos += ((frameNanos - previous).coerceAtLeast(0L) / scale).toLong()
        }
        previousFrame = frameNanos
    }

    fun pause() { previousFrame = null }
    fun finishAt(timeNanos: Long) { nowNanos = maxOf(nowNanos, timeNanos); pause() }
}

internal data class StreamingTailGlyph(val start: Int, val end: Int, val bornNanos: Long) {
    fun alpha(nowNanos: Long, durationScale: Float = 1f): Float =
        if (durationScale <= 0f) 1f else
            (0.38f + 0.62f * ((nowNanos - bornNanos).toDouble() /
                (TailDurationNanos * durationScale)).toFloat().coerceIn(0f, 1f))
}

/** Bounded presentation state: appends keep the age of existing glyphs instead of dimming them again. */
internal class StreamingTailTracker {
    private var previousText = ""
    private var pending = emptyList<StreamingTailGlyph>()

    fun update(text: String, streaming: Boolean, nowNanos: Long): List<StreamingTailGlyph> {
        val appended = text.startsWith(previousText)
        val oldLength = if (appended) previousText.length else 0
        val previous = if (appended) pending else emptyList()
        previousText = text
        pending = if (!streaming) emptyList() else tailGlyphRanges(text).mapNotNull { range ->
            previous.firstOrNull { it.start == range.first }?.copy(end = range.last + 1)
                ?: if (range.last >= oldLength) StreamingTailGlyph(range.first, range.last + 1, nowNanos)
                else null
        }
        return pending
    }
}

/** Keep surrogate pairs, combining marks, joined emoji and paired flag indicators in one mask. */
internal fun tailGlyphRanges(text: String, limit: Int = 8): List<IntRange> {
    val ranges = ArrayList<IntRange>(limit)
    var end = text.length
    while (end > 0 && ranges.size < limit) {
        var start = text.offsetByCodePoints(end, -1)
        fun codePoint() = text.codePointAt(start)
        while (start > 0 && isTailExtender(codePoint())) start = text.offsetByCodePoints(start, -1)
        while (start > 0 && text.codePointBefore(start) == 0x200D) {
            start = text.offsetByCodePoints(start, -1)
            if (start > 0) start = text.offsetByCodePoints(start, -1)
            while (start > 0 && isTailExtender(codePoint())) start = text.offsetByCodePoints(start, -1)
        }
        if (codePoint() in 0x1F1E6..0x1F1FF) {
            var runStart = start
            while (runStart > 0 && text.codePointBefore(runStart) in 0x1F1E6..0x1F1FF) {
                runStart = text.offsetByCodePoints(runStart, -1)
            }
            if (start > runStart && (text.codePointCount(runStart, start) + 1) % 2 == 0) {
                start = text.offsetByCodePoints(start, -1)
            }
        }
        ranges.add(start until end)
        end = start
    }
    return ranges.asReversed()
}

private fun isTailExtender(codePoint: Int): Boolean {
    val type = Character.getType(codePoint)
    return type == Character.NON_SPACING_MARK.toInt() ||
        type == Character.COMBINING_SPACING_MARK.toInt() ||
        type == Character.ENCLOSING_MARK.toInt() || codePoint in 0xFE00..0xFE0F ||
        codePoint in 0x1F3FB..0x1F3FF || codePoint in 0xE0100..0xE01EF ||
        codePoint in 0xE0020..0xE007F
}

/**
 * A short-lived draw-only mask. Text is laid out once with all original spans and annotations.
 * Only the tail's small bounds use a temporary layer; a long paragraph never becomes a full-size
 * offscreen texture. Reading the frame clock here invalidates drawing, not text measurement.
 */
@Composable
internal fun Modifier.streamingTailFade(
    text: String,
    streaming: Boolean,
    layout: () -> TextLayoutResult?,
): Modifier {
    // Historical paragraphs allocate no mask cache or frame-clock subscription.
    if (!streaming) return this
    val clock = remember { StreamingTailClock() }
    val tracker = remember { StreamingTailTracker() }
    val glyphs = remember(text, streaming) { tracker.update(text, streaming, clock.nowNanos) }
    val frameTime = remember { mutableLongStateOf(0L) }
    LaunchedEffect(glyphs) {
        if (glyphs.isEmpty()) {
            clock.pause()
            return@LaunchedEffect
        }
        val motionScale = coroutineContext[MotionDurationScale]
        val lastBirth = glyphs.maxOf { it.bornNanos }
        do {
            if ((motionScale?.scaleFactor ?: 1f) <= 0f) {
                clock.finishAt(lastBirth + TailDurationNanos)
            } else withFrameNanos { frame ->
                val scale = motionScale?.scaleFactor ?: 1f
                if (scale <= 0f) clock.finishAt(lastBirth + TailDurationNanos)
                else clock.advance(frame, scale)
            }
            frameTime.longValue = clock.nowNanos
        } while (clock.nowNanos - lastBirth < TailDurationNanos)
        clock.pause()
    }
    return drawWithCache {
        val result = layout()
        val geometry = if (result == null || result.layoutInput.text.text != text) emptyList() else
            glyphs.mapNotNull { glyph ->
                var bounds = result.getBoundingBox(glyph.start)
                for (offset in glyph.start + 1 until glyph.end) {
                    val box = result.getBoundingBox(offset)
                    bounds = Rect(minOf(bounds.left, box.left), minOf(bounds.top, box.top),
                        maxOf(bounds.right, box.right), maxOf(bounds.bottom, box.bottom))
                }
                if (bounds.width <= 0f || bounds.height <= 0f) null else bounds to glyph
            }
        // Ligatures can give multiple characters the same/overlapping glyph box. Merge these
        // regions once so DstIn never multiplies alpha on the same pixels.
        val masks = ArrayList<Pair<Rect, StreamingTailGlyph>>()
        geometry.forEach { (rect, glyph) ->
            var union = rect
            var youngest = glyph
            var index = 0
            while (index < masks.size) {
                val (other, previous) = masks[index]
                if (union.overlaps(other)) {
                    union = Rect(minOf(union.left, other.left), minOf(union.top, other.top),
                        maxOf(union.right, other.right), maxOf(union.bottom, other.bottom))
                    if (previous.bornNanos > youngest.bornNanos) youngest = previous
                    masks.removeAt(index)
                    index = 0
                } else index++
            }
            masks.add(union to youngest)
        }
        val path = Path().apply { masks.forEach { (rect, _) -> addRect(rect) } }
        val paint = Paint()
        onDrawWithContent {
            if (masks.isEmpty() || masks.all { (_, glyph) -> glyph.alpha(frameTime.longValue) >= 1f }) {
                drawContent()
            } else {
                // Draw all settled content without a layer, then isolate only the animated glyphs.
                clipPath(path, ClipOp.Difference) { this@onDrawWithContent.drawContent() }
                val canvas = drawContext.canvas
                canvas.saveLayer(path.getBounds(), paint)
                try {
                    clipPath(path) {
                        this@onDrawWithContent.drawContent()
                        masks.forEach { (rect, glyph) ->
                            val alpha = glyph.alpha(frameTime.longValue)
                            drawRect(Color.Black.copy(alpha = alpha), rect.topLeft, rect.size,
                                blendMode = BlendMode.DstIn)
                        }
                    }
                } finally {
                    canvas.restore()
                }
            }
        }
    }
}
