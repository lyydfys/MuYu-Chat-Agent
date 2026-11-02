package com.muyuchat.mca

import com.muyuchat.core.mediatek.MediaTekAnimaTextEncoderContract
import com.muyuchat.core.mediatek.MediaTekAnimaTokenSequence
import com.muyuchat.core.mediatek.MediaTekAnimaTokenizer
import com.muyuchat.core.mediatek.MediaTekAnimaTokenTruncationPolicy
import com.muyuchat.core.mediatek.MediaTekAnimaTokenWeighting
import com.muyuchat.core.mediatek.MediaTekAnimaTokenizerPaddingPolicy
import com.muyuchat.core.mediatek.MediaTekTokenizerSource
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Bridges the two manifest-declared tokenizer roles to stable-diffusion.cpp's exact Anima tokenizers. */
internal class MediaTekAnimaNativeTokenizer(
    private val tokenizePrompt: (prompt: String, qwenMaxTokens: Int, t5MaxTokens: Int) -> String =
        { prompt, qwenMaxTokens, t5MaxTokens ->
            NativeStableDiffusionBridge().tokenizeAnimaPrompt(prompt, qwenMaxTokens, t5MaxTokens)
        }
) : MediaTekAnimaTokenizer {
    override fun tokenize(
        bundleRoot: File,
        textEncoder: MediaTekAnimaTextEncoderContract,
        prompt: String,
        qwenMaxTokens: Int,
        t5MaxTokens: Int
    ): MediaTekAnimaTokenSequence {
        require(bundleRoot.isDirectory && bundleRoot.canRead()) {
            "MediaTek Anima tokenizer requires a readable bundle root."
        }
        requireTokenizerContract(textEncoder, qwenMaxTokens, t5MaxTokens)
        require(prompt.length <= MAX_PROMPT_CHARS) { "Anima prompt exceeds the app tokenizer limit." }
        return parseNativeResult(tokenizePrompt(prompt, qwenMaxTokens, t5MaxTokens), qwenMaxTokens, t5MaxTokens)
    }

    private fun requireTokenizerContract(
        contract: MediaTekAnimaTextEncoderContract,
        qwenMaxTokens: Int,
        t5MaxTokens: Int
    ) {
        val qwen = contract.qwenTokenizer
        val t5 = contract.t5Tokenizer
        require(qwen.source == MediaTekTokenizerSource.RUNTIME_PROVIDER &&
            qwen.providerId == QWEN_PROVIDER_ID && qwen.asset == null
        ) { "Unsupported MediaTek Anima Qwen tokenizer provider contract." }
        require(t5.source == MediaTekTokenizerSource.RUNTIME_PROVIDER &&
            t5.providerId == T5_PROVIDER_ID && t5.asset == null
        ) { "Unsupported MediaTek Anima T5 tokenizer provider contract." }
        require(qwenMaxTokens == qwen.sequenceLength && t5MaxTokens == t5.sequenceLength &&
            qwenMaxTokens in 1..MAX_NATIVE_SEQUENCE_LENGTH && t5MaxTokens in 1..MAX_NATIVE_SEQUENCE_LENGTH
        ) { "MediaTek Anima tokenizer sequence lengths do not match the v3 manifest." }
        require(qwen.padTokenId == QWEN_PAD_TOKEN_ID && t5.padTokenId == T5_PAD_TOKEN_ID) {
            "MediaTek Anima tokenizer pad IDs do not match the bundled stable-diffusion.cpp providers."
        }
        require(qwen.weighting == MediaTekAnimaTokenWeighting.UNIFORM_ONE &&
            qwen.paddingPolicy == MediaTekAnimaTokenizerPaddingPolicy.RIGHT_PAD &&
            qwen.paddingWeight == 0f && qwen.truncationPolicy == MediaTekAnimaTokenTruncationPolicy.TRUNCATE_TAIL
        ) { "Unsupported MediaTek Anima Qwen tokenizer semantics." }
        require(t5.weighting == MediaTekAnimaTokenWeighting.PROMPT_ATTENTION &&
            t5.paddingPolicy == MediaTekAnimaTokenizerPaddingPolicy.RIGHT_PAD &&
            t5.paddingWeight == 0f && t5.truncationPolicy == MediaTekAnimaTokenTruncationPolicy.TRUNCATE_TAIL
        ) { "Unsupported MediaTek Anima T5 tokenizer semantics." }
    }

    private fun parseNativeResult(raw: String, qwenLength: Int, t5Length: Int): MediaTekAnimaTokenSequence {
        require(raw.isNotBlank() && raw.length <= MAX_NATIVE_JSON_CHARS) {
            "Native Anima tokenizer returned an empty or oversized response."
        }
        val json = try {
            JSONObject(raw)
        } catch (error: Exception) {
            throw IllegalStateException("Native Anima tokenizer returned malformed JSON.", error)
        }
        val ok = json.opt("ok") as? Boolean
            ?: throw IllegalStateException("Native Anima tokenizer response is missing its boolean ok field.")
        if (!ok) {
            val code = json.optString("errorCode").trim().take(80).ifBlank { "tokenization_failed" }
            val message = json.optString("message").trim().take(400).ifBlank { "No native error message was provided." }
            throw IllegalStateException("Native Anima tokenizer failed ($code): $message")
        }
        require(json.requiredLength("qwenSequenceLength") == qwenLength &&
            json.requiredLength("t5SequenceLength") == t5Length
        ) { "Native Anima tokenizer response lengths do not match the requested contract." }
        require(json.requiredString("qwenPadding") == "right" &&
            json.requiredString("t5Padding") == "right" &&
            json.requiredString("qwenTruncation") == "tail" &&
            json.requiredString("t5Truncation") == "tail_with_eos"
        ) { "Native Anima tokenizer response does not match the required padding/truncation semantics." }

        val qwenIds = json.requiredIntArray("qwenTokenIds", qwenLength)
        val qwenMask = json.requiredIntArray("qwenAttentionMask", qwenLength)
        val t5Ids = json.requiredIntArray("t5TokenIds", t5Length)
        val t5Mask = json.requiredIntArray("t5AttentionMask", t5Length)
        val t5Weights = json.requiredFloatArray("t5TokenWeights", t5Length)
        require(qwenMask.all { it == 0 || it == 1 } && t5Mask.all { it == 0 || it == 1 }) {
            "Native Anima tokenizer attention masks must contain only zero or one."
        }
        require(t5Weights.indices.all { index ->
            t5Mask[index] == 1 || t5Weights[index] == 0f
        }) { "Native Anima tokenizer padding weights must be zero." }
        return MediaTekAnimaTokenSequence(qwenIds, qwenMask, t5Ids, t5Weights, t5Mask)
    }

    private fun JSONObject.requiredLength(name: String): Int {
        val raw = opt(name) as? Number
            ?: throw IllegalStateException("Native Anima tokenizer response field '$name' must be an integer.")
        val value = raw.toDouble()
        require(value.isFinite() && value % 1.0 == 0.0 && value in 1.0..MAX_NATIVE_SEQUENCE_LENGTH.toDouble()) {
            "Native Anima tokenizer response field '$name' is outside the supported range."
        }
        return value.toInt()
    }

    private fun JSONObject.requiredString(name: String): String =
        opt(name) as? String
            ?: throw IllegalStateException("Native Anima tokenizer response field '$name' must be a string.")

    private fun JSONObject.requiredIntArray(name: String, expectedLength: Int): IntArray {
        val array = opt(name) as? JSONArray
            ?: throw IllegalStateException("Native Anima tokenizer response field '$name' must be an array.")
        require(array.length() == expectedLength) {
            "Native Anima tokenizer field '$name' has ${array.length()} entries; expected $expectedLength."
        }
        return IntArray(expectedLength) { index ->
            val raw = array.opt(index) as? Number
                ?: throw IllegalStateException("Native Anima tokenizer field '$name[$index]' must be an integer.")
            val value = raw.toDouble()
            require(value.isFinite() && value % 1.0 == 0.0 &&
                value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()
            ) { "Native Anima tokenizer field '$name[$index]' is not an exact 32-bit integer." }
            value.toInt()
        }
    }

    private fun JSONObject.requiredFloatArray(name: String, expectedLength: Int): FloatArray {
        val array = opt(name) as? JSONArray
            ?: throw IllegalStateException("Native Anima tokenizer response field '$name' must be an array.")
        require(array.length() == expectedLength) {
            "Native Anima tokenizer field '$name' has ${array.length()} entries; expected $expectedLength."
        }
        return FloatArray(expectedLength) { index ->
            val raw = array.opt(index) as? Number
                ?: throw IllegalStateException("Native Anima tokenizer field '$name[$index]' must be numeric.")
            val value = raw.toDouble()
            require(value.isFinite() && value in -Float.MAX_VALUE.toDouble()..Float.MAX_VALUE.toDouble()) {
                "Native Anima tokenizer field '$name[$index]' is not a finite float."
            }
            value.toFloat()
        }
    }

    private companion object {
        const val QWEN_PROVIDER_ID = "mca.qwen3-tokenizer"
        const val T5_PROVIDER_ID = "mca.anima-t5-tokenizer"
        const val QWEN_PAD_TOKEN_ID = 151643
        const val T5_PAD_TOKEN_ID = 0
        const val MAX_NATIVE_SEQUENCE_LENGTH = 512
        const val MAX_PROMPT_CHARS = 64 * 1024
        const val MAX_NATIVE_JSON_CHARS = 1024 * 1024
    }
}
