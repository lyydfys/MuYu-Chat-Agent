package com.muyuchat.core.engine

internal data class ReasoningFilterOutput(
    val visible: String = "",
    val reasoning: String = ""
)

/**
 * Splits model output into visible content and reasoning_content.
 *
 * The main path follows llama-server style structured markers such as
 * <think>...</think> and channel thought/analysis blocks. A narrow fallback also
 * handles models that start the whole answer with a plain "Thinking Process:"
 * style heading, but only before any visible answer text has been emitted.
 */
internal class ReasoningContentFilter {
    private var insideReasoning = false
    private var insidePlainReasoning = false
    private var buffer = ""
    private var trimNextVisiblePrefix = false
    private var emittedVisibleText = false

    fun filter(chunk: String): ReasoningFilterOutput {
        if (chunk.isEmpty()) return ReasoningFilterOutput()
        buffer += chunk
        val visible = StringBuilder()
        val reasoning = StringBuilder()

        while (buffer.isNotEmpty()) {
            if (insideReasoning || insidePlainReasoning) {
                if (insidePlainReasoning) {
                    val finalMarker = findAnyMarker(buffer, PLAIN_FINAL_MARKERS)
                    if (finalMarker != null) {
                        reasoning.append(buffer.substring(0, finalMarker.start))
                        buffer = buffer.substring(finalMarker.end)
                        insidePlainReasoning = false
                        trimNextVisiblePrefix = true
                        continue
                    }
                    keepPlainReasoningTail(reasoning)
                    break
                }
                val closeMarker = findAnyMarker(buffer, REASONING_CLOSE_MARKERS)
                if (closeMarker != null) {
                    reasoning.append(buffer.substring(0, closeMarker.start))
                    buffer = buffer.substring(closeMarker.end)
                    insideReasoning = false
                    trimNextVisiblePrefix = true
                    continue
                }
                keepReasoningTail(reasoning)
                break
            }

            val openMarker = findAnyMarker(buffer, REASONING_OPEN_MARKERS)
            val plainMarker = findLeadingPlainReasoningMarker(buffer)
            if (plainMarker != null && (openMarker == null || plainMarker.start <= openMarker.start)) {
                appendVisible(visible, buffer.substring(0, plainMarker.start))
                buffer = buffer.substring(plainMarker.end)
                insidePlainReasoning = true
                continue
            }
            if (openMarker != null) {
                appendVisible(visible, buffer.substring(0, openMarker.start))
                buffer = buffer.substring(openMarker.end)
                insideReasoning = true
                continue
            }
            keepVisibleTail(visible)
            break
        }

        return ReasoningFilterOutput(
            visible = visible.toString(),
            reasoning = reasoning.toString().dropChatChannelMarkup()
        )
    }

    fun finish(): ReasoningFilterOutput {
        val visible = StringBuilder()
        val reasoning = StringBuilder()
        if (insideReasoning || insidePlainReasoning) {
            reasoning.append(buffer)
        } else {
            appendVisible(visible, buffer)
        }
        buffer = ""
        insideReasoning = false
        insidePlainReasoning = false
        return ReasoningFilterOutput(
            visible = visible.toString(),
            reasoning = reasoning.toString().dropChatChannelMarkup()
        )
    }

    private fun keepReasoningTail(reasoning: StringBuilder) {
        val keep = REASONING_CLOSE_MARKERS.maxOf { it.length } - 1
        if (buffer.length > keep) {
            reasoning.append(buffer.dropLast(keep))
            buffer = buffer.takeLast(keep)
        }
    }

    private fun keepVisibleTail(visible: StringBuilder) {
        val keep = maxOf(
            REASONING_OPEN_MARKERS.maxOf { it.length } - 1,
            PLAIN_REASONING_LOOKAHEAD
        )
        if (buffer.length > keep) {
            appendVisible(visible, buffer.dropLast(keep))
            buffer = buffer.takeLast(keep)
        }
    }

