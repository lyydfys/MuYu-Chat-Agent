package com.muyuchat.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two similarly named settings surfaces against being merged, gutted or dropped together.
 *
 * `SettingsSearchScreen.kt` is the 联网检索 *provider configuration* page (SearxNG / Brave / Tavily
 * keys, trigger mode, diagnostics). `SettingsSearchPage.kt` is the settings *search* page (the query
 * field plus grouped hits). The names differ by one word and mean opposite things; the IA document
 * calls this out as a known trap, and review has already mis-read them once.
 *
 * **Why this is not covered by the other two tests.** Both existing guards are blind to the failure
 * that matters most here:
 * - The golden compares string *sets*. Folding [SEARCH_PAGE]'s copy into [SEARCH_SCREEN] keeps the
 *   union identical, so `lost`/`added` stay empty and the golden passes.
 * - `SettingsAboutSurfaceCoverageTest` enumerates files that exist. Deleting [SEARCH_PAGE] removes it
 *   from that enumeration instead of failing it.
 *
 * So a merge/rename/delete of either page would leave every test green while the product silently
 * lost a settings surface. The assertions below pin the *structure*: both files exist, both are
 * scanned, and each still owns copy that no other scanned file contributes - which a merge or a
 * forwarding stub cannot satisfy. Editing copy in only one of them is already caught by the golden,
 * so that case needs no extra assertion here.
 */
class SettingsSearchPageNamingTrapTest {

    @Test
    fun `两个相似文件名的设置页都存在且都在金样扫描面内`() {
        val root = projectRoot()
        val scanned = scannedSources()
        listOf(SEARCH_SCREEN, SEARCH_PAGE).forEach { path ->
            assertTrue(
                "设置页 $path 不存在。SettingsSearchScreen.kt（联网检索配置）与 " +
                    "SettingsSearchPage.kt（设置搜索）是两个语义相反的面，任一被删除/改名都必须是显式决定。",
                File(root, path).isFile
            )
            assertTrue(
                "设置页 $path 不在 SettingsSurfaceGoldenTest.SOURCE_FILES 里，其文案不受金样保护。" +
                    "当前扫描面：$scanned",
                path in scanned
            )
        }
    }

    @Test
    fun `两个相似文件名的设置页各自仍拥有独有文案`() {
        val scanned = scannedSources()
        val literalsByFile = scanned.associateWith { chineseLiteralsOf(it) }
        val allLiterals = literalsByFile.values.flatten().toSet()

        listOf(SEARCH_SCREEN to "联网检索配置页", SEARCH_PAGE to "设置搜索页").forEach { (path, role) ->
            val mine = literalsByFile.getValue(path)
            assertTrue(
                "$role（$path）抽不到任何中文文案；它应当仍是真实的界面文件而不是转发壳。",
                mine.isNotEmpty()
            )
            val owned = mine.filter { literal ->
                literalsByFile.filterKeys { it != path }.values.none { literal in it }
            }
            assertTrue(
                "$role（$path）没有任何一条只属于自己的中文文案：说明它被合并进别的文件、" +
                    "被改成了转发壳，或文案被整体搬走。请恢复独立页面，或在 IA 层面显式决定合并。" +
                    "当前它与其它扫描文件的共有文案数：${mine.size - owned.size}",
                owned.isNotEmpty()
            )
        }

        // 两个面的文案今天完全不重叠：重叠意味着有人把检索页的配置文案写进了搜索页。
        val overlap = literalsByFile.getValue(SEARCH_SCREEN)
            .intersect(literalsByFile.getValue(SEARCH_PAGE).toSet())
        assertEquals(
            "联网检索配置页与设置搜索页的文案不应重叠；重叠说明两个面被混在了一起：$overlap",
            emptySet<String>(),
            overlap
        )
        assertTrue("扫描面本身应当非空", allLiterals.isNotEmpty())
    }

    /** The paths `SettingsSurfaceGoldenTest` reads, parsed from its own source (single source of truth). */
    private fun scannedSources(): List<String> {
        val source = File(projectRoot(), GOLDEN_TEST_PATH)
        assertTrue("找不到金样测试源文件：$GOLDEN_TEST_PATH", source.isFile)
        return SCANNED_PATH_LITERAL.findAll(source.readText())
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()
    }

    /** Chinese literals of one scanned file, using the golden test's own extraction regex. */
    private fun chineseLiteralsOf(path: String): List<String> {
        val file = File(projectRoot(), path)
        if (!file.isFile) return emptyList()
        return CHINESE_LITERAL.findAll(file.readText())
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()
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

        /** 联网检索配置页（provider / trigger / research / diagnostics）。 */
        const val SEARCH_SCREEN = "$SETTINGS_DIR/SettingsSearchScreen.kt"

        /** 设置搜索页（query field + grouped hits）。 */
        const val SEARCH_PAGE = "$SETTINGS_DIR/SettingsSearchPage.kt"

        const val GOLDEN_TEST_PATH =
            "feature/settings/src/test/java/com/muyuchat/feature/settings/SettingsSurfaceGoldenTest.kt"

        /** Same pattern the golden test uses; kept literal so the two cannot drift. */
        val CHINESE_LITERAL = Regex("\"([^\"\\\\]*[\\u4e00-\\u9fff][^\"\\\\]*)\"")

        val SCANNED_PATH_LITERAL = Regex("\"($SETTINGS_DIR/[^\"]*\\.kt)\"")
    }
}
