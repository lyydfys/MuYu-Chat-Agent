package com.muyuchat.mca.agent.tools

import com.muyuchat.core.dsh.a11y.policy.A11yNode

/**
 * One screen's worth of candidate controls, described the three ways a UI can identify a control.
 *
 * [byText] is the visible label, [byDescription] the accessibility description, and [byId] the
 * view's stable identifier. Separating them lets a caller say "the real label" instead of one bag of
 * needles, so ranking can prefer a text hit over a class-name hit.
 *
 * Note on [byId]: the a11y node model (`com.muyuchat.core.dsh.a11y.policy.A11yNode`) carries no
 * resource-name/viewId field 鈥?[A11yNode.cls] is the only identifier-like value a captured node has.
 * So this route currently matches the class name. When a viewId is added to the node model, only
 * [AgentScreenTargetResolver.idOf] has to change.
 */
data class ScreenTarget(
    val byText: List<String> = emptyList(),
    val byId: List<String> = emptyList(),
    val byDescription: List<String> = emptyList()
) {
    val isEmpty: Boolean
        get() = byText.isEmpty() && byId.isEmpty() && byDescription.isEmpty()
}

/** How well a node matched; ordered worst-to-best so the natural enum order is the ranking. */
enum class ScreenMatchKind {
    /** One label contains the other, e.g. needle "鎼滅储" inside label "鎼滅储鑱旂郴浜?. */
    CONTAINS,

    /** Label equals the needle after normalisation. */
    EXACT
}

/** Which of the three routes produced a candidate. */
enum class ScreenMatchRoute { TEXT, ID, DESCRIPTION }

/** One ranked candidate: the node plus why it matched, for [AgentScreenTargetResolver.describeCandidates]. */
data class ScreenCandidate(
    val node: A11yNode,
    val kind: ScreenMatchKind,
    val route: ScreenMatchRoute,
    /** The needle that matched, so a model can see which of its words landed. */
    val needle: String
)

/**
 * When `device.screen` tap-by-text finds nothing, work out *what the model probably meant*.
 *
 * The accessibility engine already has an exact-match reader (`A11yTreePolicy.findBestMatch`), and
 * that stays the authoritative hit path: a tap that resolves exactly never reaches this file. This
 * resolver exists for the miss case 鈥?the model asked for a control the tree cannot match verbatim
 * (a partial label, a differently-cased word, a self-drawn row whose only desc is a fragment) and
 * would otherwise have to guess coordinates. Instead it returns a short ranked list the model can
 * choose from, or act on with a coordinate tap.
 *
 * Matching rules, deliberately simple and explainable:
 *  - **Normalised, case- and whitespace-insensitive.** Labels and needles are trimmed, lower-cased
 *    and have inner whitespace collapsed, so `"  鎼滅储  鑱旂郴浜?"` and `"鎼滅储 鑱旂郴浜?` are the same.
 *  - **Bidirectional containment.** Either the label contains the needle *or* the needle contains
 *    the label. The second direction is what makes a natural-language goal work: the goal
 *    "甯垜鐐瑰紑鎼滅储鎸夐挳" contains the node label "鎼滅储", which is exactly the intended control.
 *  - **Short labels never match by containment.** A one-character container label would otherwise
 *    match every sentence; containment requires the contained side to be at least
 *    [AgentScreenTargetResolver.MIN_CONTAINMENT_CHARS] characters.
 *  - **No fuzzy grade, on purpose.** A near-miss guess ("登陆" for "登录") and an unrelated neighbour
 *    ("搜图" vs "搜索") measure identically under character overlap, so no threshold separates them.
 *    Offering a wrong control is worse than offering none, so a miss returns an empty list and the
 *    model is told to try a different word.
 *  - **Password nodes are never candidates.** The reader redacts their text; offering an empty
 *    redacted row as "the closest match" would invite a blind tap into a credential field.
 *  - **Ranked, capped, deterministic.** EXACT > CONTAINS; inside a kind, actionable nodes
 *    (clickable or input) first, then text-route hits, then shallower depth, then capture order.
 *    At most [AgentScreenTargetResolver.MAX_CANDIDATES] are returned, so a model's prompt stays
 *    bounded and the same tree always yields the same list.
 */
object AgentScreenTargetResolver {

    /** How many candidates a model is offered; enough to disambiguate, short enough to read. */
    const val MAX_CANDIDATES: Int = 5

    /** Smallest label/needle that may match by containment, in characters. */
    const val MIN_CONTAINMENT_CHARS: Int = 2



    /** Needles shorter than this are dropped: a single character matches half a screen. */
    private const val MIN_NEEDLE_CHARS: Int = 2

