package com.muyuchat.core.modelstore

import com.muyuchat.core.modelstore.MnnBundleReadinessAnalyzer.TieEmbeddingDescriptor
import org.json.JSONArray
import org.json.JSONObject

internal fun MnnBundleReadinessAnalyzer.addInvalidTieEmbeddingDiagnostic(
        rootConfig: JSONObject,
        invalid: MutableList<String>,
        diagnostics: MutableList<MnnBundleDiagnostic>,
        detail: String
    ) {
        val llmConfigPath = rootConfig.optString("llm_config").trim().ifBlank { "llm_config.json" }
        val component = "$llmConfigPath: tie_embeddings"
        invalid += component
        diagnostics += MnnBundleDiagnostic(
            code = MnnBundleDiagnosticCode.TIE_EMBEDDINGS_INVALID,
            severity = MnnBundleDiagnosticSeverity.ERROR,
            path = llmConfigPath,
            message = "$component is invalid: $detail"
        )
    }

internal fun MnnBundleReadinessAnalyzer.parseTieEmbeddingDescriptor(value: Any): TieEmbeddingDescriptor {
        fun exactLong(raw: Any?, field: String): Long {
            require(raw is Number) { "$field must be an integer." }
            val doubleValue = raw.toDouble()
            val longValue = raw.toLong()
            require(doubleValue.isFinite() && doubleValue == longValue.toDouble()) {
                "$field must be an exact 64-bit integer."
            }
            return longValue
        }

        return when (value) {
            is JSONArray -> {
                require(value.length() >= 5) { "legacy tie_embeddings must contain at least five integers." }
                TieEmbeddingDescriptor(
                    weightOffset = exactLong(value.opt(0), "weight_offset"),
                    alphaOffset = exactLong(value.opt(1), "alpha_offset"),
                    alphaSize = exactLong(value.opt(2), "alpha_size"),
                    quantBit = exactLong(value.opt(3), "quant_bit"),
                    quantBlock = exactLong(value.opt(4), "quant_block"),
                    alphaFp16 = value.opt(5).let { raw ->
                        when (raw) {
                            null, JSONObject.NULL -> false
                            is Boolean -> raw
                            is Number -> exactLong(raw, "alpha_fp16") != 0L
                            else -> error("alpha_fp16 must be a boolean or integer.")
                        }
                    }
                )
            }
            is JSONObject -> {
                val alphaDtype = value.optString("alpha_dtype", "fp32").lowercase()
                require(alphaDtype == "fp16" || alphaDtype == "fp32") {
                    "alpha_dtype must be fp16 or fp32."
                }
                TieEmbeddingDescriptor(
                    weightOffset = exactLong(value.opt("weight_offset"), "weight_offset"),
                    alphaOffset = exactLong(value.opt("alpha_offset"), "alpha_offset"),
                    alphaSize = exactLong(value.opt("alpha_size"), "alpha_size"),
                    quantBit = exactLong(value.opt("quant_bit"), "quant_bit"),
                    quantBlock = exactLong(value.opt("quant_block"), "quant_block"),
                    alphaFp16 = alphaDtype == "fp16"
                )
            }
            else -> error("tie_embeddings must be an array or object.")
        }
    }

internal fun MnnBundleReadinessAnalyzer.effectiveLong(modelConfig: JSONObject?, rootConfig: JSONObject, key: String): Long {
        val raw = when {
            modelConfig?.opt(key) is Number -> modelConfig.opt(key)
            rootConfig.opt(key) is Number -> rootConfig.opt(key)
            else -> null
        }
        return (raw as? Number)?.toLong() ?: 0L
    }

internal fun MnnBundleReadinessAnalyzer.validateTieEmbeddingDescriptor(
        descriptor: TieEmbeddingDescriptor,
        hiddenSize: Long,
        storageSize: Long
    ): String? {
        if (descriptor.weightOffset < 0L || descriptor.alphaOffset < 0L || descriptor.alphaSize <= 0L) {
            return "offsets must be non-negative and alpha_size must be positive"
        }
        if (hiddenSize <= 0L) return "hidden_size must be positive"
        if (descriptor.quantBit != 4L && descriptor.quantBit != 8L) {
            return "quant_bit must be 4 or 8"
        }
        if (descriptor.quantBlock < 0L || descriptor.quantBlock > hiddenSize ||
            (descriptor.quantBlock > 0L && hiddenSize % descriptor.quantBlock != 0L)
        ) {
            return "quant_block must be zero or divide hidden_size exactly"
        }
        if (descriptor.alphaOffset <= descriptor.weightOffset) {
            return "alpha_offset must follow the quantized weight range"
        }
        if (descriptor.alphaOffset > storageSize ||
            descriptor.alphaSize > storageSize - descriptor.alphaOffset
        ) {
            return "alpha range exceeds $storageSize-byte backing file"
        }
        val tokenBits = runCatching { Math.multiplyExact(hiddenSize, descriptor.quantBit) }.getOrNull()
            ?: return "token width overflows"
        if (tokenBits % 8L != 0L) return "token width is not byte-aligned"
        val tokenBytes = tokenBits / 8L
        val weightBytes = descriptor.alphaOffset - descriptor.weightOffset
        if (tokenBytes <= 0L || weightBytes <= 0L || weightBytes % tokenBytes != 0L) {
            return "quantized weight range is not a whole number of token rows"
        }
        val vocabularySize = weightBytes / tokenBytes
        val blockCount = if (descriptor.quantBlock == 0L) 1L else hiddenSize / descriptor.quantBlock
        val alphaElementBytes = if (descriptor.alphaFp16) 2L else 4L
        if (descriptor.alphaSize % alphaElementBytes != 0L) {
            return "alpha_size is not aligned to alpha_dtype"
        }
        val symmetricElements = runCatching {
            Math.multiplyExact(vocabularySize, blockCount)
        }.getOrNull() ?: return "scale count overflows"
        val asymmetricElements = runCatching {
            Math.multiplyExact(symmetricElements, 2L)
        }.getOrNull() ?: return "scale count overflows"
        val actualElements = descriptor.alphaSize / alphaElementBytes
        if (actualElements != symmetricElements && actualElements != asymmetricElements) {
            return "alpha range does not match token and quant-block geometry"
        }
        return null
    }
