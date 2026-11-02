package com.muyuchat.mca.agent.extensions.mcp

/**
 * Incremental `text/event-stream` framing for MCP Streamable HTTP.
 *
 * Only complete `data` events are emitted; comment/`event:`/`id:`/`retry:` lines are ignored, but
 * their bytes still count toward the caps. A network chunk is not a protocol message boundary, so
 * the parser keeps its state across [feed] calls. No event is dispatched before its terminating
 * blank line; an event that exceeds the per-message or total budget fails closed. A stream that
 * ends without the terminating blank line discards the pending event (SSE spec), and [finish]
 * reports truncation so the caller never mistakes a half frame for a message.
 */
class McpSseParser(
    private val maxEventBytes: Int = McpProtocol.MAX_MESSAGE_BYTES,
    private val maxTotalBytes: Int = McpProtocol.MAX_TOOL_LIST_BYTES
) {
    private val data = StringBuilder()
    private val line = StringBuilder()
    private var totalBytes = 0L
    private var pendingCarriageReturn = false
    private var failed: McpFailure? = null

    /** Feeds one decoded chunk; [onEvent] receives complete data payloads in order. */
    fun feed(chunk: String, onEvent: (String) -> Unit) {
        if (failed != null) return
        totalBytes += chunk.toByteArray(Charsets.UTF_8).size.toLong()
        if (totalBytes > maxTotalBytes) {
            failed = McpFailure.SizeLimit("mcp_sse_total_too_large")
            return
        }
        var index = 0
        while (index < chunk.length) {
            val ch = chunk[index]
            if (pendingCarriageReturn) {
                pendingCarriageReturn = false
                if (ch == '\n') {
                    index += 1
                    continue
                }
            }
            if (ch == '\r') {
                consumeLine(onEvent)
                if (failed != null) return
                pendingCarriageReturn = true
                index += 1
            } else if (ch == '\n') {
                consumeLine(onEvent)
                if (failed != null) return
                index += 1
            } else {
                line.append(ch)
                if (line.length > maxEventBytes) {
                    failed = McpFailure.SizeLimit("mcp_sse_event_too_large")
                    return
                }
                index += 1
            }
        }
    }

    /** Called once after the last chunk. Pending bytes are discarded and truncation is reported. */
    fun finish(): McpFailure? {
        failed?.let { return it }
        val truncated = data.isNotEmpty() || line.isNotEmpty() || pendingCarriageReturn
        data.clear()
        line.clear()
        pendingCarriageReturn = false
        return if (truncated) McpFailure.Protocol("mcp_sse_truncated") else null
    }

    private fun consumeLine(onEvent: (String) -> Unit) {
        val current = line.toString()
        line.clear()
        if (current.isEmpty()) {
            if (data.isNotEmpty()) {
                val payload = data.toString()
                data.clear()
                if (payload.toByteArray(Charsets.UTF_8).size > maxEventBytes) {
                    failed = McpFailure.SizeLimit("mcp_sse_event_too_large")
                    return
                }
                onEvent(payload)
            }
            return
        }
        if (current[0] == ':') return // comment
        val colon = current.indexOf(':')
        val field = if (colon < 0) current else current.substring(0, colon)
        if (field != "data") return
        var value = if (colon < 0) "" else current.substring(colon + 1)
        if (value.startsWith(' ')) value = value.substring(1)
        if (data.isNotEmpty()) data.append('\n')
        data.append(value)
        if (data.length > maxEventBytes) {
            failed = McpFailure.SizeLimit("mcp_sse_event_too_large")
        }
    }
}
