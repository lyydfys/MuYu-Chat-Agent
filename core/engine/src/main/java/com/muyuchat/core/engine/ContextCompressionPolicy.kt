package com.muyuchat.core.engine

/**
 * User-selectable automatic compression thresholds. The values are deliberately
 * small and explicit so the UI cannot persist an arbitrary, unsafe percentage.
 */
enum class ContextCompressionThreshold(val percent: Int) {
    SEVENTY(70),
    EIGHTY(80),
    NINETY(90)
}

enum class ContextCompressionTrigger {
    AUTOMATIC,
    MANUAL
}

/** Identifies who produced the summary so deterministic text is not presented as model output. */
enum class ContextSummarySource {
    DETERMINISTIC,
    LOCAL_MODEL,
    CLOUD_MODEL,
    HOST_IMPLEMENTATION
}

/**
 * Input handed to a context summariser.
 *
 * The policy owns message selection and admission. A summariser only receives
 * the messages that are about to be folded, plus the previous summary (if
 * one exists), and returns text. Keeping this contract free of Android/UI
 * types lets a small local model, a host-side implementation, or the
 * deterministic fallback be plugged in without changing the request policy.
 */
data class ContextSummaryInput(
    val messages: List<ChatMessage>,
    val historicalMessageIndices: List<Int>,
    val previousSummary: String? = null,
    val maxChars: Int
)

/**
 * Pluggable summary implementation. Returning null or blank means that the
 * policy must use [DeterministicContextSummarizer]. Implementations should be
 * bounded and side-effect free; the policy never calls UI or Android APIs.
 */
fun interface ContextSummarizer {
    fun summarize(input: ContextSummaryInput): String?
}

/**
 * Pure policy settings. The summariser is deliberately injectable so a
 * compact local model can be added later, while a deterministic bounded
 * summary remains the safe default.
 */
data class ContextCompressionSettings(
    val threshold: ContextCompressionThreshold = ContextCompressionThreshold.EIGHTY,
    val keepRecentMessages: Int = 8,
    val minimumMessagesToCompress: Int = 2,
    val summaryMaxChars: Int = 2_400,
    val protectedMessageIndices: Set<Int> = emptySet(),
    val protectedMessageIds: Set<String> = emptySet(),
    /** Old image turns are retained until a vision-aware summariser exists. */
    val preserveMultimodalMessages: Boolean = true,
    /** A caller must label an injected local/host summariser explicitly. */
    val summarySource: ContextSummarySource = ContextSummarySource.DETERMINISTIC,
    val summarizer: ContextSummarizer = DeterministicContextSummarizer
) {
    init {
        require(keepRecentMessages >= 1) { "keepRecentMessages must be positive." }
        require(minimumMessagesToCompress >= 1) { "minimumMessagesToCompress must be positive." }
        require(summaryMaxChars >= 128) { "summaryMaxChars is too small." }
    }
}

enum class ContextCompressionStatus {
    NOT_NEEDED,
    COMPRESSED,
    FALLBACK_ADMISSION,
    FAILED
}

/** Result returned by the policy before a request crosses the native boundary. */
data class ContextCompressionResult(
    val status: ContextCompressionStatus,
    val request: ChatRequest,
    val compressedMessageCount: Int = 0,
    val summaryMessage: ChatMessage? = null,
    val trigger: ContextCompressionTrigger,
    val estimatedTokensBefore: Int,
    val thresholdPercent: Int,
    val fallbackAdmission: ContextWindowAdmission? = null,
    val failureReason: String? = null,
    val summarySource: ContextSummarySource = ContextSummarySource.DETERMINISTIC,
    /** Original message indices retained because they carry system or durable user context. */
    val protectedMessageIndices: Set<Int> = emptySet(),
    val protectedMessageIds: Set<String> = emptySet(),
    /** IDs of original turns included in this summary, excluding admission-trimmed turns. */
    val summarySourceMessageIds: List<String> = emptyList(),
    val structuredSummary: StructuredContextSummary = StructuredContextSummary(),
    val summaryModelIdentity: String? = null,
    val summaryRuntimeIdentity: String? = null,
    val summaryDiagnostic: String? = null
) {
    val didCompress: Boolean
        get() = status == ContextCompressionStatus.COMPRESSED
}

