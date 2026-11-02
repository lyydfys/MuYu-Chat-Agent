package com.muyuchat.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R12 real-device follow-up contract: composer menu accessibility exposure and height budget.
 *
 * ## What the device evidence actually shows
 *
 * Runs `device-behavior/20261021-032030-968Z` and `device-behavior-r12wire/20261021-070742-841Z`
 * dump the menu rows (`dumpRows=5`) while `popupWindows=0`; the 1x/1.5x/2x sample run
 * `font-scale/20261021-041216-411Z` dumps 9 / 9 / 8 rows. The runs with `dumpRows=0` are not a
 * menu-configuration problem:
 *
 * - `device-behavior-candidate/20261021-051333-555Z` has the *same APK* (sha256 `94af094d…`) as the
 *   passing `20261021-070742-841Z` on the same device; its menu-open screenshot shows **no menu**,
 *   and every later dump in that run belongs to `com.android.quicksearchbox`/`com.miui.home`/
 *   `com.android.email`, i.e. the app had left the foreground. The tap never opened the menu.
 * - `device-behavior/20261020-222628-494Z` *does* show the menu in the screenshot while its dump is
 *   the pre-menu tree (47 nodes / 16707 bytes, identical to the chat dump). That is a stale
 *   accessibility hierarchy, and the same APK+device dumped 9 rows in `20261021-041216-411Z`.
 *
 * So the menu is correctly exposed; the flaky signal comes from the capture (stale dump / app not
 * in front), and `popupWindows=0` is by design because the sheet is an **in-window overlay**, not a
 * `Popup`/`Dialog`. The contract below locks that configuration so a future "fix" cannot turn the
 * sheet into a popup window (which would move it, change the frozen geometry, and still not fix a
 * stale dump), and keeps the names the accessibility audit depends on.
 */
class ChatComposerMenuPopupContractTest {

    @Test
    fun `menu sheet is an in-window overlay and owns no popup window`() {
        val sheet = source("ChatComposerActionMenuViews.kt")

        // No popup/dialog wrapper anywhere in the menu: `popupWindows=0` in every device dump is the
        // expected result, not a defect. Adding one would change the frozen placement.
        assertFalse(sheet.contains("androidx.compose.ui.window.Popup"))
        assertFalse(sheet.contains("PopupProperties"))
        assertFalse(sheet.contains("Dialog("))
        assertFalse(sheet.contains("DialogProperties"))

        // It renders inside the host's live constraint box, in the same window as the composer, and
        // through AnimatedVisibility so the exit keeps the rows composed until removal.
        assertTrue(sheet.contains("BoxWithConstraints("))
        assertTrue(sheet.contains("AnimatedVisibility("))
        assertTrue(sheet.contains("Surface("))
        assertTrue(sheet.contains("visible = menu.menu.wantsVisible,"))
    }

    @Test
    fun `menu scrim keeps its accessibility name and dismiss label`() {
        // Both lines belong to the R12 a11y fix; the audit (`unnamed clickables`) depends on them.
        val sheet = source("ChatComposerActionMenuViews.kt")
        assertTrue(sheet.contains(".semantics { contentDescription = \"关闭菜单\" }"))
        assertTrue(sheet.contains(".clickable(onClickLabel = \"关闭\") { menu.dismiss() }"))
    }

    @Test
    fun `rows keep visible text and one phase-gated click action`() {
        val sheet = source("ChatComposerActionMenuViews.kt")

        // Rows stay named by their visible label (Compose exposes the row plus its child text nodes);
        // no semantics-clearing wrapper may hide them, and no row may become untouchable/invisible.
        assertTrue(sheet.contains("text = label,"))
        assertFalse(sheet.contains("clearAndSetSemantics"))
        assertFalse(sheet.contains("invisibleToUser"))
        assertFalse(sheet.contains("semantics(mergeDescendants = false)"))

        // The action is gated by the lifecycle phase, not by the row's colours (the exit stays
        // painted with its last colours while it refuses business actions).
        assertTrue(sheet.contains(".clickable(enabled = interactive, onClick = onClick)"))
        assertTrue(sheet.contains("val interactive = enabled && actionable"))
    }

    @Test
    fun `exit keeps rows in the accessibility tree until the layer is removed`() {
        val sheet = source("ChatComposerActionMenuViews.kt")

        // Composed while open *and* closing: a TalkBack focus that sits on a row does not jump
        // before the exit finishes, and the hierarchy keeps the rows for the whole fade.
        assertTrue(sheet.contains("menu.menu.onContentComposed()"))
        assertTrue(sheet.contains("onDispose { menu.menu.onContentDisposed() }"))
        assertTrue(sheet.contains("if (menu.isComposed) {"))
    }

    @Test
    fun `sheet height is capped by the real host height and scrolls when content overflows`() {
        val sheet = source("ChatComposerActionMenuViews.kt")

        assertTrue(sheet.contains("Modifier.heightIn(max = heightBudget.maxHeightDp.dp)"))
        assertTrue(sheet.contains(".verticalScroll(scrollState, enabled = heightBudget.scrollable)"))
        assertTrue(sheet.contains("constraints.hasBoundedHeight"))
        // The old fixed 520dp cap must not come back: it ignored the IME and short screens.
        assertFalse(sheet.contains("heightIn(max = 520"))
    }

