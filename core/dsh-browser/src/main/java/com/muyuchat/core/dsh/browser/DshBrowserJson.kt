package com.muyuchat.core.dsh.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire encoder for the `/browser` response.
 *
 * Every field the official `dsh-tool-browser` package declares is emitted with exactly that name
 * and JSON type; extra fields are harmless there (the package copies only declared keys), missing
 * declared fields are not. The encoder never adds a token, cookie, local path or page secret.
 */
object DshBrowserJson {

    /** Encodes one typed outcome. A response that exceeds the budget degrades to a typed failure. */
    fun encode(outcome: DshBrowserOutcome): String {
        val body = when (outcome) {
            is CapsOutcome -> caps(outcome.caps)
            is OpenOutcome -> open(outcome)
            is TabsListOutcome -> tabsList(outcome)
            is TabsNewOutcome -> tabsNew(outcome)
            is TabsSwitchOutcome -> tabsSwitch(outcome)
            is TabsCloseOutcome -> tabsClose(outcome)
            is SnapshotOutcome -> snapshot(outcome)
            is FindOutcome -> find(outcome)
            is ClickOutcome -> click(outcome)
            is TypeOutcome -> type(outcome)
            is ReadOutcome -> read(outcome)
            is NavOutcome -> nav(outcome)
            is ScrollOutcome -> scroll(outcome)
            is FailureOutcome -> failure(outcome.failure)
        }
        val text = body.toString()
        if (text.length <= DshBrowserLimits.MAX_RESPONSE_BYTES) return text
        return failure(
            DshBrowserFailure(
                DshBrowserFailureReason.TOO_LARGE,
                "The browser response exceeded the contract budget",
                "narrow the request (find instead of a full snapshot, or read one element)"
            )
        ).toString()
    }

    private fun caps(payload: DshBrowserCapsPayload): JSONObject = JSONObject()
        .put("ok", true)
        .put("surface", payload.surface)
        .put("engine", payload.engine)
        .apply { payload.userAgent?.let { put("webviewUa", it) } }
        .put("maxNodes", payload.maxNodes)
        .put("maxName", payload.maxName)
        .put("maxText", payload.maxText)
        .put("refScheme", payload.refScheme)
        .put("viewport", viewport(payload.viewport ?: DshBrowserViewport.UNKNOWN))
        .put("notes", JSONArray(payload.notes))
        .put("maxTabs", payload.maxTabs)
        .put("processMode", payload.processMode.wire)
        .put("processIsolation", payload.processIsolation)
        .put("overlayRequired", payload.overlayRequired)
        .put("urlPolicy", payload.urlPolicy)
        .put("downloads", payload.downloads)

    private fun open(outcome: OpenOutcome): JSONObject = JSONObject()
        .put("ok", true)
        .put("tabId", outcome.tabId)
        .put("url", outcome.url)
        .put("title", outcome.title)
        .put("pageGeneration", outcome.pageGeneration)
        .put("loadState", outcome.loadState.wire)
        .put("ready", outcome.ready)
        .apply { outcome.hint?.let { put("hint", it) } }

