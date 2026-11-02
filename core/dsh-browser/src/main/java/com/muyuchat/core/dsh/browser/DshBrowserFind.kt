package com.muyuchat.core.dsh.browser

/** One scored search hit. */
data class DshBrowserFindMatch(
    val node: DshBrowserNode,
    val score: Int
)

/** Bounded `find` result: total matches plus the best candidates. */
data class DshBrowserFindResult(
    val matched: Int,
    val candidates: List<DshBrowserNode>
)

/**
 * Pure text/role search over an observed node list.
 *
 * Same shape and scoring idea as the pinned reference (exact name > prefix > substring > role
 * synonym, in-view bonus, disabled penalty) but deterministic and bounded: the caller passes the
 * already-ordered node list, ties keep observation order, and at most
 * [DshBrowserLimits.MAX_FIND_LIMIT] candidates are returned.
 */
object DshBrowserFind {

    private val ROLE_SYNONYMS: Map<String, Set<String>> = mapOf(
        "button" to setOf("button"),
        "按钮" to setOf("button"),
        "link" to setOf("link"),
        "链接" to setOf("link"),
        "input" to setOf("textbox"),
        "textbox" to setOf("textbox"),
        "输入框" to setOf("textbox"),
        "search" to setOf("searchbox", "textbox"),
        "searchbox" to setOf("searchbox", "textbox"),
        "select" to setOf("combobox"),
        "dropdown" to setOf("combobox"),
        "combobox" to setOf("combobox"),
        "下拉" to setOf("combobox"),
        "checkbox" to setOf("checkbox"),
        "复选框" to setOf("checkbox"),
        "radio" to setOf("radio"),
        "单选框" to setOf("radio"),
        "click" to setOf("clickable"),
        "点击" to setOf("clickable")
    )

    fun search(
        nodes: List<DshBrowserNode>,
        rawQuery: String,
        roleFilter: String?,
        limit: Int
    ): DshBrowserFindResult {
        val query = rawQuery.trim().take(DshBrowserLimits.MAX_QUERY_CHARS).lowercase()
        val role = roleFilter?.trim()?.lowercase()?.take(DshBrowserLimits.MAX_ROLE_CHARS)
        val bounded = limit.coerceIn(1, DshBrowserLimits.MAX_FIND_LIMIT)
        if (query.isEmpty() && role.isNullOrEmpty()) return DshBrowserFindResult(0, emptyList())

        val hits = ArrayList<DshBrowserFindMatch>()
        for (node in nodes) {
            val nodeRole = node.role.lowercase()
            if (!role.isNullOrEmpty() && role != nodeRole) continue
            var score = score(node, query, nodeRole)
            if (score <= 0) continue
            if (node.inView) score += 10
            if (node.disabled) score -= 20
            hits.add(DshBrowserFindMatch(node, score))
        }
        // Stable sort keeps observation order for equal scores (deterministic output).
        hits.sortWith(compareByDescending<DshBrowserFindMatch> { it.score })
        return DshBrowserFindResult(
            matched = hits.size,
            candidates = hits.take(bounded).map { it.node }
        )
    }

    private fun score(node: DshBrowserNode, query: String, nodeRole: String): Int {
        if (query.isEmpty()) return if (roleSynonymMatch(nodeRole, query)) 40 else 1
        val name = node.name.lowercase()
        return when {
            name == query -> 100
            name.startsWith(query) -> 80
            name.contains(query) -> 60
            roleSynonymMatch(nodeRole, query) -> 40
            else -> 0
        }
    }

    private fun roleSynonymMatch(role: String, query: String): Boolean {
        if (role == query) return true
        val synonyms = ROLE_SYNONYMS[query] ?: return false
        return role in synonyms
    }
}
