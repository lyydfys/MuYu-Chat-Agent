package com.muyuchat.core.dsh.a11y.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.muyuchat.core.dsh.a11y.policy.A11yCommand
import com.muyuchat.core.dsh.a11y.policy.A11yCoord
import com.muyuchat.core.dsh.a11y.policy.A11yEngine
import com.muyuchat.core.dsh.a11y.policy.A11yGlobalActionResult
import com.muyuchat.core.dsh.a11y.policy.A11yInputResult
import com.muyuchat.core.dsh.a11y.policy.A11yLimits
import com.muyuchat.core.dsh.a11y.policy.A11yNodeRef
import com.muyuchat.core.dsh.a11y.policy.A11yOutcome
import com.muyuchat.core.dsh.a11y.policy.A11yPlanResult
import com.muyuchat.core.dsh.a11y.policy.A11yPoint
import com.muyuchat.core.dsh.a11y.policy.A11yRejection
import com.muyuchat.core.dsh.a11y.policy.A11yScreenGeometry
import com.muyuchat.core.dsh.a11y.policy.A11yScrollDirection
import com.muyuchat.core.dsh.a11y.policy.A11yScrollResult
import com.muyuchat.core.dsh.a11y.policy.A11yServiceStatus
import com.muyuchat.core.dsh.a11y.policy.A11yTapResult
import com.muyuchat.core.dsh.a11y.policy.A11yTouchAction
import com.muyuchat.core.dsh.a11y.policy.A11yTouchDispatchResult
import com.muyuchat.core.dsh.a11y.policy.A11yTreeSnapshot
import com.muyuchat.core.dsh.a11y.policy.A11yTreePolicy
import com.muyuchat.core.dsh.a11y.policy.A11yTouchTracker
import com.muyuchat.core.dsh.a11y.policy.DshA11yCapabilityRoutes
import com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode
import com.muyuchat.core.dsh.contract.capability.DshCapabilityRouteTable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Process-local singleton that owns the live accessibility service and executes typed commands.
 *
 * Ownership / state:
 * - created lazily by [DshA11yService.controller]; released only when the process dies;
 * - [attach]/[detach] are driven exclusively by the service lifecycle callbacks on the main thread;
 * - every command runs on the main looper (the framework's gesture/screenshot callbacks require a
 *   Handler and node handles must not be shared across threads), bounded by a per-command timeout;
 * - the touch state is owned by [A11yTouchTracker]; cancellation, timeout and detach lift every
 *   finger that the tracker still holds, so no touch survives a failed request.
 *
 * This class is the module's engine: `DshA11yCapabilityRoutes(controller)` turns it into the
 * platform route table. Nothing here trusts a request after the target window changed: node refs
 * carry the tree generation, coordinates are resolved against the live display, and an explicit
 * `package` guard is compared with the foreground app before the action.
 */
