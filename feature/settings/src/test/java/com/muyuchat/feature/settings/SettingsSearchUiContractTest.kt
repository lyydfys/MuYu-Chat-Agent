package com.muyuchat.feature.settings

import com.muyuchat.feature.settings.search.SettingsSearchEntries
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the settings-search UI wiring without Compose: this module's test source set has only JUnit,
 * so there is no `createComposeRule` and no Robolectric.
 *
 * The contract has two halves. Source-level checks assert the dashboard really renders the search
 * entry above the card grid and reuses the shared design-system controls instead of a home-made
 * field. Logic-level checks call the real filter/grouping functions that the page uses, so an empty
 * query or a grouping change fails here instead of silently rendering 54 rows on a phone.
 */
class SettingsSearchUiContractTest {

    @Test
    fun `搜索入口位于分区网格之前`() {
        val source = readSource(HUB_PATH)
        val searchItem = source.indexOf("item(key = \"search\")")
        val gridItem = source.indexOf("item(key = \"sections-grid\")")
        assertTrue(
            "设置首页里找不到搜索入口：期望有一个 item(key = \"search\")，否则搜索没有任何入口",
            searchItem >= 0
        )
        assertTrue(
            "设置首页里找不到分区网格：期望有一个 item(key = \"sections-grid\")",
            gridItem >= 0
        )
        assertTrue(
            "搜索入口必须渲染在分区网格之前（LazyColumn 的源码顺序就是渲染顺序），" +
                "实际 search=$searchItem grid=$gridItem",
            searchItem < gridItem
        )
        val entryField = source.indexOf("SettingsSearchEntryField(")
        assertTrue(
            "搜索入口没有调用 SettingsSearchEntryField：只留一个同名空 item 不算接入",
            entryField > searchItem && entryField < gridItem
        )
    }

    @Test
    fun `搜索页复用既有输入组件`() {
        val page = readSource(SEARCH_PAGE_PATH)
        assertTrue(
            "搜索页必须复用 design system 的 McaTextField，而不是自造输入控件",
            page.contains("McaTextField(")
        )
        assertTrue(
            "搜索页必须复用 McaIconButton 作为返回操作，而不是自造图标按钮",
            page.contains("McaIconButton(")
        )
        // 去掉共享控件的名字之后再找裸 TextField，避免 "McaTextField(" 被当成自造控件误判。
        val withoutSharedField = page.replace("McaTextField(", "")
        assertFalse(
            "搜索页不得直接使用未包装的 TextField/OutlinedTextField/BasicTextField 控件",
            withoutSharedField.contains("TextField(")
        )
        assertFalse(
            "搜索页不得使用 TextFieldDefaults 自造配色与形状",
            page.contains("TextFieldDefaults")
        )
        assertTrue(
            "输入框必须带 placeholder 文案，否则空输入时用户不知道这一格是干什么的",
            page.contains("placeholder = { Text(")
        )
    }

    @Test
    fun `搜索结果按分组呈现`() {
        // 「模型」同时命中首页卡片的检索词与运行页的行标题，因此必然跨至少两个分组。
        val hits = settingsSearchResults("模型")
        assertTrue("查询「模型」应至少命中一条索引项，实际为空：索引或匹配层坏了", hits.isNotEmpty())
        val groups = settingsSearchGroups(hits)
        assertTrue("命中项没有被分成任何组", groups.isNotEmpty())
        assertEquals(
            "分组后条目总数必须与命中数一致：分组不得丢弃或重复任何条目",
            hits.size,
            groups.sumOf { it.hits.size }
        )
        assertEquals(
            "分组不得改变整体命中顺序：把各组合并回去必须与原始命中序列逐条相同",
            hits.map { it.entry.spotId },
            groups.flatMap { it.hits }.map { it.entry.spotId }
        )
        assertEquals(
            "同一分组名不得出现两次：分组是唯一的，否则渲染出重复标题",
            groups.size,
            groups.map { it.group }.distinct().size
        )
        groups.forEach { group ->
            assertTrue("分组标题不能为空", group.group.isNotBlank())
            assertTrue("分组 ${group.group} 不能是空组", group.hits.isNotEmpty())
            group.hits.forEach { hit ->
                assertEquals(
                    "条目 ${hit.entry.spotId} 出现在错误的组里",
                    group.group,
                    hit.entry.group
                )
                assertTrue("每条结果都必须有可见标题", hit.entry.title.isNotBlank())
                assertTrue("每条结果都必须能显示所属分区名", hit.entry.section.title.isNotBlank())
            }
        }
        assertTrue(
            "「模型」命中应跨至少两个分组（配置中心 + 运行状态），实际分组：${groups.map { it.group }}",
            groups.size >= 2
        )
    }

