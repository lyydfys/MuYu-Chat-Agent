package com.muyuchat.core.dsh.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DshBrowserSessionTest {

    private fun isolated() = DshBrowserProcessPolicy.decide(
        processDeclared = true,
        overlayGranted = true,
        engineAvailable = true
    )

    private fun session(
        engine: FakeBrowserEngine,
        tabs: DshBrowserTabs = DshBrowserTabs(),
        isolation: DshBrowserIsolationState = isolated()
    ) = DshBrowserSession(engine, isolation, tabs)

    private fun call(
        op: DshBrowserOp,
        args: DshBrowserArgs,
        timeoutMillis: Long = 5_000L
    ) = DshBrowserCall(op, args, timeoutMillis)

    private fun reason(outcome: DshBrowserOutcome): DshBrowserFailureReason {
        assertTrue("expected a failure, got $outcome", outcome is FailureOutcome)
        return (outcome as FailureOutcome).failure.reason
    }

    private suspend fun DshBrowserSession.openReady(url: String = "https://example.com"): OpenOutcome {
        val opened = execute(call(DshBrowserOp.OPEN, DshBrowserArgs.Open(url, false)))
        assertTrue("open failed: $opened", opened is OpenOutcome)
        return opened as OpenOutcome
    }

    private suspend fun DshBrowserSession.snapshotNow(): SnapshotOutcome {
        val snapshot = execute(call(DshBrowserOp.SNAPSHOT, DshBrowserArgs.Snapshot(null, false)))
        assertTrue("snapshot failed: $snapshot", snapshot is SnapshotOutcome)
        return snapshot as SnapshotOutcome
    }

    @Test
    fun `caps is honest about the isolated process and the budgets`() = runTest {
        val engine = FakeBrowserEngine()
        val outcome = session(engine).execute(call(DshBrowserOp.CAPS, DshBrowserArgs.Caps)) as CapsOutcome
        assertEquals("browser", outcome.caps.surface)
        assertEquals("stable-fingerprint", outcome.caps.refScheme)
        assertEquals(DshBrowserLimits.MAX_TABS, outcome.caps.maxTabs)
        assertEquals(DshBrowserProcessMode.ISOLATED, outcome.caps.processMode)
        assertTrue(outcome.caps.processIsolation)
        assertFalse(outcome.caps.overlayRequired)
        assertEquals("http-https-only", outcome.caps.urlPolicy)
        assertTrue(outcome.caps.notes.any { it.contains(":browser process") })
        assertTrue(outcome.caps.notes.any { it.contains("session-only") })
    }

    @Test
    fun `caps degrades honestly when the declared process cannot host a WebView`() = runTest {
        val engine = FakeBrowserEngine()
        val degraded = DshBrowserProcessPolicy.decide(true, overlayGranted = false, engineAvailable = true)
        val outcome = session(engine, isolation = degraded)
            .execute(call(DshBrowserOp.CAPS, DshBrowserArgs.Caps)) as CapsOutcome
        assertFalse(outcome.caps.processIsolation)
        assertEquals(DshBrowserProcessMode.DECLARED_UNAVAILABLE, outcome.caps.processMode)
        assertTrue(outcome.caps.overlayRequired)
        assertTrue(outcome.caps.notes.any { it.contains("process isolation is NOT active") })

        val open = session(engine, isolation = degraded)
            .execute(call(DshBrowserOp.OPEN, DshBrowserArgs.Open("https://example.com", false)))
        assertEquals(DshBrowserFailureReason.ENGINE_UNAVAILABLE, reason(open))
        assertTrue(engine.loads.isEmpty())
    }

    @Test
    fun `open refuses a blocked scheme before the engine ever loads`() = runTest {
        val engine = FakeBrowserEngine()
        val outcome = session(engine)
            .execute(call(DshBrowserOp.OPEN, DshBrowserArgs.Open("file:///sdcard/secret", false)))
        assertEquals(DshBrowserFailureReason.BLOCKED_URL, reason(outcome))
        assertTrue(engine.loads.isEmpty())
        assertTrue(engine.pages.isEmpty())
    }

    @Test
    fun `open loads an allowed page and reports tab identity and generation`() = runTest {
        val engine = FakeBrowserEngine()
        val owner = session(engine)
        val opened = owner.openReady()
        assertEquals("t1", opened.tabId)
        assertEquals("https://example.com", opened.url)
        assertEquals("Fixture", opened.title)
        assertEquals(1L, opened.pageGeneration)
        assertEquals(DshBrowserLoadState.READY, opened.loadState)
        assertTrue(opened.ready)
        assertEquals(listOf("t1" to "https://example.com"), engine.loads)
    }

    @Test
    fun `a redirect to a loopback address is refused after the load`() = runTest {
        val engine = FakeBrowserEngine()
        engine.redirectTo = "http://127.0.0.1:3081/browser"
        val outcome = session(engine)
            .execute(call(DshBrowserOp.OPEN, DshBrowserArgs.Open("https://example.com", false)))
        assertEquals(DshBrowserFailureReason.BLOCKED_URL, reason(outcome))
    }

    @Test
    fun `a network load error is reported as a typed load failure`() = runTest {
        val engine = FakeBrowserEngine()
        engine.loadFailureDetail = "net-error:-2"
        val outcome = session(engine)
            .execute(call(DshBrowserOp.OPEN, DshBrowserArgs.Open("https://example.com", false)))
        assertEquals(DshBrowserFailureReason.LOAD_FAILED, reason(outcome))
    }

    @Test
    fun `the tab cap is bounded and a new tab becomes active`() = runTest {
        val engine = FakeBrowserEngine()
        val owner = session(engine, tabs = DshBrowserTabs(maxTabs = 2))
        owner.openReady()
        val second = owner.execute(call(DshBrowserOp.OPEN, DshBrowserArgs.Open("https://b.example", true)))
            as OpenOutcome
        assertEquals("t2", second.tabId)
        val list = owner.execute(call(DshBrowserOp.TABS_LIST, DshBrowserArgs.TabsList)) as TabsListOutcome
        assertEquals("t2", list.activeTabId)
        assertEquals(2, list.count)

        val third = owner.execute(call(DshBrowserOp.TABS_NEW, DshBrowserArgs.TabsNew(null, true, null)))
        assertEquals(DshBrowserFailureReason.TAB_LIMIT, reason(third))
        assertEquals(2, engine.pages.size)
    }

    @Test
    fun `tabs new without front keeps the current page active`() = runTest {
        val engine = FakeBrowserEngine()
        val owner = session(engine)
        owner.openReady()
        val created = owner.execute(
            call(DshBrowserOp.TABS_NEW, DshBrowserArgs.TabsNew("https://b.example", false, null))
        ) as TabsNewOutcome
        assertEquals("t2", created.tabId)
        assertEquals("t1", created.activeTabId)
        assertEquals(2, created.count)
        assertEquals(DshBrowserLoadState.LOADING, created.loadState)
        assertEquals("t1", engine.pages.keys.first())
    }
    @Test
    fun `tabs switch close and the replacement tab keep the model consistent`() = runTest {
        val engine = FakeBrowserEngine()
        val owner = session(engine)
        owner.openReady()
        owner.execute(call(DshBrowserOp.OPEN, DshBrowserArgs.Open("https://b.example", true)))
        val switched = owner.execute(call(DshBrowserOp.TABS_SWITCH, DshBrowserArgs.TabsSwitch("t1")))
            as TabsSwitchOutcome
        assertEquals("t1", switched.activeTabId)
        assertEquals(2, switched.count)

        val closedFirst = owner.execute(call(DshBrowserOp.TABS_CLOSE, DshBrowserArgs.TabsClose("t1")))
            as TabsCloseOutcome
        assertEquals("t1", closedFirst.closedTabId)
        assertEquals(1, closedFirst.count)
        assertEquals("t2", closedFirst.activeTabId)
        assertNull(closedFirst.newTabId)

        val closedLast = owner.execute(call(DshBrowserOp.TABS_CLOSE, DshBrowserArgs.TabsClose(null)))
            as TabsCloseOutcome
        assertEquals("t2", closedLast.closedTabId)
        assertEquals(1, closedLast.count)
        assertTrue(closedLast.newTabId != null)
        assertTrue(engine.closeTabCalls.containsAll(listOf("t1", "t2")))
    }

    @Test
    fun `snapshot is full first then a real diff then unchanged`() = runTest {
        val engine = FakeBrowserEngine()
        val owner = session(engine)
        owner.openReady()
        val full = owner.snapshotNow()
        assertTrue(full.full)
        assertEquals(4, full.nodeCount)
        assertEquals(4, full.nodes!!.size)
        assertEquals("r0000001", full.nodes.first().ref)

        val same = owner.execute(
            call(DshBrowserOp.SNAPSHOT, DshBrowserArgs.Snapshot(full.pageGeneration, false))
        ) as SnapshotOutcome
        assertFalse(same.full)
        assertTrue(same.unchanged)
        assertEquals(4, same.unchangedCount)
        assertTrue(same.added.isEmpty())
        assertTrue(same.newText.isEmpty())

        engine.page("t1").mutation += 1
        engine.page("t1").nodes = engine.page("t1").nodes
            .filter { it.ref != "r0000003" }.toMutableList()
        engine.page("t1").nodes.add(FakeBrowserEngine.node("r00000aa", "button", "Fresh"))
        engine.page("t1").text = engine.page("t1").text + "fresh line"
        val diff = owner.execute(
            call(DshBrowserOp.SNAPSHOT, DshBrowserArgs.Snapshot(full.pageGeneration, false))
        ) as SnapshotOutcome
        assertFalse(diff.full)
        assertFalse(diff.unchanged)
        assertEquals(listOf("r00000aa"), diff.added.map { it.ref })
        assertEquals(listOf("r0000003"), diff.removed)
        assertEquals(listOf("fresh line"), diff.newText)
        assertTrue(diff.textChanged)
        assertTrue(diff.pageGeneration > full.pageGeneration)
    }

    @Test
    fun `a found ref is immediately clickable and a stale ref is refused`() = runTest {
        val engine = FakeBrowserEngine()
        val owner = session(engine)
        owner.openReady()
        val found = owner.execute(call(DshBrowserOp.FIND, DshBrowserArgs.Find("Go", null, 5))) as FindOutcome
        assertTrue(found.matched >= 1)
        val ref = found.candidates.first().ref

        val clicked = owner.execute(call(DshBrowserOp.CLICK, DshBrowserArgs.Click(ref, null))) as ClickOutcome
        assertEquals("js", clicked.via)
        assertTrue(clicked.changed)
        assertEquals("r00000ff", engine.page("t1").nodes.last().ref)

        owner.openReady("https://example.com/next")
        val stale = owner.execute(call(DshBrowserOp.CLICK, DshBrowserArgs.Click("r0000002", null)))
        assertEquals(DshBrowserFailureReason.SNAPSHOT_REQUIRED, reason(stale))
    }

    @Test
    fun `click guards the snapshot, the ref shape, the tab and the target state`() = runTest {
        val engine = FakeBrowserEngine()
        val owner = session(engine)
        owner.openReady()
        assertEquals(
            DshBrowserFailureReason.SNAPSHOT_REQUIRED,
            reason(owner.execute(call(DshBrowserOp.CLICK, DshBrowserArgs.Click("r0000002", null))))
        )
        owner.snapshotNow()
        assertEquals(
            DshBrowserFailureReason.INVALID_REF,
            reason(owner.execute(call(DshBrowserOp.CLICK, DshBrowserArgs.Click("nope", null))))
        )
        assertEquals(
            DshBrowserFailureReason.STALE_REF,
            reason(owner.execute(call(DshBrowserOp.CLICK, DshBrowserArgs.Click("r00000ee", null))))
        )
        assertEquals(
            DshBrowserFailureReason.NO_SUCH_TAB,
            reason(owner.execute(call(DshBrowserOp.CLICK, DshBrowserArgs.Click("r0000002", "t9"))))
        )
        owner.execute(call(DshBrowserOp.OPEN, DshBrowserArgs.Open("https://b.example", true)))
        assertEquals(
            DshBrowserFailureReason.STALE_TAB,
            reason(owner.execute(call(DshBrowserOp.CLICK, DshBrowserArgs.Click("r0000002", "t1"))))
        )
        owner.execute(call(DshBrowserOp.TABS_SWITCH, DshBrowserArgs.TabsSwitch("t1")))
        assertEquals(
            DshBrowserFailureReason.ELEMENT_DISABLED,
            reason(owner.execute(call(DshBrowserOp.CLICK, DshBrowserArgs.Click("r0000004", null))))
        )
        engine.resolveReturnsNull = true
        assertEquals(
            DshBrowserFailureReason.STALE_REF,
            reason(owner.execute(call(DshBrowserOp.CLICK, DshBrowserArgs.Click("r0000002", null))))
        )
    }

    @Test
    fun `a click that did not change the page says so`() = runTest {
        val engine = FakeBrowserEngine()
        val owner = session(engine)
        owner.openReady()
        owner.snapshotNow()
        engine.clickAddsNode = false
        engine.clickMutates = false
        engine.clickMutatesWithoutChange = true
        val clicked = owner.execute(call(DshBrowserOp.CLICK, DshBrowserArgs.Click("r0000002", null))) as ClickOutcome
        assertFalse(clicked.changed)
        assertTrue(clicked.hint!!.contains("did not change"))
    }

    @Test
    fun `a click that navigates advances the generation and drops refs`() = runTest {
        val engine = FakeBrowserEngine()
        engine.clickNavigatesTo = "https://example.com/next"
        val owner = session(engine)
        owner.openReady()
        owner.snapshotNow()
        val clicked = owner.execute(call(DshBrowserOp.CLICK, DshBrowserArgs.Click("r0000002", null))) as ClickOutcome
        assertTrue(clicked.changed)
        assertEquals("https://example.com/next", clicked.url)
        assertTrue(clicked.toGeneration > clicked.fromGeneration)
        val afterNavigation = owner.execute(call(DshBrowserOp.CLICK, DshBrowserArgs.Click("r0000002", null)))
        assertEquals(DshBrowserFailureReason.SNAPSHOT_REQUIRED, reason(afterNavigation))
    }

    @Test
    fun `type refuses a non editable target and reports the observed value`() = runTest {
        val engine = FakeBrowserEngine()
        val owner = session(engine)
        owner.openReady()
        owner.snapshotNow()
        val refused = owner.execute(
            call(DshBrowserOp.TYPE, DshBrowserArgs.TypeAction("r0000002", "hi", true, null))
        )
        assertEquals(DshBrowserFailureReason.NOT_EDITABLE, reason(refused))

        val typed = owner.execute(
            call(DshBrowserOp.TYPE, DshBrowserArgs.TypeAction("r0000001", "hello", true, null))
        ) as TypeOutcome
        assertEquals("r0000001", typed.ref)
        assertEquals("hello", typed.value)
        assertEquals("setter", typed.via)
        assertTrue(typed.dispatched)
        assertTrue(typed.changed)
    }

    @Test
    fun `read caps long text and reports form fields structurally`() = runTest {
        val engine = FakeBrowserEngine()
        val owner = session(engine)
        owner.openReady()
        owner.snapshotNow()
        engine.readLong = true
        val long = owner.execute(call(DshBrowserOp.READ, DshBrowserArgs.Read(null, null))) as ReadOutcome
        assertTrue(long.truncated)
        assertEquals(DshBrowserLimits.MAX_TEXT_CHARS, long.text.length)
        assertEquals(9_000, long.chars)

        val field = owner.execute(
            call(DshBrowserOp.READ, DshBrowserArgs.Read("r0000001", null))
        ) as ReadOutcome
        assertTrue(field.isField)
        assertEquals("typed value", field.value)
        assertTrue(field.hint!!.contains("value"))
    }

    @Test
    fun `scroll reports the real position per direction`() = runTest {
        val engine = FakeBrowserEngine()
        val owner = session(engine)
        owner.openReady()
        engine.scrollY = 0
        val down = owner.execute(
            call(DshBrowserOp.SCROLL, DshBrowserArgs.Scroll(DshBrowserScrollDirection.DOWN, 700))
        ) as ScrollOutcome
        assertEquals(700, down.scrollY)
        val up = owner.execute(
            call(DshBrowserOp.SCROLL, DshBrowserArgs.Scroll(DshBrowserScrollDirection.UP, 300))
        ) as ScrollOutcome
        assertEquals(400, up.scrollY)
        val top = owner.execute(
            call(DshBrowserOp.SCROLL, DshBrowserArgs.Scroll(DshBrowserScrollDirection.TOP, 700))
        ) as ScrollOutcome
        assertEquals(0, top.scrollY)
        val bottom = owner.execute(
            call(DshBrowserOp.SCROLL, DshBrowserArgs.Scroll(DshBrowserScrollDirection.BOTTOM, 700))
        ) as ScrollOutcome
        assertEquals(4000, bottom.scrollY)
        assertEquals(4000, bottom.docHeight)
    }

    @Test
    fun `nav no history is a typed failure and reload advances the generation`() = runTest {
        val engine = FakeBrowserEngine()
        val owner = session(engine)
        val opened = owner.openReady()
        engine.navNoHistory = true
        val noHistory = owner.execute(call(DshBrowserOp.NAV, DshBrowserArgs.Nav(DshBrowserNavOp.BACK)))
        assertEquals(DshBrowserFailureReason.NO_HISTORY, reason(noHistory))
        val after = owner.execute(call(DshBrowserOp.TABS_LIST, DshBrowserArgs.TabsList)) as TabsListOutcome
        assertEquals(opened.pageGeneration, after.tabs.single().pageGeneration)

        engine.navNoHistory = false
        val reloaded = owner.execute(call(DshBrowserOp.NAV, DshBrowserArgs.Nav(DshBrowserNavOp.RELOAD)))
            as NavOutcome
        assertFalse(reloaded.closed)
        assertTrue(reloaded.pageGeneration > opened.pageGeneration)
    }

    @Test
    fun `nav close releases every tab and later calls fail closed`() = runTest {
        val engine = FakeBrowserEngine()
        val owner = session(engine)
        owner.openReady()
        val closed = owner.execute(call(DshBrowserOp.NAV, DshBrowserArgs.Nav(DshBrowserNavOp.CLOSE)))
            as NavOutcome
        assertTrue(closed.closed)
        assertTrue(owner.isClosed)
        assertTrue(engine.closed)
        assertTrue(engine.pages.isEmpty())
        val after = owner.execute(call(DshBrowserOp.OPEN, DshBrowserArgs.Open("https://example.com", false)))
        assertEquals(DshBrowserFailureReason.BROWSER_CLOSED, reason(after))
        val caps = owner.execute(call(DshBrowserOp.CAPS, DshBrowserArgs.Caps))
        assertTrue(caps is CapsOutcome)
    }

    @Test
    fun `a per-call timeout is a typed failure that drops refs`() = runTest {
        val engine = FakeBrowserEngine()
        val owner = session(engine)
        owner.openReady()
        owner.snapshotNow()
        engine.clickDelayMillis = 60_000
        val timedOut = owner.execute(
            call(DshBrowserOp.CLICK, DshBrowserArgs.Click("r0000002", null), timeoutMillis = 1_000)
        )
        assertEquals(DshBrowserFailureReason.TIMEOUT, reason(timedOut))
        engine.clickDelayMillis = 0
        val afterTimeout = owner.execute(call(DshBrowserOp.CLICK, DshBrowserArgs.Click("r0000002", null)))
        assertEquals(DshBrowserFailureReason.SNAPSHOT_REQUIRED, reason(afterTimeout))
    }

    @Test
    fun `cancellation propagates and the unknown outcome drops refs`() = runTest {
        val engine = FakeBrowserEngine()
        val owner = session(engine)
        owner.openReady()
        owner.snapshotNow()
        engine.clickDelayMillis = 60_000
        val pending = async {
            owner.execute(call(DshBrowserOp.CLICK, DshBrowserArgs.Click("r0000002", null)))
        }
        delay(1)
        pending.cancel()
        try {
            pending.await()
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
            // expected
        }
        engine.clickDelayMillis = 0
        val afterCancel = owner.execute(call(DshBrowserOp.CLICK, DshBrowserArgs.Click("r0000002", null)))
        assertEquals(DshBrowserFailureReason.SNAPSHOT_REQUIRED, reason(afterCancel))
    }

    @Test
    fun `an engine unavailable fault maps to a typed engine failure`() = runTest {
        val fake = FakeBrowserEngine()
        val engine = object : DshBrowserEnginePort by fake {
            override suspend fun observe(tabId: String, includeText: Boolean): DshBrowserObservation {
                delay(1)
                throw DshBrowserEngineException(DshBrowserEngineError.UNAVAILABLE, "no webview provider")
            }
        }
        val owner = DshBrowserSession(engine, isolated())
        val opened = owner.execute(call(DshBrowserOp.OPEN, DshBrowserArgs.Open("https://example.com", false)))
        assertTrue(opened is OpenOutcome)
        val snapshot = owner.execute(call(DshBrowserOp.SNAPSHOT, DshBrowserArgs.Snapshot(null, false)))
        assertEquals(DshBrowserFailureReason.ENGINE_UNAVAILABLE, reason(snapshot))
    }
}
