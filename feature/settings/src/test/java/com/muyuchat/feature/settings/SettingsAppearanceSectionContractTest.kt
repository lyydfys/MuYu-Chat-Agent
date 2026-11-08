package com.muyuchat.feature.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the settings-appearance wiring without Compose: this module's test source set has only JUnit,
 * so there is no `createComposeRule` and no Robolectric. Everything below either reads the real
 * `SettingsSection` enum (now `internal`, so a same-module test can) or asserts on the source text of
 * the two surfaces that carry the wiring.
 *
 * The enum assertions are deliberately not source-parsed: the enum is the single source of truth for
 * section order, and a text test would keep passing after the enum itself changed meaning.
 */
class SettingsAppearanceSectionContractTest {

    @Test
    fun `外观段出现在设置首页枚举中`() {
        assertTrue(
            "设置首页分区枚举里缺少 APPEARANCE，外观设置将没有任何入口",
            SettingsSection.entries.contains(SettingsSection.APPEARANCE)
        )
    }

    @Test
    fun `外观段位于联网检索之后实验功能之前`() {
        // 这是契约而不是巧合：外观是高频、即时可见的通用设置，应排在运行/日志/隐私/检索这些
        // 「排障与配置」分区之后；实验功能只放仍在打磨的能力，必须留在外观之后。顺序由 enum
        // 声明顺序直接决定（SettingsSectionGrid 遍历 entries），因此改动顺序就是改动产品。
        val order = SettingsSection.entries.map { it.name }
        val appearance = order.indexOf(SettingsSection.APPEARANCE.name)
        assertTrue(
            "外观分区必须排在联网检索之后，实际顺序：$order",
            order.indexOf(SettingsSection.SEARCH.name) < appearance
        )
        assertTrue(
            "外观分区必须排在实验功能之前，实际顺序：$order",
            appearance < order.indexOf(SettingsSection.EXPERIMENTS.name)
        )
        assertTrue("外观分区必须排在关于之前，实际顺序：$order", appearance < order.indexOf(SettingsSection.ABOUT.name))
    }

    @Test
    fun `外观段的标题与摘要非空`() {
        val section = SettingsSection.APPEARANCE
        assertTrue("外观分区缺少用户可见标题，卡片会渲染成空白", section.title.isNotBlank())
        assertTrue("外观分区缺少摘要，用户看不到这段设置的作用", section.summary.isNotBlank())
        assertTrue(
            "外观分区标题应保持为「外观」，它同时出现在卡片和详情页标题里",
            section.title == "外观"
        )
    }

    @Test
    fun `每个 Section 都有对应图标`() {
        // `SettingsSection.icon()` 是文件私有函数，纯 JVM 测试无法调用它，所以这里锚定它的映射块：
        // 只截取 icon() 到 hubStatus() 之间的正文，避免「分发 when 里有这一项」造成的误判通过。
        val iconBlock = iconMappingBlock()
        SettingsSection.entries.forEach { section ->
            assertTrue(
                "分区 ${section.name} 在 SettingsSection.icon() 映射里没有分支，卡片会缺少图标",
                iconBlock.contains("SettingsSection.${section.name} ->")
            )
        }
    }

    @Test
    fun `重看引导入口的标签存在于源码`() {
        val aboutSource = readSource(ABOUT_PATH)
        val hubSource = readSource(HUB_PATH)
        assertTrue(
            "关于页缺少「重新查看新手引导」入口文案",
            aboutSource.contains("\"$REPLAY_ONBOARDING_LABEL\"")
        )
        assertTrue(
            "「重新查看新手引导」没有接到 onReplayOnboarding 回调，点击将没有任何效果",
            aboutSource.contains("onClick = onReplayOnboarding")
        )
        assertTrue(
            "SettingsHubScreen 没有把 onReplayOnboarding 传给关于页",
            hubSource.contains("onReplayOnboarding = onReplayOnboarding")
        )
        // 这里是硬约束的代码化：UI 只表达意图，重置持久化标志属于 :app 的 OnboardingPreferences。
        assertFalse(
            "关于页不得自行重置新手引导持久化状态；重置应留给 :app 的 OnboardingPreferences.reset()",
            aboutSource.contains("OnboardingPreferences")
        )
    }

    @Test
    fun `SettingsHubScreen 的新增参数有默认值`() {
        // 默认值是接入契约：:app 侧分两步接线期间，既有 60+ 参数的调用点必须继续编译。
        val signature = hubSignature()
        assertTrue(
            "appearanceThemeChoice 缺少默认值，既有调用点会编译失败",
            signature.contains("appearanceThemeChoice: McaAppearanceThemeChoice = McaAppearanceThemeChoice.Default")
        )
        assertTrue(
            "onAppearanceThemeChoiceChange 缺少默认值，既有调用点会编译失败",
            signature.contains("onAppearanceThemeChoiceChange: (McaAppearanceThemeChoice) -> Unit = {}")
        )
        assertTrue(
            "onReplayOnboarding 缺少默认值，既有调用点会编译失败",
            signature.contains("onReplayOnboarding: () -> Unit = {}")
        )
    }

    private fun iconMappingBlock(): String {
        val source = readSource(HUB_PATH)
        val start = source.indexOf(ICON_MAPPING_ANCHOR)
        assertTrue("SettingsScreens.kt 里找不到 SettingsSection.icon() 的映射起点", start >= 0)
        val end = source.indexOf("private fun SettingsSection.hubStatus", start)
        assertTrue("无法确定 SettingsSection.icon() 映射块的结束位置", end > start)
        return source.substring(start, end)
    }

    private fun hubSignature(): String {
        val source = readSource(HUB_PATH)
        val start = source.indexOf("fun SettingsHubScreen(")
        assertTrue("SettingsScreens.kt 里找不到 SettingsHubScreen 的签名", start >= 0)
        val end = source.indexOf("\n) {", start)
        assertTrue("无法确定 SettingsHubScreen 签名的结束位置", end > start)
        return source.substring(start, end)
    }

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
        const val HUB_PATH =
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsScreens.kt"
        const val ABOUT_PATH =
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsAboutScreen.kt"
        const val REPLAY_ONBOARDING_LABEL = "重新查看新手引导"
        const val ICON_MAPPING_ANCHOR = "private fun SettingsSection.icon(): ImageVector = when (this) {"
    }
}
