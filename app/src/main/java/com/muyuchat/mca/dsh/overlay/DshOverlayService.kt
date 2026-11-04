package com.muyuchat.mca.dsh.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.muyuchat.core.designsystem.McaMascotMood
import com.muyuchat.core.dsh.contract.DshOverlayReplyPreview
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Events the window service reports to its process-local owner; all arrive on the main thread. */
internal interface DshOverlayWindowHost {
    fun onWindowAttached()

    fun onServiceStopped()

    fun onMarkTapped()

    fun onWindowRejected(failure: DshOverlayFailure)
}

/**
 * Process-level hand-off between the started service and [DshOverlayController]: one slot, replaced
 * by the controller on creation and cleared on [DshOverlayController.release].
 */
internal object DshOverlayWindowBus {
    @Volatile
    var host: DshOverlayWindowHost? = null
}

/**
 * Owner of exactly one `TYPE_APPLICATION_OVERLAY` window: the mascot mark, its reply bubble and the
 * compact status panel. Tap opens DSH, long press toggles the panel, drag moves the mark and persists
 * the position as a fraction of the screen, so rotation restores it inside the new bounds.
 *
 * The window is intentionally not focusable and not touch-modal: it never steals input from the app
 * below and never pulls MCA to the front by itself. Reply bubbles are pushed by
 * [DshOverlayHost] after its throttle gate accepted a turn boundary; the service only renders.
 *
 * Release: [removeWindow] is idempotent and runs on hide, rejection and [onDestroy]; the attached
 * state is reported only after `addView` succeeded.
 */
class DshOverlayService : Service() {

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ui = DshOverlayUiState()

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var composeHost: DshOverlayComposeHost? = null
    private var layout: WindowManager.LayoutParams? = null
    private var attached = false
    private var status: DshOverlayStatus = DshOverlayStatus.unavailable()
    private var settings: DshOverlaySettings = DshOverlaySettings()
    private var repliedReset: Runnable? = null
    private var bubbleHide: Runnable? = null

