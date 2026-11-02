package com.muyuchat.feature.agent.workspace.ui

import androidx.compose.ui.graphics.vector.ImageVector
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.feature.agent.workspace.AgentStatusTone
import com.muyuchat.feature.agent.workspace.AgentTimelineKind
import com.muyuchat.feature.agent.workspace.AgentTimelineRow
import java.util.Locale

/**
 * Presentation-only category for one timeline row. It only selects the leading glyph; it never
 * reclassifies an event, changes a tone, or exposes new data. Tool keys are lowercase dotted
 * identifiers such as `workspace.read_text`, so the first recognised verb token decides the
 * category and unknown tools fall back to the generic wrench.
 */
internal enum class AgentTimelineCategory {
    READ,
    WRITE,
    SEARCH,
    LIST,
    APPROVAL,
    ERROR,
    MODEL,
    QUESTION,
    ARTIFACT,
    BUDGET,
    RUN,
    TOOL,
    OTHER
}

internal fun agentTimelineCategory(row: AgentTimelineRow): AgentTimelineCategory = when (row.kind) {
    AgentTimelineKind.APPROVAL -> AgentTimelineCategory.APPROVAL
    AgentTimelineKind.ERROR -> AgentTimelineCategory.ERROR
    AgentTimelineKind.MODEL -> AgentTimelineCategory.MODEL
    AgentTimelineKind.QUESTION -> AgentTimelineCategory.QUESTION
    AgentTimelineKind.ARTIFACT -> AgentTimelineCategory.ARTIFACT
    AgentTimelineKind.BUDGET -> AgentTimelineCategory.BUDGET
    AgentTimelineKind.RUN -> AgentTimelineCategory.RUN
    AgentTimelineKind.TOOL -> toolActionCategory(row.title) ?: if (row.tone == AgentStatusTone.ERROR) {
        AgentTimelineCategory.ERROR
    } else {
        AgentTimelineCategory.TOOL
    }
    AgentTimelineKind.OTHER -> if (row.tone == AgentStatusTone.ERROR) {
        AgentTimelineCategory.ERROR
    } else {
        AgentTimelineCategory.OTHER
    }
}

internal fun agentTimelineCategoryIcon(category: AgentTimelineCategory): ImageVector =
    when (category) {
        AgentTimelineCategory.READ -> McaIcons.Visibility
        AgentTimelineCategory.WRITE -> McaIcons.Edit
        AgentTimelineCategory.SEARCH -> McaIcons.Search
        AgentTimelineCategory.LIST -> McaIcons.FormatListBulleted
        AgentTimelineCategory.APPROVAL -> McaIcons.Lock
        AgentTimelineCategory.ERROR -> McaIcons.ErrorOutline
        AgentTimelineCategory.MODEL -> McaIcons.AutoAwesome
        AgentTimelineCategory.QUESTION -> McaIcons.HelpOutline
        AgentTimelineCategory.ARTIFACT -> McaIcons.Description
        AgentTimelineCategory.BUDGET -> McaIcons.Speed
        AgentTimelineCategory.RUN -> McaIcons.PlayArrow
        AgentTimelineCategory.TOOL -> McaIcons.Build
        AgentTimelineCategory.OTHER -> McaIcons.Info
    }

/** Verb tokens are checked in title order so `create_directory` is a write, not a list. */
private val VERB_CATEGORIES: List<Pair<AgentTimelineCategory, Set<String>>> = listOf(
    AgentTimelineCategory.SEARCH to setOf("search", "find", "grep", "query", "lookup", "scan"),
    AgentTimelineCategory.LIST to setOf("list", "ls", "glob", "tree", "dir", "directory", "enumerate"),
    AgentTimelineCategory.APPROVAL to setOf("approval", "approve", "authorize", "permission", "grant"),
    AgentTimelineCategory.READ to setOf(
        "read", "open", "cat", "head", "tail", "view", "fetch", "load", "inspect", "get", "download"
    ),
    AgentTimelineCategory.WRITE to setOf(
        "write", "edit", "append", "create", "save", "update", "delete", "remove",
        "move", "copy", "rename", "mkdir", "patch", "apply", "upload", "put"
    ),
    AgentTimelineCategory.RUN to setOf("run", "exec", "execute", "shell", "bash", "terminal")
)

private val CHINESE_VERB_RULES: List<Pair<AgentTimelineCategory, List<String>>> = listOf(
    AgentTimelineCategory.SEARCH to listOf("搜索", "查找", "检索", "查询"),
    AgentTimelineCategory.LIST to listOf("列表", "列出", "目录", "枚举"),
    AgentTimelineCategory.APPROVAL to listOf("授权", "审批", "批准", "许可"),
    AgentTimelineCategory.READ to listOf("读取", "查看", "打开", "载入", "下载", "获取"),
    AgentTimelineCategory.WRITE to listOf("写入", "编辑", "创建", "保存", "删除", "移动", "复制", "修改", "上传"),
    AgentTimelineCategory.RUN to listOf("运行", "执行", "终端", "命令")
)

private val WORD_SEPARATOR = Regex("[^a-z0-9]+")

private fun toolActionCategory(title: String): AgentTimelineCategory? {
    val normalized = title.lowercase(Locale.ROOT)
    for (word in normalized.split(WORD_SEPARATOR)) {
        VERB_CATEGORIES.firstOrNull { (_, verbs) -> word in verbs }?.let { return it.first }
    }
    CHINESE_VERB_RULES.forEach { (category, verbs) ->
        if (verbs.any { normalized.contains(it) }) return category
    }
    return null
}
