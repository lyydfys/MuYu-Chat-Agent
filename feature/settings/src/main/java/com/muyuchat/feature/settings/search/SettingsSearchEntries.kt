package com.muyuchat.feature.settings.search

import com.muyuchat.feature.settings.SettingsSection

/**
 * Result-group heading of the dashboard cards. Matches the "配置中心" heading the hub renders above
 * its grid (`SettingsScreens.kt:526`), so a search for it lists the sections themselves.
 */
private const val HUB_GROUP: String = "配置中心"

/** Page heading rendered by `RuntimeScreen` (`SettingsRuntimeScreen.kt:100`). */
private const val RUNTIME_GROUP: String = "运行状态"

/** Page heading rendered by `LogsApiScreen` (`SettingsLogsPrivacyScreens.kt:94`). */
private const val LOGS_GROUP: String = "日志/诊断"

/** Page heading rendered by `PrivacyDataScreen` (`SettingsLogsPrivacyScreens.kt:350`). */
private const val PRIVACY_GROUP: String = "隐私与数据"

/**
 * The single source of truth for settings search: one row per jumpable spot on the settings surface.
 *
 * **Coverage rule.** The dashboard in `SettingsScreens.kt` renders exactly one clickable card per
 * [SettingsSection] (`SettingsSectionGrid` -> `SettingsSection.entries`), and each card opens a
 * section page whose real rows live in the sibling `Settings*Screen.kt` files. Level one of this
 * table is one entry per dashboard card, derived from [SettingsSection.entries], so the card list
 * and the index cannot drift. The rest are the concrete rows those pages render. Every entry below
 * is a title string that exists in the source at the cited line - nothing here is invented, and a
 * row that gets deleted from the UI must lose its entry here.
 *
 * **Hub cards are derived, never listed by hand.** [settingsHubEntries] maps the real enum through
 * [hubEntry], so a new section automatically gets its `hub.<wireName>` entry, and [hubSearchKeywords]
 * is an exhaustive `when` that fails to compile until the new value has search vocabulary. That is
 * the whole point of deleting the mirrored enum that used to live in this package: a mirrored list
 * silently stayed at six entries when `APPEARANCE` was added.
 *
 * **Split across two files, never inside one section.** A section's rows all live in one file:
 * `RUNTIME`, `LOGS` and `PRIVACY` here, `SEARCH`, `APPEARANCE`, `EXPERIMENTS` and `ABOUT` in
 * [settingsSectionPageEntries]. The split is a pure-data line-budget split (see
 * `docs/architecture/MCA_PROJECT_HARD_LOCK.md` section 8), not a responsibility split; callers only
 * ever read [SettingsSearchEntries].
 *
 * Adding a row that does not exist on screen would make search jump to a row with no anchor, so
 * only add an entry together with the rendered row.
 */
internal val SettingsSearchEntries: List<SettingsSearchEntry> = buildList {
    addAll(settingsHubEntries())
    addAll(settingsRuntimeLogsPrivacyEntries())
    addAll(settingsSectionPageEntries())
}

/**
 * One entry per dashboard card, in dashboard order: `hub.runtime`, `hub.logs`, ... .
 *
 * The title and the section come from the enum, so a renamed label or a dropped section cannot leave
 * a stale card in the index. Only the search vocabulary is local, because the enum must not carry
 * search concerns into the UI file.
 *
 * A function rather than a `val` so [SettingsSearchEntries] can be documented above the table
 * without depending on top-level initialisation order (a forward reference in an initialiser does
 * not compile).
 */
private fun settingsHubEntries(): List<SettingsSearchEntry> =
    SettingsSection.entries.map { it.hubEntry() }

private fun SettingsSection.hubEntry(): SettingsSearchEntry = SettingsSearchEntry(
    spotId = "hub.$wireName",
    section = this,
    title = title,
    keywords = hubSearchKeywords(),
    group = HUB_GROUP
)

/**
 * Extra search vocabulary of one dashboard card: English terms, synonyms and the pre-rename names
 * that other screens still print.
 *
 * The `when` is exhaustive on purpose and must stay that way - an `else` branch would let a new
 * [SettingsSection] enter the index with no vocabulary at all.
 */
