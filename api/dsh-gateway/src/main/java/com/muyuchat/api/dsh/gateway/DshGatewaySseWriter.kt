package com.muyuchat.api.dsh.gateway

import java.net.Socket
/** SSE transport for one streaming call. All frames and heartbeats share one lock. */
internal class DshGatewaySseWriter(private val socket: Socket) {
    private val output = socket.getOutputStream()
    private val lock = Any()

    @Volatile
    private var closed = false

    private var completionId: String = ""
    private var created: Long = 0L
    private var model: String = ""

    fun start(completionId: String, created: Long, model: String, callId: String) {
        synchronized(lock) {
            if (closed) return
            val header = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/event-stream; charset=utf-8\r\n")
                append("Cache-Control: no-cache, no-store\r\n")
                append("X-Accel-Buffering: no\r\n")
                append("X-MCA-Call-Id: ")
                append(callId.filter { it.isLetterOrDigit() || it == ':' || it == '-' || it == '_' })
                append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(header.toByteArray(Charsets.ISO_8859_1))
            this.completionId = completionId
            this.created = created
            this.model = model
            writeFrameUnlocked(DshOpenAiWire.roleChunk(completionId, created, model))
            output.flush()
        }
    }

    fun frame(json: String) {
        synchronized(lock) {
            if (closed) return
            writeFrameUnlocked(json)
            output.flush()
        }
    }

    /**
     * Idle keep-alive that also satisfies OpenAI-compatible stream watchdogs.
     *
     * A bare SSE comment is ignored by the official pi-ai client's 300 s idle timer; an empty
     * `delta: {}` chunk is a valid activity event and does not alter the transcript.
     */
    fun idleHeartbeat(comment: String) {
        synchronized(lock) {
            if (closed) return
            writeFrameUnlocked(DshOpenAiWire.emptyDeltaChunk(completionId, created, model))
            output.write(": $comment\n\n".toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    fun comment(text: String) {
        synchronized(lock) {
            if (closed) return
            output.write(": $text\n\n".toByteArray(Charsets.UTF_8))
            output.flush()
        }
    }

    fun done() {
        synchronized(lock) {
            if (closed) return
            output.write(DshOpenAiWire.DONE_FRAME.toByteArray(Charsets.UTF_8))
            output.flush()
            closed = true
        }
    }

    fun closeQuietly() {
        synchronized(lock) {
            closed = true
            runCatching { socket.shutdownOutput() }
        }
    }

    private fun writeFrameUnlocked(json: String) {
        output.write("data: ".toByteArray(Charsets.UTF_8))
        output.write(json.toByteArray(Charsets.UTF_8))
        output.write("\n\n".toByteArray(Charsets.UTF_8))
    }
}
