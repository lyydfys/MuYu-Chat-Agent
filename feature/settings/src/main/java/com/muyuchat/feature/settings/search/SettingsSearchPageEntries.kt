package com.muyuchat.feature.settings.search

import com.muyuchat.feature.settings.SettingsSection

/** Page heading rendered by `SearchSettingsScreen` (`SettingsSearchScreen.kt:161`). */
private const val SEARCH_GROUP: String = "联网检索"

/**
 * Page heading of the appearance page. `McaAppearanceSection` draws it itself
 * (`McaAppearanceSection.kt:68`), and the detail header repeats it as `SettingsSection.APPEARANCE.title`.
 */
private const val APPEARANCE_GROUP: String = "外观"

/** Page heading rendered by `ExperimentsScreen` (`SettingsLogsPrivacyScreens.kt:629`). */
private const val EXPERIMENTS_GROUP: String = "实验功能"

/**
 * Page heading of the about page. There is no heading literal inside `SettingsAboutScreen.kt`: the
 * detail header renders `SettingsSection.ABOUT.title`, so the group name is that same string.
 */
private const val ABOUT_GROUP: String = "关于"

/**
 * Rows rendered by the search, appearance, experiments and about pages.
 *
 * The counterpart of `settingsRuntimeLogsPrivacyEntries` in `SettingsSearchEntries.kt`: the table is
 * split there and here only to keep each pure-data file inside the line budget of
 * `docs/architecture/MCA_PROJECT_HARD_LOCK.md` section 8. No section is ever split across the two
 * files, and callers only read `SettingsSearchEntries`.
 *
 * **APPEARANCE has three rows, and that is the whole page.** `SettingsAppearanceScreen`
 * (`SettingsScreens.kt:476`) renders one `McaAppearanceSection` and nothing else; that composable
 * draws a heading, one explanation and the three theme choices. The choices - not a hand-written
 * copy of them - are `McaAppearanceThemeChoice.SYSTEM/LIGHT/DARK` (`McaAppearanceThemeChoice.kt:17-19`)
 * whose labels "跟随系统" / "浅色" / "深色" are the titles below, so a search hit lands on the row
 * the user can actually select.
 */
