package com.muyuchat.core.dsh.browser

/** Self-description returned by `browser_caps`; every field is true for this build. */
data class DshBrowserCapsPayload(
    val surface: String,
    val engine: String,
    val userAgent: String?,
    val maxNodes: Int,
    val maxName: Int,
    val maxText: Int,
    val refScheme: String,
    val viewport: DshBrowserViewport?,
    val notes: List<String>,
    val maxTabs: Int,
    val processMode: DshBrowserProcessMode,
    val processIsolation: Boolean,
    val overlayRequired: Boolean,
    val urlPolicy: String,
    val downloads: String
)

/** One typed terminal answer of one browser operation. */
sealed interface DshBrowserOutcome {
    val op: DshBrowserOp
}

data class CapsOutcome(val caps: DshBrowserCapsPayload) : DshBrowserOutcome {
    override val op: DshBrowserOp get() = DshBrowserOp.CAPS
}

data class OpenOutcome(
    val tabId: String,
    val url: String,
    val title: String,
    val pageGeneration: Long,
    val loadState: DshBrowserLoadState,
    val ready: Boolean,
    val hint: String?
) : DshBrowserOutcome {
    override val op: DshBrowserOp get() = DshBrowserOp.OPEN
}

data class TabsListOutcome(
    val tabs: List<DshBrowserTab>,
    val activeTabId: String
) : DshBrowserOutcome {
    override val op: DshBrowserOp get() = DshBrowserOp.TABS_LIST
    val count: Int get() = tabs.size
}

data class TabsNewOutcome(
    val tabId: String,
    val activeTabId: String,
    val count: Int,
    val url: String,
    val loadState: DshBrowserLoadState,
    val hint: String?
) : DshBrowserOutcome {
    override val op: DshBrowserOp get() = DshBrowserOp.TABS_NEW
}

data class TabsSwitchOutcome(
    val activeTabId: String,
    val url: String,
    val title: String,
    val loadState: DshBrowserLoadState,
    val pageGeneration: Long,
    val hasSnapshot: Boolean,
    val count: Int,
    val hint: String?
) : DshBrowserOutcome {
    override val op: DshBrowserOp get() = DshBrowserOp.TABS_SWITCH
}

data class TabsCloseOutcome(
    val closedTabId: String,
    val count: Int,
    val activeTabId: String,
    val newTabId: String?,
    val hint: String?
) : DshBrowserOutcome {
    override val op: DshBrowserOp get() = DshBrowserOp.TABS_CLOSE
}

data class SnapshotOutcome(
    val tabId: String,
    val url: String,
    val title: String,
    val pageGeneration: Long,
    val full: Boolean,
    val unchanged: Boolean,
    val nodeCount: Int,
    val nodes: List<DshBrowserNode>?,
    val added: List<DshBrowserNode>,
    val removed: List<String>,
    val changed: List<DshBrowserNodeChange>,
    val unchangedCount: Int,
    val truncated: Boolean,
    val text: List<String>?,
    val newText: List<String>,
    val textChanged: Boolean,
    val viewport: DshBrowserViewport,
    val hint: String?
) : DshBrowserOutcome {
    override val op: DshBrowserOp get() = DshBrowserOp.SNAPSHOT
}

data class FindOutcome(
    val query: String,
    val matched: Int,
    val candidates: List<DshBrowserNode>,
    val pageGeneration: Long,
    val url: String,
    val tabId: String,
    val hint: String?
) : DshBrowserOutcome {
    override val op: DshBrowserOp get() = DshBrowserOp.FIND
}

data class ClickOutcome(
    val ref: String,
    val changed: Boolean,
    val via: String,
    val newText: List<String>,
    val fromGeneration: Long,
    val toGeneration: Long,
    val pageGeneration: Long,
    val url: String,
    val title: String,
    val loadState: DshBrowserLoadState,
    val clickedAt: List<Int>?,
    val hint: String?
) : DshBrowserOutcome {
    override val op: DshBrowserOp get() = DshBrowserOp.CLICK
}

data class TypeOutcome(
    val ref: String,
    val value: String,
    val via: String,
    val dispatched: Boolean,
    val changed: Boolean,
    val pageGeneration: Long,
    val fromGeneration: Long,
    val toGeneration: Long
) : DshBrowserOutcome {
    override val op: DshBrowserOp get() = DshBrowserOp.TYPE
}

data class ReadOutcome(
    val ref: String,
    val text: String,
    val chars: Int,
    val truncated: Boolean,
    val isField: Boolean,
    val tag: String,
    val type: String,
    val value: String,
    val placeholder: String,
    val disabled: Boolean,
    val hint: String?
) : DshBrowserOutcome {
    override val op: DshBrowserOp get() = DshBrowserOp.READ
}

data class NavOutcome(
    val navOp: DshBrowserNavOp,
    val url: String,
    val title: String,
    val pageGeneration: Long,
    val loadState: DshBrowserLoadState,
    val closed: Boolean
) : DshBrowserOutcome {
    override val op: DshBrowserOp get() = DshBrowserOp.NAV
}

data class ScrollOutcome(
    val scrollY: Int,
    val innerHeight: Int,
    val docHeight: Int
) : DshBrowserOutcome {
    override val op: DshBrowserOp get() = DshBrowserOp.SCROLL
}

data class FailureOutcome(val failure: DshBrowserFailure) : DshBrowserOutcome {
    override val op: DshBrowserOp get() = DshBrowserOp.CAPS
}
