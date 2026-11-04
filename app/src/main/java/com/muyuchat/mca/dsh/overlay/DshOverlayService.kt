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
import com.muyuchat.core.dsh.contract.DshOverlayReplyPreview
import com.muyuchat.feature.dsh.workspace.device.DshCommandApproval
import com.muyuchat.feature.dsh.workspace.device.DshCommandApprovalDecision
import com.muyuchat.feature.dsh.workspace.device.DshCommandApprovalRequest
import kotlin.math.roundToInt

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
 * Owner of exactly one `TYPE_APPLICATION_OVERLAY` window: **the DSH whale ball**, its reply bubble,
 * the two-row action panel and the inline settings sheet.
 *
 * The ball is the same control as the in-app `DshFloatingBall` (white disc + whale glyph) and only
 * breathes; tap opens the action panel (返回 DSH / 停止后台运行), long press opens the settings sheet,
 * drag moves it and persists the position as a fraction of the screen.
 *
 * The window is intentionally not focusable and not touch-modal: it never steals input from the app
 * below and never pulls MCA to the front by itself. Reply bubbles are pushed by [DshOverlayHost] after
 * its throttle gate accepted a turn boundary; the service only renders.
 *
 * Release: [removeWindow] is idempotent and runs on hide, rejection and [onDestroy]; the attached state
 * is reported only after `addView` succeeded.
 */
class DshOverlayService : Service(), DshOverlayWindowActions {

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val ui = DshOverlayUiState()

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var composeHost: DshOverlayComposeHost? = null
    private var layout: WindowManager.LayoutParams? = null