/** Stable marker used to keep a generated summary out of a later summary pass. */
const val CONTEXT_COMPRESSION_SUMMARY_MARKER: String = "[MCA_CONTEXT_SUMMARY v1]"

/** Explicit marker available to importers and editors for durable user memory. */
const val CONTEXT_MEMORY_KEEP_MARKER: String = "[MCA_KEEP_MEMORY]"

enum class ContextSummaryKind { FACT, TIMELINE, RELATIONSHIP, PREFERENCE, OPEN_TASK }

/** An exact source excerpt, not a model-inferred fact. */
data class ContextSummaryEvidence(
    val kind: ContextSummaryKind,
    val text: String,
    val sourceMessageIds: List<String>,
    val sourceCreatedAt: Long,
    val sourceOffset: Int,
    val sourceLength: Int,
    val userPinned: Boolean,
    val supersedesEvidenceId: String? = null
)

data class StructuredContextSummary(
    val evidence: List<ContextSummaryEvidence> = emptyList(),
    val sourceMessageCount: Int = 0,
    val candidateExcerptCount: Int = 0,
    val omittedExcerptCount: Int = 0,
    val omittedCharacterCount: Int = 0,
    val coverageLimited: Boolean = false
)

private val CONTEXT_MEMORY_LABEL_PATTERN = Regex(
    """(?is)^(?:[-*]\s*)?(?:${Regex.escape(CONTEXT_MEMORY_KEEP_MARKER)}|【(?:长期记忆|记忆|关键事实|重要事实|人物关系|关系|事件时间|时间线|未完成事项|待办)】|\[(?:memory|key facts?|important facts?|relationships?|timeline|unfinished|todo)\]|(?:长期记忆|记忆|关键事实|重要事实|人物关系|关系|事件时间|时间线|未完成事项|待办|memory|key facts?|important facts?|relationships?|timeline|unfinished|todo)\s*[:：])"""
)

/**
 * Detect durable context without requiring a separate schema on every message. Labels are
 * anchored at the beginning so a normal message that merely mentions "memory" is compressible.
 */
internal fun isContextMemoryMessage(message: ChatMessage): Boolean =
    message.content.trimStart().let(CONTEXT_MEMORY_LABEL_PATTERN::containsMatchIn)

/** Default bounded summariser used when no local summary model is available. */
object DeterministicContextSummarizer : ContextSummarizer {
    override fun summarize(input: ContextSummaryInput): String {
        val structured = deterministicContextEvidence(input)
        val builder = StringBuilder(CONTEXT_COMPRESSION_SUMMARY_MARKER)
            .append("\n较早对话的来源摘录；未列出的内容不代表已完整保留。")

        input.previousSummary
            ?.let(::summaryBodyWithoutMarker)
            ?.takeIf { it.isNotBlank() }
            ?.let { previous ->
                val allowance = (input.maxChars / 4).coerceAtMost(MAX_PREVIOUS_SUMMARY_CHARS)
                builder.append("\n已有摘要摘录：").append(previous.take(allowance))
                if (previous.length > allowance) builder.append(" [已有摘要截断]")
            }
        for (evidence in structured.evidence) {
            val line = "\n${evidence.kind.name} [${evidence.sourceMessageIds.single()}] ${evidence.text}"
            if (builder.length + line.length + SUMMARY_COVERAGE_NOTE.length > input.maxChars) break
            builder.append(line)
        }
        if (structured.coverageLimited || structured.evidence.any { it.text !in builder }) {
            builder.append(SUMMARY_COVERAGE_NOTE.take((input.maxChars - builder.length).coerceAtLeast(0)))
        }
        return normalizeContextSummary(builder.toString(), input.maxChars)
    }
}

