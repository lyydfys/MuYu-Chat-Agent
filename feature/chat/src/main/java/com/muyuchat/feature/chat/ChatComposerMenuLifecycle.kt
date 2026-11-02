package com.muyuchat.feature.chat

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue

/**
 * R04：composer 浮层菜单家族的显式生命周期。
 *
 * The previous implementation tied row clickability, the scrim and the back gesture to the two raw
 * host booleans. That let a fading row still run its business action, removed the research capsule
 * before its exit could draw, and left the system back gesture to the page while a menu was open.
 * The phases below separate the two questions the UI actually has:
 *
 * - *is the layer still on screen?* ([ComposerMenuLayer.isVisible]/[ComposerMenuLayer.wantsVisible])
 *   keeps the exit layer composed, painted with its last colours, and still intercepting input;
 * - *may a business action run?* ([ComposerMenuLayer.acceptsActions]) is false from the first
 *   [ComposerMenuPhase.Closing] frame, without changing any colour.
 *
 * Everything here is deterministic and UI-toolkit free apart from snapshot state, so the full
 * interaction contract is driven by a plain JVM test instead of source-string assertions.
 */
internal enum class ComposerMenuPhase {
    /** Nothing composed: no scrim, no sheet, no back capture. */
    Closed,

    /** Open requested; the content has not been composed yet. */
    Opening,

    /** The sheet is on screen. */
    Open,

    /** Exit requested; the sheet still draws its exit frames and still blocks input. */
    Closing,
}

/**
 * One layer of the composer menu family (the action sheet, or the research submenu above it).
 *
 * All entries reuse the same owner, so open/dismiss/reopen cannot race: a late animation completion
 * only acts on the phase that still expects it.
 *
 * [Stable] is a measured fact, not a suppression: every property composition can observe is a
 * snapshot state ([phase], [isRendered]), the remaining members are derived getters, and the
 * compiler reported this class as unstable only because of the previously plain `isRendered` `var`.
 * That instability made the whole menu family unstable and forced the open menu subtree to
 * re-compose on every composer keystroke.
 */
@Stable
internal class ComposerMenuLayer(initial: ComposerMenuPhase = ComposerMenuPhase.Closed) {
    var phase by mutableStateOf(initial)
        private set

    /**
     * True between the first content composition and its disposal.
     *
     * An open/close pair that happens before the first frame has nothing to animate out; knowing
     * this keeps the close from waiting for an exit completion that can never arrive (the system
     * animation scale 0x case included).
     *
     * Snapshot-backed rather than a plain `var`: [Stable] is only truthful while every value
     * composition can read is observable. Today composition reads only [phase]; this keeps a future
     * reader of `isRendered` correct instead of silently stale.
     */
    var isRendered by mutableStateOf(false)
        private set

    /** Composed for anything except [ComposerMenuPhase.Closed]: scrim, blocking layer, back. */
    val isVisible: Boolean get() = phase != ComposerMenuPhase.Closed

    /** The sheet itself is (or is becoming) visible. */
    val wantsVisible: Boolean
        get() = phase == ComposerMenuPhase.Opening || phase == ComposerMenuPhase.Open

    /** A tap on a row may run its business action exactly now. */
    val acceptsActions: Boolean get() = wantsVisible

    fun requestOpen(): Boolean = when (phase) {
        ComposerMenuPhase.Closed -> setPhase(ComposerMenuPhase.Opening)
        ComposerMenuPhase.Closing ->
            // Reopening during the exit reverses the same transition. When the content never left
            // composition it is already on screen, so the layer is immediately open again.
            setPhase(
                if (isRendered) ComposerMenuPhase.Open else ComposerMenuPhase.Opening
            )
        ComposerMenuPhase.Opening, ComposerMenuPhase.Open -> false
    }

    fun requestClose(): Boolean = when {
        phase == ComposerMenuPhase.Closed -> false
        // Idempotent: a repeated dismiss (scrim + back + row in the same frame) starts nothing new.
        phase == ComposerMenuPhase.Closing -> false
        !isRendered -> setPhase(ComposerMenuPhase.Closed)
        else -> setPhase(ComposerMenuPhase.Closing)
    }

    /** The content was composed: the layer is on screen and its rows accept business actions. */
    fun onContentComposed() {
        isRendered = true
        if (phase == ComposerMenuPhase.Opening) phase = ComposerMenuPhase.Open
    }

    /** The content left composition: the exit finished, or the host itself went away. */
    fun onContentDisposed() {
        // Only the exit that is actually running may settle here. A disposal callback that arrives
        // after the layer was reopened belongs to the abandoned exit: the content is still composed
        // for the current open phase, so it must neither hide the layer nor turn the next close into
        // an instant jump past its exit frames.
        if (phase != ComposerMenuPhase.Closing) return
        isRendered = false
        phase = ComposerMenuPhase.Closed
    }

    private fun setPhase(next: ComposerMenuPhase): Boolean {
        if (next == phase) return false
        phase = next
        return true
    }
}

/**
 * The single owner of the composer menu family: the action sheet plus the research submenu it
 * opens. Host code must not keep a second copy of "is the menu open".
 */
@Stable
internal class ComposerMenuLifecycle(restoredVisible: Boolean = false) {
    val menu = ComposerMenuLayer(
        if (restoredVisible) ComposerMenuPhase.Open else ComposerMenuPhase.Closed
    )
    val research = ComposerMenuLayer()

    /** The family still owns an exit layer, a blocking scrim and the back gesture. */
    val isComposed: Boolean get() = menu.isVisible || research.isVisible

    fun open() {
        menu.requestOpen()
    }

    /** Closes every layer. Idempotent, including from inside the exit. */
    fun dismiss() {
        menu.requestClose()
        research.requestClose()
    }

    /** The research submenu is a child layer: closing it leaves the sheet open. */
    fun dismissResearch() {
        research.requestClose()
    }

    /** Opening/closing the submenu is itself a menu action, so it runs only on an open sheet. */
    fun toggleResearch(): Boolean {
        if (!menu.acceptsActions) return false
        if (research.wantsVisible) research.requestClose() else research.requestOpen()
        return true
    }

    /**
     * Runs one business action at most once and then closes the family.
     *
     * The action is not deferred until the exit finishes; the close only starts afterwards, so the
     * camera/file picker/settings entry point keeps its original timing. A tap that lands during
     * the exit returns false and changes nothing.
     */
    fun runMenuAction(action: () -> Unit): Boolean {
        if (!menu.acceptsActions) return false
        action()
        dismiss()
        return true
    }

    fun runResearchAction(action: () -> Unit): Boolean {
        if (!research.acceptsActions) return false
        action()
        dismiss()
        return true
    }

    /**
     * Back closes exactly one layer: the submenu first, then the sheet, then the page.
     *
     * A layer that is already fading keeps consuming the gesture, so a double back never pops two
     * layers at once and never reaches the page while an exit frame is still visible.
     */
    fun handleBack(): Boolean {
        if (research.isVisible) {
            research.requestClose()
            return true
        }
        if (menu.isVisible) {
            menu.requestClose()
            return true
        }
        return false
    }

    companion object {
        /**
         * Preserves the previous `rememberSaveable` booleans: a configuration change with the sheet
         * open restores it open, without replaying the entrance inside the new composition.
         */
        val LifecycleSaver: Saver<ComposerMenuLifecycle, Boolean> = Saver(
            save = { it.isComposed },
            restore = { ComposerMenuLifecycle(restoredVisible = it) },
        )
    }
}
