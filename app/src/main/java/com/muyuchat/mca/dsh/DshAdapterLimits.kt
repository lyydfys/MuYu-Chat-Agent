package com.muyuchat.mca.dsh

/**
 * Bounded, deterministic limits for one DSH model call.
 *
 * The internal DSH gateway already bounds the HTTP body, concurrency and the total response it
 * emits. These limits protect the app process from a single oversized prompt or tool transcript
 * before it reaches the engine or a cloud provider, and keep the prompted-tool envelope bounded.
 * They are adapter policy, not model capability; the catalog never derives capability from them.
 */
internal data class DshAdapterLimits(
    /** Whole mapped transcript (system + messages) ceiling in characters. */
    val maxTotalTranscriptChars: Int = 1_000_000,
    /** A single system/user/assistant text message ceiling; oversized input fails honestly. */
    val maxMessageChars: Int = 256 * 1024,
    /** A single tool result is truncated with an explicit marker instead of failing the loop. */
    val maxToolResultChars: Int = 32 * 1024,
    /** All assistant tool-call blocks of one historical message, truncated with a marker. */
    val maxAssistantToolCallChars: Int = 64 * 1024,
    /**
     * Complete JSON arguments of one tool call (history or a native provider response). Larger
     * arguments are rejected explicitly ([DshAdapterErrorCodes.TOOL_ARGUMENTS_TOO_LARGE]); they are
     * never silently truncated into an unusable half-call.
     */
    val maxToolCallArgumentsChars: Int = 64 * 1024,
    /** Tool calls accepted from one native provider response. */
    val maxToolCallsPerResponse: Int = 16,
    /** Tool definitions accepted by the adapter (the gateway also caps at 128). */
    val maxToolDefinitions: Int = 128,
    /** One offered tool name. */
    val maxToolNameChars: Int = 128,
    /** Description budget for one tool inside the prompted catalog. */
    val maxToolDescriptionChars: Int = 2 * 1024,
    /** JSON schema budget for one tool inside the prompted catalog. */
    val maxToolParametersChars: Int = 12 * 1024,
    /** Whole prompted tool catalog ceiling; tools beyond it are omitted and named as unavailable. */
    val maxPromptedToolCatalogChars: Int = 24 * 1024,
    /** Whole prompted tool-call envelope ceiling; a larger envelope degrades to plain text. */
    val maxPromptedEnvelopeChars: Int = 64 * 1024,
    /** Tool calls accepted from one prompted model answer. */
    val maxPromptedToolCalls: Int = 16,
    /** Output-token request ceiling for one call (matches the cloud transport's hard cap). */
    val maxOutputTokens: Int = 32_768,
    /**
     * How long the local engine may take to emit its first event before the call is reported as a
     * retryable resource-busy failure. The engine serializes requests; waiting forever would hide
     * contention from DSH, so the adapter cancels exactly its own request and lets DSH retry.
     *
     * 180 s covers a cold 2–4 GiB GGUF load plus prompt prefill on a phone; 60 s produced a
     * resource-busy retry storm while the first request was still loading the model. It is still
     * bounded so a genuinely stuck engine is reported instead of hanging the turn.
     */
    val admissionTimeoutMillis: Long = 180_000L,
    /** Diagnostic/error text ceiling in a [com.muyuchat.core.dsh.contract.DshModelEvent.Failed]. */
    val maxErrorMessageChars: Int = 400,
    /**
     * Context window a cloud prompt is fitted into by [com.muyuchat.mca.CloudPromptBudget].
     *
     * The DSH catalog advertises this per cloud model; 128 k is the documented fallback for a gateway
     * whose real window is unknown. It is an adapter budget, never a capability claim.
     */
    val cloudContextWindowTokens: Int = 128_000,
    /** Cloud output budget advertised/requested; 0 = derive it from the window (min(window/2, 65 536)). */
    val cloudMaxOutputTokens: Int = 0
) {
    /**
     * Cloud models have a large context and must see the whole tool catalog (including MCP tools);
     * the 24 KiB prompted-catalog default exists for small local models. This only raises the
     * catalog/description/parameter budgets, never a transcript or argument limit.
     */
    /**
     * Local CPU models prefill far slower than cloud models; the 24 KiB catalog plus the full
     * instruction used to push a 1.5B request past the official 300 s stream watchdog. This tighter
     * local budget keeps the highest-priority tools and the same protocol, trading omitted tools for
     * a prompt the device can actually prefill in time.
     */
    fun forLocalPrompted(): DshAdapterLimits = copy(
        // An 8 KiB native catalog keeps bash/read/write/terminal (packed first) while leaving
        // room for the agent transcript inside DSH's 8K safety budget. The previous 16 KiB
        // catalog pushed the full system prompt past that budget on the 0.5B model, so the host
        // rejected the turn before generation; the catalog still degrades honestly when it
        // cannot fit.
        maxPromptedToolCatalogChars = 8 * 1024,
        maxToolParametersChars = 4 * 1024,
        // 512 dropped `bash` (795-char description) and `workflow` from the local catalog even
        // though their schemas fit, so the model was told the shell tool was unavailable. 2 KiB
        // keeps the real DSH descriptions; the catalog still packs essentials first.
        maxToolDescriptionChars = 2 * 1024
    )

    fun forCloudPrompted(): DshAdapterLimits = copy(
        maxPromptedToolCatalogChars = maxOf(maxPromptedToolCatalogChars, 64 * 1024),
        maxToolParametersChars = maxOf(maxToolParametersChars, 24 * 1024),
        maxToolDescriptionChars = maxOf(maxToolDescriptionChars, 4 * 1024)
    )

    init {
        require(maxTotalTranscriptChars in 1_024..16 * 1024 * 1024) {
            "maxTotalTranscriptChars must be in 1024..16 MiB."
        }
        require(maxMessageChars in 256..maxTotalTranscriptChars) {
            "maxMessageChars must be in 256..maxTotalTranscriptChars."
        }
        require(maxToolResultChars in 64..maxMessageChars) {
            "maxToolResultChars must be in 64..maxMessageChars."
        }
        require(maxAssistantToolCallChars in 64..maxTotalTranscriptChars) {
            "maxAssistantToolCallChars must be positive and bounded by the transcript."
        }
        require(maxToolCallArgumentsChars in 64..maxTotalTranscriptChars) {
            "maxToolCallArgumentsChars must be in 64..maxTotalTranscriptChars."
        }
        require(maxToolCallsPerResponse in 1..128) {
            "maxToolCallsPerResponse must be in 1..128."
        }
        require(maxToolDefinitions in 1..512) { "maxToolDefinitions must be in 1..512." }
        require(maxToolNameChars in 1..256) { "maxToolNameChars must be in 1..256." }
        require(maxToolDescriptionChars in 0..64 * 1024) { "maxToolDescriptionChars is out of range." }
        require(maxToolParametersChars in 2..256 * 1024) { "maxToolParametersChars is out of range." }
        require(maxPromptedToolCatalogChars in 1_024..1024 * 1024) {
            "maxPromptedToolCatalogChars must be in 1 KiB..1 MiB."
        }
        require(maxPromptedEnvelopeChars in 512..1024 * 1024) {
            "maxPromptedEnvelopeChars must be in 512 B..1 MiB."
        }
        require(maxPromptedToolCalls in 1..128) { "maxPromptedToolCalls must be in 1..128." }
        require(maxOutputTokens in 1..1_000_000) { "maxOutputTokens must be in 1..1_000_000." }
        require(admissionTimeoutMillis in 1_000L..600_000L) {
            "admissionTimeoutMillis must be in 1000..600000."
        }
        require(maxErrorMessageChars in 64..4_096) { "maxErrorMessageChars must be in 64..4096." }
        require(cloudContextWindowTokens in 1_024..4_000_000) {
            "cloudContextWindowTokens must be in 1024..4000000."
        }
        require(cloudMaxOutputTokens in 0..1_000_000) {
            "cloudMaxOutputTokens must be 0..1000000."
        }
    }

    companion object {
        val Default: DshAdapterLimits = DshAdapterLimits()
    }
}

