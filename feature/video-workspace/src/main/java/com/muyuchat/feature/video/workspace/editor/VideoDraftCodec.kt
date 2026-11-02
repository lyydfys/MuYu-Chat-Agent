package com.muyuchat.feature.video.workspace.editor

import com.muyuchat.core.video.contract.VideoEdge
import com.muyuchat.core.video.contract.VideoNode
import com.muyuchat.core.video.contract.VideoNodeUi
import com.muyuchat.core.video.contract.VideoNodeValue
import com.muyuchat.core.video.contract.VideoRevisionId
import com.muyuchat.core.video.contract.VideoWorkflowDocument
import com.muyuchat.core.video.contract.VideoWorkflowId
import com.muyuchat.core.video.contract.VideoWorkflowSource

/**
 * Compact draft persistence for saved-instance state (proposal §9.4.1: draft survives rotation
 * and process death locally). This is NOT a durable interchange format: the executable digest
 * always comes from the shared canonical form, and the durable revision store (V1-02a) keeps
 * full documents. Round-trip fidelity — including UI positions — is pinned by unit tests.
 *
 * Line-oriented, versioned; `|` separates fields, `~` separates records, and `%xx` escapes
 * `%`, `|`, `~`, newline and carriage return inside free text.
 */
object VideoDraftCodec {

    private const val FORMAT = "draft1"
    private const val FIELD = '|'
    private const val RECORD = '~'

    fun encode(document: VideoWorkflowDocument): String = buildString {
        append(FORMAT)
        appendField(document.workflowId.value)
        appendField(document.revisionId.value)
        appendField(document.source.name)
        for (node in document.nodes) {
            appendRecord()
            append('N')
            appendField(node.nodeId)
            appendField(node.typeId)
            appendField(node.typeVersion.toString())
            appendField(node.params.size.toString())
            for ((name, value) in node.params) {
                appendField("P")
                appendField(name)
                appendField(valueTag(value))
                appendField(valueText(value))
            }
            appendField("U")
            appendField(node.ui.positionX?.toString() ?: "")
            appendField(node.ui.positionY?.toString() ?: "")
            appendField(if (node.ui.collapsed) "1" else "0")
            appendField(node.ui.label?.let(::escape) ?: "")
        }
        for (edge in document.edges) {
            appendRecord()
            append('E')
            appendField(edge.fromNodeId)
            appendField(edge.fromPort)
            appendField(edge.toNodeId)
            appendField(edge.toPort)
        }
        document.rawSourceRef?.let { raw ->
            appendRecord()
            append('R')
            appendField(escape(raw))
        }
        for ((key, value) in document.metadata) {
            appendRecord()
            append('M')
            appendField(escape(key))
            appendField(escape(value))
        }
    }

    fun decode(encoded: String): VideoWorkflowDocument? {
        if (!encoded.startsWith(FORMAT)) return null
        val records = encoded.substring(FORMAT.length).split(RECORD)
        // Header record: <empty-before-first-field>|workflowId|revisionId|source
        val header = records.firstOrNull()?.split(FIELD) ?: return null
        if (header.size < 4) return null
        val workflowId = runCatching { VideoWorkflowId(unescape(header[1])) }.getOrNull() ?: return null
        val revisionId = runCatching { VideoRevisionId(unescape(header[2])) }.getOrNull() ?: return null
        val source = runCatching { VideoWorkflowSource.valueOf(header[3]) }.getOrDefault(VideoWorkflowSource.MCA_NATIVE)

        val nodes = mutableListOf<VideoNode>()
        val edges = mutableListOf<VideoEdge>()
        val metadata = LinkedHashMap<String, String>()
        var rawSourceRef: String? = null
        for (record in records.drop(1)) {
            when (record.firstOrNull()) {
                'N' -> decodeNode(record.removePrefix("N|"))?.let(nodes::add) ?: return null
                'E' -> decodeEdge(record.removePrefix("E|"))?.let(edges::add) ?: return null
                'R' -> rawSourceRef = unescape(record.removePrefix("R|"))
                'M' -> {
                    val fields = record.removePrefix("M|").split(FIELD)
                    if (fields.size != 2) return null
                    metadata[unescape(fields[0])] = unescape(fields[1])
                }
                else -> return null
            }
        }
        return VideoWorkflowDocument(
            workflowId = workflowId,
            revisionId = revisionId,
            source = source,
            nodes = nodes,
            edges = edges,
            metadata = metadata,
            rawSourceRef = rawSourceRef,
        )
    }

