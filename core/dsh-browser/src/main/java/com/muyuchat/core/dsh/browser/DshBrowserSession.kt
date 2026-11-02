package com.muyuchat.core.dsh.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Browser state owner: tabs, page/navigation generations, ref identity tables, snapshot diff
 * baselines and the per-call deadline/cancellation contract.
 *
 * One instance owns one browser session. Every mutation is serialised by a mutex because the
 * underlying `WebView` document is single-threaded and a concurrent snapshot/click pair would
 * otherwise race on the same page. The engine port is fully typed, so this whole class is plain
 * Kotlin and tested with a fake engine.
 *
 * Accountability of an unknown outcome: when a mutating action times out or is cancelled we do not
 * know whether the DOM changed. Refs are dropped for that tab, so a retry with an old ref fails
 * with `stale-ref` instead of silently clicking a different element.
 */
class DshBrowserSession(
    private val engine: DshBrowserEnginePort,
    private val isolation: DshBrowserIsolationState = DshBrowserProcessPolicy.decide(
        processDeclared = true,
        overlayGranted = true,
        engineAvailable = true
    ),
    private val tabs: DshBrowserTabs = DshBrowserTabs(),
    private val refs: DshBrowserRefStore = DshBrowserRefStore()
) {
    private val mutex = Mutex()
    private val observations = HashMap<String, DshBrowserObservation>()
    private var closed = false

    /** True once `nav close` released every tab and the engine. */
    val isClosed: Boolean
        get() = closed

    suspend fun execute(call: DshBrowserCall): DshBrowserOutcome {
        if (closed && call.op != DshBrowserOp.CAPS) {
            return fail(
                DshBrowserFailureReason.BROWSER_CLOSED,
                "The browser session is closed",
                "open a page again; a new session starts with an empty tab"
            )
        }
        var started = false
        return try {
            withTimeout(call.timeoutMillis) {
                mutex.withLock {
                    started = true
                    dispatch(call)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            if (started) dropUnknownOutcome(call)
            fail(
                DshBrowserFailureReason.TIMEOUT,
                "The browser operation exceeded its per-call deadline",
                "the outcome is unknown; take a fresh browser_snapshot before acting again"
            )
        } catch (cancelled: CancellationException) {
            if (started) dropUnknownOutcome(call)
            throw cancelled
        } catch (engineError: DshBrowserEngineException) {
            mapEngineFailure(engineError)
        } catch (defect: Throwable) {
            fail(
                DshBrowserFailureReason.INTERNAL,
                "The browser adapter failed unexpectedly",
                sanitize(defect.message) ?: defect::class.java.simpleName
            )
        }
    }

    /** Releases every tab, every WebView and the engine profile. Idempotent. */
    suspend fun close() {
        mutex.withLock { releaseLocked() }
    }

    /** Release body, callable only while the session mutex is held (nav close runs inside it). */
    private suspend fun releaseLocked() {
        if (closed) return
        closed = true
        tabs.list().forEach { tab ->
            runCatching { engine.closeTab(tab.id) }
        }
        tabs.clear()
        refs.clear()
        observations.clear()
        runCatching { engine.close() }
    }

    private suspend fun dispatch(call: DshBrowserCall): DshBrowserOutcome = when (call.op) {
        DshBrowserOp.CAPS -> caps()
        DshBrowserOp.OPEN -> open(call.args as DshBrowserArgs.Open)
        DshBrowserOp.TABS_LIST -> tabsList()
        DshBrowserOp.TABS_NEW -> tabsNew(call.args as DshBrowserArgs.TabsNew)
        DshBrowserOp.TABS_SWITCH -> tabsSwitch(call.args as DshBrowserArgs.TabsSwitch)
        DshBrowserOp.TABS_CLOSE -> tabsClose(call.args as DshBrowserArgs.TabsClose)
        DshBrowserOp.SNAPSHOT -> snapshot(call.args as DshBrowserArgs.Snapshot)
        DshBrowserOp.FIND -> find(call.args as DshBrowserArgs.Find)
        DshBrowserOp.CLICK -> click(call.args as DshBrowserArgs.Click)
        DshBrowserOp.TYPE -> type(call.args as DshBrowserArgs.TypeAction)
        DshBrowserOp.READ -> read(call.args as DshBrowserArgs.Read)
        DshBrowserOp.NAV -> navigate(call.args as DshBrowserArgs.Nav)
        DshBrowserOp.SCROLL -> scroll(call.args as DshBrowserArgs.Scroll)
    }

    // ---------------------------------------------------------------- caps

    private fun caps(): DshBrowserOutcome {
        val engineCaps = runCatching { engine.capabilities() }.getOrNull()
        val active = tabs.active()
        val viewport = engineCaps?.viewport
            ?: active?.let { observations[it.id]?.viewport }
            ?: DshBrowserViewport.UNKNOWN
        val notes = buildList {
            add("the browser runs in its own :browser process; a page crash does not stop the DSH engine")
            add("refs are element identity fingerprints: stable across snapshots until the page navigates")
            add("pageGeneration advances on navigation and on DOM changes; snapshot{since} returns a true diff")
            add("only http/https is accepted; file/content/javascript/data/blob/intent and loopback/private hosts are refused")
            add("cookies and cache are session-only and cleared when the browser closes")
            add("at most ${DshBrowserLimits.MAX_TABS} tabs; closing a tab releases its WebView")
            if (!isolation.processIsolation) {
                add("process isolation is NOT active (${isolation.mode.wire}): ${isolation.reason ?: "unknown reason"}")
            }
            add("downloads: ${ engineCaps?.downloadsPolicy ?: "reject" } (no download sink is installed by default)")
        }
        return CapsOutcome(
            DshBrowserCapsPayload(
                surface = "browser",
                engine = engineCaps?.engine ?: "webview",
                userAgent = engineCaps?.userAgent,
                maxNodes = DshBrowserLimits.MAX_NODES,
                maxName = DshBrowserLimits.MAX_NAME_CHARS,
                maxText = DshBrowserLimits.MAX_TEXT_CHARS,
                refScheme = REF_SCHEME,
                viewport = viewport,
                notes = notes,
                maxTabs = DshBrowserLimits.MAX_TABS,
                processMode = isolation.mode,
                processIsolation = isolation.processIsolation,
                overlayRequired = !isolation.processIsolation,
                urlPolicy = "http-https-only",
                downloads = engineCaps?.downloadsPolicy ?: "reject"
            )
        )
    }

    // ---------------------------------------------------------------- open / tabs

    private suspend fun open(args: DshBrowserArgs.Open): DshBrowserOutcome {
        if (args.url.lowercase() == "about:blank") {
            return fail(
                DshBrowserFailureReason.BLOCKED_URL,
                "Navigation refused by the browser URL policy",
                DshBrowserBlockReason.ABOUT_BLANK.detail
            )
        }
        val decision = DshBrowserUrlPolicy.evaluate(args.url)
        if (decision is DshBrowserUrlDecision.Block) return decision.toFailure()

        val tab = if (args.newTab) {
            createTab() ?: return tabsFull()
        } else {
            activeTabOrNull() ?: return fail(
                DshBrowserFailureReason.ENGINE_UNAVAILABLE,
                "No browser tab could be created",
                isolation.reason ?: "the :browser process did not start its WebView host"
            )
        }
        if (args.newTab) tabs.switchTo(tab.id)
        return loadInto(tab.id, (decision as DshBrowserUrlDecision.Allow).url)
    }

    private suspend fun loadInto(tabId: String, url: String): DshBrowserOutcome {
        tabs.beginNavigation(tabId, url)
        refs.release(tabId)
        observations.remove(tabId)
        val load = engine.load(tabId, url)
        validateLoadedUrl(tabId, load.url)?.let { return it }
        val tab = tabs.finishLoad(tabId, load.url, load.title, load.state)
            ?: return fail(
                DshBrowserFailureReason.NO_SUCH_TAB,
                "The tab disappeared while the page was loading",
                "call browser_tabs op=list"
            )
        if (load.state == DshBrowserLoadState.ERROR) {
            return fail(
                DshBrowserFailureReason.LOAD_FAILED,
                "The page could not be loaded",
                sanitize(load.errorDetail) ?: "check the URL or the network and reload"
            )
        }
        return OpenOutcome(
            tabId = tab.id,
            url = tab.url,
            title = tab.title,
            pageGeneration = tab.pageGeneration,
            loadState = tab.loadState,
            ready = load.ready,
            hint = "next: browser_snapshot (structure) or browser_find (one element)"
        )
    }

    private fun tabsList(): DshBrowserOutcome = TabsListOutcome(
        tabs = tabs.list(),
        activeTabId = tabs.activeTabId
    )

    private suspend fun tabsNew(args: DshBrowserArgs.TabsNew): DshBrowserOutcome {
        val startUrl = args.url
        var allowedUrl: String? = null
        if (!startUrl.isNullOrBlank()) {
            val decision = DshBrowserUrlPolicy.evaluate(startUrl)
            if (decision is DshBrowserUrlDecision.Block) return decision.toFailure()
            allowedUrl = (decision as DshBrowserUrlDecision.Allow).url
        }
        val tab = createTab(allowedUrl) ?: return tabsFull()
        if (args.front) tabs.switchTo(tab.id)
        return TabsNewOutcome(
            tabId = tab.id,
            activeTabId = tabs.activeTabId,
            count = tabs.size,
            url = tab.url,
            loadState = tab.loadState,
            hint = if (allowedUrl != null) {
                "the page is still loading; browser_open{newTab:true} waits for it, or snapshot later"
            } else {
                null
            }
        )
    }

    private fun tabsSwitch(args: DshBrowserArgs.TabsSwitch): DshBrowserOutcome {
        val tab = tabs.switchTo(args.tabId)
            ?: return fail(
                DshBrowserFailureReason.NO_SUCH_TAB,
                "No such tab",
                "call browser_tabs op=list for the live tab ids"
            )
        return TabsSwitchOutcome(
            activeTabId = tab.id,
            url = tab.url,
            title = tab.title,
            loadState = tab.loadState,
            pageGeneration = tab.pageGeneration,
            hasSnapshot = tab.hasSnapshot,
            count = tabs.size,
            hint = "the tab has its own refs and generation; take a snapshot if you need element refs"
        )
    }

    private suspend fun tabsClose(args: DshBrowserArgs.TabsClose): DshBrowserOutcome {
        val targetId = args.tabId ?: tabs.activeTabId
        if (targetId.isBlank()) {
            return fail(
                DshBrowserFailureReason.NO_SUCH_TAB,
                "There is no tab to close",
                "the browser has no tab; open a URL first"
            )
        }
        val target = tabs.find(targetId)
            ?: return fail(
                DshBrowserFailureReason.NO_SUCH_TAB,
                "No such tab",
                "call browser_tabs op=list for the live tab ids"
            )
        runCatching { engine.closeTab(target.id) }
        tabs.remove(target.id)
        refs.release(target.id)
        observations.remove(target.id)
        var newTabId: String? = null
        if (tabs.isEmpty()) {
            val replacement = createTab(null)
            newTabId = replacement?.id
        }
        return TabsCloseOutcome(
            closedTabId = target.id,
            count = tabs.size,
            activeTabId = tabs.activeTabId,
            newTabId = newTabId,
            hint = "the closed tab's WebView and refs were released"
        )
    }

    // ---------------------------------------------------------------- snapshot / find

    private suspend fun snapshot(args: DshBrowserArgs.Snapshot): DshBrowserOutcome {
        val tab = activeTabForRead() ?: return noTabFailure()
        val observation = engine.observe(tab.id, includeText = true)
        validateObservedUrl(observation.url)?.let { return it }
        val current = recordObservation(tab.id, observation)
        val nodes = DshBrowserRefStore.sanitize(observation.nodes)
        val previous = observations[tab.id]
        val full = args.since == null || args.since < 0 || previous == null

        if (full) {
            refs.record(tab.id, current.navigationGeneration, nodes)
            tabs.markSnapshot(tab.id)
            observations[tab.id] = observation
            return SnapshotOutcome(
                tabId = tab.id,
                url = observation.url,
                title = observation.title,
                pageGeneration = current.pageGeneration,
                full = true,
                unchanged = false,
                nodeCount = nodes.size,
                nodes = nodes,
                added = emptyList(),
                removed = emptyList(),
                changed = emptyList(),
                unchangedCount = 0,
                truncated = observation.truncated,
                text = if (args.includeText) observation.textLines else null,
                newText = emptyList(),
                textChanged = false,
                viewport = observation.viewport,
                hint = if (observation.truncated || nodes.size >= DshBrowserLimits.MAX_NODES) {
                    "the node cap was reached; use browser_find to narrow the page"
                } else {
                    null
                }
            )
        }

        val baseline = previous!!
        val delta = DshBrowserDomDiff.diff(
            previous = DshBrowserRefStore.sanitize(baseline.nodes),
            current = nodes,
            previousText = baseline.textLines,
            currentText = observation.textLines
        )
        refs.record(tab.id, current.navigationGeneration, nodes)
        tabs.markSnapshot(tab.id)
        observations[tab.id] = observation
        return SnapshotOutcome(
            tabId = tab.id,
            url = observation.url,
            title = observation.title,
            pageGeneration = current.pageGeneration,
            full = false,
            unchanged = delta.isUnchanged,
            nodeCount = nodes.size,
            nodes = null,
            added = delta.added,
            removed = delta.removed,
            changed = delta.changed,
            unchangedCount = delta.unchangedCount,
            truncated = observation.truncated,
            text = if (args.includeText) observation.textLines else null,
            newText = delta.newText,
            textChanged = delta.textChanged,
            viewport = observation.viewport,
            hint = if (delta.isUnchanged) null else "page generation advanced; the diff is against the previous snapshot"
        )
    }

    private suspend fun find(args: DshBrowserArgs.Find): DshBrowserOutcome {
        val tab = activeTabForRead() ?: return noTabFailure()
        val observation = engine.observe(tab.id, includeText = true)
        validateObservedUrl(observation.url)?.let { return it }
        val current = recordObservation(tab.id, observation)
        val nodes = DshBrowserRefStore.sanitize(observation.nodes)
        refs.record(tab.id, current.navigationGeneration, nodes)
        tabs.markSnapshot(tab.id)
        observations[tab.id] = observation
        val result = DshBrowserFind.search(nodes, args.query, args.role, args.limit)
        return FindOutcome(
            query = args.query.take(DshBrowserLimits.MAX_QUERY_CHARS),
            matched = result.matched,
            candidates = result.candidates,
            pageGeneration = current.pageGeneration,
            url = observation.url,
            tabId = tab.id,
            hint = if (result.matched == 0) {
                "no element matched; try the visible label text or a role such as button/link/textbox"
            } else {
                "use browser_click with one of these refs"
            }
        )
    }

    // ---------------------------------------------------------------- click / type / read

    private suspend fun click(args: DshBrowserArgs.Click): DshBrowserOutcome {
        explicitTabFailure(args.tabId)?.let { return it }
        val tab = activeTabForRead() ?: return noTabFailure()
        val check = refs.validate(tab.id, tab.navigationGeneration, args.ref)
        if (check is DshBrowserRefCheck.Rejected) return FailureOutcome(check.failure)
        val target = engine.resolve(tab.id, args.ref)
            ?: return staleRef(args.ref)
        if (!target.found) return staleRef(args.ref)
        if (target.disabled) {
            return fail(
                DshBrowserFailureReason.ELEMENT_DISABLED,
                "The element is disabled, so clicking it cannot do anything",
                "take a fresh snapshot and pick an enabled element"
            )
        }

        val fromGeneration = tab.pageGeneration
        val baseline = observations[tab.id]
        val dispatch = engine.click(tab.id, target)
        val after = engine.observe(tab.id, includeText = true)
        validateObservedUrl(after.url)?.let { return it }
        val changed = applyPostActionState(tab.id, after, baseline)

        return ClickOutcome(
            ref = args.ref,
            changed = changed,
            via = dispatch.via,
            newText = if (changed) newTextFor(after, baseline) else emptyList(),
            fromGeneration = fromGeneration,
            toGeneration = tabs.find(tab.id)?.pageGeneration ?: fromGeneration,
            pageGeneration = tabs.find(tab.id)?.pageGeneration ?: fromGeneration,
            url = after.url,
            title = after.title,
            loadState = tabs.find(tab.id)?.loadState ?: DshBrowserLoadState.LOADING,
            clickedAt = dispatch.clickedAt,
            hint = if (changed) null else {
                "the page did not change: the element may be decorative or the click was intercepted; re-snapshot before retrying"
            }
        )
    }

    private suspend fun type(args: DshBrowserArgs.TypeAction): DshBrowserOutcome {
        explicitTabFailure(args.tabId)?.let { return it }
        val tab = activeTabForRead() ?: return noTabFailure()
        val check = refs.validate(tab.id, tab.navigationGeneration, args.ref)
        if (check is DshBrowserRefCheck.Rejected) return FailureOutcome(check.failure)
        val target = engine.resolve(tab.id, args.ref)
            ?: return staleRef(args.ref)
        if (!target.found) return staleRef(args.ref)
        if (!target.editable) {
            return fail(
                DshBrowserFailureReason.NOT_EDITABLE,
                "The target element is not editable",
                "pick an input/textarea/contenteditable ref from a fresh snapshot"
            )
        }

        val fromGeneration = tab.pageGeneration
        val baseline = observations[tab.id]
        val dispatch = engine.type(tab.id, target, args.text, args.replace)
        val after = engine.observe(tab.id, includeText = true)
        validateObservedUrl(after.url)?.let { return it }
        val changed = applyPostActionState(tab.id, after, baseline)

        return TypeOutcome(
            ref = args.ref,
            value = dispatch.value.take(DshBrowserLimits.MAX_TEXT_CHARS),
            via = dispatch.via,
            dispatched = dispatch.dispatched,
            changed = changed,
            pageGeneration = tabs.find(tab.id)?.pageGeneration ?: fromGeneration,
            fromGeneration = fromGeneration,
            toGeneration = tabs.find(tab.id)?.pageGeneration ?: fromGeneration
        )
    }

    private suspend fun read(args: DshBrowserArgs.Read): DshBrowserOutcome {
        explicitTabFailure(args.tabId)?.let { return it }
        val tab = activeTabForRead() ?: return noTabFailure()
        if (args.ref != null) {
            val check = refs.validate(tab.id, tab.navigationGeneration, args.ref)
            if (check is DshBrowserRefCheck.Rejected) return FailureOutcome(check.failure)
        }
        val result = engine.read(tab.id, args.ref)
        val truncated = result.text.length > DshBrowserLimits.MAX_TEXT_CHARS
        return ReadOutcome(
            ref = args.ref ?: "",
            text = result.text.take(DshBrowserLimits.MAX_TEXT_CHARS),
            chars = result.text.length,
            truncated = truncated,
            isField = result.isField,
            tag = result.tag.take(DshBrowserLimits.MAX_ROLE_CHARS),
            type = result.type.take(DshBrowserLimits.MAX_ROLE_CHARS),
            value = result.value.take(DshBrowserLimits.MAX_TEXT_CHARS),
            placeholder = result.placeholder.take(DshBrowserLimits.MAX_NAME_CHARS),
            disabled = result.disabled,
            hint = if (result.isField) {
                "for form fields the typed value is in 'value'; textContent stays empty by design"
            } else {
                null
            }
        )
    }

    // ---------------------------------------------------------------- nav / scroll

    private suspend fun navigate(args: DshBrowserArgs.Nav): DshBrowserOutcome {
        if (args.op == DshBrowserNavOp.CLOSE) {
            releaseLocked()
            return NavOutcome(
                navOp = DshBrowserNavOp.CLOSE,
                url = "",
                title = "",
                pageGeneration = 0,
                loadState = DshBrowserLoadState.IDLE,
                closed = true
            )
        }
        val tab = activeTabForRead() ?: return noTabFailure()
        val result = engine.navigate(tab.id, args.op)
        if (result.noHistory) {
            return fail(
                DshBrowserFailureReason.NO_HISTORY,
                "There is no history entry in that direction",
                "the page generation did not change; try reload or browser_open"
            )
        }
        tabs.beginNavigation(tab.id, tab.url)
        refs.release(tab.id)
        observations.remove(tab.id)
        val observation = engine.observe(tab.id, includeText = false)
        validateObservedUrl(observation.url)?.let { return it }
        val updated = tabs.finishLoad(tab.id, observation.url, observation.title, DshBrowserLoadState.READY)
            ?: return noTabFailure()
        observations[tab.id] = observation
        return NavOutcome(
            navOp = args.op,
            url = updated.url,
            title = updated.title,
            pageGeneration = updated.pageGeneration,
            loadState = updated.loadState,
            closed = false
        )
    }

    private suspend fun scroll(args: DshBrowserArgs.Scroll): DshBrowserOutcome {
        val tab = activeTabForRead() ?: return noTabFailure()
        val position = engine.scroll(
            tabId = tab.id,
            direction = args.direction,
            amount = args.amount.coerceIn(1, DshBrowserLimits.MAX_SCROLL_AMOUNT)
        )
        return ScrollOutcome(
            scrollY = position.scrollY,
            innerHeight = position.innerHeight,
            docHeight = position.docHeight
        )
    }

    // ---------------------------------------------------------------- shared mechanics

    /** Applies mutation/generation/ref bookkeeping after a click/type and answers "did it change". */
    private fun applyPostActionState(
        tabId: String,
        after: DshBrowserObservation,
        baseline: DshBrowserObservation?
    ): Boolean {
        val tab = tabs.find(tabId) ?: return false
        val navigated = after.url.isNotEmpty() && after.url != tab.url
        if (navigated) {
            tabs.beginNavigation(tabId, after.url)
            refs.release(tabId)
            observations.remove(tabId)
            tabs.finishLoad(tabId, after.url, after.title, DshBrowserLoadState.READY)
            observations[tabId] = after
            return true
        }
        val nodes = DshBrowserRefStore.sanitize(after.nodes)
        val delta = DshBrowserDomDiff.diff(
            previous = DshBrowserRefStore.sanitize(baseline?.nodes ?: emptyList()),
            current = nodes,
            previousText = baseline?.textLines,
            currentText = after.textLines
        )
        val mutated = baseline == null || after.mutationCount != baseline.mutationCount
        if (mutated && baseline != null) tabs.noteDomMutation(tabId)
        val current = tabs.find(tabId) ?: tab
        refs.record(tabId, current.navigationGeneration, nodes)
        observations[tabId] = after
        // A DOM mutation with an identical node table (e.g. a reactive `value` update) still means
        // the action landed; the generation moved, so `changed` must not claim otherwise.
        return !delta.isUnchanged || mutated
    }

    private fun newTextFor(
        after: DshBrowserObservation,
        baseline: DshBrowserObservation?
    ): List<String> = DshBrowserDomDiff.newLines(
        previous = baseline?.textLines ?: emptyList(),
        current = after.textLines ?: emptyList()
    )

    /** Records an observation, advancing the page generation on an observed DOM mutation. */
    private fun recordObservation(tabId: String, observation: DshBrowserObservation): DshBrowserTab {
        val previous = observations[tabId]
        if (previous != null && observation.mutationCount > 0 &&
            observation.mutationCount != previous.mutationCount
        ) {
            tabs.noteDomMutation(tabId)
        }
        return tabs.find(tabId) ?: error("browser tab $tabId disappeared")
    }

    /** Creates and registers one engine tab. Returns null when the tab cap is reached. */
    private suspend fun createTab(startUrl: String? = null): DshBrowserTab? {
        if (tabs.isAtCapacity) return null
        val id = tabs.allocateId()
        try {
            engine.createTab(id, startUrl)
        } catch (error: DshBrowserEngineException) {
            throw error
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw DshBrowserEngineException(
                DshBrowserEngineError.UNAVAILABLE,
                error.message ?: error::class.java.simpleName
            )
        }
        if (!tabs.add(id)) return null
        if (startUrl != null) {
            tabs.beginNavigation(id, startUrl)
            tabs.finishLoad(id, startUrl, "", DshBrowserLoadState.LOADING)
        }
        return tabs.find(id)
    }

    /** The active tab. Page operations create the first tab implicitly, like the reference shell. */
    private suspend fun activeTabOrNull(): DshBrowserTab? {
        if (isolation.mode == DshBrowserProcessMode.DECLARED_UNAVAILABLE) {
            throw DshBrowserEngineException(
                DshBrowserEngineError.UNAVAILABLE,
                isolation.reason ?: "the :browser process cannot host a WebView"
            )
        }
        tabs.active()?.let { return it }
        return createTab(null)
    }

    private suspend fun activeTabForRead(): DshBrowserTab? = activeTabOrNull()

    private fun tabsFull(): DshBrowserOutcome = fail(
        DshBrowserFailureReason.TAB_LIMIT,
        "The bounded tab count is reached",
        "close a tab with browser_tabs op=close before opening another (cap ${DshBrowserLimits.MAX_TABS})"
    )

    private fun noTabFailure(): DshBrowserOutcome = fail(
        DshBrowserFailureReason.ENGINE_UNAVAILABLE,
        "No browser tab is available",
        isolation.reason ?: "the :browser process did not start its WebView host"
    )

    private fun explicitTabFailure(tabId: String?): DshBrowserOutcome? {
        if (tabId == null) return null
        if (tabs.find(tabId) == null) {
            return fail(
                DshBrowserFailureReason.NO_SUCH_TAB,
                "No such tab",
                "call browser_tabs op=list for the live tab ids"
            )
        }
        if (!tabs.isActive(tabId)) {
            return fail(
                DshBrowserFailureReason.STALE_TAB,
                "The action targets a tab that is not active",
                "switch to that tab with browser_tabs op=switch before using its ref"
            )
        }
        return null
    }

    private fun staleRef(ref: String): DshBrowserOutcome = fail(
        DshBrowserFailureReason.STALE_REF,
        "The ref no longer identifies an element on this page",
        "take a fresh browser_snapshot or browser_find; refs never cross a navigation"
    )

    /** Re-validates the final post-redirect URL; a blocked redirect is never accepted silently. */
    private fun validateLoadedUrl(tabId: String, url: String): DshBrowserOutcome? {
        if (DshBrowserUrlPolicy.isInternalBlank(url)) return null
        return validateObservedUrl(url)
    }

    private fun validateObservedUrl(url: String): DshBrowserOutcome? {
        if (DshBrowserUrlPolicy.isInternalBlank(url)) return null
        val decision = DshBrowserUrlPolicy.evaluate(url)
        return if (decision is DshBrowserUrlDecision.Block) decision.toFailure() else null
    }

    private fun dropUnknownOutcome(call: DshBrowserCall) {
        val mutating = call.op == DshBrowserOp.CLICK ||
            call.op == DshBrowserOp.TYPE ||
            call.op == DshBrowserOp.NAV ||
            call.op == DshBrowserOp.OPEN
        if (!mutating) return
        val tabId = tabs.activeTabId
        if (tabId.isEmpty()) return
        refs.release(tabId)
        observations.remove(tabId)
    }

    private fun mapEngineFailure(error: DshBrowserEngineException): DshBrowserOutcome {
        val reason = when (error.kind) {
            DshBrowserEngineError.UNAVAILABLE -> DshBrowserFailureReason.ENGINE_UNAVAILABLE
            DshBrowserEngineError.PAGE_NOT_READY -> DshBrowserFailureReason.NOT_LOADED
            DshBrowserEngineError.SCRIPT_FAILED -> DshBrowserFailureReason.INTERNAL
            DshBrowserEngineError.CLOSED -> DshBrowserFailureReason.BROWSER_CLOSED
            DshBrowserEngineError.TIMEOUT -> DshBrowserFailureReason.TIMEOUT
            DshBrowserEngineError.NAVIGATION_BLOCKED -> DshBrowserFailureReason.BLOCKED_URL
            DshBrowserEngineError.INTERNAL -> DshBrowserFailureReason.INTERNAL
        }
        val message = when (reason) {
            DshBrowserFailureReason.ENGINE_UNAVAILABLE -> "The isolated browser process is not available"
            DshBrowserFailureReason.NOT_LOADED -> "The page is not ready for this operation"
            DshBrowserFailureReason.BROWSER_CLOSED -> "The browser session is closed"
            DshBrowserFailureReason.TIMEOUT -> "The page operation exceeded its deadline"
            DshBrowserFailureReason.BLOCKED_URL -> "A navigation or redirect was refused by the URL policy"
            else -> "The browser engine failed to run the page operation"
        }
        return fail(reason, message, sanitize(error.detail))
    }

    private fun DshBrowserUrlDecision.Block.toFailure(): DshBrowserOutcome = fail(
        DshBrowserFailureReason.BLOCKED_URL,
        "Navigation refused by the browser URL policy",
        reason.detail
    )

    private fun fail(
        reason: DshBrowserFailureReason,
        message: String,
        hint: String? = null
    ): DshBrowserOutcome = FailureOutcome(
        DshBrowserFailure(
            reason = reason,
            message = message,
            hint = sanitize(hint),
            retryable = reason == DshBrowserFailureReason.TIMEOUT
        )
    )

    /** Keeps adapter/page-adjacent strings out of the failure envelope in a safe shape. */
    private fun sanitize(detail: String?): String? {
        if (detail == null) return null
        val cleaned = detail
            .filter { it.code >= 0x20 && it.code != 0x7F }
            .take(200)
        return cleaned.ifBlank { null }
    }

    companion object {
        /** Reported by `browser_caps`; the wire contract the package may rely on. */
        const val REF_SCHEME = "stable-fingerprint"
    }
}
