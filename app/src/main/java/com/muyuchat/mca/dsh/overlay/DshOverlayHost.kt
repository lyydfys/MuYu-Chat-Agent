package com.muyuchat.mca.dsh.overlay

import android.content.Context
import android.net.Uri
import android.provider.Settings
import com.muyuchat.api.dsh.gateway.DshGatewayBackend
import com.muyuchat.core.dsh.contract.DshOverlayAppearance
import com.muyuchat.core.dsh.contract.DshOverlayReplyPreview
import com.muyuchat.core.dsh.contract.MascotId
import com.muyuchat.feature.dsh.workspace.ui.DshOverlayUiPort
import com.muyuchat.feature.dsh.workspace.ui.DshOverlayUiSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Typed seams the app composition lends to the overlay.
 *
 * Both are app-lifetime facts, not UI state: "make sure the one DSH host runs" is a call into the
 * existing [com.muyuchat.core.dsh.contract.DshRuntimeClient], never a second runtime. [None] is the
 * honest state before the composition installed them (for example a service recreated by the system
 * before any Activity existed): the overlay still works, it just cannot restart the host by itself.
 */
internal class DshOverlayRuntimeHooks(val ensureStarted: () -> Unit = {}) {
    companion object {
        val None = DshOverlayRuntimeHooks()
    }
}

/**
 * Process-level owner of the app-outside display: one window controller, one appearance store, one
 * reply gate, and the bridge to the keep-alive service.
 *
 * It is the single writer of the overlay preference document, and the only place that decides whether
 * a user action may turn the window on. The `:dsh` runtime is never restarted here — [ensureStarted]
 * is the injected call into the existing runtime client, so the gateway port, the host process and the
 * capability bridge keep their single owner.
 *
 * The capability bridge (`/overlay`) and the workspace panel share [controller]: the controller holds
 * the one process-level window-bus slot, so a second instance would steal the first one's callbacks.
 */
internal class DshOverlayHost private constructor(private val appContext: Context) : DshOverlayUiPort {

    private val store = DshOverlayStore(appContext)
    private val gate = DshOverlayBubbleGate()

    /** Live status provider of the DSH runtime, installed by the capability assembly when it exists. */
    @Volatile
    internal var statusProvider: DshOverlayStatusProvider = DshOverlayStatusProvider.Unavailable

    @Volatile
    private var runtime: DshOverlayRuntimeHooks = DshOverlayRuntimeHooks.None

    private var runtimeBound = false
    private var restoreAttempted = false

    internal val controller: DshOverlayController = DshOverlayController(
        context = appContext,
        statusProvider = { statusProvider.status() },
        permissionCheck = { DshOverlayHost.canDrawOverlays(appContext) }
    )

    fun installRuntimeHooks(hooks: DshOverlayRuntimeHooks) {
        runtime = hooks
    }

    /**
     * One-time binding of the app composition (runtime hook install + window re-attach).
     *
     * Called from the ViewModel's overlay port getter, which may be read more than once: the install
     * and the re-attach run exactly once per process, so a second read cannot restart the host or
     * re-show a window the user just hid.
     */
    internal fun bindRuntime(hooks: DshOverlayRuntimeHooks): DshOverlayHost {
        val shouldRestore = synchronized(this) {
            if (!runtimeBound) {
                runtime = hooks
                runtimeBound = true
            }
            if (restoreAttempted) {
                false
            } else {
                restoreAttempted = true
                true
            }
        }
        if (shouldRestore) restoreIfEnabled()
        return this
    }

    /** Wraps the existing DSH gateway backend with the one reply-observation decorator. */
    internal fun observeGateway(delegate: DshGatewayBackend): DshGatewayBackend = DshOverlayReplyObserver(
        delegate = delegate,
        onBusy = { DshOverlayService.instance?.setWorking(true) },
        onIdle = { DshOverlayService.instance?.setWorking(false) },
        onReply = ::onReply
    )

    fun ensureRuntimeStarted() {
        runtime.ensureStarted()
    }

