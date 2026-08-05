package com.muyuchat.core.engine

/**
 * A tokenizer-independent footprint for text that may be passed to a local
 * runtime. UTF-8 bytes and Unicode code points are tracked separately from the
 * token estimate so pathological input cannot bypass the estimate by relying
 * on a particular character encoding.
 */
data class LocalPromptTextFootprint(
    val utf8Bytes: Long,
    val codePointCount: Long,
    val estimatedTokens: Int
)

/**
 * Conservative input limits derived from the logical prompt portion of a
 * context window. The prompt token limit already leaves the budget's output
 * reserve and runtime headroom untouched.
 */
data class ContextWindowInputLimits(
    val promptTokenLimit: Int,
    val promptUtf8ByteLimit: Long,
    val promptCodePointLimit: Long
)

/** Reason a request could not be represented safely in the active window. */
enum class ContextWindowRejectionCode {
    CONTEXT_BUDGET_TOO_SMALL,
    SYSTEM_CONTEXT_TOO_LARGE,
    LATEST_USER_INPUT_TOO_LARGE
}

/** The concrete safety dimension that reached its limit first. */
enum class ContextWindowLimit {
    TOKENS,
    UTF8_BYTES,
    CODE_POINTS
}

enum class ContextWindowAdmissionStatus {
    ACCEPTED,
    TRIMMED,
    REJECTED
}

/**
 * Reusable context decision for UI, Local API, and the native engine boundary.
 * Accepted decisions return the original request without a message-list copy;
 * trimmed decisions contain only the deterministic retained history.
 */
data class ContextWindowAdmission(
    val status: ContextWindowAdmissionStatus,
    val request: ChatRequest,
    val budget: ContextWindowBudget,
    val limits: ContextWindowInputLimits,
    val admittedUsage: ContextWindowUsage,
    val trimmedMessageCount: Int = 0,
    val messageRetention: List<PromptMessageRetention> = emptyList(),
    val rejectionCode: ContextWindowRejectionCode? = null,
    val limitExceeded: ContextWindowLimit? = null,
    val userMessage: String? = null
) {
    val isAccepted: Boolean
        get() = status != ContextWindowAdmissionStatus.REJECTED
}

/** Usage reported for the request that will reach native execution. */
data class ContextWindowUsage(
    val estimatedTokens: Long,
    val utf8Bytes: Long,
    val codePointCount: Long,
    val messageCount: Int
)

/**
 * Measures without allocating a UTF-8 byte array. The fallback intentionally
 * treats CJK, emoji, symbols, and source-code punctuation as denser than plain
 * Latin prose until a runtime tokenizer is available.
 */
fun localPromptTextFootprint(text: CharSequence): LocalPromptTextFootprint {
    val measurement = measurePromptText(text, PromptMeasurementLimits.UNBOUNDED)
    return LocalPromptTextFootprint(
        utf8Bytes = measurement.utf8Bytes,
        codePointCount = measurement.codePointCount,
        estimatedTokens = measurement.estimatedTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    )
}

/** Conservative fallback used before a runtime tokenizer is available. */
fun estimateLocalPromptTokens(text: CharSequence): Int = localPromptTextFootprint(text).estimatedTokens

fun localContextWindowInputLimits(budget: ContextWindowBudget): ContextWindowInputLimits {
    val promptTokens = budget.promptBudgetTokens.coerceAtLeast(0)
    return ContextWindowInputLimits(
        promptTokenLimit = promptTokens,
        promptUtf8ByteLimit = scaledInputLimit(
            promptTokens,
            UTF8_BYTES_PER_PROMPT_TOKEN,
            MAX_CONTEXT_INPUT_UTF8_BYTES
        ),
        promptCodePointLimit = scaledInputLimit(
            promptTokens,
            CODE_POINTS_PER_PROMPT_TOKEN,
            MAX_CONTEXT_INPUT_CODE_POINTS
        )
    )
}

fun localContextWindowInputLimits(requestedContextLength: Int): ContextWindowInputLimits =
    localContextWindowInputLimits(localContextWindowBudget(requestedContextLength))

/**
 * Produces the exact request that may cross the native boundary.
 *
 * System/runtime context is immutable for this decision. The newest user
 * message is reserved first, then older non-system messages are retained from
 * newest to oldest while they still fit. This preserves both the latest user
 * input and [ContextWindowBudget.reservedOutputTokens].
 */