    /**
     * Candidates for a natural-language [goal], e.g. the text the model passed to `tap`.
     *
     * The goal is split into needles (see [needlesOf]); every node is then matched against every
     * needle on all three routes. This is the entry point the screen tool calls after an exact tap
     * miss.
     */
    fun resolve(goal: String, nodes: List<A11yNode>): List<A11yNode> {
        // A goal is not a field: the same needles are offered to both the text and the description
        // route so an icon-only row (empty text, named only by its description) stays reachable.
        val needles = needlesOf(goal)
        return candidates(ScreenTarget(byText = needles, byDescription = needles), nodes)
            .map { it.node }
    }

    /** Candidates for an explicit [target]; the route a needle came from is part of the ranking. */
    fun resolve(target: ScreenTarget, nodes: List<A11yNode>): List<A11yNode> =
        candidates(target, nodes).map { it.node }

    /** Ranked candidates with their match reason; empty when nothing resembles the target. */
    fun candidates(target: ScreenTarget, nodes: List<A11yNode>): List<ScreenCandidate> {
        if (target.isEmpty || nodes.isEmpty()) return emptyList()
        val scored = ArrayList<ScreenCandidate>()
        for (node in nodes) {
            // A redacted password row has no readable label and must never be offered as a hit.
            if (node.password || node.redacted) continue
            bestMatchFor(node, target)?.let(scored::add)
        }
        return scored
            .sortedWith(
                compareByDescending<ScreenCandidate> { it.kind.ordinal }
                    .thenByDescending { it.node.clickable || it.node.input }
                    .thenBy { it.route.ordinal }
                    .thenBy { it.node.depth }
                    .thenBy { it.node.index }
            )
            .take(MAX_CANDIDATES)
    }

    /**
     * The model-facing list of [candidates].
     *
     * Every line carries the capture index (which `tap` accepts as a pixel-free reference), the
     * label, the identifier, the pixel bounds and the match reason. Bounds are not decoration: they
     * are how the model decides between two identically-labelled rows, and how it can fall back to a
     * coordinate tap on the rectangle it picked. Returns `""` for an empty list so a caller can
     * concatenate it unconditionally.
     */
    fun describeCandidates(candidates: List<ScreenCandidate>): String {
        if (candidates.isEmpty()) return ""
        val builder = StringBuilder()
        candidates.forEachIndexed { position, candidate ->
            if (builder.isNotEmpty()) builder.append('\n')
            val node = candidate.node
            builder.append(position).append(". ")
                .append("index=").append(node.index).append(' ')
                .append("bounds=")
                .append(node.rect.x).append(',').append(node.rect.y)
                .append(' ').append(node.rect.w).append('x').append(node.rect.h)
            val label = labelOf(node)
            if (label.isNotEmpty()) builder.append(" text=\"").append(label).append('"')
            builder.append(" id=").append(node.cls.ifEmpty { "(none)" })
            val flags = buildList {
                if (node.clickable) add("tap")
                if (node.input) add("input")
                if (node.scrollable) add("scroll")
            }
            if (flags.isNotEmpty()) builder.append(" (").append(flags.joinToString(",")).append(')')
            builder.append(" match=").append(candidate.kind.name.lowercase())
                .append('/').append(candidate.route.name.lowercase())
        }
        return builder.toString()
    }

    /** Convenience: [describeCandidates] over [resolve], for the tool's "no exact match" result. */
    fun describeCandidatesOf(goal: String, nodes: List<A11yNode>): String {
        val needles = needlesOf(goal)
        return describeCandidates(candidates(ScreenTarget(byText = needles, byDescription = needles), nodes))
    }

    // ------------------------------------------------------------------ matching

    private fun bestMatchFor(node: A11yNode, target: ScreenTarget): ScreenCandidate? {
        var best: ScreenCandidate? = null
        for ((route, routeNeedles) in routesOf(target)) {
            val haystack = when (route) {
                ScreenMatchRoute.TEXT -> node.text.normalize()
                ScreenMatchRoute.DESCRIPTION -> node.desc.normalize()
                ScreenMatchRoute.ID -> node.cls.normalize()
            }
            if (haystack.isEmpty()) continue
            for (needle in routeNeedles) {
                val kind = kindOf(haystack, needle) ?: continue
                val current = best
                if (current == null || kind.ordinal > current.kind.ordinal) {
                    best = ScreenCandidate(node, kind, route, needle)
                }
            }
        }
        return best
    }

    private fun routesOf(target: ScreenTarget): List<Pair<ScreenMatchRoute, List<String>>> = listOf(
        ScreenMatchRoute.TEXT to target.byText,
        ScreenMatchRoute.DESCRIPTION to target.byDescription,
        ScreenMatchRoute.ID to target.byId
    )

    /** Compared label: text, falling back to the description so an icon-only row still has a name. */
    private fun labelOf(node: A11yNode): String = node.text.trim().ifEmpty { node.desc.trim() }