    private var density = 1f
    private var markPx = 0
    private var edgePx = 0
    private var screenWidthPx = 0
    private var screenHeightPx = 0
    private var markLeftPx = 0f
    private var markTopPx = 0f
    private var working = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        density = resources.displayMetrics.density
        settings = DshOverlayStore(this).loadSettings()
        applySettingsToUi(settings)
        ui.headline = status.headline
        ui.summary = status.capabilitySummary
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            status = statusFrom(intent)
            applyStatusTexts()
        }
        when {
            // System restart after a kill: only re-attach when the user still has it switched on.
            intent == null -> {
                val stored = DshOverlayStore(this).loadSettings()
                if (stored.enabled) {
                    settings = stored
                    applySettingsToUi(stored)
                    showWindow()
                } else {
                    stopSelf()
                }
            }

            intent.action == ACTION_SHOW -> showWindow()
            else -> applyStatusTexts()
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!attached) return
        measure()
        clampMarkPosition()
        updateWindowPosition()
    }

    override fun onDestroy() {
        cancelScheduled()
        removeWindow()
        scope.cancel()
        if (instance === this) instance = null
        DshOverlayWindowBus.host?.onServiceStopped()
        super.onDestroy()
    }

    /** Updates the status panel text on the main thread; safe from any thread. */
    fun applyStatus(next: DshOverlayStatus) {
        status = next
        onMain { applyStatusTexts() }
    }

    /** Panel visibility, decided by the controller after it reduced a long-press event. */
    fun setPanelExpanded(expanded: Boolean) {
        onMain {
            ui.panelVisible = expanded
            updateWindowPosition()
        }
    }

    /** Applies a changed appearance while the window is attached; safe from any thread. */
    fun applySettings(next: DshOverlaySettings) {
        val normalized = next.normalized()
        settings = normalized
        onMain {
            applySettingsToUi(normalized)
            if (attached) {
                measure()
                clampMarkPosition()
                updateWindowPosition()
                reloadCustomMark()
            }
        }
    }

    /** Shows one reply bubble and plays the one-shot "replied" cue; safe from any thread. */
    fun showReply(preview: DshOverlayReplyPreview) {
        onMain {
            // The finished bubble replaces any live text from the same turn.
            ui.streamText = null
            ui.streamLabel = null
            ui.streamActive = false
            ui.bubble = preview.text
            ui.bubbleLines = settings.appearance.bubbleLines.coerceIn(1, MAX_BUBBLE_LINES)
            ui.mood = McaMascotMood.REPLIED
            scheduleRepliedReset()
            scheduleBubbleHide()
            updateWindowPosition()
        }
    }

    /**
     * Renders the live stream state ("AI output on screen"); safe from any thread.
     *
     * Only [DshOverlayStream.Streaming] and [DshOverlayStream.ToolRunning] produce content. A finished
     * turn clears the live text here and lets [showReply] own the bubble, so there is exactly one
     * bubble on screen and the existing 2 s / 30 s / 6.5 s rules keep applying to it.
     */
    fun applyStream(state: DshOverlayStream) {
        onMain {
            when (state) {
                is DshOverlayStream.Streaming -> {
                    cancelBubbleHide()
                    ui.bubble = null
                    ui.streamActive = true
                    ui.streamText = if (bubbleAllowed()) state.text else null
                    ui.streamLabel = null
                    if (ui.mood != McaMascotMood.REPLIED) ui.mood = McaMascotMood.WORKING
                }

                is DshOverlayStream.ToolRunning -> {
                    cancelBubbleHide()
                    ui.bubble = null
                    ui.streamActive = true
                    ui.streamText = null
                    ui.streamLabel = if (bubbleAllowed()) state.label else null
                    if (ui.mood != McaMascotMood.REPLIED) ui.mood = McaMascotMood.WORKING
                }

                is DshOverlayStream.Finished, DshOverlayStream.Idle -> clearStream()
            }
            updateWindowPosition()
        }
    }

    /** Drops the live text but keeps the mark's working mood; the observer settles the mood. */
    private fun clearStream() {
        ui.streamActive = false
        ui.streamText = null
        ui.streamLabel = null
    }

    private fun cancelBubbleHide() {
        bubbleHide?.let { mainHandler.removeCallbacks(it) }
        bubbleHide = null
    }

    /** Bubbles are hidden by the user's own switches; the progress arc still shows the work. */
    private fun bubbleAllowed(): Boolean =
        settings.appearance.notifyReplies &&
            settings.appearance.bubbleLines > 0 &&
            settings.streamingEnabled

    /** Working mood while a DSH turn streams; the one-shot replied cue is never overwritten. */
    fun setWorking(isWorking: Boolean) {
        onMain {
            working = isWorking
            if (ui.mood != McaMascotMood.REPLIED) {
                ui.mood = if (isWorking) McaMascotMood.WORKING else McaMascotMood.IDLE
            }
        }
    }

    // ------------------------------------------------------------------ window lifecycle

    private fun showWindow() {
        if (!Settings.canDrawOverlays(this)) {
            reject(overlayPermissionFailure())
            return
        }
        val manager = windowManager
        if (manager == null) {
            reject(DshOverlayFailure.of(DshOverlayFailureCode.INTERNAL, "悬浮窗服务缺少 WindowManager"))
            return
        }
        if (attached) {
            applyStatusTexts()
            DshOverlayWindowBus.host?.onWindowAttached()
            return
        }
        measure()
        val host = DshOverlayComposeHost(this)
        val view = host.createView {
            DshOverlayWindowContent(
                state = ui,
                onTap = { DshOverlayIntents.openWorkspace(this) },
                onLongPress = { DshOverlayWindowBus.host?.onMarkTapped() },
                onBubbleTap = { DshOverlayIntents.openWorkspace(this) },
                onDrag = ::dragBy,
                onDragEnd = ::persistPosition
            )
        }
        markLeftPx = (edgePx + DshOverlayGeometry.offsetPx(settings.position.xFraction, travelX())).toFloat()
        markTopPx = (edgePx + DshOverlayGeometry.offsetPx(settings.position.yFraction, travelY())).toFloat()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            alpha = settings.appearance.opacity
        }
        layout = params
        updateWindowPosition(force = true)
        try {
            manager.addView(view, params)
            rootView = view
            composeHost = host
            attached = true
            host.attached()
            reloadCustomMark()
            DshOverlayWindowBus.host?.onWindowAttached()
        } catch (_: SecurityException) {
            host.release()
            reject(DshOverlayFailure.of(DshOverlayFailureCode.WINDOW_REJECTED, "系统拒绝了悬浮窗（权限）"))
        } catch (_: RuntimeException) {
            host.release()
            reject(DshOverlayFailure.of(DshOverlayFailureCode.WINDOW_REJECTED, "悬浮窗添加失败（窗口令牌）"))
        }
    }

    private fun removeWindow() {
        val manager = windowManager
        val view = rootView
        rootView = null
        attached = false
        layout = null
        if (manager != null && view != null) {
            try {
                manager.removeView(view)
            } catch (_: RuntimeException) {
                // Already gone (process death/system revocation); nothing to leak.
            }
        }
        composeHost?.release()
        composeHost = null
    }

    private fun reject(failure: DshOverlayFailure) {
        removeWindow()
        DshOverlayWindowBus.host?.onWindowRejected(failure)
        stopSelf()
    }

    // ------------------------------------------------------------------ geometry

    private fun measure() {
        val metrics = resources.displayMetrics
        screenWidthPx = metrics.widthPixels
        screenHeightPx = metrics.heightPixels
        density = metrics.density
        markPx = (ui.markSizeDp * density).roundToInt().coerceAtLeast(1)
        edgePx = (DshOverlayGeometry.EDGE_DP * density).roundToInt()
    }

    private fun travelX(): Int = DshOverlayGeometry.travelPx(screenWidthPx, markPx, edgePx)

    private fun travelY(): Int = DshOverlayGeometry.travelPx(screenHeightPx, markPx, edgePx)

    private fun bubbleBlockHeightPx(): Int =
        (((ui.bubbleLines.coerceIn(1, MAX_BUBBLE_LINES) * 20) + 32) * density).roundToInt()

    /** True while the mark carries any bubble content: a finished reply or the live stream text. */
    private fun hasContentBubble(): Boolean =
        ui.bubble != null || ui.streamText != null || ui.streamLabel != null

    private fun panelBlockHeightPx(): Int = ((2 * 18 + 16) * density).roundToInt()

    /**
     * Places the window so the mark stays exactly where the user left it while the bubble/panel grows
     * away from the nearest screen edge; the anchor switch happens at the screen middle.
     */
    private fun updateWindowPosition(force: Boolean = false) {
        val params = layout ?: return
        clampMarkPosition()
        val rightAnchored = markLeftPx + markPx / 2f >= screenWidthPx / 2f
        ui.alignEnd = rightAnchored
        val growthAbove = when {
            ui.panelVisible -> panelBlockHeightPx()
            hasContentBubble() -> bubbleBlockHeightPx()
            else -> 0
        }
        val bubbleBelow = growthAbove > 0 && markTopPx - growthAbove - edgePx < 0
        ui.bubbleBelow = bubbleBelow
        val gravity = (if (rightAnchored) Gravity.END else Gravity.START) or Gravity.TOP
        val x = if (rightAnchored) {
            (screenWidthPx - (markLeftPx + markPx)).roundToInt().coerceAtLeast(edgePx)
        } else {
            markLeftPx.roundToInt().coerceAtLeast(edgePx)
        }
        val y = if (bubbleBelow || growthAbove == 0) {
            markTopPx.roundToInt().coerceAtLeast(edgePx)
        } else {
            (markTopPx - growthAbove).roundToInt().coerceAtLeast(0)
        }
        params.gravity = gravity
        params.x = x
        params.y = y
        params.alpha = settings.appearance.opacity
        val view = rootView
        if (view != null && (force || attached)) {
            runCatching { windowManager?.updateViewLayout(view, params) }
        }
    }

    private fun clampMarkPosition() {
        markLeftPx = markLeftPx.coerceIn(edgePx.toFloat(), (edgePx + travelX()).toFloat())
        markTopPx = markTopPx.coerceIn(edgePx.toFloat(), (edgePx + travelY()).toFloat())
    }

    private fun dragBy(dx: Float, dy: Float) {
        if (!attached || !settings.appearance.draggable) return
        if (!dx.isFinite() || !dy.isFinite()) return
        markLeftPx = DshOverlayGeometry.fractionAfterDrag(
            currentFraction = DshOverlayGeometry.fractionForOffset(
                (markLeftPx - edgePx).roundToInt(),
                travelX()
            ),
            deltaPx = dx,
            travelPx = travelX()
        ) * travelX() + edgePx
        markTopPx = DshOverlayGeometry.fractionAfterDrag(
            currentFraction = DshOverlayGeometry.fractionForOffset(
                (markTopPx - edgePx).roundToInt(),
                travelY()
            ),
            deltaPx = dy,
            travelPx = travelY()
        ) * travelY() + edgePx
        updateWindowPosition()
    }

    private fun persistPosition() {
        val xFraction = DshOverlayGeometry.fractionForOffset(
            (markLeftPx - edgePx).roundToInt(),
            travelX()
        )
        val yFraction = DshOverlayGeometry.fractionForOffset(
            (markTopPx - edgePx).roundToInt(),
            travelY()
        )
        settings = settings.copy(position = DshOverlayPosition(xFraction, yFraction))
        DshOverlayStore(this).saveSettings(settings)
    }

    // ------------------------------------------------------------------ appearance

    private fun applySettingsToUi(next: DshOverlaySettings) {
        val appearance = next.appearance
        ui.mascot = com.muyuchat.core.dsh.contract.MascotId.fromId(appearance.mascotId).toMcaMascot()
        ui.markSizeDp = appearance.markSizeDp
        ui.bubbleLines = appearance.bubbleLines.coerceIn(0, MAX_BUBBLE_LINES)
        ui.draggable = appearance.draggable
        if (appearance.bubbleLines == 0 || !appearance.notifyReplies) ui.bubble = null
        // Turning live text off (or hiding bubbles) must drop the text already on screen; the
        // progress arc keeps showing that DSH is still working.
        if (!next.streamingEnabled || !bubbleAllowed()) {
            ui.streamText = null
            ui.streamLabel = null
        }
        if (!next.streamingEnabled) ui.streamActive = false
    }

    /** Decodes the user's image off the main thread; a failed decode keeps the vector fallback. */
    private fun reloadCustomMark() {
        val path = settings.appearance.customImagePath
            ?.takeIf { settings.appearance.mascotId.equals(CUSTOM_MASCOT_ID, ignoreCase = true) }
        if (path == null) {
            ui.customMark = null
            return
        }
        scope.launch {
            val bitmap = DshOverlayCustomImage.load(path)
            withContext(Dispatchers.Main) {
                ui.customMark = bitmap
            }
        }
    }

    private fun applyStatusTexts() {
        ui.headline = status.headline
        ui.summary = status.capabilitySummary
    }

    private fun scheduleRepliedReset() {
        repliedReset?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            repliedReset = null
            ui.mood = if (working) McaMascotMood.WORKING else McaMascotMood.IDLE
        }
        repliedReset = runnable
        mainHandler.postDelayed(runnable, REPLIED_CUE_MILLIS)
    }

    private fun scheduleBubbleHide() {
        bubbleHide?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            bubbleHide = null
            ui.bubble = null
            updateWindowPosition()
        }
        bubbleHide = runnable
        mainHandler.postDelayed(runnable, BUBBLE_VISIBLE_MILLIS)
    }

    private fun cancelScheduled() {
        repliedReset?.let { mainHandler.removeCallbacks(it) }
        bubbleHide?.let { mainHandler.removeCallbacks(it) }
        repliedReset = null
        bubbleHide = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    companion object {
        const val ACTION_SHOW = "com.muyuchat.mca.dsh.overlay.action.SHOW"

        /** How long the one-shot "replied" bounce lasts before the mark falls back to idle/working. */
        const val REPLIED_CUE_MILLIS = 800L

        /** How long a reply bubble stays before it hides itself. */
        const val BUBBLE_VISIBLE_MILLIS = 6_500L

        private const val MAX_BUBBLE_LINES = 5
        private const val CUSTOM_MASCOT_ID = "custom"

        private const val EXTRA_PHASE = "overlay.phase"
        private const val EXTRA_GENERATION = "overlay.generation"
        private const val EXTRA_ENGINE_ONLINE = "overlay.engineOnline"
        private const val EXTRA_SUMMARY = "overlay.summary"

        @Volatile
        internal var instance: DshOverlayService? = null
            private set

        fun intent(context: Context, action: String, status: DshOverlayStatus? = null): Intent =
            Intent(context, DshOverlayService::class.java).setAction(action).apply {
                if (status != null) {
                    putExtra(EXTRA_PHASE, status.runtimePhase)
                    putExtra(EXTRA_GENERATION, status.generation)
                    putExtra(EXTRA_ENGINE_ONLINE, status.engineOnline)
                    putExtra(EXTRA_SUMMARY, status.capabilitySummary)
                }
            }

        private fun statusFrom(intent: Intent): DshOverlayStatus = DshOverlayStatus.of(
            runtimePhase = intent.getStringExtra(EXTRA_PHASE),
            generation = intent.getLongExtra(EXTRA_GENERATION, 0L),
            engineOnline = intent.getBooleanExtra(EXTRA_ENGINE_ONLINE, false),
            capabilitySummary = intent.getStringExtra(EXTRA_SUMMARY)
        )
    }
}
