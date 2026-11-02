package com.muyuchat.feature.agent.workspace

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.muyuchat.core.designsystem.McaTheme
import com.muyuchat.feature.agent.workspace.ui.AgentWorkspaceContent
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * REQUIRES A CONNECTED DEVICE/EMULATOR. Execution status for this delivery: NOT_RUN. The task
 * forbids Gradle/build/device runs, so none of these tests has been executed and none may be
 * reported as passing.
 *
 * Coverage intent (real Compose layout/semantics, no screenshot or pixel assertion):
 * - the single hamburger exposes its accessible name, is clickable and keeps the 48dp target;
 * - the home header does not duplicate the drawer exit;
 * - the drawer footer exit is displayed and invokes the existing back callback after opening;
 * - the home header is one line: 18dp start, 48dp menu, 12dp gap, a 30dp MCA mark, 10dp gap and
 *   the "MCA Agent" title, so the title begins 52dp after the menu target and shares its centre;
 * - the header renders exactly one title line and no subtitle line below it;
 * - fontScale 2.0 re-measures the single title line without clipping it or overlapping the body;
 * - dark theme keeps the header and the drawer exit inside the viewport.
 *
 * Not covered here because it needs human/visual verification: glyph and border optical quality,
 * colour contrast in dark mode, TalkBack announcement order, ripple/focus behaviour and whether a
 * partially clipped footer is still acceptable on unusually short screens.
 */
@RunWith(AndroidJUnit4::class)
class AgentHomeHeaderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `hamburger exposes its accessible name and a 48dp target`() {
        setHomeContent()

        val hamburger = composeRule.onNodeWithContentDescription(HAMBURGER)
        hamburger.assertIsDisplayed()
        hamburger.assertHasClickAction()
        hamburger.assertWidthIsAtLeast(48.dp)
        hamburger.assertHeightIsAtLeast(48.dp)

