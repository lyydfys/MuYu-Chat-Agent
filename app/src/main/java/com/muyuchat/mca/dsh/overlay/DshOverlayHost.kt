package com.muyuchat.mca.dsh.overlay

import android.content.Context
import android.provider.Settings
import com.muyuchat.api.dsh.gateway.DshGatewayBackend
import com.muyuchat.core.dsh.contract.DshOverlayAppearance
import com.muyuchat.core.dsh.contract.DshOverlayReplyPreview
import com.muyuchat.feature.dsh.workspace.device.DshCommandApproval
import com.muyuchat.feature.dsh.workspace.device.DshCommandApprovalPresenter
import com.muyuchat.feature.dsh.workspace.device.DshCommandApprovalRequest
import com.muyuchat.feature.dsh.workspace.ui.DshOverlayUiPort
import com.muyuchat.feature.dsh.workspace.ui.DshOverlayUiSnapshot

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
 * Process-level owner of the app-outside display: one window controller, one settings store, one reply
 * gate, one stream presenter, and the bridge to the keep-alive service.
 *
 * It is the single writer of the overlay preference document, and the only place that decides whether a
 * user action may turn the window on. The `:dsh` runtime is never restarted here — [ensureStarted] is
 * the injected call into the existing runtime client, so the gateway port, the host process and the
 * capability bridge keep their single owner.
 *
 * The capability bridge (`/overlay`) and the in-app settings panel share [controller]: the controller
 * holds the one process-level window-bus slot, so a second instance would steal the first one's
 * callbacks.
 */
internal class DshOverlayHost private constructor(private val appContext: Context) : DshOverlayUiPort {

    private val store = DshOverlayStore(appContext)
    private val gate = DshOverlayBubbleGate()

    /**
     * Cached "may the window show live text right now" fact.
     *
     * The observer asks this on every delta, so it must not decode the preference document per chunk.
     * It is recomputed on every write the host performs (enable/disable, settings save, restore), which
     * are the only places the answer can change.
     */
    @Volatile
    private var streamingAllowed: Boolean = false

    /**
     * One presenter per process: it owns the streaming state machine between the gateway and the window.
     * The window is looked up on each push because the service may be attached or not.
     */
    private val streamPresenter = DshOverlayStreamPresenter(
        onState = { state -> DshOverlayService.instance?.applyStream(state) },
        streamingEnabled = { streamingAllowed }
    )

    /**
     * The window half of the dangerous-command approval: feature-side channels ask
     * [com.muyuchat.feature.dsh.workspace.device.DshCommandApproval] and this presenter renders the card.
     *
     * [present] returns the service's own answer, so "no window attached" is a refused card (and the
     * gate then denies) instead of a prompt nobody can see.
     */
    private val approvalPresenter = object : DshCommandApprovalPresenter {
        override fun present(request: DshCommandApprovalRequest): Boolean =
            DshOverlayService.instance?.showApproval(request) ?: false

        override fun dismiss(id: Long) {
            DshOverlayService.instance?.dismissApproval(id)
        }
    }

