package com.muyuchat.mca.dsh.overlay

import com.muyuchat.core.dsh.contract.DshOverlayReplyPreview

/**
 * What the floating mark is doing *right now*, for the live "AI output on screen" view.
 *
 * This is deliberately a closed set of four states instead of a growing set of flags: the window
 * renders exactly one of them, and every transition is a total function in [DshOverlayStreamReducer],
 * so the overlay can be tested without Compose, Android or a real model.
 *
 * Lifetime: memory only. Nothing here is persisted — a streaming turn is worthless after the process
 * dies, and the finished text already reaches the durable transcript in the app.
 */
sealed interface DshOverlayStream {
    /** Nothing is running; the mark shows its idle/breathing face. */
    data object Idle : DshOverlayStream

    /**
     * Assistant text is arriving. [text] is already tail-clamped to
     * [DshOverlayStreamReducer.MAX_STREAM_CHARS] (newest content wins, head replaced by an ellipsis)
     * and [startedAtMs] is the wall-clock start used by the progress arc.
     */
    data class Streaming(val text: String, val startedAtMs: Long) : DshOverlayStream

    /**
     * A tool is executing. [label] is a human phrase from [DshOverlayToolLabels]; raw tool
     * arguments — paths, commands, queries — never reach this state.
     */
    data class ToolRunning(val label: String) : DshOverlayStream

    /** The turn ended with [text]; the existing bubble gate and its 2 s/30 s rules take it from here. */
    data class Finished(val text: String) : DshOverlayStream
}

/**
 * Pure transition rules for [DshOverlayStream].
 *
 * Kept as an object with no state so every case is directly unit-testable: the presenter owns the
 * state, this file owns the meaning of each event.
 */
internal object DshOverlayStreamReducer {

    /** Aligned with the finished-reply preview so a stream and its bubble can never disagree. */
    const val MAX_STREAM_CHARS: Int = DshOverlayReplyPreview.MAX_PREVIEW_CHARS

    /**
     * Appends one delta.
     *
     * A delta that arrives while idle starts a new stream at [nowMs]; a delta that arrives after a
     * tool starts text output again from the accumulated text (tools normally emit no text, so this is
     * simply "the answer resumed"). Oversized text keeps the tail, which is the part the user has not
     * read yet, and marks the cut with a leading ellipsis.
     */
    fun onDelta(
        current: DshOverlayStream,
        delta: String,
        nowMs: Long,
        limit: Int = MAX_STREAM_CHARS
    ): DshOverlayStream {
        if (delta.isEmpty()) return current
        val base = current as? DshOverlayStream.Streaming
        val startedAt = base?.startedAtMs ?: nowMs
        val merged = if (base == null || base.text.isEmpty()) {
            delta.trimStart()
        } else {
            base.text + delta
        }
        val clamped = clampTail(merged, limit)
        // A whitespace-only opening chunk must not make the bubble look broken; the stream is still
        // considered started so the next real delta continues it.
        return DshOverlayStream.Streaming(text = clamped, startedAtMs = startedAt)
    }

    /** A tool started. Only its *name* is mapped; arguments never cross this boundary. */
    fun onToolStart(current: DshOverlayStream, toolName: String?): DshOverlayStream =
        DshOverlayStream.ToolRunning(label = DshOverlayToolLabels.of(toolName))

    /**
     * The turn ended.
     *
     * [finalText] wins when the transport supplies it; otherwise the accumulated stream text is used
     * so a turn that ended without a terminal snapshot still produces a bubble. An empty result is
     * [DshOverlayStream.Idle]: nothing to say, so nothing is shown.
     */
    fun onTurnEnd(current: DshOverlayStream, finalText: String?): DshOverlayStream {
        val candidate = finalText ?: (current as? DshOverlayStream.Streaming)?.text
        val text = candidate.orEmpty().trim()
        if (text.isEmpty()) return DshOverlayStream.Idle
        return DshOverlayStream.Finished(text.take(MAX_STREAM_CHARS))
    }

    /** Peer disconnect, host stop or a failed turn: never leave half an answer on screen. */
    fun onAbort(current: DshOverlayStream): DshOverlayStream = DshOverlayStream.Idle

    /**
     * Tail-clamps [text] to [limit] characters, prefixing `…` when anything was dropped.
     *
     * Also used by the window to show the newest 3 lines: the reducer's 280 characters can still be
     * more than a small bubble shows, and the tail is the part that matters while streaming.
     */
    fun clampTail(text: String, limit: Int): String {
        if (limit <= 0) return ""
        if (text.length <= limit) return text
        var tail = text.takeLast(limit - 1)
        // Never start the visible string inside a surrogate pair.
        if (tail.isNotEmpty() && tail.first().isLowSurrogate()) tail = tail.drop(1)
        return "…" + tail
    }
}

