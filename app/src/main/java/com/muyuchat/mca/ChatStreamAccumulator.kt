package com.muyuchat.mca

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Publish the accumulated stream text once it reaches this many characters. */
internal const val CHAT_STREAM_PUBLISH_CHARS = 2 * 1024

/** ...or once this long has passed since the previous publish, whichever comes first. */
internal const val CHAT_STREAM_PUBLISH_INTERVAL_MS = 75L

/** One drained slice of streamed assistant output, ready to be appended to the message. */
internal data class ChatStreamOutputBatch(
    val content: String,
    val reasoning: String,
    val reasoningDurationMs: Long
)

/**
 * Owns the streamed assistant output between network deltas and the message it eventually lands in.
 *
 * Everything the buffering needs is here and nowhere else: the lock, the two text builders, the
 * reasoning-duration high-water mark, the generation id the buffer currently belongs to, the last
 * publish timestamp and the deferred publish job. Previously all seven lived in `MainViewModel`
 * alongside chat, image, model and API state, so "who may touch the buffer, and under which lock"
 * could only be answered by reading the whole class.
 *
 * The accumulator deliberately does **not** know about `MainUiState`. It hands a drained
 * [ChatStreamOutputBatch] to [commit], which is the ViewModel's job, and it cannot append a drained
 * batch to a newer assistant message because [commit] re-checks [isStillCurrent] *inside* the state
 * update -- the same atomicity the inline version had.
 *
 * Nothing here is exposed: callers get [append], [flush] and [discard] and cannot reach the lock,
 * the buffers or the publish job.
 */
internal class ChatStreamAccumulator(
    private val scope: CoroutineScope,
    private val currentRunId: () -> Long,
    private val activeSessionId: () -> String?,
    private val hasAssistantMessage: () -> Boolean,
    private val commit: (batch: ChatStreamOutputBatch, isStillCurrent: () -> Boolean) -> Unit,
    /**
     * Monotonic milliseconds. Defaults to the call the inline version made, so behaviour is
     * unchanged; it is a parameter only so the publish-interval rule can be tested on the JVM.
     */
    private val now: () -> Long = { SystemClock.elapsedRealtime() }
) {

    private val lock = Any()
    private val pendingOutput = StringBuilder()
    private val pendingReasoning = StringBuilder()
    private var pendingReasoningDurationMs = 0L
    private var bufferGenerationId: Long? = null
    private var lastPublishedAtMs = 0L
    private var publishJob: Job? = null

    /**
     * Appends a delta and publishes it if the buffer is full, the interval elapsed, or the caller
     * asked to force it. Otherwise a deferred publish is scheduled.
     */
    fun append(
        delta: String,
        reasoningDelta: String = "",
        reasoningDurationMs: Long = 0L,
        forcePublish: Boolean = false,
        generationRunId: Long? = null
    ) {
        val now = now()
        val shouldPublish = synchronized(lock) {
            // Re-check while holding the buffer lock. A cancelled producer can
            // race a new send between the caller's check and this append.
            if (generationRunId != null && currentRunId() != generationRunId) {
                return@synchronized false
            }
            if (generationRunId != null &&
                bufferGenerationId != null &&
                bufferGenerationId != generationRunId
            ) {
                publishJob?.cancel()
                publishJob = null
                pendingOutput.setLength(0)
                pendingReasoning.setLength(0)
                pendingReasoningDurationMs = 0L
            }
            if (generationRunId != null) {
                bufferGenerationId = generationRunId
            }
            pendingOutput.append(delta)
            pendingReasoning.append(reasoningDelta)
            pendingReasoningDurationMs = maxOf(
                pendingReasoningDurationMs,
                reasoningDurationMs
            )
            forcePublish ||
                pendingOutput.length + pendingReasoning.length >= CHAT_STREAM_PUBLISH_CHARS ||
                now - lastPublishedAtMs >= CHAT_STREAM_PUBLISH_INTERVAL_MS
        }
        if (shouldPublish) {
            flush(generationRunId)
        } else {
            schedulePublication(generationRunId ?: currentRunId())
        }
    }

    private fun schedulePublication(generationRunId: Long) {
        val sessionId = activeSessionId()
        lateinit var scheduled: Job
        val next = synchronized(lock) {
            if (currentRunId() != generationRunId || publishJob != null ||
                (pendingOutput.isEmpty() && pendingReasoning.isEmpty() &&
                    pendingReasoningDurationMs <= 0L)
            ) return@synchronized null
            scheduled = scope.launch(start = CoroutineStart.LAZY) {
                delay(CHAT_STREAM_PUBLISH_INTERVAL_MS)
                val ownsPublication = synchronized(lock) {
                    if (publishJob !== scheduled) false else {
                        publishJob = null
                        currentRunId() == generationRunId &&
                            activeSessionId() == sessionId
                    }
                }
                if (ownsPublication) flush(generationRunId)
            }
            publishJob = scheduled
            scheduled
        }
        next?.start()
    }

    /** Publishes accumulated stream text once, preserving message metadata. */
    fun flush(generationRunId: Long? = null): Boolean {
        val pending = synchronized(lock) {
            if (generationRunId != null && currentRunId() != generationRunId) {
                null
            } else if (generationRunId != null &&
                bufferGenerationId != null &&
                bufferGenerationId != generationRunId
            ) {
                null
            } else if (pendingOutput.isEmpty() && pendingReasoning.isEmpty() &&
                pendingReasoningDurationMs <= 0L
            ) {
                null
            } else {
                ChatStreamOutputBatch(
                    content = pendingOutput.toString(),
                    reasoning = pendingReasoning.toString(),
                    reasoningDurationMs = pendingReasoningDurationMs
                ).also {
                    publishJob?.cancel()
                    publishJob = null
                    pendingOutput.setLength(0)
                    pendingReasoning.setLength(0)
                    pendingReasoningDurationMs = 0L
                    bufferGenerationId = null
                    lastPublishedAtMs = now()
                }
            }
        } ?: return false
        // Do not append a drained batch to a newer assistant message if the
        // generation changed while the buffer was being published.
        if (generationRunId != null && currentRunId() != generationRunId) {
            return false
        }
        if (!hasAssistantMessage()) return false
        commit(pending) { generationRunId == null || currentRunId() == generationRunId }
        return true
    }

    /** Drops whatever is buffered for [generationRunId], cancelling any pending publish. */
    fun discard(generationRunId: Long? = null) {
        if (generationRunId != null && currentRunId() != generationRunId) return
        synchronized(lock) {
            if (generationRunId != null && currentRunId() != generationRunId) {
                return@synchronized
            }
            pendingOutput.setLength(0)
            pendingReasoning.setLength(0)
            pendingReasoningDurationMs = 0L
            bufferGenerationId = null
            lastPublishedAtMs = 0L
            publishJob?.cancel()
            publishJob = null
        }
    }
}