    init {
        refreshStreamingAllowed()
        // Wired here (one process, one host) so the device channels can ask the same window that shows
        // reply bubbles. The default feature-side state is "no presenter, disabled" = strict refusal.
        DshCommandApproval.installPresenter(approvalPresenter)
        DshCommandApproval.setEnabledProvider {
            val settings = store.loadSettings()
            settings.enabled && settings.commandApprovalEnabled
        }
    }

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
     * Called from the ViewModel's overlay port getter, which may be read more than once: the install and
     * the re-attach run exactly once per process, so a second read cannot restart the host or re-show a
     * window the user just hid.
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
        onReply = ::onReply,
        stream = streamPresenter,
        streamingEnabled = { streamingAllowed }
    )

    /** Recomputes [streamingAllowed] after any write that can change it. */
    private fun refreshStreamingAllowed() {
        val settings = store.loadSettings()
        streamingAllowed = settings.enabled && settings.streamingEnabled
        if (!streamingAllowed) streamPresenter.reset()
    }

    fun ensureRuntimeStarted() {
        runtime.ensureStarted()
    }

    /**
     * Re-attaches the window after the process was recreated (foreground-service restart or the user
     * coming back to the DSH page) when the switch is still on and the grant still exists.
     */
    fun restoreIfEnabled() {
        val settings = store.loadSettings()
        refreshStreamingAllowed()
        if (!settings.enabled) return
        if (!canDrawOverlays(appContext)) return
        if (!DshBackgroundService.start(appContext)) return
        ensureRuntimeStarted()
        controller.show()
    }

    /** Reply boundary accepted by the transport; the gate decides whether it becomes a bubble. */
    private fun onReply(preview: DshOverlayReplyPreview) {
        val settings = store.loadSettings()
        lastGatewayReplyAtMillis = System.currentTimeMillis()
        if (!settings.enabled) return
        val decision = gate.decide(preview, settings.toOverlayAppearance(), System.currentTimeMillis())
        if (decision != DshOverlayBubbleDecision.SHOW) return
        DshOverlayService.instance?.showReply(preview)
    }

    /**
     * A reply observed somewhere other than the MCA gateway: the DSH page's own DOM, which is the
     * only signal available when the user is signed in with an official account that talks to
     * upstream directly.
     *
     * It reuses [onReply] so throttle, de-duplication and auto-hide are identical; the text simply
     * arrives as page text and is clipped to the preview budget by [DshOverlayReplyPreview.of].
     */
    internal fun publishAccountReply(text: String) {
        val preview = DshOverlayReplyPreview.of(
            sessionId = null,
            reply = text,
            atEpochMs = System.currentTimeMillis()
        ) ?: return
        onReply(preview)
    }

    /** Wall clock of the last gateway reply, used to suppress a duplicate DOM observation. */
    @Volatile
    internal var lastGatewayReplyAtMillis: Long? = null
        private set

    /** Persists an edit made inside the system window's own settings sheet. */
    internal fun applyWindowSettings(settings: DshOverlaySettings) {
        store.saveSettings(settings)
        refreshStreamingAllowed()
        DshOverlayService.instance?.applySettings(settings)
    }

    // ------------------------------------------------------------------ DshOverlayUiPort

    override fun snapshot(): DshOverlayUiSnapshot {
        val settings = store.loadSettings()
        return DshOverlayUiSnapshot(
            enabled = settings.enabled,
            permissionGranted = canDrawOverlays(appContext),
            backgroundRunning = DshBackgroundService.isRunning,
            appearance = settings.toOverlayAppearance(),
            streamingEnabled = settings.streamingEnabled,
            commandApprovalEnabled = settings.commandApprovalEnabled
        )
    }

    override fun setEnabled(enabled: Boolean): String? {
        val settings = store.loadSettings()
        if (!enabled) {
            store.saveSettings(settings.copy(enabled = false))
            refreshStreamingAllowed()
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
        refreshStreamingAllowed()
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

    /**
     * Persists the bubble-related part of the in-app appearance editor.
     *
     * The contract type still carries mascot/size/opacity fields for stored-config compatibility; they
     * are no longer rendered (the window is the DSH whale ball) and are deliberately not written back as
     * new choices.
     */
    override fun saveAppearance(appearance: DshOverlayAppearance): String? {
        val settings = store.loadSettings().copy(
            bubbleLines = appearance.normalized().bubbleLines,
            notifyReplies = appearance.notifyReplies,
            draggable = appearance.draggable
        )
        store.saveSettings(settings)
        refreshStreamingAllowed()
        DshOverlayService.instance?.applySettings(settings)
        return null
    }

    override fun setStreamingEnabled(enabled: Boolean): String? {
        val settings = store.loadSettings().copy(streamingEnabled = enabled)
        store.saveSettings(settings)
        refreshStreamingAllowed()
        DshOverlayService.instance?.applySettings(settings)
        return null
    }

    /**
     * "命令需要确认" switch.
     *
     * Off does not authorise dangerous commands: the feature-side gate sees a disabled provider and
     * denies every request immediately, which is the previous strict `policy_blocked` behaviour.
     */
    override fun setCommandApprovalEnabled(enabled: Boolean): String? {
        val settings = store.loadSettings().copy(commandApprovalEnabled = enabled)
        store.saveSettings(settings)
        if (!enabled) {
            // A card already on screen must not stay answerable after the switch went off.
            DshOverlayService.instance?.dismissAnyApproval()
        }
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
