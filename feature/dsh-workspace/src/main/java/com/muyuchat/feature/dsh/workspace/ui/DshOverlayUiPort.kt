package com.muyuchat.feature.dsh.workspace.ui

import com.muyuchat.core.dsh.contract.DshOverlayAppearance

/** Background treatment of the top streaming strip; mirrors the app-owned setting without a dependency. */
enum class DshOverlayStripSurfaceUi {
    LIGHT,
    DARK,
    TRANSLUCENT
}

/**
 * Editable state of the top streaming strip.
 *
 * The strip is a second, optional window (default off) that echoes the live turn at the top edge while
 * the floating ball keeps its own bubbles. `opacityPercent` is 35..100 and [lines] is 1..2; the app
 * clamps and persists the values, this type only carries them across the port boundary.
 */
data class DshOverlayStripUi(
    val enabled: Boolean = false,
    val surface: DshOverlayStripSurfaceUi = DshOverlayStripSurfaceUi.DARK,
    val opacityPercent: Int = 100,
    val lines: Int = 2,
    /** Keep the strip below the status bar instead of drawing under it. */
    val safeArea: Boolean = true
) {
    fun normalized(): DshOverlayStripUi = copy(
        opacityPercent = opacityPercent.coerceIn(MIN_OPACITY_PERCENT, MAX_OPACITY_PERCENT),
        lines = lines.coerceIn(MIN_LINES, MAX_LINES)
    )

    companion object {
        const val MIN_OPACITY_PERCENT = 35
        const val MAX_OPACITY_PERCENT = 100
        const val MIN_LINES = 1
        const val MAX_LINES = 2
    }
}

/**
 * Display snapshot of the app-outside display, as the workspace settings panel renders it.
 *
 * [permissionGranted] is the live `SYSTEM_ALERT_WINDOW` grant; [enabled] is the user's own switch, which
 * can be on while the grant is missing (the panel then explains how to grant it).
 *
 * The window is the DSH whale ball, so there is no mascot or custom-image state here any more.
 */
data class DshOverlayUiSnapshot(
    val enabled: Boolean = false,
    val permissionGranted: Boolean = false,
    val backgroundRunning: Boolean = false,
    /**
     * Live bubble switches only: `bubbleLines`, `notifyReplies` and `draggable`. The retired
     * mascot/size/opacity fields keep their contract defaults and are ignored by the renderer.
     */
    val appearance: DshOverlayAppearance = DshOverlayAppearance(),
    /**
     * Whether DSH's streaming text is echoed into the overlay while a turn runs.
     *
     * Persisted separately from the visual appearance because it is a "how loud" choice, not a
     * "how it looks" one; turning it off leaves the one-shot end-of-turn bubble untouched.
     */
    val streamingEnabled: Boolean = true,
    /**
     * Whether a dangerous device command must be confirmed on the floating window first (default on).
     *
     * Off does not authorise those commands: it restores the strict "refuse immediately" behaviour.
     */
    val commandApprovalEnabled: Boolean = true,
    /**
     * Whether the account-path reply probe is injected into the DSH page (default on).
     *
     * The probe only observes rendered text; turning it off removes the injected bridge entirely, so
     * the page is left untouched. Persisting and rendering the switch belong to the settings owner;
     * this feature defines the field and its default.
     */
    val accountReplyProbeEnabled: Boolean = true,
    /** The optional top streaming strip; off by default and fully independent of the ball. */
    val strip: DshOverlayStripUi = DshOverlayStripUi()
)

/**
 * The workspace's narrow port onto the app-owned overlay.
 *
 * The feature module renders and collects intents; the app owns the window service, the foreground
 * service and the persistence. Every mutating call returns `null` on success or a short Chinese
 * explanation to show in a snackbar, so a refusal is never silent.
 */
interface DshOverlayUiPort {
    fun snapshot(): DshOverlayUiSnapshot

    /** Enables/disables the whole app-outside display (window + background keep-alive). */
    fun setEnabled(enabled: Boolean): String?

    /** Persists the edited bubble switches and applies them to the live window, if attached. */
    fun saveAppearance(appearance: DshOverlayAppearance): String?

    /** The explicit "停止后台运行" intent: window removed, keep-alive service stopped. */
    fun stopBackground(): String?

    /**
     * Turns the live streaming echo on or off; persists immediately.
     *
     * Off means the overlay never shows in-progress text: it falls back to the single end-of-turn
     * bubble, which is what someone who only wants "done" notifications wants.
     */
    fun setStreamingEnabled(enabled: Boolean): String?

    /**
     * Persists the account-path reply probe switch; the DSH page is re-injected on the next entry.
     *
     * Defaulted (returns success and persists nothing) so an app implementation that has not wired the
     * preference yet still compiles and behaves exactly as before.
     */
    fun setAccountReplyProbeEnabled(enabled: Boolean): String? = null

    /**
     * Turns the dangerous-command confirmation on or off; persists immediately.
     *
     * Off does **not** allow those commands: the device policy then refuses them outright, exactly as
     * before this feature existed. Defaulted so an older app implementation keeps compiling.
     */
    fun setCommandApprovalEnabled(enabled: Boolean): String? = null

    /**
     * Turns the top streaming strip on/off; persists immediately and creates/removes only that window.
     *
     * Defaulted: an older app implementation without the strip simply persists nothing and the toggle
     * stays decorative instead of crashing.
     */
    fun setStripEnabled(enabled: Boolean): String? = null

    /** Persists the strip's visual options (surface, opacity, lines, safe area). Defaulted like above. */
    fun saveStripSettings(strip: DshOverlayStripUi): String? = null
}