    private fun decodeNode(body: String): VideoNode? {
        val fields = body.split(FIELD)
        if (fields.size < 4) return null
        val nodeId = unescape(fields[0])
        val typeId = unescape(fields[1])
        val typeVersion = fields[2].toIntOrNull() ?: return null
        val paramCount = fields[3].toIntOrNull() ?: return null

        // Field layout after the header: [P, name, tag, text] * paramCount, then
        // [U, posX, posY, collapsedFlag, label].
        var cursor = 4
        val params = LinkedHashMap<String, VideoNodeValue>()
        repeat(paramCount) {
            if (cursor + 2 > fields.size - 1) return null
            if (fields[cursor] != "P") return null
            val name = fields[cursor + 1]
            val value = parseValue(fields[cursor + 2], fields[cursor + 3]) ?: return null
            params[name] = value
            cursor += 4
        }
        if (cursor + 4 > fields.size - 1) return null
        if (fields[cursor] != "U") return null
        val posX = fields[cursor + 1].takeIf { it.isNotEmpty() }?.toFloatOrNull()
        val posY = fields[cursor + 2].takeIf { it.isNotEmpty() }?.toFloatOrNull()
        val collapsed = fields[cursor + 3] == "1"
        val label = fields[cursor + 4].takeIf { it.isNotEmpty() }?.let(::unescape)
        return VideoNode(
            nodeId = nodeId,
            typeId = typeId,
            typeVersion = typeVersion,
            params = params,
            ui = VideoNodeUi(posX, posY, collapsed, label),
        )
    }

    private fun decodeEdge(body: String): VideoEdge? {
        val fields = body.split(FIELD)
        if (fields.size < 4) return null
        return runCatching {
            VideoEdge(unescape(fields[0]), unescape(fields[1]), unescape(fields[2]), unescape(fields[3]))
        }.getOrNull()
    }

    private fun valueTag(value: VideoNodeValue): String = when (value) {
        is VideoNodeValue.TextValue -> "t"
        is VideoNodeValue.LongValue -> "l"
        is VideoNodeValue.DecimalValue -> "d"
        is VideoNodeValue.ImageAssetRefValue -> "i"
        is VideoNodeValue.ModelRefValue -> "m"
    }

    private fun valueText(value: VideoNodeValue): String = when (value) {
        is VideoNodeValue.TextValue -> escape(value.text)
        is VideoNodeValue.LongValue -> value.value.toString()
        is VideoNodeValue.DecimalValue -> "${value.unscaled}:${value.scale}"
        is VideoNodeValue.ImageAssetRefValue -> escape(value.assetId)
        is VideoNodeValue.ModelRefValue -> escape(value.modelId)
    }

    private fun parseValue(tag: String, text: String): VideoNodeValue? = when (tag) {
        "t" -> VideoNodeValue.TextValue(unescape(text))
        "l" -> VideoNodeValue.LongValue(text.toLongOrNull() ?: return null)
        "d" -> {
            val parts = text.split(':')
            if (parts.size != 2) null
            else VideoNodeValue.DecimalValue(parts[0].toLongOrNull() ?: return null, parts[1].toIntOrNull() ?: return null)
        }

        "i" -> VideoNodeValue.ImageAssetRefValue(unescape(text))
        "m" -> VideoNodeValue.ModelRefValue(unescape(text))
        else -> null
    }

    private fun StringBuilder.appendField(value: String) {
        append(FIELD)
        append(value)
    }

    private fun StringBuilder.appendRecord() {
        append(RECORD)
    }

    private fun escape(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '%', FIELD, RECORD -> {
                    append('%')
                    append(ch.code.toString(16).padStart(2, '0'))
                }

                '\n' -> append("%0a")
                '\r' -> append("%0d")
                else -> append(ch)
            }
        }
    }

    private fun unescape(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '%' && i + 3 <= text.length + 0) {
                val hex = text.substring(i + 1, i + 3)
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    i += 3
                    continue
                }
            }
            out.append(ch)
            i += 1
        }
        return out.toString()
    }
}
