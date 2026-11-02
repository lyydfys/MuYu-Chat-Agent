package com.muyuchat.mca.dsh.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

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
 * Thin owner of exactly one `TYPE_APPLICATION_OVERLAY` window: a compact brand mark whose tap is
 * forwarded to the controller (the controller decides whether the panel opens), plus the panel the
 * controller fills. No WebView, no token, no Activity pull-to-front, not focusable.
 *
 * Release: [removeWindow] is idempotent and runs on hide, rejection and [onDestroy]; visibility is
 * reported only after `addView` succeeded.
 */
class DshOverlayService : Service() {

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    private var windowManager: WindowManager? = null
    private var rootView: View? = null
    private var panelView: LinearLayout? = null
    private var headlineView: TextView? = null
    private var summaryView: TextView? = null
    private var attached = false
    private var status: DshOverlayStatus = DshOverlayStatus.unavailable()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) status = statusFrom(intent)
        if (intent?.action == ACTION_SHOW) showWindow()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeWindow()
        windowManager = null
        if (instance === this) instance = null
        DshOverlayWindowBus.host?.onServiceStopped()
        super.onDestroy()
    }

    /** Updates the panel text on the main thread; safe from any thread. */
    fun applyStatus(next: DshOverlayStatus) {
        status = next
        onMain { applyTexts() }
    }

    /** Panel visibility, decided by the controller after it reduced a tap event. */
    fun setPanelExpanded(expanded: Boolean) {
        onMain { panelView?.visibility = if (expanded) View.VISIBLE else View.GONE }
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
            applyTexts()
            DshOverlayWindowBus.host?.onWindowAttached()
            return
        }
        val view = buildView()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(160)
        }
        try {
            manager.addView(view, params)
            rootView = view
            attached = true
            DshOverlayWindowBus.host?.onWindowAttached()
        } catch (_: SecurityException) {
            reject(DshOverlayFailure.of(DshOverlayFailureCode.WINDOW_REJECTED, "系统拒绝了悬浮窗（权限）"))
        } catch (_: RuntimeException) {
            reject(DshOverlayFailure.of(DshOverlayFailureCode.WINDOW_REJECTED, "悬浮窗添加失败（窗口令牌）"))
        }
    }

    private fun removeWindow() {
        val manager = windowManager ?: return
        val view = rootView ?: return
        rootView = null
        attached = false
        try {
            manager.removeView(view)
        } catch (_: RuntimeException) {
            // Already gone (process death/system revocation); nothing to leak.
        }
    }

    private fun reject(failure: DshOverlayFailure) {
        removeWindow()
        DshOverlayWindowBus.host?.onWindowRejected(failure)
        stopSelf()
    }

    private fun buildView(): View {
        val mark = TextView(this).apply {
            text = "🐋"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "DeepSeek Harness 悬浮窗"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BRAND_COLOR)
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { DshOverlayWindowBus.host?.onMarkTapped() }
        }
        val headline = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
        }
        val summary = TextView(this).apply {
            textSize = 10f
            setTextColor(PANEL_TEXT_COLOR)
        }
        headlineView = headline
        summaryView = summary
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(PANEL_COLOR)
            }
            addView(headline)
            addView(summary)
        }
        panelView = panel
        applyTexts()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(mark)
            addView(panel)
        }
    }

    private fun applyTexts() {
        headlineView?.text = status.headline
        summaryView?.text = status.capabilitySummary
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_SHOW = "com.muyuchat.mca.dsh.overlay.action.SHOW"

        private const val EXTRA_PHASE = "overlay.phase"
        private const val EXTRA_GENERATION = "overlay.generation"
        private const val EXTRA_ENGINE_ONLINE = "overlay.engineOnline"
        private const val EXTRA_SUMMARY = "overlay.summary"

        private const val BRAND_COLOR = 0xFF1F6FEB.toInt()
        private const val PANEL_COLOR = 0xE6172433.toInt()
        private const val PANEL_TEXT_COLOR = 0xFFB8C4D0.toInt()

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