    /** Read by the approval gate from a channel coroutine: volatile so it never answers stale. */
    @Volatile
    private var attached = false
    private var status: DshOverlayStatus = DshOverlayStatus.unavailable()
    private var settings: DshOverlaySettings = DshOverlaySettings()
    private var repliedReset: Runnable? = null
    private var bubbleHide: Runnable? = null
    private var noticeHide: Runnable? = null

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
            ui.panel = if (expanded) DshOverlayPanel.ACTIONS else DshOverlayPanel.NONE
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
            ui.bubbleLines = settings.bubbleLines.coerceIn(1, MAX_BUBBLE_LINES)
            ui.mood = DshOverlayBallMood.REPLIED
            scheduleRepliedReset()
            scheduleBubbleHide()
            updateWindowPosition()
        }
    }

    /**
     * Renders the live stream state ("AI output on screen"); safe from any thread.
     *
     * Only [DshOverlayStream.Streaming] and [DshOverlayStream.ToolRunning] produce content. A finished
     * turn clears the live text here and lets [showReply] own the bubble, so there is exactly one bubble
     * on screen and the existing throttle/de-dupe/hide rules keep applying to it.
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
                    if (ui.mood != DshOverlayBallMood.REPLIED) ui.mood = DshOverlayBallMood.WORKING
                }

                is DshOverlayStream.ToolRunning -> {
                    cancelBubbleHide()
                    ui.bubble = null
                    ui.streamActive = true
                    ui.streamText = null
                    ui.streamLabel = if (bubbleAllowed()) state.label else null
                    if (ui.mood != DshOverlayBallMood.REPLIED) ui.mood = DshOverlayBallMood.WORKING
                }

                is DshOverlayStream.Finished, DshOverlayStream.Idle -> clearStream()
            }
            updateWindowPosition()
        }
    }

    /** Working mood while a DSH turn streams; the one-shot replied cue is never overwritten. */
    fun setWorking(isWorking: Boolean) {
        onMain {
            working = isWorking
            if (ui.mood != DshOverlayBallMood.REPLIED) {
                ui.mood = if (isWorking) DshOverlayBallMood.WORKING else DshOverlayBallMood.IDLE
            }
        }
    }

    // ------------------------------------------------------------------ command approval

    /**
     * Shows the "DSH 想执行一条命令" card; called from the feature-side approval gate.
     *
     * Returns false when no window is attached: the gate then denies, so a prompt nobody can see never
     * turns into a permission.
     */
    fun showApproval(request: DshCommandApprovalRequest): Boolean {
        if (!attached) return false
        onMain {
            ui.approval = DshOverlayApprovalUi(
                id = request.id,
                commandMasked = request.commandMasked,
                deadlineMillis = request.deadlineMillis,
                timeoutMillis = request.timeoutMillis
            )
            ui.panel = DshOverlayPanel.NONE
            ui.bubble = null
            clearStream()
            cancelBubbleHide()
            updateWindowPosition()
        }
        return true
    }

    /** Removes the card for [id] (answer, timeout or cancellation); a newer card is left alone. */
    fun dismissApproval(id: Long) {
        onMain {
            if (ui.approval?.id != id) return@onMain
            ui.approval = null
            updateWindowPosition()
        }
    }

    /** Removes whatever card is on screen, e.g. when the user switches approval off. */
    fun dismissAnyApproval() {
        onMain {
            if (ui.approval == null) return@onMain
            ui.approval = null
            updateWindowPosition()
        }
    }

    /**
     * "允许一次" / "拒绝" pressed.
     *
     * The card is removed first so a second tap cannot answer twice (the queue is idempotent anyway), and
     * the decision is delivered to the feature-side gate, which resumes the waiting channel.
     */
    private fun decideApproval(allowOnce: Boolean) {
        val current = ui.approval ?: return
        ui.approval = null
        updateWindowPosition()
        DshCommandApproval.decide(
            current.id,
            if (allowOnce) {
                DshCommandApprovalDecision.ALLOW_ONCE
            } else {
                DshCommandApprovalDecision.DENY
            }
        )
    }

    // ------------------------------------------------------------------ window actions

    /** "返回 DSH": reuse the one EXTRA_OPEN_WORKSPACE launch path; report a failure visibly. */
    override fun returnToDsh() {
        if (DshOverlayIntents.openWorkspace(this)) return
        onMain {
            ui.panel = DshOverlayPanel.NONE
            ui.notice = "无法打开 DSH，请从通知或桌面打开"
            scheduleNoticeHide()
            updateWindowPosition()
        }
    }

    /** "停止后台运行": window removed, keep-alive service stopped, switch cleared. */
    override fun stopBackground() {
        DshOverlayHost.of(this).stopBackgroundFromUser()
    }

    /** Applies one inline-settings edit; the host persists it and re-applies it here. */
    override fun updateSettings(transform: (DshOverlaySettings) -> DshOverlaySettings) {
        val next = transform(settings).normalized()
        settings = next
        applySettingsToUi(next)
        updateWindowPosition()
        DshOverlayHost.of(this).applyWindowSettings(next)
    }

    /** Tap: toggle the two-row action panel. */
    fun toggleActionPanel() {
        onMain {
            ui.panel = if (ui.panel == DshOverlayPanel.ACTIONS) {
                DshOverlayPanel.NONE
            } else {
                DshOverlayPanel.ACTIONS
            }
            onPanelOpened()
        }
    }

    /** Long press: open the inline settings sheet. */
    fun openSettingsPanel() {
        onMain {
            ui.panel = if (ui.panel == DshOverlayPanel.SETTINGS) {
                DshOverlayPanel.NONE
            } else {
                DshOverlayPanel.SETTINGS
            }
            onPanelOpened()
        }
    }

    fun closePanel() {
        onMain {
            ui.panel = DshOverlayPanel.NONE
            updateWindowPosition()
        }
    }

    // ------------------------------------------------------------------ window lifecycle

    private fun onPanelOpened() {
        // A panel and a bubble compete for the same space above the ball; the panel wins.
        ui.bubble = null
        ui.streamText = null
        ui.streamLabel = null
        cancelBubbleHide()
        updateWindowPosition()
    }

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
                actions = this,
                onBallTap = { toggleActionPanel() },
                onBallLongPress = { openSettingsPanel() },
                onClosePanel = { closePanel() },
                onApprovalDecision = ::decideApproval,
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
        )
        layout = params
        updateWindowPosition(force = true)
        try {
            manager.addView(view, params)
            rootView = view
            composeHost = host
            attached = true
            host.attached()
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

    /** True while the ball carries any bubble content: a finished reply or the live stream text. */
    private fun hasContentBubble(): Boolean =
        ui.bubble != null || ui.streamText != null || ui.streamLabel != null

    /** The approval card is taller than a bubble (title + command + hint + buttons + countdown). */
    private fun approvalBlockHeightPx(): Int = (176 * density).roundToInt()

    /**
     * Places the window so the ball stays exactly where the user left it while bubbles grow away from
     * the nearest screen edge; a panel always grows downwards, so it never moves the ball.
     */
    private fun updateWindowPosition(force: Boolean = false) {
        val params = layout ?: return
        clampMarkPosition()
        val rightAnchored = markLeftPx + markPx / 2f >= screenWidthPx / 2f
        ui.alignEnd = rightAnchored
        val growthAbove = when {
            ui.panel != DshOverlayPanel.NONE -> 0
            ui.approval != null -> approvalBlockHeightPx()
            hasContentBubble() -> bubbleBlockHeightPx()
            else -> 0
        }
        val bubbleBelow = growthAbove > 0 && markTopPx - growthAbove - edgePx < 0
        ui.bubbleBelow = bubbleBelow || growthAbove == 0
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
        if (!attached || !settings.draggable) return
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
        ui.markSizeDp = next.markSizeDp.coerceIn(
            DshOverlaySettings.MIN_MARK_SIZE_DP,
            DshOverlaySettings.MAX_MARK_SIZE_DP
        )
        ui.bubbleLines = next.bubbleLines.coerceIn(0, MAX_BUBBLE_LINES)
        ui.draggable = next.draggable
        ui.notifyReplies = next.notifyReplies
        ui.streamingEnabled = next.streamingEnabled
        ui.commandApprovalEnabled = next.commandApprovalEnabled
        // Switching approval off must also clear a card that is already waiting: it can no longer be
        // answered, and the waiting channel has to be told DENY rather than left hanging.
        if (!next.commandApprovalEnabled) decideApproval(allowOnce = false)
        if (next.bubbleLines == 0 || !next.notifyReplies) ui.bubble = null
        // Turning live text off (or hiding bubbles) must drop the text already on screen; the progress
        // ring keeps showing that DSH is still working.
        if (!next.streamingEnabled || !bubbleAllowed()) {
            ui.streamText = null
            ui.streamLabel = null
        }
        if (!next.streamingEnabled) ui.streamActive = false
    }

    /** Bubbles are hidden by the user's own switches; the progress ring still shows the work. */
    private fun bubbleAllowed(): Boolean =
        settings.notifyReplies && settings.bubbleLines > 0 && settings.streamingEnabled

    private fun clearStream() {
        ui.streamActive = false
        ui.streamText = null
        ui.streamLabel = null
    }

    private fun cancelBubbleHide() {
        bubbleHide?.let { mainHandler.removeCallbacks(it) }
        bubbleHide = null
    }

    private fun applyStatusTexts() {
        ui.headline = status.headline
        ui.summary = status.capabilitySummary
    }

    private fun scheduleRepliedReset() {
        repliedReset?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            repliedReset = null
            ui.mood = if (working) DshOverlayBallMood.WORKING else DshOverlayBallMood.IDLE
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

    private fun scheduleNoticeHide() {
        noticeHide?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            noticeHide = null
            ui.notice = null
            updateWindowPosition()
        }
        noticeHide = runnable
        mainHandler.postDelayed(runnable, NOTICE_VISIBLE_MILLIS)
    }

    private fun cancelScheduled() {
        repliedReset?.let { mainHandler.removeCallbacks(it) }
        bubbleHide?.let { mainHandler.removeCallbacks(it) }
        noticeHide?.let { mainHandler.removeCallbacks(it) }
        repliedReset = null
        bubbleHide = null
        noticeHide = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    companion object {
        const val ACTION_SHOW = "com.muyuchat.mca.dsh.overlay.action.SHOW"

        /** How long the one-shot "replied" bounce lasts before the ball falls back to idle/working. */
        const val REPLIED_CUE_MILLIS = 800L

        /** How long a reply bubble stays before it hides itself. */
        const val BUBBLE_VISIBLE_MILLIS = 6_500L

        private const val NOTICE_VISIBLE_MILLIS = 4_000L
        private const val MAX_BUBBLE_LINES = DshOverlaySettings.MAX_BUBBLE_LINES

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
