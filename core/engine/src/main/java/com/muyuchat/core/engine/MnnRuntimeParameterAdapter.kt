package com.muyuchat.core.engine

import org.json.JSONObject

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
