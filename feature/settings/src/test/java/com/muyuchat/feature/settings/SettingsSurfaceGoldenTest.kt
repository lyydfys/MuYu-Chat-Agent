package com.muyuchat.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freezes the user-visible settings surface so no label or help text can disappear silently.
 *
 * The settings screen surface (shell `SettingsScreens.kt` + its section files, formerly a single 2,961-line file) carries 624 distinct Chinese strings: section
 * titles, switch labels, help paragraphs, error text and button captions. It is the
 * largest user-visible surface in the app, and unlike the Local API it had no contract
 * test at all -- the only settings-named tests in the repository cover address
 * normalisation, one prefix-cache UI state and search diagnostics.
 *
 * The plan forbids hiding an entry or removing a setting to make a refactor easier, and
 * P3-P6 move code across module boundaries. A dropped label across the split settings screen files is easy
 * to miss in review, so it is pinned here instead. This mirrors the route golden in
 * api:local: the check is a text contract, and losing a line means losing UI.
 *
 * Every string matters, including help text, because the help text is what tells a user
 * how a setting behaves; losing it changes the product as much as losing the switch.
 *
 * On the first run the fixture is written and the test fails on purpose so the generated
 * surface is reviewed before it is committed.
 */
class SettingsSurfaceGoldenTest {

    @Test
    fun `every user-visible settings string is still present`() {
        val actual = chineseLiterals()
        val golden = goldenFile()

        if (!golden.exists()) {
            golden.parentFile!!.mkdirs()
            golden.writeText(goldenText(actual))
            throw AssertionError(
                "Settings surface golden was missing and has been generated at $GOLDEN_PATH. " +
                    "Review it, commit it, then re-run."
            )
        }

        val frozen = golden.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val lost = frozen - actual.toSet()
        val added = actual - frozen.toSet()

        assertEquals(
            "settings strings disappeared from the UI; if a setting was intentionally " +
                "removed, that is a product change and needs its own task: $lost",
            emptyList<String>(),
            lost
        )
        assertEquals(
            "new settings strings appeared; add them to $GOLDEN_PATH once reviewed: $added",
            emptyList<String>(),
            added
        )
    }

    @Test
    fun `the settings surface is substantial enough for the golden to mean something`() {
        // A guard against the extraction silently breaking: if the regex stopped matching,
        // both sets would be empty and the comparison above would pass vacuously.
        val actual = chineseLiterals()
        assertTrue(
            "expected the settings screen to carry hundreds of visible strings, found ${actual.size}",
            actual.size >= 300
        )
        assertTrue(
            "the settings source must still exist at its expected path",
            sourceFile().isFile
        )
    }

    /** Quoted string literals containing at least one CJK character, sorted and de-duplicated. */
    private fun chineseLiterals(): List<String> =
        CHINESE_LITERAL.findAll(sourceText())
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()

    // T2B9/R7-B4: SettingsScreens.kt 拆分为「壳 + 11 个同包文件」后读取面随迁：
    // 可见中文文案集合改读新文件并集；旧路径壳保留（测试 2 的 isFile 锚），断言与 golden 未变。
    private fun sourceText(): String =
        SOURCE_FILES.joinToString("\n") { File(projectRoot(), it).readText() }

    private fun goldenText(values: List<String>): String = buildString {
        appendLine("# User-visible Chinese strings in SettingsScreens.kt, sorted and de-duplicated.")
        appendLine("# Generated from the source; reviewed before committing. See SettingsSurfaceGoldenTest.")
        appendLine("# A line disappearing means a settings label or help text was removed from the UI.")
        values.forEach { appendLine(it) }
    }

    private fun goldenFile(): File = File(projectRoot(), GOLDEN_PATH)

    private fun sourceFile(): File = File(projectRoot(), SOURCE_PATH)

    private fun projectRoot(): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Unable to locate the project root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val SOURCE_PATH =
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsScreens.kt"

        // T2B9/R7-B4: 拆分后的读取面 = 壳 + 11 个同包后继文件；SOURCE_PATH 仍是壳（test 2 isFile 锚）。
        // SettingsAboutScreen.kt 曾漏在扫描面外（About 页 86 条文案、含新手引导入口完全不受保护），
        // 已补入；SettingsAboutSurfaceCoverageTest 负责防止它再次被移出。
        val SOURCE_FILES = listOf(
            SOURCE_PATH,
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsRuntimeScreen.kt",
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsLogsPrivacyScreens.kt",
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsSearchScreen.kt",
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsSearchPage.kt",
            // The appearance panel (colour mode + wallpaper + launch-screen/icon statements) is its
            // own user-visible settings surface, so its copy sits under the same guard as the screen
            // that hosts it. Its KDoc is English-only so the Chinese-literal sweep cannot mistake a
            // comment for a label.
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsAppearancePanel.kt",
            // 行级 reveal 契约（spotId → 滚动 + 短暂高亮）的文案也在金样保护面内。
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsReveal.kt",
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsAboutScreen.kt",
            "feature/settings/src/main/java/com/muyuchat/feature/settings/WebSearchDiagnostics.kt",
            "feature/settings/src/main/java/com/muyuchat/feature/settings/LocalApiToolScreen.kt",
            "feature/settings/src/main/java/com/muyuchat/feature/settings/LocalApiSelfTestSupport.kt",
            "feature/settings/src/main/java/com/muyuchat/feature/settings/LocalApiSelfTestImage.kt",
            // Agent 工具审批卡（三个审批档位 + 说明）：与 AgentApprovalSettings.kt 同时新增的
            // settings 界面，曾和 About 页一样不在扫描面内。SettingsAboutSurfaceCoverageTest
            // 的全文件覆盖断言要求它必须在这里，否则新增 settings 源文件会再次漏出金样保护。
            "feature/settings/src/main/java/com/muyuchat/feature/settings/AgentApprovalSettings.kt",
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsSupport.kt"
        )
        const val GOLDEN_PATH = "feature/settings/src/test/resources/settings-surface.golden.txt"

        /** A double-quoted literal that is not escaped and contains a CJK character. */
        /**
         * One single-line Kotlin string literal that contains Chinese.
         *
         * The two character classes exclude `\n` on purpose. Without that exclusion `[^"\\]`
         * also matches newlines, so a literal whose closing quote sits further down the file
         * swallows whole statements into one "match" - the sweep then reports source code as
         * user-visible copy, and a fixture regenerated from it is garbage. A Kotlin string
         * literal that holds UI copy is always written on one line here, so pinning to a single
         * line is both stricter and correct.
         */
        val CHINESE_LITERAL = Regex("\"([^\"\\\\\n]*[\\u4e00-\\u9fff][^\"\\\\\n]*)\"")
    }
}
