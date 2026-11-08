package com.muyuchat.mca

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V03 app-shell motion contract.
 *
 * `src/test` has no Compose UI host, so these tests pin the production wiring in `MainActivity.kt`:
 * which container owns the tab transition, that every tab shares the one McaMotion fade, that the
 * chat base layer is never wrapped (its state cannot be disposed by a tab switch), and that the
 * splash -> app handoff is a crossfade instead of the previous early-return hard cut. They do not
 * claim visual verification; real frame/recording evidence is collected by the measurement task.
 */
class MainShellMotionContractTest {
    private val shell = sourceFile("MainActivity.kt").replace("\r\n", "\n")

    /**
     * The first-frame shell: it owns the one `McaTheme` node and the guide/app gate, so the
     * "exactly one theme node" contract is counted there and the shell is checked to add none.
     */
    private val firstRunHost = sourceFile("FirstRunHost.kt").replace("\r\n", "\n")

    @Test
    fun tabOverlaysAllUseTheSharedCrossfadeAndMcaMotionFade() {
        assertEquals(
            "every overlay tab (chat stays the base layer) must go through the one crossfade wrapper",
            8,
            countMatches("\\n            CrossfadeTabPage\\(")
        )
        assertEquals(
            "the crossfade spec must come from McaMotion, not a local tween",
            1,
            countMatches("animationSpec = McaMotion\\.fade\\(TAB_SWITCH_FADE_MILLIS\\)")
        )
        // The helper pairs each visibility flag with its saveable-state key, so a tab cannot be
        // added or renamed without also keeping its rememberSaveable state across the fade.
        assertEquals(
            "each tab's visibility must stay paired with its saveable-state key",
            8,
            countMatches(
                "CrossfadeTabPage\\(\\n" +
                    "                visible = state\\.tab == AppTab\\.(\\w+),\\n" +
                    "                stateHolder = tabStateHolder,\\n" +
                    "                tabKey = AppTab\\.\\1\\.name"
            )
        )
        assertTrue(
            "the wrapper must still host the existing SwipeBackPage",
            shell.contains("SwipeBackPage(")
        )
        assertTrue(
            "the wrapper's crossfade target must be the tab visibility flag",
            shell.contains("targetState = visible")
        )
        assertEquals(
            "all overlays must be hosted by the crossfade wrapper, not raw SwipeBackPage calls",
            1,
            countMatches("SwipeBackPage\\(")
        )
    }

    @Test
    fun tabFadeAndHandoffStayInsideTheMcaMotionWindow() {
        assertTrue(
            "tab fade must come from the shared motion language",
            shell.contains("McaMotion.fade(TAB_SWITCH_FADE_MILLIS)")
        )
        assertTrue(
            "startup handoff must come from the shared motion language",
            shell.contains("McaMotion.fade(STARTUP_HANDOFF_FADE_MILLIS)")
        )
        val tabFade = intConstant("TAB_SWITCH_FADE_MILLIS")
        val handoffFade = intConstant("STARTUP_HANDOFF_FADE_MILLIS")
        assertTrue("tab fade $tabFade ms must stay in the 180..220 ms contract", tabFade in 180..220)
        assertTrue("handoff fade $handoffFade ms must stay in the 180..220 ms contract", handoffFade in 180..220)
        // No local motion spec or animation value may drift from the shared language; Crossfade and
        // McaMotion already read their animated values in the layer/draw phase.
        listOf(
            "tween(",
            "spring(",
            "keyframes(",
            "animateFloatAsState",
            "animateDpAsState",
            "animateColorAsState",
            "rememberInfiniteTransition",
            "Animatable(",
        ).forEach { token ->
            assertFalse("MainActivity must not add its own animation spec/value: $token", shell.contains(token))
        }
    }

    @Test
    fun chatBaseLayerStaysComposedOutsideEveryTabCrossfade() {
        val chat = shell.indexOf("ChatRoute(")
        val firstOverlay = shell.indexOf("\n            CrossfadeTabPage(")
        assertTrue("ChatRoute( must exist", chat >= 0)
        assertTrue("the chat page must stay before (below) every tab crossfade", firstOverlay > chat)
        assertFalse(
            "chat must not become a disposable crossfade branch; its ChatViewModel/draft/scroll state lives there",
            shell.contains("AppTab.CHAT.name")
        )
        assertEquals(
            "one saveable holder must serve all overlay tabs",
            1,
            countMatches("rememberSaveableStateHolder\\(\\)")
        )
        assertTrue(
            "overlay tab state must be restored through the saveable holder",
            shell.contains("stateHolder.SaveableStateProvider(tabKey)")
        )
        listOf("AgentScreen(", "ModelHubScreen(", "PresetManagerScreen(", "LocalApiToolScreen(", "SettingsHubScreen(", "AgentWorkspaceRoute(", "DshWorkspaceRoute(", "VideoWorkspaceRoute(").forEach { screen ->
            assertTrue("$screen must still be the tab's visible content", shell.contains(screen))
        }
    }

