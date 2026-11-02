package com.muyuchat.api.local

/**
 * The advertised Local API endpoint table.
 *
 * Moved verbatim out of [LocalApiCapabilityRegistry]. It is pure data: field names,
 * auth_required flags, support levels, error codes and notes are the outward discovery
 * contract, and the order of this list is the order clients receive in `endpoints`.
 */
internal val mcaLocalApiCapabilityEntries: List<LocalApiEndpointCapability> = listOf(
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
        acceptedFields = LocalResponsesCompat.acceptedFields,
        notes = listOf(
            "Input is text or an array of role/content messages with input_text parts; unsupported fields and parts are rejected before dispatch.",
            "Streaming emits numbered typed Responses events and a response.completed or response.failed terminal event.",
            "Reasoning controls and reasoning output items are outside this text subset and are rejected when requested.",
            "Tool calls, hosted tools, response continuation and stored-response retrieval are not implemented."
        ),
        errorCodes = listOf(
            "invalid_request", "unsupported_parameter", "parameter_scope_conflict", "model_not_found", "runtime_busy",
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
            "loras", "vae_tiling", "textual_inversion_ids", "ultrafix"
        ),
        notes = listOf(
            "Runtime/model capability and execution evidence are validated before native dispatch.",
            "Unsupported task modes, samplers, image counts and response formats are rejected.",
            "Optional X-MCA-Request-Id identifies the in-flight image request for scoped stop; the response echoes it.",
            // preview is absent from acceptedFields on purpose, and this note is why. The
            // request parser rejects any preview field outright with unsupported_preview --
            // synchronous generation has no preview path -- so listing it as accepted told
            // clients the opposite of what happens. The field stays named in the parser's
            // REQUEST_FIELDS so that sending it still fails as unsupported_preview rather
            // than as an unknown field; the rejection behaviour and the error code are
            // unchanged, only the claim in this registry is.
            "Synchronous generation does not support preview: a preview field is rejected with unsupported_preview, and this endpoint publishes no preview frames."
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
        path = "/v1/video",
        support = LocalApiCapabilitySupport.PARTIAL,
        authRequired = true,
        streaming = false,
        description = "Authenticated local video-generation subset (Wan2.2 TI2V-5B).",
        acceptedFields = listOf(
            "prompt", "width", "height", "frames", "fps", "steps", "seed", "engine", "backend",
            "ditPath", "vaePath", "tePath", "loras"
        ),
        notes = listOf(
            "Synchronous: the response is written after the run reaches a terminal state.",
            "engine accepts \"\" (built-in MCA engine) or \"sdcpp_external\" (external stable-diffusion.cpp backend); any other value is rejected with unsupported_video_engine.",
            "When engine is absent or empty the native request does not contain an engine field at all, so the built-in path is byte-identical to the pre-engine protocol.",
            "backend selects the external engine transport: \"htp\" (default) or \"cpu\"; it is only valid together with engine=sdcpp_external and is rejected otherwise instead of being ignored.",
            "ditPath/vaePath/tePath are optional confirmations and must name the manifest-resolved files this device loads; any other path is rejected with video_model_path_mismatch.",
            "loras is an optional array of {path, weight} LoRA entries (at most 16, weight in (0, 8]); it is only accepted with engine=sdcpp_external and every path must be a staged regular file, otherwise the run is rejected with invalid_loras or loras_requires_external_engine.",
            "Optionally identify the request with X-MCA-Request-Id; the response echoes it and a client disconnect cancels that run through the single video owner."
        ),
        errorCodes = listOf(
            "invalid_json", "unsupported_parameter", "invalid_prompt", "prompt_too_long",
            "invalid_size", "invalid_frames", "invalid_fps", "invalid_steps", "invalid_seed",
            "unsupported_video_engine", "unsupported_video_backend",
            "backend_requires_external_engine", "invalid_loras", "loras_requires_external_engine",
            "video_runtime_busy", "video_control_plane_failed",
            "video_runtime_unavailable", "video_generation_busy", "video_model_not_ready",
            "video_model_path_mismatch", "video_generation_cancelled", "video_generation_timeout",
            "video_generation_failed", "unauthorized"
        )
    ),
    LocalApiEndpointCapability(
        method = "POST",
        path = "/v1/generate/stop",
        support = LocalApiCapabilitySupport.SUPPORTED,
        authRequired = true,
        streaming = false,
        description = "Stops the current generation or one matching request_id.",
        acceptedFields = listOf("request_id"),
        notes = listOf(
            "With request_id, only the matching active Local API request is stopped; stale IDs do not affect later requests.",
            "Without request_id, the legacy operation stops the generation current when this call is received."
        ),
        errorCodes = listOf("unauthorized", "invalid_request", "invalid_request_id", "unsupported_parameter", "generation_not_active", "stop_failed")
    ),
    LocalApiEndpointCapability(
        method = "GET",
        path = LocalApiCapabilityRegistry.PATH,
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
    ),

    // ---------------------------------------------------------------------------------
    // Appended entries: routes the router serves that were missing from this registry.
    //
    // Everything above this line is the original, frozen contract. It is append-only:
    // clients already read those nine entries, so none of them may be edited or removed.
    //
    // `authRequired` mirrors the router exactly. A route is advertised as key-free only
    // when McaLoopbackServer.handle serves it before the API-key check (the GET chat
    // page) or when McaLoopbackServer.isPublicRoute lists it. Every other route needs
    // the key. LocalApiCapabilityRegistryCoverageTest re-derives that set from the
    // router source, so a route cannot silently drift open or drift undocumented.
    //
    // `errorCodes` lists the codes the route can actually produce: a key-free route
    // never advertises `unauthorized`, and `not_found` is listed where the route's own
    // absence is the only application-level failure a client can observe for it.
    // ---------------------------------------------------------------------------------
    LocalApiEndpointCapability(
        method = "HEAD",
        path = "/health",
        support = LocalApiCapabilitySupport.SUPPORTED,
        authRequired = false,
        streaming = false,
        description = "Header-only health probe.",
        notes = listOf(
            "Key-free like GET /health: the router's public-route predicate matches HEAD /health.",
            "Answers 204 No Content, so there is no payload to parse."
        ),
        errorCodes = listOf("not_found")
    ),
    LocalApiEndpointCapability(
        method = "GET",
        path = "/",
        support = LocalApiCapabilitySupport.SUPPORTED,
        authRequired = false,
        streaming = false,
        description = "Built-in local chat page (HTML).",
        notes = listOf(
            "Served by the router before the API-key check, so it is reachable without a key.",
            "This is a human-facing page, not a JSON API; the model chat contract is /v1/chat/completions."
        ),
        errorCodes = listOf("not_found")
    ),
    LocalApiEndpointCapability(
        method = "GET",
        path = "/index.html",
        support = LocalApiCapabilitySupport.SUPPORTED,
        authRequired = false,
        streaming = false,
        description = "Alias of the built-in local chat page.",
        notes = listOf("Same handler and response as GET /."),
        errorCodes = listOf("not_found")
    ),
    LocalApiEndpointCapability(
        method = "GET",
        path = "/models",
        support = LocalApiCapabilitySupport.SUPPORTED,
        authRequired = false,
        streaming = false,
        description = "Alias of /v1/models.",
        notes = listOf(
            "Key-free like /v1/models: the router's public-route predicate matches GET /models.",
            "Same handler and payload as /v1/models."
        ),
        errorCodes = listOf("runtime_unavailable")
    ),
    LocalApiEndpointCapability(
        method = "GET",
        path = "/metrics",
        support = LocalApiCapabilitySupport.SUPPORTED,
        authRequired = true,
        streaming = false,
        description = "Redacted native metrics, request correlation and coordinator signatures.",
        notes = listOf(
            "Absolute paths, prompts, sessions and credentials are removed at this boundary.",
            "Returns text/plain, not JSON, and requires the API key."
        ),
        errorCodes = listOf("unauthorized", "request_failed")
    ),
    LocalApiEndpointCapability(
        method = "GET",
        path = "/v1/mca/device",
        support = LocalApiCapabilitySupport.PARTIAL,
        authRequired = true,
        streaming = false,
        description = "Device profile reported by the runtime.",
        notes = listOf(
            "The payload is produced by the runtime-registered device-profile provider; this module forwards it and cannot guarantee the response shape.",
            "Device evidence is descriptive only and is never an admission gate for any route."
        ),
        errorCodes = listOf("unauthorized", "request_failed")
    ),
    LocalApiEndpointCapability(
        method = "GET",
        path = "/v1/mca/tuning",
        support = LocalApiCapabilitySupport.SUPPORTED,
        authRequired = true,
        streaming = false,
        description = "Public tuning state with private fields removed.",
        errorCodes = listOf("unauthorized", "runtime_unavailable")
    ),
    LocalApiEndpointCapability(
        method = "GET",
        path = "/v1/images/textual-inversions",
        support = LocalApiCapabilitySupport.SUPPORTED,
        authRequired = true,
        streaming = false,
        description = "Image-extension inventory available for the image endpoints.",
        notes = listOf(
            "Only the fields needed to select an artifact are published; app-private paths and compatibility fingerprints are discarded."
        ),
        errorCodes = listOf("unauthorized", "runtime_unavailable")
    ),
    LocalApiEndpointCapability(
        method = "GET",
        path = "/images/textual-inversions",
        support = LocalApiCapabilitySupport.SUPPORTED,
        authRequired = true,
        streaming = false,
        description = "Alias of /v1/images/textual-inversions.",
        notes = listOf("Same handler and payload as /v1/images/textual-inversions."),
        errorCodes = listOf("unauthorized", "runtime_unavailable")
    ),
    LocalApiEndpointCapability(
        method = "POST",
        path = "/v1/tuning/jobs",
        support = LocalApiCapabilitySupport.SUPPORTED,
        authRequired = true,
        streaming = false,
        description = "Creates a tuning job and returns its initial state.",
        acceptedFields = listOf("modelId", "mode", "autoApply", "performancePreference"),
        notes = listOf(
            "Only the fields listed here are accepted; unsupported fields are rejected.",
            "mode accepts quick, standard, deep or power_save.",
            "Idempotency-Key is required and an exact retry replays the original response while reuse for a different request is rejected.",
            "A transient lifecycle conflict is reported before dispatch and does not consume the key."
        ),
        errorCodes = listOf(
            "unauthorized", "idempotency_key_required", "invalid_idempotency_key",
            "idempotency_key_conflict", "invalid_json", "unsupported_tuning_fields",
            "invalid_tuning_mode", "invalid_auto_apply", "invalid_performance_preference",
            "lifecycle_conflict", "control_plane_invalid_response", "request_failed"
        )
    ),
    LocalApiEndpointCapability(
        method = "POST",
        path = "/v1/tuning/rollback",
        support = LocalApiCapabilitySupport.SUPPORTED,
        authRequired = true,
        streaming = false,
        description = "Rolls the active tuning state back.",
        notes = listOf(
            "The request body must be empty or an empty JSON object.",
            "Idempotency-Key is required and an exact retry replays the original response while reuse for a different request is rejected."
        ),
        errorCodes = listOf(
            "unauthorized", "idempotency_key_required", "invalid_idempotency_key",
            "idempotency_key_conflict", "invalid_json", "unsupported_tuning_fields",
            "lifecycle_conflict", "control_plane_invalid_response", "request_failed"
        )
    ),
    LocalApiEndpointCapability(
        method = "GET",
        path = "/v1/tuning/jobs/{jobId}",
        support = LocalApiCapabilitySupport.SUPPORTED,
        authRequired = true,
        streaming = false,
        description = "Read-only state of one tuning job.",
        notes = listOf(
            "{jobId} is a literal placeholder: substitute a real job id, which may contain letters, digits, dot, underscore, colon or hyphen.",
            "Reads through the runtime coordinator and does not use the generation busy gate, so it stays available while a candidate is loading or being recovered.",
            "The single segment `current` selects the current job."
        ),
        errorCodes = listOf(
            "unauthorized", "invalid_tuning_job_route", "control_plane_invalid_response",
            "request_failed"
        )
    ),
    LocalApiEndpointCapability(
        method = "POST",
        path = "/v1/tuning/jobs/{jobId}",
        support = LocalApiCapabilitySupport.SUPPORTED,
        authRequired = true,
        streaming = false,
        description = "Applies a pause, resume, cancel or apply transition to one tuning job.",
        notes = listOf(
            "{jobId} is a literal placeholder: substitute a real job id, which may contain letters, digits, dot, underscore, colon or hyphen.",
            "The router also accepts a two-segment form /v1/tuning/jobs/{jobId}/{action} where action is pause, resume, cancel or apply; no other action is accepted.",
            "The body must be empty or an empty JSON object.",
            "`current` is rejected here: a transition needs a concrete job id.",
            "Idempotency-Key is required and an exact retry replays the original response while reuse for a different request is rejected."
        ),
        errorCodes = listOf(
            "unauthorized", "invalid_tuning_job_route", "invalid_tuning_job_id",
            "idempotency_key_required", "invalid_idempotency_key", "idempotency_key_conflict",
            "invalid_json", "unsupported_tuning_fields", "tuning_action_not_found",
            "lifecycle_conflict", "control_plane_invalid_response", "request_failed"
        )
    ),
    LocalApiEndpointCapability(
        method = "POST",
        path = "/v1/mca/recommend",
        support = LocalApiCapabilitySupport.PARTIAL,
        authRequired = true,
        streaming = false,
        description = "Runtime agent recommendation for the posted request body.",
        notes = listOf(
            "The response payload is produced by the runtime-registered advisor provider; this module forwards the body and returns that JSON, so it cannot guarantee the response shape.",
            "A recommendation is advisory and never an admission gate for any route."
        ),
        errorCodes = listOf("unauthorized", "request_failed")
    ),
    LocalApiEndpointCapability(
        method = "POST",
        path = "/v1/mca/benchmark",
        support = LocalApiCapabilitySupport.PARTIAL,
        authRequired = true,
        streaming = false,
        description = "Runtime benchmark result for the posted request body.",
        notes = listOf(
            "The response payload is produced by the runtime-registered benchmark provider; this module forwards the body and returns that JSON, so it cannot guarantee the response shape.",
            "Benchmark results are advisory and never an admission gate for any route."
        ),
        errorCodes = listOf("unauthorized", "request_failed")
    ),
    LocalApiEndpointCapability(
        method = "POST",
        path = "/chat/completions",
        support = LocalApiCapabilitySupport.PARTIAL,
        authRequired = true,
        streaming = true,
        description = "Alias of /v1/chat/completions.",
        notes = listOf(
            "Same handler, field set and SSE behaviour as /v1/chat/completions; see that entry for the accepted fields.",
            "Requires a JSON object body, a model id and a non-empty messages array."
        ),
        errorCodes = listOf(
            "invalid_request", "parameter_scope_conflict", "invalid_model", "model_not_found",
            "runtime_busy", "context_length_exceeded", "generation_failed", "generation_cancelled",
            "unauthorized"
        )
    ),
    LocalApiEndpointCapability(
        method = "POST",
        path = "/v1/completions",
        support = LocalApiCapabilitySupport.PARTIAL,
        authRequired = true,
        streaming = true,
        description = "Completion-compatible alias of /v1/chat/completions.",
        notes = listOf(
            "Same handler, field set and SSE behaviour as /v1/chat/completions; see that entry for the accepted fields.",
            "Requires a JSON object body and a model id, but does not require a non-empty messages array."
        ),
        errorCodes = listOf(
            "invalid_request", "parameter_scope_conflict", "invalid_model", "model_not_found",
            "runtime_busy", "context_length_exceeded", "generation_failed", "generation_cancelled",
            "unauthorized"
        )
    ),
    LocalApiEndpointCapability(
        method = "POST",
        path = "/completion",
        support = LocalApiCapabilitySupport.PARTIAL,
        authRequired = true,
        streaming = true,
        description = "Legacy text-completion compatibility route.",
        notes = listOf(
            "Handled by the /v1/chat/completions pipeline, but this route is the permissive one: it accepts a non-JSON body and does not require a model id.",
            "Kept for existing local clients; new clients should use /v1/chat/completions.",
            "SSE is available when stream=true or the client requests text/event-stream."
        ),
        errorCodes = listOf(
            "invalid_request", "parameter_scope_conflict", "invalid_model", "model_not_found",
            "runtime_busy", "context_length_exceeded", "generation_failed", "generation_cancelled",
            "unauthorized", "preflight_failed"
        )
    ),
    LocalApiEndpointCapability(
        method = "POST",
        path = "/responses",
        support = LocalApiCapabilitySupport.PARTIAL,
        authRequired = true,
        streaming = true,
        description = "Alias of /v1/responses.",
        acceptedFields = LocalResponsesCompat.acceptedFields,
        notes = listOf(
            "Same handler, field set and streaming events as /v1/responses.",
            "Input is text or an array of role/content messages with input_text parts; unsupported fields and parts are rejected before dispatch."
        ),
        errorCodes = listOf(
            "invalid_request", "unsupported_parameter", "parameter_scope_conflict", "model_not_found", "runtime_busy",
            "context_length_exceeded", "generation_failed", "generation_cancelled", "unauthorized"
        )
    ),
    LocalApiEndpointCapability(
        method = "POST",
        path = "/images/generations",
        support = LocalApiCapabilitySupport.PARTIAL,
        authRequired = true,
        streaming = false,
        description = "Alias of /v1/images/generations.",
        notes = listOf(
            "Same handler, accepted fields and error semantics as /v1/images/generations; see that entry for the field list.",
            "Synchronous generation does not support preview: a preview field is rejected with unsupported_preview."
        ),
        errorCodes = listOf(
            "invalid_json", "unsupported_sampler", "unsupported_image_count",
            "unsupported_response_format", "unsupported_preview", "image_model_not_ready",
            "image_generation_busy", "image_worker_unavailable", "image_native_execution_failed",
            "unauthorized"
        )
    )
)
