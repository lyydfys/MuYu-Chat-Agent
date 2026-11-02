package com.muyuchat.core.video.contract

import java.security.MessageDigest

/**
 * Canonical serialization of a workflow's executable graph (proposal §4.1 rule 3).
 *
 * The digest is computed over the CANONICAL form of the typed document — never over raw user
 * JSON or an imported file. Canonicalization rules, frozen for schema version 1:
 * - object keys sorted lexicographically,
 * - node array sorted by `nodeId`, edge array sorted by the 4-tuple
 *   (`fromNodeId`,`fromPort`,`toNodeId`,`toPort`),
 * - integers rendered plain, decimals rendered from (unscaled, scale) with trailing zeros
 *   stripped and a bare `0` for zero,
 * - strings escaped with the minimal fixed escape set below; non-ASCII kept as UTF-8,
 * - excluded from the executable form: [VideoNodeUi] (position/collapsed/label), document
 *   `metadata`, `rawSourceRef` and any previously stored digest.
 *
 * Params are serialized exactly as stored: a document that omits an optional param and one that
 * stores an equivalent value are DIFFERENT revisions. No silent default-equivalence — runs bind
 * the stored snapshot (§4.1 rule 4/5).
 *
 * Parsing canonical JSON back into a document is a repository concern (app side); this object
 * only writes and digests, so the contract stays free of any JSON library.
 */
object VideoWorkflowCanon {

    fun canonicalExecutableJson(document: VideoWorkflowDocument): String = buildString {
        append("{\"edges\":[")
        document.edges
            .sortedWith(
                compareBy({ it.fromNodeId }, { it.fromPort }, { it.toNodeId }, { it.toPort }),
            )
            .forEachIndexed { index, edge ->
                if (index > 0) append(',')
                appendEdge(edge)
            }
        append("],\"nodes\":[")
        document.nodes.sortedBy { it.nodeId }.forEachIndexed { index, node ->
            if (index > 0) append(',')
            appendNode(node)
        }
        append("],\"schemaVersion\":")
        append(document.schemaVersion)
        append(",\"source\":\"")
        appendEscaped(document.source.name)
        append("\"}")
    }

    fun canonicalExecutableSha256(document: VideoWorkflowDocument): String {
        val canonical = canonicalExecutableJson(document)
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    /** Canonical text form of one parameter value, shared by the node writer and snapshots. */
    fun canonicalValueText(value: VideoNodeValue): String = buildString { appendValue(value) }

    private fun StringBuilder.appendNode(node: VideoNode) {
        append("{\"nodeId\":\"")
        appendEscaped(node.nodeId)
        append("\",\"params\":{")
        node.params.entries
            .sortedWith(compareBy({ it.key }, { canonicalValueText(it.value) }))
            .forEachIndexed { index, (name, value) ->
                if (index > 0) append(',')
                append('"')
                appendEscaped(name)
                append("\":")
                appendValue(value)
            }
        append("},\"typeId\":\"")
        appendEscaped(node.typeId)
        append("\",\"typeVersion\":")
        append(node.typeVersion)
        append('}')
    }

    private fun StringBuilder.appendEdge(edge: VideoEdge) {
        append("{\"fromNodeId\":\"")
        appendEscaped(edge.fromNodeId)
        append("\",\"fromPort\":\"")
        appendEscaped(edge.fromPort)
        append("\",\"toNodeId\":\"")
        appendEscaped(edge.toNodeId)
        append("\",\"toPort\":\"")
        appendEscaped(edge.toPort)
        append("\"}")
    }

    private fun StringBuilder.appendValue(value: VideoNodeValue) {
        when (value) {
            is VideoNodeValue.TextValue -> {
                append('"')
                appendEscaped(value.text)
                append('"')
            }

            is VideoNodeValue.LongValue -> append(value.value)

            is VideoNodeValue.DecimalValue -> appendCanonicalDecimal(value.unscaled, value.scale)

            is VideoNodeValue.ImageAssetRefValue -> {
                append("{\"assetId\":\"")
                appendEscaped(value.assetId)
                append("\"}")
            }

            is VideoNodeValue.ModelRefValue -> {
                append("{\"modelId\":\"")
                appendEscaped(value.modelId)
                append("\"}")
            }
        }
    }

    /** Renders (unscaled, scale) without scientific notation and without trailing fraction zeros. */
    internal fun StringBuilder.appendCanonicalDecimal(unscaled: Long, scale: Int) {
        require(scale in 0..9) { "decimal scale out of range: $scale" }
        if (scale == 0 || unscaled == 0L) {
            append(if (unscaled == 0L) "0" else unscaled.toString())
            return
        }
        val negative = unscaled < 0
        val digits = (if (negative) -unscaled else unscaled).toString()
        if (digits.length <= scale) {
            append(if (negative) "-0." else "0.")
            repeat(scale - digits.length) { append('0') }
            append(digits.trimEnd('0').ifEmpty { "0" })
            return
        }
        if (negative) append('-')
        val intPart = digits.substring(0, digits.length - scale)
        val fracPart = digits.substring(digits.length - scale).trimEnd('0')
        append(intPart)
        if (fracPart.isNotEmpty()) {
            append('.')
            append(fracPart)
        }
    }

    /** Minimal fixed escape set; every other code unit passes through as UTF-8. */
    private fun StringBuilder.appendEscaped(text: String) {
        for (ch in text) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> {
                    if (ch < ' ') {
                        append("\\u")
                        append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
    }
}
