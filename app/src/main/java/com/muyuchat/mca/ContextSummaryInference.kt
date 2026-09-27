package com.muyuchat.mca

import com.muyuchat.core.engine.GenerateEvent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.util.concurrent.CancellationException

internal suspend fun collectContextSummary(
    flow: Flow<GenerateEvent>,
    isOwnerCurrent: () -> Boolean
): String {
    suspend fun ensureCurrent() {
        currentCoroutineContext().ensureActive()
        if (!isOwnerCurrent()) throw CancellationException("Summary request owner or revision is stale.")
    }

    val body = StringBuilder()
    var terminal = false
    var completed = false
    var failure: Exception? = null
    ensureCurrent()
    // Drain after terminal events so the producer releases its runtime lease before the next request.
    flow.collect { event ->
        ensureCurrent()
        if (terminal) return@collect
        when (event) {
            is GenerateEvent.Chunk -> {
                if (event.text.length > MAX_SUMMARY_BODY_CHARS - body.length) {
                    terminal = true
                    failure = IllegalStateException("Summary output exceeds $MAX_SUMMARY_BODY_CHARS characters.")
                } else body.append(event.text)
            }
            is GenerateEvent.Done -> {
                terminal = true
                completed = event.toolCalls.isEmpty()
                if (!completed) failure = IllegalStateException("Summary inference returned unsupported tool calls.")
            }
            is GenerateEvent.Error -> {
                terminal = true
                val code = event.code?.let { " [$it]" }.orEmpty()
                failure = if (event.code?.uppercase() in setOf("REQUEST_CANCELLED", "CANCELLED", "CANCELED")) {
                    CancellationException(event.message)
                } else IllegalStateException("Summary inference failed$code: ${event.message}")
            }
            is GenerateEvent.Phase, is GenerateEvent.Persist -> Unit
        }
    }
    ensureCurrent()
    failure?.let { throw it }
    check(completed) { "Summary inference ended without a successful Done event." }
    return body.toString()
}

private const val MAX_SUMMARY_BODY_CHARS = 65_536
