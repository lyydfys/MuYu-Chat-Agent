package com.muyuchat.mca.agent.knowledge

import android.content.Context

/**
 * Read-only access to the bundled operation knowledge ("how to drive this app / site").
 *
 * MCA already has two mechanisms that carry *capability*: Skills (the `agent/extensions/skills`
 * package, a `SKILL.md` frontmatter package with tool patterns, at most four enabled at once) and
 * tool packages (executable). Neither fits here: Skills are user-installed packages that must
 * declare tool patterns and are counted against the four-skill cap, while this knowledge is shipped
 * with the APK, changes with the app, and only ever contributes *text* to a prompt. So it stays a
 * separate, read-only asset index rather than a fifth pseudo-Skill.
 *
 * Everything that decides relevance and size is a pure function over strings, so the whole index is
 * unit-testable without Android: [load] has a `Context` overload for production and a reader-lambda
 * overload for tests.
 */
class AgentKnowledgeIndex private constructor(private val documents: List<Document>) {

    /** One bundled document, already read from assets. */
    class Document internal constructor(
        /** Stable asset name without extension, e.g. `wechat`. */
        val id: String,
        /** Human title shown in diagnostics, taken from the document's `# ` heading. */
        val title: String,
        /** Full body (never truncated at load time; truncation happens per query). */
        val body: String,
        /** Lowercase match terms: app names, page names and the actions this document covers. */
        val keywords: List<String>
    )

    /** One search result: the document plus the excerpt a caller may put in a prompt. */
    class Hit internal constructor(
        val id: String,
        val title: String,
        val excerpt: String,
        /** Number of distinct keyword matches; higher means more relevant. Exposed for tests. */
        val score: Int
    )

    /** Ids of every bundled document, in declaration order. */
    fun documentIds(): List<String> = documents.map { it.id }

    /**
     * Returns at most [limit] most relevant documents for [query], most relevant first.
     *
     * Matching is deliberately simple and total: a document is relevant when any of its keywords
     * occurs in the query, or when the query occurs in the title. An unmatched query returns an
     * empty list — never "the closest available document" — because injecting unrelated operation
     * steps is worse than injecting nothing.
     */
    fun search(query: String, limit: Int = DEFAULT_LIMIT): List<Hit> {
        val normalized = normalize(query)
        if (normalized.isEmpty() || limit <= 0) return emptyList()
        return documents
            .mapNotNull { document ->
                val score = scoreOf(document, normalized)
                if (score <= 0) {
                    null
                } else {
                    Hit(
                        id = document.id,
                        title = document.title,
                        excerpt = truncate(document.body, MAX_EXCERPT_CHARS),
                        score = score
                    )
                }
            }
            .sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.id })
            .take(limit)
    }

    /**
     * Convenience for prompt assembly: the concatenated excerpts of [search], bounded to
     * [MAX_EXCERPT_CHARS] in total, or an empty string when nothing matched.
     */
    fun promptText(query: String, limit: Int = DEFAULT_LIMIT): String =
        search(query, limit)
            .map { it.excerpt }
            .fold(StringBuilder()) { builder, excerpt ->
                if (builder.isEmpty()) {
                    builder.append(excerpt)
                } else if (builder.length + excerpt.length + 2 <= MAX_PROMPT_CHARS) {
                    builder.append("\n\n").append(excerpt)
                } else {
                    builder
                }
            }
            .toString()

    private fun scoreOf(document: Document, normalizedQuery: String): Int {
        var score = 0
        document.keywords.forEach { keyword ->
            if (keyword.isNotEmpty() && normalizedQuery.contains(keyword)) score += 2
        }
        val title = document.title.lowercase()
        if (title.isNotEmpty() && normalizedQuery.contains(title)) score += 3
        // Reverse direction: the user typed a phrase that lives inside the document (for example a
        // heading), which is a weaker signal than a curated keyword but still better than nothing.
        if (score == 0 && normalizedQuery.length >= 2 &&
            document.body.lowercase().contains(normalizedQuery)
        ) {
            score = 1
        }
        return score
    }

    private fun normalize(raw: String): String = raw.trim().lowercase()

    private fun truncate(text: String, limit: Int): String {
        if (text.length <= limit) return text
        var cut = text.take(limit - 1)
        // Never end inside a surrogate pair.
        if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
        return cut + "…"
    }

    companion object {
        /** Two documents is enough to cover "where do I click" without flooding a prompt. */
        const val DEFAULT_LIMIT: Int = 2

        /** Hard cap for one document's excerpt. */
        const val MAX_EXCERPT_CHARS: Int = 2_000

        /** Hard cap for [promptText] across all selected documents. */
        const val MAX_PROMPT_CHARS: Int = 4_000

        /**
         * Curated terms per document. Kept in code (not in the asset) so a wording change in a
         * Markdown file cannot silently change routing.
         */
        private val KEYWORDS: Map<String, List<String>> = mapOf(
            "wechat" to listOf(
                "微信", "wechat", "weixin", "联系人", "聊天记录", "群聊", "群", "发消息",
                "朋友圈", "好友", "备注"
            ),
            "settings" to listOf(
                "设置", "settings", "开发者", "开发者选项", "无线调试", "adb", "调试",
                "无障碍", "辅助功能", "电池", "电池优化", "白名单", "省电", "关于手机", "版本号"
            ),
            "browser" to listOf(
                "浏览器", "browser", "网页", "网站", "加载", "等待", "弹窗", "按钮",
                "验证码", "人机验证", "滑块", "反爬", "限流", "登录", "表单", "提交"
            )
        )

        private val ORDER = listOf("wechat", "settings", "browser")

        private const val ASSET_DIR = "agent-knowledge"

        /** Production entry point: reads the bundled Markdown from the APK assets. */
        fun load(context: Context): AgentKnowledgeIndex =
            load { name ->
                runCatching {
                    context.applicationContext.assets
                        .open("$ASSET_DIR/$name.md")
                        .use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
            }

        /**
         * Test seam: [readAsset] receives a bare document name (`wechat`) and returns its body or
         * `null` when absent. Missing or unreadable documents are skipped instead of failing the
         * whole index, so one damaged asset cannot disable the feature.
         */
        fun load(readAsset: (String) -> String?): AgentKnowledgeIndex {
            val documents = ORDER.mapNotNull { id ->
                val body = runCatching { readAsset(id) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                Document(
                    id = id,
                    title = titleOf(body) ?: id,
                    body = body,
                    keywords = KEYWORDS[id].orEmpty()
                )
            }
            return AgentKnowledgeIndex(documents)
        }

        /** First `# ` heading, used as the human title; falls back to the file id. */
        private fun titleOf(body: String): String? = body.lineSequence()
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