        composeRule.onAllNodesWithContentDescription(HAMBURGER).assertCountEquals(1)
        // The title is a label, not a second hit target for the same action.
        composeRule.onNodeWithText(TITLE).assertIsDisplayed().assertHasNoClickAction()
    }

    @Test
    fun `home header has one menu and no exit icon`() {
        setHomeContent()

        val headerBottomPx = composeRule.onNodeWithContentDescription(HAMBURGER)
            .fetchSemanticsNode()
            .boundsInRoot
            .bottom
        val exitsInsideHeader = composeRule.onAllNodesWithContentDescription(EXIT)
            .fetchSemanticsNodes()
            .filter { it.boundsInRoot.top < headerBottomPx }

        // The drawer content may already be composed offscreen; only its absence from the top bar
        // is asserted here. The opened drawer is checked in the reachability test below.
        assertTrue(
            "the exit control must live in the drawer footer, not in the home top bar",
            exitsInsideHeader.isEmpty()
        )
        composeRule.onAllNodesWithContentDescription(HAMBURGER).assertCountEquals(1)
    }

    @Test
    fun `drawer exit is reachable after opening the drawer`() {
        val backInvocations = AtomicInteger(0)
        setHomeContent(
            actions = AgentWorkspaceActions(onBack = { backInvocations.incrementAndGet() })
        )

        composeRule.onNodeWithContentDescription(HAMBURGER).performClick()
        waitUntilTagDisplayed(DRAWER_TAG)
        waitUntilDescriptionDisplayed(EXIT)

        composeRule.onAllNodesWithContentDescription(EXIT).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(EXIT)
            .filter(hasAnyAncestor(hasTestTag(DRAWER_TAG)))
            .assertCountEquals(1)

        val exit = composeRule.onNodeWithContentDescription(EXIT)
        exit.assertIsDisplayed()
        exit.assertHasClickAction()
        exit.assertHeightIsAtLeast(48.dp)
        exit.performClick()

        composeRule.waitUntil(5_000L) { backInvocations.get() == 1 }
        assertEquals(1, backInvocations.get())
    }

    @Test
    fun `title is centred with the hamburger in the frozen top bar grid`() {
        setHomeContent()

        val hamburger = composeRule.onNodeWithContentDescription(HAMBURGER)
            .getUnclippedBoundsInRoot()
        val title = composeRule.onNodeWithText(TITLE).getUnclippedBoundsInRoot()
        val bodyTop = composeRule.onNodeWithTag("agent.home").getUnclippedBoundsInRoot().top.value

        assertEquals("18dp start inset", 18f, hamburger.left.value, 1.5f)
        assertEquals("14dp top inset", 14f, hamburger.top.value, 1.5f)
        assertEquals(
            "52dp title gap = 12dp spacer + 30dp mark + 10dp spacer",
            52f,
            title.left.value - hamburger.right.value,
            1.5f
        )

        // The 30dp MCA mark, not extra whitespace, fills the gap between the menu and the title.
        val hamburgerNode = composeRule.onNodeWithContentDescription(HAMBURGER)
            .fetchSemanticsNode()
        val titleNode = composeRule.onNodeWithText(TITLE).fetchSemanticsNode()
        val marksInGap = composeRule.onAllNodesWithContentDescription(BRAND_MARK)
            .fetchSemanticsNodes()
            .filter { mark ->
                mark.boundsInRoot.left >= hamburgerNode.boundsInRoot.right &&
                    mark.boundsInRoot.right <= titleNode.boundsInRoot.left
            }
        assertEquals("exactly one brand mark fills the title gap", 1, marksInGap.size)
        val markWidthDp = with(composeRule.density) {
            marksInGap.single().boundsInRoot.width.toDp().value
        }
        assertEquals("the brand mark keeps the 30dp size", 30f, markWidthDp, 1f)

        val hamburgerCenterY = (hamburger.top.value + hamburger.bottom.value) / 2f
        val titleCenterY = (title.top.value + title.bottom.value) / 2f
        assertTrue(
            "the title must share the hamburger's vertical centre (delta ${abs(hamburgerCenterY - titleCenterY)}dp)",
            abs(hamburgerCenterY - titleCenterY) <= 1.5f
        )
        assertTrue(
            "the top bar must not overlap the scrolling body",
            title.bottom.value <= bodyTop + 0.5f
        )
    }

    @Test
    fun `home header renders a single one-line MCA Agent title with no subtitle`() {
        setHomeContent()

        composeRule.onAllNodesWithText(TITLE).assertCountEquals(1)
        val titleInteraction = composeRule.onNodeWithText(TITLE)
        titleInteraction.assertIsDisplayed()
        val titleBounds = titleInteraction.fetchSemanticsNode().boundsInRoot
        val titleHeightDp = with(composeRule.density) { titleBounds.height.toDp().value }
        // titleSmall is a 20sp line; a second measured line would be roughly double this.
        assertTrue(
            "the title must stay on one text line (measured ${titleHeightDp}dp)",
            titleHeightDp <= 30f
        )

        // The band between the title's baseline and the scrolling body is where a stacked
        // subtitle used to live; the one-line header must leave it empty.
        val bodyTopPx = composeRule.onNodeWithTag("agent.home")
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val subtitleLines = composeRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)
        ).fetchSemanticsNodes().filter { node ->
            val bounds = node.boundsInRoot
            bounds.left >= titleBounds.left - 0.5f &&
                bounds.top >= titleBounds.bottom - 0.5f &&
                bounds.bottom <= bodyTopPx + 0.5f
        }
        assertTrue(
            "the header must not render a subtitle line below the title",
            subtitleLines.isEmpty()
        )
    }

    @Test
    fun `font scale two keeps the title inside the top bar and the target intact`() {
        setHomeContent(fontScale = 2f)

        val hamburger = composeRule.onNodeWithContentDescription(HAMBURGER)
        hamburger.assertIsDisplayed()
        hamburger.assertWidthIsAtLeast(48.dp)
        hamburger.assertHeightIsAtLeast(48.dp)

        val titleInteraction = composeRule.onNodeWithText(TITLE)
        titleInteraction.assertIsDisplayed()
        val title = titleInteraction.getUnclippedBoundsInRoot()
        val bodyTop = composeRule.onNodeWithTag("agent.home").getUnclippedBoundsInRoot().top.value
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        val titleHeight = title.bottom.value - title.top.value

        // Proves the density override reached the text pass; otherwise the clipping assertions
        // below would only prove that the normal-size header still fits.
        assertTrue(
            "fontScale 2.0 must actually grow the title (measured ${titleHeight}dp)",
            titleHeight >= 28f
        )
        // 1.5 lines at 2.0x is <= 60dp; a wrapped second line would exceed it.
        assertTrue(
            "fontScale 2.0 must keep the title on one line (measured ${titleHeight}dp)",
            titleHeight <= 60f
        )
        assertTrue("the title must not overlap the body", title.bottom.value <= bodyTop + 0.5f)
        assertTrue("the title's left edge must stay on screen", title.left.value >= root.left.value)
        assertTrue("the title's right edge must stay on screen", title.right.value <= root.right.value)
        assertTrue("the title's bottom must stay on screen", title.bottom.value <= root.bottom.value)
    }

    @Test
    fun `dark theme keeps the header and drawer exit inside the viewport`() {
        setHomeContent(darkTheme = true)

        composeRule.onNodeWithContentDescription(HAMBURGER).assertIsDisplayed()
        composeRule.onNodeWithText(TITLE).assertIsDisplayed()
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        val title = composeRule.onNodeWithText(TITLE).getUnclippedBoundsInRoot()
        assertTrue(title.left.value >= root.left.value)
        assertTrue(title.right.value <= root.right.value)
        assertTrue(title.bottom.value <= root.bottom.value)

        composeRule.onNodeWithContentDescription(HAMBURGER).performClick()
        waitUntilDescriptionDisplayed(EXIT)
        composeRule.onNodeWithContentDescription(EXIT).assertIsDisplayed()
    }

    @Test
    fun `drawer footer exit stays reachable at font scale two`() {
        setHomeContent(fontScale = 2f)

        composeRule.onNodeWithContentDescription(HAMBURGER).performClick()
        waitUntilDescriptionDisplayed(EXIT)

        val exit = composeRule.onNodeWithContentDescription(EXIT)
        exit.assertIsDisplayed()
        exit.assertHasClickAction()
        exit.assertHeightIsAtLeast(48.dp)
    }

    private fun setHomeContent(
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
        actions: AgentWorkspaceActions = AgentWorkspaceActions()
    ) {
        composeRule.setContent {
            McaTheme(darkTheme = darkTheme) {
                val baseDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = baseDensity.density,
                        fontScale = fontScale
                    )
                ) {
                    AgentWorkspaceContent(
                        state = emptyHomeState(),
                        actions = actions,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    private fun emptyHomeState(): AgentWorkspaceUiState = AgentWorkspaceUiState(
        home = AgentHomeUiState(
            status = AgentWorkspaceStatus.EMPTY,
            loading = false,
            empty = true,
            endReached = true
        )
    )

    private fun waitUntilTagDisplayed(tag: String) {
        composeRule.waitUntil(5_000L) {
            runCatching {
                composeRule.onNodeWithTag(tag).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun waitUntilDescriptionDisplayed(description: String) {
        composeRule.waitUntil(5_000L) {
            runCatching {
                composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
            }.isSuccess
        }
    }

    private companion object {
        const val HAMBURGER = "打开任务与工具抽屉"
        const val EXIT = "返回对话"
        const val TITLE = "MCA Agent"
        const val BRAND_MARK = "MCA 品牌标志"
        const val DRAWER_TAG = "agent.drawer"
    }
}
