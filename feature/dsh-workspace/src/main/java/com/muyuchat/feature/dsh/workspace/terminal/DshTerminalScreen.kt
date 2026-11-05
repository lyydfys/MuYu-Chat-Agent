package com.muyuchat.feature.dsh.workspace.terminal

/**
 * Bounded, line-oriented terminal screen model.
 *
 * The PTY hands us raw bytes: UTF-8 text interleaved with carriage returns, backspaces and ANSI
 * escape sequences (colour, cursor movement, line erase). Rendering that verbatim shows escape soup,
 * so this model consumes the stream and keeps only what a user would see in a terminal.
 *
 * Scope, deliberately narrow:
 *  - **handled**: `\r`, `\n`, `\t`, `\b`, CSI `K`/`J`/`H`, CSI `m` (colours are dropped), OSC
 *    (`ESC ] … BEL` / `ESC ] … ESC \`), and unpaired `ESC` sequences;
 *  - **not handled**: alternate screen buffers, scroll regions, cursor addressing beyond home, and
 *    any full-screen TUI. Those degrade to their printed text rather than to garbage, which is the
 *    behaviour the "简易兜底" fallback promises.
 *
 * The model is pure, allocation-bounded and never throws: a hostile stream can lose characters but
 * cannot grow the buffer past [maxLines] / [maxLineLength].
 */
class DshTerminalScreen(
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxLineLength: Int = DEFAULT_MAX_LINE_LENGTH
) {
    private val lines = ArrayDeque<String>()
    private val current = StringBuilder()

    init {
        require(maxLines > 0) { "terminal screen needs at least one line" }
        require(maxLineLength > 0) { "terminal screen needs a positive line length" }
    }

    // Incremental parser state, kept across `append` calls so a sequence split between two reads is
    // still recognised as one sequence.
    private var state = State.TEXT
    private var csiLength = 0

    /**
     * Every line currently on screen, oldest first, including the in-progress prompt line.
     *
     * Never empty: a fresh screen is one empty line.
     */
    fun lines(): List<String> = lines.toList() + current.toString()

    /** The visible text, joined by newlines. */
    fun text(): String = lines().joinToString("\n")

    /** The prompt line (what the user is typing into) without the trailing newline. */
    fun tail(): String = current.toString()

    fun clear() {
        lines.clear()
        current.setLength(0)
        state = State.TEXT
        csiLength = 0
    }

    /** Feeds one decoded chunk of terminal output. */
    fun append(chunk: String) {
        for (char in chunk) consume(char)
    }

    /** Feeds raw bytes, decoded as UTF-8 with replacement so a split code point cannot throw. */
    fun append(bytes: ByteArray, length: Int = bytes.size) {
        append(String(bytes, 0, length.coerceIn(0, bytes.size), Charsets.UTF_8))
    }

    private fun consume(char: Char) {
        when (state) {
            State.TEXT -> when (char) {
                '\u001b' -> state = State.ESCAPE
                '\n' -> commitLine()
                '\r' -> current.setLength(0)
                '\b' -> if (current.isNotEmpty()) current.setLength(current.length - 1)
                '\t' -> repeat(TAB_WIDTH) { appendVisible(' ') }
                '\u0007' -> Unit // bell
                else -> if (char >= ' ' || char == '\t') appendVisible(char)
            }
            State.ESCAPE -> when (char) {
                '[' -> {
                    state = State.CSI
                    csiLength = 0
                }
                ']' -> state = State.OSC
                // Two-character escapes (charset selection and friends) end here.
                else -> state = State.TEXT
            }
            State.CSI -> {
                csiLength += 1
                if (char in '@'..'~') {
                    applyCsi(char)
                    state = State.TEXT
                } else if (csiLength > MAX_SEQUENCE_LENGTH) {
                    // A runaway sequence is dropped instead of swallowing the rest of the stream.
                    state = State.TEXT
                }
            }
            State.OSC -> when {
                char == '\u0007' -> state = State.TEXT
                char == '\u001b' -> state = State.OSC_ESCAPE
                else -> Unit
            }
            State.OSC_ESCAPE -> state = if (char == '\\') State.TEXT else State.OSC
        }
    }

    private fun applyCsi(final: Char) {
        when (final) {
            // Erase in line: drop everything the terminal would erase.
            'K' -> current.setLength(0)
            // Erase in display: treat as a screen clear; a TUI redraw then starts from a clean slate.
            'J' -> clear()
            // Cursor home / position: subsequent output overwrites the prompt line.
            'H', 'f' -> current.setLength(0)
            else -> Unit
        }
    }

    private fun appendVisible(char: Char) {
        if (current.length >= maxLineLength) return
        current.append(char)
    }

    /**
     * Ends the current line and starts a new, empty one.
     *
     * [lines] holds *completed* lines only; the in-progress prompt lives in [current] and is
     * appended by [lines]. Keeping them apart is what stops a newline from overwriting the line it
     * just finished.
     */
    private fun commitLine() {
        lines.addLast(current.toString())
        current.setLength(0)
        trimToWindow()
    }

    private fun trimToWindow() {
        while (lines.size > maxLines) lines.removeFirst()
    }

    private enum class State { TEXT, ESCAPE, CSI, OSC, OSC_ESCAPE }

    companion object {
        const val DEFAULT_MAX_LINES: Int = 2_000
        const val DEFAULT_MAX_LINE_LENGTH: Int = 4_096
        const val TAB_WIDTH: Int = 8

        /** Longer than any sequence we care about; beyond this the sequence is abandoned. */
        const val MAX_SEQUENCE_LENGTH: Int = 64
    }
}