internal fun settingsSectionPageEntries(): List<SettingsSearchEntry> = listOf(
    // -- SEARCH (page heading: SettingsSearchScreen.kt:161) --------------------------------------
    // SettingsSearchScreen.kt:164
    SettingsSearchEntry(
        spotId = "search.work_mode",
        section = SettingsSection.SEARCH,
        title = "工作方式",
        keywords = listOf("状态", "配置", "触发", "研究"),
        group = SEARCH_GROUP
    ),
    // SettingsSearchScreen.kt:177
    SettingsSearchEntry(
        spotId = "search.usage_help",
        section = SettingsSection.SEARCH,
        title = "使用说明",
        keywords = listOf("帮助", "help", "说明", "教程", "隐私边界"),
        group = SEARCH_GROUP
    ),
    // SettingsSearchScreen.kt:226
    SettingsSearchEntry(
        spotId = "search.enable",
        section = SettingsSection.SEARCH,
        title = "启用联网检索",
        keywords = listOf("enable", "开启", "联网", "检索", "开关"),
        group = SEARCH_GROUP
    ),
    // SettingsSearchScreen.kt:246
    SettingsSearchEntry(
        spotId = "search.advanced",
        section = SettingsSection.SEARCH,
        title = "高级设置",
        keywords = listOf("advanced", "高级", "触发方式", "研究模式"),
        group = SEARCH_GROUP
    ),
    // SettingsSearchScreen.kt:252
    SettingsSearchEntry(
        spotId = "search.trigger_mode",
        section = SettingsSection.SEARCH,
        title = "触发方式",
        keywords = listOf("trigger", "智能", "手动", "始终", "触发"),
        group = SEARCH_GROUP
    ),
    // SettingsSearchScreen.kt:269
    SettingsSearchEntry(
        spotId = "search.research_mode",
        section = SettingsSection.SEARCH,
        title = "研究模式",
        keywords = listOf("research", "自动", "普通", "深度", "多源研究"),
        group = SEARCH_GROUP
    ),
    // SettingsSearchScreen.kt:289 (label swaps to "SearxNG 地址" on the default provider)
    SettingsSearchEntry(
        spotId = "search.endpoint",
        section = SettingsSection.SEARCH,
        title = "搜索接口地址",
        keywords = listOf("SearxNG 地址", "endpoint", "接口地址", "searxng", "brave", "tavily", "jina"),
        group = SEARCH_GROUP
    ),
    // SettingsSearchScreen.kt:297
    SettingsSearchEntry(
        spotId = "search.api_key",
        section = SettingsSection.SEARCH,
        title = "API Key",
        keywords = listOf("apikey", "key", "密钥", "鉴权"),
        group = SEARCH_GROUP
    ),
    // SettingsSearchScreen.kt:317
    SettingsSearchEntry(
        spotId = "search.backup_providers",
        section = SettingsSection.SEARCH,
        title = "备用搜索源",
        keywords = listOf("备用", "backup provider", "接力", "限流"),
        group = SEARCH_GROUP
    ),
    // SettingsSearchScreen.kt:367
    SettingsSearchEntry(
        spotId = "search.max_results",
        section = SettingsSection.SEARCH,
        title = "结果数量",
        keywords = listOf("max results", "数量", "条数", "1-8"),
        group = SEARCH_GROUP
    ),
    // SettingsSearchScreen.kt:377
    SettingsSearchEntry(
        spotId = "search.fetch_page",
        section = SettingsSection.SEARCH,
        title = "抓取网页正文",
        keywords = listOf("fetch", "正文", "抓取", "网页内容"),
        group = SEARCH_GROUP
    ),
    // SettingsSearchScreen.kt:409
    SettingsSearchEntry(
        spotId = "search.testing",
        section = SettingsSection.SEARCH,
        title = "测试检索",
        keywords = listOf("测试", "test", "预检", "闭环自检", "协议自检"),
        group = SEARCH_GROUP
    ),
    // SettingsSearchScreen.kt:515
    SettingsSearchEntry(
        spotId = "search.recent",
        section = SettingsSection.SEARCH,
        title = "最近检索",
        keywords = listOf("诊断记录", "最近", "history", "diagnostics"),
        group = SEARCH_GROUP
    ),

    // -- APPEARANCE (page heading: McaAppearanceSection.kt:68) ----------------------------------
    // McaAppearanceThemeChoice.kt:17, rendered by the McaAppearanceThemeChoice.entries loop at
    // McaAppearanceSection.kt:84.
    SettingsSearchEntry(
        spotId = "appearance.theme.system",
        section = SettingsSection.APPEARANCE,
        title = "跟随系统",
        keywords = listOf(
            "system", "theme", "外观", "主题", "跟随", "自动", "深色模式", "浅色模式", "系统主题"
        ),
        group = APPEARANCE_GROUP
    ),
    // McaAppearanceThemeChoice.kt:18
    SettingsSearchEntry(
        spotId = "appearance.theme.light",
        section = SettingsSection.APPEARANCE,
        title = "浅色",
        keywords = listOf("light", "theme", "外观", "主题", "亮色", "白天", "浅色模式"),
        group = APPEARANCE_GROUP
    ),
    // McaAppearanceThemeChoice.kt:19
    SettingsSearchEntry(
        spotId = "appearance.theme.dark",
        section = SettingsSection.APPEARANCE,
        title = "深色",
        keywords = listOf("dark", "theme", "外观", "主题", "暗色", "夜间", "黑夜", "深色模式"),
        group = APPEARANCE_GROUP
    ),

    // -- EXPERIMENTS (page heading: SettingsLogsPrivacyScreens.kt:629) --------------------------
    // SettingsLogsPrivacyScreens.kt:632
    SettingsSearchEntry(
        spotId = "experiments.image_gen",
        section = SettingsSection.EXPERIMENTS,
        title = "本地生图",
        keywords = listOf("生图", "image", "绘图", "图片生成"),
        group = EXPERIMENTS_GROUP
    ),
    // SettingsLogsPrivacyScreens.kt:640
    SettingsSearchEntry(
        spotId = "experiments.multimodal",
        section = SettingsSection.EXPERIMENTS,
        title = "本地多模态",
        keywords = listOf("multimodal", "视觉", "vision", "多模态", "识图"),
        group = EXPERIMENTS_GROUP
    ),
    // SettingsLogsPrivacyScreens.kt:646
    SettingsSearchEntry(
        spotId = "experiments.web_search",
        section = SettingsSection.EXPERIMENTS,
        title = "联网检索",
        keywords = listOf("search", "检索", "联网"),
        group = EXPERIMENTS_GROUP
    ),
    // SettingsLogsPrivacyScreens.kt:695
    SettingsSearchEntry(
        spotId = "experiments.local_tools",
        section = SettingsSection.EXPERIMENTS,
        title = "聊天本地工具",
        keywords = listOf("tools", "本地工具", "时间", "算术", "掷骰", "骰子"),
        group = EXPERIMENTS_GROUP
    ),
    // SettingsLogsPrivacyScreens.kt:747
    SettingsSearchEntry(
        spotId = "experiments.rich_blocks",
        section = SettingsSection.EXPERIMENTS,
        title = "富文本块显示",
        keywords = listOf("rich blocks", "富文本", "details", "status", "显示"),
        group = EXPERIMENTS_GROUP
    ),
    // SettingsLogsPrivacyScreens.kt:665
    SettingsSearchEntry(
        spotId = "experiments.tools_mcp",
        section = SettingsSection.EXPERIMENTS,
        title = "工具 / MCP",
        keywords = listOf("mcp", "tools", "扩展", "后续扩展"),
        group = EXPERIMENTS_GROUP
    ),

    // -- ABOUT (page heading: SettingsSection.ABOUT.title) --------------------------------------
    // SettingsAboutScreen.kt:195
    SettingsSearchEntry(
        spotId = "about.author",
        section = SettingsSection.ABOUT,
        title = "主作者",
        keywords = listOf("author", "作者", "lyydfys"),
        group = ABOUT_GROUP
    ),
    // SettingsAboutScreen.kt:201
    SettingsSearchEntry(
        spotId = "about.repository",
        section = SettingsSection.ABOUT,
        title = "开源仓库",
        keywords = listOf("github", "仓库", "repo", "源码"),
        group = ABOUT_GROUP
    ),
    // SettingsAboutScreen.kt:214
    SettingsSearchEntry(
        spotId = "about.sponsor",
        section = SettingsSection.ABOUT,
        title = "赞助支持",
        keywords = listOf("sponsor", "赞助", "捐赠", "donate", "star"),
        group = ABOUT_GROUP
    ),
    // SettingsAboutScreen.kt:220
    SettingsSearchEntry(
        spotId = "about.feedback",
        section = SettingsSection.ABOUT,
        title = "意见反馈",
        keywords = listOf("feedback", "反馈", "issues", "建议"),
        group = ABOUT_GROUP
    ),
    // SettingsAboutScreen.kt:335
    SettingsSearchEntry(
        spotId = "about.check_update",
        section = SettingsSection.ABOUT,
        title = "检查更新",
        keywords = listOf("update", "检查", "版本更新", "releases"),
        group = ABOUT_GROUP
    ),
    // SettingsAboutScreen.kt:339
    SettingsSearchEntry(
        spotId = "about.update_history",
        section = SettingsSection.ABOUT,
        title = "更新记录",
        keywords = listOf("更新说明", "release notes", "changelog", "历史"),
        group = ABOUT_GROUP
    ),
    // SettingsAboutScreen.kt:590
    SettingsSearchEntry(
        spotId = "about.version_info",
        section = SettingsSection.ABOUT,
        title = "版本信息",
        keywords = listOf("version", "版本", "许可", "Apache-2.0", "自动检查"),
        group = ABOUT_GROUP
    )
)
