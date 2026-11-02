package com.muyuchat.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The canonical Hugging Face URL is the provenance/pin record; the transfer host is the app-wide
 * preferred mirror. Pins (repo, immutable revision, file path) must survive the rewrite verbatim,
 * because the model manifest's size/SHA-256 check is the only thing standing between a stale
 * mirror and a foreign weight file.
 */
class HuggingFaceTransferUrlTest {

    private val pinned =
        "https://huggingface.co/QuantStack/Wan2.2-TI2V-5B-GGUF/resolve/" +
            "57437632ddd08bdcbd1508c866aa22e126ed51d2/Wan2.2-TI2V-5B-Q4_0.gguf"

    @Test
    fun `a canonical resolve url keeps every pin and only swaps the front door`() {
        val transfer = huggingFacePreferredTransferUrl(pinned)
        assertTrue("expected the preferred mirror host, got $transfer", transfer.startsWith("https://hf-mirror.com/"))
        assertEquals(
            pinned.removePrefix("https://huggingface.co"),
            transfer.removePrefix("https://hf-mirror.com"),
        )
        assertTrue(transfer.contains("/resolve/57437632ddd08bdcbd1508c866aa22e126ed51d2/"))
    }

    @Test
    fun `a url that is not on huggingface is left untouched`() {
        val other = "https://modelscope.cn/models/owner/model/resolve/main/model.gguf"
        assertEquals(other, huggingFacePreferredTransferUrl(other))
    }

    @Test
    fun `hf co short link is rewritten too`() {
        val short = "https://hf.co/city96/umt5-xxl-encoder-gguf/resolve/" +
            "b535255bee98c2b0a59ea7c0ae2dcd0c6657b3b7/umt5-xxl-encoder-Q8_0.gguf"
        val transfer = huggingFacePreferredTransferUrl(short)
        assertTrue(transfer.startsWith("https://hf-mirror.com/city96/umt5-xxl-encoder-gguf/resolve/"))
        assertTrue(transfer.endsWith("/b535255bee98c2b0a59ea7c0ae2dcd0c6657b3b7/umt5-xxl-encoder-Q8_0.gguf"))
    }
}