class DshA11yController private constructor() : A11yEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchTracker = A11yTouchTracker()

    @Volatile
    private var attached: DshA11yService? = null

    @Volatile
    private var generation: Long = 0L

    @Volatile
    private var lastSnapshot: A11yTreeSnapshot? = null

    @Volatile
    private var foregroundPackage: String = ""

    @Volatile
    private var windowId: Int = -1

    @Volatile
    private var lastGeometry: A11yScreenGeometry? = null

    /** True only while the user has the service enabled and the system has bound it. */
    val isRunning: Boolean
        get() = attached != null

    /** Convenience for the app composition root; equivalent to wrapping this engine. */
    fun routes(): DshCapabilityRouteTable = DshA11yCapabilityRoutes(this).routeTable()

    internal fun attach(service: DshA11yService) {
        attached = service
        invalidateTree()
        lastGeometry = null
        touchTracker.releaseAll()
    }

    internal fun detach(service: DshA11yService) {
        if (attached !== service) return
        attached = null
        invalidateTree()
        lastGeometry = null
        touchTracker.releaseAll()
    }

    internal fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        event.packageName?.toString()?.takeIf { it.isNotBlank() }?.let { foregroundPackage = it }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                windowId = event.windowId
                invalidateTree()
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                windowId = event.windowId
                invalidateTree()
            }
        }
    }

    internal fun onConfigurationChanged() {
        invalidateTree()
        releaseHeldFingers()
    }

    internal fun onInterrupt() {
        touchTracker.releaseAll()
    }

    override suspend fun execute(command: A11yCommand): A11yOutcome {
        val service = attached ?: return A11yOutcome.Rejected(A11yRejection.notEnabled())
        return try {
            withTimeout(timeoutFor(command)) {
                withContext(Dispatchers.Main.immediate) { dispatch(service, command) }
            }
        } catch (timedOut: TimeoutCancellationException) {
            withContext(NonCancellable + Dispatchers.Main.immediate) { releaseHeldFingers() }
            A11yOutcome.Rejected(A11yRejection.timedOut("accessibility request timed out"))
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.Main.immediate) { releaseHeldFingers() }
            throw cancelled
        } catch (failure: Throwable) {
            Log.w(TAG, "accessibility command ${commandName(command)} failed: ${failure.javaClass.simpleName}")
            A11yOutcome.Rejected(A11yRejection.internal("accessibility command failed"))
        }
    }

    private suspend fun dispatch(service: DshA11yService, command: A11yCommand): A11yOutcome {
        if (attached !== service) return A11yOutcome.Rejected(A11yRejection.notEnabled())
        val geometry = refreshGeometry(service)
        return when (command) {
            A11yCommand.Status -> status(service)
            A11yCommand.Dump -> dump(service)
            A11yCommand.TouchStatus -> A11yOutcome.TouchStatus(touchTracker.report(geometry))
            A11yCommand.TouchRelease -> releaseAllFingers(service)
            A11yCommand.Back -> A11yOutcome.GlobalAction(
                A11yGlobalActionResult(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            )

            A11yCommand.Home -> A11yOutcome.GlobalAction(
                A11yGlobalActionResult(service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
            )

            is A11yCommand.Tap -> tap(service, command, geometry)
            is A11yCommand.Input -> input(service, command)
            is A11yCommand.Scroll -> scroll(service, command)
            is A11yCommand.Screenshot -> DshA11yScreenshotCapture.capture(
                service = service,
                displayId = command.displayId,
                grid = command.grid,
                geometry = geometry,
                mainHandler = mainHandler
            )

            is A11yCommand.Swipe -> executeOps(
                service,
                listOf(
                    com.muyuchat.core.dsh.a11y.policy.A11yStrokeOp.Swipe(
                        finger = command.finger ?: -1,
                        from = command.from,
                        to = command.to,
                        durationMs = command.durationMs
                    )
                ),
                geometry,
                command.expectedPackage
            )

            is A11yCommand.Hold -> executeOps(
                service,
                listOf(
                    com.muyuchat.core.dsh.a11y.policy.A11yStrokeOp.Hold(
                        finger = command.finger ?: -1,
                        point = command.target,
                        durationMs = command.durationMs
                    )
                ),
                geometry,
                command.expectedPackage
            )

            is A11yCommand.Touch -> {
                val ops = touchOps(command)
                    ?: return A11yOutcome.Rejected(
                        A11yRejection.invalid("touch ${command.action.name.lowercase()} needs x/y or fx/fy")
                    )
                executeOps(service, ops, geometry, command.expectedPackage)
            }

            is A11yCommand.Gesture -> executeOps(service, command.ops, geometry, command.expectedPackage)
        }
    }

    // ------------------------------------------------------------------ status / dump

    private fun status(service: DshA11yService): A11yOutcome = A11yOutcome.Status(
        A11yServiceStatus(
            running = attached === service,
            packageName = currentPackage(service),
            nodeCount = DshA11yTreeReader.countNodes(service),
            apiLevel = Build.VERSION.SDK_INT,
            canScreenshot = Build.VERSION.SDK_INT >= 30,
            windowId = windowId
        )
    )

    private fun dump(service: DshA11yService): A11yOutcome {
        invalidateTree()
        val capture = DshA11yTreeReader.capture(service, generation, windowId)
        lastSnapshot = capture.snapshot
        DshA11yTreeReader.recycle(capture)
        return A11yOutcome.Dump(capture.snapshot)
    }

    // ------------------------------------------------------------------ tap

    private suspend fun tap(
        service: DshA11yService,
        command: A11yCommand.Tap,
        geometry: A11yScreenGeometry
    ): A11yOutcome {
        checkTarget(service, command.expectedPackage)?.let { return A11yOutcome.Rejected(it) }
        command.ref?.let { return tapByRef(service, it, geometry) }

        val point = command.target?.resolve(geometry)
        if (command.text == null && command.desc == null && point == null) {
            return A11yOutcome.Rejected(A11yRejection.invalid("tap target is outside the current screen"))
        }
        val capture = DshA11yTreeReader.capture(service, generation, windowId)
        try {
            val method = if (command.text != null || command.desc != null) "node-text" else "node-coord"
            val match = if (command.text != null || command.desc != null) {
                A11yTreePolicy.findBestMatch(capture.snapshot, command.text ?: command.desc)
            } else {
                point?.let { A11yTreePolicy.nodeAtPoint(capture.snapshot, it) }
            }
            if (match != null) {
                val handle = capture.handles.getOrNull(match.index)
                val clicked = handle != null && runCatching {
                    handle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                if (clicked) {
                    return A11yOutcome.Tap(A11yTapResult(true, method, null))
                }
            }
            if (point != null) {
                return gestureTapAt(service, point, geometry)
            }
            return A11yOutcome.Tap(
                A11yTapResult(false, method, "no clickable node matched the request")
            )
        } finally {
            DshA11yTreeReader.recycle(capture)
        }
    }

    private suspend fun tapByRef(
        service: DshA11yService,
        ref: A11yNodeRef,
        geometry: A11yScreenGeometry
    ): A11yOutcome {
        val previous = lastSnapshot
        val currentGeneration = generation
        val stale = previous == null ||
            previous.generation != ref.generation ||
            ref.generation != currentGeneration ||
            previous.packageName != currentPackage(service)
        if (stale) {
            return A11yOutcome.Rejected(
                A11yRejection.targetChanged("node ref is stale; call /dump again before tapping")
            )
        }
        val capture = DshA11yTreeReader.capture(service, generation, windowId)
        try {
            val old = previous!!.nodes.firstOrNull { it.index == ref.index }
            val fresh = capture.snapshot.nodes.firstOrNull { it.index == ref.index }
            if (old == null || fresh == null || !A11yTreePolicy.matchesIdentity(old, fresh)) {
                return A11yOutcome.Rejected(
                    A11yRejection.targetChanged("the referenced node changed; call /dump again")
                )
            }
            val handle = capture.handles.getOrNull(ref.index)
            val clicked = handle != null && runCatching {
                handle.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
            if (clicked) {
                return A11yOutcome.Tap(A11yTapResult(true, "node-ref", null))
            }
            val center = A11yPoint(
                fresh.rect.x + fresh.rect.w / 2,
                fresh.rect.y + fresh.rect.h / 2
            )
            return gestureTapAt(service, center, geometry)
        } finally {
            DshA11yTreeReader.recycle(capture)
        }
    }

    private suspend fun gestureTapAt(
        service: DshA11yService,
        point: A11yPoint,
        geometry: A11yScreenGeometry
    ): A11yOutcome {
        val outcome = executeOps(
            service,
            listOf(
                com.muyuchat.core.dsh.a11y.policy.A11yStrokeOp.Tap(
                    finger = -1,
                    point = A11yCoord.Pixels(point.x, point.y),
                    durationMs = A11yLimits.DEFAULT_TAP_MILLIS
                )
            ),
            geometry,
            expectedPackage = null
        )
        return when (outcome) {
            is A11yOutcome.Touch -> A11yOutcome.Tap(A11yTapResult(true, "gesture", null))
            is A11yOutcome.Rejected -> A11yOutcome.Tap(
                A11yTapResult(false, "none", outcome.rejection.message)
            )

            else -> A11yOutcome.Tap(A11yTapResult(false, "none", "tap gesture did not complete"))
        }
    }

    // ------------------------------------------------------------------ input

    private fun input(service: DshA11yService, command: A11yCommand.Input): A11yOutcome {
        checkTarget(service, command.expectedPackage)?.let { return A11yOutcome.Rejected(it) }
        val root = service.rootInActiveWindow
            ?: return A11yOutcome.Rejected(
                A11yRejection(
                    DshCapabilityErrorCode.CAPABILITY_UNAVAILABLE,
                    "no active window to type into"
                )
            )
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditable(root)
            ?: return A11yOutcome.Input(
                A11yInputResult(false, "none", "no focused or editable input field")
            )
        if (!target.isFocused) {
            runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        }

        var method = "set"
        var accepted: Boolean
        if (command.paste) {
            method = "paste"
            accepted = pasteInto(service, target, command.text)
        } else {
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    command.text
                )
            }
            accepted = runCatching {
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            }.getOrDefault(false)
            if (!accepted) {
                method = "paste"
                accepted = pasteInto(service, target, command.text)
            }
        }
        if (!accepted) {
            return A11yOutcome.Input(
                A11yInputResult(
                    focused = target.isFocused,
                    method = method,
                    error = "the target field rejected text input; injecting key events needs the privileged channel"
                )
            )
        }
        return A11yOutcome.Input(A11yInputResult(focused = true, method = method, error = null))
    }

    private fun pasteInto(
        service: DshA11yService,
        target: AccessibilityNodeInfo,
        text: String
    ): Boolean {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        return runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL, text))
            target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ scroll

    private fun scroll(service: DshA11yService, command: A11yCommand.Scroll): A11yOutcome {
        checkTarget(service, command.expectedPackage)?.let { return A11yOutcome.Rejected(it) }
        val root = service.rootInActiveWindow
            ?: return A11yOutcome.Rejected(
                A11yRejection(
                    DshCapabilityErrorCode.CAPABILITY_UNAVAILABLE,
                    "no active window to scroll"
                )
            )
        val scrollable = findFirstScrollable(root)
            ?: return A11yOutcome.Scroll(
                A11yScrollResult(command.direction, "none", "no scrollable region on this screen")
            )
        val action = when (command.direction) {
            A11yScrollDirection.UP, A11yScrollDirection.LEFT ->
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

            A11yScrollDirection.DOWN, A11yScrollDirection.RIGHT ->
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        val accepted = runCatching { scrollable.performAction(action) }.getOrDefault(false)
        return if (accepted) {
            A11yOutcome.Scroll(A11yScrollResult(command.direction, "node", null))
        } else {
            A11yOutcome.Scroll(
                A11yScrollResult(command.direction, "none", "the scrollable region rejected the action")
            )
        }
    }

    // ------------------------------------------------------------------ gestures / touch

    private fun touchOps(command: A11yCommand.Touch): List<com.muyuchat.core.dsh.a11y.policy.A11yStrokeOp>? =
        when (command.action) {
            A11yTouchAction.DOWN -> command.target?.let {
                listOf(
                    com.muyuchat.core.dsh.a11y.policy.A11yStrokeOp.Down(command.finger, it)
                )
            }

            A11yTouchAction.MOVE -> command.target?.let {
                listOf(
                    com.muyuchat.core.dsh.a11y.policy.A11yStrokeOp.Move(
                        command.finger,
                        it,
                        A11yLimits.DEFAULT_MOVE_MILLIS
                    )
                )
            }

            A11yTouchAction.UP -> listOf(
                com.muyuchat.core.dsh.a11y.policy.A11yStrokeOp.Up(
                    command.finger,
                    command.target,
                    A11yLimits.DEFAULT_UP_MILLIS
                )
            )

            A11yTouchAction.RELEASE -> listOf(
                com.muyuchat.core.dsh.a11y.policy.A11yStrokeOp.Up(
                    command.finger,
                    null,
                    A11yLimits.DEFAULT_UP_MILLIS
                )
            )
        }

    private suspend fun executeOps(
        service: DshA11yService,
        ops: List<com.muyuchat.core.dsh.a11y.policy.A11yStrokeOp>,
        geometry: A11yScreenGeometry,
        expectedPackage: String?
    ): A11yOutcome {
        checkTarget(service, expectedPackage)?.let { return A11yOutcome.Rejected(it) }
        return when (val plan = touchTracker.planAndApply(ops, geometry)) {
            is A11yPlanResult.Rejected -> A11yOutcome.Rejected(plan.rejection)
            is A11yPlanResult.Planned -> {
                val completed = dispatchGesture(service, plan.strokes)
                if (!completed) {
                    releaseHeldFingers()
                    A11yOutcome.Rejected(
                        A11yRejection(
                            DshCapabilityErrorCode.USER_CANCELLED,
                            "gesture was cancelled by the system; every finger was released"
                        )
                    )
                } else {
                    A11yOutcome.Touch(
                        A11yTouchDispatchResult(
                            durationMs = plan.totalMs,
                            held = touchTracker.report(geometry).held
                        )
                    )
                }
            }
        }
    }

    private suspend fun dispatchGesture(
        service: DshA11yService,
        strokes: List<com.muyuchat.core.dsh.a11y.policy.A11yPlannedStroke>
    ): Boolean {
        val gesture = buildGesture(strokes) ?: return false
        return suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            val dispatched = runCatching {
                service.dispatchGesture(gesture, callback, mainHandler)
            }.getOrDefault(false)
            if (!dispatched && continuation.isActive) continuation.resume(false)
        }
    }

    private fun buildGesture(
        strokes: List<com.muyuchat.core.dsh.a11y.policy.A11yPlannedStroke>
    ): GestureDescription? = runCatching {
        val builder = GestureDescription.Builder()
        strokes.forEach { stroke ->
            val first = stroke.points.first()
            val path = Path().apply {
                moveTo(first.x.toFloat(), first.y.toFloat())
                stroke.points.drop(1).forEach { point ->
                    lineTo(point.x.toFloat(), point.y.toFloat())
                }
            }
            builder.addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    stroke.startMs,
                    stroke.durationMs,
                    stroke.willContinue
                )
            )
        }
        builder.build()
    }.getOrNull()

    private fun releaseAllFingers(service: DshA11yService): A11yOutcome {
        releaseHeldFingers()
        return A11yOutcome.Touch(A11yTouchDispatchResult(durationMs = 0L, held = emptyList()))
    }

    /** Lifts every finger the tracker still holds; safe to call from timeout/cancel paths. */
    private fun releaseHeldFingers() {
        val service = attached ?: return
        val held = touchTracker.releaseAll()
        if (held.isEmpty()) return
        val gesture = runCatching {
            val builder = GestureDescription.Builder()
            held.forEach { state ->
                val path = Path().apply { moveTo(state.x, state.y) }
                builder.addStroke(GestureDescription.StrokeDescription(path, 0L, RELEASE_MILLIS))
            }
            builder.build()
        }.getOrNull() ?: return
        runCatching { service.dispatchGesture(gesture, null, null) }
    }

    // ------------------------------------------------------------------ helpers

    private fun refreshGeometry(service: DshA11yService): A11yScreenGeometry {
        val geometry = geometryOf(service)
        val previous = lastGeometry
        if (previous != null && previous != geometry) {
            // Rotation or display change invalidates every stored coordinate.
            touchTracker.releaseAll()
            invalidateTree()
        }
        lastGeometry = geometry
        return geometry
    }

    @Suppress("DEPRECATION")
    private fun geometryOf(service: DshA11yService): A11yScreenGeometry {
        val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = runCatching { windowManager?.currentWindowMetrics?.bounds }.getOrNull()
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                return A11yScreenGeometry(bounds.width(), bounds.height())
            }
        }
        val metrics = DisplayMetrics()
        runCatching { windowManager?.defaultDisplay?.getRealMetrics(metrics) }
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            return A11yScreenGeometry(metrics.widthPixels, metrics.heightPixels)
        }
        val fallback = service.resources.displayMetrics
        return A11yScreenGeometry(fallback.widthPixels, fallback.heightPixels)
    }

    private fun currentPackage(service: DshA11yService): String {
        val fromRoot = service.rootInActiveWindow?.packageName?.toString().orEmpty()
        return fromRoot.ifBlank { foregroundPackage }
    }

    private fun checkTarget(service: DshA11yService, expected: String?): A11yRejection? {
        if (expected == null) return null
        if (currentPackage(service) == expected) return null
        return A11yRejection.targetChanged(
            "foreground app is no longer the requested package; the action was not sent"
        )
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findFirst(root) { node ->
            runCatching {
                node.isEditable || node.className?.toString() == EDIT_TEXT_CLASS
            }.getOrDefault(false)
        }

    private fun findFirstScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findFirst(root) { node -> runCatching { node.isScrollable }.getOrDefault(false) }

    private fun findFirst(
        root: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_INPUT_SEARCH_NODES) {
            visited++
            val node = stack.removeLast()
            if (predicate(node)) return node
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (index in 0 until childCount) {
                runCatching { node.getChild(index) }.getOrNull()?.let { stack.addLast(it) }
            }
        }
        return null
    }

    private fun invalidateTree() {
        synchronized(this) {
            generation++
            lastSnapshot = null
        }
    }

    private fun timeoutFor(command: A11yCommand): Long = when (command) {
        A11yCommand.Status -> A11yLimits.STATUS_TIMEOUT_MILLIS
        A11yCommand.Dump -> A11yLimits.DUMP_TIMEOUT_MILLIS
        is A11yCommand.Tap -> A11yLimits.TAP_TIMEOUT_MILLIS
        is A11yCommand.Input -> A11yLimits.INPUT_TIMEOUT_MILLIS
        is A11yCommand.Scroll -> A11yLimits.SCROLL_TIMEOUT_MILLIS
        A11yCommand.Back, A11yCommand.Home -> A11yLimits.GLOBAL_ACTION_TIMEOUT_MILLIS
        is A11yCommand.Screenshot -> A11yLimits.SCREENSHOT_TIMEOUT_MILLIS
        A11yCommand.TouchStatus -> A11yLimits.TOUCH_STATUS_TIMEOUT_MILLIS
        A11yCommand.TouchRelease -> A11yLimits.TOUCH_TIMEOUT_MILLIS
        is A11yCommand.Swipe, is A11yCommand.Hold, is A11yCommand.Touch ->
            A11yLimits.TOUCH_TIMEOUT_MILLIS

        is A11yCommand.Gesture ->
            A11yLimits.MAX_GESTURE_TOTAL_MILLIS + A11yLimits.GESTURE_SLACK_MILLIS
    }

    private fun commandName(command: A11yCommand): String = when (command) {
        A11yCommand.Status -> "status"
        A11yCommand.Dump -> "dump"
        A11yCommand.TouchStatus -> "touch-status"
        A11yCommand.TouchRelease -> "touch-release"
        A11yCommand.Back -> "back"
        A11yCommand.Home -> "home"
        is A11yCommand.Tap -> "tap"
        is A11yCommand.Input -> "input"
        is A11yCommand.Scroll -> "scroll"
        is A11yCommand.Screenshot -> "screenshot"
        is A11yCommand.Swipe -> "swipe"
        is A11yCommand.Hold -> "hold"
        is A11yCommand.Touch -> "touch"
        is A11yCommand.Gesture -> "gesture"
    }

    companion object {
        /** One controller per process: the platform wraps exactly this engine. */
        val instance: DshA11yController by lazy { DshA11yController() }

        private const val TAG = "dsh-a11y"
        private const val EDIT_TEXT_CLASS = "android.widget.EditText"
        private const val CLIPBOARD_LABEL = "dsh-input"
        private const val RELEASE_MILLIS = 80L
        private const val MAX_INPUT_SEARCH_NODES = 4_000
    }
}