/** Selects bounded exact excerpts across the whole scanned source, including late facts. */
fun deterministicContextEvidence(input: ContextSummaryInput): StructuredContextSummary {
    val candidates = mutableListOf<ContextSummaryEvidence>()
    var omittedCharacters = 0
    for (index in input.historicalMessageIndices) {
        val message = input.messages.getOrNull(index) ?: continue
        val content = message.content
        val scanLength = content.length.coerceAtMost(MAX_SOURCE_SCAN_CHARS)
        omittedCharacters += content.length - scanLength
        val source = content.substring(0, scanLength)
        for (match in SUMMARY_SENTENCE_PATTERN.findAll(source)) {
            val raw = match.value
            var offset = 0
            while (offset < raw.length) {
                val end = (offset + MAX_EVIDENCE_CHARS).coerceAtMost(raw.length)
                val piece = raw.substring(offset, end).trim()
                if (piece.isNotEmpty()) {
                    val leading = raw.substring(offset, end).indexOfFirst { !it.isWhitespace() }
                    candidates += ContextSummaryEvidence(
                        kind = classifyContextEvidence(piece),
                        text = piece,
                        sourceMessageIds = listOf(message.id),
                        sourceCreatedAt = message.createdAt,
                        sourceOffset = match.range.first + offset + leading,
                        sourceLength = piece.length,
                        userPinned = message.pinned
                    )
                }
                offset = end
            }
        }
    }
    val evidenceBudget = (input.maxChars - SUMMARY_FIXED_OVERHEAD_CHARS).coerceAtLeast(0)
    val selected = mutableListOf<ContextSummaryEvidence>()
    var used = 0
    // Relevant evidence gets priority, with source position as a stable tie breaker.
    val ranked = candidates.withIndex().sortedWith(
        compareByDescending<IndexedValue<ContextSummaryEvidence>> { evidencePriority(it.value) }
            .thenBy { it.index }
    )
    for ((_, item) in ranked) {
        val cost = item.text.length + item.sourceMessageIds.single().length + 32
        if (selected.size >= MAX_SUMMARY_EVIDENCE || used + cost > evidenceBudget) continue
        selected += item
        used += cost
    }
    val ordered = selected.sortedWith(compareBy({ it.sourceCreatedAt }, { it.sourceMessageIds.single() }, { it.sourceOffset }))
    return StructuredContextSummary(
        evidence = ordered,
        sourceMessageCount = input.historicalMessageIndices.size,
        candidateExcerptCount = candidates.size,
        omittedExcerptCount = candidates.size - ordered.size,
        omittedCharacterCount = omittedCharacters,
        coverageLimited = omittedCharacters > 0 || ordered.size < candidates.size
    )
}

private fun classifyContextEvidence(text: String): ContextSummaryKind = when {
    SUMMARY_OPEN_TASK_PATTERN.containsMatchIn(text) &&
        !SUMMARY_TASK_COMPLETED_PATTERN.containsMatchIn(text) -> ContextSummaryKind.OPEN_TASK
    SUMMARY_PREFERENCE_PATTERN.containsMatchIn(text) -> ContextSummaryKind.PREFERENCE
    SUMMARY_RELATIONSHIP_PATTERN.containsMatchIn(text) -> ContextSummaryKind.RELATIONSHIP
    SUMMARY_DATE_PATTERN.containsMatchIn(text) -> ContextSummaryKind.TIMELINE
    else -> ContextSummaryKind.FACT
}

private fun evidencePriority(item: ContextSummaryEvidence): Int = when (item.kind) {
    ContextSummaryKind.OPEN_TASK -> 5
    ContextSummaryKind.TIMELINE -> 4
    ContextSummaryKind.RELATIONSHIP -> 4
    ContextSummaryKind.PREFERENCE -> 4
    ContextSummaryKind.FACT -> 1
}

