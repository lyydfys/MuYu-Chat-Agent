package com.muyuchat.core.engine

import java.math.BigDecimal
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class ParameterOwner {
    MODEL_EXECUTION,
    ASSISTANT_GENERATION,
    SESSION_DIAGNOSTIC,
    INTERNAL_CANARY
}

enum class ParameterMutability {
    LOAD_BOUND,
    HOT_EXECUTION,
    GENERATION_ONLY,
    UNSUPPORTED
}

enum class ParameterApiOverridePolicy {
    TRUSTED_PROFILE_ONLY,
    TRUSTED_RUNTIME_OVERRIDE,
    REQUEST_ALLOWED,
    NEVER
}

data class ParameterFieldPolicy(
    val field: String,
    val owner: ParameterOwner,
    val mutability: ParameterMutability,
    val apiOverridePolicy: ParameterApiOverridePolicy,
    val affectsSemantics: Boolean = false,
    val requiredGate: String? = null,
    val evidence: String = "product-contract"
)

data class VersionedParameterPolicySet(
    val runtime: LocalChatRuntime,
    val runtimeVersionPrefix: String? = null,
    val nativeLibrarySha256Prefix: String? = null,
    val policyVersion: String,
    val policies: Map<String, ParameterFieldPolicy>
) {
    internal fun matches(identity: ModelRuntimeIdentity): Boolean =
        runtime == identity.runtime &&
            (runtimeVersionPrefix == null || identity.runtimeVersion.startsWith(runtimeVersionPrefix)) &&
            (nativeLibrarySha256Prefix == null ||
                identity.nativeLibrarySha256.startsWith(nativeLibrarySha256Prefix, ignoreCase = true))

    internal val specificity: Int
        get() = (if (runtimeVersionPrefix != null) 1 else 0) +
            (if (nativeLibrarySha256Prefix != null) 2 else 0)
}

/**
 * The single field policy registry. Unknown fields fail closed as UNSUPPORTED.
 * More-specific runtime/native rules override the built-in family contract.
 */
