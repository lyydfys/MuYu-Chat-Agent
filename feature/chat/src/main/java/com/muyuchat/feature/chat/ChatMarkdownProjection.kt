package com.muyuchat.feature.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * R01: bounded background projection for long Markdown replies.
 *
 * The renderer only keeps a cheap synchronous fast path; this file owns the scheduling / commit
 * contract for everything above [MARKDOWN_SYNC_PARSE_BUDGET_CHARS]. It has no Compose dependency so
 * the real dispatch and commit rules can be driven directly by `runTest` (see
 * `ChatMarkdownProjectionTest`).
 *
 * Owner model:
 * - A [MarkdownProjectionRequest] is scoped to one displayed message identity plus one page range.
 * - A controller keeps at most the last committed result and the newest pending request. A burst of
 *   streaming updates replaces the single pending slot instead of queueing one task per token.
 * - A result is committed only while it still matches the pending request (identity + version).
 *   Cancelling the old coroutine is not used as a substitute for that check, because a CPU scan
 *   already started on `Dispatchers.Default` may finish after it was superseded.
 */
internal const val MARKDOWN_SYNC_PARSE_BUDGET_CHARS = 2_048

/**
 * The scheduling threshold, not a message length limit: the fast path is only taken when both the
 * displayed page and the complete source are inside the budget, because the parser walks both (the
 * complete source supplies the copy payload of unfinished code blocks).
 */
internal fun markdownProjectionNeedsBackground(
    displayContent: String,
    fullContent: String
): Boolean =
    displayContent.length > MARKDOWN_SYNC_PARSE_BUDGET_CHARS ||
        fullContent.length > MARKDOWN_SYNC_PARSE_BUDGET_CHARS

/**
 * Cheap content revision used to compare a request with its result. It is a pre-check only: the
 * full strings are still part of [MarkdownProjectionRequest.matches] so a hash collision cannot
 * commit the wrong payload.
 */
internal fun markdownContentRevision(content: String): Long =
    (content.length.toLong() shl 32) xor (content.hashCode().toLong() and 0xFFFF_FFFFL)

/**
 * One parse request for one displayed owner and page range.
 *
 * [visibleCharacters] is the page budget that produced [displayContent]; [contentLength] is the
 * length of the complete message. Both [displayRevision] and [fullRevision] are carried so a commit
 * can verify the displayed page and the complete copy source independently.
 */
internal data class MarkdownProjectionRequest(
    val owner: ChatMessageIdentity?,
    val visibleCharacters: Int,
    val contentLength: Int,
    val displayContent: String,
    val fullContent: String,
    val displayRevision: Long = markdownContentRevision(displayContent),
    val fullRevision: Long = markdownContentRevision(fullContent),
    val sequence: Long = 0L
)

/**
 * A parsed projection plus the request that produced it. [failure] keeps the engineering reason
 * when the parser failed and the renderer fell back to the readable source text.
 */
internal data class MarkdownProjectionResult(
    val request: MarkdownProjectionRequest,
    val blocks: List<MarkdownBlock>,
    val failure: String? = null
) {
    val owner: ChatMessageIdentity? get() = request.owner
    val visibleCharacters: Int get() = request.visibleCharacters
}

/**
 * Identity + version equality between a request and the result that claims to answer it. Both the
 * controller commit gate and the tests use this one function.
 */
internal fun MarkdownProjectionRequest.matches(result: MarkdownProjectionResult): Boolean {
    val committed = result.request
    return sequence == committed.sequence &&
        owner == committed.owner &&
        visibleCharacters == committed.visibleCharacters &&
        contentLength == committed.contentLength &&
        displayRevision == committed.displayRevision &&
        fullRevision == committed.fullRevision &&
        displayContent == committed.displayContent &&
        fullContent == committed.fullContent
}

/**
 * Whether the previously completed projection may stay on screen while [this] request is pending.
 *
 * Allowed only for the same owner, the same page range and a strict append of both the displayed
 * page and the complete source. A different owner, a non-prefix edit (replaced content) or any page
 * range change must not borrow the old blocks.
 */
internal fun MarkdownProjectionRequest.canDisplay(result: MarkdownProjectionResult?): Boolean {
    if (result == null) return false
    val previous = result.request
    if (owner != previous.owner) return false
    if (visibleCharacters != previous.visibleCharacters) return false
    if (contentLength < previous.contentLength) return false
    if (!fullContent.startsWith(previous.fullContent)) return false
    return displayContent.startsWith(previous.displayContent)
}

/**
 * Single-slot, identity-aware projection scheduler.
 *
 * Lifecycle: create one controller per rendered rich-text surface, start [run] from a
 * `LaunchedEffect(controller)` and submit every request from composition. [run] is cancelled with
 * the composition; a parse that is already executing on the background dispatcher may still finish,
 * but [run] re-checks cancellation and the pending version before it commits, so a destroyed owner
 * never publishes a result.
 *
 * There is intentionally no unbounded queue: [queuedRequests] is conflated, so a fast stream of
 * updates overwrites the single pending item while the worker parses the previous one.
 */
internal class MarkdownProjectionController(
    private val parseBlocks: suspend (displayContent: String, fullContent: String) -> List<MarkdownBlock>,
    private val onCommitted: (MarkdownProjectionResult) -> Unit = {}
) {
    private val queuedRequests = Channel<MarkdownProjectionRequest>(Channel.CONFLATED)
    private var lastSequence = 0L

    /** Last committed projection; it is expected to be validated through [canDisplay] before use. */
    var current: MarkdownProjectionResult? = null
        private set

    /** Newest submitted request; at most one, regardless of how many updates arrive. */
    var pendingRequest: MarkdownProjectionRequest? = null
        private set

    /**
     * Registers [request] as the only pending projection and returns the sequenced request that was
     * actually queued. The sequence is assigned here so two requests for identical text still get
     * distinct versions.
     */
    fun submit(request: MarkdownProjectionRequest): MarkdownProjectionRequest {
        val sequenced = request.copy(sequence = lastSequence + 1)
        lastSequence = sequenced.sequence
        pendingRequest = sequenced
        queuedRequests.trySend(sequenced)
        return sequenced
    }

    /**
     * Consumes the conflated queue until the surrounding coroutine is cancelled. Must be the only
     * worker for this controller.
     */
    suspend fun run() {
        for (request in queuedRequests) {
            currentCoroutineContext().ensureActive()
            val result = try {
                MarkdownProjectionResult(
                    request = request,
                    blocks = parseBlocks(request.displayContent, request.fullContent)
                )
            } catch (cancellation: CancellationException) {
                // Cancellation must stay cancellation: it is never mapped to a user-facing error.
                throw cancellation
            } catch (error: Exception) {
                // Keep a readable source projection and the engineering diagnostic instead of
                // dropping the answer when parsing fails.
                MarkdownProjectionResult(
                    request = request,
                    blocks = listOf(MarkdownBlock.Paragraph(request.displayContent)),
                    failure = error.toString()
                )
            }
            // A CPU scan is not cancellable once it started; the version / identity gate below is
            // what keeps a superseded or destroyed owner from publishing its result.
            currentCoroutineContext().ensureActive()
            val latest = pendingRequest ?: continue
            if (!latest.matches(result)) continue
            pendingRequest = null
            current = result
            onCommitted(result)
        }
    }
}