fun localContextWindowAdmission(request: ChatRequest): ContextWindowAdmission {
    val budget = localContextWindowBudget(request.params.nCtx)
    val limits = localContextWindowInputLimits(budget)
    val usage = MutableContextWindowUsage()

    fun rejected(
        code: ContextWindowRejectionCode,
        limit: ContextWindowLimit? = null
    ): ContextWindowAdmission = ContextWindowAdmission(
        status = ContextWindowAdmissionStatus.REJECTED,
        request = request,
        budget = budget,
        limits = limits,
        admittedUsage = usage.snapshot(),
        rejectionCode = code,
        limitExceeded = limit,
        userMessage = contextWindowErrorMessage(code, budget.contextLength)
    )

    if (budget.promptBudgetTokens < budget.minimumPromptBudgetTokens) {
        return rejected(ContextWindowRejectionCode.CONTEXT_BUDGET_TOO_SMALL)
    }

    // System messages, the configured system prompt, and runtime context are
    // never silently removed. Reject before native execution when they alone
    // exhaust the usable prompt window.
    for (message in request.messages) {
        if (message.role != Role.SYSTEM) continue
        usage.tryAddMessage(message, limits)?.let { limit ->
            return rejected(ContextWindowRejectionCode.SYSTEM_CONTEXT_TOO_LARGE, limit)
        }
    }
    usage.tryAddText(request.params.systemPrompt, limits)?.let { limit ->
        return rejected(ContextWindowRejectionCode.SYSTEM_CONTEXT_TOO_LARGE, limit)
    }
    usage.tryAddText(request.runtimeSystemContext, limits)?.let { limit ->
        return rejected(ContextWindowRejectionCode.SYSTEM_CONTEXT_TOO_LARGE, limit)
    }
    usage.tryAddStaticTokens(REASONING_INSTRUCTION_ESTIMATE_TOKENS, limits)?.let { limit ->
        return rejected(ContextWindowRejectionCode.SYSTEM_CONTEXT_TOO_LARGE, limit)
    }

    val newestUserIndex = request.messages.indexOfLast { it.role == Role.USER }
    val anchorIndex = newestUserIndex.takeIf { it >= 0 }
        ?: request.messages.indexOfLast { it.role != Role.SYSTEM }
    if (anchorIndex >= 0) {
        usage.tryAddMessage(request.messages[anchorIndex], limits)?.let { limit ->
            return rejected(ContextWindowRejectionCode.LATEST_USER_INPUT_TOO_LARGE, limit)
        }
    }

    // Do not allocate a second list for the common fully-admitted request. A
    // retained-index set is created only after the first old message has to be
    // omitted; message content itself is never copied by this policy.
    var retained: MutableSet<Int>? = null
    for (index in request.messages.indices.reversed()) {
        val message = request.messages[index]
        if (message.role == Role.SYSTEM || index == anchorIndex) continue

        val limit = usage.tryAddMessage(message, limits)
        if (limit == null) {
            retained?.add(index)
            continue
        }

        if (retained == null) {
            val knownRetained = HashSet<Int>()
            retained = knownRetained
            for (knownIndex in request.messages.indices) {
                if (request.messages[knownIndex].role == Role.SYSTEM ||
                    knownIndex == anchorIndex ||
                    knownIndex > index
                ) {
                    knownRetained.add(knownIndex)
                }
            }
        }
    }

    val selected = retained ?: return ContextWindowAdmission(
        status = ContextWindowAdmissionStatus.ACCEPTED,
        request = request,
        budget = budget,
        limits = limits,
        admittedUsage = usage.snapshot(),
        messageRetention = request.messages.mapIndexed { index, message ->
            PromptMessageRetention(index, message.role, retained = true)
        }
    )

    val keptMessages = ArrayList<ChatMessage>(selected.size)
    request.messages.forEachIndexed { index, message ->
        if (index in selected) keptMessages += message
    }
    return ContextWindowAdmission(
        status = ContextWindowAdmissionStatus.TRIMMED,
        request = request.copy(messages = keptMessages),
        budget = budget,
        limits = limits,
        admittedUsage = usage.snapshot(),
        trimmedMessageCount = request.messages.size - keptMessages.size,
        messageRetention = request.messages.mapIndexed { index, message ->
            PromptMessageRetention(index, message.role, retained = index in selected)
        }
    )
}

private class MutableContextWindowUsage {
    var estimatedTokens: Long = 0L
        private set
    var utf8Bytes: Long = 0L
        private set
    var codePointCount: Long = 0L
        private set
    var messageCount: Int = 0
        private set

    fun tryAddStaticTokens(
        tokens: Int,
        limits: ContextWindowInputLimits
    ): ContextWindowLimit? {
        val candidate = saturatedAdd(estimatedTokens, tokens.toLong().coerceAtLeast(0L))
        if (candidate > limits.promptTokenLimit.toLong()) return ContextWindowLimit.TOKENS
        estimatedTokens = candidate
        return null
    }