    /**
     * Re-attaches the window after the process was recreated (foreground-service restart or the user
     * coming back to the DSH page) when the switch is still on and the grant still exists.
     */
    fun restoreIfEnabled() {
        val settings = store.loadSettings()
        if (!settings.enabled) return
        if (!canDrawOverlays(appContext)) return
        if (!DshBackgroundService.start(appContext)) return
        ensureRuntimeStarted()
        controller.show()
    }

    /** Reply boundary accepted by the transport; the gate decides whether it becomes a bubble. */
    private fun onReply(preview: DshOverlayReplyPreview) {
        val settings = store.loadSettings()
        if (!settings.enabled) return
        val decision = gate.decide(preview, settings.appearance, System.currentTimeMillis())
        if (decision != DshOverlayBubbleDecision.SHOW) return
        DshOverlayService.instance?.showReply(preview)
    }

    // ------------------------------------------------------------------ DshOverlayUiPort

    override fun snapshot(): DshOverlayUiSnapshot {
        val settings = store.loadSettings()
        val appearance = settings.appearance
        val customPath = appearance.customImagePath
        return DshOverlayUiSnapshot(
            enabled = settings.enabled,
            permissionGranted = canDrawOverlays(appContext),
            backgroundRunning = DshBackgroundService.isRunning,
            appearance = appearance,
            // A metadata check only (no content read): the panel explains a missing file instead of
            // silently showing the vector fallback.
            customImageMissing = customPath != null && !DshOverlayCustomImage.exists(customPath)
        )
    }

    override fun setEnabled(enabled: Boolean): String? {
        val settings = store.loadSettings()
        if (!enabled) {
            store.saveSettings(settings.copy(enabled = false))
            controller.hide()
            DshBackgroundService.stop(appContext)
            gate.reset()
            return null
        }
        if (!canDrawOverlays(appContext)) {
            store.saveSettings(settings.copy(enabled = false))
            return "请先在系统设置中允许 MCA 显示在其他应用上层"
        }
        store.saveSettings(settings.copy(enabled = true))
        if (!DshBackgroundService.start(appContext)) {
            store.saveSettings(settings.copy(enabled = false))
            return "无法启动后台保持服务，App 外显示未开启"
        }
        ensureRuntimeStarted()
        gate.reset()
        val failure = controller.show()
        if (failure != null) {
            store.saveSettings(settings.copy(enabled = false))
            DshBackgroundService.stop(appContext)
            return failure.message
        }
        return null
    }

    override fun saveAppearance(appearance: DshOverlayAppearance): String? {
        val settings = store.loadSettings().copy(appearance = appearance)
        store.saveSettings(settings)
        DshOverlayService.instance?.applySettings(store.loadSettings())
        return null
    }

    override suspend fun importCustomImage(uri: Uri): String? {
        val path = withContext(Dispatchers.IO) { DshOverlayCustomImage.import(appContext, uri) }
            ?: return "这张图片无法读取，请换一张 JPG/PNG 图片"
        val settings = store.loadSettings()
        val next = settings.copy(
            appearance = settings.appearance.copy(
                mascotId = MascotId.CUSTOM.id,
                customImagePath = path
            )
        )
        store.saveSettings(next)
        DshOverlayService.instance?.applySettings(next)
        return null
    }

    override fun stopBackground(): String? = setEnabled(false)

    /** Notification action path: the user explicitly stops the background keep-alive. */
    internal fun stopBackgroundFromUser() {
        setEnabled(false)
    }

    companion object {
        @Volatile
        private var instance: DshOverlayHost? = null

        fun of(context: Context): DshOverlayHost {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: DshOverlayHost(context.applicationContext).also { instance = it }
            }
        }

        /** The one process-wide window controller, shared with the capability bridge. */
        fun controllerFor(context: Context): DshOverlayController = of(context).controller

        fun canDrawOverlays(context: Context): Boolean =
            runCatching { Settings.canDrawOverlays(context.applicationContext) }.getOrDefault(false)
    }
}
