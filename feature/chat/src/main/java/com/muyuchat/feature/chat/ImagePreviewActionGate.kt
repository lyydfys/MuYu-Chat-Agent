package com.muyuchat.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * R07.2: every business action the full-screen image preview can start.
 */
internal enum class ImagePreviewAction {
    FAVORITE,
    MORE_MENU,
    RETRY_IMAGE,
    RECREATE,
    SAVE,
    SHARE,
    DELETE,
    UPSCALE,
    USE_AS_IMAGE_INPUT,
    USE_PARAMETERS,
    SHOW_GENERATION_DETAILS
}

/**
 * Closing state owner for [ImageAssetPreviewOverlay].
 *
 * The overlay plays a ~150 ms exit before it tells the host to remove it. That window used to be a
 * hole: the preview's own Back handler switched off as soon as the exit started, so a second Back
 * reached the workspace and cleared the preview id immediately, truncating the exit; and the
 * remaining bottom-bar/overflow actions were not gated at all.
 *
 * The rules modelled here:
 *
 * - [requestClose] is a one-way latch: the first request owns the played-out exit, every further
 *   Back/tap is consumed without restarting or truncating it;
 * - while closing, every new business action is refused ([allows]); the close affordance itself
 *   stays available through [requestClose];
 * - closing collapses the overflow submenu ([isMenuOpen]) and it cannot be reopened afterwards;
 * - a save that already started is settled by its own owner: it is never submitted twice and the
 *   visual exit neither repeats nor cancels it ([beginSave]/[finishSave]).
 *
 * The flags are Compose snapshot state so the overlay recomposes from the same values the tests
 * drive; the state machine itself has no Android or coroutine dependency.
 */
internal class ImagePreviewActionGate {
    var isClosing by mutableStateOf(false)
        private set

    var isSaving by mutableStateOf(false)
        private set

    var isMenuOpen by mutableStateOf(false)
        private set

    /**
     * Returns true exactly once per preview instance. The caller that receives true owns starting
     * the exit animation; false means "already closing, consume this Back without doing anything".
     */
    fun requestClose(): Boolean {
        if (isClosing) return false
        isClosing = true
        isMenuOpen = false
        return true
    }

    /** Whether a new business action may start. SAVE is also blocked while a save is in flight. */
    fun allows(action: ImagePreviewAction): Boolean = when (action) {
        ImagePreviewAction.SAVE -> !isClosing && !isSaving
        else -> !isClosing
    }

    fun openMenu() {
        if (allows(ImagePreviewAction.MORE_MENU)) isMenuOpen = true
    }

    fun closeMenu() {
        isMenuOpen = false
    }

    /**
     * Reserves the single in-flight gallery save. Must be paired with [finishSave] by the save
     * owner in a `finally`; a second submit while saving or after closing returns false so the
     * exit can never duplicate the write.
     */
    fun beginSave(): Boolean {
        if (!allows(ImagePreviewAction.SAVE)) return false
        isSaving = true
        isMenuOpen = false
        return true
    }

    fun finishSave() {
        isSaving = false
    }
}
