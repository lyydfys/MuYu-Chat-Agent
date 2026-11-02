package com.muyuchat.mca

import com.muyuchat.core.mediatek.MediaTekAnimaTextEncoderContract
import com.muyuchat.core.mediatek.MediaTekAnimaTokenTruncationPolicy
import com.muyuchat.core.mediatek.MediaTekAnimaTokenWeighting
import com.muyuchat.core.mediatek.MediaTekAnimaTokenizerContract
import com.muyuchat.core.mediatek.MediaTekAnimaTokenizerPaddingPolicy
import com.muyuchat.core.mediatek.MediaTekTokenizerSource
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekAnimaNativeTokenizerTest {
    @Test
    fun invokesNativeTokenizersWithIndependentManifestLengthsAndMapsExactArrays() {
        var arguments: List<Any>? = null
        val tokenizer = MediaTekAnimaNativeTokenizer { prompt, qwen, t5 ->
            arguments = listOf(prompt, qwen, t5)
            response(
                qwenLength = qwen,
                t5Length = t5,
                qwenIds = listOf(10, 11, 0),
                qwenMask = listOf(1, 1, 0),
                t5Ids = listOf(20, 21, 22, 0),
                t5Weights = listOf(1f, 1.2f, 0.8f, 0f),
                t5Mask = listOf(1, 1, 1, 0)
            )
        }

        val sequence = tokenizer.tokenize(
            bundleRoot = File("."),
            textEncoder = textEncoder(qwenLength = 3, t5Length = 4),
            prompt = "a (red:1.2) cup",
            qwenMaxTokens = 3,
            t5MaxTokens = 4
        )

        assertEquals(listOf("a (red:1.2) cup", 3, 4), arguments)
        assertArrayEquals(intArrayOf(10, 11, 0), sequence.qwenTokenIds)
        assertArrayEquals(intArrayOf(1, 1, 0), sequence.qwenAttentionMask)
        assertArrayEquals(intArrayOf(20, 21, 22, 0), sequence.t5TokenIds)
        assertArrayEquals(floatArrayOf(1f, 1.2f, 0.8f, 0f), sequence.t5TokenWeights, 0f)
        assertArrayEquals(intArrayOf(1, 1, 1, 0), sequence.t5AttentionMask)
    }

    @Test
    fun rejectsUnknownTokenizerProviderBeforeCallingNativeCode() {
        var called = false
        val tokenizer = MediaTekAnimaNativeTokenizer { _, _, _ ->
            called = true
            "{}"
        }

        val error = runCatching {
            tokenizer.tokenize(
                File("."),
                textEncoder(qwenLength = 3, t5Length = 4, qwenProvider = "other.qwen"),
                "prompt",
                3,
                4
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertFalse(called)
    }

    @Test
    fun rejectsManifestPadIdsThatDoNotMatchNativeTokenizerProviders() {
        var called = false
        val tokenizer = MediaTekAnimaNativeTokenizer { _, _, _ ->
            called = true
            "{}"
        }
        val contract = textEncoder(3, 4).copy(
            qwenTokenizer = textEncoder(3, 4).qwenTokenizer.copy(padTokenId = 0)
        )

        val error = runCatching {
            tokenizer.tokenize(File("."), contract, "prompt", 3, 4)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertFalse(called)
    }

    @Test
    fun reportsNativeFailureEnvelopeWithoutTreatingItAsTokens() {
        val tokenizer = MediaTekAnimaNativeTokenizer { _, _, _ ->
            JSONObject()
                .put("ok", false)
                .put("errorCode", "tokenization_failed")
                .put("message", "native vocab is unavailable")
                .toString()
        }

        val error = runCatching {
            tokenizer.tokenize(File("."), textEncoder(3, 4), "prompt", 3, 4)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("tokenization_failed"))
        assertTrue(error?.message.orEmpty().contains("native vocab is unavailable"))
    }

    @Test
    fun rejectsWrongLengthsNonBinaryMasksAndNonzeroPaddingWeights() {
        val wrongLength = MediaTekAnimaNativeTokenizer { _, _, _ ->
            response(3, 4, listOf(1), listOf(1), listOf(2, 3, 4, 0), listOf(1f, 1f, 1f, 0f), listOf(1, 1, 1, 0))
        }
        val badMask = MediaTekAnimaNativeTokenizer { _, _, _ ->
            response(3, 4, listOf(1, 2, 0), listOf(1, 2, 0), listOf(2, 3, 4, 0), listOf(1f, 1f, 1f, 0f), listOf(1, 1, 1, 0))
        }
        val badWeights = MediaTekAnimaNativeTokenizer { _, _, _ ->
            response(3, 4, listOf(1, 2, 0), listOf(1, 1, 0), listOf(2, 3, 4, 0), listOf(1f, 1f, 1f, 0.5f), listOf(1, 1, 1, 0))
        }

        assertTrue(runCatching { wrongLength.tokenize(File("."), textEncoder(3, 4), "p", 3, 4) }.isFailure)
        assertTrue(runCatching { badMask.tokenize(File("."), textEncoder(3, 4), "p", 3, 4) }.isFailure)
        assertTrue(runCatching { badWeights.tokenize(File("."), textEncoder(3, 4), "p", 3, 4) }.isFailure)
    }

    private fun textEncoder(
        qwenLength: Int,
        t5Length: Int,
        qwenProvider: String = "mca.qwen3-tokenizer"
    ) = MediaTekAnimaTextEncoderContract(
        graphs = emptyList(),
        qwenTokenizer = tokenizer(qwenLength, qwenProvider, MediaTekAnimaTokenWeighting.UNIFORM_ONE, 151643),
        t5Tokenizer = tokenizer(t5Length, "mca.anima-t5-tokenizer", MediaTekAnimaTokenWeighting.PROMPT_ATTENTION, 0),
        tokenIdsInput = "token_ids",
        attentionMaskInput = "attention_mask",
        embeddingsOutput = "embeddings"
    )

    private fun tokenizer(
        length: Int,
        provider: String,
        weighting: MediaTekAnimaTokenWeighting,
        padId: Int
) = MediaTekAnimaTokenizerContract(
        source = MediaTekTokenizerSource.RUNTIME_PROVIDER,
        asset = null,
        providerId = provider,
        sequenceLength = length,
        padTokenId = padId,
        paddingPolicy = MediaTekAnimaTokenizerPaddingPolicy.RIGHT_PAD,
        paddingWeight = 0f,
        weighting = weighting,
        truncationPolicy = MediaTekAnimaTokenTruncationPolicy.TRUNCATE_TAIL
    )

    private fun response(
        qwenLength: Int,
        t5Length: Int,
        qwenIds: List<Int>,
        qwenMask: List<Int>,
        t5Ids: List<Int>,
        t5Weights: List<Float>,
        t5Mask: List<Int>
    ) = JSONObject()
        .put("ok", true)
        .put("qwenTokenIds", JSONArray(qwenIds))
        .put("qwenAttentionMask", JSONArray(qwenMask))
        .put("t5TokenIds", JSONArray(t5Ids))
        .put("t5TokenWeights", JSONArray(t5Weights))
        .put("t5AttentionMask", JSONArray(t5Mask))
        .put("qwenSequenceLength", qwenLength)
        .put("t5SequenceLength", t5Length)
        .put("qwenPadding", "right")
        .put("t5Padding", "right")
        .put("qwenTruncation", "tail")
        .put("t5Truncation", "tail_with_eos")
        .toString()
}