/**
 * Stable adapter-local failure codes reported through [com.muyuchat.core.dsh.contract.DshModelEvent.Failed].
 * They are lower snake case so the gateway can pass them through unchanged.
 */
internal object DshAdapterErrorCodes {
    const val INVALID_REQUEST = "dsh_invalid_request"
    const val DUPLICATE_CALL_ID = "dsh_duplicate_call_id"
    const val MODEL_NOT_FOUND = "dsh_model_not_found"
    const val MODEL_NOT_AVAILABLE = "dsh_model_not_available"
    const val RESOURCE_BUSY = "dsh_resource_busy"
    const val REQUEST_TOO_LARGE = "dsh_request_too_large"
    const val TOOL_SCHEMA_INVALID = "dsh_tool_schema_invalid"
    const val TOOL_CATALOG_TOO_LARGE = "dsh_tool_catalog_too_large"
    const val TOOL_CALL_INVALID = "dsh_tool_call_invalid"
    const val TOOL_ARGUMENTS_TOO_LARGE = "dsh_tool_arguments_too_large"
    const val TOOL_HISTORY_INVALID = "dsh_tool_history_invalid"
    const val TOOL_REQUIRED_MISSING = "dsh_tool_required_missing"
    const val PROMPTED_TOOL_UNKNOWN_NAME = "dsh_prompted_tool_unknown_name"
    const val PROMPTED_TOOL_MALFORMED = "dsh_prompted_tool_malformed"
    const val PROMPTED_TOOL_PARALLEL_FORBIDDEN = "dsh_prompted_tool_parallel_forbidden"
    const val CONTEXT_LENGTH_EXCEEDED = "dsh_context_length_exceeded"
    const val MODEL_CONFIGURATION_CONFLICT = "dsh_model_configuration_conflict"
    const val MODEL_GENERATION_FAILED = "dsh_model_generation_failed"
    const val BACKEND_STREAM_CLOSED = "dsh_backend_stream_closed"
    const val CLOUD_NOT_CONFIGURED = "dsh_cloud_not_configured"
    const val CLOUD_MODEL_ERROR = "dsh_cloud_model_error"
    const val CLOUD_HTTP_ERROR_PREFIX = "dsh_cloud_http_"
}