    private fun keepPlainReasoningTail(reasoning: StringBuilder) {
        if (buffer.length > PLAIN_FINAL_MARKER_LOOKAHEAD) {
            reasoning.append(buffer.dropLast(PLAIN_FINAL_MARKER_LOOKAHEAD))
            buffer = buffer.takeLast(PLAIN_FINAL_MARKER_LOOKAHEAD)
        }
    }

    private fun appendVisible(output: StringBuilder, text: String) {
        val visible = if (trimNextVisiblePrefix) {
            text.trimStart(' ', '\n', '\r', '\t')
        } else {
            text
        }
        if (visible.isNotEmpty()) {
            trimNextVisiblePrefix = false
            output.append(visible.dropChatChannelMarkup())
            if (visible.any { !it.isWhitespace() }) emittedVisibleText = true
        }
    }

    private fun findAnyMarker(text: String, markers: List<String>): MarkerRange? {
        val lower = text.lowercase()
        return markers
            .mapNotNull { marker ->
                val index = lower.indexOf(marker.lowercase())
                if (index >= 0) MarkerRange(index, index + marker.length) else null
            }
            .minByOrNull { it.start }
    }

    private fun findLeadingPlainReasoningMarker(text: String): MarkerRange? {
        if (emittedVisibleText) return null
        val firstContent = text.indexOfFirst { !it.isWhitespace() }
        if (firstContent < 0) return null
        val lower = text.lowercase()
        return PLAIN_REASONING_MARKERS
            .mapNotNull { marker ->
                val index = lower.indexOf(marker.lowercase())
                if (index == firstContent) MarkerRange(index, index + marker.length) else null
            }
            .minByOrNull { it.start }
    }

    private fun String.dropChatChannelMarkup(): String {
        var cleaned = this
        CHANNEL_MARKUP_TO_DROP.forEach { marker ->
            cleaned = cleaned.replace(marker, "", ignoreCase = true)
        }
        return cleaned
    }

    private data class MarkerRange(val start: Int, val end: Int)

    private companion object {
        const val PLAIN_REASONING_LOOKAHEAD = 96
        const val PLAIN_FINAL_MARKER_LOOKAHEAD = 64

        val REASONING_OPEN_MARKERS = listOf(
            "<think>",
            "<|think|>",
            "<|channel>thought",
            "<|channel|>thought",
            "<|channel>analysis",
            "<|channel|>analysis"
        )

        val REASONING_CLOSE_MARKERS = listOf(
            "</think>",
            "<channel|>",
            "<|channel>final",
            "<|channel|>final",
            "<|channel>response",
            "<|channel|>response",
            "<|channel>assistant",
            "<|channel|>assistant",
            "<channel>"
        )

        val PLAIN_REASONING_MARKERS = listOf(
            "thinking process:",
            "thinking process：",
            "thinking process",
            "thought process:",
            "thought process：",
            "reasoning process:",
            "reasoning process：",
            "analysis:",
            "analysis：",
            "思考过程：",
            "思考过程:",
            "推理过程：",
            "推理过程:",
            "分析过程：",
            "分析过程:"
        )

        val PLAIN_FINAL_MARKERS = listOf(
            "final answer:",
            "final answer：",
            "final response:",
            "final response：",
            "answer:",
            "answer：",
            "最终答案：",
            "最终答案:",
            "最终回答：",
            "最终回答:",
            "答案：",
            "答案:"
        )

        val CHANNEL_MARKUP_TO_DROP = listOf(
            "<|channel>final",
            "<|channel|>final",
            "<|channel>response",
            "<|channel|>response",
            "<|channel>assistant",
            "<|channel|>assistant",
            "<|channel>thought",
            "<|channel|>thought",
            "<|channel>analysis",
            "<|channel|>analysis",
            "<|message|>",
            "<|end|>",
            "<|start|>",
            "<|think|>",
            "<think>",
            "</think>",
            "<channel|>",
            "<channel>"
        )
    }
}
