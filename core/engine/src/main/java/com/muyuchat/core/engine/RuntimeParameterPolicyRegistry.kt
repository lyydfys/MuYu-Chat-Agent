package com.muyuchat.core.engine

/**
 * The single field policy registry. Unknown fields fail closed as UNSUPPORTED.
 * More-specific runtime/native rules override the built-in family contract.
 */
class ParameterFieldPolicyRegistry(
    private val overrides: List<VersionedParameterPolicySet> = emptyList()
) {
@ConsistentCopyVisibility
    data class RuntimeView internal constructor(
        val identity: ModelRuntimeIdentity,
        val policyVersion: String,
        private val policies: Map<String, ParameterFieldPolicy>
    ) {
        fun policy(field: String): ParameterFieldPolicy = policies[field] ?: unsupportedPolicy(field)

        fun policies(): Map<String, ParameterFieldPolicy> = policies.toMap()
    }

    fun forRuntime(identity: ModelRuntimeIdentity): RuntimeView {
        val base = builtIn(identity.runtime)
        val selected = overrides
            .filter { it.matches(identity) }
            .maxWithOrNull(compareBy<VersionedParameterPolicySet> { it.specificity }
                .thenBy { it.runtimeVersionPrefix?.length ?: 0 }
                .thenBy { it.nativeLibrarySha256Prefix?.length ?: 0 })
        return RuntimeView(
            identity = identity,
            policyVersion = selected?.policyVersion ?: base.policyVersion,
            policies = if (selected == null) base.policies else base.policies + selected.policies
        )
    }

    fun forRuntime(
        runtime: LocalChatRuntime,
        runtimeVersion: String,
        nativeLibrarySha256: String
    ): RuntimeView = forRuntime(
        ModelRuntimeIdentity(
            modelId = "policy-probe",
            artifactFingerprint = "policy-probe",
            runtime = runtime,
            runtimeVersion = runtimeVersion,
            nativeLibrarySha256 = nativeLibrarySha256
        )
    )

    companion object {
        const val BUILTIN_POLICY_VERSION = "runtime-parameters-v2-sparse-moe-mmap"

        private fun unsupportedPolicy(field: String) = ParameterFieldPolicy(
            field = field,
            owner = ParameterOwner.MODEL_EXECUTION,
            mutability = ParameterMutability.UNSUPPORTED,
            apiOverridePolicy = ParameterApiOverridePolicy.NEVER,
            evidence = "unregistered-field"
        )

        private fun builtIn(runtime: LocalChatRuntime): VersionedParameterPolicySet =
            VersionedParameterPolicySet(
                runtime = runtime,
                policyVersion = BUILTIN_POLICY_VERSION,
                policies = when (runtime) {
                    LocalChatRuntime.LLAMA_CPP -> llamaPolicies()
                    LocalChatRuntime.GENIEX_LLAMA_CPP -> llamaPolicies(LocalChatRuntime.GENIEX_LLAMA_CPP) + mapOf(
                        "geniex_compute_unit" to ParameterFieldPolicy(
                            field = "geniex_compute_unit",
                            owner = ParameterOwner.MODEL_EXECUTION,
                            mutability = ParameterMutability.LOAD_BOUND,
                            apiOverridePolicy = ParameterApiOverridePolicy.TRUSTED_PROFILE_ONLY
                        )
                    )
                    LocalChatRuntime.MNN_CPU -> mnnPolicies()
                    LocalChatRuntime.GENIEX_QAIRT -> qairtPolicies()
                    LocalChatRuntime.LITERT_LM -> litertLmPolicies()
                }
            )

        private fun llamaPolicies(runtime: LocalChatRuntime = LocalChatRuntime.LLAMA_CPP): Map<String, ParameterFieldPolicy> = buildMap {
            loadFields(
                "n_ctx", "n_batch", "n_ubatch", "n_gpu_layers", "main_gpu", "split_mode",
                "n_cpu_moe", "cache_type_k", "cache_type_v", "flash_attn", "perf", "n_parallel",
                "spec_type", "spec_draft_n_max", "mmap", "mlock", "mmproj_path"
            )
            hotFields("n_threads", "n_threads_batch", "cache_reuse")
            behaviorHotField("use_jinja", "template-correctness")
            behaviorHotField("chat_template_mode", "template-correctness")
            behaviorHotField("template_policy_ref", "template-correctness")
            generationFields(runtime)
        }

        private fun mnnPolicies(): Map<String, ParameterFieldPolicy> = buildMap {
            loadFields(
                "n_ctx", "backend", "precision", "memory", "power", "mmap", "kvcache_mmap",
                "processor", "visual_model", "is_visual", "bundle_fingerprint"
            )
            hotFields("n_threads", "thread_num", "chunk")
            behaviorHotField("use_jinja", "template-correctness")
            behaviorHotField("chat_template_mode", "template-correctness")
            behaviorHotField("template_policy_ref", "template-correctness")
            generationFields(LocalChatRuntime.MNN_CPU)
            put(
                "mca_debug_trace",
                ParameterFieldPolicy(
                    field = "mca_debug_trace",
                    owner = ParameterOwner.SESSION_DIAGNOSTIC,
                    mutability = ParameterMutability.GENERATION_ONLY,
                    apiOverridePolicy = ParameterApiOverridePolicy.REQUEST_ALLOWED,
                    affectsSemantics = false,
                    evidence = "explicit-debug-smoke-opt-in"
                )
            )
        }

        private fun qairtPolicies(): Map<String, ParameterFieldPolicy> = buildMap {
            loadFields("bundle_fingerprint", "backend", "runtime_id", "context_binary", "shape_profile")
            behaviorHotField("chat_template_mode", "template-correctness")
            behaviorHotField("template_policy_ref", "template-correctness")
            generationFields(LocalChatRuntime.GENIEX_QAIRT)
        }

        /**
         * LiteRT-LM creates an immutable Engine from these values. Keep the
         * constructor inputs load-bound so a request cannot silently change
         * the backend or context allocation of an active session. CPU thread
         * count is also part of Backend.CPU and therefore remains load-bound.
         */
        private fun litertLmPolicies(): Map<String, ParameterFieldPolicy> = buildMap {
            loadFields("backend", "max_num_tokens", "cache_dir", "n_threads")
            behaviorHotField("chat_template_mode", "template-correctness")
            behaviorHotField("template_policy_ref", "template-correctness")
            generationFields(LocalChatRuntime.LITERT_LM)
        }

        private fun MutableMap<String, ParameterFieldPolicy>.loadFields(vararg fields: String) {
            fields.forEach { field ->
                put(
                    field,
                    ParameterFieldPolicy(
                        field = field,
                        owner = ParameterOwner.MODEL_EXECUTION,
                        mutability = ParameterMutability.LOAD_BOUND,
                        apiOverridePolicy = ParameterApiOverridePolicy.TRUSTED_PROFILE_ONLY
                    )
                )
            }
        }

        private fun MutableMap<String, ParameterFieldPolicy>.hotFields(vararg fields: String) {
            fields.forEach { field ->
                put(
                    field,
                    ParameterFieldPolicy(
                        field = field,
                        owner = ParameterOwner.MODEL_EXECUTION,
                        mutability = ParameterMutability.HOT_EXECUTION,
                        apiOverridePolicy = ParameterApiOverridePolicy.TRUSTED_RUNTIME_OVERRIDE
                    )
                )
            }
        }

        private fun MutableMap<String, ParameterFieldPolicy>.behaviorHotField(
            field: String,
            gate: String
        ) {
            put(
                field,
                ParameterFieldPolicy(
                    field = field,
                    owner = ParameterOwner.MODEL_EXECUTION,
                    mutability = ParameterMutability.HOT_EXECUTION,
                    apiOverridePolicy = ParameterApiOverridePolicy.TRUSTED_PROFILE_ONLY,
                    affectsSemantics = true,
                    requiredGate = gate
                )
            )
        }

        private fun MutableMap<String, ParameterFieldPolicy>.generationFields(runtime: LocalChatRuntime) {
            listOf(
                "n_predict", "max_tokens", "temperature", "top_k", "top_p", "min_p",
                "repeat_penalty", "repetition_penalty", "presence_penalty", "frequency_penalty",
                "seed", "system_prompt", "stop_words", "stop",
                "reasoning_mode", "enable_thinking", "thinking_budget", "hide_reasoning"
            ).forEach { field ->
                val support = when {
                    field in setOf("system_prompt", "hide_reasoning", "reasoning_mode", "stop_words", "stop") ->
                        ParameterExecutionSupport.HOST_ENFORCED
                    runtime == LocalChatRuntime.LITERT_LM && field == "min_p" ->
                        ParameterExecutionSupport.UNSUPPORTED
                    runtime == LocalChatRuntime.LITERT_LM && field in setOf("temperature", "top_k", "top_p", "seed") ->
                        ParameterExecutionSupport.UNKNOWN
                    runtime in setOf(LocalChatRuntime.GENIEX_QAIRT, LocalChatRuntime.GENIEX_LLAMA_CPP) && field == "thinking_budget" ->
                        ParameterExecutionSupport.UNSUPPORTED
                    else -> ParameterExecutionSupport.NATIVE
                }
                val evidence = when {
                    runtime == LocalChatRuntime.LITERT_LM && field == "min_p" -> "litertlm-0.16.1:no-min-p-api"
                    runtime == LocalChatRuntime.LITERT_LM && support == ParameterExecutionSupport.UNKNOWN ->
                        "litertlm-0.16.1:conversation-sampler-cpu-gpu-only; inspect request trace for delegate"
                    runtime in setOf(LocalChatRuntime.GENIEX_QAIRT, LocalChatRuntime.GENIEX_LLAMA_CPP) && field == "thinking_budget" ->
                        "geniex-0.10.0:template-enable-thinking-only"
                    support == ParameterExecutionSupport.HOST_ENFORCED -> "mca-request-context-and-stream-policy"
                    else -> "adapter-submission; native acknowledgement is separate"
                }
                put(
                    field,
                    ParameterFieldPolicy(
                        field = field,
                        owner = ParameterOwner.ASSISTANT_GENERATION,
                        mutability = ParameterMutability.GENERATION_ONLY,
                        apiOverridePolicy = ParameterApiOverridePolicy.REQUEST_ALLOWED,
                        affectsSemantics = true,
                        evidence = evidence,
                        executionSupport = support
                    )
                )
            }
        }
    }
}
