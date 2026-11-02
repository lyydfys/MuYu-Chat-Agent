package com.muyuchat.feature.agent.workspace

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STATIC source contract for the frozen Agent chrome.
 *
 * These tests read the production sources as text and guard:
 * - the accessible name and the 48dp round target of the single hamburger;
 * - the absent duplicate exit in the header and the single drawer-footer exit;
 * - the 14/10 top-bar grid with the one-line "MCA Agent" title: 18dp start, 48dp target, 12dp
 *   gap, 30dp BrandMark and 10dp gap, so the title starts 52dp after the menu target's right edge;
 * - the absence of a second/subtitle text line in the top bar;
 * - the 0.72/300dp drawer sheet;
 * - the detail composer's run-stats strip: after the composer Surface and as the last child of
 *   its Column (never above the composer).
 *
 * They are NOT UI behavior proofs. Source text cannot observe actual hit testing, rendering,
 * fontScale re-layout, dark-theme colours, TalkBack output or drawer animation. Those are covered
 * by [AgentHomeHeaderTest] in androidTest, which needs a connected device/emulator.
 *
 * Execution status for this delivery: NOT_RUN. The task forbids Gradle/build/device runs, so no
 * JUnit execution result exists; do not report these guards as passing.
 */
class AgentHomeHeaderContractTest {

    @Test
    fun `hamburger exposes its accessible name on a 48dp round target`() {
        val home = homeSource()
        val topBar = topBarSection(home)

        assertEquals(
            "the header must expose exactly one stable accessible name",
            1,
            occurrences(home, "contentDescription = \"打开任务与工具抽屉\"")
        )
        assertTrue(topBar.contains("modifier = Modifier.size(48.dp)"))
        assertTrue(topBar.contains("shape = CircleShape"))
        assertTrue(
            topBar.contains(
                "border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)"
            )
        )
        assertTrue(topBar.contains("glyphSize = McaIconSize.L"))
        assertTrue(topBar.contains("McaIconButton("))
        // The shared primitive owns the 48dp target and the required accessible name; a raw
        // material3 IconButton here would bypass both.
        assertFalse(home.contains("import androidx.compose.material3.IconButton"))
    }

    @Test
    fun `home header has one menu control and no exit icon`() {
        val home = homeSource()
        val topBar = topBarSection(home)
        val drawer = drawerSource()

        assertEquals("one hamburger, not a cluster", 1, occurrences(topBar, "McaIconButton("))
        // Family choice now belongs to the shared semantic map; keep the single-menu contract.
        assertEquals(1, occurrences(home, "McaIcons.Menu"))
        // The only exit is the drawer footer row. The header must not duplicate it as an icon or
        // as a second accessible control.
        assertEquals(0, occurrences(home, "返回对话"))
        assertEquals(0, occurrences(home, "Icons.Filled.Close"))
        assertEquals(0, occurrences(home, "ArrowBack"))
        assertEquals(0, occurrences(topBar, "onBack"))
        assertEquals(
            "the drawer footer owns the single exit control",
            1,
            occurrences(drawer, "contentDescription = \"返回对话\"")
        )
        assertEquals(1, occurrences(drawer, "testTag(\"agent.drawer.exit\")"))
    }

    @Test
    fun `top bar keeps the frozen 14 over 10 grid and the one-line MCA Agent title`() {
        val topBar = topBarSection(homeSource())

        assertTrue(
            topBar.contains(
                ".padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 10.dp)"
            )
        )
        assertEquals(
            "the hamburger and the title share one vertically centred row",
            1,
            occurrences(topBar, "verticalAlignment = Alignment.CenterVertically")
        )
        assertTrue(topBar.contains("Spacer(Modifier.width(12.dp))"))
        assertTrue(topBar.contains("BrandMark(size = 30.dp)"))
        assertTrue(topBar.contains("Spacer(Modifier.width(10.dp))"))
        assertTrue(topBar.contains("text = \"MCA Agent\""))
        assertTrue(topBar.contains("style = MaterialTheme.typography.titleSmall"))
        assertTrue(topBar.contains("fontWeight = FontWeight.Medium"))

        // The frozen title origin is 18 start + 48 target + 12 gap + 30 mark + 10 gap = 118dp, so
        // the menu target's right edge and the title are 12 + 30 + 10 = 52dp apart.
        val menuIndex = topBar.indexOf("McaIconButton(")
        val menuToMarkGap = topBar.indexOf("Spacer(Modifier.width(12.dp))")
        val markIndex = topBar.indexOf("BrandMark(size = 30.dp)")
        val markToTitleGap = topBar.indexOf("Spacer(Modifier.width(10.dp))")
        val titleIndex = topBar.indexOf("text = \"MCA Agent\"")
        assertTrue("menu precedes the title in the top bar", menuIndex in 0 until titleIndex)
        assertTrue("the 12dp gap follows the 48dp menu target", menuToMarkGap > menuIndex)
        assertTrue(
            "the 30dp brand mark sits between the 12dp and 10dp gaps",
            markIndex in (menuToMarkGap + 1) until markToTitleGap
        )
        assertTrue(
            "the title follows the 10dp gap after the mark",
            titleIndex in (markToTitleGap + 1)..topBar.lastIndex
        )
    }

