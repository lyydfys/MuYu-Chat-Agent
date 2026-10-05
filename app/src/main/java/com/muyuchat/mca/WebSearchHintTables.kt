package com.muyuchat.mca

import java.util.Calendar

/**
 * Web-search hint tables (P7/S8), moved verbatim from WebSearchProvider.kt: the search-intent,
 * freshness, official-source, comparison, research, factual, device-clock and generic-term tables.
 * Tables consumed by the retained file are internal here; the rest keep their original visibility.
 * Same-package resolution keeps every caller and behavioral test call unchanged.
 */

// P7/S8: shared with WebSearchProvider.kt.
internal val explicitSearchHints = listOf(
    "搜索",
    "搜一下",
    "查一下",
    "查找",
    "联网",
    "上网查",
    "网上查",
    "检索",
    "找资料",
    "search",
    "web search",
    "look up",
    "browse",
    "find online",
    "online search"
)
// P7/S5: shared with WebSearchScoringOperations.kt.
internal val freshnessSearchHints = listOf(
    "最新",
    "今天",
    "现在",
    "目前",
    "近期",
    "新闻",
    "价格",
    "发布",
    "版本",
    "更新",
    "排行",
    "评测",
    "latest",
    "today",
    "current",
    "recent",
    "news",
    "price",
    "released",
    "release",
    "version",
    "update",
    "ranking",
    "review"
)
// P7/S8: shared with WebSearchProvider.kt.
internal val DEVICE_CLOCK_EXTERNAL_FACT_HINTS = listOf(
    "新闻", "发生", "事件", "天气", "气温", "股价", "汇率", "比赛", "比分",
    "航班", "列车", "政策", "公告", "发布", "更新", "价格", "日程", "节日",
    "news", "happened", "event", "weather", "temperature", "stock", "exchange",
    "score", "flight", "train", "policy", "announcement", "released", "price"
)
// P7/S8: shared with WebSearchProvider.kt.
internal val DEVICE_CLOCK_ONLY_PATTERNS = listOf(
    Regex("(?:请问|告诉我|帮我看看|你知道)?(?:今天|现在|当前|此刻|此时)?(?:是)?(?:几号|几月几日|几月几号|日期是什么|星期几|周几|几点|几点了|时间是什么|当前时间|本地时间|当前日期|本地日期|当前时区|本地时区)(?:了|呢|是)?"),
    Regex("(?:今天|现在|当前|此刻|此时)(?:是)?(?:什么日期|什么时间|什么时区|哪一天)"),
    // Natural Chinese often puts the subject before the question particle:
    // "当前时区是什么" / "本地日期是什么".  Keep this narrowly scoped to
    // date/time/zone nouns so external factual questions still route online.
    Regex("(?:今天|现在|当前|此刻|此时|本地)(?:日期|时间|时区)(?:是什么|是啥|呢)?"),
    Regex("what(?:is|'s)?(?:the)?(?:current|local|today'?s)*(?:date|day|time|timezone)(?:today|now)?"),
    Regex("what(?:date|day)isittoday"),
    Regex("whattimeisit"),
    Regex("(?:current|local|today'?s)+(?:date|day|time|timezone)"),
    Regex("show(?:me)?(?:the)?(?:current|local|today'?s)*(?:date|day|time|timezone)")
)
// P7/S5: shared with WebSearchScoringOperations.kt.
internal val officialSearchHints = listOf(
    "接口",
    "api",
    "文档",
    "官网",
    "官方",
    "能力",
    "模型",
    "协议",
    "接入",
    "用法",
    "下载",
    "链接",
    "网址",
    "docs",
    "documentation",
    "official",
    "guide",
    "quickstart",
    "download",
    "github",
    "modelscope"
)
// P7/S8: shared with WebSearchProvider.kt.
internal val comparisonSearchHints = listOf(
    "对比",
    "区别",
    "哪个",
    "推荐",
    "选择",
    "优缺点",
    "更好",
    "compare",
    "comparison",
    "best",
    "recommend",
    "which",
    "vs",
    "versus",
    "alternative",
    "benchmark"
)
// P7/S4: shared with WebSearchPlanBuilding.kt.
internal val researchSearchHints = listOf(
    "深度",
    "研究",
    "调研",
    "资料",
    "方案",
    "架构",
    "测评",
    "评测",
    "横评",
    "竞品",
    "优缺点",
    "限制",
    "问题",
    "风险",
    "可行性",
    "生态",
    "部署",
    "research",
    "deep dive",
    "analysis",
    "survey",
    "proposal",
    "architecture",
    "evaluation",
    "benchmark",
    "limitations",
    "issues",
    "tradeoff",
    "trade-offs",
    "ecosystem",
    "deployment"
)
// P7/S8: shared with WebSearchProvider.kt.
internal val factualSearchHints = listOf(
    "天气",
    "汇率",
    "股价",
    "赛事",
    "比分",
    "政策",
    "法规",
    "公告",
    "榜单",
    "路线",
    "航班",
    "列车",
    "status",
    "weather",
    "exchange rate",
    "stock",
    "score",
    "policy",
    "regulation",
    "announcement",
    "flight",
    "train"
)
// P7/S8: shared with WebSearchProvider.kt.
internal val genericSearchTerms = setOf(
    "搜索",
    "联网",
    "检索",
    "查找",
    "资料",
    "来源",
    "回答",
    "最新",
    "今天",
    "现在",
    "目前",
    "近期",
    "新闻",
    "版本",
    "更新",
    "官方",
    "文档",
    "接口",
    "用法",
    "下载",
    "评测",
    "对比",
    "推荐",
    "search",
    "web",
    "online",
    "find",
    "look",
    "source",
    "sources",
    "answer",
    "chinese",
    "latest",
    "today",
    "current",
    "recent",
    "news",
    "version",
    "update",
    "updates",
    "official",
    "docs",
    "documentation",
    "api",
    "guide",
    "download",
    "review",
    "reviews",
    "compare",
    "comparison",
    "best",
    "recommend",
    "with",
    "and",
    "the",
    Calendar.getInstance().get(Calendar.YEAR).toString()
)
