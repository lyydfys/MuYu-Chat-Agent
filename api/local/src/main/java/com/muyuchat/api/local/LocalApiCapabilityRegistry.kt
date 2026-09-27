package com.muyuchat.api.local

import org.json.JSONArray
import org.json.JSONObject

/**
 * The public contract advertised by the authenticated Local API.
 *
 * Keep this registry close to the router so documentation and clients can inspect the same
 * method/path, authentication, streaming and field-subset semantics that the production server
 * actually exposes.  A capability describes protocol scope; model/runtime readiness remains
 * dynamic and is reported by /v1/models and /v1/mca/runtime.
 */
enum class LocalApiCapabilitySupport {
    SUPPORTED,
    PARTIAL,
    UNSUPPORTED
}

data class LocalApiEndpointCapability(
    val method: String,
    val path: String,
    val support: LocalApiCapabilitySupport,
    val authRequired: Boolean,
    val streaming: Boolean,
    val description: String,
    val acceptedFields: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val errorCodes: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("method", method)
        .put("path", path)
        .put("support", support.name.lowercase())
        .put("auth_required", authRequired)
        .put("stream", streaming)
        .put("description", description)
        .put("fields", JSONArray(acceptedFields))
        .put("notes", JSONArray(notes))
        .put("error_codes", JSONArray(errorCodes))
}

object LocalApiCapabilityRegistry {
    const val SCHEMA = "mca.local-api.capabilities.v1"
    const val PATH = "/v1/mca/capabilities"

    private val entries = listOf(
        LocalApiEndpointCapability(
            method = "GET",
            path = "/health",
            support = LocalApiCapabilitySupport.SUPPORTED,
            authRequired = false,
            streaming = false,
            description = "Process health check.",
            errorCodes = listOf("unauthorized", "not_found")
        ),
        LocalApiEndpointCapability(
            method = "GET",
            path = "/v1/models",
            support = LocalApiCapabilitySupport.SUPPORTED,
            authRequired = false,
            streaming = false,
            description = "Loaded/catalog models and runtime-specific readiness.",
            errorCodes = listOf("unauthorized", "runtime_unavailable")
        ),
        LocalApiEndpointCapability(
            method = "POST",
            path = "/v1/chat/completions",
            support = LocalApiCapabilitySupport.PARTIAL,
            authRequired = true,
            streaming = true,
            description = "OpenAI-compatible local chat subset.",
            acceptedFields = listOf(
                "model", "messages", "prompt", "input", "stream", "temperature", "top_p", "top_k",
                "min_p", "repeat_penalty", "repetition_penalty", "presence_penalty",
                "frequency_penalty", "max_tokens", "max_completion_tokens", "n_predict", "stop",
                "stop_words", "seed", "system_prompt", "reasoning_mode", "thinking_mode",
                "enable_thinking", "show_reasoning", "hide_reasoning"
            ),
            notes = listOf(
                "Only the fields listed here are accepted; unsupported fields are rejected.",
                "SSE is available when stream=true or the client requests text/event-stream.",
                "Actual model/runtime availability is reported separately by /v1/models."
            ),
            errorCodes = listOf(
                "invalid_request", "parameter_scope_conflict", "invalid_model", "model_not_found",
                "runtime_busy", "context_length_exceeded", "generation_failed", "generation_cancelled",
                "unauthorized"
            )
        ),
        LocalApiEndpointCapability(
            method = "POST",
            path = "/v1/responses",
            support = LocalApiCapabilitySupport.PARTIAL,
            authRequired = true,
            streaming = true,
            description = "OpenAI Responses-compatible text/message subset.",
            acceptedFields = listOf(
                "model", "instructions", "input", "stream", "max_output_tokens", "temperature",
                "top_p", "stop", "reasoning_mode", "thinking_mode", "enable_thinking",
                "hide_reasoning"
            ),
            notes = listOf(
                "Only text and message input parts are supported; unsupported input parts are rejected.",
                "Tool calls, hosted tools and arbitrary Responses fields are not implemented."
            ),
            errorCodes = listOf(
                "invalid_request", "parameter_scope_conflict", "model_not_found", "runtime_busy",
                "context_length_exceeded", "generation_failed", "generation_cancelled", "unauthorized"
            )
        ),
        LocalApiEndpointCapability(
            method = "POST",
            path = "/v1/images/generations",
            support = LocalApiCapabilitySupport.PARTIAL,
            authRequired = true,
            streaming = false,
            description = "Authenticated local image-generation subset.",
            acceptedFields = listOf(
                "model", "prompt", "negative_prompt", "sampler", "size", "n", "response_format",
                "steps", "cfg_scale", "seed", "task_mode", "input_image", "source_image",
                "mask_image", "mask", "control_image", "strength", "control_strength", "clip_skip",
                "loras", "vae_tiling", "textual_inversion_ids", "ultrafix", "preview"
            ),
            notes = listOf(
                "Runtime/model capability and execution evidence are validated before native dispatch.",
                "Unsupported task modes, samplers, image counts and response formats are rejected."
            ),
            errorCodes = listOf(
                "invalid_json", "unsupported_sampler", "unsupported_image_count",
                "unsupported_response_format", "unsupported_preview", "image_model_not_ready",
                "image_generation_busy", "image_worker_unavailable", "image_native_execution_failed",
                "unauthorized"
            )
        ),
        LocalApiEndpointCapability(
            method = "POST",
            path = "/v1/generate/stop",
            support = LocalApiCapabilitySupport.SUPPORTED,
            authRequired = true,
            streaming = false,
            description = "Stops the active chat or image generation request.",
            errorCodes = listOf("unauthorized", "generation_not_active", "stop_failed")
        ),
        LocalApiEndpointCapability(
            method = "GET",
            path = PATH,
            support = LocalApiCapabilitySupport.SUPPORTED,
            authRequired = true,
            streaming = false,
            description = "This endpoint: protocol capability registry for the authenticated Local API.",
            notes = listOf(
                "Capability support is protocol scope, not a per-device or per-model admission list."
            ),
            errorCodes = listOf("unauthorized", "not_found")
        ),
        LocalApiEndpointCapability(
            method = "GET",
            path = "/v1/mca/runtime",
            support = LocalApiCapabilitySupport.SUPPORTED,
            authRequired = true,
            streaming = false,
            description = "Current worker/coordinator lifecycle state.",
            errorCodes = listOf("unauthorized", "runtime_unavailable")
        ),
        LocalApiEndpointCapability(
            method = "GET",
            path = "/v1/mca/profile",
            support = LocalApiCapabilitySupport.SUPPORTED,
            authRequired = true,
            streaming = false,
            description = "Redacted active runtime profile and parameter evidence.",
            errorCodes = listOf("unauthorized", "runtime_unavailable")
        )
    )

    fun all(): List<LocalApiEndpointCapability> = entries

    fun json(): String = JSONObject()
        .put("schema", SCHEMA)
        .put("version", 1)
        .put("generated_by", "McaLoopbackServer")
        .put("endpoints", JSONArray().also { array -> entries.forEach { array.put(it.toJson()) } })
        .put(
            "support_values",
            JSONArray(LocalApiCapabilitySupport.entries.map { it.name.lowercase() })
        )
        .toString()
}