    @Test
    fun `home title is a single line with no subtitle`() {
        val topBar = topBarSection(homeSource())

        // The previous header stacked "MCA Agent" over a muted workspace line. The one-line
        // header renders exactly one Text in one Row and clips it instead of wrapping.
        assertEquals(
            "the top bar must render exactly one text line",
            1,
            occurrences(topBar, "Text(")
        )
        assertEquals("no nested text column for a subtitle", 1, occurrences(topBar, "Column("))
        assertTrue(topBar.contains("maxLines = 1"))
        assertTrue(topBar.contains("overflow = TextOverflow.Ellipsis"))
    }

    @Test
    fun `drawer sheet keeps 72 percent width capped at 300dp`() {
        val home = homeSource()

        val sheet = home.indexOf("ModalDrawerSheet(")
        val width = home.indexOf(".fillMaxWidth(0.72f)")
        val cap = home.indexOf(".requiredWidthIn(max = 300.dp)")
        assertTrue("drawer sheet must exist", sheet >= 0)
        assertTrue("drawer sheet uses the frozen 0.72 width", width > sheet)
        assertTrue("drawer sheet caps the width at 300dp", cap > width)
        assertTrue(home.contains("windowInsets = WindowInsets(0, 0, 0, 0)"))
    }

    @Test
    fun `drawer footer owns the only exit row below the history list`() {
        val drawer = drawerSource()
        val exitRow = section(
            drawer,
            "private fun DrawerBackToChatRow(",
            "private fun DrawerBrandHeader("
        )

        assertTrue(exitRow.contains(".testTag(\"agent.drawer.exit\")"))
        assertTrue(exitRow.contains(".semantics { contentDescription = \"返回对话\" }"))
        assertTrue(exitRow.contains(".clickable(onClickLabel = \"返回对话\", onClick = onClick)"))
        assertTrue(exitRow.contains(".heightIn(min = 48.dp)"))
        // Back is still auto-mirrored; its family is selected centrally instead of in the drawer.
        assertTrue(exitRow.contains("McaIcons.Back"))

        val historyDivider = drawer.indexOf(
            "HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)"
        )
        val exitCall = drawer.indexOf("DrawerBackToChatRow(onClick = { closeThen(actions.onBack) })")
        val footer = drawer.indexOf("本地工作区")
        assertTrue(historyDivider >= 0)
        assertTrue("exit is rendered after the task-history divider", exitCall > historyDivider)
        assertTrue("exit is rendered above the local-workspace footer", footer > exitCall)
    }

    @Test
    fun `top bar can grow with font scale and takes colours from the theme`() {
        val topBar = topBarSection(homeSource())

        // No fixed height or line cap on the top bar/title: a fontScale 2.0 pass may grow the row.
        assertFalse(topBar.contains(".height("))
        assertFalse(topBar.contains("heightIn("))
        // Dark and light share the same semantic roles instead of a hardcoded colour.
        assertTrue(topBar.contains("MaterialTheme.colorScheme.surface"))
        assertTrue(topBar.contains("MaterialTheme.colorScheme.outlineVariant"))
        assertTrue(topBar.contains("MaterialTheme.colorScheme.onSurface"))
        assertFalse(topBar.contains("Color(0x"))
    }

