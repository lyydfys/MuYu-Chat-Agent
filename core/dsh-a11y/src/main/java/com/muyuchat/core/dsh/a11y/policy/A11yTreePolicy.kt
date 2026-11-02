package com.muyuchat.core.dsh.a11y.policy

/**
 * Pure tree policy: caps, truncation, password redaction and node lookup.
 *
 * The Android adapter only walks the real `AccessibilityNodeInfo` tree and produces
 * [A11yNodeDraft]s; every decision that a reviewer might question (how much text is kept, what a
 * password field exposes, which node a text/point/ref resolves to) lives here and is unit-tested
 * on the JVM.
 */
object A11yTreePolicy {

    /** Shown when the readable tree is empty; the reference uses the same guidance. */
    const val EMPTY_SCREEN_HINT =
        "当前界面没有可读控件（常见于 Unity/游戏/自绘界面）。请改用 android_see 截屏看图，" +
            "并优先用分数坐标（fx/fy，0~1）点击/滑动——截图会被模型查看器缩放，绝对像素坐标会点偏。"

    /**
     * Applies the node/depth/text caps and password redaction.
     *
     * Nodes are emitted in DFS order, and the emitted list is always a prefix of [drafts], so
     * `node.index` keeps pointing at the same `AccessibilityNodeInfo` handle the adapter captured.
     * When the global text budget is exhausted the remaining nodes are not listed and
     * [A11yTreeSnapshot.truncated] / [A11yTreeSnapshot.textTruncated] say so.
     */
    fun buildSnapshot(
        generation: Long,
        packageName: String,
        windowId: Int,
        drafts: List<A11yNodeDraft>,
        walkTruncated: Boolean
    ): A11yTreeSnapshot {
        val nodes = ArrayList<A11yNode>(minOf(drafts.size, A11yLimits.MAX_NODES))
        var nodeCapReached = false
        var depthCapReached = false
        var textCapReached = false
        var budget = A11yLimits.MAX_SCREEN_TEXT_CHARS
        var redactedCount = 0

        for ((position, draft) in drafts.withIndex()) {
            if (position >= A11yLimits.MAX_NODES) {
                nodeCapReached = true
                break
            }
            if (draft.depth > A11yLimits.MAX_DEPTH) {
                depthCapReached = true
                continue
            }
            val password = draft.password
            val text = if (password) "" else truncate(draft.text)
            val desc = if (password) "" else truncate(draft.desc)
            if (password) redactedCount++
            val cost = text.length + desc.length
            if (cost > budget) {
                textCapReached = true
                break
            }
            budget -= cost
            nodes += A11yNode(
                index = position,
                depth = draft.depth,
                text = text,
                desc = desc,
                cls = draft.cls,
                rect = draft.rect,
                clickable = draft.clickable,
                input = draft.input,
                checked = draft.checked,
                selected = draft.selected,
                scrollable = draft.scrollable,
                password = password,
                redacted = password,
                ref = A11yNodeRef(generation, position)
            )
        }

        val truncated = walkTruncated || nodeCapReached || depthCapReached || textCapReached
        return A11yTreeSnapshot(
            generation = generation,
            packageName = packageName,
            windowId = windowId,
            nodes = nodes,
            truncated = truncated,
            textTruncated = textCapReached,
            redactedCount = redactedCount
        )
    }

    /** Caps one node label at [A11yLimits.MAX_NODE_TEXT_CHARS] with the reference's ellipsis. */
    fun truncate(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length <= A11yLimits.MAX_NODE_TEXT_CHARS) return trimmed
        return trimmed.take(A11yLimits.MAX_NODE_TEXT_CHARS) + "…"
    }

    /**
     * Best text/description match for `/tap`.
     *
     * Rank mirrors the reference service: exact+clickable (4) > partial+clickable (3) > exact (2) >
     * partial (1). Ties keep DFS order, so the result is deterministic.
     */
    fun findBestMatch(snapshot: A11yTreeSnapshot, needle: String?): A11yNode? {
        val target = needle?.trim()?.lowercase().orEmpty()
        if (target.isEmpty()) return null
        var best: A11yNode? = null
        var bestRank = 0
        for (node in snapshot.nodes) {
            if (node.password) continue
            val text = node.text.lowercase()
            val desc = node.desc.lowercase()
            val exact = text == target || desc == target
            val partial = !exact && (text.contains(target) || desc.contains(target))
            if (!exact && !partial) continue
            val rank = when {
                exact && node.clickable -> 4
                partial && node.clickable -> 3
                exact -> 2
                else -> 1
            }
            if (rank > bestRank) {
                bestRank = rank
                best = node
            }
        }
        return best
    }

    /** Deepest node containing [point]; the deepest clickable node wins when one exists. */
    fun nodeAtPoint(snapshot: A11yTreeSnapshot, point: A11yPoint): A11yNode? {
        var deepest: A11yNode? = null
        var clickable: A11yNode? = null
        for (node in snapshot.nodes) {
            if (!node.rect.contains(point)) continue
            if (deepest == null || node.depth >= deepest.depth) deepest = node
            if (node.clickable && (clickable == null || node.depth >= clickable.depth)) clickable = node
        }
        return clickable ?: deepest
    }

    /** True when a freshly captured node still is the node a stored reference pointed at. */
    fun matchesIdentity(previous: A11yNode, fresh: A11yNode): Boolean =
        previous.index == fresh.index &&
            previous.rect == fresh.rect &&
            previous.text == fresh.text &&
            previous.desc == fresh.desc &&
            previous.cls == fresh.cls
}