private val SUMMARY_SENTENCE_PATTERN = Regex("[^。！？!?;；.\\n]+[。！？!?;；.]?")
private val SUMMARY_DATE_PATTERN = Regex("(?:\\b\\d{4}[-/]\\d{1,2}(?:[-/]\\d{1,2})?\\b|\\d{4}年\\d{1,2}月|昨天|今天|明天|上周|下周)")
private val SUMMARY_RELATIONSHIP_PATTERN = Regex("(?i)(关系|父亲|母亲|妻子|丈夫|伴侣|朋友|姐姐|妹妹|哥哥|弟弟|father|mother|wife|husband|partner|friend|sister|brother)")
private val SUMMARY_PREFERENCE_PATTERN = Regex("(?i)(偏好|喜欢|不喜欢|讨厌|请用|习惯|prefer|preference|like|dislike|avoid)")
private val SUMMARY_OPEN_TASK_PATTERN = Regex("(?i)(待办|未完成|还没|计划|需要|待处理|todo|pending|need to|plan to)")
private val SUMMARY_TASK_COMPLETED_PATTERN = Regex("(?i)(已完成|完成了|已解决|done|completed|resolved)")

/**
 * Conservative token estimate for the complete logical request. This is only a
 * trigger signal; [localContextWindowAdmission] remains the final safety gate.
 */
fun estimateChatRequestContextTokens(request: ChatRequest): Int {
    var estimate = estimateLocalPromptTokens(request.params.systemPrompt)
    estimate += estimateLocalPromptTokens(request.runtimeSystemContext)
    estimate += REASONING_INSTRUCTION_ESTIMATE_TOKENS_FOR_COMPRESSION
    request.messages.forEach { message ->
        estimate += message.tokenCount?.coerceAtLeast(0) ?: estimateLocalPromptTokens(message.content)
        estimate += MESSAGE_TEMPLATE_ESTIMATE_TOKENS_FOR_COMPRESSION
    }
    return estimate.coerceAtMost(Int.MAX_VALUE)
}

/** Returns whether the configured automatic threshold has been reached. */
fun shouldCompressContext(
    request: ChatRequest,
    estimatedTokens: Int = estimateChatRequestContextTokens(request),
    settings: ContextCompressionSettings = ContextCompressionSettings(),
    trigger: ContextCompressionTrigger = ContextCompressionTrigger.AUTOMATIC
): Boolean {
    if (trigger == ContextCompressionTrigger.MANUAL) return true
    val nCtx = request.params.nCtx.coerceAtLeast(1)
    return estimatedTokens.toDouble() / nCtx.toDouble() >= settings.threshold.percent / 100.0
}

/**
 * Compresses only historical non-system turns. System messages (including role
 * cards represented as system messages), explicitly protected indices, and old
 * multimodal turns are retained. If the resulting request cannot pass the
 * normal admission policy, the caller receives that policy's deterministic
 * trimming result instead of a request that might fail in native code.
 */
