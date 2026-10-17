package com.muyuchat.core.engine

import org.json.JSONObject

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
