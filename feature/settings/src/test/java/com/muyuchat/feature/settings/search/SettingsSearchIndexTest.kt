package com.muyuchat.feature.settings.search

import com.muyuchat.feature.settings.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the search index to the real settings surface: every section is reachable, every jump
 * anchor is unique and stable, and the row count still agrees with what the dashboard renders.
 *
 * A failure here means the index and the UI have drifted apart, which silently turns a search hit
 * into a jump to nowhere. Every expectation below is derived from [SettingsSection.entries] rather
 * than from a hand-written list of sections: a hard-coded list would be the second source of truth
 * this package was cleaned of.
 */
class SettingsSearchIndexTest {

    @Test
    fun `每个 SettingsSection 都有且仅有一个 hub 条目`() {
        // 这条是「新增分区必须进索引」的强制点：hub 条目由 SettingsSection.entries 派生，
        // 因此分区数、顺序、标题、wireName 四处任何一处漂移都会在这里失败。
        val hubEntries = SettingsSearchEntries.filter { it.spotId.startsWith(HUB_SPOT_PREFIX) }
        assertEquals(
            "首页分区卡片数（${hubEntries.map { it.spotId }}）必须等于 SettingsSection.entries.size",
            SettingsSection.entries.size,
            hubEntries.size
        )
        SettingsSection.entries.forEach { section ->
            val matches = hubEntries.filter { it.section == section }
            assertEquals(
                "分区 ${section.wireName}（${section.title}）的入口锚点必须恰好一个，实际：${matches.map { it.spotId }}",
                1,
                matches.size
            )
            assertEquals(
                "分区 ${section.wireName} 的入口锚点必须是 hub.${section.wireName}",
                "$HUB_SPOT_PREFIX${section.wireName}",
                matches.single().spotId
            )
            assertEquals(
                "分区 ${section.wireName} 的入口锚点标题必须来自 SettingsSection.title",
                section.title,
                matches.single().title
            )
        }
        assertEquals(
            "入口锚点必须按 SettingsSection.entries 的顺序与之一一对应",
            SettingsSection.entries.toList(),
            hubEntries.map { it.section }
        )
    }

    @Test
    fun `每个 Section 至少有一条索引项`() {
        SettingsSection.entries.forEach { section ->
            assertTrue(
                "分区 ${section.wireName}（${section.title}）在索引里没有任何条目；搜索将完全找不到这一组设置",
                SettingsSearchEntries.any { it.section == section }
            )
        }
    }

    @Test
    fun `spotId 全局唯一`() {
        val duplicates = SettingsSearchEntries
            .groupBy { it.spotId }
            .filterValues { it.size > 1 }
            .keys
        assertEquals(
            "spotId 必须全局唯一，重复值会让跳转锚点指向错误的一行：$duplicates",
            emptySet<String>(),
            duplicates
        )
    }

    @Test
    fun `索引项数量与首页真实行数一致`() {
        // 首页（SettingsHubContent）里唯一的可点设置项是 SettingsSectionGrid 渲染的分区卡片，
        // 而该网格遍历 SettingsSection.entries，所以「首页真实可点项数」就是分区数，
        // 这个值从枚举本身取，不写死数字。
        val hubEntries = SettingsSearchEntries.filter { it.spotId.startsWith(HUB_SPOT_PREFIX) }
        assertEquals(
            "设置首页每个分区应恰好有一个入口锚点；缺失或重复都会让某张卡片搜不到",
            SettingsSection.entries.size,
            hubEntries.size
        )
        // 除首页卡片外，索引还逐条覆盖各分区详情页的真实行。详情页存在依赖运行状态的动态行
        // （运行记录、调参记录、最新版本等），其数量在源码里没有静态确定值，因此无法对总条数
        // 做等值断言；这里保留题意要求的「不少于首页可点项数」下界断言。
        assertTrue(
            "索引条目数（${SettingsSearchEntries.size}）应不少于首页真实可点项数（${hubEntries.size}）",
            SettingsSearchEntries.size >= hubEntries.size
        )
    }