fun compressChatRequestContext(
    request: ChatRequest,
    settings: ContextCompressionSettings = ContextCompressionSettings(),
    trigger: ContextCompressionTrigger = ContextCompressionTrigger.AUTOMATIC,
    estimatedTokens: Int = estimateChatRequestContextTokens(request),
    fallbackAdmission: (ChatRequest) -> ContextWindowAdmission = ::localContextWindowAdmission
): ContextCompressionResult {
    val thresholdPercent = settings.threshold.percent
    val incomingRequest = request
    val protectedIds = incomingRequest.protectedMessageIds + settings.protectedMessageIds +
        settings.protectedMessageIndices.mapNotNull { incomingRequest.messages.getOrNull(it)?.id }
    val request = if (protectedIds == incomingRequest.protectedMessageIds) incomingRequest else
        incomingRequest.copy(protectedMessageIds = protectedIds)
    if (!shouldCompressContext(request, estimatedTokens, settings, trigger)) {
        return ContextCompressionResult(
            status = ContextCompressionStatus.NOT_NEEDED,
            request = request,
            trigger = trigger,
            estimatedTokensBefore = estimatedTokens,
            thresholdPercent = thresholdPercent,
            protectedMessageIds = request.protectedMessageIds
        )
    }

    return runCatching {
        val existingSummaryIndices = request.messages.mapIndexedNotNull { index, message ->
            if (message.content.startsWith(CONTEXT_COMPRESSION_SUMMARY_MARKER)) index else null
        }
        val protected = request.messages.mapIndexedNotNull { index, message ->
            if (message.role == Role.SYSTEM ||
                index in settings.protectedMessageIndices ||
                message.id in request.protectedMessageIds ||
                message.pinned ||
                (settings.preserveMultimodalMessages && message.imageAttachments.isNotEmpty()) ||
                index in existingSummaryIndices ||
                isContextMemoryMessage(message)
            ) index else null
        }.toSet()
        val candidates = request.messages.indices.filter { it !in protected }
        val keepCount = settings.keepRecentMessages.coerceAtMost(candidates.size)
        val recent = candidates.takeLast(keepCount).toSet()
        val historical = candidates.dropLast(keepCount).filter { it !in recent }
        if (historical.size < settings.minimumMessagesToCompress) {
            return@runCatching ContextCompressionResult(
                status = ContextCompressionStatus.NOT_NEEDED,
                request = request,
                trigger = trigger,
                estimatedTokensBefore = estimatedTokens,
                thresholdPercent = thresholdPercent,
                protectedMessageIndices = protected,
                protectedMessageIds = request.protectedMessageIds
            )
        }

        val previousSummary = existingSummaryIndices
            .asSequence()
            .map { request.messages[it].content }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
        val summarySourceIndices = (existingSummaryIndices + historical).distinct().sorted()
        val summaryInput = ContextSummaryInput(
            messages = request.messages,
            historicalMessageIndices = historical,
            previousSummary = previousSummary,
            maxChars = settings.summaryMaxChars
        )
        val deterministicEvidence = deterministicContextEvidence(summaryInput)
        val builtSummary = buildContextSummary(
            summarizer = settings.summarizer,
            input = summaryInput,
            declaredSource = settings.summarySource
        )
        val summary = builtSummary.text
        // Replace all prior markers and newly folded turns with one marker.
        // This makes repeated automatic/manual passes idempotent and prevents
        // summary messages from multiplying in long-running chats.
        val firstHistorical = summarySourceIndices.first()
        val summaryMessage = ChatMessage(
            role = Role.SYSTEM,
            content = summary,
            createdAt = request.messages[firstHistorical].createdAt.coerceAtLeast(1L) - 1L,
            tokenCount = estimateLocalPromptTokens(summary)
        )
        val compressedMessages = ArrayList<ChatMessage>(
            request.messages.size - summarySourceIndices.size + 1
        )
        request.messages.forEachIndexed { index, message ->
            when {
                index == firstHistorical -> compressedMessages += summaryMessage
                index in summarySourceIndices -> Unit
                else -> compressedMessages += message
            }
        }
        val compressedRequest = request.copy(messages = compressedMessages)

        // A fixed character cap can still be too large for compact native
        // windows (for example n_ctx=512 with a role card and a recent turn).
        // Retry admission with a progressively smaller summary before giving
        // up.  This is the important distinction between "compression was
        // attempted" and "the compressed request can actually execute".
        var admittedCompressedRequest: ChatRequest? = null
        var compressedAdmission: ContextWindowAdmission? = null
        var candidateRequest = compressedRequest
        repeat(MAX_SUMMARY_ADMISSION_RETRIES) { attempt ->
            val candidateAdmission = runCatching { fallbackAdmission(candidateRequest) }.getOrNull()
            if (candidateAdmission?.isAccepted == true) {
                admittedCompressedRequest = candidateAdmission.request
                compressedAdmission = candidateAdmission
                return@repeat
            }
            if (attempt == MAX_SUMMARY_ADMISSION_RETRIES - 1) return@repeat
            val summaryIndex = candidateRequest.messages.indexOfFirst {
                it.content.startsWith(CONTEXT_COMPRESSION_SUMMARY_MARKER)
            }
            if (summaryIndex < 0) return@repeat
            val currentSummary = candidateRequest.messages[summaryIndex].content
            val nextMaxChars = (currentSummary.length / 2).coerceAtLeast(
                CONTEXT_COMPRESSION_SUMMARY_MARKER.length + MIN_COMPACT_SUMMARY_BODY_CHARS
            )
            if (nextMaxChars >= currentSummary.length) return@repeat
            val compactedSummary = normalizeContextSummary(currentSummary, nextMaxChars)
            val updatedMessages = candidateRequest.messages.toMutableList().apply {
                this[summaryIndex] = this[summaryIndex].copy(
                    content = compactedSummary,
                    tokenCount = estimateLocalPromptTokens(compactedSummary)
                )
            }
            candidateRequest = candidateRequest.copy(messages = updatedMessages)
        }
        val compressedAdmissionRequest = admittedCompressedRequest
        val compressedAdmissionResult = compressedAdmission
        if (compressedAdmissionResult?.isAccepted != true || compressedAdmissionRequest == null) {
            // A summariser may be larger than the turns it replaces. Prefer a
            // safe admission of the original request when one exists; this is
            // deterministic and avoids sending an over-budget compressed
            // request across the native boundary.
            val originalAdmission = runCatching { fallbackAdmission(request) }.getOrNull()
            val safeFallback = listOfNotNull(originalAdmission, compressedAdmissionResult)
                .firstOrNull { it.isAccepted }
            if (safeFallback != null) {
                return@runCatching ContextCompressionResult(
                    status = ContextCompressionStatus.FALLBACK_ADMISSION,
                    request = safeFallback.request,
                    compressedMessageCount = historical.size,
                    summaryMessage = compressedAdmissionRequest?.messages
                        ?.firstOrNull { it.content.startsWith(CONTEXT_COMPRESSION_SUMMARY_MARKER) }
                        ?: summaryMessage,
                    trigger = trigger,
                    estimatedTokensBefore = estimatedTokens,
                    thresholdPercent = thresholdPercent,
                    fallbackAdmission = safeFallback,
                    failureReason = "压缩结果未通过上下文预算，已使用安全保留策略。",
                    summarySource = builtSummary.source,
                    protectedMessageIndices = protected,
                    protectedMessageIds = request.protectedMessageIds
                )
            }
            val rejectedAdmission = compressedAdmissionResult ?: originalAdmission
            return@runCatching ContextCompressionResult(
                status = ContextCompressionStatus.FALLBACK_ADMISSION,
                request = rejectedAdmission?.request ?: request,
                compressedMessageCount = historical.size,
                summaryMessage = compressedAdmissionRequest?.messages
                    ?.firstOrNull { it.content.startsWith(CONTEXT_COMPRESSION_SUMMARY_MARKER) }
                    ?: summaryMessage,
                trigger = trigger,
                estimatedTokensBefore = estimatedTokens,
                thresholdPercent = thresholdPercent,
                fallbackAdmission = rejectedAdmission,
                failureReason = rejectedAdmission?.userMessage
                    ?: "上下文压缩和保留策略都无法满足当前窗口预算。",
                summarySource = builtSummary.source,
                protectedMessageIndices = protected,
                protectedMessageIds = request.protectedMessageIds
            )
        }
        val finalSummaryText = compressedAdmissionRequest.messages
            .firstOrNull { it.content.startsWith(CONTEXT_COMPRESSION_SUMMARY_MARKER) }
            ?.content.orEmpty()
        val survivingEvidence = deterministicEvidence.evidence.filter { it.text in finalSummaryText }
        val coveredEvidence = deterministicEvidence.copy(
            evidence = survivingEvidence,
            omittedExcerptCount = deterministicEvidence.candidateExcerptCount - survivingEvidence.size,
            coverageLimited = deterministicEvidence.coverageLimited ||
                survivingEvidence.size < deterministicEvidence.evidence.size
        )
        ContextCompressionResult(
            status = ContextCompressionStatus.COMPRESSED,
            request = compressedAdmissionRequest,
            compressedMessageCount = historical.size,
            summaryMessage = compressedAdmissionRequest.messages
                .firstOrNull { it.content.startsWith(CONTEXT_COMPRESSION_SUMMARY_MARKER) }
                ?: summaryMessage,
            trigger = trigger,
            estimatedTokensBefore = estimatedTokens,
            thresholdPercent = thresholdPercent,
            fallbackAdmission = compressedAdmissionResult,
            summarySource = builtSummary.source,
            protectedMessageIndices = protected,
            protectedMessageIds = request.protectedMessageIds,
            summarySourceMessageIds = historical.map { request.messages[it].id },
            structuredSummary = coveredEvidence
        )
    }.getOrElse { error ->
        if (error is java.util.concurrent.CancellationException) throw error
        val admission = runCatching { fallbackAdmission(request) }.getOrNull()
        ContextCompressionResult(
            status = ContextCompressionStatus.FAILED,
            request = admission?.request ?: request,
            trigger = trigger,
            estimatedTokensBefore = estimatedTokens,
            thresholdPercent = thresholdPercent,
            fallbackAdmission = admission,
            failureReason = error.message?.take(240) ?: error::class.java.simpleName,
            summarySource = ContextSummarySource.DETERMINISTIC,
            protectedMessageIds = request.protectedMessageIds
        )
    }
}