    @Test
    fun `空查询不渲染全部条目`() {
        // 这是有意为之的产品决策：空查询显示提示语，而不是把整个索引一次性铺开。
        assertTrue("索引本身不能为空，否则本用例失去意义", SettingsSearchEntries.isNotEmpty())
        assertTrue(
            "空查询必须返回空集合，否则搜索页会一次性渲染 ${SettingsSearchEntries.size} 条记录",
            settingsSearchResults("").isEmpty()
        )
        assertTrue("纯空格查询必须返回空集合", settingsSearchResults("   ").isEmpty())
        assertTrue("全角空格查询必须返回空集合", settingsSearchResults("\u3000\u3000").isEmpty())
        assertTrue("制表符/换行查询必须返回空集合", settingsSearchResults("\t\n").isEmpty())
        assertTrue(
            "空查询的结果数必须严格小于索引总量",
            settingsSearchResults("").size < SettingsSearchEntries.size
        )
        assertTrue(
            "完全匹配不到的关键词必须返回空集合（无结果状态依赖它）",
            settingsSearchResults("zzz-没有这个设置-zzz").isEmpty()
        )
    }

    @Test
    fun `无查询结果时给出提示文案`() {
        val page = readSource(SEARCH_PAGE_PATH)
        assertTrue(
            "搜索页缺少「无结果」提示文案，用户会看到一个空白页面",
            page.contains("没有找到匹配的设置项，换个关键词试试。")
        )
        assertTrue(
            "搜索页缺少「空查询」提示文案，用户打开搜索页时不知道要做什么",
            page.contains("输入关键词搜索设置项。")
        )
        // 两句提示都必须真的被渲染分支引用，而不是只留在常量里。
        assertTrue(
            "空查询提示没有被渲染：期望出现在 query.isBlank() 分支里",
            page.contains("query.isBlank() -> SearchNotice(")
        )
        assertTrue(
            "无结果提示没有被渲染：期望出现在 groups.isEmpty() 分支里",
            page.contains("groups.isEmpty() -> SearchNotice(")
        )
    }

    @Test
    fun `点结果把整条索引项交给宿主以实现行级定位`() {
        // 第五轮起跳转升级为行级：结果回传整条 entry（section + spotId），宿主先开分区页再请求
        // reveal。分区级跳转仍然存在，但它现在是「该行未接线」时的降级路径，而不是唯一能力。
        val page = readSource(SEARCH_PAGE_PATH)
        assertTrue(
            "结果点击必须回传整条 entry，否则宿主拿不到 spotId、无法行级定位",
            page.contains("onOpenSection(hit.entry)")
        )
        val hub = readSource(HUB_PATH)
        assertTrue(
            "宿主必须把结果分区写入 section 状态，否则点击结果没有反应",
            hub.contains("section = entry.section")
        )
        assertTrue(
            "宿主必须在切换分区前请求 reveal，否则目标页组合时请求已丢失",
            hub.indexOf("reveal.revealTo(entry.spotId)") < hub.indexOf("section = entry.section")
        )
        assertTrue(
            "KDoc 必须如实写明「哪些行只到分区级」的降级边界",
            page.contains("SettingsRevealDegradation")
        )
    }

    @Test
    fun `搜索页是局部状态而不是新分区`() {
        val hub = readSource(HUB_PATH)
        assertTrue(
            "搜索状态必须是页面局部状态（rememberSaveable），不能靠新增分区承载",
            hub.contains("var searchOpen by rememberSaveable")
        )
        assertTrue(
            "搜索不得进入分区枚举：多出一个分区会改变首页卡片数量与冻结顺序",
            SettingsSection.entries.none { it.title.contains("搜索设置") }
        )
        // 第六轮起分区标题改按用户意图命名（MCA_SETTINGS_IA.md §4），顺序仍是冻结契约：
        // 搜索能力不得插进枚举，也不得让任何一张卡片消失或改名而不更新这里。
        assertEquals(
            "分区的先后顺序与用户意图标题是冻结契约，搜索能力不得插进枚举",
            listOf("模型与运行", "日志与排错", "隐私与数据", "联网与检索", "外观", "对话与生成", "更新与关于"),
            SettingsSection.entries.map { it.title }
        )
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
        const val SEARCH_PAGE_PATH =
            "feature/settings/src/main/java/com/muyuchat/feature/settings/SettingsSearchPage.kt"
    }
}