    @Test
    fun `每个索引项都有标题和分组`() {
        SettingsSearchEntries.forEach { entry ->
            assertTrue("${entry.spotId} 缺少用户可见标题", entry.title.isNotBlank())
            assertTrue("${entry.spotId} 缺少结果分组标题", entry.group.isNotBlank())
        }
    }

    @Test
    fun `spotId 是稳定的 ascii 短标识并带分区前缀`() {
        val allowed = Regex("[a-z0-9_.]+")
        SettingsSearchEntries.forEach { entry ->
            assertTrue(
                "spotId 必须是小写 ascii 短标识，实际为 '${entry.spotId}'",
                allowed.matches(entry.spotId)
            )
            val expectedPrefix = if (entry.spotId.startsWith(HUB_SPOT_PREFIX)) HUB_SPOT_PREFIX else "${entry.section.wireName}."
            assertTrue(
                "spotId ${entry.spotId} 应以 $expectedPrefix 开头，否则跳转锚点与所属分区对不上",
                entry.spotId.startsWith(expectedPrefix)
            )
        }
    }

    @Test
    fun `分区标签与设置首页的分区文案一致`() {
        // 期望值来自真实的 SettingsSection，而不是另写一份分区标签列表：后者正是本次被删除的
        // 镜像枚举留下的双来源问题。
        val hubEntries = SettingsSearchEntries.filter { it.spotId.startsWith(HUB_SPOT_PREFIX) }
        assertEquals(
            "分区文案必须与 SettingsScreens.kt 的 SettingsSection.title 完全一致",
            SettingsSection.entries.map { it.title },
            hubEntries.map { it.title }
        )
        SettingsSection.entries.forEach { section ->
            assertTrue(
                "分区 ${section.wireName} 在首页卡片上显示的摘要不得为空，否则搜索结果分组无法自解释",
                section.summary.isNotBlank()
            )
        }
    }

    @Test
    fun `未知分区名回退到默认分区且不抛异常`() {
        // 回退目标是首页列出的第一个分区，而不是写死的某一项。
        val fallback = SettingsSection.entries.first()
        assertEquals(SettingsSection.RUNTIME, fallback)
        assertEquals(fallback, settingsSectionFromWire(null))
        assertEquals(fallback, settingsSectionFromWire(""))
        assertEquals(fallback, settingsSectionFromWire("   "))
        assertEquals(
            "未知分区名必须回退到默认分区而不是抛异常，它可能来自旧版本写下的日志",
            fallback,
            settingsSectionFromWire("no-such-section")
        )
        assertEquals(SettingsSection.LOGS, settingsSectionFromWire("  LOGS "))
        assertEquals(SettingsSection.SEARCH, settingsSectionFromWire("search"))
        assertEquals(SettingsSection.APPEARANCE, settingsSectionFromWire("Appearance"))
        SettingsSection.entries.forEach { section ->
            assertEquals(
                "每个分区的 wireName 都必须能被 fromWire 还原：${section.wireName}",
                section,
                settingsSectionFromWire(section.wireName)
            )
        }
    }

    @Test
    fun `每个分区的 wireName 都是稳定的小写 ascii`() {
        val allowed = Regex("[a-z]+")
        SettingsSection.entries.forEach { section ->
            assertTrue(
                "分区 ${section.name} 的 wireName 必须是小写 ascii，实际为 '${section.wireName}'",
                allowed.matches(section.wireName)
            )
        }
        assertEquals(
            "wireName 必须唯一，否则 fromWire 无法还原分区",
            SettingsSection.entries.size,
            SettingsSection.entries.map { it.wireName }.toSet().size
        )
    }

    private companion object {
        const val HUB_SPOT_PREFIX: String = "hub."
    }
}