private data class BuiltContextSummary(
    val text: String,
    val source: ContextSummarySource
)

private fun buildContextSummary(
    summarizer: ContextSummarizer,
    input: ContextSummaryInput,
    declaredSource: ContextSummarySource
): BuiltContextSummary {
    val generated = runCatching { summarizer.summarize(input) }
        .onFailure { if (it is java.util.concurrent.CancellationException) throw it }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
    return BuiltContextSummary(
        text = normalizeContextSummary(
            generated ?: DeterministicContextSummarizer.summarize(input),
            input.maxChars
        ),
        source = if (generated != null) declaredSource else ContextSummarySource.DETERMINISTIC
    )
}

private fun normalizeContextSummary(summary: String, maxChars: Int): String {
    val body = summaryBodyWithoutMarker(summary)
        .trim()
        .ifBlank { "较早对话已压缩，保留其上下文线索。" }
    val normalized = "$CONTEXT_COMPRESSION_SUMMARY_MARKER\n$body"
    return normalized.take(maxChars.coerceAtLeast(CONTEXT_COMPRESSION_SUMMARY_MARKER.length + 1))
}

private fun summaryBodyWithoutMarker(summary: String): String =
    summary
        .replace(CONTEXT_COMPRESSION_SUMMARY_MARKER, "")
        .trim()

private const val MAX_PREVIOUS_SUMMARY_CHARS = 1_200
private const val MAX_EVIDENCE_CHARS = 320
private const val MAX_SOURCE_SCAN_CHARS = 32_000
private const val MAX_SUMMARY_EVIDENCE = 20
private const val SUMMARY_FIXED_OVERHEAD_CHARS = 260
private const val SUMMARY_COVERAGE_NOTE = "\n[覆盖有限：未列出的来源片段仍需查看原始消息。]"
private const val MAX_SUMMARY_ADMISSION_RETRIES = 6
private const val MIN_COMPACT_SUMMARY_BODY_CHARS = 64

private const val REASONING_INSTRUCTION_ESTIMATE_TOKENS_FOR_COMPRESSION = 96
private const val MESSAGE_TEMPLATE_ESTIMATE_TOKENS_FOR_COMPRESSION = 2