    fun tryAddText(
        text: CharSequence,
        limits: ContextWindowInputLimits,
        extraTokens: Int = 0,
        knownTokenCount: Int? = null
    ): ContextWindowLimit? {
        val remainingTokens = limits.promptTokenLimit.toLong() - estimatedTokens - extraTokens
        val remainingBytes = limits.promptUtf8ByteLimit - utf8Bytes
        val remainingCodePoints = limits.promptCodePointLimit - codePointCount
        if (remainingTokens < 1L) return ContextWindowLimit.TOKENS
        if (remainingBytes < 0L) return ContextWindowLimit.UTF8_BYTES
        if (remainingCodePoints < 0L) return ContextWindowLimit.CODE_POINTS

        val measurement = measurePromptText(
            text = text,
            limits = PromptMeasurementLimits(
                tokenLimit = remainingTokens,
                utf8ByteLimit = remainingBytes,
                codePointLimit = remainingCodePoints
            )
        )
        measurement.limitExceeded?.let { return it }
        val trustedTokens = maxOf(
            measurement.estimatedTokens,
            knownTokenCount?.toLong()?.coerceAtLeast(0L) ?: 0L
        )
        if (trustedTokens > remainingTokens) return ContextWindowLimit.TOKENS

        estimatedTokens = saturatedAdd(estimatedTokens, trustedTokens + extraTokens)
        utf8Bytes = saturatedAdd(utf8Bytes, measurement.utf8Bytes)
        codePointCount = saturatedAdd(codePointCount, measurement.codePointCount)
        return null
    }

    fun tryAddMessage(
        message: ChatMessage,
        limits: ContextWindowInputLimits
    ): ContextWindowLimit? {
        val limit = tryAddText(
            text = message.content,
            limits = limits,
            extraTokens = MESSAGE_TEMPLATE_ESTIMATE_TOKENS,
            knownTokenCount = message.tokenCount
        )
        if (limit == null) messageCount += 1
        return limit
    }

    fun snapshot(): ContextWindowUsage = ContextWindowUsage(
        estimatedTokens = estimatedTokens,
        utf8Bytes = utf8Bytes,
        codePointCount = codePointCount,
        messageCount = messageCount
    )
}

private data class PromptMeasurement(
    val utf8Bytes: Long,
    val codePointCount: Long,
    val estimatedTokens: Long,
    val limitExceeded: ContextWindowLimit? = null
)

private data class PromptMeasurementLimits(
    val tokenLimit: Long,
    val utf8ByteLimit: Long,
    val codePointLimit: Long
) {
    companion object {
        val UNBOUNDED = PromptMeasurementLimits(
            tokenLimit = Long.MAX_VALUE,
            utf8ByteLimit = Long.MAX_VALUE,
            codePointLimit = Long.MAX_VALUE
        )
    }
}

private fun measurePromptText(
    text: CharSequence,
    limits: PromptMeasurementLimits
): PromptMeasurement {
    var denseTokens = 0L
    var sparseCharacters = 0L
    var syntaxTokens = 0L
    var utf8Bytes = 0L
    var codePointCount = 0L
    var index = 0

    fun estimated(): Long = saturatedAdd(
        saturatedAdd(denseTokens, syntaxTokens),
        (sparseCharacters + SPARSE_CHARACTERS_PER_TOKEN - 1L) / SPARSE_CHARACTERS_PER_TOKEN
    ).coerceAtLeast(1L)

    if (text.isEmpty()) {
        val emptyEstimate = 1L
        return PromptMeasurement(
            utf8Bytes = 0L,
            codePointCount = 0L,
            estimatedTokens = emptyEstimate,
            limitExceeded = if (emptyEstimate > limits.tokenLimit) ContextWindowLimit.TOKENS else null
        )
    }

    while (index < text.length) {
        val codePoint = Character.codePointAt(text, index)
        codePointCount = saturatedAdd(codePointCount, 1L)
        utf8Bytes = saturatedAdd(utf8Bytes, utf8Length(codePoint).toLong())
        when {
            isDenseScript(codePoint) -> denseTokens = saturatedAdd(denseTokens, 1L)
            isEmojiOrSymbol(codePoint) -> denseTokens = saturatedAdd(denseTokens, EMOJI_OR_SYMBOL_TOKENS)
            isSparseLatinOrWhitespace(codePoint) -> sparseCharacters = saturatedAdd(sparseCharacters, 1L)
            else -> syntaxTokens = saturatedAdd(syntaxTokens, 1L)
        }
        index += Character.charCount(codePoint)

        val tokenEstimate = estimated()
        val exceeded = when {
            tokenEstimate > limits.tokenLimit -> ContextWindowLimit.TOKENS
            utf8Bytes > limits.utf8ByteLimit -> ContextWindowLimit.UTF8_BYTES
            codePointCount > limits.codePointLimit -> ContextWindowLimit.CODE_POINTS
            else -> null
        }
        if (exceeded != null) {
            return PromptMeasurement(
                utf8Bytes = utf8Bytes,
                codePointCount = codePointCount,
                estimatedTokens = tokenEstimate,
                limitExceeded = exceeded
            )
        }
    }

    return PromptMeasurement(
        utf8Bytes = utf8Bytes,
        codePointCount = codePointCount,
        estimatedTokens = estimated()
    )
}