/**
 * Human label for one tool name.
 *
 * The overlay is visible over *other* apps, so a tool label is a sentence a stranger can read:
 * only a fixed mapping table may produce it. The raw name is normalized (lowercased, cut at the first
 * argument separator) and anything that still looks like a path, argument or URL is reported as the
 * generic label instead of being echoed.
 */
internal object DshOverlayToolLabels {
    const val UNKNOWN = "使用工具"

    private val exact = mapOf(
        "shell" to "运行命令",
        "exec" to "运行命令",
        "execute" to "运行命令",
        "bash" to "运行命令",
        "sh" to "运行命令",
        "terminal" to "运行命令",
        "command" to "运行命令",
        "run_command" to "运行命令",
        "read_file" to "读取文件",
        "read" to "读取文件",
        "view" to "读取文件",
        "cat" to "读取文件",
        "write_file" to "修改文件",
        "write" to "修改文件",
        "edit" to "修改文件",
        "edit_file" to "修改文件",
        "apply_patch" to "修改文件",
        "create_file" to "修改文件",
        "browser" to "上网查看",
        "web_search" to "上网查看",
        "search" to "上网查看",
        "fetch" to "上网查看",
        "open_url" to "上网查看",
        "web_fetch" to "上网查看",
        "list_files" to "查找内容",
        "list" to "查找内容",
        "ls" to "查找内容",
        "glob" to "查找内容",
        "grep" to "查找内容",
        "find" to "查找内容",
        "memory" to "整理记忆",
        "remember" to "整理记忆"
    )

    fun of(rawName: String?): String {
        val normalized = normalize(rawName)
        if (normalized.isEmpty()) return UNKNOWN
        exact[normalized]?.let { return it }
        // Gateways name their tools freely (`file_read`, `shell_exec`, `browser_open`); keyword
        // matching keeps those readable without ever echoing the name itself.
        return when {
            normalized.contains("shell") || normalized.contains("exec") ||
                normalized.contains("command") || normalized.contains("terminal") -> "运行命令"

            normalized.contains("read") || normalized.contains("view") -> "读取文件"
            normalized.contains("write") || normalized.contains("edit") ||
                normalized.contains("patch") -> "修改文件"

            normalized.contains("browser") || normalized.contains("web") ||
                normalized.contains("search") || normalized.contains("fetch") -> "上网查看"

            normalized.contains("grep") || normalized.contains("glob") ||
                normalized.contains("find") || normalized.contains("list") -> "查找内容"

            else -> UNKNOWN
        }
    }

    /**
     * Lowercased tool name with any argument tail removed. A value that still carries a path, URL,
     * query or `key=value` fragment is treated as "not a name" and maps to [UNKNOWN] upstream.
     */
    private fun normalize(rawName: String?): String {
        val trimmed = rawName.orEmpty().trim()
        if (trimmed.isEmpty()) return ""
        val name = trimmed
            .substringBefore('(')
            .substringBefore('{')
            .substringBefore(' ')
            .substringBefore('\t')
            .trim()
            .lowercase()
        if (name.isEmpty()) return ""
        val looksLikeArgument = name.contains('/') || name.contains('\\') ||
            name.contains(':') || name.contains('=') || name.contains('?') ||
            name.contains('&') || name.contains('"') || name.contains('\'')
        return if (looksLikeArgument) "" else name
    }
}

/**
 * Decides whether a new delta is worth pushing into Compose.
 *
 * DSH emits deltas far faster than a bubble can be read, and every push recomposes the window over
 * someone else's app. Two independent limits keep that cheap: at most one push per
 * [DEFAULT_MIN_INTERVAL_MILLIS], and only once at least [DEFAULT_MIN_CHAR_DELTA] new characters
 * accumulated. The *state* keeps every character; only the render trigger is throttled, and the turn
 * end always flushes through the finished-bubble path.
 */
internal class DshOverlayStreamThrottle(
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
    private val minCharDelta: Int = DEFAULT_MIN_CHAR_DELTA
) {
    private var lastEmitAtMs: Long? = null
    private var lastEmittedLength = 0

    fun shouldEmit(textLength: Int, nowMs: Long): Boolean {
        if (textLength < 0) return false
        if (textLength - lastEmittedLength < minCharDelta) return false
        val last = lastEmitAtMs
        // A backwards clock must not freeze the overlay for the rest of the process.
        if (last != null && nowMs >= last && nowMs - last < minIntervalMillis) return false
        lastEmitAtMs = nowMs
        lastEmittedLength = textLength
        return true
    }

    fun reset() {
        lastEmitAtMs = null
        lastEmittedLength = 0
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MILLIS = 100L
        const val DEFAULT_MIN_CHAR_DELTA = 2
    }
}
