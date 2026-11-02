package com.muyuchat.core.dsh.vscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView

/**
 * Floating preview window for the virtual display.
 *
 * It is deliberately small and non-interactive: `TYPE_APPLICATION_OVERLAY`, not focusable, not
 * touchable, bottom-right, and capped to a fraction of the screen. It renders the newest frame the
 * owner admitted; a decode failure simply keeps the previous frame.
 *
 * The window is optional. Without `SYSTEM_ALERT_WINDOW` ([available] is false) the preview relay
 * keeps the frame in memory and the virtual screen keeps working — the app reports the preview as
 * unavailable instead of failing `android_vscreen_see`.
 *
 * Device status: overlay rendering is not exercised in this source-only environment (`NOT_RUN`).
 */
class AndroidDshVscreenPreviewWindow(
    private val context: Context,
    private val maxWidthDp: Int = 180,
    private val marginDp: Int = 12
) : DshVscreenPreviewWindow {

    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val decodeLock = Any()
    private var decodeThread: HandlerThread? = null
    private var decodeHandler: Handler? = null

    private var view: ImageView? = null
    private var rendered: Bitmap? = null
    private var attachedDisplayId: Int = -1

    override val available: Boolean
        get() = runCatching { Settings.canDrawOverlays(applicationContext) }.getOrDefault(false)

    override fun show(displayId: Int, frameWidth: Int, frameHeight: Int) {
        if (!available || attachedDisplayId == displayId) return
        mainHandler.post { attach(displayId, frameWidth, frameHeight) }
    }

    override fun render(frame: DshVscreenFrame) {
        if (!available) return
        decodingHandler().post {
            val decoded = decode(frame) ?: return@post
            mainHandler.post { apply(decoded) }
        }
    }

    override fun hide() {
        mainHandler.post { detach() }
    }

    /** Releases the decode thread. Call once from the app composition on a confirmed stop. */
    fun close() {
        hide()
        synchronized(decodeLock) {
            decodeThread?.quitSafely()
            decodeThread = null
            decodeHandler = null
        }
    }

    /** Created on the first frame so an unused preview keeps no background thread alive. */
    private fun decodingHandler(): Handler = synchronized(decodeLock) {
        decodeHandler ?: Handler(
            HandlerThread(DECODE_THREAD_NAME).apply { start() }.also { decodeThread = it }.looper
        ).also { decodeHandler = it }
    }

    private fun attach(displayId: Int, frameWidth: Int, frameHeight: Int) {
        if (view != null) return
        val metrics = applicationContext.resources.displayMetrics
        val width = minOf(
            (metrics.widthPixels * MAX_SCREEN_FRACTION).toInt().coerceAtLeast(dp(96)),
            dp(maxWidthDp)
        )
        val height = if (frameWidth > 0 && frameHeight > 0) {
            (width.toDouble() * frameHeight / frameWidth).toInt().coerceAtLeast(dp(96))
        } else {
            width * 2
        }
        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(marginDp)
            y = dp(marginDp)
        }
        val image = ImageView(applicationContext).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(BACKGROUND_COLOR)
        }
        val windowManager = applicationContext.getSystemService(WindowManager::class.java)
        if (windowManager == null) return
        try {
            windowManager.addView(image, params)
            view = image
            attachedDisplayId = displayId
        } catch (refused: RuntimeException) {
            // A refused overlay (permission revoked mid-session) leaves the preview buffer intact.
        }
    }

    private fun apply(bitmap: Bitmap) {
        val target = view
        if (target == null) {
            bitmap.recycle()
            return
        }
        val previous = rendered
        rendered = bitmap
        target.setImageBitmap(bitmap)
        previous?.let { stale -> if (stale !== bitmap) stale.recycle() }
    }

    private fun detach() {
        val target = view ?: return
        view = null
        attachedDisplayId = -1
        val windowManager = applicationContext.getSystemService(WindowManager::class.java)
        runCatching { windowManager?.removeView(target) }
        rendered?.let { stale -> runCatching { stale.recycle() } }
        rendered = null
    }

    private fun decode(frame: DshVscreenFrame): Bitmap? = try {
        BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.size)
    } catch (failure: RuntimeException) {
        null
    } catch (failure: OutOfMemoryError) {
        null
    }

    private fun dp(value: Int): Int =
        (value * applicationContext.resources.displayMetrics.density).toInt()

    private companion object {
        const val DECODE_THREAD_NAME = "dsh-vscreen-preview"
        const val MAX_SCREEN_FRACTION = 0.45
        const val BACKGROUND_COLOR = 0xCC000000.toInt()
    }
}