private fun SettingsSection.hubSearchKeywords(): List<String> = when (this) {
    SettingsSection.RUNTIME -> listOf(
        "runtime", "模型与运行", "运行状态", "模型加载", "生成参数", "后端", "内存", "model", "应用更新"
    )
    SettingsSection.LOGS -> listOf(
        // "日志与接口" is the pre-rename name still printed by SettingsRuntimeScreen.kt:227.
        "logs", "log", "日志与排错", "日志与接口", "接口", "运行日志", "API 日志", "诊断导出", "备份"
    )
    SettingsSection.PRIVACY -> listOf(
        "privacy", "本机数据", "清理", "缓存边界", "数据", "传感器", "短信", "设备能力"
    )
    SettingsSection.SEARCH -> listOf(
        "search", "web search", "联网与检索", "搜索服务", "触发策略", "来源质量", "搜索"
    )
    SettingsSection.APPEARANCE -> listOf(
        "appearance", "外观", "主题", "theme", "跟随系统", "浅色", "深色", "深色模式", "暗色"
    )
    SettingsSection.EXPERIMENTS -> listOf(
        "experiments", "experimental", "对话与生成", "预览", "beta", "实验", "本地工具", "富文本"
    )
    SettingsSection.ABOUT -> listOf(
        "about", "更新与关于", "版本更新", "开源许可", "项目信息", "version", "license"
    )
}

/**
 * Rows rendered by the runtime, logs and privacy pages.
 *
 * Kept as a function rather than a top-level `val` so [SettingsSearchEntries] can be declared and
 * documented above the table without depending on top-level initialisation order.
 */