    /**
     * How [haystack] matches [needle], or `null`.
     *
     * Two grades only, deliberately. A third "fuzzy" grade was prototyped and removed: character
     * overlap cannot separate a genuine near-miss ("鐧婚檰" for "鐧诲綍") from an unrelated neighbour
     * ("鎼滃浘" vs "鎼滅储") 鈥?both measure identically 鈥?so any threshold that accepts the first also
     * accepts the second. Offering a wrong control is worse than offering none, so a miss returns
     * an empty list and the model is told to try a different word or a coordinate.
     *
     * Bidirectional containment is what makes a goal-shaped needle work, but it is also the rule
     * most able to produce nonsense, so the contained side must reach [MIN_CONTAINMENT_CHARS].
     */
    private fun kindOf(rawHaystack: String, rawNeedle: String): ScreenMatchKind? {
        val haystack = rawHaystack.normalize()
        val needle = rawNeedle.normalize()
        if (haystack.isEmpty() || needle.isEmpty()) return null
        if (haystack == needle) return ScreenMatchKind.EXACT
        val shorterIsLongEnough = minOf(haystack.length, needle.length) >= MIN_CONTAINMENT_CHARS
        if (shorterIsLongEnough && (haystack.contains(needle) || needle.contains(haystack))) {
            return ScreenMatchKind.CONTAINS
        }
        return null
    }

    // ------------------------------------------------------------------ needles

    /**
     * Splits a goal into searchable needles.
     *
     * A goal is a sentence, not a label: "甯垜鐐瑰紑銆屾悳绱€嶆寜閽? must yield "鎼滅储". The split is on
     * whitespace and the punctuation a chat model actually emits, and each piece is then also split
     * where a Latin word meets a CJK run, so "鎵撳紑WeChat" produces both "wechat" and the surrounding
     * runs. Pieces shorter than [MIN_NEEDLE_CHARS] are dropped.
     */
    internal fun needlesOf(goal: String): List<String> {
        val trimmed = goal.trim()
        if (trimmed.isEmpty()) return emptyList()
        val pieces = trimmed.split(*NEEDLE_SEPARATORS)
        val needles = LinkedHashSet<String>()
        for (piece in pieces) {
            for (part in splitByScript(piece)) {
                val normalizedPart = part.normalize()
                if (normalizedPart.length >= MIN_NEEDLE_CHARS) needles += normalizedPart
            }
        }
        // The whole goal is also a needle: a node whose label equals the entire request is the most
        // precise hit possible, and losing it to tokenisation would be surprising.
        val whole = trimmed.normalize()
        if (whole.length >= MIN_NEEDLE_CHARS && !whole.contains(' ')) needles += whole
        return needles.toList()
    }

    /**
     * Splits at Latin/CJK boundaries so "鎵撳紑WeChat" becomes "鎵撳紑" and "WeChat".
     *
     * The real needles are unchanged apart from the shared punctuation split; this only stops one
     * glued token from being unmatchable.
     */
    private fun splitByScript(piece: String): List<String> {
        if (piece.length < 2) return listOf(piece)
        val out = ArrayList<String>(2)
        val current = StringBuilder()
        var currentIsLatin: Boolean? = null
        for (character in piece) {
            val latin = character.isLatinOrDigit()
            if (currentIsLatin != null && latin != currentIsLatin) {
                out += current.toString()
                current.setLength(0)
            }
            currentIsLatin = latin
            current.append(character)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private fun Char.isLatinOrDigit(): Boolean =
        (this in 'a'..'z') || (this in 'A'..'Z') || (this in '0'..'9')

    /**
     * Characters a model uses to separate a label from the rest of its sentence.
     *
     * Written as escapes rather than literal CJK punctuation so this list survives a toolchain that
     * rewrites the file in another encoding: a corrupted `'，'` silently becomes a two-character
     * literal and the file stops compiling.
     */
    private val NEEDLE_SEPARATORS: CharArray = charArrayOf(
        ' ', '\t', '\n', '\r', ',', '.', ';', ':', '!', '?',
        '\uFF0C', '\u3002', '\u3001', '\uFF1B', '\uFF1A', '\uFF01', '\uFF1F',
        '\u300C', '\u300D', '\u300E', '\u300F',
        '\u201C', '\u201D', '\u2018', '\u2019',
        '(', ')', '\uFF08', '\uFF09',
        '[', ']', '\u3010', '\u3011',
        '/', '\\', '-', '_', '*', '#', '@', '\'', '"'
    )
}

/** Trim, lower-case, collapse inner whitespace; the one normalisation every comparison uses. */
internal fun String.normalize(): String {
    val builder = StringBuilder(length)
    var pendingSpace = false
    for (character in trim().lowercase()) {
        if (character.isWhitespace()) {
            pendingSpace = builder.isNotEmpty()
        } else {
            if (pendingSpace) builder.append(' ')
            pendingSpace = false
            builder.append(character)
        }
    }
    return builder.toString()
}