    private fun tabsList(outcome: TabsListOutcome): JSONObject {
        val array = JSONArray()
        val activeId = outcome.activeTabId
        outcome.tabs.forEach { tab ->
            array.put(
                JSONObject()
                    .put("tabId", tab.id)
                    .put("url", tab.url)
                    .put("title", tab.title)
                    .put("loadState", tab.loadState.wire)
                    .put("pageGeneration", tab.pageGeneration)
                    .put("hasSnapshot", tab.hasSnapshot)
                    .put("active", tab.id == activeId)
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("tabs", array)
            .put("count", outcome.count)
            .put("activeTabId", activeId)
            .put("hint", "each tab owns its own refs, snapshot and generation; snapshot again after a switch")
    }

    private fun tabsNew(outcome: TabsNewOutcome): JSONObject = JSONObject()
        .put("ok", true)
        .put("tabId", outcome.tabId)
        .put("activeTabId", outcome.activeTabId)
        .put("count", outcome.count)
        .put("url", outcome.url)
        .put("loadState", outcome.loadState.wire)
        .apply { outcome.hint?.let { put("hint", it) } }

    private fun tabsSwitch(outcome: TabsSwitchOutcome): JSONObject = JSONObject()
        .put("ok", true)
        .put("activeTabId", outcome.activeTabId)
        .put("url", outcome.url)
        .put("title", outcome.title)
        .put("loadState", outcome.loadState.wire)
        .put("pageGeneration", outcome.pageGeneration)
        .put("hasSnapshot", outcome.hasSnapshot)
        .put("count", outcome.count)
        .apply { outcome.hint?.let { put("hint", it) } }

    private fun tabsClose(outcome: TabsCloseOutcome): JSONObject = JSONObject()
        .put("ok", true)
        .put("closed", outcome.closedTabId)
        .put("count", outcome.count)
        .put("activeTabId", outcome.activeTabId)
        .apply { outcome.newTabId?.let { put("newTabId", it) } }
        .apply { outcome.hint?.let { put("hint", it) } }

    private fun snapshot(outcome: SnapshotOutcome): JSONObject {
        val body = JSONObject()
            .put("ok", true)
            .put("full", outcome.full)
            .put("tabId", outcome.tabId)
            .put("url", outcome.url)
            .put("title", outcome.title)
            .put("pageGeneration", outcome.pageGeneration)
            .put("nodeCount", outcome.nodeCount)
            .put("truncated", outcome.truncated)
            .put("viewport", viewport(outcome.viewport))
            .put("refScheme", DshBrowserSession.REF_SCHEME)
        if (outcome.full) {
            body.put("nodes", nodes(outcome.nodes ?: emptyList()))
        } else {
            body.put("added", nodes(outcome.added))
            body.put("removed", JSONArray(outcome.removed))
            val changed = JSONArray()
            outcome.changed.forEach { change ->
                changed.put(
                    JSONObject()
                        .put("ref", change.ref)
                        .put("fields", JSONArray(change.fields))
                        .put("node", node(change.node))
                )
            }
            body.put("changed", changed)
            body.put("unchangedCount", outcome.unchangedCount)
            if (outcome.unchanged) body.put("unchanged", true)
            if (outcome.newText.isNotEmpty()) body.put("newText", JSONArray(outcome.newText))
            if (outcome.textChanged) body.put("textChanged", true)
        }
        outcome.text?.let { body.put("text", JSONArray(it)) }
        outcome.hint?.let { body.put("hint", it) }
        return body
    }

    private fun find(outcome: FindOutcome): JSONObject = JSONObject()
        .put("ok", true)
        .put("query", outcome.query)
        .put("matched", outcome.matched)
        .put("candidates", nodes(outcome.candidates))
        .put("pageGeneration", outcome.pageGeneration)
        .put("url", outcome.url)
        .put("tabId", outcome.tabId)
        .apply { outcome.hint?.let { put("hint", it) } }

    private fun click(outcome: ClickOutcome): JSONObject = JSONObject()
        .put("ok", true)
        .put("ref", outcome.ref)
        .put("changed", outcome.changed)
        .put("via", outcome.via)
        .apply { if (outcome.newText.isNotEmpty()) put("newText", JSONArray(outcome.newText)) }
        .put("fromGeneration", outcome.fromGeneration)
        .put("toGeneration", outcome.toGeneration)
        .put("pageGeneration", outcome.pageGeneration)
        .put("url", outcome.url)
        .put("title", outcome.title)
        .put("loadState", outcome.loadState.wire)
        .apply { outcome.clickedAt?.let { put("clickedAt", JSONArray(it)) } }
        .apply { outcome.hint?.let { put("hint", it) } }

    private fun type(outcome: TypeOutcome): JSONObject = JSONObject()
        .put("ok", true)
        .put("ref", outcome.ref)
        .put("value", outcome.value)
        .put("via", outcome.via)
        .put("dispatched", outcome.dispatched)
        .put("changed", outcome.changed)
        .put("pageGeneration", outcome.pageGeneration)
        .put("fromGeneration", outcome.fromGeneration)
        .put("toGeneration", outcome.toGeneration)

    private fun read(outcome: ReadOutcome): JSONObject = JSONObject()
        .put("ok", true)
        .put("ref", outcome.ref)
        .put("text", outcome.text)
        .put("chars", outcome.chars)
        .put("truncated", outcome.truncated)
        .put("field", outcome.isField)
        .put("tag", outcome.tag)
        .put("type", outcome.type)
        .put("value", outcome.value)
        .put("placeholder", outcome.placeholder)
        .put("disabled", outcome.disabled)
        .apply { outcome.hint?.let { put("hint", it) } }

    private fun nav(outcome: NavOutcome): JSONObject = JSONObject()
        .put("ok", true)
        .put("op", outcome.navOp.wire)
        .put("url", outcome.url)
        .put("title", outcome.title)
        .put("pageGeneration", outcome.pageGeneration)
        .put("loadState", outcome.loadState.wire)
        .put("closed", outcome.closed)

    private fun scroll(outcome: ScrollOutcome): JSONObject = JSONObject()
        .put("ok", true)
        .put("scrollY", outcome.scrollY)
        .put("innerHeight", outcome.innerHeight)
        .put("docHeight", outcome.docHeight)

    fun failure(failure: DshBrowserFailure): JSONObject = JSONObject()
        .put("ok", false)
        .put("reason", failure.reason.wire)
        .put("error", failure.message)
        .apply { failure.hint?.let { put("hint", it) } }

    private fun nodes(list: List<DshBrowserNode>): JSONArray {
        val array = JSONArray()
        list.forEach { array.put(node(it)) }
        return array
    }

    private fun node(node: DshBrowserNode): JSONObject {
        val json = JSONObject()
            .put("ref", node.ref)
            .put("role", node.role)
            .put("name", node.name)
            .put("inView", node.inView)
            .put("disabled", node.disabled)
            .put("dup", node.dup)
        node.bounds?.let { bounds ->
            json.put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.width, bounds.height)))
        }
        return json
    }

    private fun viewport(viewport: DshBrowserViewport): JSONObject = JSONObject()
        .put("width", viewport.width)
        .put("height", viewport.height)
        .put("scale", viewport.scale)
}
