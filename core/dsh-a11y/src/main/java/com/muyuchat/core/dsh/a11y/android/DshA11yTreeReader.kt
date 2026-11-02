package com.muyuchat.core.dsh.a11y.android

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.muyuchat.core.dsh.a11y.policy.A11yLimits
import com.muyuchat.core.dsh.a11y.policy.A11yNodeDraft
import com.muyuchat.core.dsh.a11y.policy.A11yRect
import com.muyuchat.core.dsh.a11y.policy.A11yTreePolicy

/**
 * One captured tree: the prompt-safe snapshot plus the live handles of the nodes that were
 * emitted, in the same order (so `snapshot.nodes[i].index == i` and `handles[i]` is the same
 * `AccessibilityNodeInfo`).
 */
internal class TreeCapture(
    val snapshot: com.muyuchat.core.dsh.a11y.policy.A11yTreeSnapshot,
    val handles: List<AccessibilityNodeInfo>
)

/**
 * Thin Android walk of `AccessibilityNodeInfo`. All decisions about caps, redaction and ordering
 * are delegated to [A11yTreePolicy]; this file only maps framework types and never exposes raw node
 * text to a caller that could log it.
 */
internal object DshA11yTreeReader {

    fun capture(service: AccessibilityService, generation: Long, windowId: Int): TreeCapture {
        val root = service.rootInActiveWindow
        val drafts = ArrayList<A11yNodeDraft>()
        val handles = ArrayList<AccessibilityNodeInfo>()
        var walkTruncated = false

        if (root != null) {
            walk(root, 0, drafts, handles) { walkTruncated = true }
        }
        val packageName = root?.packageName?.toString().orEmpty()
        val snapshot = A11yTreePolicy.buildSnapshot(
            generation = generation,
            packageName = packageName,
            windowId = windowId,
            drafts = drafts,
            walkTruncated = walkTruncated
        )
        return TreeCapture(snapshot, handles)
    }

    /** Lifts framework node handles after an action; no-op on API 33+ where recycle is deprecated. */
    fun recycle(capture: TreeCapture) {
        if (Build.VERSION.SDK_INT >= 33) return
        capture.handles.asReversed().forEach { handle ->
            runCatching { handle.recycle() }
        }
    }

    /** Node count for `/status`; bounded so a pathological tree cannot stall the request. */
    fun countNodes(service: AccessibilityService): Int {
        val root = service.rootInActiveWindow ?: return 0
        var count = 0
        walkAll(root) {
            if (count < A11yLimits.MAX_STATUS_NODE_COUNT) count++
        }
        return count
    }

    /** Bounds of password fields, used to black them out in a screenshot. */
    fun passwordRects(service: AccessibilityService): List<A11yRect> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val rects = ArrayList<A11yRect>(4)
        walkAll(root) { node ->
            if (rects.size >= MAX_PASSWORD_REGIONS) return@walkAll
            val isPassword = runCatching { node.isPassword }.getOrDefault(false)
            if (!isPassword) return@walkAll
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                rects += A11yRect(bounds.left, bounds.top, bounds.width(), bounds.height())
            }
        }
        return rects
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        drafts: MutableList<A11yNodeDraft>,
        handles: MutableList<AccessibilityNodeInfo>,
        onTruncated: () -> Unit
    ) {
        if (depth > A11yLimits.MAX_DEPTH) {
            onTruncated()
            return
        }
        if (drafts.size >= A11yLimits.MAX_NODES) {
            onTruncated()
            return
        }
        appendDraft(node, depth, drafts, handles)

        val childCount = runCatching { node.childCount }.getOrDefault(0)
        for (index in 0 until childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            walk(child, depth + 1, drafts, handles, onTruncated)
        }
    }

    private fun appendDraft(
        node: AccessibilityNodeInfo,
        depth: Int,
        drafts: MutableList<A11yNodeDraft>,
        handles: MutableList<AccessibilityNodeInfo>
    ) {
        val text = runCatching { node.text?.toString().orEmpty() }.getOrDefault("").trim()
        val desc = runCatching { node.contentDescription?.toString().orEmpty() }.getOrDefault("").trim()
        val clickable = runCatching { node.isClickable }.getOrDefault(false)
        val editable = runCatching { node.isEditable }.getOrDefault(false)
        val className = runCatching { node.className?.toString().orEmpty() }.getOrDefault("")
        val input = editable || className == EDIT_TEXT_CLASS
        val scrollable = runCatching { node.isScrollable }.getOrDefault(false)
        if (text.isEmpty() && desc.isEmpty() && !clickable && !input && !scrollable) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        drafts += A11yNodeDraft(
            depth = depth,
            text = text,
            desc = desc,
            cls = className,
            rect = A11yRect(bounds.left, bounds.top, bounds.width(), bounds.height()),
            clickable = clickable,
            input = input,
            checked = runCatching { node.isChecked }.getOrDefault(false),
            selected = runCatching { node.isSelected }.getOrDefault(false),
            scrollable = scrollable,
            password = runCatching { node.isPassword }.getOrDefault(false)
        )
        handles += node
    }

    private fun walkAll(node: AccessibilityNodeInfo, visitor: (AccessibilityNodeInfo) -> Unit) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        val depth = ArrayDeque<Int>()
        stack.addLast(node)
        depth.addLast(0)
        var visited = 0
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val currentDepth = depth.removeLast()
            if (currentDepth > A11yLimits.MAX_DEPTH) continue
            visited++
            if (visited > A11yLimits.MAX_STATUS_NODE_COUNT * 4) return
            visitor(current)
            val childCount = runCatching { current.childCount }.getOrDefault(0)
            for (index in 0 until childCount) {
                val child = runCatching { current.getChild(index) }.getOrNull() ?: continue
                stack.addLast(child)
                depth.addLast(currentDepth + 1)
            }
        }
    }

    private const val EDIT_TEXT_CLASS = "android.widget.EditText"
    private const val MAX_PASSWORD_REGIONS = 32
}