    @Test
    fun `one x and one point five x content fit without scrolling on the sampled device`() {
        // 2304FPN6DC: 1440x3200 @ density 560 => 914dp window; the menu's own navigationBarsPadding
        // and the frozen 86dp placement leave ~767dp for the sheet (matches the 2x screen capture:
        // sheet top ~40dp, bottom padding ends just above the composer).
        val host = 853
        val oneX = composerMenuHeightBudget(hostAvailableHeightDp = host, contentHeightDp = ONE_X_CONTENT_DP)
        val oneAndHalfX = composerMenuHeightBudget(hostAvailableHeightDp = host, contentHeightDp = ONE_POINT_FIVE_X_CONTENT_DP)

        assertEquals(host - ComposerMenuMetrics.SHEET_BOTTOM_OFFSET_DP, oneX.maxHeightDp)
        assertFalse("1x: every row fits", oneX.scrollable)
        assertTrue(oneX.maxHeightDp >= ONE_X_CONTENT_DP)

        assertFalse("1.5x: every row still fits", oneAndHalfX.scrollable)
        assertTrue(oneAndHalfX.maxHeightDp >= ONE_POINT_FIVE_X_CONTENT_DP)
    }

    @Test
    fun `two x content overflows into the scroll container and keeps the last row reachable`() {
        val budget = composerMenuHeightBudget(
            hostAvailableHeightDp = 853,
            contentHeightDp = TWO_X_CONTENT_DP,
        )

        assertTrue("2x: the sheet must scroll instead of clipping", budget.scrollable)
        assertTrue(budget.maxHeightDp < TWO_X_CONTENT_DP)
        // The viewport is still tall enough for several full rows, so scrolling is a real gesture,
        // not a one-row slot.
        assertTrue(budget.maxHeightDp >= ComposerMenuMetrics.ROW_MIN_HEIGHT_DP * 3)
    }

    @Test
    fun `scrollability is monotone in content height and the cap is monotone in host height`() {
        var scrollableSeen = false
        for (contentHeightDp in listOf(0, 200, 421, 645, 766, 767, 800, 1_200)) {
            val budget = composerMenuHeightBudget(
                hostAvailableHeightDp = 853,
                contentHeightDp = contentHeightDp,
            )
            if (scrollableSeen) {
                assertTrue("content $contentHeightDp must stay scrollable", budget.scrollable)
            }
            scrollableSeen = scrollableSeen || budget.scrollable
        }

        var previousCap = 0
        for (hostHeightDp in listOf(0, 40, 86, 87, 200, 340, 853, 1_400)) {
            val cap = composerMenuHeightBudget(hostHeightDp, contentHeightDp = 0).maxHeightDp
            assertTrue("cap must not shrink when the host grows (host=$hostHeightDp)", cap >= previousCap)
            previousCap = cap
        }
    }

    @Test
    fun `budget never drops below one row and always fits a realistically sized host`() {
        val floor = ComposerMenuMetrics.ROW_MIN_HEIGHT_DP
        for (hostHeightDp in listOf(0, 40, 86, 87, 120, 200, 340, 900)) {
            val budget = composerMenuHeightBudget(hostHeightDp, contentHeightDp = 0)
            assertTrue(
                "host=$hostHeightDp: the cap must keep at least one row",
                budget.maxHeightDp >= floor
            )
            if (hostHeightDp >= ComposerMenuMetrics.SHEET_BOTTOM_OFFSET_DP + floor) {
                assertEquals(hostHeightDp - ComposerMenuMetrics.SHEET_BOTTOM_OFFSET_DP, budget.maxHeightDp)
                assertTrue(
                    "host=$hostHeightDp: sheet + placement must not exceed the host",
                    budget.maxHeightDp + ComposerMenuMetrics.SHEET_BOTTOM_OFFSET_DP <= hostHeightDp
                )
            }
        }
    }

    private fun source(name: String): String {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            val candidate = File(
                directory,
                "feature/chat/src/main/java/com/muyuchat/feature/chat/$name"
            )
            if (candidate.isFile) return candidate.readText(Charsets.UTF_8)
            directory = directory.parentFile
        }
        error("Unable to locate $name")
    }

    private companion object {
        /**
         * Measured from `artifacts/ui-quality/v9-closeout/font-scale/20261021-041216-411Z`:
         * first row top to last row bottom at density 560 is 1388px (≈397dp) at 1x, plus the sheet's
         * 24dp vertical padding => ≈421dp. At 1.5x it is 2174px (≈621dp) + 24dp => ≈645dp.
         */
        const val ONE_X_CONTENT_DP = 421
        const val ONE_POINT_FIVE_X_CONTENT_DP = 645

        /**
         * 2x is taller than its 743dp visible viewport in the same capture (the last row is cut by
         * the scroll viewport, which is why the sheet scrolls); 800dp is a conservative lower bound
         * above the 767dp cap, and the exact value is not needed for the contract.
         */
        const val TWO_X_CONTENT_DP = 800
    }
}