    @Test
    fun `detail run stats strip renders below the composer surface at the bottom of the column`() {
        val composer = section(
            source(DETAIL_COMPOSER),
            "internal fun AgentDetailComposer(",
            "private fun DshTextField("
        )

        assertEquals(
            "the composer renders the run-stats strip exactly once",
            1,
            occurrences(composer, "AgentRunStatsBar(")
        )
        assertEquals("one composer shell Surface", 1, occurrences(composer, "Surface("))

        val columnOpen = composer.indexOf("{", composer.indexOf("Column("))
        val columnClose = blockEnd(composer, columnOpen)
        val surfaceIndex = composer.indexOf("Surface(")
        val surfaceClose = blockEnd(composer, composer.indexOf("{", surfaceIndex))
        val statsGuardIndex = composer.indexOf("detail.runStats?.let")
        val statsIndex = composer.indexOf("AgentRunStatsBar(")
        val statsClose = blockEnd(composer, composer.indexOf("{", statsGuardIndex))

        assertTrue("the composer shell is inside the root Column", surfaceIndex > columnOpen)
        assertTrue("the stats strip is inside the same root Column", statsIndex > columnOpen)
        assertTrue(
            "the stats strip must render after the composer Surface block, not inside it",
            statsIndex > surfaceClose
        )
        assertFalse(
            "the stats strip must never render before the composer Surface",
            composer.substring(0, surfaceIndex).contains("AgentRunStatsBar(")
        )
        assertTrue(
            "the stats strip is guarded by the real projected run stats",
            statsGuardIndex > surfaceClose
        )
        assertTrue("the stats strip call follows its runStats guard", statsIndex > statsGuardIndex)
        assertTrue("the stats strip stays inside the composer Column", statsClose < columnClose)
        assertTrue(
            "the stats strip is the last child of the composer Column",
            composer.substring(statsClose + 1, columnClose).isBlank()
        )
    }

    private fun homeSource(): String = source(HOME_SCREEN)

    private fun drawerSource(): String = source(HOME_DRAWER)

    private fun topBarSection(home: String): String =
        section(home, "private fun AgentHomeTopBar(", "private fun AgentHomeBanners(")

    private fun section(source: String, from: String, to: String): String {
        val start = source.indexOf(from)
        val end = source.indexOf(to)
        if (start < 0 || end <= start) {
            error("Unable to slice '$from' .. '$to' from the production source")
        }
        return source.substring(start, end)
    }

    private fun occurrences(source: String, needle: String): Int = source.split(needle).size - 1

    /**
     * Index of the `}` closing the `{` at [openIndex], ignoring braces inside string/char
     * literals and line comments. Only the production sources under test are fed into this.
     */
    private fun blockEnd(source: String, openIndex: Int): Int {
        require(openIndex in source.indices && source[openIndex] == '{') {
            "expected an opening brace at index $openIndex"
        }
        var depth = 0
        var index = openIndex
        var inString = false
        var inChar = false
        var inLineComment = false
        while (index < source.length) {
            val char = source[index]
            if (inLineComment) {
                if (char == '\n') inLineComment = false
            } else if (inString) {
                if (char == '\\') index++ else if (char == '"') inString = false
            } else if (inChar) {
                if (char == '\\') index++ else if (char == '\'') inChar = false
            } else if (char == '/' && source.getOrNull(index + 1) == '/') {
                inLineComment = true
                index++
            } else if (char == '"') {
                inString = true
            } else if (char == '\'') {
                inChar = true
            } else if (char == '{') {
                depth++
            } else if (char == '}') {
                depth--
                if (depth == 0) return index
            }
            index++
        }
        error("unbalanced braces from index $openIndex")
    }

    private fun source(relativePath: String): String =
        File(projectRoot(), relativePath).readText(Charsets.UTF_8)

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("project root not found from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val HOME_SCREEN =
            "feature/agent-workspace/src/main/java/com/muyuchat/feature/agent/workspace/ui/AgentWorkspaceHomeScreen.kt"
        const val HOME_DRAWER =
            "feature/agent-workspace/src/main/java/com/muyuchat/feature/agent/workspace/ui/AgentHomeDrawer.kt"
        const val DETAIL_COMPOSER =
            "feature/agent-workspace/src/main/java/com/muyuchat/feature/agent/workspace/ui/AgentQuestionCard.kt"
    }
}