private fun settingsRuntimeLogsPrivacyEntries(): List<SettingsSearchEntry> = listOf(
    // -- RUNTIME (page heading: SettingsRuntimeScreen.kt:100) -----------------------------------
    // SettingsRuntimeScreen.kt:103
    SettingsSearchEntry(
        spotId = "runtime.offline_translation",
        section = SettingsSection.RUNTIME,
        title = "离线图片提示词翻译 · Hy-MT2",
        keywords = listOf("翻译", "translation", "Hy-MT2", "图片提示词", "离线翻译", "下载", "导入"),
        group = RUNTIME_GROUP
    ),
    // SettingsRuntimeScreen.kt:376
    SettingsSearchEntry(
        spotId = "runtime.app_update",
        section = SettingsSection.RUNTIME,
        title = "应用更新",
        keywords = listOf("update", "更新", "自动检查", "GitHub Releases", "检查更新"),
        group = RUNTIME_GROUP
    ),
    // SettingsRuntimeScreen.kt:179
    SettingsSearchEntry(
        spotId = "runtime.model",
        section = SettingsSection.RUNTIME,
        title = "当前模型",
        keywords = listOf("model", "模型", "已加载", "本地模型"),
        group = RUNTIME_GROUP
    ),
    // SettingsRuntimeScreen.kt:191
    SettingsSearchEntry(
        spotId = "runtime.performance",
        section = SettingsSection.RUNTIME,
        title = "性能",
        keywords = listOf("performance", "速度", "首 token", "ttft", "token/s", "解码"),
        group = RUNTIME_GROUP
    ),
    // SettingsRuntimeScreen.kt:201
    SettingsSearchEntry(
        spotId = "runtime.memory",
        section = SettingsSection.RUNTIME,
        title = "内存",
        keywords = listOf("memory", "RSS", "PSS", "占用", "可用内存"),
        group = RUNTIME_GROUP
    ),
    // SettingsRuntimeScreen.kt:215
    SettingsSearchEntry(
        spotId = "runtime.load",
        section = SettingsSection.RUNTIME,
        title = "实时负载",
        keywords = listOf("cpu", "gpu", "npu", "负载", "占用率"),
        group = RUNTIME_GROUP
    ),
    // SettingsRuntimeScreen.kt:224 (static label; the row retitles itself to "最近问题" on error)
    SettingsSearchEntry(
        spotId = "runtime.diagnostic",
        section = SettingsSection.RUNTIME,
        title = "诊断",
        keywords = listOf("最近问题", "error", "错误", "排错", "故障"),
        group = RUNTIME_GROUP
    ),

    // -- LOGS (page heading: SettingsLogsPrivacyScreens.kt:94) ----------------------------------
    // SettingsLogsPrivacyScreens.kt:139
    SettingsSearchEntry(
        spotId = "logs.diagnostic_report",
        section = SettingsSection.LOGS,
        title = "诊断报告",
        keywords = listOf("报告", "复制", "导出", "export", "diagnostics"),
        group = LOGS_GROUP
    ),
    // SettingsLogsPrivacyScreens.kt:155
    SettingsSearchEntry(
        spotId = "logs.runtime_records",
        section = SettingsSection.LOGS,
        title = "运行记录",
        keywords = listOf("logs", "运行日志", "刷新", "runtime log"),
        group = LOGS_GROUP
    ),
    // SettingsLogsPrivacyScreens.kt:178 heading, :189 row title
    SettingsSearchEntry(
        spotId = "logs.agent_records",
        section = SettingsSection.LOGS,
        title = "调参记录",
        keywords = listOf("智能调参", "agent", "调参", "记录"),
        group = LOGS_GROUP
    ),
    // SettingsLogsPrivacyScreens.kt:220
    SettingsSearchEntry(
        spotId = "logs.backup",
        section = SettingsSection.LOGS,
        title = "备份与恢复",
        keywords = listOf("backup", "restore", "备份", "恢复", "导出备份包", "JSON"),
        group = LOGS_GROUP
    ),

    // -- PRIVACY (page heading: SettingsLogsPrivacyScreens.kt:350) ------------------------------
    // SettingsLogsPrivacyScreens.kt:353
    SettingsSearchEntry(
        spotId = "privacy.memory",
        section = SettingsSection.PRIVACY,
        title = "记忆管理",
        keywords = listOf("记忆", "memory", "助手记忆"),
        group = PRIVACY_GROUP
    ),
    // SettingsLogsPrivacyScreens.kt:363
    SettingsSearchEntry(
        spotId = "privacy.local_data",
        section = SettingsSection.PRIVACY,
        title = "本地数据",
        keywords = listOf("数据", "占用", "图片", "文件", "聊天"),
        group = PRIVACY_GROUP
    ),
    // SettingsLogsPrivacyScreens.kt:370
    SettingsSearchEntry(
        spotId = "privacy.file_index",
        section = SettingsSection.PRIVACY,
        title = "文件库索引",
        keywords = listOf("文件索引", "index", "上传", "文本索引"),
        group = PRIVACY_GROUP
    ),
    // SettingsLogsPrivacyScreens.kt:495
    SettingsSearchEntry(
        spotId = "privacy.prefix_cache",
        section = SettingsSection.PRIVACY,
        title = "持久化前缀缓存",
        keywords = listOf("前缀缓存", "prefix cache", "kv cache", "命中率", "缓存"),
        group = PRIVACY_GROUP
    ),
    // SettingsLogsPrivacyScreens.kt:570
    SettingsSearchEntry(
        spotId = "privacy.cleanup",
        section = SettingsSection.PRIVACY,
        title = "清理能力",
        keywords = listOf("清理", "清空", "clear", "删除", "聊天记录"),
        group = PRIVACY_GROUP
    ),
    // SettingsLogsPrivacyScreens.kt:989
    SettingsSearchEntry(
        spotId = "privacy.device_access",
        section = SettingsSection.PRIVACY,
        title = "设备传感器与位置（DSH 能力）",
        keywords = listOf(
            "sensor", "传感器", "定位", "手电筒", "陀螺仪", "加速度", "磁力计", "气压", "步数", "光线"
        ),
        group = PRIVACY_GROUP
    ),
    // SettingsLogsPrivacyScreens.kt:1056
    SettingsSearchEntry(
        spotId = "privacy.sms",
        section = SettingsSection.PRIVACY,
        title = "短信只读查询（DSH 能力）",
        keywords = listOf("短信", "sms", "只读", "权限"),
        group = PRIVACY_GROUP
    )
)