class ParameterFieldPolicyRegistry(
    private val overrides: List<VersionedParameterPolicySet> = emptyList()
) {
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
                    LocalChatRuntime.GENIEX_LLAMA_CPP -> llamaPolicies() + mapOf(
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

        private fun llamaPolicies(): Map<String, ParameterFieldPolicy> = buildMap {
            loadFields(
                "n_ctx", "n_batch", "n_ubatch", "n_gpu_layers", "main_gpu", "split_mode",
                "n_cpu_moe", "cache_type_k", "cache_type_v", "flash_attn", "perf", "n_parallel",
                "spec_type", "spec_draft_n_max", "mmap", "mlock", "mmproj_path"
            )
            hotFields("n_threads", "n_threads_batch", "cache_reuse")
            behaviorHotField("use_jinja", "template-correctness")
            behaviorHotField("chat_template_mode", "template-correctness")
            behaviorHotField("template_policy_ref", "template-correctness")
            generationFields()
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
            generationFields()
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
            generationFields()
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
            generationFields()
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

        private fun MutableMap<String, ParameterFieldPolicy>.generationFields() {
            listOf(
                "n_predict", "max_tokens", "temperature", "top_k", "top_p", "min_p",
                "repeat_penalty", "repetition_penalty", "presence_penalty", "frequency_penalty",
                "seed", "system_prompt", "stop_words", "stop",
                "reasoning_mode", "enable_thinking", "thinking_budget", "hide_reasoning"
            ).forEach { field ->
                put(
                    field,
                    ParameterFieldPolicy(
                        field = field,
                        owner = ParameterOwner.ASSISTANT_GENERATION,
                        mutability = ParameterMutability.GENERATION_ONLY,
                        apiOverridePolicy = ParameterApiOverridePolicy.REQUEST_ALLOWED,
                        affectsSemantics = true
                    )
                )
            }
        }
    }
}

data class ModelRuntimeIdentity(
    val modelId: String,
    val artifactFingerprint: String,
    val runtime: LocalChatRuntime,
    val runtimeVersion: String = "unknown",
    val nativeLibrarySha256: String = "unknown",
    val abi: String = "arm64-v8a",
    val backendFingerprint: String = "",
    val projectorFingerprint: String = "",
    val bundleFingerprint: String = "",
    val tokenizerFingerprint: String = "",
    val templateFingerprint: String = "",
    val deviceCapabilityFingerprint: String = "",
    val installationScopeId: String = "local-installation",
    val ruleSetFingerprint: String = "",
    val evaluatorFingerprint: String = "",
    val engineContractVersion: String = "1",
    val schemaFingerprint: String = "runtime-parameters-schema-v1",
    val parameterPolicyVersion: String = ParameterFieldPolicyRegistry.BUILTIN_POLICY_VERSION,
    val capabilities: Set<String> = emptySet()
) {
    init {
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        require(artifactFingerprint.isNotBlank()) { "artifactFingerprint must not be blank" }
    }

    val identityHash: String = sha256(
        listOf(
            modelId,
            artifactFingerprint,
            runtime.name,
            runtimeVersion,
            nativeLibrarySha256.lowercase(),
            abi,
            backendFingerprint,
            projectorFingerprint,
            bundleFingerprint,
            tokenizerFingerprint,
            templateFingerprint,
            deviceCapabilityFingerprint,
            installationScopeId,
            ruleSetFingerprint,
            evaluatorFingerprint,
            engineContractVersion,
            schemaFingerprint,
            parameterPolicyVersion,
            capabilities.toSortedSet().joinToString(",")
        ).joinToString("\n", transform = ::escapeCanonical)
    )
}

/** Scalar/JSON values encoded with their type so 1, 1.0, true, and "1" never collide. */
data class CanonicalParameterSet private constructor(
    val encodedValues: Map<String, String>
) {
    val fields: Set<String>
        get() = encodedValues.keys

    fun value(field: String): Any? = encodedValues[field]?.let(::decodeValue)

    fun toJsonObject(): JSONObject = JSONObject().also { target ->
        encodedValues.toSortedMap().forEach { (field, value) -> target.put(field, decodeValue(value)) }
    }

    fun plus(other: CanonicalParameterSet): CanonicalParameterSet =
        fromEncoded(encodedValues + other.encodedValues)

    fun only(fields: Set<String>): CanonicalParameterSet =
        fromEncoded(encodedValues.filterKeys(fields::contains))

    fun differences(other: CanonicalParameterSet): Set<String> =
        (fields + other.fields).filterTo(linkedSetOf()) { encodedValues[it] != other.encodedValues[it] }

    companion object {
        val EMPTY = CanonicalParameterSet(emptyMap())

        fun of(values: Map<String, Any?>): CanonicalParameterSet =
            CanonicalParameterSet(values.toSortedMap().mapValues { encodeValue(it.value) })

        fun fromJson(root: JSONObject): CanonicalParameterSet = buildMap<String, Any?> {
            root.keys().asSequence().toList().sorted().forEach { key -> put(key, root.opt(key)) }
        }.let(::of)

        fun fromEncoded(values: Map<String, String>): CanonicalParameterSet =
            CanonicalParameterSet(values.toSortedMap())
    }
}

data class QuarantinedOverride(
    val field: String,
    val rawJson: String,
    val reason: String
)

interface RuntimeParameterSignature {
    val identityHash: String
    val values: CanonicalParameterSet
    val digest: String
}

data class DesiredProfileSignature private constructor(
    override val identityHash: String,
    override val values: CanonicalParameterSet,
    override val digest: String
) : RuntimeParameterSignature {
    companion object {
        fun of(identity: ModelRuntimeIdentity, values: CanonicalParameterSet): DesiredProfileSignature {
            val safe = signatureSafeValues(identity, values)
            return DesiredProfileSignature(identity.identityHash, safe, signatureDigest("desired", identity, safe))
        }
    }
}

data class ResolvedLoadSignature private constructor(
    override val identityHash: String,
    override val values: CanonicalParameterSet,
    override val digest: String
) : RuntimeParameterSignature {
    companion object {
        fun of(identity: ModelRuntimeIdentity, values: CanonicalParameterSet): ResolvedLoadSignature {
            val safe = signatureSafeValues(identity, values)
            return ResolvedLoadSignature(identity.identityHash, safe, signatureDigest("resolved-load", identity, safe))
        }
    }
}

data class ActiveLoadedSignature private constructor(
    override val identityHash: String,
    override val values: CanonicalParameterSet,
    override val digest: String
) : RuntimeParameterSignature {
    companion object {
        fun of(identity: ModelRuntimeIdentity, values: CanonicalParameterSet): ActiveLoadedSignature {
            val safe = signatureSafeValues(identity, values)
            return ActiveLoadedSignature(identity.identityHash, safe, signatureDigest("active-loaded", identity, safe))
        }
    }
}

data class CommittedExecutionSignature private constructor(
    override val identityHash: String,
    override val values: CanonicalParameterSet,
    override val digest: String
) : RuntimeParameterSignature {
    companion object {
        fun of(identity: ModelRuntimeIdentity, values: CanonicalParameterSet): CommittedExecutionSignature {
            val safe = signatureSafeValues(identity, values)
            return CommittedExecutionSignature(
                identity.identityHash,
                safe,
                signatureDigest("committed-execution", identity, safe)
            )
        }
    }
}

data class RuntimeOverrideSignature private constructor(
    override val identityHash: String,
    override val values: CanonicalParameterSet,
    override val digest: String,
    val isNone: Boolean
) : RuntimeParameterSignature {
    companion object {
        fun none(identity: ModelRuntimeIdentity) =
            RuntimeOverrideSignature(identity.identityHash, CanonicalParameterSet.EMPTY, "NONE", true)

        fun of(identity: ModelRuntimeIdentity, values: CanonicalParameterSet): RuntimeOverrideSignature {
            if (values.fields.isEmpty()) return none(identity)
            val safe = signatureSafeValues(identity, values)
            return RuntimeOverrideSignature(
                identity.identityHash,
                safe,
                signatureDigest("runtime-override", identity, safe),
                false
            )
        }
    }
}

data class EffectiveExecutionSignature private constructor(
    override val identityHash: String,
    override val values: CanonicalParameterSet,
    override val digest: String
) : RuntimeParameterSignature {
    companion object {
        fun of(
            identity: ModelRuntimeIdentity,
            active: ActiveLoadedSignature,
            committed: CommittedExecutionSignature,
            override: RuntimeOverrideSignature
        ): EffectiveExecutionSignature {
            require(listOf(active.identityHash, committed.identityHash, override.identityHash)
                .all { it == identity.identityHash }) {
                "signature identity mismatch: expected=${identity.identityHash}, " +
                    "active=${active.identityHash}, committed=${committed.identityHash}, override=${override.identityHash}"
            }
            val values = active.values.plus(committed.values).plus(override.values)
            return EffectiveExecutionSignature(
                identity.identityHash,
                values,
                signatureDigest("effective-execution", identity, values)
            )
        }
    }
}

data class ParameterSignatureSnapshot(
    val desired: DesiredProfileSignature,
    val resolved: ResolvedLoadSignature,
    val active: ActiveLoadedSignature?,
    val committed: CommittedExecutionSignature,
    val override: RuntimeOverrideSignature,
    val effective: EffectiveExecutionSignature?
)

data class ModelExecutionProfile(
    val schemaVersion: Int = 1,
    val modelId: String,
    val runtimeIdentity: ModelRuntimeIdentity,
    val desiredLoadBoundValues: CanonicalParameterSet,
    val resolvedLoadBoundValues: CanonicalParameterSet,
    val hotExecutionValues: CanonicalParameterSet,
    val desiredHotExecutionValues: CanonicalParameterSet = hotExecutionValues,
    val modelBehaviorValues: CanonicalParameterSet = CanonicalParameterSet.EMPTY,
    val desiredModelBehaviorValues: CanonicalParameterSet = modelBehaviorValues,
    val profileId: String = UUID.randomUUID().toString(),
    val revision: Long = 1,
    val userOverrides: Set<String> = emptySet(),
    /** Unsupported or legacy fields retained for redacted diagnostics, never native dispatch. */
    val quarantinedOverrides: List<QuarantinedOverride> = emptyList(),
    val resolvedAt: Long = System.currentTimeMillis()
) {
    init {
        require(modelId == runtimeIdentity.modelId) { "profile modelId must match runtime identity" }
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        require(revision > 0) { "revision must be positive" }
    }

    val desiredSignature: DesiredProfileSignature = DesiredProfileSignature.of(
        runtimeIdentity,
        desiredLoadBoundValues.plus(desiredHotExecutionValues).plus(desiredModelBehaviorValues)
    )
    val resolvedLoadSignature: ResolvedLoadSignature =
        ResolvedLoadSignature.of(runtimeIdentity, resolvedLoadBoundValues)
    val committedExecutionSignature: CommittedExecutionSignature =
        CommittedExecutionSignature.of(runtimeIdentity, hotExecutionValues.plus(modelBehaviorValues))
}

data class ParameterResolution(
    val requested: DesiredProfileSignature,
    val resolved: ResolvedLoadSignature,
    val profile: ModelExecutionProfile,
    val sourceByField: Map<String, String>,
    val warnings: List<String>,
    val reloadRequired: Boolean,
    val rejectedOverrides: List<String>,
    val quarantinedOverrides: List<QuarantinedOverride>
)

data class RuntimeParameterPartition(
    val loadBound: CanonicalParameterSet,
    val hotExecution: CanonicalParameterSet,
    val modelBehavior: CanonicalParameterSet,
    val generationJson: String,
    val quarantinedOverrides: List<QuarantinedOverride>,
    val warnings: List<String>
)

interface RuntimeParameterAdapter {
    val runtime: LocalChatRuntime
    val registry: ParameterFieldPolicyRegistry

    fun partition(identity: ModelRuntimeIdentity, rawJson: String): RuntimeParameterPartition

    fun resolveLoadProfile(
        identity: ModelRuntimeIdentity,
        rawJson: String,
        profileId: String = UUID.randomUUID().toString(),
        revision: Long = 1,
        activeLoadSignature: ActiveLoadedSignature? = null
    ): ParameterResolution

    fun activeLoadedSignature(
        identity: ModelRuntimeIdentity,
        nativeStatsJson: String,
        expected: ResolvedLoadSignature
    ): ActiveLoadedSignature?

    /**
     * A path-redacted comparison used only to explain a rejected native
     * readback. It does not alter admission or relax the active signature.
     */
    fun loadSignatureDiagnostic(
        identity: ModelRuntimeIdentity,
        nativeStatsJson: String,
        expected: ResolvedLoadSignature
    ): JSONObject

    fun nativeCompletionJson(
        partition: RuntimeParameterPartition,
        profile: ModelExecutionProfile,
        override: RuntimeOverrideSignature
    ): String

    fun nativeLoadJson(profile: ModelExecutionProfile): String

    fun isLoadSignatureMismatch(beginReturnCode: Int, nativeError: String? = null): Boolean
}

class LlamaCppRuntimeParameterAdapter(
    registry: ParameterFieldPolicyRegistry = ParameterFieldPolicyRegistry()
) : BaseRuntimeParameterAdapter(LocalChatRuntime.LLAMA_CPP, registry) {
    // A 256-token floor skipped cache reuse for ordinary short chats. Sixteen
    // common tokens avoid a trivial BOS-only reuse while preserving the full
    // validated prefix for a typical multi-turn prompt.
    override fun runtimeDefaults(identity: ModelRuntimeIdentity): Pair<Map<String, Any?>, Map<String, Any?>> =
        mapOf(
            "n_ctx" to 4096,
            "n_batch" to 512,
            "n_ubatch" to 512,
            "n_gpu_layers" to if ("gpu_offload" in identity.capabilities) -1 else 0,
            "main_gpu" to 0,
            "split_mode" to if ("gpu_offload" in identity.capabilities) "layer" else "none",
            "n_cpu_moe" to 0,
            "cache_type_k" to "f16",
            "cache_type_v" to "f16",
            "flash_attn" to "auto",
            "perf" to false,
            "n_parallel" to 1,
            "spec_type" to "none",
            "spec_draft_n_max" to 0,
            "mmap" to true,
            "mlock" to false
        ) to mapOf("n_threads" to 1, "n_threads_batch" to 1, "cache_reuse" to 16)

    override fun normalize(
        identity: ModelRuntimeIdentity,
        requestedLoad: MutableMap<String, Any?>,
        requestedHot: MutableMap<String, Any?>,
        warnings: MutableList<String>,
        sourceByField: MutableMap<String, String>
    ) {
        fun normalizeLoad(field: String, safeValue: Any?, reason: String) {
            if (requestedLoad[field] != safeValue) {
                warnings += "$field normalized to $safeValue because $reason"
            }
            requestedLoad[field] = safeValue
            sourceByField[field] = "runtime-safety"
        }

        if ("gpu_offload" !in identity.capabilities) {
            mapOf(
                "n_gpu_layers" to 0,
                "main_gpu" to 0,
                "n_cpu_moe" to 0,
                "split_mode" to "none"
            ).forEach { (field, safeValue) ->
                if (requestedLoad[field] != safeValue) {
                    warnings += "$field normalized to $safeValue because this runtime has no GPU offload capability"
                }
                requestedLoad[field] = safeValue
                sourceByField[field] = "runtime-safety"
            }
        }

        // llama.cpp routes prompt prefill through n_threads_batch. A profile
        // that only specifies n_threads previously inherited the one-thread
        // runtime default here, making prefill dramatically slower than decode.
        // Preserve an explicit batch-thread choice, but align the implicit
        // default with the selected decode worker count.
        if (sourceByField["n_threads_batch"] == "runtime-default") {
            val threads = (requestedHot["n_threads"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
            if (requestedHot["n_threads_batch"] != threads) {
                requestedHot["n_threads_batch"] = threads
                sourceByField["n_threads_batch"] = "runtime-default:aligned-with-n_threads"
                warnings += "n_threads_batch aligned to n_threads=$threads for prompt prefill"
            }
        }

        if ((requestedLoad["n_gpu_layers"] as? Number)?.toInt() == 0) {
            normalizeLoad(
                "split_mode",
                "none",
                "n_gpu_layers=0 disables GPU layer splitting"
            )
        }

        if ("sparse_moe" in identity.capabilities) {
            normalizeLoad("mmap", true, "sparse MoE weights must remain file-backed")
            normalizeLoad("mlock", false, "sparse MoE file pages must stay reclaimable")
        }
        if ("sparse_moe_16gb_tier" in identity.capabilities) {
            val requestedCtx = (requestedLoad["n_ctx"] as? Number)?.toInt() ?: 4096
            val requestedBatch = (requestedLoad["n_batch"] as? Number)?.toInt() ?: 512
            val requestedUbatch = (requestedLoad["n_ubatch"] as? Number)?.toInt() ?: 512
            // Sparse-MoE weights remain file-backed, so total parameter count is
            // not a valid reason to silently replace a user-selected context.
            // Admission and the real load can still reject an unsafe value, but
            // an explicit 8K/16K request must reach native unchanged so users can
            // trade available memory for context on 12/16 GB devices.
            normalizeLoad("n_ctx", requestedCtx.coerceAtLeast(1), "context must be a positive token count")
            normalizeLoad("n_batch", requestedBatch.coerceIn(1, 2048), "this sparse MoE is running in the <=16GB memory tier")
            normalizeLoad("n_ubatch", requestedUbatch.coerceIn(1, 256), "this sparse MoE is running in the <=16GB memory tier")
            normalizeLoad("n_parallel", 1, "the <=16GB sparse-MoE tier keeps one sequence")
        }
        if ("verified_q4_kv_cache" in identity.capabilities) {
            normalizeLoad("cache_type_k", "q4_0", "this exact model/runtime has a verified low-memory KV profile")
            normalizeLoad("cache_type_v", "q4_0", "this exact model/runtime has a verified low-memory KV profile")
            normalizeLoad("flash_attn", "on", "quantized V cache requires flash attention")
        }

        val nBatch = (requestedLoad["n_batch"] as? Number)?.toInt() ?: 512
        val nUbatch = (requestedLoad["n_ubatch"] as? Number)?.toInt() ?: 512
        if (nUbatch > nBatch || nBatch % nUbatch != 0) {
            val upper = if ("sparse_moe_16gb_tier" in identity.capabilities) {
                minOf(nBatch, 256)
            } else {
                nBatch
            }
            val divisor = (upper downTo 1).first { nBatch % it == 0 }
            requestedLoad["n_ubatch"] = divisor
            sourceByField["n_ubatch"] = "dependency-normalization"
            warnings += "n_ubatch normalized to $divisor because it must divide n_batch=$nBatch"
        }
        val specType = requestedLoad["spec_type"]?.toString() ?: "none"
        if (specType == "none") requestedLoad["spec_draft_n_max"] = 0
        if (specType == "draft-mtp" && "draft_mtp" !in identity.capabilities) {
            requestedLoad["spec_type"] = "none"
            requestedLoad["spec_draft_n_max"] = 0
            requestedHot["cache_reuse"] = 0
            sourceByField["spec_type"] = "runtime-safety"
            warnings += "draft-mtp disabled because the loaded model/runtime did not report an MTP capability"
        }
    }

    override fun activeValuesFromStats(root: JSONObject): JSONObject? =
        root.optJSONObject("effectiveConfig")?.let { config ->
            JSONObject(config.toString()).apply {
                root.optString("mmprojPath").takeIf { it.isNotBlank() }?.let { put("mmproj_path", it) }
            }
        }

    override fun activeLoadedSignature(
        identity: ModelRuntimeIdentity,
        nativeStatsJson: String,
        expected: ResolvedLoadSignature
    ): ActiveLoadedSignature? {
        if (identity.runtime == LocalChatRuntime.GENIEX_LLAMA_CPP) {
            val root = runCatching { JSONObject(nativeStatsJson) }.getOrNull() ?: return null
            if (!root.optBoolean("loaded", false) ||
                !root.optString("backend").contains("geniex", ignoreCase = true)
            ) return null
            // GenieX 0.3.x does not expose a typed config readback. Its create
            // call is synchronous and immutable; admission plus the exact
            // sanitized create JSON is the strongest available P0 evidence.
            return ActiveLoadedSignature.of(identity, expected.values)
        }
        super.activeLoadedSignature(identity, nativeStatsJson, expected)?.let { return it }

        // llama.cpp keeps the caller's logical context limit, but pads the
        // physical context allocation to a 256-token boundary. Preserve the
        // user's exact n_ctx in desired/resolved/active signatures and accept
        // only that deterministic native realization. Every other load-bound
        // field remains fail-closed and must read back exactly.
        if (expected.identityHash != identity.identityHash) return null
        val root = runCatching { JSONObject(nativeStatsJson) }.getOrNull() ?: return null
        if (!root.optBoolean("loaded", true)) return null
        val raw = activeValuesFromStats(root) ?: return null
        val observed = partition(identity, raw.toString()).loadBound.only(expected.values.fields)
        val observedSafe = signatureSafeValues(identity, observed)
        if (observedSafe.fields != expected.values.fields) return null
        val differences = observedSafe.differences(expected.values)
        val nativeCpuFallback = isNativeAutoGpuFallback(expected.values, observedSafe)
        val allowedDifferences = if (nativeCpuFallback) {
            setOf("n_ctx", "n_gpu_layers", "split_mode", "n_cpu_moe")
        } else {
            setOf("n_ctx")
        }
        if (differences.any { it !in allowedDifferences }) return null
        if ("n_ctx" in differences) {
            val requestedCtx = (expected.values.value("n_ctx") as? Number)?.toInt() ?: return null
            val nativeCtx = (observedSafe.value("n_ctx") as? Number)?.toInt() ?: return null
            if (nativeCtx != nativeAlignedContext(requestedCtx)) return null
        }
        return ActiveLoadedSignature.of(identity, expected.values)
    }

    override fun isLoadSignatureMismatch(beginReturnCode: Int, nativeError: String?): Boolean =
        beginReturnCode == NativeRuntimeErrorCodes.LOAD_SIGNATURE_MISMATCH ||
            nativeError.orEmpty().contains("load-bound fields", ignoreCase = true)

    private companion object {
        const val LLAMA_CONTEXT_ALIGNMENT = 256

        fun isNativeAutoGpuFallback(
            expected: CanonicalParameterSet,
            observed: CanonicalParameterSet
        ): Boolean =
            (expected.value("n_gpu_layers") as? Number)?.toInt() == -1 &&
                (observed.value("n_gpu_layers") as? Number)?.toInt() == 0 &&
                observed.value("split_mode")?.toString() == "none" &&
                (expected.value("n_cpu_moe") as? Number)?.toInt()?.let { it >= 0 } == true &&
                (observed.value("n_cpu_moe") as? Number)?.toInt() == 0

        fun nativeAlignedContext(nCtx: Int): Int {
            if (nCtx <= 0 || nCtx % LLAMA_CONTEXT_ALIGNMENT == 0) return nCtx
            return (((nCtx.toLong() + LLAMA_CONTEXT_ALIGNMENT - 1L) / LLAMA_CONTEXT_ALIGNMENT) *
                LLAMA_CONTEXT_ALIGNMENT)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }
    }
}

class MnnRuntimeParameterAdapter(
    registry: ParameterFieldPolicyRegistry = ParameterFieldPolicyRegistry()
) : BaseRuntimeParameterAdapter(LocalChatRuntime.MNN_CPU, registry) {
    override val aliases: Map<String, String> = mapOf(
        "max_all_tokens" to "n_ctx",
        "thread_num" to "n_threads",
        "backend_type" to "backend",
        "use_mmap" to "mmap",
        "topK" to "top_k",
        "topP" to "top_p",
        "minP" to "min_p"
    )

    override val advancedOverrideFields = setOf("temperature", "top_k", "top_p", "min_p", "seed")

    override fun runtimeDefaults(identity: ModelRuntimeIdentity): Pair<Map<String, Any?>, Map<String, Any?>> =
        mapOf(
            "n_ctx" to 8192,
            "backend" to "cpu",
            "precision" to "low",
            "memory" to "low",
            "power" to "normal",
            "mmap" to true,
            "kvcache_mmap" to true
        ) to mapOf("n_threads" to 4, "chunk" to 128)

    override fun normalize(
        identity: ModelRuntimeIdentity,
        requestedLoad: MutableMap<String, Any?>,
        requestedHot: MutableMap<String, Any?>,
        warnings: MutableList<String>,
        sourceByField: MutableMap<String, String>
    ) {
        // The native MNN bridge owns the effective precision per transport:
        // CPU uses high precision to avoid non-finite logits from the low-
        // precision Arm kernels, while OpenCL retains the validated low path.
        // Normalize the signed profile to that same effective value so load
        // readback cannot reject an otherwise valid CPU session.
        val backend = requestedLoad["backend"]
            ?.toString()
            ?.trim()
            ?.lowercase()
            ?.replace('-', '_')
        val effectiveBackend = when (backend) {
            "gpu", "opencl", "open_cl" -> "opencl"
            else -> "cpu"
        }
        if (requestedLoad["backend"] != effectiveBackend) {
            requestedLoad["backend"] = effectiveBackend
            sourceByField["backend"] = "runtime-safety:mnn-transport"
        }
        val effectivePrecision = if (effectiveBackend == "opencl") "low" else "high"
        if (!effectivePrecision.equals(requestedLoad["precision"]?.toString(), ignoreCase = true)) {
            warnings += "precision normalized to $effectivePrecision for MNN $effectiveBackend runtime"
            requestedLoad["precision"] = effectivePrecision
            sourceByField["precision"] = "runtime-safety:mnn-precision"
        }
        if (requestedLoad["is_visual"] == false) {
            // false is only a metadata hint, not a native execution field;
            // omit it so text bundles do not fail readback on runtimes that
            // simply leave the key out.
            requestedLoad.remove("is_visual")
        }
        val hasDeclaredVisionComponent = requestedLoad["visual_model"]
            ?.toString()
            ?.trim()
            ?.isNotEmpty() == true
        val isVisionProfile = hasDeclaredVisionComponent || requestedLoad["is_visual"] == true ||
            identity.capabilities.any { capability ->
                capability.equals("vision", ignoreCase = true) ||
                    capability.equals("multimodal", ignoreCase = true) ||
                    capability.startsWith("mnn_vision", ignoreCase = true)
            }
        if (!isVisionProfile) return

        // MNN's visual session owns additional model/KV mappings. Current native
        // builds force both mappings off for visual bundles; resolving that rule
        // here keeps desired/resolved/native readback signatures consistent.
        listOf("mmap", "kvcache_mmap").forEach { field ->
            if (requestedLoad[field] != false) {
                warnings += "$field normalized to false for an MNN visual profile"
            }
            requestedLoad[field] = false
            sourceByField[field] = "runtime-safety:mnn-vision"
        }
    }

    override fun activeValuesFromStats(root: JSONObject): JSONObject? {
        val raw = root.opt("loadedConfigJson") ?: root.opt("lastConfigJson") ?: return null
        val config = when (raw) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        } ?: return null
        return JSONObject().apply {
            config.keys().forEach { source ->
                val target = aliases[source] ?: source
                put(target, config.opt(source))
            }
        }
    }

    override fun nativeCompletionJson(
        partition: RuntimeParameterPartition,
        profile: ModelExecutionProfile,
        override: RuntimeOverrideSignature
    ): String {
        // MNN rebuilds a complete config at beginCompletion. Materialize the
        // already-resolved load signature so native can fail closed rather than
        // silently falling back to different defaults.
        val generation = JSONObject(partition.generationJson)
        return JSONObject(nativeLoadJson(profile)).apply {
            if (generation.has(MNN_DEBUG_TRACE_FIELD)) {
                val advanced = optJSONObject("advanced_json") ?: JSONObject()
                advanced.put(MNN_DEBUG_TRACE_FIELD, generation.opt(MNN_DEBUG_TRACE_FIELD))
                put("advanced_json", advanced)
                generation.remove(MNN_DEBUG_TRACE_FIELD)
            }
            mergeJson(generation)
            mergeJson(profile.hotExecutionValues.plus(override.values).toJsonObject())
            mergeJson(profile.modelBehaviorValues.toJsonObject())
        }.toString()
    }

    override fun nativeLoadJson(profile: ModelExecutionProfile): String {
        val load = profile.resolvedLoadBoundValues.toJsonObject()
        val hot = profile.hotExecutionValues.toJsonObject()
        val advanced = JSONObject()
        listOf("backend", "precision", "memory", "power", "kvcache_mmap", "processor", "visual_model")
            .forEach { field ->
                if (load.has(field)) {
                    val nativeField = when (field) {
                        "backend" -> "backend_type"
                        else -> field
                    }
                    advanced.put(nativeField, load.opt(field))
                    load.remove(field)
                }
            }
        load.mergeJson(hot)
        if (advanced.length() > 0) load.put("advanced_json", advanced)
        return load.toString()
    }

    override fun isLoadSignatureMismatch(beginReturnCode: Int, nativeError: String?): Boolean =
        beginReturnCode == NativeRuntimeErrorCodes.LOAD_SIGNATURE_MISMATCH ||
            nativeError.orEmpty().contains("load signature", ignoreCase = true)

    private companion object {
        const val MNN_DEBUG_TRACE_FIELD = "mca_debug_trace"
    }
}

class QairtRuntimeParameterAdapter(
    registry: ParameterFieldPolicyRegistry = ParameterFieldPolicyRegistry()
) : BaseRuntimeParameterAdapter(LocalChatRuntime.GENIEX_QAIRT, registry) {
    override fun runtimeDefaults(identity: ModelRuntimeIdentity): Pair<Map<String, Any?>, Map<String, Any?>> =
        mapOf("backend" to "qairt") to emptyMap()

    override fun activeValuesFromStats(root: JSONObject): JSONObject? =
        root.optJSONObject("effectiveConfig")

    override fun activeLoadedSignature(
        identity: ModelRuntimeIdentity,
        nativeStatsJson: String,
        expected: ResolvedLoadSignature
    ): ActiveLoadedSignature? {
        val root = runCatching { JSONObject(nativeStatsJson) }.getOrNull() ?: return null
        val backend = root.optString("backend")
        val devices = root.opt("backendDevices")?.toString().orEmpty()
        if (!root.optBoolean("loaded", false) ||
            !backend.contains("qairt", ignoreCase = true) ||
            !(devices.contains("qairt", ignoreCase = true) &&
                (devices.contains("htp", ignoreCase = true) || devices.contains("npu", ignoreCase = true)))
        ) return null
        return ActiveLoadedSignature.of(identity, expected.values)
    }

    override fun isLoadSignatureMismatch(beginReturnCode: Int, nativeError: String?): Boolean =
        beginReturnCode == NativeRuntimeErrorCodes.LOAD_SIGNATURE_MISMATCH ||
            nativeError.orEmpty().contains("signature", ignoreCase = true)
}

/**
 * Parameter adapter for the Kotlin LiteRT-LM Engine API.
 *
 * LiteRT-LM does not expose the llama.cpp/MNN style native config readback on
 * every release. We still validate the concrete load state that is available
 * (the session is loaded and reports the requested backend), and use the
 * immutable, coordinator-resolved profile for the remaining fields when the
 * runner cannot echo them. This keeps profile authorization useful without
 * making a device allowlist a prerequisite for loading a model.
 */
class LiteRtLmRuntimeParameterAdapter(
    registry: ParameterFieldPolicyRegistry = ParameterFieldPolicyRegistry()
) : BaseRuntimeParameterAdapter(LocalChatRuntime.LITERT_LM, registry) {
    override val aliases: Map<String, String> = mapOf(
        "n_ctx" to "max_num_tokens",
        "maxNumTokens" to "max_num_tokens",
        "backend_type" to "backend",
        "backendType" to "backend",
        "cacheDir" to "cache_dir",
        "cache_directory" to "cache_dir",
        "thread_count" to "n_threads",
        "threadCount" to "n_threads"
    )

    override fun runtimeDefaults(identity: ModelRuntimeIdentity): Pair<Map<String, Any?>, Map<String, Any?>> =
        mapOf(
            "backend" to "cpu",
            "max_num_tokens" to 4096,
            "n_threads" to 4
        ) to emptyMap()

    override fun normalize(
        identity: ModelRuntimeIdentity,
        requestedLoad: MutableMap<String, Any?>,
        requestedHot: MutableMap<String, Any?>,
        warnings: MutableList<String>,
        sourceByField: MutableMap<String, String>
    ) {
        val rawBackend = requestedLoad["backend"]
        val normalizedBackend = canonicalBackend(rawBackend?.toString()) ?: "cpu"
        if (rawBackend != null &&
            !canonicalBackend(rawBackend.toString()).equals(normalizedBackend, ignoreCase = true)
        ) {
            warnings += "backend normalized to $normalizedBackend because LiteRT-LM supports cpu, gpu, npu, or google_tensor"
        } else if (rawBackend != null && rawBackend.toString() != normalizedBackend) {
            warnings += "backend normalized to $normalizedBackend"
        }
        requestedLoad["backend"] = normalizedBackend
        sourceByField["backend"] = if (rawBackend == null) "runtime-default" else "runtime-safety"

        val rawMaxTokens = requestedLoad["max_num_tokens"]
        val maxTokens = integerValue(rawMaxTokens)
        if (maxTokens == null || maxTokens < 1) {
            requestedLoad["max_num_tokens"] = 4096
            sourceByField["max_num_tokens"] = "runtime-safety"
            if (rawMaxTokens != null) {
                warnings += "max_num_tokens normalized to 4096 because it must be a positive integer"
            }
        } else {
            requestedLoad["max_num_tokens"] = maxTokens
        }

        val rawThreads = requestedLoad["n_threads"]
        val threads = integerValue(rawThreads)
        if (threads == null || threads < 1) {
            requestedLoad["n_threads"] = 4
            sourceByField["n_threads"] = "runtime-safety"
            if (rawThreads != null) {
                warnings += "n_threads normalized to 4 because it must be a positive integer"
            }
        } else {
            requestedLoad["n_threads"] = threads
        }

        val rawCacheDir = requestedLoad["cache_dir"]
        if (rawCacheDir == null || rawCacheDir == JSONObject.NULL || rawCacheDir.toString().trim().isEmpty()) {
            requestedLoad.remove("cache_dir")
            sourceByField.remove("cache_dir")
        } else if (rawCacheDir !is String) {
            requestedLoad["cache_dir"] = rawCacheDir.toString()
        }
    }

    override fun activeValuesFromStats(root: JSONObject): JSONObject? {
        val config = configObject(root) ?: JSONObject()
        val values = linkedMapOf<String, Any?>()

        fun copyKnown(source: JSONObject, onlyIfAbsent: Boolean = false) {
            source.keys().forEach { key ->
                val target = aliases[key] ?: key
                if (!onlyIfAbsent || !values.containsKey(target)) {
                    values[target] = source.opt(key)
                }
            }
        }

        copyKnown(config)
        // Some LiteRT-LM releases put backend/effective limits at the stats
        // root instead of inside effectiveConfig. Preserve either shape.
        copyKnown(root, onlyIfAbsent = true)

        if (values.isEmpty()) return null
        return JSONObject().apply {
            values.forEach { (field, value) ->
                when (field) {
                    "backend" -> canonicalBackend(value?.toString())?.let { put(field, it) }
                    "max_num_tokens", "n_threads" -> integerValue(value)?.let { put(field, it) }
                    else -> if (value != null && value != JSONObject.NULL) put(field, value)
                }
            }
        }
    }

    override fun activeLoadedSignature(
        identity: ModelRuntimeIdentity,
        nativeStatsJson: String,
        expected: ResolvedLoadSignature
    ): ActiveLoadedSignature? {
        if (expected.identityHash != identity.identityHash) return null
        val root = runCatching { JSONObject(nativeStatsJson) }.getOrNull() ?: return null
        if (!root.optBoolean("loaded", false)) return null

        val observed = activeValuesFromStats(root)
        val observedBackend = observed?.optString("backend")
            ?.takeIf { it.isNotBlank() }
            ?: root.optString("backend").takeIf { it.isNotBlank() }
        val expectedBackend = expected.values.value("backend")?.toString() ?: "cpu"
        if (observedBackend == null ||
            canonicalBackend(observedBackend) != canonicalBackend(expectedBackend)
        ) return null

        // Prefer exact readback when the runner supplies effectiveConfig. A
        // valid loaded/backend witness is sufficient for LiteRT-LM builds that
        // do not expose the other EngineConfig fields in their stats payload.
        super.activeLoadedSignature(identity, nativeStatsJson, expected)
            ?.let { return it }
        return ActiveLoadedSignature.of(identity, expected.values)
    }

    override fun isLoadSignatureMismatch(beginReturnCode: Int, nativeError: String?): Boolean =
        beginReturnCode == NativeRuntimeErrorCodes.LOAD_SIGNATURE_MISMATCH ||
            nativeError.orEmpty().contains("load signature", ignoreCase = true)

    private fun configObject(root: JSONObject): JSONObject? {
        val raw = root.opt("effectiveConfig")
            ?: root.opt("loadedConfigJson")
            ?: root.opt("lastConfigJson")
            ?: root.opt("config")
        return when (raw) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        }
    }

    private fun integerValue(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun canonicalBackend(raw: String?): String? {
        val value = raw?.trim()?.lowercase()?.replace('-', '_') ?: return null
        return when (value) {
            "cpu", "host" -> "cpu"
            "gpu", "opencl", "open_cl" -> "gpu"
            "npu", "qnn", "qualcomm" -> "npu"
            "google_tensor", "googletensor", "tpu", "google_tensor_processor" -> "google_tensor"
            else -> null
        }
    }
}

abstract class BaseRuntimeParameterAdapter(
    final override val runtime: LocalChatRuntime,
    final override val registry: ParameterFieldPolicyRegistry
) : RuntimeParameterAdapter {
    protected open val aliases: Map<String, String> = emptyMap()
    protected open val advancedOverrideFields: Set<String> = emptySet()

    protected abstract fun runtimeDefaults(
        identity: ModelRuntimeIdentity
    ): Pair<Map<String, Any?>, Map<String, Any?>>

    protected open fun normalize(
        identity: ModelRuntimeIdentity,
        requestedLoad: MutableMap<String, Any?>,
        requestedHot: MutableMap<String, Any?>,
        warnings: MutableList<String>,
        sourceByField: MutableMap<String, String>
    ) = Unit

    protected open fun behaviorDefaults(identity: ModelRuntimeIdentity): Map<String, Any?> =
        mapOf("chat_template_mode" to "auto", "use_jinja" to true)

    protected abstract fun activeValuesFromStats(root: JSONObject): JSONObject?

    override fun partition(identity: ModelRuntimeIdentity, rawJson: String): RuntimeParameterPartition {
        require(identity.runtime == runtime ||
            (runtime == LocalChatRuntime.LLAMA_CPP && identity.runtime == LocalChatRuntime.GENIEX_LLAMA_CPP)) {
            "${identity.runtime} cannot use $runtime adapter"
        }
        val warnings = mutableListOf<String>()
        val quarantined = mutableListOf<QuarantinedOverride>()
        val root = runCatching { JSONObject(rawJson.ifBlank { "{}" }) }.getOrElse { error ->
            return RuntimeParameterPartition(
                CanonicalParameterSet.EMPTY,
                CanonicalParameterSet.EMPTY,
                CanonicalParameterSet.EMPTY,
                "{}",
                listOf(QuarantinedOverride("$", JSONObject.quote(rawJson), "invalid JSON: ${error.message}")),
                listOf("parameter JSON was invalid and was quarantined")
            )
        }
        val flattened = linkedMapOf<String, Any?>()
        val advanced = parseAdvanced(root.opt("advanced_json"), quarantined, warnings)
        advanced?.keys()?.forEach { key -> flattened[aliases[key] ?: key] = advanced.opt(key) }
        root.keys().forEach { key ->
            if (key != "advanced_json") flattened[aliases[key] ?: key] = root.opt(key)
        }
        // MNN resolves advanced sampling controls before ordinary controls.
        // Canonical names win over aliases within the same source, as in native.
        advancedOverrideFields.forEach { field ->
            for (source in listOfNotNull(advanced, root)) {
                val key = (listOf(field) + aliases.filterValues { it == field }.keys)
                    .firstOrNull { source.has(it) && !source.isNull(it) }
                if (key != null) {
                    flattened[field] = source.opt(key)
                    break
                }
            }
        }

        val view = registry.forRuntime(identity)
        val load = linkedMapOf<String, Any?>()
        val hot = linkedMapOf<String, Any?>()
        val behavior = linkedMapOf<String, Any?>()
        val generation = JSONObject()
        flattened.forEach { (field, value) ->
            if (field == LlamaAdvancedParams.SCHEMA_VERSION_KEY) return@forEach
            val policy = view.policy(field)
            when (policy.mutability) {
                ParameterMutability.LOAD_BOUND -> load[field] = value
                ParameterMutability.HOT_EXECUTION -> {
                    if (policy.affectsSemantics) behavior[field] = value else hot[field] = value
                }
                ParameterMutability.GENERATION_ONLY -> generation.put(field, value)
                ParameterMutability.UNSUPPORTED -> quarantined += QuarantinedOverride(
                    field = field,
                    rawJson = jsonValueString(value),
                    reason = "unsupported by ${identity.runtime}/${identity.runtimeVersion}/${identity.nativeLibrarySha256}"
                )
            }
        }
        return RuntimeParameterPartition(
            loadBound = CanonicalParameterSet.of(load),
            hotExecution = CanonicalParameterSet.of(hot),
            modelBehavior = CanonicalParameterSet.of(behavior),
            generationJson = generation.toString(),
            quarantinedOverrides = quarantined.sortedBy { it.field },
            warnings = warnings
        )
    }

    override fun resolveLoadProfile(
        identity: ModelRuntimeIdentity,
        rawJson: String,
        profileId: String,
        revision: Long,
        activeLoadSignature: ActiveLoadedSignature?
    ): ParameterResolution {
        val partition = partition(identity, rawJson)
        val (defaultLoad, defaultHot) = runtimeDefaults(identity)
        val desiredLoad = defaultLoad.toMutableMap().apply {
            partition.loadBound.encodedValues.forEach { (field, encoded) -> put(field, decodeValue(encoded)) }
        }
        val desiredHot = defaultHot.toMutableMap().apply {
            partition.hotExecution.encodedValues.forEach { (field, encoded) -> put(field, decodeValue(encoded)) }
        }
        val desiredBehavior = behaviorDefaults(identity).toMutableMap().apply {
            partition.modelBehavior.encodedValues.forEach { (field, encoded) -> put(field, decodeValue(encoded)) }
        }
        val resolvedLoad = desiredLoad.toMutableMap()
        val resolvedHot = desiredHot.toMutableMap()
        val warnings = partition.warnings.toMutableList()
        val sourceByField = buildMap<String, String> {
            defaultLoad.keys.forEach { put(it, "runtime-default") }
            defaultHot.keys.forEach { put(it, "runtime-default") }
            behaviorDefaults(identity).keys.forEach { put(it, "runtime-default") }
            partition.loadBound.fields.forEach { put(it, "requested-profile") }
            partition.hotExecution.fields.forEach { put(it, "requested-profile") }
            partition.modelBehavior.fields.forEach { put(it, "requested-profile") }
        }.toMutableMap()
        normalize(identity, resolvedLoad, resolvedHot, warnings, sourceByField)
        val profile = ModelExecutionProfile(
            modelId = identity.modelId,
            runtimeIdentity = identity,
            desiredLoadBoundValues = CanonicalParameterSet.of(desiredLoad),
            resolvedLoadBoundValues = CanonicalParameterSet.of(resolvedLoad),
            hotExecutionValues = CanonicalParameterSet.of(resolvedHot),
            desiredHotExecutionValues = CanonicalParameterSet.of(desiredHot),
            modelBehaviorValues = CanonicalParameterSet.of(desiredBehavior),
            desiredModelBehaviorValues = CanonicalParameterSet.of(desiredBehavior),
            profileId = profileId,
            revision = revision,
            userOverrides = partition.loadBound.fields + partition.hotExecution.fields + partition.modelBehavior.fields,
            quarantinedOverrides = partition.quarantinedOverrides
        )
        return ParameterResolution(
            requested = profile.desiredSignature,
            resolved = profile.resolvedLoadSignature,
            profile = profile,
            sourceByField = sourceByField,
            warnings = warnings,
            reloadRequired = activeLoadSignature?.let {
                it.identityHash != identity.identityHash ||
                    it.values.differences(profile.resolvedLoadSignature.values).isNotEmpty()
            } ?: true,
            rejectedOverrides = partition.quarantinedOverrides.map { it.field },
            quarantinedOverrides = partition.quarantinedOverrides
        )
    }

    open override fun activeLoadedSignature(
        identity: ModelRuntimeIdentity,
        nativeStatsJson: String,
        expected: ResolvedLoadSignature
    ): ActiveLoadedSignature? {
        if (expected.identityHash != identity.identityHash) return null
        val root = runCatching { JSONObject(nativeStatsJson) }.getOrNull() ?: return null
        if (!root.optBoolean("loaded", true)) return null
        val raw = activeValuesFromStats(root) ?: return null
        val partition = partition(identity, raw.toString())
        val observed = partition.loadBound.only(expected.values.fields)
        val observedSafe = signatureSafeValues(identity, observed)
        if (observedSafe.fields != expected.values.fields ||
            observedSafe.differences(expected.values).isNotEmpty()
        ) return null
        return ActiveLoadedSignature.of(identity, observed)
    }

    override fun loadSignatureDiagnostic(
        identity: ModelRuntimeIdentity,
        nativeStatsJson: String,
        expected: ResolvedLoadSignature
    ): JSONObject {
        val root = runCatching { JSONObject(nativeStatsJson) }.getOrNull()
        val expectedValues = expected.values
        val effective = root
            ?.let(::activeValuesFromStats)
            ?.let { raw ->
                partition(identity, raw.toString()).loadBound.only(expectedValues.fields)
            }
            ?.let { observed -> signatureSafeValues(identity, observed) }
        return JSONObject().apply {
            put("nativeLoaded", root?.optBoolean("loaded", false) ?: false)
            put("expectedLoad", expectedValues.toJsonObject())
            if (effective == null) {
                put("nativeEffectiveLoad", JSONObject.NULL)
                put("missingFields", JSONArray(expectedValues.fields.sorted()))
                put("differentFields", JSONArray(expectedValues.fields.sorted()))
            } else {
                put("nativeEffectiveLoad", effective.toJsonObject())
                put(
                    "missingFields",
                    JSONArray((expectedValues.fields - effective.fields).sorted())
                )
                put(
                    "differentFields",
                    JSONArray(effective.differences(expectedValues).sorted())
                )
            }
        }
    }

    override fun nativeCompletionJson(
        partition: RuntimeParameterPartition,
        profile: ModelExecutionProfile,
        override: RuntimeOverrideSignature
    ): String = JSONObject(partition.generationJson).apply {
        mergeJson(profile.hotExecutionValues.plus(override.values).toJsonObject())
        mergeJson(profile.modelBehaviorValues.toJsonObject())
    }.toString()

    override fun nativeLoadJson(profile: ModelExecutionProfile): String = JSONObject().apply {
        mergeJson(profile.resolvedLoadBoundValues.toJsonObject())
        mergeJson(profile.hotExecutionValues.toJsonObject())
        mergeJson(profile.modelBehaviorValues.toJsonObject())
    }.toString()

    private fun parseAdvanced(
        raw: Any?,
        quarantined: MutableList<QuarantinedOverride>,
        warnings: MutableList<String>
    ): JSONObject? = when (raw) {
        null, JSONObject.NULL -> null
        is JSONObject -> raw
        is String -> runCatching { JSONObject(raw) }.getOrElse { error ->
            quarantined += QuarantinedOverride("advanced_json", JSONObject.quote(raw), "invalid JSON: ${error.message}")
            warnings += "advanced_json was invalid and was quarantined"
            null
        }
        else -> {
            quarantined += QuarantinedOverride("advanced_json", jsonValueString(raw), "must be a JSON object")
            warnings += "advanced_json was not an object and was quarantined"
            null
        }
    }
}

object NativeRuntimeErrorCodes {
    const val LOAD_SIGNATURE_MISMATCH = -11
}

class LoadAuthorization internal constructor(
    val transactionId: String,
    val modelIdentityHash: String,
    val profileId: String,
    val revision: Long,
    val desiredProfileDigest: String,
    val resolvedLoadDigest: String,
    val committedExecutionDigest: String,
    internal val coordinatorNonce: String
)

/**
 * Opaque, coordinator-issued authorization for one exact HOT_EXECUTION value set.
 * The public Boolean compatibility flag is deliberately insufficient to create it.
 */
class HotOverrideAuthorization internal constructor(
    val profileId: String,
    val revision: Long,
    val modelIdentityHash: String,
    val fields: Set<String>,
    val overrideDigest: String,
    internal val coordinatorNonce: String
)

data class PendingLoadTransaction(
    val profile: ModelExecutionProfile,
    val authorization: LoadAuthorization,
    val rollbackTargetProfileId: String?,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Atomic, read-only proof for one exact authorized pending candidate.
 *
 * [strictlyMatches] is true only when the authorization is still the same
 * unconsumed pending transaction and all six signatures describe the candidate
 * that native code actually loaded. Candidate proof always uses
 * RuntimeOverride=NONE so an unrelated request-scoped throttle cannot leak into
 * tuning validation.
 */
data class AuthorizedPendingSignatureVerification(
    val transactionId: String,
    val profileId: String,
    val revision: Long,
    val signatures: ParameterSignatureSnapshot,
    val strictlyMatches: Boolean
)

sealed interface CompletionPreflight {
    data class Ready(
        val nativeParamsJson: String,
        val generationJson: String,
        val signatures: ParameterSignatureSnapshot,
        val quarantinedOverrides: List<QuarantinedOverride>
    ) : CompletionPreflight

    data class Rejected(
        val code: String,
        val changedFields: Set<String>,
        val quarantinedOverrides: List<QuarantinedOverride>,
        val message: String
    ) : CompletionPreflight
}

sealed interface MismatchRecoveryDecision {
    data class ReloadAuthorizedPending(
        val transaction: PendingLoadTransaction
    ) : MismatchRecoveryDecision

    data class ReloadCommittedForDrift(
        val profile: ModelExecutionProfile
    ) : MismatchRecoveryDecision

    data class Fail(val code: String, val message: String) : MismatchRecoveryDecision
}

/**
 * In-memory P0 coordinator. Persistence owns profile/journal records; this
 * class owns authorization, immutable signature publication, and retry bounds.
 */
class ParameterCoordinator(
    private val adapters: Map<LocalChatRuntime, RuntimeParameterAdapter>,
    private val nonce: String = UUID.randomUUID().toString()
) {
    private var committedProfile: ModelExecutionProfile? = null
    private var pending: PendingLoadTransaction? = null
    private var active: ActiveLoadedSignature? = null
    private var runtimeOverride: RuntimeOverrideSignature? = null
    private val recoveryAttemptedRequestIds = mutableSetOf<String>()
    private val issuedAuthorizations = mutableMapOf<String, LoadAuthorization>()
    private val consumedTransactionIds = mutableSetOf<String>()
    // HotOverrideAuthorization is a regular class (not a data class), so the
    // set deliberately uses object identity and cannot be matched by a forged
    // value-equivalent instance.
    private val issuedHotAuthorizations = mutableSetOf<HotOverrideAuthorization>()

    constructor(registry: ParameterFieldPolicyRegistry = ParameterFieldPolicyRegistry()) : this(
        mapOf(
            LocalChatRuntime.LLAMA_CPP to LlamaCppRuntimeParameterAdapter(registry),
            LocalChatRuntime.GENIEX_LLAMA_CPP to LlamaCppRuntimeParameterAdapter(registry),
            LocalChatRuntime.MNN_CPU to MnnRuntimeParameterAdapter(registry),
            LocalChatRuntime.GENIEX_QAIRT to QairtRuntimeParameterAdapter(registry),
            LocalChatRuntime.LITERT_LM to LiteRtLmRuntimeParameterAdapter(registry)
        )
    )

    @Synchronized
    fun resolveProfile(
        identity: ModelRuntimeIdentity,
        requestedParamsJson: String,
        profileId: String = UUID.randomUUID().toString(),
        revision: Long = 1
    ): ParameterResolution = adapter(identity.runtime).resolveLoadProfile(
        identity = identity,
        rawJson = requestedParamsJson,
        profileId = profileId,
        revision = revision,
        activeLoadSignature = active
    )

    @Synchronized
    fun nativeLoadJson(profile: ModelExecutionProfile): String =
        adapter(profile.runtimeIdentity.runtime).nativeLoadJson(profile)

    @Synchronized
    fun loadSignatureDiagnostic(
        profile: ModelExecutionProfile,
        nativeStatsJson: String
    ): JSONObject = adapter(profile.runtimeIdentity.runtime).loadSignatureDiagnostic(
        identity = profile.runtimeIdentity,
        nativeStatsJson = nativeStatsJson,
        expected = profile.resolvedLoadSignature
    )

    @Synchronized
    fun isLoadSignatureMismatch(
        identity: ModelRuntimeIdentity,
        beginReturnCode: Int,
        nativeError: String? = null
    ): Boolean = adapter(identity.runtime).isLoadSignatureMismatch(beginReturnCode, nativeError)

    @Synchronized
    fun committedProfile(): ModelExecutionProfile? = committedProfile

    @Synchronized
    fun commit(profile: ModelExecutionProfile) {
        pending?.authorization?.transactionId?.let(consumedTransactionIds::add)
        committedProfile = profile
        runtimeOverride = RuntimeOverrideSignature.none(profile.runtimeIdentity)
        // An ordinary commit is an explicit lifecycle boundary. Never leave an
        // older pending authorization live after a different profile is made active.
        pending = null
    }

    @Synchronized
    fun markUnloaded() {
        active = null
        committedProfile?.let { runtimeOverride = RuntimeOverrideSignature.none(it.runtimeIdentity) }
    }

    @Synchronized
    fun createLoadAuthorization(
        transactionId: String,
        profile: ModelExecutionProfile
    ): LoadAuthorization {
        require(transactionId.isNotBlank()) { "transactionId must not be blank" }
        require(transactionId !in consumedTransactionIds) { "transactionId has already been consumed" }
        issuedAuthorizations[transactionId]?.let { existing ->
            require(matches(profile, existing)) { "transactionId is already bound to another profile" }
            return existing
        }
        return LoadAuthorization(
            transactionId = transactionId,
            modelIdentityHash = profile.runtimeIdentity.identityHash,
            profileId = profile.profileId,
            revision = profile.revision,
            desiredProfileDigest = profile.desiredSignature.digest,
            resolvedLoadDigest = profile.resolvedLoadSignature.digest,
            committedExecutionDigest = profile.committedExecutionSignature.digest,
            coordinatorNonce = nonce
        ).also { issuedAuthorizations[transactionId] = it }
    }

    @Synchronized
    fun createHotOverrideAuthorization(
        profile: ModelExecutionProfile,
        values: CanonicalParameterSet
    ): HotOverrideAuthorization {
        val knownProfile = when {
            sameProfile(committedProfile, profile) -> committedProfile
            sameProfile(pending?.profile, profile) -> pending?.profile
            else -> null
        } ?: error("hot override profile is not committed or pending")
        val view = adapter(knownProfile.runtimeIdentity.runtime).registry.forRuntime(knownProfile.runtimeIdentity)
        require(values.fields.isNotEmpty()) { "hot override authorization must bind at least one field" }
        require(values.fields.all { field ->
            view.policy(field).mutability == ParameterMutability.HOT_EXECUTION &&
                !view.policy(field).affectsSemantics
        }) { "hot override authorization may contain only non-semantic HOT_EXECUTION fields" }
        val signature = RuntimeOverrideSignature.of(knownProfile.runtimeIdentity, values)
        return HotOverrideAuthorization(
            profileId = knownProfile.profileId,
            revision = knownProfile.revision,
            modelIdentityHash = knownProfile.runtimeIdentity.identityHash,
            fields = values.fields.toSortedSet(),
            overrideDigest = signature.digest,
            coordinatorNonce = nonce
        ).also { issuedHotAuthorizations += it }
    }

    @Synchronized
    fun stageAuthorizedPending(
        profile: ModelExecutionProfile,
        authorization: LoadAuthorization,
        rollbackTargetProfileId: String?
    ): PendingLoadTransaction {
        require(matches(profile, authorization)) { "load authorization does not match pending profile" }
        require(issuedAuthorizations[authorization.transactionId] === authorization) {
            "load authorization was not issued by this coordinator"
        }
        require(authorization.transactionId !in consumedTransactionIds) {
            "load transaction has already been consumed"
        }
        val rollbackProfile = committedProfile ?: error("a committed profile is required before staging pending")
        require(rollbackProfile.runtimeIdentity.identityHash == profile.runtimeIdentity.identityHash) {
            "pending profile must target the committed model runtime identity"
        }
        require(rollbackTargetProfileId == null || rollbackTargetProfileId == rollbackProfile.profileId) {
            "rollbackTargetProfileId must identify the current committed profile"
        }
        require(pending == null || pending?.authorization === authorization) {
            "another pending profile transaction is already staged"
        }
        return PendingLoadTransaction(profile, authorization, rollbackTargetProfileId).also { pending = it }
    }

    @Synchronized
    fun authorizedPendingFor(authorization: LoadAuthorization): PendingLoadTransaction? =
        pending?.takeIf { staged ->
            staged.authorization === authorization &&
                authorization.transactionId !in consumedTransactionIds &&
                matches(staged.profile, authorization)
        }

    @Synchronized
    fun isAuthorizedPendingActive(authorization: LoadAuthorization): Boolean =
        authorizedPendingSignatureVerificationLocked(authorization)?.strictlyMatches == true

    /**
     * Returns an atomic candidate-specific six-signature view for the exact
     * coordinator-issued pending authorization. A forged, replaced or consumed
     * authorization receives no view. A genuine pending candidate that has not
     * yet been loaded (or whose native readback drifted) receives a view with
     * [AuthorizedPendingSignatureVerification.strictlyMatches] set to false.
     */
    @Synchronized
    fun authorizedPendingSignatureVerification(
        authorization: LoadAuthorization
    ): AuthorizedPendingSignatureVerification? =
        authorizedPendingSignatureVerificationLocked(authorization)

    @Synchronized
    fun commitAuthorizedPending(authorization: LoadAuthorization): ModelExecutionProfile {
        val staged = pending ?: error("no pending profile")
        require(staged.authorization === authorization && matches(staged.profile, authorization)) {
            "pending profile commit was not authorized"
        }
        val loaded = active ?: error("pending profile is not loaded")
        require(loaded.identityHash == staged.profile.runtimeIdentity.identityHash &&
            loaded.values.differences(staged.profile.resolvedLoadSignature.values).isEmpty()) {
            "active native load does not match the pending resolved signature"
        }
        consumedTransactionIds += authorization.transactionId
        commit(staged.profile)
        return staged.profile
    }

    @Synchronized
    fun publishLoaded(
        profile: ModelExecutionProfile,
        nativeStatsJson: String,
        authorization: LoadAuthorization? = null
    ): ParameterSignatureSnapshot {
        val staged = pending
        if (staged != null) {
            when {
                sameProfile(staged.profile, profile) -> require(
                    authorization != null &&
                        authorization === staged.authorization &&
                        matches(profile, authorization)
                ) { "pending profile load was not authorized" }
                sameProfile(committedProfile, profile) -> require(authorization == null) {
                    "rollback/committed profile publication must not reuse pending authorization"
                }
                else -> error("an unrelated profile cannot be published while a pending transaction exists")
            }
        } else if (authorization != null) {
            error("load authorization has no matching pending transaction")
        }
        val adapter = adapter(profile.runtimeIdentity.runtime)
        val observed = adapter.activeLoadedSignature(
            profile.runtimeIdentity,
            nativeStatsJson,
            profile.resolvedLoadSignature
        ) ?: error("native effective load signature does not match the resolved profile")
        active = observed
        val publicationOverride = if (
            committedProfile?.runtimeIdentity?.identityHash == profile.runtimeIdentity.identityHash
        ) {
            runtimeOverride ?: RuntimeOverrideSignature.none(profile.runtimeIdentity)
        } else {
            // A runtime/identity switch must never inherit a thermal or
            // request-scoped override signed for the previously loaded model.
            RuntimeOverrideSignature.none(profile.runtimeIdentity)
        }
        if (committedProfile == null) commit(profile)
        return snapshotLocked(profile, publicationOverride)
    }

    /**
     * Explicit normal loads cancel stale pending state, but cannot smuggle the
     * exact staged candidate around its transaction authorization.
     */
    @Synchronized
    fun prepareOrdinaryLoad(profile: ModelExecutionProfile) {
        val staged = pending ?: return
        require(!sameProfile(staged.profile, profile)) {
            "the staged pending profile requires its exact load authorization"
        }
        staged.authorization.transactionId.let(consumedTransactionIds::add)
        pending = null
    }

    @Synchronized
    fun rollbackTargetFor(authorization: LoadAuthorization): ModelExecutionProfile {
        val staged = pending ?: error("no pending profile")
        require(staged.authorization === authorization && matches(staged.profile, authorization)) {
            "pending rollback was not authorized"
        }
        val target = committedProfile ?: error("pending transaction has no committed rollback profile")
        require(staged.rollbackTargetProfileId == null || staged.rollbackTargetProfileId == target.profileId) {
            "the in-memory committed profile does not match rollbackTargetProfileId"
        }
        return target
    }

    @Synchronized
    fun abortAuthorizedPending(authorization: LoadAuthorization): Boolean {
        val staged = authorizedPendingFor(authorization) ?: return false
        consumedTransactionIds += staged.authorization.transactionId
        pending = null
        return true
    }

    @Synchronized
    fun setRuntimeOverride(values: CanonicalParameterSet): RuntimeOverrideSignature {
        val profile = committedProfile ?: error("no committed profile")
        val allowed = values.fields.all { field ->
            adapter(profile.runtimeIdentity.runtime).registry.forRuntime(profile.runtimeIdentity)
                .policy(field).let { policy ->
                    policy.mutability == ParameterMutability.HOT_EXECUTION && !policy.affectsSemantics
                }
        }
        require(allowed) {
            "runtime overrides may contain only registered non-semantic HOT_EXECUTION fields"
        }
        return RuntimeOverrideSignature.of(profile.runtimeIdentity, values).also { runtimeOverride = it }
    }

    @Synchronized
    fun clearRuntimeOverride(): RuntimeOverrideSignature {
        val profile = committedProfile ?: error("no committed profile")
        return RuntimeOverrideSignature.none(profile.runtimeIdentity).also { runtimeOverride = it }
    }

    @Synchronized
    fun preflight(
        identity: ModelRuntimeIdentity,
        requestParamsJson: String,
        trustedAuthorization: LoadAuthorization? = null,
        @Suppress("UNUSED_PARAMETER") allowTrustedHotOverride: Boolean = false,
        hotOverrideAuthorization: HotOverrideAuthorization? = null
    ): CompletionPreflight {
        val committed = committedProfile
            ?: return CompletionPreflight.Rejected("model_not_loaded", emptySet(), emptyList(), "No committed model profile is loaded.")
        if (committed.runtimeIdentity.identityHash != identity.identityHash) {
            return CompletionPreflight.Rejected("model_mismatch", emptySet(), emptyList(), "The request model identity is not active.")
        }
        val adapter = adapter(identity.runtime)
        val partition = adapter.partition(identity, requestParamsJson)
        val activeSignature = active
            ?: return CompletionPreflight.Rejected("model_not_loaded", emptySet(), partition.quarantinedOverrides, "No active native load signature is published.")
        val staged = pending
        val authorizedPendingIsActive = trustedAuthorization != null && staged != null &&
            staged.authorization === trustedAuthorization && matches(staged.profile, trustedAuthorization) &&
            activeSignature.identityHash == staged.profile.runtimeIdentity.identityHash &&
            activeSignature.values.differences(staged.profile.resolvedLoadSignature.values).isEmpty()
        val profile = if (authorizedPendingIsActive) staged!!.profile else committed
        val activeDrift = activeSignature.values.differences(profile.resolvedLoadSignature.values)
        if (activeSignature.identityHash != identity.identityHash || activeDrift.isNotEmpty()) {
            if (!authorizedPendingIsActive) {
                return CompletionPreflight.Rejected(
                    "active_profile_drift",
                    activeDrift,
                    partition.quarantinedOverrides,
                    "The native load does not match the committed profile; reload the committed profile before generating."
                )
            }
        }
        val changedLoad = partition.loadBound.differences(profile.resolvedLoadBoundValues)
            .filterTo(linkedSetOf()) { partition.loadBound.fields.contains(it) }
        if (changedLoad.isNotEmpty()) {
            val authorized = trustedAuthorization != null && staged != null &&
                staged.authorization === trustedAuthorization && matches(staged.profile, trustedAuthorization) &&
                staged.profile.runtimeIdentity.identityHash == identity.identityHash &&
                requestedFieldsMatch(partition.loadBound, staged.profile.resolvedLoadBoundValues) &&
                requestedFieldsMatch(partition.hotExecution, staged.profile.hotExecutionValues)
            return CompletionPreflight.Rejected(
                code = if (authorized) "model_reload_required_authorized" else "model_reload_required",
                changedFields = changedLoad,
                quarantinedOverrides = partition.quarantinedOverrides,
                message = if (authorized) "The authorized pending profile must be reloaded before generation."
                else "Load-bound request fields cannot change the active model without an authorized pending transaction."
            )
        }
        val changedHot = partition.hotExecution.differences(profile.hotExecutionValues)
            .filterTo(linkedSetOf()) { partition.hotExecution.fields.contains(it) }
        val requestedHotOverride = partition.hotExecution.only(changedHot)
        val hotOverride = RuntimeOverrideSignature.of(identity, requestedHotOverride)
        val hotOverrideAuthorized = changedHot.isNotEmpty() &&
            hotOverrideAuthorization != null &&
            matches(profile, hotOverride, hotOverrideAuthorization)
        if (changedHot.isNotEmpty() && !hotOverrideAuthorized) {
            return CompletionPreflight.Rejected(
                "execution_override_forbidden",
                changedHot,
                partition.quarantinedOverrides,
                "Ordinary requests may override generation fields only."
            )
        }
        val changedBehavior = partition.modelBehavior.differences(profile.modelBehaviorValues)
            .filterTo(linkedSetOf()) { partition.modelBehavior.fields.contains(it) }
        if (changedBehavior.isNotEmpty()) {
            return CompletionPreflight.Rejected(
                "model_behavior_override_forbidden",
                changedBehavior,
                partition.quarantinedOverrides,
                "Template and model-behavior fields require a trusted profile and correctness gate."
            )
        }
        val override = if (hotOverrideAuthorized) {
            hotOverride
        } else if (authorizedPendingIsActive) {
            // A pending transaction must be evaluated exactly as signed. Do not
            // let a prior thermal/runtime override contaminate candidate proof.
            RuntimeOverrideSignature.none(identity)
        } else {
            runtimeOverride ?: RuntimeOverrideSignature.none(identity)
        }
        val nativeJson = adapter.nativeCompletionJson(partition, profile, override)
        return CompletionPreflight.Ready(
            nativeParamsJson = nativeJson,
            generationJson = partition.generationJson,
            signatures = snapshotLocked(profile, override, activeSignature),
            quarantinedOverrides = partition.quarantinedOverrides
        )
    }

    @Synchronized
    fun decideMismatchRecovery(
        requestId: String,
        identity: ModelRuntimeIdentity,
        trustedAuthorization: LoadAuthorization? = null
    ): MismatchRecoveryDecision {
        if (requestId.isBlank()) return MismatchRecoveryDecision.Fail("invalid_request_id", "requestId must not be blank")
        if (!recoveryAttemptedRequestIds.add(requestId)) {
            return MismatchRecoveryDecision.Fail("recovery_exhausted", "Load signature recovery is limited to one attempt per request.")
        }
        val staged = pending
        if (trustedAuthorization != null && staged != null &&
            staged.authorization === trustedAuthorization && matches(staged.profile, trustedAuthorization) &&
            staged.profile.runtimeIdentity.identityHash == identity.identityHash
        ) {
            return MismatchRecoveryDecision.ReloadAuthorizedPending(staged)
        }
        val committed = committedProfile
        if (committed?.runtimeIdentity?.identityHash == identity.identityHash) {
            return MismatchRecoveryDecision.ReloadCommittedForDrift(committed)
        }
        return MismatchRecoveryDecision.Fail("model_mismatch", "No matching committed profile is available for recovery.")
    }

    @Synchronized
    fun finishRequest(requestId: String) {
        // Keep the bounded-attempt marker until the request has fully exited.
        recoveryAttemptedRequestIds.remove(requestId)
    }

    @Synchronized
    fun snapshot(): ParameterSignatureSnapshot? = committedProfile?.let(::snapshotLocked)

    private fun authorizedPendingSignatureVerificationLocked(
        authorization: LoadAuthorization
    ): AuthorizedPendingSignatureVerification? {
        val staged = pending ?: return null
        if (staged.authorization !== authorization ||
            authorization.transactionId in consumedTransactionIds ||
            !matches(staged.profile, authorization)
        ) {
            return null
        }

        val profile = staged.profile
        val identity = profile.runtimeIdentity
        val noOverride = RuntimeOverrideSignature.none(identity)
        val actualActive = active
        val effective = actualActive
            ?.takeIf { it.identityHash == identity.identityHash }
            ?.let { loaded ->
                EffectiveExecutionSignature.of(
                    identity = identity,
                    active = loaded,
                    committed = profile.committedExecutionSignature,
                    override = noOverride
                )
            }
        val signatures = ParameterSignatureSnapshot(
            desired = profile.desiredSignature,
            resolved = profile.resolvedLoadSignature,
            active = actualActive,
            committed = profile.committedExecutionSignature,
            override = noOverride,
            effective = effective
        )

        val expectedActive = ActiveLoadedSignature.of(identity, profile.resolvedLoadSignature.values)
        val activeMatches = actualActive == expectedActive
        val expectedEffective = if (activeMatches) {
            EffectiveExecutionSignature.of(
                identity = identity,
                active = expectedActive,
                committed = profile.committedExecutionSignature,
                override = noOverride
            )
        } else {
            null
        }
        val authorizationMatchesSignatures =
            signatures.desired.digest == authorization.desiredProfileDigest &&
                signatures.resolved.digest == authorization.resolvedLoadDigest &&
                signatures.committed.digest == authorization.committedExecutionDigest
        val overrideIsStrictlyNone = signatures.override == noOverride &&
            signatures.override.isNone &&
            signatures.override.digest == "NONE" &&
            signatures.override.values.fields.isEmpty()
        val strictlyMatches = authorizationMatchesSignatures &&
            signatures.desired == profile.desiredSignature &&
            signatures.resolved == profile.resolvedLoadSignature &&
            signatures.committed == profile.committedExecutionSignature &&
            activeMatches &&
            overrideIsStrictlyNone &&
            signatures.effective != null &&
            signatures.effective == expectedEffective

        return AuthorizedPendingSignatureVerification(
            transactionId = authorization.transactionId,
            profileId = profile.profileId,
            revision = profile.revision,
            signatures = signatures,
            strictlyMatches = strictlyMatches
        )
    }

    private fun matches(profile: ModelExecutionProfile, authorization: LoadAuthorization): Boolean =
        authorization.coordinatorNonce == nonce &&
            issuedAuthorizations[authorization.transactionId] === authorization &&
            authorization.transactionId.isNotBlank() &&
            authorization.modelIdentityHash == profile.runtimeIdentity.identityHash &&
            authorization.profileId == profile.profileId &&
            authorization.revision == profile.revision &&
            authorization.desiredProfileDigest == profile.desiredSignature.digest &&
            authorization.resolvedLoadDigest == profile.resolvedLoadSignature.digest &&
            authorization.committedExecutionDigest == profile.committedExecutionSignature.digest

    private fun matches(
        profile: ModelExecutionProfile,
        override: RuntimeOverrideSignature,
        authorization: HotOverrideAuthorization
    ): Boolean = authorization.coordinatorNonce == nonce &&
        authorization in issuedHotAuthorizations &&
        override.identityHash == profile.runtimeIdentity.identityHash &&
        authorization.profileId == profile.profileId &&
        authorization.revision == profile.revision &&
        authorization.modelIdentityHash == profile.runtimeIdentity.identityHash &&
        authorization.fields == override.values.fields &&
        authorization.overrideDigest == override.digest

    private fun sameProfile(first: ModelExecutionProfile?, second: ModelExecutionProfile?): Boolean =
        first != null && second != null &&
            first.runtimeIdentity.identityHash == second.runtimeIdentity.identityHash &&
            first.profileId == second.profileId &&
            first.revision == second.revision &&
            first.desiredSignature.digest == second.desiredSignature.digest &&
            first.resolvedLoadSignature.digest == second.resolvedLoadSignature.digest &&
            first.committedExecutionSignature.digest == second.committedExecutionSignature.digest

    private fun requestedFieldsMatch(
        requested: CanonicalParameterSet,
        target: CanonicalParameterSet
    ): Boolean = requested.encodedValues.all { (field, value) -> target.encodedValues[field] == value }

    private fun adapter(runtime: LocalChatRuntime): RuntimeParameterAdapter =
        adapters[runtime] ?: error("No runtime parameter adapter registered for $runtime")

    private fun snapshotLocked(
        profile: ModelExecutionProfile,
        override: RuntimeOverrideSignature = runtimeOverride ?: RuntimeOverrideSignature.none(profile.runtimeIdentity),
        activeSignature: ActiveLoadedSignature? = active
    ): ParameterSignatureSnapshot {
        val effective = activeSignature?.let {
            EffectiveExecutionSignature.of(
                profile.runtimeIdentity,
                it,
                profile.committedExecutionSignature,
                override
            )
        }
        return ParameterSignatureSnapshot(
            desired = profile.desiredSignature,
            resolved = profile.resolvedLoadSignature,
            active = activeSignature,
            committed = profile.committedExecutionSignature,
            override = override,
            effective = effective
        )
    }
}

private fun JSONObject.mergeJson(source: JSONObject) {
    source.keys().forEach { key -> put(key, source.opt(key)) }
}

private fun signatureSafeValues(
    identity: ModelRuntimeIdentity,
    values: CanonicalParameterSet
): CanonicalParameterSet = CanonicalParameterSet.fromEncoded(
    values.encodedValues.mapValues { (field, encoded) ->
        when {
            field == "mmproj_path" || field == "projector_path" -> encodeValue(
                "projector:${identity.projectorFingerprint.ifBlank { "bound" }}"
            )
            field.endsWith("_path") || field.endsWith("_uri") ->
                encodeValue("resource:${sha256(decodeValue(encoded).toString())}")
            else -> encoded
        }
    }
)

private fun signatureDigest(
    kind: String,
    identity: ModelRuntimeIdentity,
    values: CanonicalParameterSet
): String = sha256(
    buildString {
        append(kind).append('\n')
        append(identity.identityHash).append('\n')
        values.encodedValues.toSortedMap().forEach { (field, value) ->
            append(escapeCanonical(field)).append('=').append(escapeCanonical(value)).append('\n')
        }
    }
)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun escapeCanonical(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\n", "\\n")
    .replace("=", "\\=")

private fun encodeValue(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "n:"
    is Boolean -> if (value) "b:1" else "b:0"
    is Byte, is Short, is Int, is Long -> "i:${(value as Number).toLong()}"
    is Float, is Double -> "d:${BigDecimal(value.toString()).stripTrailingZeros().toPlainString()}"
    is Number -> "d:${BigDecimal(value.toString()).stripTrailingZeros().toPlainString()}"
    is String -> "s:$value"
    is JSONObject -> "j:${canonicalJsonObject(value)}"
    is JSONArray -> "j:${canonicalJsonArray(value)}"
    else -> "s:${value}"
}

private fun decodeValue(encoded: String): Any = when {
    encoded == "n:" -> JSONObject.NULL
    encoded == "b:1" -> true
    encoded == "b:0" -> false
    encoded.startsWith("i:") -> encoded.substring(2).toLong().let { value ->
        if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else value
    }
    encoded.startsWith("d:") -> encoded.substring(2).toDouble()
    encoded.startsWith("s:") -> encoded.substring(2)
    encoded.startsWith("j:") -> {
        val json = encoded.substring(2)
        if (json.startsWith("[")) JSONArray(json) else JSONObject(json)
    }
    else -> encoded
}

private fun canonicalJsonObject(root: JSONObject): String = JSONObject().also { out ->
    root.keys().asSequence().toList().sorted().forEach { key ->
        out.put(key, canonicalJsonValue(root.opt(key)))
    }
}.toString()

private fun canonicalJsonArray(array: JSONArray): String = JSONArray().also { out ->
    for (index in 0 until array.length()) out.put(canonicalJsonValue(array.opt(index)))
}.toString()

private fun canonicalJsonValue(value: Any?): Any? = when (value) {
    is JSONObject -> JSONObject(canonicalJsonObject(value))
    is JSONArray -> JSONArray(canonicalJsonArray(value))
    else -> value
}

private fun jsonValueString(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject, is JSONArray -> value.toString()
    is Number, is Boolean -> value.toString()
    is String -> JSONObject.quote(value)
    else -> JSONObject.quote(value.toString())
}