    @Test
    fun splashToAppHandoffCrossfadesInsteadOfHardCutting() {
        assertTrue(
            "the handoff target must be the ready app model",
            shell.contains("targetState = startupState.model")
        )
        assertTrue(
            "the handoff must use the named crossfade",
            shell.contains("label = \"startup-to-app\"")
        )
        assertTrue(shell.contains("if (readyModel == null)"))
        assertTrue(shell.contains("AppStartupScreen("))
        assertTrue(shell.contains("McaApp("))
        // Both branch calls keep their existing ownership wiring; the handoff only wraps them.
        assertTrue(shell.contains("onRetry = { startup.start() }"))
        assertTrue(shell.contains("onSkipScan = { startup.skipModelDiscovery() }"))
        assertTrue(shell.contains("state = state"))
        assertTrue(shell.contains("viewModel = viewModel"))
        assertFalse(
            "the old early-return hard cut must not come back",
            shell.contains("return@setContent")
        )
        assertEquals(
            "both splash and app must share exactly one McaTheme node during the handoff",
            1,
            // Counts the theme *call*, not one particular argument list: the contract being pinned is
            // "exactly one theme node owns both branches", which must not depend on whether arguments
            // are supplied. The node now lives in FirstRunContent (which owns the guide branch too), so
            // the count is taken there and the shell is required to add none of its own.
            countMatches(firstRunHost, "McaTheme\\(")
        )
        assertEquals(
            "the shell must not open a second theme node around the app body",
            0,
            countMatches("McaTheme\\(")
        )
        val target = shell.indexOf("targetState = startupState.model")
        assertTrue(target >= 0)
        assertTrue(shell.indexOf("AppStartupScreen(") > target)
        assertTrue(shell.indexOf("McaApp(") > target)
    }

    @Test
    fun shellMotionIntroducesNoVisualTokensAndNoNewSideEffects() {
        listOf("Color(", "fontSize", ".dp", ".sp").forEach { token ->
            assertFalse("shell motion must not change visual tokens: $token", shell.contains(token))
        }
        // The crossfade itself is a pure appearance transition: it must not introduce a timer,
        // collector or other composition side effect into the shell.
        listOf("LaunchedEffect", "DisposableEffect", "SideEffect").forEach { token ->
            assertFalse("shell motion must not add a new composition side effect: $token", shell.contains(token))
        }
    }

    @Test
    fun shellFileOwnsNoClickableSurfaceSoPressScaleHasNoTargetHere() {
        // Requirement 3 asks for mcaPressScale on clickable top-level controls. This file composes
        // no clickable surface of its own: every tappable control lives inside the feature-owned
        // screens and dialogs it calls. mcaPressScale has no legal target in MainActivity.kt, and a
        // wrapper around a whole screen would change layout geometry.
        listOf(
            "Modifier.clickable",
            "combinedClickable",
            "InteractionSource",
            "interactionSource",
            "Button(",
            "IconButton(",
            "mcaPressScale",
        ).forEach { token ->
            assertFalse("unexpected shell-owned clickable surface: $token", shell.contains(token))
        }
    }

    private fun intConstant(name: String): Int {
        val match = Regex("$name\\s*=\\s*(\\d+)").find(shell)
            ?: error("Missing int constant $name in MainActivity.kt")
        return match.groupValues[1].toInt()
    }

    private fun countMatches(pattern: String): Int = Regex(pattern).findAll(shell).count()

    /** Same counter against the first-frame shell, where the theme node and the gate now live. */
    private fun countMatches(source: String, pattern: String): Int = Regex(pattern).findAll(source).count()

    private fun sourceFile(name: String): String = sequenceOf(
        File("src/main/java/com/muyuchat/mca/$name"),
        File("app/src/main/java/com/muyuchat/mca/$name")
    ).firstOrNull(File::isFile)?.readText(Charsets.UTF_8)
        ?: error("Unable to locate $name")
}
