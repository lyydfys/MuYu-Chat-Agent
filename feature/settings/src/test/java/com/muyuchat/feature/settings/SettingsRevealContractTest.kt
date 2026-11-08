package com.muyuchat.feature.settings

import com.muyuchat.feature.settings.search.SettingsSearchEntries
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the row-level reveal contract without Compose (this module's unit tests have only JUnit).
 *
 * The reveal itself is visual, but the *contract* is data: which `spotId`s can be located, which ones
 * are deliberately downgraded to "open the section only", and whether the pages and the search hand-off
 * still agree with those tables. Those are exactly the facts that rot silently, so they are asserted
 * here against the real index and the real source files.
 */
class SettingsRevealContractTest {

    @Test
    fun `每个索引项要么可定位要么显式降级`() {
        // 三个真源：运行页的 item 顺序表、About 页的行清单、显式降级表。
        val wired = SettingsRevealOrder.runtime.filterNotNull().toSet() + SettingsRevealOrder.about.toSet()
        val degraded = SettingsRevealDegradation.keys
        val handled = wired + degraded

        // `hub.*` entries are the dashboard cards themselves: "locating" one means opening that
        // section, which is exactly what a section-level jump already does. They are not rows.
        val unhandled = SettingsSearchEntries
            .map { it.spotId }
            .filter { !it.startsWith("hub.") }
            .filter { it !in handled }
            .sorted()

        assertEquals(
            "以下 spotId 既没有行级 reveal 也没有登记降级理由：搜索会静默地只跳到分区页，" +
                "而调用方以为做到了行级定位。请接线，或在 SettingsRevealDegradation 里写清原因。",
            emptyList<String>(),
            unhandled
        )
    }

    @Test
    fun `降级表里的 spotId 都是真实索引项`() {
        val indexIds = SettingsSearchEntries.map { it.spotId }.toSet()
        val phantom = SettingsRevealDegradation.keys.filter { it !in indexIds }.sorted()
        assertEquals(
            "降级表里出现了索引中不存在的 spotId（改名或删除后忘记同步），会让覆盖统计虚高：",
            emptyList<String>(),
            phantom
        )
    }

    @Test
    fun `降级表不得与真正接线的行重复`() {
        // 顺序表要包含页面的每一个 item 才能算对下标，所以它可能包含「位置已知但本轮未接线」的行
        // （runtime.app_update）。真正的接线证据是页面里的 settingsRevealSpot 标记，不是顺序表。
        val markers = revealMarkers().toSet()
        val both = markers.intersect(SettingsRevealDegradation.keys)
        assertEquals(
            "同一 spotId 既在页面里接了 reveal 又登记为降级，表格自相矛盾：",
            emptySet<String>(),
            both
        )
    }

    @Test
    fun `已接线的行都在运行时页面里有 reveal 标记`() {
        val source = readSource(RUNTIME_PATH)
        SettingsRevealOrder.runtime.filterNotNull()
            .filter { it !in SettingsRevealDegradation.keys }
            .forEach { spotId ->
            assertTrue(
                "SettingsRevealOrder.runtime 声明 $spotId 可定位，但 SettingsRuntimeScreen.kt 里" +
                    "没有对应的 settingsRevealSpot(\"$spotId\", ...) 标记；顺序表已与页面脱节。",
                source.contains("settingsRevealSpot(\"$spotId\"")
            )
        }
    }

    @Test
    fun `运行时顺序表与页面渲染顺序一致`() {
        // 顺序表的下标直接当作 LazyColumn 的 item 下标使用，顺序错位会把用户滚到相邻的行上，
        // 这种错误在界面上看起来「差不多对」，只能靠源码顺序比对发现。
        val source = readSource(RUNTIME_PATH)
        val inSourceOrder = revealMarkers()
        // 声明顺序里可以含未接线的行（它仍占一个 item 位置），比对时两边都取真正接线的子集。
        val declared = SettingsRevealOrder.runtime.filterNotNull()
            .filter { it !in SettingsRevealDegradation.keys }

        assertEquals(
            "运行时顺序表与页面里 reveal 标记的先后顺序必须一致，否则滚动会落到错误的行：",
            declared,
            inSourceOrder
        )
    }

    @Test
    fun `运行时顺序表覆盖页面的每个可定位条目`() {
        val source = readSource(RUNTIME_PATH)
        val declared = SettingsRevealOrder.runtime.filterNotNull().toSet()
        val markers = Regex("settingsRevealSpot\\(\"([a-z_.]+)\"")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals(
            "页面里出现了顺序表未声明的 reveal 标记，滚动索引将无法定位该行：",
            emptySet<String>(),
            markers - declared
        )
    }

    @Test
    fun `可定位的行数不低于十三`() {
        // 硬下界：runtime 6 + about 7。将来若有人把某行退回分区级，这个数字会掉下去并让测试失败，
        // 而不是让搜索悄悄退化回「只到分区」。
        val wiredRuntime = SettingsRevealOrder.runtime
            .filterNotNull()
            .filter { it !in SettingsRevealDegradation.keys }
        val wired = (wiredRuntime + SettingsRevealOrder.about).toSet()
        assertTrue("可定位 spotId 应至少 13 个，实际 ${wired.size}：搜索的行级定位退化了", wired.size >= 13)

        val aboutSource = readSource(ABOUT_PATH)
        SettingsRevealOrder.about.forEach { spotId ->
            assertTrue(
                "SettingsRevealOrder.about 声明 $spotId 可定位，但 SettingsAboutScreen.kt 里没有" +
                    "对应的 settingsRevealSpot(\"$spotId\", ...) 标记",
                aboutSource.contains("settingsRevealSpot(\"$spotId\"")
            )
        }
    }

    @Test
    fun `搜索点结果把整条索引项交给宿主`() {
        val page = readSource(SEARCH_PAGE_PATH)
        assertTrue(
            "搜索页必须把整条 entry 交出去（分区 + spotId），只传 section 就无法再做行级定位",
            page.contains("onOpenSection: (SettingsSearchEntry) -> Unit")
        )
        assertTrue(
            "结果点击必须回传 hit.entry 而不是 hit.entry.section",
            page.contains("onOpenSection(hit.entry)")
        )
        val hub = readSource(HUB_PATH)
        val revealIndex = hub.indexOf("reveal.revealTo(entry.spotId)")
        val openIndex = hub.indexOf("section = entry.section")
        assertTrue("宿主没有在跳转时发出 reveal 请求", revealIndex >= 0)
        assertTrue("宿主没有把结果落到对应分区页", openIndex >= 0)
        assertTrue(
            "必须先发出 reveal 再切换分区：反序会在目标页组合前丢掉请求",
            revealIndex < openIndex
        )
    }

    @Test
    fun `高亮是短暂的且一定会复原`() {
        val source = readSource(REVEAL_PATH)
        assertTrue(
            "高亮时长必须显式常量化，便于审计「短暂」这一要求",
            source.contains("const val HIGHLIGHT_MILLIS")
        )
        val millis = SettingsReveal.HIGHLIGHT_MILLIS
        assertTrue("高亮时长必须为正，实际 $millis", millis > 0)
        assertTrue("高亮必须短暂（不超过 5 秒），实际 ${millis}ms", millis <= 5_000)
        assertTrue(
            "高亮必须淡出到 0 并清除请求，否则会留下持续高亮",
            source.contains("alpha.animateTo(0f")
        )
        assertTrue(
            "淡出结束后必须 consume 请求，恢复到无高亮状态",
            source.contains("reveal.consume(spotId, request.token)")
        )
        assertTrue(
            "consume 必须只清除自己那一次请求，不能打断更新的请求",
            source.contains("if (active.spotId == spotId && active.token == token) request = null")
        )
    }

    @Test
    fun `高亮不只靠颜色`() {
        val source = readSource(REVEAL_PATH)
        assertTrue(
            "定位结果必须有文字提示（不能只靠颜色），当前缺少 banner 文案",
            source.contains("已定位到「")
        )
        assertTrue(
            "文字提示必须标记为 live region，屏幕阅读器才会播报",
            source.contains("liveRegion = LiveRegionMode.Polite")
        )
        assertTrue(
            "banner 标题必须取自索引，不能另写第二份文案",
            source.contains("SettingsSearchEntries.firstOrNull { it.spotId == request.spotId }?.title")
        )
    }

    @Test
    fun `reveal 状态由宿主持有而不是全局`() {
        val source = readSource(REVEAL_PATH)
        assertTrue(
            "reveal 状态必须是可记忆的页面级对象",
            source.contains("remember { SettingsRevealState() }")
        )
        assertTrue(
            "reveal 状态不得写进全局单例或 MainUiState",
            !source.contains("object SettingsRevealState") &&
                !source.contains("MainUiState")
        )
    }

    /** The spot ids that actually carry a reveal marker in the runtime page, in source order. */
    private fun revealMarkers(): List<String> =
        Regex("settingsRevealSpot\\(\"([a-z_.]+)\"")
            .findAll(readSource(RUNTIME_PATH))
            .map { it.groupValues[1] }
            .toList()

    private fun readSource(relativePath: String): String =
        File(projectRoot(), relativePath).readText()

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("无法从 ${System.getProperty("user.dir")} 定位项目根目录")
    }

    private companion object {
        const val DIR = "feature/settings/src/main/java/com/muyuchat/feature/settings"
        const val HUB_PATH = "$DIR/SettingsScreens.kt"
        const val RUNTIME_PATH = "$DIR/SettingsRuntimeScreen.kt"
        const val ABOUT_PATH = "$DIR/SettingsAboutScreen.kt"
        const val SEARCH_PAGE_PATH = "$DIR/SettingsSearchPage.kt"
        const val REVEAL_PATH = "$DIR/SettingsReveal.kt"
    }
}
