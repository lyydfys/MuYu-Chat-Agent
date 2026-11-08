package com.muyuchat.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the *scanned surface* of the settings copy golden, not just its contents.
 *
 * `SettingsSurfaceGoldenTest` freezes every user-visible Chinese string it reads, but it only reads
 * the files listed in its `SOURCE_FILES`. A settings screen that is missing from that list is
 * invisible to the golden: its copy can be deleted in a refactor and no test notices. That is
 * exactly what happened to `SettingsAboutScreen.kt` (86 strings, including the entire 新手引导
 * entry point) and to `AgentApprovalSettings.kt`.
 *
 * The third test below closes the gap structurally: every `.kt` file under the settings package must
 * be either scanned or on a short, justified exclusion list. Adding a new settings screen without
 * registering it now fails the build instead of silently escaping protection.
 */
class SettingsAboutSurfaceCoverageTest {

    @Test
    fun `About 页在文案金样扫描面内`() {
        assertTrue(
            "SettingsAboutScreen.kt 必须登记在 SettingsSurfaceGoldenTest.SOURCE_FILES 里；" +
                "否则 About 页的全部用户可见文案（含新手引导入口）都不受金样保护。" +
                "当前扫描面：${scannedSources()}",
            ABOUT_SCREEN in scannedSources()
        )
    }

    @Test
    fun `新手引导入口文案受金样保护`() {
        val frozen = frozenLines()
        assertTrue("文案金样文件不存在或没有任何数据行：$GOLDEN_PATH", frozen.isNotEmpty())
        ONBOARDING_REPLAY_TEXTS.forEach { expected ->
            assertTrue(
                "金样里缺少 About 页的新手引导文案 \"$expected\"；" +
                    "它应当被 SettingsSurfaceGoldenTest 抽取并冻结，缺失说明扫描面或 fixture 回退了。",
                frozen.contains(expected)
            )
        }
    }

    @Test
    fun `金样扫描面覆盖全部 Settings 源文件`() {
        val scanned = scannedSources().toSet()
        val present = settingsSourceFiles()
        assertTrue("没有在 $SETTINGS_DIR 下找到任何 .kt 源文件", present.isNotEmpty())

        val missing = present.filter { path ->
            path !in scanned && EXCLUDED_SOURCES.none { prefix -> path.startsWith(prefix) }
        }
        assertEquals(
            "以下 settings 源文件没有进入文案金样扫描面，其用户可见文案不受保护。\n" +
                "请把它加入 SettingsSurfaceGoldenTest.SOURCE_FILES 并重算 fixture；" +
                "只有 $SETTINGS_DIR/search/（索引数据层）允许排除。\n" +
                "缺失的文件：",
            emptyList<String>(),
            missing
        )
    }

    @Test
    fun `每个被扫描的源文件都真实存在`() {
        val root = projectRoot()
        scannedSources().forEach { path ->
            val file = File(root, path)
            assertTrue("SettingsSurfaceGoldenTest 登记了不存在的文件（重命名后忘记更新？）：$path", file.isFile)
        }
    }

    /** Settings 数据类所在包，绝对路径由 [projectRoot] 解析。 */
    private fun settingsDirectory(): File = File(projectRoot(), SETTINGS_DIR)

    /** Every `.kt` under the settings package, as root-relative paths with `/` separators. */
    private fun settingsSourceFiles(): List<String> {
        val root = projectRoot()
        val directory = settingsDirectory()
        assertTrue("settings 包目录不存在：${directory.absolutePath}", directory.isDirectory)
        return directory.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .toList()
    }

    /**
     * The paths `SettingsSurfaceGoldenTest` actually reads, parsed out of its own source.
     *
     * Parsing the sibling test instead of copying the list keeps a single source of truth: if the
     * golden test's list changes, this guard follows it automatically.
     */
    private fun scannedSources(): List<String> {
        val source = File(projectRoot(), GOLDEN_TEST_PATH)
        assertTrue("找不到金样测试源文件：$GOLDEN_TEST_PATH", source.isFile)
        return SCANNED_PATH_LITERAL.findAll(source.readText())
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()
    }

    /** Fixture data lines, filtered exactly like the golden test does. */
    private fun frozenLines(): List<String> {
        val golden = File(projectRoot(), GOLDEN_PATH)
        if (!golden.isFile) return emptyList()
        return golden.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
    }

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val SETTINGS_DIR = "feature/settings/src/main/java/com/muyuchat/feature/settings"

        const val ABOUT_SCREEN = "$SETTINGS_DIR/SettingsAboutScreen.kt"

        const val GOLDEN_TEST_PATH =
            "feature/settings/src/test/java/com/muyuchat/feature/settings/SettingsSurfaceGoldenTest.kt"

        const val GOLDEN_PATH = "feature/settings/src/test/resources/settings-surface.golden.txt"

        /** About 页「新手引导」卡片的三条文案，须逐条被金样冻结。 */
        val ONBOARDING_REPLAY_TEXTS = listOf(
            "新手引导",
            "重新查看新手引导",
            "重新走一遍首次启动时的功能说明；不会清除聊天记录、模型或已保存的设置。"
        )

        /**
         * 允许不进扫描面的目录，必须逐条给出理由。
         *
         * - [SETTINGS_DIR]/search/：设置搜索的索引数据层（行标题/关键字常量表 + 纯匹配函数），
         *   不是 Compose 界面文案。它含 200+ 条中文，但都是「应该等于 UI 行标题」的数据；
         *   纳入金样只会让改一个关键字就触发大面积 fixture 重算，而起不到文案回归保护作用。
         *   这些标题的正确性由 `SettingsSearchIndexTest`（每条 title 必须逐字存在于 UI 源码）
         *   与 `SettingsSearchMatcherTest` 负责。
         *
         * 若将来该目录里出现新的 Compose 界面文件，必须把它移出本清单。
         */
        val EXCLUDED_SOURCES = listOf("$SETTINGS_DIR/search/")

        /** A quoted `feature/settings/src/main/java/...` `.kt` path inside the golden test's source. */
        val SCANNED_PATH_LITERAL = Regex("\"($SETTINGS_DIR/[^\"]*\\.kt)\"")
    }
}
