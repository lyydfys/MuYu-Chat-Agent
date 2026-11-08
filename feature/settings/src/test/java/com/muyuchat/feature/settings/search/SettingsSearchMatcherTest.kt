package com.muyuchat.feature.settings.search

import com.muyuchat.feature.settings.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour contract of the settings matcher: what a Chinese, English, mixed or full-width query
 * finds, in which order, and what it does with input that matches nothing.
 *
 * The ranking rule under test is the product rule: an exact title beats a title prefix, a title
 * prefix beats a title substring, a title substring beats a keyword, and a keyword beats a group
 * name. Ties resolve on section order and then spotId, so results are reproducible.
 */
class SettingsSearchMatcherTest {

    @Test
    fun `搜中文标题能命中`() {
        val hits = searchSettings("隐私与数据")
        assertTrue("中文标题查询不应为空", hits.isNotEmpty())
        assertEquals(
            "完整标题应精确命中该分区入口，并排在第一位",
            "hub.privacy",
            hits.first().entry.spotId
        )
        assertEquals(
            "完整标题命中应报告为标题字段",
            SettingsSearchField.TITLE,
            hits.first().matchedField
        )
    }

    @Test
    fun `搜英文关键词能命中`() {
        val hits = searchSettings("privacy")
        assertEquals(
            "英文关键词 privacy 应唯一命中隐私分区入口",
            listOf("hub.privacy"),
            hits.map { it.entry.spotId }
        )
        assertEquals(
            "该命中来自关键词而不是标题",
            SettingsSearchField.KEYWORD,
            hits.first().matchedField
        )
    }

    @Test
    fun `搜旧名字或别名能命中`() {
        // "日志与接口" 是改名前的旧名字，源码里仍由运行状态页的提示文案引用。
        val oldName = searchSettings("日志与接口")
        assertTrue("旧名字 日志与接口 应仍能命中日志分区", oldName.isNotEmpty())
        assertEquals("hub.logs", oldName.first().entry.spotId)

        // "智能调参" 是同一行的另一处可见叫法，用来确认别名而不只是旧名可搜。
        val alias = searchSettings("智能调参")
        assertTrue(
            "别名 智能调参 应命中调参记录行：${alias.map { it.entry.spotId }}",
            alias.any { it.entry.spotId == "logs.agent_records" }
        )
    }

    @Test
    fun `空白查询返回空列表且不抛异常`() {
        listOf("", " ", "   ", "\u3000", "\u3000 \u3000", "\t\n ").forEach { query ->
            val outcome = runCatching { searchSettings(query) }
            assertTrue(
                "空白查询不应抛异常：'$query' -> ${outcome.exceptionOrNull()}",
                outcome.isSuccess
            )
            assertEquals(
                "空白查询必须返回空列表：'$query'",
                emptyList<SettingsSearchHit>(),
                outcome.getOrThrow()
            )
        }
    }

    @Test
    fun `未知查询返回空列表`() {
        listOf("zzzz-no-such-setting", "绝无此项", "☃☃☃").forEach { query ->
            assertEquals(
                "未知查询必须返回空列表而不是抛异常：'$query'",
                emptyList<SettingsSearchHit>(),
                searchSettings(query)
            )
        }
    }

    @Test
    fun `排序稳定：同一查询连续两次结果一致`() {
        listOf("更新", "缓存", "search", "工具", "日志 log").forEach { query ->
            val first = searchSettings(query).map { it.entry.spotId }
            val second = searchSettings(query).map { it.entry.spotId }
            assertTrue("查询 '$query' 应有结果可供比较排序", first.isNotEmpty())
            assertEquals("查询 '$query' 的两次结果必须完全一致", first, second)
        }
    }

    @Test
    fun `全角数字和字母归一化正确`() {
        assertEquals("runtime", normalizeSettingsQuery("ＲＵＮＴＩＭＥ"))
        assertEquals("mca 123", normalizeSettingsQuery("ＭＣＡ　１２３"))
        assertEquals("api key", normalizeSettingsQuery("  ＡＰＩ   ＫＥＹ  "))
        assertEquals("1-8", normalizeSettingsQuery("１－８"))
        assertEquals("runtime", normalizeSettingsQuery("  Runtime  "))

        assertTrue(
            "全角输入的查询也应命中英文关键词",
            searchSettings("ＲＵＮＴＩＭＥ").any { it.entry.spotId == "hub.runtime" }
        )
    }

    @Test
    fun `标题命中排在关键词命中之前`() {
        // 内存：runtime.memory 的标题精确命中；hub.runtime 只在关键词里提到内存。
        val hits = searchSettings("内存")
        assertEquals("标题精确命中应排在关键词命中之前", "runtime.memory", hits.first().entry.spotId)
        assertEquals(SettingsSearchField.TITLE, hits.first().matchedField)
        assertTrue(
            "关键词命中也应出现，只是排在后面：${hits.map { it.entry.spotId }}",
            hits.drop(1).any { it.matchedField == SettingsSearchField.KEYWORD }
        )
    }

    @Test
    fun `标题前缀命中排在标题包含命中之前`() {
        // 工具：experiments.tools_mcp 标题以「工具」开头（前缀），聊天本地工具只是包含。
        val ids = searchSettings("工具").map { it.entry.spotId }
        assertTrue("工具应命中 MCP 行：$ids", ids.contains("experiments.tools_mcp"))
        assertTrue("工具应命中聊天本地工具行：$ids", ids.contains("experiments.local_tools"))
        assertTrue(
            "标题前缀命中应先于标题包含命中：$ids",
            ids.indexOf("experiments.tools_mcp") < ids.indexOf("experiments.local_tools")
        )
    }

