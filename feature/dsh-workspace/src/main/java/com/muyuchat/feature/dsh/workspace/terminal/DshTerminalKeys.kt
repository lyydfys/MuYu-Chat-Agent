package com.muyuchat.feature.dsh.workspace.terminal

/**
 * One key of the extended key row.
 *
 * The row exists because a phone keyboard has no Ctrl, Alt, Esc, arrows, Tab or pipe. Each key
 * carries the exact bytes a terminal would send, so the page never has to guess: [payload] is what
 * goes to the PTY, [label] is what the user reads.
 */
data class DshTerminalKey(
    val id: String,
    val label: String,
    val payload: String,
    /** True for keys that must not also submit the input line (Ctrl/Alt are modifiers). */
    val modifier: Boolean = false
)

/** Sticky modifier state above the key row. */
data class DshTerminalModifiers(
    val ctrl: Boolean = false,
    val alt: Boolean = false
) {
    val any: Boolean get() = ctrl || alt

    fun toggleCtrl(): DshTerminalModifiers = copy(ctrl = !ctrl)

    fun toggleAlt(): DshTerminalModifiers = copy(alt = !alt)

    fun cleared(): DshTerminalModifiers = DshTerminalModifiers()
}

/**
 * Extended key row, in the order terminals conventionally show it.
 *
 * Arrows send the CSI sequences a real terminal sends (not the word "Up"), Esc is `\u001b`, Tab is
 * `\t`. Ctrl/Alt are sticky toggles, and [applyModifiers] folds them into the next payload — so
 * `Ctrl` + `c` produces the same `0x03` a desktop terminal would.
 */
object DshTerminalKeys {

    const val CTRL_C: Char = '\u0003'
    const val CTRL_D: Char = '\u0004'
    const val CTRL_L: Char = '\u000c'
    const val CTRL_Z: Char = '\u001a'

    val ESC: DshTerminalKey = DshTerminalKey("esc", "Esc", "\u001b")
    val TAB: DshTerminalKey = DshTerminalKey("tab", "Tab", "\t")
    val UP: DshTerminalKey = DshTerminalKey("up", "↑", "\u001b[A")
    val DOWN: DshTerminalKey = DshTerminalKey("down", "↓", "\u001b[B")
    val RIGHT: DshTerminalKey = DshTerminalKey("right", "→", "\u001b[C")
    val LEFT: DshTerminalKey = DshTerminalKey("left", "←", "\u001b[D")
    val HOME: DshTerminalKey = DshTerminalKey("home", "Home", "\u001b[H")
    val END: DshTerminalKey = DshTerminalKey("end", "End", "\u001b[F")
    val PIPE: DshTerminalKey = DshTerminalKey("pipe", "|", "|")
    val TILDE: DshTerminalKey = DshTerminalKey("tilde", "~", "~")
    val SLASH: DshTerminalKey = DshTerminalKey("slash", "/", "/")
    val DASH: DshTerminalKey = DshTerminalKey("dash", "-", "-")
    val CTRL_C_KEY: DshTerminalKey =
        DshTerminalKey("ctrl-c", "^C", CTRL_C.toString(), modifier = false)
    val CTRL_D_KEY: DshTerminalKey =
        DshTerminalKey("ctrl-d", "^D", CTRL_D.toString(), modifier = false)

    val ROW: List<DshTerminalKey> = listOf(
        ESC, TAB, UP, DOWN, LEFT, RIGHT, HOME, END,
        PIPE, TILDE, SLASH, DASH, CTRL_C_KEY, CTRL_D_KEY
    )

    /**
     * Folds sticky modifiers into the payload of the next key or typed character.
     *
     * Ctrl is applied the way a terminal does it: `Ctrl`+`<letter>` becomes `code & 0x1f`. Alt
     * prefixes ESC. Any other combination is left unchanged rather than invented.
     */
    fun applyModifiers(payload: String, modifiers: DshTerminalModifiers): String {
        if (!modifiers.any || payload.isEmpty()) return payload
        var result = payload
        if (modifiers.ctrl) {
            val first = result[0]
            val folded = if (first in 'a'..'z') {
                (first.code - 'a'.code + 1).toChar()
            } else if (first in 'A'..'Z') {
                (first.code - 'A'.code + 1).toChar()
            } else {
                first
            }
            result = folded + result.substring(1)
        }
        if (modifiers.alt) result = "\u001b$result"
        return result
    }
}
