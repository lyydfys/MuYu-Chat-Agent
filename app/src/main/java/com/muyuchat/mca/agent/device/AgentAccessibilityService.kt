package com.muyuchat.mca.agent.device

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference

/** Optional host service; the user enables it explicitly in system accessibility settings. */
class AgentAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        AgentAccessibilityBridge.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AgentAccessibilityBridge.detach(this)
        super.onDestroy()
    }
}

/** One strict selector; empty fields are ignored and at least one must be provided. */
data class AgentUiSelector(
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null
) {
    init {
        require(listOfNotNull(text, contentDescription, viewId).any { it.isNotBlank() }) {
            "at least one selector field is required"
        }
        listOfNotNull(text, contentDescription, viewId).forEach {
            require(it.length <= MAX_FIELD_CHARS && it.none { ch -> ch.isISOControl() }) {
                "selector field is invalid"
            }
        }
    }

    fun matches(node: AccessibilityNodeInfo): Boolean {
        if (text != null && node.text?.toString() != text) return false
        if (contentDescription != null && node.contentDescription?.toString() != contentDescription) return false
        if (viewId != null && node.viewIdResourceName != viewId) return false
        return true
    }

    fun summary(): String = when {
        text != null -> "文本 \"${text.take(60)}\""
        contentDescription != null -> "描述 \"${contentDescription.take(60)}\""
        else -> "viewId $viewId"
    }

    companion object {
        const val MAX_FIELD_CHARS: Int = 256
        const val MAX_MATCHES: Int = 500
    }
}

sealed interface AgentUiActionOutcome {
    data class Done(val matched: Int) : AgentUiActionOutcome
    data class Refused(val code: String, val message: String) : AgentUiActionOutcome
}

object AgentAccessibilityBridge {
    @Volatile
    private var serviceRef: WeakReference<AgentAccessibilityService>? = null

    fun attach(service: AgentAccessibilityService) {
        serviceRef = WeakReference(service)
    }

    fun detach(service: AgentAccessibilityService) {
        if (serviceRef?.get() === service) serviceRef = null
    }

    fun isConnected(): Boolean = serviceRef?.get() != null

    fun click(selector: AgentUiSelector, index: Int = 0): AgentUiActionOutcome =
        act(selector, index) { node ->
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

    fun input(selector: AgentUiSelector, text: String, index: Int = 0): AgentUiActionOutcome {
        if (text.length > MAX_INPUT_CHARS) {
            return AgentUiActionOutcome.Refused("DEVICE_UI_INPUT_TOO_LARGE", "输入文本过长。")
        }
        if (text.any { it.isISOControl() && it != '\n' && it != '\t' }) {
            return AgentUiActionOutcome.Refused("DEVICE_UI_INPUT_INVALID", "输入文本包含控制字符。")
        }
        return act(selector, index) { node ->
            val arguments = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
    }

    private fun act(
        selector: AgentUiSelector,
        index: Int,
        action: (AccessibilityNodeInfo) -> Boolean
    ): AgentUiActionOutcome {
        val service = serviceRef?.get()
            ?: return AgentUiActionOutcome.Refused("DEVICE_UI_UNAVAILABLE", "无障碍服务未启用。")
        if (index !in 0..AgentUiSelector.MAX_MATCHES) {
            return AgentUiActionOutcome.Refused("DEVICE_UI_SELECTOR_INVALID", "index 超出范围。")
        }
        val root = service.rootInActiveWindow
            ?: return AgentUiActionOutcome.Refused("DEVICE_UI_NO_WINDOW", "当前没有可访问窗口。")
        val matches = ArrayList<AccessibilityNodeInfo>(4)
        collect(root, selector, matches)
        if (matches.isEmpty()) {
            return AgentUiActionOutcome.Refused("DEVICE_UI_NOT_FOUND", "没有匹配的控件。")
        }
        val target = matches.getOrNull(index)
            ?: return AgentUiActionOutcome.Refused("DEVICE_UI_NOT_FOUND", "index 超出匹配数量。")
        val performed = runCatching { action(target) }.getOrDefault(false)
        return if (performed) {
            AgentUiActionOutcome.Done(matches.size)
        } else {
            AgentUiActionOutcome.Refused("DEVICE_UI_ACTION_FAILED", "控件不支持该动作。")
        }
    }

    private fun collect(
        root: AccessibilityNodeInfo,
        selector: AgentUiSelector,
        output: MutableList<AccessibilityNodeInfo>
    ) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < AgentUiSelector.MAX_MATCHES) {
            val node = queue.removeFirst()
            visited += 1
            if (selector.matches(node)) output.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child -> if (queue.size < AgentUiSelector.MAX_MATCHES) queue.add(child) }
            }
        }
    }

    const val MAX_INPUT_CHARS: Int = 4_096
}