    @Test
    fun `中文只打两个字也能命中`() {
        // 中文用户常只打两三个字，匹配按子串而不是按整词。
        assertTrue(
            "两个字「缓存」应命中持久化前缀缓存",
            searchSettings("缓存").any { it.entry.spotId == "privacy.prefix_cache" }
        )
        assertTrue(
            "两个字「前缀」应命中持久化前缀缓存",
            searchSettings("前缀").any { it.entry.spotId == "privacy.prefix_cache" }
        )
        assertTrue(
            "两个字「翻译」应命中离线翻译行",
            searchSettings("翻译").any { it.entry.spotId == "runtime.offline_translation" }
        )
    }

    @Test
    fun `搜分组名能命中该组条目`() {
        val hits = searchSettings("配置中心")
        assertEquals(
            "分组名应命中设置首页的全部分区入口，并按分区顺序排列",
            // 期望顺序由 SettingsSection.entries 派生：新增分区自动进入这条断言，
            // 不会再出现「镜像列表停在 6 项」的双来源漂移。
            SettingsSection.entries.map { "hub.${it.wireName}" },
            hits.map { it.entry.spotId }
        )
        assertTrue(
            "分组命中应报告为分组字段：${hits.map { it.matchedField }}",
            hits.all { it.matchedField == SettingsSearchField.GROUP }
        )
    }

    @Test
    fun `搜索外观能找到外观分区`() {
        val hubSpotId = "hub.${SettingsSection.APPEARANCE.wireName}"
        val hits = searchSettings("外观")
        assertTrue("搜索「外观」不应为空", hits.isNotEmpty())
        assertEquals(
            "「外观」是分区标题，应精确命中外观分区的入口锚点并排在第一位",
            hubSpotId,
            hits.first().entry.spotId
        )
        assertEquals(SettingsSearchField.TITLE, hits.first().matchedField)
        assertTrue(
            "外观分区页的主题行也应出现在结果里：${hits.map { it.entry.spotId }}",
            hits.any { it.entry.section == SettingsSection.APPEARANCE && it.entry.spotId != hubSpotId }
        )
    }

    @Test
    fun `搜索深色能找到外观主题选项`() {
        val dark = SettingsSearchEntries.single { it.spotId == "appearance.theme.dark" }
        // 标题必须与设计系统里真实渲染的 McaAppearanceThemeChoice.DARK.label 逐字一致。
        assertEquals("深色", dark.title)
        assertEquals(SettingsSection.APPEARANCE, dark.section)
        val hits = searchSettings("深色")
        assertEquals(
            "「深色」是主题选项的标题，应精确命中并排在第一位",
            "appearance.theme.dark",
            hits.first().entry.spotId
        )
        assertEquals(SettingsSearchField.TITLE, hits.first().matchedField)
    }

    @Test
    fun `外观分区的每个主题选项都能按标题搜到`() {
        val hubSpotId = "hub.${SettingsSection.APPEARANCE.wireName}"
        val appearanceRows = SettingsSearchEntries.filter {
            it.section == SettingsSection.APPEARANCE && it.spotId != hubSpotId
        }
        assertEquals(
            "外观页只有 3 个真实可选主题行（McaAppearanceThemeChoice.entries）",
            listOf("跟随系统", "浅色", "深色"),
            appearanceRows.map { it.title }
        )
        appearanceRows.forEach { row ->
            assertEquals(
                "按标题「${row.title}」必须能命中 ${row.spotId}",
                row.spotId,
                searchSettings(row.title).first().entry.spotId
            )
        }
    }

    @Test
    fun `中英混合多词查询要求每个词都命中`() {
        val hits = searchSettings("日志 log")
        assertTrue("中英混合查询不应为空", hits.isNotEmpty())
        assertEquals(
            "同时含「日志」与「log」的行应排在第一位",
            "hub.logs",
            hits.first().entry.spotId
        )
        // 「日志/诊断」这一组里只有提到 log 的条目能同时满足两个词。
        assertTrue(
            "结果必须每个词都命中：${hits.map { it.entry.spotId }}",
            hits.none { it.entry.spotId == "logs.diagnostic_report" }
        )
    }

    @Test
    fun `大小写与首尾空白不影响结果`() {
        val baseline = searchSettings("runtime").map { it.entry.spotId }
        assertEquals(baseline, searchSettings("RUNTIME").map { it.entry.spotId })
        assertEquals(baseline, searchSettings("  Runtime  ").map { it.entry.spotId })
        assertTrue("runtime 应有结果", baseline.isNotEmpty())
    }

    @Test
    fun `可以对自定义索引子集搜索`() {
        val aboutOnly = SettingsSearchEntries.filter { it.section == SettingsSection.ABOUT }
        assertTrue(
            "自定义子集内的标题应能命中",
            searchSettings("版本信息", aboutOnly).any { it.entry.spotId == "about.version_info" }
        )
        assertTrue(
            "子集外的行不应出现在结果里",
            searchSettings("内存", aboutOnly).isEmpty()
        )
    }

    @Test
    fun `字段权重保持标题大于关键词大于分组`() {
        assertTrue(
            "标题权重必须高于关键词",
            SettingsSearchField.TITLE.weight() > SettingsSearchField.KEYWORD.weight()
        )
        assertTrue(
            "关键词权重必须高于分组",
            SettingsSearchField.KEYWORD.weight() > SettingsSearchField.GROUP.weight()
        )
    }
}