private fun isDenseScript(codePoint: Int): Boolean =
    codePoint in 0x1100..0x11FF ||
        codePoint in 0x2E80..0x33FF ||
        codePoint in 0x3400..0x4DBF ||
        codePoint in 0x4E00..0x9FFF ||
        codePoint in 0xA960..0xA97F ||
        codePoint in 0xAC00..0xD7FF ||
        codePoint in 0xF900..0xFAFF ||
        codePoint in 0x20000..0x2EBEF ||
        codePoint in 0x30000..0x323AF

private fun isEmojiOrSymbol(codePoint: Int): Boolean {
    if (codePoint in 0x1F000..0x1FAFF || codePoint in 0x2600..0x27BF) return true
    return when (Character.getType(codePoint)) {
        Character.MATH_SYMBOL.toInt(),
        Character.CURRENCY_SYMBOL.toInt(),
        Character.MODIFIER_SYMBOL.toInt(),
        Character.OTHER_SYMBOL.toInt() -> true
        else -> false
    }
}

private fun isSparseLatinOrWhitespace(codePoint: Int): Boolean =
    codePoint <= 0x7F && (Character.isLetterOrDigit(codePoint) || Character.isWhitespace(codePoint)) ||
        codePoint in 0x00C0..0x024F ||
        Character.isWhitespace(codePoint)

private fun utf8Length(codePoint: Int): Int = when {
    codePoint <= 0x7F -> 1
    codePoint <= 0x7FF -> 2
    codePoint <= 0xFFFF -> 3
    else -> 4
}

private fun contextWindowErrorMessage(
    code: ContextWindowRejectionCode,
    nCtx: Int
): String = when (code) {
    ContextWindowRejectionCode.CONTEXT_BUDGET_TOO_SMALL ->
        "\u4e0a\u4e0b\u6587\u9884\u7b97\u8fc7\u5c0f\uff1an_ctx=$nCtx\u3002\u8bf7\u63d0\u9ad8 n_ctx\uff0c\u6216\u7f29\u77ed\u4e0a\u4f20\u6587\u4ef6/\u5386\u53f2\u5bf9\u8bdd\u3002"
    ContextWindowRejectionCode.SYSTEM_CONTEXT_TOO_LARGE ->
        "\u7cfb\u7edf\u63d0\u793a\u548c\u8fd0\u884c\u65f6\u4e0a\u4e0b\u6587\u8d85\u8fc7 n_ctx=$nCtx \u7684\u5b89\u5168\u63d0\u793a\u9884\u7b97\u3002\u8bf7\u7f29\u77ed\u7cfb\u7edf\u63d0\u793a\uff0c\u6216\u63d0\u9ad8 n_ctx\u3002"
    ContextWindowRejectionCode.LATEST_USER_INPUT_TOO_LARGE ->
        "\u5f53\u524d\u8f93\u5165\u8d85\u8fc7\u672c\u673a\u5b89\u5168\u4e0a\u4e0b\u6587\u9884\u7b97\u3002\u8bf7\u7f29\u77ed\u4e0a\u4f20\u6587\u4ef6/\u95ee\u9898\uff0c\u6216\u5728\u53c2\u6570\u9875\u63d0\u9ad8 n_ctx\u3002"
}

private fun scaledInputLimit(tokens: Int, perToken: Long, absoluteLimit: Long): Long =
    (tokens.toLong().coerceAtLeast(0L) * perToken).coerceAtMost(absoluteLimit)

private fun saturatedAdd(left: Long, right: Long): Long =
    if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

private const val REASONING_INSTRUCTION_ESTIMATE_TOKENS = 96
private const val MESSAGE_TEMPLATE_ESTIMATE_TOKENS = 2
private const val SPARSE_CHARACTERS_PER_TOKEN = 3L
private const val EMOJI_OR_SYMBOL_TOKENS = 2L
private const val UTF8_BYTES_PER_PROMPT_TOKEN = 4L
private const val CODE_POINTS_PER_PROMPT_TOKEN = 3L
private const val MAX_CONTEXT_INPUT_UTF8_BYTES = 8L * 1024L * 1024L
private const val MAX_CONTEXT_INPUT_CODE_POINTS = 2L * 1024L * 1024L
