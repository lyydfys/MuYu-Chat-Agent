package com.muyuchat.mca.presentation.startup

import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.delay

/**
 * The composer's semantics contract, as set by `feature/chat`'s `ChatComposerScreens.kt`
 * (`Modifier.semantics { contentDescription = "消息输入框" }.testTag("chat.editor")`).
 * The probe only reads it; it never writes into the chat feature.
 */
private const val CHAT_EDITOR_TEST_TAG = "chat.editor"
private const val CHAT_EDITOR_LABEL = "消息输入框"

/**
 * Upper bound for waiting on the composer. After this the signal is recorded as incomplete; the
 * measurement script turns that into NOT_RUN rather than into a TTFD.
 */
private const val INPUT_READY_TIMEOUT_MS = 120_000L
private const val PROBE_INTERVAL_MS = 50L
private const val MAX_SCANNED_VIEWS = 64
private const val MAX_SCANNED_SEMANTICS_NODES = 4_000

/**
 * Production TTFD probe.
 *
 * Two observations, both one-shot, both reported through [TtfdSignal] on the fixed `McaTtfd` tag:
 *
 *  1. first drawn frame: `ViewTreeObserver.registerFrameCommitCallback` (API 29+, called after the
 *     first frame is committed) with an `OnDrawListener` fallback for API 26-28, registered when
 *     the decor view attaches (that is the window's observer, and it happens before the first
 *     traversal);
 *  2. actionable composer: the Compose semantics tree is sampled until a node with the composer's
 *     semantics exists, is not disabled, has real bounds and exposes `SetText`.
 *
 * The probe deliberately does not inject touch events: TTFD is defined at the semantics level
 * ("ready to accept input"), not as a synthetic tap. A real tap latency is a different metric.
 */
internal object TtfdProbe {
    private var installed = false

    /**
     * Installs the process-start origin and the first-frame probe. Call once from
     * `MainActivity.onCreate` with the window decor view. Idempotent across Activity recreation.
     */
    fun install(decorView: View) {
        if (installed) return
        installed = true
        val processStart = android.os.Process.getStartUptimeMillis().takeIf { it > 0L }
            ?: SystemClock.uptimeMillis()
        TtfdSignal.begin(processStart) { line -> Log.i(TtfdLog.TAG, line) }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.removeOnAttachStateChangeListener(this)
                registerFirstFrameProbe(view)
            }

            override fun onViewDetachedFromWindow(view: View) = Unit
        }
        if (decorView.isAttachedToWindow) {
            registerFirstFrameProbe(decorView)
        } else {
            decorView.addOnAttachStateChangeListener(attachListener)
        }
    }

    private fun registerFirstFrameProbe(view: View) {
        val observer = view.viewTreeObserver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val callback = object : Runnable {
                override fun run() {
                    TtfdSignal.noteFirstFrame(SystemClock.uptimeMillis())
                    runCatching { view.viewTreeObserver.unregisterFrameCommitCallback(this) }
                }
            }
            runCatching { observer.registerFrameCommitCallback(callback) }
        } else {
            observer.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    TtfdSignal.noteFirstFrame(SystemClock.uptimeMillis())
                    // Removing a draw listener during the draw pass is not allowed; defer it.
                    view.post { runCatching { view.viewTreeObserver.removeOnDrawListener(this) } }
                }
            })
        }
    }
}

/**
 * Compose-side half of the probe: samples the semantics tree on the main thread until the chat
 * composer is actionable, then records the second timestamp. Renders nothing.
 *
 * `MainActivity` adds exactly one call to this next to its existing content.
 */
@Composable
internal fun TtfdProbeHost() {
    val view = LocalView.current
    LaunchedEffect(view) {
        if (TtfdSignal.isComplete()) return@LaunchedEffect
        val deadline = SystemClock.uptimeMillis() + INPUT_READY_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (isChatComposerActionable(view)) {
                TtfdSignal.noteInputReady(
                    actionable = true,
                    atUptimeMs = SystemClock.uptimeMillis(),
                )
                return@LaunchedEffect
            }
            delay(PROBE_INTERVAL_MS)
        }
        TtfdSignal.noteProbeTimeout()
    }
}

/**
 * `AndroidComposeView` (the view behind `setContent`, i.e. `LocalView`) is Kotlin-`internal` in
 * compose-ui 1.11, so app code cannot name it. Its `getSemanticsOwner()` JVM method is public and
 * the repo already drives it that way in `ProductionUiSessionTest`; this is the same read-only
 * lookup, resolved by JVM name so no compile-time dependency on an internal Kotlin declaration is
 * created. The whole lookup is cached per view class by the JVM's own method table.
 */
private fun resolveSemanticsOwner(view: View): SemanticsOwner? {
    val pending = ArrayDeque<View>()
    pending.add(view)
    var visited = 0
    while (pending.isNotEmpty() && visited < MAX_SCANNED_VIEWS) {
        val current = pending.removeFirst()
        visited++
        semanticsOwnerOf(current)?.let { return it }
        if (current is ViewGroup) {
            for (index in 0 until current.childCount) pending.add(current.getChildAt(index))
        }
    }
    return null
}

private fun semanticsOwnerOf(view: View): SemanticsOwner? {
    val getter = view.javaClass.methods.firstOrNull { method ->
        method.name == "getSemanticsOwner" &&
            method.parameterCount == 0 &&
            SemanticsOwner::class.java.isAssignableFrom(method.returnType)
    } ?: return null
    return runCatching { getter.invoke(view) as? SemanticsOwner }.getOrNull()
}

private fun isChatComposerActionable(view: View): Boolean = runCatching {
    val owner = resolveSemanticsOwner(view) ?: return@runCatching false
    // The unmerged tree exposes the editor's own config; the merged tree is the fallback in case a
    // merge ancestor ever hides it from the raw tree walk.
    hasActionableEditor(owner.unmergedRootSemanticsNode) ||
        hasActionableEditor(owner.rootSemanticsNode)
}.getOrDefault(false)

private fun hasActionableEditor(root: SemanticsNode): Boolean {
    val pending = ArrayDeque<SemanticsNode>()
    pending.add(root)
    var visited = 0
    while (pending.isNotEmpty() && visited < MAX_SCANNED_SEMANTICS_NODES) {
        val node = pending.removeFirst()
        visited++
        if (isActionableEditor(node)) return true
        pending.addAll(node.children)
    }
    return false
}

private fun isActionableEditor(node: SemanticsNode): Boolean {
    val config = node.config
    if (config.contains(SemanticsProperties.Disabled)) return false
    val testTag = config.getOrNull(SemanticsProperties.TestTag)
    val described = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    val named = testTag == CHAT_EDITOR_TEST_TAG || described.any { it.contains(CHAT_EDITOR_LABEL) }
    if (!named) return false
    // Text entry is what makes the field actionable; a merely present, disabled placeholder is not
    // accepted.
    if (!config.contains(SemanticsActions.SetText)) return false
    val bounds = node.boundsInRoot
    return bounds.width > 0f && bounds.height > 0f
}
